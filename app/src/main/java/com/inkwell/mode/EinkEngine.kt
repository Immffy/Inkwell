package com.inkwell.mode

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * 统一执行入口：按可用通道自动分发。
 *
 * 优先级（能力从强到弱）：
 *   ① LSPosed  —— 开机自启、覆盖最全
 *   ② Shizuku  —— 效果等同 ①，但重启后需重新启用一次
 *   ③ 遮罩      —— 零依赖，观感降级
 *
 * 同一个 APK 在 root / 非 root 机器上都能用，靠的就是这里的自动选择。
 * 用户不需要理解区别，App 自己挑最好的那条路。
 */
object EinkEngine {

    /**
     * 检测当前可用引擎。
     *
     * LSPosed 是否激活的判断：模块激活探测器需要模块侧配合，
     * 这里用「HookEntry 是否写入过标记」间接判断；
     * 检测不到不代表没生效（可能只是作用域没勾本 App），
     * 所以 LSPosed 激活与否不影响 Shizuku 的判断 —— 两者可共存。
     */
    fun detect(ctx: Context): EngineKind {
        ShizukuEngine.refresh()
        if (ShizukuEngine.available && ShizukuEngine.granted) return EngineKind.SHIZUKU
        if (ShizukuEngine.available) return EngineKind.SHIZUKU   // 未授权也走它，UI 会提示授权
        if (OverlayService.hasPermission(ctx)) return EngineKind.OVERLAY
        return EngineKind.NONE
    }

    /**
     * 真正生效的引擎。
     *
     * 注意：LSPosed 通道由模块侧轮询 /data/local/tmp/inkwell.conf 生效，
     * 不经过这里。所以这里只需保证「LSPosed 没接管时」还有别的路可走。
     */
    fun apply(ctx: Context, p: EinkParams, enabled: Boolean): ApplyResult {
        // ① 文件通道：LSPosed 模块会读它。无 root 也能写，且重启后保持。
        val fileOk = writeConf(ctx, p, enabled)

        // ② Shizuku：有则立刻下发（不等模块轮询）
        var shizukuOk = false
        ShizukuEngine.refresh()
        if (ShizukuEngine.available && ShizukuEngine.granted) {
            shizukuOk = ShizukuEngine.apply(p, enabled)
        }

        // ③ 遮罩：只有当 Shizuku 不可用时才启用，避免两层叠加
        var overlay = false
        if (!shizukuOk && !isLikelyLsposed()) {
            if (OverlayService.hasPermission(ctx)) {
                if (enabled) OverlayService.start(ctx, p, true) else OverlayService.stop(ctx)
                overlay = enabled
            }
        }

        return ApplyResult(
            fileOk = fileOk,
            shizukuOk = shizukuOk,
            overlayShown = overlay,
            kind = detect(ctx)
        )
    }

    /**
     * 判断是否有 LSPosed 模块在接管。
     *
     * 模块激活探测器会写 /data/local/tmp/inkwell.module.active，
     * 若存在说明模块侧活着，此时不能再叠遮罩（会双重叠加）。
     */
    private fun isLikelyLsposed(): Boolean {
        return try {
            java.io.File("/data/local/tmp/inkwell.module.active").exists()
        } catch (_: Throwable) {
            false
        }
    }

    private fun writeConf(ctx: Context, p: EinkParams, enabled: Boolean): Boolean {
        EinkPrefs.save(ctx, p)
        EinkPrefs.setEnabled(ctx, enabled)
        val preset = EinkPrefs.getPreset(ctx)
        val bw = EinkPrefs.getAppRulesSet(ctx)
        val mode = EinkPrefs.getMode(ctx)
        val rules = EinkPrefs.getAppRuleModes(ctx)
        val text = ControlContract.serialize(enabled, p, preset, bw, mode, rules)
        return try {
            val f = java.io.File(ControlContract.CONF_FILE)
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

    /** 打开悬浮窗权限设置页（遮罩模式需要） */
    fun openOverlaySettings(ctx: Context) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:${ctx.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        } catch (_: Throwable) { }
    }

    data class ApplyResult(
        val fileOk: Boolean,
        val shizukuOk: Boolean,
        val overlayShown: Boolean,
        val kind: EngineKind
    )
}
