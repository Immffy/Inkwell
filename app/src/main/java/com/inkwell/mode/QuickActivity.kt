package com.inkwell.mode

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * 快捷按钮设置页。
 *
 * 单独开一页而不塞进诊断页，是因为这是「日常要用」的入口，
 * 不是排查问题时才看的。
 *
 * 0.2.3 起把「系统快捷开关」置顶：QsTileInstaller 有 root 时直接把
 * 磁贴写进 sysui_qs_tiles，一键加入控制中心常用区；无 root 时给出
 * 编辑页手动添加的路径。其余三条是不依赖控制中心的补充入口。
 */
class QuickActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnNotifyToggle: Button
    private lateinit var tvTileState: TextView

    /** 申请通知权限期间暂存「用户想开启」，回到前台后补上 */
    private var pendingEnableNotify = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quick)

        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        btnNotifyToggle = findViewById(R.id.btnNotifyToggle)
        tvTileState = findViewById(R.id.tvTileState)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        bind(R.id.btnPinToggle, QuickActions.TOGGLE)
        bind(R.id.btnPinOriginal, QuickActions.ORIGINAL)
        bind(R.id.btnPinColor, QuickActions.COLOR_PAPER)
        bind(R.id.btnPinInk, QuickActions.INK_PAPER)

        btnNotifyToggle.setOnClickListener { toggleNotify() }

        findViewById<Button>(R.id.btnInstallTile).setOnClickListener { runTileOp(false) }
        findViewById<Button>(R.id.btnUninstallTile).setOnClickListener { runTileOp(true) }

        // 如果这个页面是被 QS 磁贴长按拉起来的，也刷新一下状态
        syncUi()
    }

    override fun onResume() {
        super.onResume()
        // 系统权限对话框关闭后本页会重新回到前台，在这里收尾，
        // 不用覆写 onRequestPermissionsResult（各 support 库签名略有差异）。
        if (pendingEnableNotify) {
            val granted = QuickNotify.hasPermission(this)
            pendingEnableNotify = false
            applyNotify(granted)
            if (!granted) {
                tvLog.text = "没有通知权限，常驻通知用不了。\n可去 设置 → 应用管理 → 墨境 → 通知 里手动打开后返回本页重试。"
            }
        }
        syncUi()
        refreshTileState()
    }

    private fun bind(viewId: Int, actionId: String) {
        findViewById<Button>(viewId).setOnClickListener {
            val supported = ShortcutHelper.isSupported(this)
            val sent = if (supported) ShortcutHelper.pin(this, actionId) else false
            tvLog.text = when {
                !supported -> "这台设备的桌面不支持固定快捷方式。\n请改用「长按 App 图标」或开启下方的通知栏快捷按钮。"
                sent -> "已请求添加「${QuickActions.shortLabel(actionId)}」到桌面。\n请在弹出的确认框里点「添加」。\n\n点这个图标会直接切模式，不会打开 App。"
                else -> "请求失败，桌面可能拒绝了固定快捷方式。\n请改用「长按 App 图标」或通知栏快捷按钮。"
            }
        }
    }

    private fun toggleNotify() {
        val enable = !EinkPrefs.isQuickNotifyEnabled(this)
        if (enable && !QuickNotify.hasPermission(this)) {
            // Android 13+ 要通知权限；先申请，回到前台时在 onResume 里收尾
            pendingEnableNotify = true
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
            } else {
                applyNotify(true)
                pendingEnableNotify = false
            }
            return
        }
        applyNotify(enable)
    }

    private fun applyNotify(enable: Boolean) {
        EinkPrefs.setQuickNotifyEnabled(this, enable)
        try {
            QuickNotify.sync(this)
        } catch (_: Throwable) { }
        tvLog.text = if (enable) {
            "常驻通知已开启。下拉通知栏即可看到四个按钮，点一下直接切模式。"
        } else {
            "常驻通知已关闭。"
        }
        syncUi()
    }

    private fun syncUi() {
        val on = EinkPrefs.isEnabled(this)
        val mode = EinkPrefs.getMode(this).ifBlank { "彩纸模式" }
        tvStatus.text = if (on) "当前状态：开启中 · $mode" else "当前状态：已关闭（原色）"

        val notifyOn = EinkPrefs.isQuickNotifyEnabled(this)
        btnNotifyToggle.text = if (notifyOn) "常驻通知：已开启（点击关闭）"
                              else "常驻通知：已关闭（点击开启）"

        // 权限可能被用户在系统设置里关掉，如实反映
        if (notifyOn && !QuickNotify.hasPermission(this)) {
            tvStatus.text = tvStatus.text.toString() + "\n（通知权限已被关闭，快捷按钮不会显示）"
        }

        // 页面打开时顺手让磁贴与通知状态跟上最新值
        try { QuickActions.refresh(this) } catch (_: Throwable) { }
    }

    /**
     * 一键加入 / 移除系统快捷开关。
     * 必须放后台：QsTileInstaller 会起 su 进程并 waitFor()，
     * 授权弹窗期间不能把 UI 线程卡住。
     */
    private fun runTileOp(remove: Boolean) {
        tvLog.text = if (remove)
            "正在从系统快捷开关移除…"
        else
            "正在写入系统快捷开关…\n如果弹出 root 授权请点「允许」。"
        Thread {
            val r = if (remove) QsTileInstaller.uninstall(applicationContext)
                    else QsTileInstaller.install(applicationContext)
            runOnUiThread {
                tvLog.text = r.message
                refreshTileState()
            }
        }.apply { isDaemon = true }.start()
    }

    /** 后台查询是否已加入，避免每次 onResume 都阻塞 UI */
    private fun refreshTileState() {
        Thread {
            val installed = QsTileInstaller.isInstalled(applicationContext)
            runOnUiThread {
                tvTileState.text = when (installed) {
                    true -> "状态：已加入系统快捷开关 ✓  下拉控制中心即可看到"
                    false -> "状态：尚未加入  点下面按钮一键添加"
                    null -> "状态：无法查询（未授予 root）  可在编辑页手动添加"
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
