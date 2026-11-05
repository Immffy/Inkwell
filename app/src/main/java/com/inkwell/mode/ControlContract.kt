package com.inkwell.mode

import java.io.File

/**
 * App 与模块之间的控制协议（两侧共享同一份常量）。
 *
 * ── 0.2.2 重要修正 ──
 *
 * 之前把配置文件放在 /data/local/tmp，并假设"App 可直接写、system_server 可读"。
 * 这在 Android 16 上是错的：
 *   · /data/local/tmp 属主是 shell:shell，普通 app 进程（untrusted_app 域）
 *     在 SELinux 下**没有写权限** → App 侧 f.writeText() 抛 EACCES
 *   · 即便侥幸写成功，该目录的 SELinux label 是 shell_data_file，
 *     system_server 域也未必有读权限
 *
 * 结果就是：诊断页那个"文件通道 未写入 ✗"。开关指令从来没进过配置文件，
 * 模块一直按默认参数（Kaleido 3）自己生效 —— 这也解释了最早"关不掉"。
 *
 * 现在改为：
 *   · 配置文件 → /data/system/inkwell.conf（system_server 自己的目录，必可读），
 *     由 App 通过 su 写入；没 root 时退回广播，不再谎报成功
 *   · 广播去掉 setPackage 限制 → 直达 system_server 的动态接收器，
 *     这是真正 root-free 的即时通道
 *   · setprop（需 root）负责重启后保持
 *
 * 旧路径仍保留读取兼容，升级时不会丢上次的选择。
 */
object ControlContract {

    const val ACTION_APPLY = "com.inkwell.mode.ACTION_APPLY"

    const val EXTRA_ENABLED = "enabled"
    const val EXTRA_SAT = "sat"
    const val EXTRA_BLACK = "black"
    const val EXTRA_WHITE = "white"
    const val EXTRA_WARMTH = "warmth"
    const val EXTRA_PRESET = "preset"
    const val EXTRA_MODE = "mode"
    const val EXTRA_RULES = "rules"

    /** 配置文件（system_server 域必可读；App 需经 su 写入） */
    const val CONF_FILE = "/data/system/inkwell.conf"

    /** 0.2.2 及更早的路径，仅作读取兼容 */
    const val LEGACY_CONF_FILE = "/data/local/tmp/inkwell.conf"

    /** 模块激活标记（system_server 写、App 经 su 探测） */
    const val ACTIVE_MARK = "/data/system/inkwell.active"

    /** 旧版激活标记，探测时一起看 */
    const val LEGACY_ACTIVE_MARK = "/data/local/tmp/inkwell.module.active"

    /** 按 App 规则的独立文件（避免配置文件过长） */
    const val RULES_FILE = "/data/system/inkwell_rules.conf"

    /** 黑白档 App 名单（旧字段，保留兼容） */
    const val BW_APPS_FILE = "/data/local/tmp/inkwell_bw_apps.txt"

    fun fingerprint(conf: String): String = conf

    /**
     * 序列化配置。新参数都带默认值，旧调用点不用改也能编译。
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
        append("rules=").append(
            rules.entries.joinToString(";") { "${it.key}:${it.value}" }
        ).append('\n')
        append("bw=").append(bwApps.joinToString(";")).append('\n')
    }

    fun serializeRules(rules: Map<String, String>): String =
        rules.entries.joinToString("\n") { "${it.key}=${it.value}" }

    fun parseRules(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        text.lineSequence().forEach { line ->
            val s = line.trim()
            if (s.isBlank() || s.startsWith("#")) return@forEach
            val i = s.indexOf('=')
            if (i > 0) {
                val mode = s.substring(i + 1).trim()
                if (mode in DisplayMode.ALL) out[s.substring(0, i).trim()] = mode
            } else {
                out[s] = DisplayMode.INK_PAPER
            }
        }
        return out
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

    /** 优先读新路径，回落旧路径 */
    fun read(): Conf {
        for (path in arrayOf(CONF_FILE, LEGACY_CONF_FILE)) {
            try {
                val f = File(path)
                if (f.exists()) return parse(f.readText())
            } catch (_: Throwable) { /* 试下一个 */ }
        }
        return Conf()
    }

    /** 读配置文件原文（诊断页用） */
    fun readRaw(): String {
        for (path in arrayOf(CONF_FILE, LEGACY_CONF_FILE)) {
            try {
                val f = File(path)
                if (f.exists()) return f.readText().trim()
            } catch (_: Throwable) { }
        }
        return ""
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
        val rules: Map<String, String> = emptyMap()
    ) {
        fun hasParams(): Boolean =
            sat != null || black != null || white != null || preset.isNotBlank()
    }
}
