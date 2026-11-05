package com.inkwell.mode

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 统一执行入口：按可用通道自动分发（0.2.2 修正）。
 *
 * ── 修正了什么 ──
 *
 * 0.2.2 的 detect() 只判断 Shizuku 和悬浮窗权限，从未返回 LSPOSED。
 * 于是只要没装 Shizuku、而 App 恰好有悬浮窗权限，就会被判成「遮罩模式（降级）」
 * 并自动叠一层窗口 —— 哪怕用户已经 root、LSPosed 模块正在 system_server 里正常工作。
 * 这正是"点了开启却给我开遮罩"的原因。
 *
 * 现在：
 *   · 优先识别 LSPosed（模块自检 hook / 激活标记）
 *   · 遮罩改为**默认关闭**，只有用户在诊断页显式打开才会启用
 *   · 配置下发由 ConfigStore 负责，这里不再重复写文件
 */
object EinkEngine {

    /** detect() 的结果缓存，避免在 UI 线程重复起 su 进程 */
    @Volatile
    private var cachedKind: EngineKind = EngineKind.NONE

    fun detect(ctx: Context): EngineKind {
        val k = detectSlow(ctx)
        cachedKind = k
        return k
    }

    private fun detectSlow(ctx: Context): EngineKind {
        ShizukuEngine.refresh()
        if (ShizukuEngine.available) return EngineKind.SHIZUKU
        if (isLikelyLsposed()) return EngineKind.LSPOSED
        if (EinkPrefs.isOverlayEnabled(ctx) && OverlayService.hasPermission(ctx)) {
            return EngineKind.OVERLAY
        }
        return EngineKind.NONE
    }

    /**
     * 真正生效的引擎动作。
     *
     * 注意：文件/广播/setprop 三条通道都在 ConfigStore 里写好了，
     * LSPosed 模块自己会读；这里只负责「没有系统级通道时」的补充。
     */
    fun apply(ctx: Context, p: EinkParams, enabled: Boolean): ApplyResult {
        var shizukuOk = false
        ShizukuEngine.refresh()
        if (ShizukuEngine.available && ShizukuEngine.granted) {
            shizukuOk = ShizukuEngine.apply(p, enabled)
        }

        // 遮罩：必须同时满足「用户显式开启」「不是系统级通道」「无 Shizuku」。
        // 默认关闭 —— 已 root / 已装 LSPosed 的机器绝不会莫名叠一层窗口。
        var overlay = false
        if (!shizukuOk && !isLikelyLsposedFast() &&
            EinkPrefs.isOverlayEnabled(ctx) && OverlayService.hasPermission(ctx)) {
            if (enabled) OverlayService.start(ctx, p, true) else OverlayService.stop(ctx)
            overlay = enabled
        }

        return ApplyResult(
            fileOk = true,  // 由 ConfigStore 负责，这里不重复写
            shizukuOk = shizukuOk,
            overlayShown = overlay,
            kind = cachedKind
        )
    }

    /** 完整判定（含 su 探测），只在后台线程调用 */
    private fun isLikelyLsposed(): Boolean =
        EinkPrefs.isModuleActive() || ConfigStore.probeModuleMark()

    /** 快速判定，UI 线程安全（不启动进程） */
    private fun isLikelyLsposedFast(): Boolean = EinkPrefs.isModuleActive()

    /** 打开悬浮窗权限设置页（遮罩模式需要） */
    fun openOverlaySettings(ctx: Context) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                ctx.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${ctx.packageName}")
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
