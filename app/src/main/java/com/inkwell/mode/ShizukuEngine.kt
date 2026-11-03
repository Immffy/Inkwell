package com.inkwell.mode

import android.content.Context
import android.content.pm.PackageManager
import java.io.DataOutputStream

/**
 * Shizuku 通道：不需要 root，效果与 LSPosed 版完全一致。
 *
 * ── 为什么 Shizuku 能做同样的事 ──
 *
 * 色彩矩阵本质是 `service call SurfaceFlinger 1015` —— 这条命令 adb shell
 * （uid 2000）本来就有权限执行。Shizuku 的作用就是把 adb shell 级权限
 * 授予普通 App，于是我们能下发和 LSPosed 模块完全相同的矩阵。
 *
 * 代价：
 *   - 需要 Android 11+（无线调试）
 *   - 手机重启后开发者选项的无线调试开关会关闭，需重新启用一次（约 30 秒）
 *
 * 若用户机器没装 Shizuku，本类所有方法都安全返回 false，不会崩溃
 * —— Shizuku 未运行时 pingBinder() 返回 false，我们据此降级到遮罩模式。
 */
object ShizukuEngine {

    private const val TAG = "Inkwell/Shizuku"
    private const val REQ_CODE = 10086

    /** Shizuku 是否可用（已安装且已运行） */
    @Volatile
    var available: Boolean = false
        private set

    /** 是否已授予权限 */
    @Volatile
    var granted: Boolean = false
        private set

    private var listenerRegistered = false

    /**
     * 刷新状态。必须在每次使用前调用（Shizuku 可能刚被启动或刚停止）。
     * 任何异常都吞掉并降级 —— 没有 Shizuku 是正常情况，不是错误。
     */
    fun refresh(ctx: Context) {
        try {
            val pm = ctx.packageManager
            val installed = try {
                pm.getPackageInfo("moe.shizuku.privileged.api", 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
            if (!installed) {
                available = false; granted = false
                return
            }

            available = rikka.shizuku.Shizuku.pingBinder()
            granted = if (available) {
                try {
                    val v = rikka.shizuku.Shizuku.checkSelfPermission()
                    v == PackageManager.PERMISSION_GRANTED
                } catch (_: Throwable) { false }
            } else false

            if (available && !listenerRegistered) {
                try {
                    rikka.shizuku.Shizuku.addBinderReceivedListener {
                        available = true
                        refresh(ctx)
                    }
                    listenerRegistered = true
                } catch (_: Throwable) { }
            }
        } catch (t: Throwable) {
            // Shizuku API 不可用（未集成或版本不兼容）
            available = false; granted = false
            XLog.w("Shizuku 不可用：${t.message}")
        }
    }

    fun requestPermission(code: Int = REQ_CODE) {
        try {
            if (available && !granted) rikka.shizuku.Shizuku.requestPermission(code)
        } catch (_: Throwable) { }
    }

    /**
     * 下发色彩矩阵。
     *
     * 与 LSPosed 版的区别只有调用方式：那边直接调 DisplayTransformManager 的方法，
     * 这边通过 adb shell 的 service call。矩阵内容完全相同。
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
            val proc = rikka.shizuku.Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val os = DataOutputStream(proc.outputStream)
            os.writeBytes("exit\n"); os.flush()
            val code = proc.waitFor()
            XLog.i("shizuku applied (exit=$code)")
            code == 0
        } catch (t: Throwable) {
            XLog.e("shizuku apply failed", t)
            false
        }
    }
}
