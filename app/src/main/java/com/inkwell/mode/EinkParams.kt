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
        // 黑白电子纸无滤光片吃光，比彩色档更亮、对比更强
        val PAPER_BW = EinkParams(0.00f, 0.08f, 0.96f)
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
    const val K_LEVELS = "levels"
    const val K_GRAIN = "grain"
    const val K_CFA = "cfa"
    const val K_CELL = "cell"
    const val K_ENABLED = "enabled"
    const val K_PRESET = "preset"

    /** 按 App 包名配置的档位，value 为预设名 */
    const val K_APP_RULES = "app_rules"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context): EinkParams {
        val p = prefs(ctx)
        return EinkParams(
            saturation = p.getFloat(K_SAT, EinkParams.KALEIDO3.saturation),
            black = p.getFloat(K_BLACK, EinkParams.KALEIDO3.black),
            white = p.getFloat(K_WHITE, EinkParams.KALEIDO3.white),
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

    fun getPreset(ctx: Context) = prefs(ctx).getString(K_PRESET, "Kaleido 3（默认）")
    fun setPreset(ctx: Context, name: String) =
        prefs(ctx).edit().putString(K_PRESET, name).apply()

    fun getAppRules(ctx: Context): Map<String, String> {
        val raw = prefs(ctx).getString(K_APP_RULES, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return raw.split(";").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2 && kv[0].isNotBlank()) kv[0] to kv[1] else null
        }.toMap()
    }

    fun putAppRule(ctx: Context, pkg: String, preset: String) {
        val m = getAppRules(ctx).toMutableMap()
        m[pkg] = preset
        prefs(ctx).edit().putString(K_APP_RULES, m.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
    }

    fun removeAppRule(ctx: Context, pkg: String) {
        val m = getAppRules(ctx).toMutableMap()
        m.remove(pkg)
        prefs(ctx).edit().putString(K_APP_RULES, m.entries.joinToString(";") { "${it.key}=${it.value}" }).apply()
    }

    /**
     * 模块激活检测：默认 false，被模块 hook 后返回 true。
     *
     * ⚠️ 不要加 @JvmStatic —— 它会同时生成静态和实例两个同名同签名方法，
     *    XposedHelpers.findMethodExact 可能匹配到歧义。
     *    只用实例方法，HookEntry 里 hook 的也是这一个。
     */
    fun isModuleActive(): Boolean = false
}
