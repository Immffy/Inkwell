package com.inkwell.mode

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 通知栏快捷开关（下拉状态栏里的磁贴）。
 *
 * 这是「恢复默认显示」最快的入口 —— 不用打开 App，下拉点一下就切。
 * 走文件通道，不需要 root。
 *
 * 添加方式：下拉通知栏 → 编辑磁贴 → 找到「墨境」拖到常用区。
 */
class EinkTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val next = !EinkPrefs.isEnabled(this)
        ConfigStore.setEnabled(this, next)

        // 通知栏收起后再执行后续（Android 14+ 要求）
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(pendingIntent())
        }
        updateTile()
    }

    private fun pendingIntent(): android.app.PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return android.app.PendingIntent.getActivity(
            this, 0, i,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val on = EinkPrefs.isEnabled(this)
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (on) "墨境·开" else "墨境·关"
        tile.subtitle = if (on) "点按恢复原色" else "点按开启墨水屏"
        tile.updateTile()
    }
}
