package com.inkwell.mode

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * 常驻通知：下拉通知栏就能点「原色 / 彩纸 / 墨纸 / 开关」。
 *
 * 为什么在澎湃 OS 上这条最实用：
 *   「系统快捷开关」现在可以一键加入（见 QsTileInstaller），但需要 root
 *   或手动去编辑页翻找；而**通知是 Android 标准能力，厂商不会屏蔽**，
 *   零权限、零 root、装完就能用，对堂弟那台没 root 的机器尤其合适。
 *
 * 默认关闭，用户在「快捷按钮」页显式开启，避免装完就多一条通知。
 */
object QuickNotify {

    private const val CHANNEL = "inkwell_quick"
    private const val NOTI_ID = 20261105

    fun hasPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    /** 按当前开关与状态刷新通知（关闭 / 无权限时撤掉） */
    fun sync(ctx: Context) {
        if (!EinkPrefs.isQuickNotifyEnabled(ctx) || !hasPermission(ctx)) {
            cancel(ctx)
            return
        }
        try {
            ensureChannel(ctx)

            val on = EinkPrefs.isEnabled(ctx)
            val mode = EinkPrefs.getMode(ctx).ifBlank { "彩纸模式" }
            val text = if (on) "当前：$mode · 点下方按钮直接切换"
                       else "已关闭（原色）· 点下方按钮直接开启"

            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_ink)
                .setContentTitle("墨境 · 一屏三模")
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(openApp(ctx))

            for (id in QuickActions.ALL) {
                b.addAction(
                    QuickActions.icon(id),
                    QuickActions.shortLabel(id),
                    actionIntent(ctx, id)
                )
            }
            NotificationManagerCompat.from(ctx).notify(NOTI_ID, b.build())
        } catch (_: Throwable) {
            // 某些 ROM 对常驻通知有额外限制，失败不影响主功能
        }
    }

    fun cancel(ctx: Context) {
        try { NotificationManagerCompat.from(ctx).cancel(NOTI_ID) } catch (_: Throwable) { }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val c = NotificationChannel(
            CHANNEL, "墨境快捷开关", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "下拉通知栏直接切换原色 / 彩纸 / 墨纸"
            setShowBadge(false)
        }
        nm.createNotificationChannel(c)
    }

    private fun actionIntent(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx, QuickActionActivity::class.java).apply {
            action = QuickActions.ACTION + "." + id
            putExtra(QuickActions.EXTRA, id)
        }
        // 每个 action 用不同的 requestCode，否则 PendingIntent 会被复用导致 extra 串味
        return PendingIntent.getActivity(
            ctx, id.hashCode() and 0x7fffffff, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openApp(ctx: Context): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            ctx, 9001, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

}
