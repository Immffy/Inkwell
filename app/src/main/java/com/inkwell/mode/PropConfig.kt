package com.inkwell.mode

/**
 * 模块端（运行在 system_server 进程）读取配置的通道。
 *
 * 为什么用 SystemProperties 而不是 SharedPreferences：
 *   1. system_server 无法可靠读取其他 App 的 /data/data 私有文件；
 *   2. `persist.` 前缀属性会写入 /data/property，**重启后依然保留**——
 *      这意味着效果开机自动生效，App 不需要常驻后台。
 *
 * App 端（有 root）通过 `setprop persist.inkwell.sat 0.45` 写入，
 * 模块端反射 android.os.SystemProperties 读取。
 */
object PropConfig {

    const val P_SAT = "persist.inkwell.sat"
    const val P_BLACK = "persist.inkwell.black"
    const val P_WHITE = "persist.inkwell.white"
    const val P_ENABLED = "persist.inkwell.enabled"
    const val P_PRESET = "persist.inkwell.preset"

    /** 使用黑白档的包名列表文件（由 App 写入并 chmod 644） */
    const val BW_APPS_FILE = "/data/local/tmp/inkwell_bw_apps.txt"

    /** 轮询间隔 */
    const val POLL_MS = 2000L

    private var sysPropsGet: java.lang.reflect.Method? = null
    private var sysPropsGetFloat: java.lang.reflect.Method? = null

    private fun ensureMethods() {
        if (sysPropsGet != null) return
        try {
            val cls = Class.forName("android.os.SystemProperties")
            sysPropsGet = cls.getDeclaredMethod("get", String::class.java, String::class.java)
            sysPropsGetFloat = cls.getDeclaredMethod("getFloat", String::class.java, Float::class.javaPrimitiveType)
        } catch (t: Throwable) {
            XLog.e("SystemProperties reflect failed", t)
        }
    }

    fun get(key: String, def: String): String {
        ensureMethods()
        return try {
            sysPropsGet?.invoke(null, key, def) as? String ?: def
        } catch (t: Throwable) {
            def
        }
    }

    fun getFloat(key: String, def: Float): Float {
        ensureMethods()
        return try {
            sysPropsGetFloat?.invoke(null, key, def) as? Float ?: def
        } catch (t: Throwable) {
            // 部分 ROM 可能没有 getFloat，退回字符串解析
            get(key, def.toString()).toFloatOrNull() ?: def
        }
    }

    fun isEnabled(): Boolean = get(P_ENABLED, "1") == "1"

    /** 读取当前生效参数 */
    fun loadParams(): EinkParams {
        val preset = get(P_PRESET, "")
        val base = if (preset.isNotBlank()) EinkParams.byName(preset) else EinkParams.KALEIDO3
        // 属性存在则用属性值（自定义微调），否则用预设值
        val sat = get(P_SAT, "")
        val blk = get(P_BLACK, "")
        val wht = get(P_WHITE, "")
        return EinkParams(
            saturation = sat.toFloatOrNull() ?: base.saturation,
            black = blk.toFloatOrNull() ?: base.black,
            white = wht.toFloatOrNull() ?: base.white,
            levels = base.levels, grain = base.grain,
            cfa = base.cfa, cellPx = base.cellPx
        )
    }

    /** 读取黑名单（这些包名使用黑白纸质档） */
    fun loadBwApps(): Set<String> {
        return try {
            val f = java.io.File(BW_APPS_FILE)
            if (!f.exists()) return emptySet()
            f.readLines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        } catch (t: Throwable) {
            emptySet()
        }
    }

    /** 配置指纹，用于判断是否需要重新应用 */
    fun fingerprint(): String {
        return listOf(
            get(P_ENABLED, "1"),
            get(P_PRESET, ""),
            get(P_SAT, ""), get(P_BLACK, ""), get(P_WHITE, "")
        ).joinToString("|")
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
