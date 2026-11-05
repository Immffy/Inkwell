package com.inkwell.mode

import java.io.File

/**
 * 模块侧（运行在 system_server）读取配置。
 *
 * 读取优先级：
 *   ① 文件 /data/system/inkwell.conf（App 经 su 写入）
 *      —— system_server 自己的目录，必可读。旧路径 /data/local/tmp 仍兼容读取。
 *
 *   ② SystemProperties persist.inkwell.*
 *      —— 需 root 才能写，但 system_server 直接可读，重启后保持。
 *
 *   ③ 广播瞬时值（最高优先）
 *      —— App 发全局广播直达 system_server 的动态 receiver，即时生效。
 *         这是 root-free 的主通道；应用后清空，后续以文件/属性为准。
 *
 * ⚠️ 0.2.2 及更早版本把 /data/local/tmp 当"App 可直接写的 root-free 通道"是错的：
 *    Android 16 的 SELinux 下普通 app 进程写不了那个目录，指令从未送达。
 */
object PropConfig {

    private const val P_SAT = "persist.inkwell.sat"
    private const val P_BLACK = "persist.inkwell.black"
    private const val P_WHITE = "persist.inkwell.white"
    private const val P_WARMTH = "persist.inkwell.warmth"
    private const val P_ENABLED = "persist.inkwell.enabled"
    private const val P_PRESET = "persist.inkwell.preset"

    /** 轮询间隔 */
    const val POLL_MS = 2000L

    /** 广播即时值（未收到则为 null，回落到文件/属性） */
    @Volatile var pushedEnabled: Boolean? = null
    @Volatile var pushedSat: Float? = null
    @Volatile var pushedBlack: Float? = null
    @Volatile var pushedWhite: Float? = null
    @Volatile var pushedWarmth: Float? = null
    @Volatile var pushedPreset: String? = null

    private var sysPropsGet: java.lang.reflect.Method? = null
    private var sysPropsGetFloat: java.lang.reflect.Method? = null

    private fun ensureMethods() {
        if (sysPropsGet != null) return
        try {
            val cls = Class.forName("android.os.SystemProperties")
            sysPropsGet = cls.getDeclaredMethod("get", String::class.java, String::class.java)
            sysPropsGetFloat = cls.getDeclaredMethod("getFloat", String::class.java, Float::class.javaPrimitiveType)
        } catch (_: Throwable) { /* 无 SystemProperties 也能工作（走文件通道） */ }
    }

    fun get(key: String, def: String): String {
        ensureMethods()
        return try {
            sysPropsGet?.invoke(null, key, def) as? String ?: def
        } catch (_: Throwable) {
            def
        }
    }

    fun getFloat(key: String, def: Float): Float {
        ensureMethods()
        return try {
            val v = sysPropsGetFloat?.invoke(null, key, def) as? Float
            v ?: get(key, def.toString()).toFloatOrNull() ?: def
        } catch (_: Throwable) {
            get(key, def.toString()).toFloatOrNull() ?: def
        }
    }

    /** 效果是否开启。默认 true，但一旦用户关过，文件会记录 0 并持久生效。 */
    fun isEnabled(): Boolean {
        pushedEnabled?.let { return it }
        val conf = ControlContract.read()
        if (conf.hasParams()) return conf.enabled == 1
        return get(P_ENABLED, "1") == "1"
    }

    /** 读取当前生效参数（含纸色暖度） */
    fun loadParams(): EinkParams {
        pushedSat?.let { s ->
            return EinkParams(
                saturation = s,
                black = pushedBlack ?: EinkParams.KALEIDO3.black,
                white = pushedWhite ?: EinkParams.KALEIDO3.white,
                warmth = pushedWarmth ?: 0f
            )
        }

        val conf = ControlContract.read()
        if (conf.hasParams()) {
            val base = if (conf.preset.isNotBlank()) EinkParams.byName(conf.preset) else EinkParams.KALEIDO3
            return EinkParams(
                saturation = conf.sat ?: base.saturation,
                black = conf.black ?: base.black,
                white = conf.white ?: base.white,
                warmth = conf.warmth ?: base.warmth
            )
        }

        // 回落到 SystemProperties
        val preset = get(P_PRESET, "")
        val base = if (preset.isNotBlank()) EinkParams.byName(preset) else EinkParams.KALEIDO3
        return EinkParams(
            saturation = get(P_SAT, "").toFloatOrNull() ?: base.saturation,
            black = get(P_BLACK, "").toFloatOrNull() ?: base.black,
            white = get(P_WHITE, "").toFloatOrNull() ?: base.white,
            warmth = get(P_WARMTH, "").toFloatOrNull() ?: base.warmth
        )
    }

    /** 全局三模（持久化在 setprop 或配置文件里） */
    fun loadMode(): String {
        val fromFile = ControlContract.read().mode
        if (fromFile.isNotBlank()) return fromFile
        return get("persist.inkwell.mode", "")
    }

    /**
     * 按 App 指定显示模式（0.2.2）。
     * 0.2.2 起优先读 /data/system/inkwell_rules.conf（App 经 su 写）。
     * 旧版只写 bw= 名单，ControlContract.parse 会自动映射成「墨纸模式」。
     */
    fun loadAppRules(): Map<String, String> {
        try {
            val f = File(ControlContract.RULES_FILE)
            if (f.exists()) return ControlContract.parseRules(f.readText())
        } catch (_: Throwable) { }

        val fromConf = ControlContract.read().rules
        if (fromConf.isNotEmpty()) return fromConf
        return try {
            val f = File(ControlContract.BW_APPS_FILE)
            if (!f.exists()) return emptyMap()
            f.readLines().map { it.trim() }
                .filter { it.isNotBlank() }
                .associateWith { DisplayMode.INK_PAPER }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    /** 兼容旧调用：返回配置了规则的包名集合 */
    fun loadBwApps(): Set<String> = loadAppRules().keys

    /** 配置指纹：内容变化才重新应用 */
    fun fingerprint(): String {
        val conf = ControlContract.readRaw()
        val rules = try {
            File(ControlContract.RULES_FILE).takeIf { it.exists() }?.readText() ?: ""
        } catch (_: Throwable) { "" }
        val legacy = try {
            File(ControlContract.LEGACY_CONF_FILE).takeIf { it.exists() }?.readText() ?: ""
        } catch (_: Throwable) { "" }
        return listOf(
            conf, rules, legacy,
            get(P_ENABLED, "1"), get(P_PRESET, ""), get("persist.inkwell.mode", ""),
            get(P_SAT, ""), get(P_BLACK, ""), get(P_WHITE, ""), get(P_WARMTH, ""),
            pushedEnabled?.toString() ?: "", pushedSat?.toString() ?: ""
        ).joinToString("|")
    }

    /** 广播收到后立即清空 pushed 值，让后续轮询以文件为准（保持一致） */
    fun clearPushed() {
        pushedEnabled = null
        pushedSat = null
        pushedBlack = null
        pushedWhite = null
        pushedWarmth = null
        pushedPreset = null
    }
}

/** 统一日志出口，LSPosed 日志里可按 tag 过滤 */
object XLog {
    private const val TAG = "Inkwell"
    fun i(msg: String) {
        try { de.robv.android.xposed.XposedBridge.log("$TAG: $msg") } catch (_: Throwable) {}
        android.util.Log.i(TAG, msg)
    }
    fun w(msg: String) {
        try { de.robv.android.xposed.XposedBridge.log("$TAG/W: $msg") } catch (_: Throwable) {}
        android.util.Log.w(TAG, msg)
    }
    fun e(msg: String, t: Throwable? = null) {
        try {
            de.robv.android.xposed.XposedBridge.log("$TAG/E: $msg")
            t?.let { de.robv.android.xposed.XposedBridge.log(it) }
        } catch (_: Throwable) {}
        android.util.Log.e(TAG, msg, t)
    }
}
