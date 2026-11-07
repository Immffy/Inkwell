package com.inkwell.mode

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build

/**
 * 桌面固定快捷按钮。
 *
 * 这是澎湃 OS 上最像「护眼模式按钮」的形态：
 * 在桌面生成一个独立图标，点一下直接切模式，**不打开 App、不进控制中心**。
 *
 * 用系统标准的 requestPinShortcut（API 26+），HyperOS 桌面会弹一次确认框。
 * 比老式 INSTALL_SHORTCUT 广播可靠得多 —— 那个在国产 ROM 上基本已被禁。
 */
object ShortcutHelper {

    fun isSupported(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return false
        return try { sm.isRequestPinShortcutSupported } catch (_: Throwable) { false }
    }

    /**
     * 请求把一个动作固定到桌面。
     * 返回 true 只代表「已向系统发起请求」，用户还需在弹框里确认。
     */
    fun pin(ctx: Context, id: String): Boolean {
        if (!isSupported(ctx)) return false
        return try {
            val sm = ctx.getSystemService(ShortcutManager::class.java) ?: return false
            val intent = Intent(ctx, QuickActionActivity::class.java).apply {
                action = QuickActions.ACTION + "." + id
                putExtra(QuickActions.EXTRA, id)
                // 固定快捷方式不能带 NEW_TASK 之外的奇怪 flag
            }
            val info = ShortcutInfo.Builder(ctx, "pin_" + id)
                .setShortLabel(QuickActions.shortLabel(id))
                .setLongLabel(QuickActions.longLabel(id))
                .setIcon(Icon.createWithResource(ctx, QuickActions.icon(id)))
                .setIntent(intent)
                .build()
            sm.requestPinShortcut(info, null)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
