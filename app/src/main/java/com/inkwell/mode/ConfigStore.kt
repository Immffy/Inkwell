package com.inkwell.mode

import android.content.Context
import android.content.Intent
import java.io.DataOutputStream
import java.io.File

/**
 * App 侧下发配置。
 *
 * 三条通道按优先级同时写（能写几条写几条，互不依赖）：
 *
 *   ① 文件  —— root-free 主通道，system_server 可读，且重启后仍在
 *   ② 广播  —— root-free 即时通道，让模块立刻生效，不用等 2 秒轮询
 *   ③ setprop —— 需 root，有则写（开机更早生效），没有也完全不影响
 *
 * 只要 ① 成功，功能就是完整可用的。它不需要任何权限。
 */
object ConfigStore {

    /** 当前是否开启效果（App 侧本地记录，同时写进文件供模块读取） */
    fun saveAndApply(ctx: Context, p: EinkParams, preset: String, enabled: Boolean): Result {
        EinkPrefs.save(ctx, p)
        EinkPrefs.setPreset(ctx, preset)
        EinkPrefs.setEnabled(ctx, enabled)

        val bw = loadBwAppsLocal(ctx)
        val text = ControlContract.serialize(enabled, p, preset, bw)

        val fileOk = writeConfFile(text)
        sendBroadcast(ctx, p, preset, enabled)
        val propOk = if (hasRoot()) applyProps(p, enabled) else false

        return Result(fileOk, propOk, text)
    }

    /** 仅切换开关，参数保持上次的值 */
    fun setEnabled(ctx: Context, enabled: Boolean): Result {
        val p = EinkPrefs.load(ctx)
        val preset = EinkPrefs.getPreset(ctx)
        return saveAndApply(ctx, p, preset, enabled)
    }

    /**
     * 写配置文件。不需要 root —— App 可写 /data/local/tmp，
     * chmod 644 后 system_server（uid 1000）可读。
     */
    private fun writeConfFile(text: String): Boolean {
        return try {
            val f = File(ControlContract.CONF_FILE)
            f.parentFile?.mkdirs()
            f.writeText(text)
            // 确保其他进程可读
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "644", f.absolutePath)).waitFor()
            } catch (_: Throwable) { /* chmod 失败也能用：文件默认权限通常已可读 */ }
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** 即时广播，让模块不用等轮询 */
    private fun sendBroadcast(ctx: Context, p: EinkParams, preset: String, enabled: Boolean) {
        try {
            val i = Intent(ControlContract.ACTION_APPLY).apply {
                putExtra(ControlContract.EXTRA_ENABLED, enabled)
                putExtra(ControlContract.EXTRA_SAT, p.saturation)
                putExtra(ControlContract.EXTRA_BLACK, p.black)
                putExtra(ControlContract.EXTRA_WHITE, p.white)
                putExtra(ControlContract.EXTRA_PRESET, preset)
                setPackage(ctx.packageName)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
            }
            ctx.sendBroadcast(i)
        } catch (_: Throwable) { /* 广播失败有文件通道兜底 */ }
    }

    /** root 通道（可选）：写入 persist 属性，开机更早生效 */
    private fun applyProps(p: EinkParams, enabled: Boolean): Boolean {
        val cmds = mutableListOf<String>()
        cmds += "setprop persist.inkwell.enabled ${if (enabled) 1 else 0}"
        if (enabled) {
            cmds += "setprop persist.inkwell.sat %.4f".format(p.saturation)
            cmds += "setprop persist.inkwell.black %.4f".format(p.black)
            cmds += "setprop persist.inkwell.white %.4f".format(p.white)
        }
        return try {
            val proc = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(proc.outputStream)
            for (c in cmds) { os.writeBytes(c + "\n"); os.flush() }
            os.writeBytes("exit\n"); os.flush()
            proc.waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    fun hasRoot(): Boolean {
        return try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "id")).waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    /** 黑白名单：本地记录 + 写文件（供模块读） */
    fun saveBwApps(ctx: Context, pkgs: Set<String>): Boolean {
        EinkPrefs.putAppRuleRaw(ctx, pkgs)
        return try {
            val f = File(ControlContract.BW_APPS_FILE)
            f.writeText(pkgs.joinToString("\n"))
            try {
                Runtime.getRuntime().exec(arrayOf("chmod", "644", f.absolutePath)).waitFor()
            } catch (_: Throwable) {}
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun loadBwAppsLocal(ctx: Context): Set<String> = EinkPrefs.getAppRulesSet(ctx)

    /** 诊断用：直接下发矩阵，验证 SurfaceFlinger 侧可用性 */
    fun testRawMatrix(): Boolean {
        val m = ColorMatrixBuilder.build(EinkParams.KALEIDO3)
        val sb = StringBuilder("service call SurfaceFlinger 1015 i32 1")
        for (v in m) sb.append(" f ").append("%.4f".format(v))
        return runSu(listOf(sb.toString()))
    }

    fun resetRawMatrix(): Boolean = runSu(listOf("service call SurfaceFlinger 1015 i32 0"))

    private fun runSu(cmds: List<String>): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(proc.outputStream)
            for (c in cmds) { os.writeBytes(c + "\n"); os.flush() }
            os.writeBytes("exit\n"); os.flush()
            proc.waitFor() == 0
        } catch (_: Throwable) {
            false
        }
    }

    data class Result(val fileOk: Boolean, val propOk: Boolean, val text: String)
}
