package com.inkwell.mode

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/** 诊断与关于（0.2.2：如实反映三条通道，不再把"文件没写"当成功能故障） */
class DiagActivity : AppCompatActivity() {

    private lateinit var tvChannels: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvAbout: TextView
    private lateinit var btnOverlayToggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diag)

        tvChannels = findViewById(R.id.tvChannels)
        tvLog = findViewById(R.id.tvLog)
        tvAbout = findViewById(R.id.tvAbout)
        btnOverlayToggle = findViewById(R.id.btnOverlayToggle)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        btnOverlayToggle.setOnClickListener {
            val next = !EinkPrefs.isOverlayEnabled(this)
            EinkPrefs.setOverlayEnabled(this, next)
            if (!next) OverlayService.stop(this)
            syncOverlayButton()
            log(
                if (next) "降级遮罩已开启。\n注意：它只能加色，无法灰度化，效果明显弱于系统级矩阵。"
                else "降级遮罩已关闭。\n已 root / 已装 LSPosed 的机器不需要它。"
            )
        }
        syncOverlayButton()

        findViewById<Button>(R.id.btnTestRaw).setOnClickListener {
            val ok = ConfigStore.testRawMatrix()
            log(if (ok) {
                "已直接下发矩阵。\n屏幕立刻变化 → SurfaceFlinger 侧正常。\n无变化 → 本机 transaction code 不是 1015（不影响本模块，模块走 DisplayTransformManager）。"
            } else "下发失败：需要 root，或 su 未授权。")
        }

        findViewById<Button>(R.id.btnResetRaw).setOnClickListener {
            val ok = ConfigStore.resetRawMatrix()
            log(if (ok) "已恢复（1015 i32 0）" else "恢复失败：需要 root。")
        }

        findViewById<Button>(R.id.btnCopyDiag).setOnClickListener { copyDiag() }

        tvAbout.text = """
墨境 Inkwell v${BuildConfig.GIT_VERSION}（versionCode ${BuildConfig.VERSION_CODE}）

在系统进程注入全局色彩矩阵，把屏幕变成彩色墨水屏观感。
零延迟、零耗电、开机自动生效，且不涉及悬浮窗权限。

设备：${Build.MANUFACTURER} ${Build.MODEL}
系统：Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）

已知限制：矩阵只能做线性变换，色阶量化、抖动、滤光网格无法实现。
        """.trimIndent()

        refreshChannels()
    }

    override fun onResume() {
        super.onResume()
        refreshChannels()
        syncOverlayButton()
    }

    private fun syncOverlayButton() {
        val on = EinkPrefs.isOverlayEnabled(this)
        btnOverlayToggle.text = if (on) "降级遮罩：已开启（点击关闭）" else "降级遮罩：已关闭（点击开启）"
    }

    private fun refreshChannels() {
        Thread {
            val root = ConfigStore.hasRoot()
            val active = EinkPrefs.isModuleActive()
            val mark = ConfigStore.probeModuleMark()
            val confRaw = ControlContract.readRaw()

            runOnUiThread {
                val confLine = if (confRaw.isBlank()) {
                    "未写入（Android 16 SELinux 限制，需 su 授权；不影响开关）"
                } else "已写入 ✓"

                tvChannels.text = """
① 广播通道（主，即时，root-free）：可用 ✓
   指令直达 system_server 的动态接收器

② 文件通道（持久化，需 root）：$confLine
   路径 ${ControlContract.CONF_FILE}
   内容：${if (confRaw.isBlank()) "（无）" else confRaw}

③ setprop 通道（重启保持，需 root）：${if (root) "可用 ✓" else "未授权 su（重启后会回默认档）"}

模块自检：${if (active) "已激活 ✓" else "未激活（作用域需勾「墨境」本身）"}
激活标记：${if (mark) "存在 ✓" else "未检测到"}
降级遮罩：${if (EinkPrefs.isOverlayEnabled(this)) "已开启" else "已关闭（默认）"}
效果状态：${if (EinkPrefs.isEnabled(this)) "开启" else "已关闭"}
                """.trimIndent()
            }
        }.start()
    }

    private fun copyDiag() {
        val text = """
墨境 Inkwell 诊断信息
版本：v${BuildConfig.GIT_VERSION} (${BuildConfig.VERSION_CODE})
设备：${Build.MANUFACTURER} ${Build.MODEL}
系统：Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}
模块激活：${EinkPrefs.isModuleActive()}
root：${ConfigStore.hasRoot()}
效果：${if (EinkPrefs.isEnabled(this)) "开启" else "关闭"}
降级遮罩：${if (EinkPrefs.isOverlayEnabled(this)) "开启" else "关闭"}
配置：${ControlContract.readRaw().ifBlank { "(无)" }}
        """.trimIndent()

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("inkwell-diag", text))
        log("已复制到剪贴板\n\n$text")
    }

    private fun log(s: String) { tvLog.text = s }
}
