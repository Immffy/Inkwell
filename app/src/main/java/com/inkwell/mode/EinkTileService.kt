package com.inkwell.mode

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 控制中心快捷开关（Android 标准 QS Tile / TileService）。
 *
 * 这就是「系统快捷开关」：在澎湃 OS 里和护眼模式、深色模式并列，
 * 点按切换开/关，长按进显示模式页，可在「编辑与排序」里拖动排序。
 *
 * ── 0.2.3 更正一处此前的误判 ──
 *
 * 之前注释写「澎湃 OS 只列系统认可的磁贴，第三方 TileService 不进列表」，
 * 这是错的。HyperOS 的「编辑与排序」同样读取 Settings.Secure 的
 * sysui_qs_tiles，第三方磁贴以 custom(<包名>/<类名>) 形式参与其中，
 * 会出现在「系统快捷开关」区域，只是列表较长、需要往下滚才能看到；
 * 装完 App 后 SystemUI 有时还要刷新一次才会重新枚举。
 *
 * 因此本版新增 QsTileInstaller：有 root 时直接把 spec 写进 sysui_qs_tiles，
 * 一键加入常用区，不用手动翻编辑页；无 root 时手动添加照样有效。
 *
 * ── 点击行为 ──
 *
 * 先在**本地**翻转偏好并立刻刷新磁贴（点一下马上看到开/关变化），
 * 再把指令交给 QuickActions 在后台下发 —— su 持久化若弹授权框，
 * 不会卡住 SystemUI 绑定的线程、让整个控制中心顿住。
 */
class EinkTileService : TileService() {

    override fun onTileAdded() {
        super.onTileAdded()
        updateTile()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val next = !EinkPrefs.isEnabled(this)

        // 即时反馈：不等广播、不等 su 授权
        EinkPrefs.setEnabled(this, next)
        updateTile()
        try { QuickNotify.sync(this) } catch (_: Throwable) { }

        // 真正的下发（广播 + setprop + 文件）放后台
        QuickActions.setEnabled(this, next)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val on = EinkPrefs.isEnabled(this)
        val mode = EinkPrefs.getMode(this).ifBlank { "彩纸模式" }

        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (on) "墨境 · 开" else "墨境 · 关"
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (on) mode else "点按开启墨水屏"
        }
        tile.updateTile()
    }
}
