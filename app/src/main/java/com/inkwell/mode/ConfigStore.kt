package com.inkwell.mode

import android.content.Context
import android.content.Intent
import java.io.DataOutputStream
import java.io.File

/**
 * App 侧下发配置（0.2.2 重写）。
 *
 * ── 三条通道，按可靠性排序 ──
 *
 * ① 广播（root-free，即时，主通道）
 *    去掉 0.2.2 的 setPackage(ctx.packageName) —— 那行把广播限制成
 *    "只发给墨境自己"，而接收器是模块注册在 system_server 的动态 receiver，
 *    包名是 android，永远收不到。动态注册不受 Android 8+ 隐式广播限制，
 *    去掉后广播直达 system_server。
 *
 * ② setprop（需 root，负责重启后保持）
 *    su -c "setprop persist.inkwell.*"。SystemProperties 全局可读，
 *    system_server 直接读，没有 SELinux 路径问题。
 *
 * ③ 文件（需 root，兼容性备份）
 *    /data/system/inkwell.conf。App 进程在 Android 16 的 SELinux 下
 *    **写不了 /data/local/tmp 和 /data/system**，所以必须由 su 写。
 *    直接写失败是预期行为，不再当成错误报给用户。
 *
 * ②③ 合并进同一次 su 会话，避免一次点击起好几个 su 进程卡 UI。
 * 只要 ① 成功，开关就能用；有 root 时 ② 让它重启后也保持。
 */
object ConfigStore {

    /** 最近一次实际使用的引擎，供 UI 显示 */
    @Volatile
    var lastKind: EngineKind = EngineKind.NONE
        private set

    fun saveAndApply(ctx: Context, p: EinkParams, preset: String, enabled: Boolean): Result {
        EinkPrefs.save(ctx, p)
        EinkPrefs.setPreset(ctx, preset)
        EinkPrefs.setEnabled(ctx, enabled)

        val mode = EinkPrefs.getMode(ctx)
        val rules = EinkPrefs.getAppRuleModes(ctx)
        val bw = rules.keys
        val text = ControlContract.serialize(enabled, p, preset, bw, mode, rules)

        // ① 广播：root-free 且即时。这是判断"指令有没有送出去"的主要依据。
        val broadcastOk = sendBroadcast(ctx, p, preset, mode, rules, enabled)

        // ② 直接写：Android 16 上多半被 SELinux 拒绝，失败属正常
        val fileOk = writeConfFile(text)

        // ③ 一次 su 会话完成 setprop + 配置文件 + 规则文件（有 root 才成功）
        val suOk = persistViaSu(p, preset, mode, enabled, text, rules)

        // 非 root 机器：交给 Shizuku；遮罩默认关闭，不再自动叠层
        val r = EinkEngine.apply(ctx, p, enabled)
        lastKind = r.kind

        // 广播成功就算指令已送达，不再因为文件写不了而谎报"失败"
        return Result(broadcastOk || fileOk || suOk, suOk || r.shizukuOk, text)
    }

    /** 仅切换开关，参数保持上次的值 */
    fun setEnabled(ctx: Context, enabled: Boolean): Result {
        // 从「原色模式」重新开启时，不能沿用 OFF 参数 —— 否则用户会看到
        // "开关显示已开启，屏幕却没变化"。自动回到彩纸档。
        if (enabled && EinkPrefs.getMode(ctx) == DisplayMode.ORIGINAL) {
            EinkPrefs.setMode(ctx, DisplayMode.COLOR_PAPER)
            EinkPrefs.save(ctx, DisplayMode.params(DisplayMode.COLOR_PAPER))
            EinkPrefs.setPreset(ctx, DisplayMode.COLOR_PAPER)
        }
        val p = EinkPrefs.load(ctx)
        val preset = EinkPrefs.getPreset(ctx)
        return saveAndApply(ctx, p, preset, enabled)
    }

    /**
     * 即时广播，让模块不用等轮询。
     *
     * ⚠️ 不要加 setPackage —— 接收器在 system_server，包名是 android。
     * 动态注册的 receiver 不受 Android 8+ 隐式广播限制，全局广播即可送达。
     */
    private fun sendBroadcast(
        ctx: Context,
        p: EinkParams,
        preset: String,
        mode: String,
        rules: Map<String, String>,
        enabled: Boolean
    ): Boolean {
        return try {
            val i = Intent(ControlContract.ACTION_APPLY).apply {
                putExtra(ControlContract.EXTRA_ENABLED, enabled)
                putExtra(ControlContract.EXTRA_SAT, p.saturation)
                putExtra(ControlContract.EXTRA_BLACK, p.black)
                putExtra(ControlContract.EXTRA_WHITE, p.white)
                putExtra(ControlContract.EXTRA_WARMTH, p.warmth)
                putExtra(ControlContract.EXTRA_PRESET, preset)
                putExtra(ControlContract.EXTRA_MODE, mode)
                putExtra(ControlContract.EXTRA_RULES, ControlContract.serializeRules(rules))
            }
            // 不带任何 flag 的隐式广播：动态注册的 receiver 不受 Android 8+ 限制，
            // 也是各 ROM 兼容性最好的形式。
            ctx.sendBroadcast(i)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 直接写文件。Android 16 上普通 app 进程对 /data/system 无写权限，
     * 失败是预期行为，由 persistViaSu 兜底。
     */
    private fun writeConfFile(text: String): Boolean {
        return try {
            val f = File(ControlContract.CONF_FILE)
            f.parentFile?.mkdirs()
            f.writeText(text)
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "644", f.absolutePath)).waitFor()
            } catch (_: Throwable) { }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 一次 su 会话写完所有持久化内容。
     * 用 quoted heredoc，配置里的中文 / 括号 / 分号都原样保留。
     */
    private fun persistViaSu(
        p: EinkParams,
        preset: String,
        mode: String,
        enabled: Boolean,
        text: String,
        rules: Map<String, String>
    ): Boolean = suShell { os ->
        // setprop：system_server 直接可读，重启保持
        os.writeBytes("setprop persist.inkwell.enabled ${if (enabled) 1 else 0}\n")
        os.writeBytes("setprop persist.inkwell.sat %.4f\n".format(p.saturation))
        os.writeBytes("setprop persist.inkwell.black %.4f\n".format(p.black))
        os.writeBytes("setprop persist.inkwell.white %.4f\n".format(p.white))
        os.writeBytes("setprop persist.inkwell.warmth %.4f\n".format(p.warmth))
        os.writeBytes("setprop persist.inkwell.preset \"$preset\"\n")
        os.writeBytes("setprop persist.inkwell.mode \"$mode\"\n")

        // 配置文件
        os.writeBytes("cat > ${ControlContract.CONF_FILE} <<'INKWELL_CONF_EOF'\n")
        os.writeBytes(text)
        os.writeBytes("INKWELL_CONF_EOF\n")
        os.writeBytes("chmod 644 ${ControlContract.CONF_FILE}\n")

        // 按 App 规则
        if (rules.isNotEmpty()) {
            os.writeBytes("cat > ${ControlContract.RULES_FILE} <<'INKWELL_RULES_EOF'\n")
            os.writeBytes(ControlContract.serializeRules(rules))
            os.writeBytes("\nINKWELL_RULES_EOF\n")
            os.writeBytes("chmod 644 ${ControlContract.RULES_FILE}\n")
        }
    }

    fun hasRoot(): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 探测模块激活标记（模块写在 /data/system，App 需经 su 读）。
     * 必须直接取 test 的退出码——不能走 suShell，因为那会在末尾补 exit 0，
     * 把 test 的结果冲掉。
     */
    fun probeModuleMark(): Boolean {
        if (!hasRoot()) return false
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf(
                "su", "-c",
                "test -f ${ControlContract.ACTIVE_MARK} || test -f ${ControlContract.LEGACY_ACTIVE_MARK}"
            ))
            proc.waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    /** 按 App 指定三模之一；保存后立刻重新下发（广播即时 + su 持久化） */
    fun saveAppRuleModes(ctx: Context, map: Map<String, String>): Boolean {
        EinkPrefs.putAppRuleModes(ctx, map)
        saveAndApply(
            ctx, EinkPrefs.load(ctx), EinkPrefs.getPreset(ctx), EinkPrefs.isEnabled(ctx)
        )
        return true
    }

    fun saveBwApps(ctx: Context, pkgs: Set<String>): Boolean {
        EinkPrefs.putAppRuleRaw(ctx, pkgs)
        return saveAppRuleModes(ctx, EinkPrefs.getAppRuleModes(ctx))
    }

    fun loadBwAppsLocal(ctx: Context): Set<String> = EinkPrefs.getAppRulesSet(ctx)

    /** 诊断用：直接下发矩阵，验证 SurfaceFlinger 侧可用性 */
    fun testRawMatrix(): Boolean {
        val m = ColorMatrixBuilder.build(EinkParams.KALEIDO3)
        val sb = StringBuilder("service call SurfaceFlinger 1015 i32 1")
        for (v in m) sb.append(" f ").append("%.4f".format(v))
        return suShell { os -> os.writeBytes(sb.toString() + "\n") }
    }

    fun resetRawMatrix(): Boolean = suShell { os ->
        os.writeBytes("service call SurfaceFlinger 1015 i32 0\n")
    }

    private fun suShell(block: (DataOutputStream) -> Unit): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(proc.outputStream)
            block(os)
            os.writeBytes("exit\n"); os.flush()
            proc.waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    data class Result(val fileOk: Boolean, val propOk: Boolean, val text: String)
}
