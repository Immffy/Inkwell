package com.inkwell.mode

import android.content.Context

/**
 * 引擎能力等级。
 *
 * 决定因素只有一个：能不能碰到 SurfaceFlinger 的色彩矩阵。
 * 碰得到就是完整效果，碰不到只能叠加窗口。
 */
enum class EngineKind {
    /** LSPosed 注入 system_server，开机自启，最省心 */
    LSPOSED,

    /** Shizuku 提供 adb shell 权限，效果等同 LSPosed，但重启后需重新启用一次 */
    SHIZUKU,

    /** 零依赖遮罩：叠加半透明窗口。只能"加色"，无法提对比度 */
    OVERLAY,

    /** 什么都不通 */
    NONE
}

object EngineInfo {

    /** 是否能真正操作色彩矩阵（即完整效果） */
    fun isFull(kind: EngineKind): Boolean =
        kind == EngineKind.LSPOSED || kind == EngineKind.SHIZUKU

    fun label(kind: EngineKind): String = when (kind) {
        EngineKind.LSPOSED -> "LSPosed（完整效果）"
        EngineKind.SHIZUKU -> "Shizuku（完整效果）"
        EngineKind.OVERLAY -> "遮罩模式（降级）"
        EngineKind.NONE    -> "无可用通道"
    }

    /**
     * 各引擎的能力说明。
     * 遮罩那栏必须写清楚局限——含糊过去只会让人以为 App 有问题。
     */
    fun description(kind: EngineKind): String = when (kind) {
        EngineKind.LSPOSED ->
            "在系统进程注入全局色彩矩阵。\n" +
            "零延迟、零耗电、覆盖状态栏和全部界面，开机自动生效。"

        EngineKind.SHIZUKU ->
            "通过 Shizuku 获得 adb shell 权限后下发同样的色彩矩阵。\n" +
            "效果与 LSPosed 版完全一致。\n" +
            "代价：手机重启后需重新启用一次（开发者选项的无线调试开关重启会关）。"

        EngineKind.OVERLAY ->
            "叠加一层半透明窗口模拟纸感。\n" +
            "⚠️ 局限：alpha 混合只能往画面上「加色」，数学上无法提高对比度，\n" +
            "也无法把画面变成灰阶。所以观感偏淡、发灰，状态栏还覆盖不到。\n\n" +
            "⚠️ 三模在这条通道上会退化：原色=不叠层，彩纸=淡暖色，\n" +
            "墨纸=更浓的米黄，但**不会变成黑白**。这不是 App 坏了，\n" +
            "是普通应用拿不到系统级色彩矩阵。\n\n" +
            "装一个 Shizuku 即可自动升级到完整效果，无需换版本。"

        EngineKind.NONE ->
            "没有可用通道。\n" +
            "遮罩模式需要悬浮窗权限，可在下方按钮前往开启。"
    }

    /** 是否需要在 UI 上提示"可以升级到完整效果" */
    fun canUpgrade(kind: EngineKind): Boolean = kind == EngineKind.OVERLAY || kind == EngineKind.NONE
}
