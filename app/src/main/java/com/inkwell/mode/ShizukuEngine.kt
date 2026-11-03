package com.inkwell.mode

import java.io.DataOutputStream
import java.lang.reflect.Method

/**
 * Shizuku 通道：不需要 root，效果与 LSPosed 版完全一致。
 *
 * ── 为什么 Shizuku 能做同样的事 ──
 *
 * 色彩矩阵本质是 `service call SurfaceFlinger 1015` —— 这条命令 adb shell
 * （uid 2000）本来就有权限执行。Shizuku 的作用就是把 adb shell 级权限
 * 授予普通 App，于是我们能下发和 LSPosed 模块完全相同的矩阵。
 *
 * ── 为什么全部用反射 ──
 *
 * `rikka.shizuku.Shizuku.newProcess(...)` 在 Shizuku 库里被标记为 internal /
 * hidden，Kotlin 编译期直接报 "cannot access private static fun newProcess"。
 * 反射能绕过编译期的可见性检查，运行期照样调用得到。
 * 副作用是失去了编译期类型检查，所以每个调用都必须包在 try 里并安全降级。
 *
 * 若用户没装 Shizuku，所有方法安全返回 false —— 这不是错误，是正常的降级路径。
 */
object ShizukuEngine {

    private const val API_CLASS = "rikka.shizuku.Shizuku"
    private const val REQ_CODE = 10086

    /** Shizuku 是否可用（已安装且已运行） */
    @Volatile
    var available: Boolean = false
        private set

    /** 是否已授予权限 */
    @Volatile
    var granted: Boolean = false
        private set

    private var inited = false
    private var mPing: Method? = null
    private var mCheckPerm: Method? = null
    private var mReqPerm: Method? = null
    private var mNewProc: Method? = null

    /**
     * 反射解析 Shizuku 的方法句柄。
     * 只做一次；失败则整个通道永久降级（没装 Shizuku 时的正常情况）。
     */
    private fun init() {
        if (inited) return
        inited = true
        try {
            val cls = Class.forName(API_CLASS)
            mPing = cls.getDeclaredMethod("pingBinder").apply { isAccessible = true }
            mCheckPerm = cls.getDeclaredMethod("checkSelfPermission").apply { isAccessible = true }
            mReqPerm = cls.getDeclaredMethod(
                "requestPermission", Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            mNewProc = cls.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
            XLog.i("shizuku api resolved")
        } catch (t: Throwable) {
            XLog.w("Shizuku API 不可用（未安装或版本不兼容）：${t.message}")
        }
    }

    /** 刷新状态。每次使用前调用（Shizuku 可能刚被启动或刚停止）。 */
    fun refresh() {
        init()
        val ping = mPing
        if (ping == null) {
            available = false; granted = false
            return
        }
        try {
            available = (ping.invoke(null) as? Boolean) ?: false
            granted = if (available) {
                val v = mCheckPerm?.invoke(null) as? Int
                v == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else false
        } catch (t: Throwable) {
            available = false; granted = false
            XLog.w("shizuku refresh failed: ${t.message}")
        }
    }

    fun requestPermission() {
        try {
            if (available && !granted) mReqPerm?.invoke(null, REQ_CODE)
        } catch (t: Throwable) {
            XLog.w("shizuku requestPermission failed: ${t.message}")
        }
    }

    /**
     * 下发色彩矩阵。
     *
     * 与 LSPosed 版的区别只有调用方式：那边直接调 DisplayTransformManager 的方法，
     * 这边通过 Shizuku 拿到 adb shell 权限后执行 service call。矩阵内容完全相同。
     */
    fun apply(p: EinkParams, enabled: Boolean): Boolean {
        if (!available || !granted) return false
        val m = if (enabled) ColorMatrixBuilder.build(p) else ColorMatrixBuilder.identity()

        // service call SurfaceFlinger 1015 i32 <level> f x16
        val sb = StringBuilder("service call SurfaceFlinger 1015 i32 999")
        for (v in m) {
            val f = "%.4f".format(v)
            sb.append(' ').append(if (f == "-0.0000") "0.0" else f)
        }
        val cmd = sb.toString()

        return try {
            val proc = mNewProc?.invoke(null, arrayOf("sh", "-c", cmd), null, null)
            if (proc == null) {
                XLog.w("shizuku newProcess 不可用")
                return false
            }
            val p2 = proc as? Process ?: run {
                XLog.w("shizuku newProcess 返回类型异常")
                return false
            }
            try {
                val os = DataOutputStream(p2.outputStream)
                os.writeBytes("exit\n"); os.flush()
            } catch (_: Throwable) { }
            val code = p2.waitFor()
            XLog.i("shizuku applied (exit=$code)")
            code == 0
        } catch (t: Throwable) {
            XLog.e("shizuku apply failed", t)
            false
        }
    }
}
