package com.inkwell.mode

import java.io.File

/**
 * App 与模块之间的控制协议（两侧共享同一份常量）。
 *
 * ── 为什么需要「不依赖 root」的通道 ──
 *
 * 最初只用 setprop（需要 root）。但很多用户没给 App root 授权，
 * 结果所有开关都写不进去 —— 最严重的后果是「关不掉效果」。
 * 一个改不了回去的色彩滤镜是不可接受的。
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
 */
object ControlContract {

    const val ACTION_APPLY = "com.inkwell.mode.ACTION_APPLY"

    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_SAT = "sat"
    const val EXTRA_BLACK = "black"
    const val EXTRA_WHITE = "white"
    const val EXTRA_PRESET = "preset"

    /** 配置文件（root-free 主通道） */
    const val CONF_FILE = "/data/local/tmp/inkwell.conf"

    /** 黑白档 App 名单 */
    const val BW_APPS_FILE = "/data/local/tmp/inkwell_bw_apps.txt"

    /** 配置指纹：内容变了才重新应用，避免每 2 秒无谓重设 */
    fun fingerprint(conf: String): String = conf

    /**
     * 序列化配置。
     * 用简单 key=value 行，避免依赖 JSON 库（模块运行在 system_server，越轻越好）。
     */
    fun serialize(
        enabled: Boolean,
        p: EinkParams,
        preset: String,
        bwApps: Set<String>
    ): String = buildString {
        append("enabled=").append(if (enabled) 1 else 0).append('\n')
        append("sat=").append("%.4f".format(p.saturation)).append('\n')
        append("black=").append("%.4f".format(p.black)).append('\n')
        append("white=").append("%.4f".format(p.white)).append('\n')
        append("preset=").append(preset).append('\n')
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
        return Conf(
            enabled = m["enabled"]?.toIntOrNull() ?: 1,
            sat = m["sat"]?.toFloatOrNull(),
            black = m["black"]?.toFloatOrNull(),
            white = m["white"]?.toFloatOrNull(),
            preset = m["preset"] ?: "",
            bwApps = (m["bw"] ?: "").split(";").map { it.trim() }.filter { it.isNotBlank() }.toSet()
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
        val preset: String = "",
        val bwApps: Set<String> = emptySet()
    )
}
