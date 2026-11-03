package com.inkwell.mode

import android.content.Context
import android.content.SharedPreferences

/**
 * 渲染参数。
 *
 * 默认值按 Kaleido 3 实测特性设定：
 *   饱和度 0.45（约为 sRGB 的四到六成）
 *   黑位 0.16 / 白位 0.92（对比度约 20-30:1，对应真机 28:1 @1000lux）
 *
 * 注意：LSPosed 模块走系统色彩矩阵，只能做「线性变换」——
 * 即饱和度 / 对比 / 白黑位 / 通道增益。
 * 量化、抖动、滤光网格、纸纹都是非线性操作，矩阵做不了，
 * 那是 MediaProjection + shader 方案的活。
 */
data class EinkParams(
    val saturation: Float = 0.45f,
    val black: Float = 0.16f,
    val white: Float = 0.92f,
    /**
     * 纸色暖度 0..1。0 = 中性灰白，1 = 明显米黄纸。
     *
     * 联想「墨纸模式」宣传里那句"底色是柔和米白，不是刺眼纯白"就是靠这个：
     * 给矩阵第 4 列的 R/G/B 三个通道不同的常量偏移，让整幅画面往暖色平移。
     * 这是 4×4 矩阵天然能做的线性操作，不需要 shader。
     */
    val warmth: Float = 0f,
    val levels: Float = 16f,      // 仅 shader 方案使用
    val grain: Float = 0.06f,     // 仅 shader 方案使用
    val cfa: Float = 0.35f,       // 仅 shader 方案使用
    val cellPx: Float = 3.0f      // 仅 shader 方案使用
) {
    companion object {
        // 矩阵方案能达到的最接近效果
        val KALEIDO3 = EinkParams(0.45f, 0.16f, 0.92f)
        val GALLERY3 = EinkParams(0.62f, 0.12f, 0.95f)
        val COMIC    = EinkParams(0.50f, 0.08f, 0.94f)
        val SPECTRA6 = EinkParams(0.55f, 0.14f, 0.94f)
        // 黑白电子纸无滤光片吃光，比彩色档更亮、对比更强；纸底偏米白
        val PAPER_BW = EinkParams(0.00f, 0.08f, 0.96f, warmth = 0.85f)
        val OFF      = EinkParams(1.00f, 0.00f, 1.00f)

        fun presetList() = linkedMapOf(
            "Kaleido 3（默认）" to KALEIDO3,
            "Gallery 3（更艳）" to GALLERY3,
            "漫画优化" to COMIC,
            "黑白纸质" to PAPER_BW,
            "Spectra 6（印刷感）" to SPECTRA6,
            "关闭效果（原色）" to OFF
        )

        fun byName(name: String): EinkParams =
            presetList()[name] ?: KALEIDO3
    }
}

object EinkPrefs {
    private const val FILE = "inkwell_params"

    const val K_SAT = "sat"
    const val K_BLACK = "black"
    const val K_WHITE = "white"
    const val K_WARMTH = "warmth"
    const val K_LEVELS = "levels"
    const val K_GRAIN = "grain"
    const val K_CFA = "cfa"
    const val K_CELL = "cell"
    const val K_ENABLED = "enabled"
    const val K_PRESET = "preset"

    /** 当前三模（原色 / 彩纸 / 墨纸）；空串表示自定义参数 */
    const val K_MODE = "mode"

    /** 按 App 包名配置的显示模式，格式 pkg:mode;pkg:mode */
    const val K_APP_RULES = "app_rules"

    const val DEFAULT_PRESET = "Kaleido 3（默认）"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context): EinkParams {
        val p = prefs(ctx)
        return EinkParams(
            saturation = p.getFloat(K_SAT, EinkParams.KALEIDO3.saturation),
            black = p.getFloat(K_BLACK, EinkParams.KALEIDO3.black),
            white = p.getFloat(K_WHITE, EinkParams.KALEIDO3.white),
            warmth = p.getFloat(K_WARMTH, EinkParams.KALEIDO3.warmth),
            levels = p.getFloat(K_LEVELS, EinkParams.KALEIDO3.levels),
            grain = p.getFloat(K_GRAIN, EinkParams.KALEIDO3.grain),
            cfa = p.getFloat(K_CFA, EinkParams.KALEIDO3.cfa),
            cellPx = p.getFloat(K_CELL, EinkParams.KALEIDO3.cellPx)
        )
    }

    fun save(ctx: Context, v: EinkParams) {
        prefs(ctx).edit().apply {
            putFloat(K_SAT, v.saturation)
            putFloat(K_BLACK, v.black)
            putFloat(K_WHITE, v.white)
            putFloat(K_WARMTH, v.warmth)
            putFloat(K_LEVELS, v.levels)
            putFloat(K_GRAIN, v.grain)
            putFloat(K_CFA, v.cfa)
            putFloat(K_CELL, v.cellPx)
            apply()
        }
    }

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(K_ENABLED, true)
    fun setEnabled(ctx: Context, on: Boolean) =
        prefs(ctx).edit().putBoolean(K_ENABLED, on).apply()

    /**
     * 返回非空 String。
     *
     * ⚠️ 不要写成 `= prefs(ctx).getString(K_PRESET, DEF)` —— SharedPreferences.getString
     * 返回的是 String?，Kotlin 会推断出可空类型，调用方传入 String 形参时直接编译报错：
     *   "Type mismatch: inferred type is String? but String was expected"
     * 必须显式标注 : String 并用 ?: 兜底。
     */
    fun getPreset(ctx: Context): String =
        prefs(ctx).getString(K_PRESET, DEFAULT_PRESET) ?: DEFAULT_PRESET
    fun setPreset(ctx: Context, name: String) =
        prefs(ctx).edit().putString(K_PRESET, name).apply()

    /** 当前三模；空串 = 用户调过滑块，处于自定义状态 */
    fun getMode(ctx: Context): String =
        prefs(ctx).getString(K_MODE, "") ?: ""
    fun setMode(ctx: Context, mode: String) =
        prefs(ctx).edit().putString(K_MODE, mode).apply()

    /**
     * 按 App 指定显示模式。
     *
     * 存储格式 `pkg:mode;pkg:mode`。旧版只存包名（无冒号），
     * 这里自动按「墨纸模式」解释 —— 已存的名单升级后不会丢。
     */
    fun getAppRuleModes(ctx: Context): Map<String, String> {
        val raw = prefs(ctx).getString(K_APP_RULES, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return raw.split(";").mapNotNull { item ->
            val s = item.trim()
            if (s.isBlank()) return@mapNotNull null
            val i = s.indexOf(':')
            if (i > 0) {
                s.substring(0, i).trim() to s.substring(i + 1).trim()
            } else {
                s to DisplayMode.INK_PAPER
            }
        }.toMap()
    }

    fun putAppRuleModes(ctx: Context, map: Map<String, String>) {
        prefs(ctx).edit()
            .putString(K_APP_RULES, map.entries.joinToString(";") { "${it.key}:${it.value}" })
            .apply()
    }

    fun putAppRuleMode(ctx: Context, pkg: String, mode: String) {
        val m = getAppRuleModes(ctx).toMutableMap()
        m[pkg] = mode
        putAppRuleModes(ctx, m)
    }

    fun removeAppRuleMode(ctx: Context, pkg: String) {
        val m = getAppRuleModes(ctx).toMutableMap()
        m.remove(pkg)
        putAppRuleModes(ctx, m)
    }

    /** 兼容旧调用：返回已配置规则的包名集合 */
    fun getAppRulesSet(ctx: Context): Set<String> = getAppRuleModes(ctx).keys

    /** 兼容旧调用：整批写入，一律按墨纸模式 */
    fun putAppRuleRaw(ctx: Context, pkgs: Set<String>) {
        putAppRuleModes(ctx, pkgs.associateWith { DisplayMode.INK_PAPER })
    }

    fun putAppRule(ctx: Context, pkg: String, preset: String) =
        putAppRuleMode(ctx, pkg, DisplayMode.INK_PAPER)

    fun removeAppRule(ctx: Context, pkg: String) = removeAppRuleMode(ctx, pkg)

    fun getAppRules(ctx: Context): Map<String, String> = getAppRuleModes(ctx)

    /**
     * 模块激活检测：默认 false，被模块 hook 后返回 true。
     *
     * ⚠️ 不要加 @JvmStatic —— 它会同时生成静态和实例两个同名同签名方法，
     *    XposedHelpers.findMethodExact 可能匹配到歧义。
     *    只用实例方法，HookEntry 里 hook 的也是这一个。
     */
    fun isModuleActive(): Boolean = false
}
