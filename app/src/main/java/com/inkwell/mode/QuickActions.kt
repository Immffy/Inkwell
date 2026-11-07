package com.inkwell.mode

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService

/**
 * 快捷动作的统一定义与执行入口。
 *
 * 桌面快捷按钮、长按图标快捷方式、通知栏 Action、QS 磁贴
 * —— 四条入口最终都汇总到这里，保证行为完全一致。
 *
 * 设计要点：**不打开任何界面**。点一下 → 立刻下发广播 → 完事。
 * 这是「像护眼模式一样点一下就变」的关键。
 *
 * 0.2.3 修正：全部下发挪到单线程后台执行。
 * ConfigStore 里的 `su` 持久化会 waitFor()，若 Magisk / KernelSU 弹授权框，
 * 同步调用会卡住调用方主线程 —— QS 磁贴的 onClick() 运行在
 * SystemUI 绑定的线程上，卡住整个控制中心会跟着顿。
 * 现在 UI 先本地翻转状态即时反馈，指令在后台慢慢送，互不阻塞。
 */
object QuickActions {

    /** 串行执行，避免用户连点时多个 su 会话互相抢授权弹窗 */
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "inkwell-quick").apply { isDaemon = true }
    }

    const val ACTION = "com.inkwell.mode.QUICK_ACTION"
    const val EXTRA = "inkwell_action"

    const val TOGGLE = "toggle"
    const val ORIGINAL = "original"
    const val COLOR_PAPER = "color_paper"
    const val INK_PAPER = "ink_paper"
    const val RESTORE = "restore"

    /** 顺序即通知栏 / 桌面按钮的展示顺序 */
    val ALL = listOf(ORIGINAL, COLOR_PAPER, INK_PAPER, TOGGLE)

    fun shortLabel(id: String): String = when (id) {
        ORIGINAL -> "原色"
        COLOR_PAPER -> "彩纸"
        INK_PAPER -> "墨纸"
        RESTORE -> "恢复默认"
        else -> "开/关"
    }

    fun longLabel(id: String): String = when (id) {
        ORIGINAL -> "恢复默认显示（原色）"
        COLOR_PAPER -> "切换到彩纸模式"
        INK_PAPER -> "切换到墨纸模式"
        RESTORE -> "恢复默认显示"
        else -> "开启 / 关闭墨水屏"
    }

    fun icon(id: String): Int = when (id) {
        ORIGINAL, RESTORE -> R.drawable.ic_quick_original
        COLOR_PAPER -> R.drawable.ic_quick_color
        INK_PAPER -> R.drawable.ic_quick_ink
        else -> R.drawable.ic_quick_toggle
    }

    fun modeOf(id: String): String? = when (id) {
        ORIGINAL, RESTORE -> DisplayMode.ORIGINAL
        COLOR_PAPER -> DisplayMode.COLOR_PAPER
        INK_PAPER -> DisplayMode.INK_PAPER
        else -> null
    }

    /** 执行一个快捷动作。可在无任何 UI 的上下文中调用。 */
    fun execute(ctx: Context, id: String) {
        val app = ctx.applicationContext
        val mode = modeOf(id)
        if (mode == null) {
            setEnabled(app, !EinkPrefs.isEnabled(app))
            return
        }
        worker.execute {
            try { applyMode(app, mode) } catch (_: Throwable) { }
            refresh(app)
        }
    }

    /** 显式指定目标开关状态（QS 磁贴先本地翻转、再后台下发时用） */
    fun setEnabled(ctx: Context, enabled: Boolean) {
        val app = ctx.applicationContext
        worker.execute {
            try { ConfigStore.setEnabled(app, enabled) } catch (_: Throwable) { }
            refresh(app)
        }
    }

    private fun applyMode(ctx: Context, mode: String) {
        val p = DisplayMode.params(mode)
        val enabled = !DisplayMode.isOff(mode)
        EinkPrefs.setMode(ctx, mode)
        EinkPrefs.save(ctx, p)
        EinkPrefs.setPreset(ctx, mode)
        ConfigStore.saveAndApply(ctx, p, mode, enabled)
    }

    /** 动作执行后，把磁贴和常驻通知的状态一起刷新 */
    fun refresh(ctx: Context) {
        try {
            TileService.requestListeningState(
                ctx, ComponentName(ctx, EinkTileService::class.java)
            )
        } catch (_: Throwable) { }
        try {
            QuickNotify.sync(ctx)
        } catch (_: Throwable) { }
    }
}
