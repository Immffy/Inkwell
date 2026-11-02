package com.inkwell.mode

import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * LSPosed 模块入口。
 *
 * 原理：在 system_server 进程中拿到 DisplayTransformManager 实例，
 * 调用 setColorMatrix(LEVEL, matrix) 注入全局色彩变换。
 *
 * 为什么 level 用 999：
 *   DisplayTransformManager 内部用 SparseArray 按 level **升序组合**所有矩阵
 *   （低 level 先作用，高 level 后作用）。已知最大系统常量是
 *   LEVEL_COLOR_MATRIX_INVERT_COLOR = 300，用 999 可保证我们的矩阵最后叠加，
 *   不会被护眼/夜览/色彩模式覆盖。
 *
 * 能做什么 / 不能做什么：
 *   ✅ 饱和度、对比、白黑位、通道增益 —— 全部线性变换，零开销、全局、开机生效
 *   ❌ 色阶量化、抖动、滤光网格、纸纹 —— 非线性，矩阵做不了
 *      （这部分需要 MediaProjection + GPU shader，见另一个工程）
 */
class HookEntry : IXposedHookLoadPackage {

    companion object {
        private const val OUR_LEVEL = 999

        /** 必须与 build.gradle.kts 的 applicationId 一致 */
        private const val OWN_PACKAGE = "com.inkwell.mode"

        /** DisplayTransformManager 在不同 Android 版本路径不同，全部试一遍 */
        private val DTM_CLASSES = arrayOf(
            "com.android.server.display.color.DisplayTransformManager", // Android 10+
            "com.android.server.display.DisplayTransformManager"        // 旧版
        )
    }

    @Volatile
    private var dtmInstance: Any? = null
    private var dtmClass: Class<*>? = null
    private var setMatrixMethod: java.lang.reflect.Method? = null

    private var mainHandler: Handler? = null
    private var systemContext: Context? = null
    private var bwApps: Set<String> = emptySet()
    private var lastFingerprint = ""
    private var lastApp = ""

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // ── 分支 A：注入到自己 App 进程，只做一件事 —— 把「模块已激活」检测打通。
        //    EinkPrefs.isModuleActive() 默认返回 false，App 靠它显示激活状态。
        //    没有这段 hook，界面会永远显示「未激活」，即使模块实际工作正常。
        if (lpparam.packageName == OWN_PACKAGE) {
            try {
                XposedHelpers.findAndHookMethod(
                    EinkPrefs::class.java, "isModuleActive",
                    XC_MethodReplacement.returnConstant(true)
                )
                XLog.i("self-check hook installed in own package")
            } catch (t: Throwable) {
                XLog.e("self-check hook failed", t)
            }
            return
        }

        // ── 分支 B：只处理 system_server（包名 android）
        if (lpparam.packageName != "android") return
        if (!lpparam.processName.contains("system_server") &&
            !lpparam.processName.contains("android")
        ) return

        XLog.i("loaded in ${lpparam.processName}")

        val cls = findDtmClass(lpparam.classLoader)
        if (cls == null) {
            XLog.e("DisplayTransformManager not found, abort")
            return
        }
        dtmClass = cls
        XLog.i("found DTM: ${cls.name}")

        // 1) hook 构造函数拿实例（比依赖字段名更稳）
        try {
            XposedHelpers.findAndHookConstructor(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    dtmInstance = param.thisObject
                    XLog.i("DTM instance captured")
                    scheduleApply(3000)
                }
            })
        } catch (t: Throwable) {
            XLog.e("hook ctor failed", t)
        }

        // 2) 拿 system Context（用于查询前台 App）
        hookSystemContext(lpparam.classLoader)

        // 3) 守护：任何别人设置的矩阵都不得把我们的挤掉
        try {
            XposedHelpers.findAndHookMethod(
                cls, "setColorMatrix",
                Int::class.javaPrimitiveType, FloatArray::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val level = param.args[0] as? Int ?: return
                        if (level == OUR_LEVEL) return
                        // 别人改了矩阵，确认我们的还在
                        if (!hasOurMatrix()) {
                            XLog.w("our matrix was dropped (level=$level), re-applying")
                            applyNow()
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XLog.e("hook setColorMatrix failed", t)
        }
    }

    private fun findDtmClass(cl: ClassLoader): Class<*>? {
        for (name in DTM_CLASSES) {
            try {
                return XposedHelpers.findClass(name, cl)
            } catch (_: Throwable) { /* try next */ }
        }
        return null
    }

    /** 通过 ColorDisplayService（继承 SystemService）拿 system Context */
    private fun hookSystemContext(cl: ClassLoader) {
        val candidates = arrayOf(
            "com.android.server.display.color.ColorDisplayService",
            "com.android.server.display.ColorDisplayService"
        )
        for (name in candidates) {
            try {
                val c = XposedHelpers.findClass(name, cl)
                XposedHelpers.findAndHookMethod(c, "onStart", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val ctx = XposedHelpers.callMethod(param.thisObject, "getContext") as? Context
                            if (ctx != null) {
                                systemContext = ctx
                                XLog.i("system context captured")
                            }
                        } catch (t: Throwable) {
                            XLog.e("getContext failed", t)
                        }
                    }
                })
                XLog.i("hooked $name.onStart")
                return
            } catch (t: Throwable) {
                XLog.w("cannot hook $name: ${t.message}")
            }
        }
    }

    private fun hasOurMatrix(): Boolean {
        return try {
            val m = XposedHelpers.callMethod(dtmInstance, "getColorMatrix", OUR_LEVEL)
            m != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun ensureMethod() {
        if (setMatrixMethod != null) return
        setMatrixMethod = XposedHelpers.findMethodExact(
            dtmClass, "setColorMatrix",
            Int::class.javaPrimitiveType, FloatArray::class.java
        )
    }

    private fun applyNow() {
        val dtm = dtmInstance ?: return
        try {
            ensureMethod()
            if (!PropConfig.isEnabled()) {
                setMatrixMethod?.invoke(dtm, OUR_LEVEL, null)
                return
            }
            val p = currentParams()
            val m = ColorMatrixBuilder.build(p)
            setMatrixMethod?.invoke(dtm, OUR_LEVEL, m)
            XLog.i("applied: sat=%.2f black=%.2f white=%.2f (bw=%s, app=%s)".format(
                p.saturation, p.black, p.white, isBwMode(), lastApp
            ))
        } catch (t: Throwable) {
            XLog.e("apply failed", t)
        }
    }

    /** 综合预设 + 按 App 规则决定最终参数 */
    private fun currentParams(): EinkParams {
        val base = PropConfig.loadParams()
        return if (isBwMode()) {
            // 黑白档：保留用户的黑位/白位偏好，只把饱和度归零
            EinkParams.PAPER_BW.copy(
                black = base.black.coerceAtMost(0.15f),
                white = base.white.coerceAtLeast(0.92f)
            )
        } else base
    }

    private fun isBwMode(): Boolean {
        val app = lastApp
        if (app.isBlank()) return false
        return bwApps.any { app == it || app.startsWith("$it:") }
    }

    private fun scheduleApply(delayMs: Long) {
        val h = mainHandler ?: Handler(Looper.getMainLooper()).also { mainHandler = it }
        h.postDelayed({ applyNow(); startPolling() }, delayMs)
    }

    /**
     * 轮询：检测属性变化 + 前台 App 变化。
     * 每 2 秒一次属性读取，开销可忽略。
     */
    private fun startPolling() {
        val h = mainHandler ?: return
        h.post(object : Runnable {
            override fun run() {
                try {
                    val fp = PropConfig.fingerprint()
                    if (fp != lastFingerprint) {
                        lastFingerprint = fp
                        bwApps = PropConfig.loadBwApps()
                        XLog.i("config changed -> $fp")
                        applyNow()
                    }
                    val app = currentForegroundApp()
                    if (app != lastApp) {
                        lastApp = app
                        if (bwApps.isNotEmpty()) applyNow()
                    }
                } catch (t: Throwable) {
                    XLog.e("poll error", t)
                }
                h.postDelayed(this, PropConfig.POLL_MS)
            }
        })
    }

    /** 用 UsageStatsManager 查最近使用 App（比 hook AMS 稳，且不依赖版本私有 API） */
    private fun currentForegroundApp(): String {
        val ctx = systemContext ?: return ""
        return try {
            val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return ""
            val now = System.currentTimeMillis()
            val stats = usm.queryUsageUsageStatsCompat(now - 5000, now)
            stats ?: ""
        } catch (t: Throwable) {
            ""
        }
    }

    /** 兼容不同版本：取最近 5 秒内最后使用的一个包名 */
    private fun UsageStatsManager.queryUsageUsageStatsCompat(from: Long, to: Long): String? {
        return try {
            val list = queryUsageStats(UsageStatsManager.INTERVAL_BEST, from, to)
            if (list.isNullOrEmpty()) return null
            list.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (t: Throwable) {
            null
        }
    }
}
