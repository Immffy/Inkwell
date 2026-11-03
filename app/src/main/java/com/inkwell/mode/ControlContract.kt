package com.inkwell.mode

import java.io.File

/**
 * App 与模块之间的控制协议（两侧共享同一份常量）。
 *
 * ── 为什么需要「不依赖 root」的通道 ──
 *
 * 最初只用 setprop（需要 root）。但很多用户没给 App root 授权，
 * 结果所有开关都写不进去 —— 最严重的后果是「关不掉效果」。
 *
 * 现在双通道：
 *
 *  ① 文件通道（主，root-free，可持久化）
 *     App 写 /data/local/tmp/inkwell.conf 并 chmod 644。
 *     system_server（uid 1000）能读该目录，无需 root。
 *     模块每 2 秒轮询 → 保证重启后按上次的开关状态恢复。
 *
 *  ② 广播通道（辅助，root-free，即时）
 *     App 发广播，模块立即应用 → 不用等轮询的 2 秒。
 *     若广播被 ROM 拦掉，文件通道仍会在 2 秒内兜底生效。
 *
 *  ③ setprop（可选增强，需 root）
 *     有 root 时额外写入 persist.inkwell.*，开机更早生效。
 *     没有也完全不影响功能。
 *
 * ── 0.2.2 新增字段 ──
 *   warmth / mode / rules。全部带默认值，旧版 App 写出来的文件
 *   新版模块能读，新版 App 写出来的文件旧版模块也能读（只是忽略新字段）。
 */
object ControlContract {

    const val ACTION_APPLY = "com.inkwell.mode.ACTION_APPLY"

    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_SAT = "sat"
    const val EXTRA_BLACK = "black"
    const val EXTRA_WHITE = "white"
    const val EXTRA_WARMTH = "warmth"
    const val EXTRA_PRESET = "preset"

    /** 配置文件（root-free 主通道） */
    const val CONF_FILE = "/data/local/tmp/inkwell.conf"

    /** 黑白档 App 名单（旧字段，保留兼容） */
    const val BW_APPS_FILE = "/data/local/tmp/inkwell_bw_apps.txt"

    /** 配置指纹：内容变了才重新应用，避免每 2 秒无谓重设 */
    fun fingerprint(conf: String): String = conf

    /**
     * 序列化配置。
     * 用简单 key=value 行，避免依赖 JSON 库（模块运行在 system_server，越轻越好）。
     *
     * 新参数都带默认值，所以旧的四参数调用点不用改也能编译。
     */
    fun serialize(
        enabled: Boolean,
        p: EinkParams,
        preset: String,
        bwApps: Set<String>,
        mode: String = "",
        rules: Map<String, String> = emptyMap()
    ): String = buildString {
        append("enabled=").append(if (enabled) 1 else 0).append('\n')
        append("sat=").append("%.4f".format(p.saturation)).append('\n')
        append("black=").append("%.4f".format(p.black)).append('\n')
        append("white=").append("%.4f".format(p.white)).append('\n')
        append("warmth=").append("%.4f".format(p.warmth)).append('\n')
        append("preset=").append(preset).append('\n')
        append("mode=").append(mode).append('\n')
        // 按 App 指定显示模式：pkg:mode;pkg:mode
        append("rules=").append(
            rules.entries.joinToString(";") { "${it.key}:${it.value}" }
        ).append('\n')
        // 旧字段继续写，方便旧版模块/降级场景读取
        append("bw=").append(bwApps.joinToString(";")).append('\n')
    }

    fun parse(text: String): Conf {
        val m = HashMap<String, String>()
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .forEach { line ->
                val i = line.indexOf('=')
                if (i > 0) m[line.substring(0, i).trim()] = line.substring(i + 1).trim()
            }

        // 新字段 rules= 优先；旧字段 bw= 兜底（一律按墨纸模式解释）
        val rules = LinkedHashMap<String, String>()
        val rawRules = m["rules"] ?: ""
        if (rawRules.isNotBlank()) {
            rawRules.split(";").forEach { item ->
                val s = item.trim()
                if (s.isBlank()) return@forEach
                val c = s.indexOf(':')
                if (c > 0) {
                    val mode = s.substring(c + 1).trim()
                    if (mode in DisplayMode.ALL) rules[s.substring(0, c).trim()] = mode
                } else {
                    rules[s] = DisplayMode.INK_PAPER
                }
            }
        }
        val bwApps = (m["bw"] ?: "").split(";")
            .map { it.trim() }.filter { it.isNotBlank() }.toSet()
        for (pkg in bwApps) {
            if (pkg !in rules) rules[pkg] = DisplayMode.INK_PAPER
        }

        return Conf(
            enabled = m["enabled"]?.toIntOrNull() ?: 1,
            sat = m["sat"]?.toFloatOrNull(),
            black = m["black"]?.toFloatOrNull(),
            white = m["white"]?.toFloatOrNull(),
            warmth = m["warmth"]?.toFloatOrNull(),
            preset = m["preset"] ?: "",
            mode = m["mode"] ?: "",
            bwApps = bwApps,
            rules = rules
        )
    }

    fun read(): Conf {
        return try {
            val f = File(CONF_FILE)
            if (f.exists()) parse(f.readText()) else Conf()
        } catch (_: Throwable) {
            Conf()
        }
    }

    data class Conf(
        val enabled: Int = 1,
        val sat: Float? = null,
        val black: Float? = null,
        val white: Float? = null,
        val warmth: Float? = null,
        val preset: String = "",
        val mode: String = "",
        val bwApps: Set<String> = emptySet(),
        /** 包名 → 三模之一 */
        val rules: Map<String, String> = emptyMap()
    ) {
        /** 配置文件里是否带了有效参数（用来判断该不该覆盖 SystemProperties） */
        fun hasParams(): Boolean =
            sat != null || black != null || white != null || preset.isNotBlank()
    }
}
