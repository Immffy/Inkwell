package com.inkwell.mode

/**
 * 一屏三模。
 *
 * 参考联想小新 10.1 彩墨柔光版的「原色 / 彩纸 / 墨纸」命名逻辑：
 * 按**内容**分档，而不是按电子纸面板型号分档。
 *
 * 为什么这个改法比原来六个预设好：
 *   原来叫 Kaleido 3 / Gallery 3 / Spectra 6，用户（尤其是不玩数码的）
 *   根本不知道该点哪一个。联想用三档就覆盖全场景，说明三档够用。
 *   细分档位收进「进阶」，不删但也不占主界面。
 *
 * 三档全部仍是普通 LCD 上的软件调色，不是电子墨水屏：
 *   - AG 防眩光是硬件蚀刻，软件做不到
 *   - 纸纹 / 颗粒感是非线性，色彩矩阵做不到
 */
object DisplayMode {

    const val ORIGINAL = "原色模式"
    const val COLOR_PAPER = "彩纸模式"
    const val INK_PAPER = "墨纸模式"

    /**
     * 自定义（用户拖过滑块或选了进阶档位）。
     * 注意不能用空串 —— 空串要留给「从未设置过」，用于从旧版迁移。
     */
    const val CUSTOM = "自定义参数"

    val ALL = listOf(ORIGINAL, COLOR_PAPER, INK_PAPER)

    fun params(mode: String): EinkParams = when (mode) {
        ORIGINAL -> EinkParams.OFF.copy(warmth = 0f)
        COLOR_PAPER -> EinkParams.KALEIDO3.copy(warmth = 0.55f)
        INK_PAPER -> EinkParams.PAPER_BW.copy(warmth = 1.0f)
        else -> EinkParams.KALEIDO3
    }

    /** 该模式是否等于「关闭效果」 */
    fun isOff(mode: String): Boolean = mode == ORIGINAL

    /** 适合看什么 —— 主按钮下方的说明文字 */
    fun usage(mode: String): String = when (mode) {
        ORIGINAL ->
            "动画课程 · 绘画上色 · 照片视频\n" +
            "标准色彩，不做任何处理。看照片、修图、刷视频时用。"

        COLOR_PAPER ->
            "故事绘本 · 科普启蒙 · 漫画\n" +
            "压低高饱和、柔化刺眼高光，像彩色铜版纸绘本。保留颜色，但不刺眼。"

        INK_PAPER ->
            "古典诗词 · 外语短文 · 小说 PDF\n" +
            "转为黑白灰阶，底色是柔和米白而非纯白，文字对比清晰，适合长时间阅读。"

        else -> "自定义参数"
    }

    fun numbers(mode: String): String {
        val p = params(mode)
        return "饱和 %.2f · 黑位 %.2f · 白位 %.2f · 暖度 %.2f".format(
            p.saturation, p.black, p.white, p.warmth
        )
    }

    /**
     * 老预设名 → 三模归类。
     * 用于把 0.2.1 及更早版本的用户选择平滑迁移到新界面。
     */
    fun fromLegacyPreset(name: String): String = when {
        name.contains("关闭") || name.contains("原色") -> ORIGINAL
        name.contains("黑白") -> INK_PAPER
        else -> COLOR_PAPER
    }
}
