package com.inkwell.mode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 遮罩引擎（零依赖降级方案）。
 *
 * ── 它做什么 ──
 * 叠加一层半透明的"纸色"窗口，让画面偏暖、压低鲜亮感，模拟纸张底色。
 *
 * ── 它做不到什么（必须诚实说明）──
 *
 * alpha 混合的数学形式固定为：out = src × (1-a) + 遮罩色 × a
 *
 * 注意 src 的系数恒小于 1 —— 这意味着**只能把画面往某个颜色拉近，
 * 无法拉开像素间的差距**。而"高对比 + 低饱和"的纸感，核心恰恰是拉大
 * 明暗差（提对比）。这条路在数学上就走不通。
 *
 * 所以遮罩版的实际观感是"褪色、偏灰、发暖"，而不是真正的电子纸。
 * 它是给无法使用完整通道的用户一个可用的近似，不是等价替代品。
 *
 * 另外受系统限制，TYPE_APPLICATION_OVERLAY 无法覆盖状态栏与导航栏。
 */
class OverlayService : Service() {

    companion object {
        private const val CH = "inkwell_overlay"
        private const val NOTI_ID = 9001

        @Volatile
        var running: Boolean = false
            private set

        fun hasPermission(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(ctx)
            } else true
        }

        fun start(ctx: Context, p: EinkParams, enabled: Boolean) {
            if (!hasPermission(ctx)) return
            val i = Intent(ctx, OverlayService::class.java).apply {
                putExtra("sat", p.saturation)
                putExtra("black", p.black)
                putExtra("white", p.white)
                putExtra("warmth", p.warmth)
                putExtra("enabled", enabled)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(i)
                } else {
                    ctx.startService(i)
                }
            } catch (t: Throwable) {
                XLog.e("overlay start failed", t)
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, OverlayService::class.java)) }
            catch (_: Throwable) { }
        }
    }

    private var wm: WindowManager? = null
    private var view: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val enabled = intent?.getBooleanExtra("enabled", true) ?: true
        val p = EinkParams(
            saturation = intent?.getFloatExtra("sat", EinkParams.KALEIDO3.saturation)
                ?: EinkParams.KALEIDO3.saturation,
            black = intent?.getFloatExtra("black", EinkParams.KALEIDO3.black)
                ?: EinkParams.KALEIDO3.black,
            white = intent?.getFloatExtra("white", EinkParams.KALEIDO3.white)
                ?: EinkParams.KALEIDO3.white,
            warmth = intent?.getFloatExtra("warmth", EinkParams.KALEIDO3.warmth)
                ?: EinkParams.KALEIDO3.warmth
        )

        startForeground(NOTI_ID, buildNotification(enabled))

        if (!enabled) {
            removeView()
            stopSelf()
            return START_NOT_STICKY
        }

        showOverlay(p)
        running = true
        return START_STICKY
    }

    private fun showOverlay(p: EinkParams) {
        val mgr = getSystemService(WINDOW_SERVICE) as? WindowManager ?: return
        wm = mgr

        removeView()

        val v = View(this).apply {
            // 纸色随 warmth 从「中性灰白」渐变到「米黄纸」。
            // 三模里原色 warmth=0、彩纸 0.55、墨纸 1.0，遮罩层至少能体现这个差别。
            setBackgroundColor(paperColor(p.warmth))
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = overlayAlpha(p)
        }

        try {
            mgr.addView(v, lp)
            view = v
            XLog.i("overlay shown, alpha=%.3f".format(lp.alpha))
        } catch (t: Throwable) {
            XLog.e("overlay addView failed", t)
        }
    }

    /**
     * 由参数推导遮罩浓度。
     *
     * 设计依据：白位越低 → 纸越旧越黄 → 遮罩越浓；
     * 饱和度越高（越接近原色）→ 需要压得越重。
     * 这是经验映射，不是精确还原 —— 遮罩本质做不到精确。
     */
    private fun paperColor(warmth: Float): Int {
        val w = warmth.coerceIn(0.0f, 1.0f)
        return Color.rgb(
            (245 + 5 * w).toInt(),
            (245 - 5 * w).toInt(),
            (245 - 25 * w).toInt()
        )
    }

    private fun overlayAlpha(p: EinkParams): Float {
        val paperAge = (1.0f - p.white).coerceIn(0.0f, 0.4f)     // 白位 1.0→0.6
        val vivid = p.saturation.coerceIn(0.0f, 1.0f)             // 越鲜艳越要压
        val warmth = p.warmth.coerceIn(0.0f, 1.0f)
        // 墨纸档（饱和≈0）额外加浓，让它至少比彩纸更"旧"一些。
        // 注意：遮罩永远做不到灰度化，这里只是让三档在观感上拉开距离。
        val mono = if (p.saturation < 0.1f) 0.12f else 0f
        val a = 0.10f + paperAge * 0.55f + vivid * 0.18f + warmth * 0.06f + mono
        return a.coerceIn(0.0f, 0.45f)
    }

    private fun removeView() {
        try { view?.let { wm?.removeView(it) } } catch (_: Throwable) { }
        view = null
    }

    private fun buildNotification(enabled: Boolean): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CH) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CH, "墨境遮罩", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "保持纸感遮罩层"
                        setShowBadge(false)
                    }
                )
            }
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (enabled) "墨境遮罩运行中" else "墨境遮罩已关闭"
        return Notification.Builder(this, CH)
            .setContentTitle(title)
            .setContentText("点按打开墨境设置")
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(pi)
            .setOngoing(enabled)
            .build()
    }

    override fun onDestroy() {
        removeView()
        running = false
        super.onDestroy()
    }
}
