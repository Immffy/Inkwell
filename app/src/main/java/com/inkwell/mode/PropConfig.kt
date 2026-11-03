package com.inkwell.mode

import java.io.File

/**
 * 模块侧（运行在 system_server）读取配置。
 *
 * 读取优先级：
 *   ① 文件 /data/local/tmp/inkwell.conf
 *      —— root-free 主通道。system_server（uid 1000）可读该目录。
 *         App 写入并 chmod 644，无需任何 root 权限。
 *         persist 到磁盘 → 重启后按上次开关状态恢复（关了就是关了）。
 *
 *   ② SystemProperties persist.inkwell.*
 *      —— 需 root 才能写。有则用（开机更早生效），没有也不影响。
 *
 * 广播收到的值存在内存中优先使用（即时生效），随后被文件轮询覆盖为一致值。
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

    /**
     * 按 App 指定显示模式（0.2.2）。
     * 旧版只写 bw= 名单，ControlContract.parse 会自动映射成「墨纸模式」。
     */
    fun loadAppRules(): Map<String, String> {
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
        val conf = try {
            File(ControlContract.CONF_FILE).takeIf { it.exists() }?.readText() ?: ""
        } catch (_: Throwable) { "" }
        return listOf(
            conf,
            get(P_ENABLED, "1"), get(P_PRESET, ""),
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
