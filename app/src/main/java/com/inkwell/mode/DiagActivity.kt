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
import java.io.File

/** 诊断与关于 */
class DiagActivity : AppCompatActivity() {

    private lateinit var tvChannels: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvAbout: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diag)

        tvChannels = findViewById(R.id.tvChannels)
        tvLog = findViewById(R.id.tvLog)
        tvAbout = findViewById(R.id.tvAbout)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

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
    }

    private fun refreshChannels() {
        Thread {
            val root = ConfigStore.hasRoot()
            val confFile = File(ControlContract.CONF_FILE)
            val confExists = confFile.exists()
            val confText = if (confExists) {
                try { confFile.readText().trim() } catch (_: Throwable) { "(读取失败)" }
            } else "(尚未写入)"

            runOnUiThread {
                tvChannels.text = """
① 文件通道（主，root-free）：${if (confExists) "可用 ✓" else "未写入 ✗"}
   路径 ${ControlContract.CONF_FILE}
   内容：$confText

② 广播通道（即时，root-free）：随配置一起发送

③ setprop 通道（可选，需 root）：${if (root) "可用 ✓" else "无 root，跳过（不影响功能）"}

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
配置：${try { File(ControlContract.CONF_FILE).readText().trim() } catch (_: Throwable) { "(无)" }}
        """.trimIndent()

        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("inkwell-diag", text))
        log("已复制到剪贴板\n\n$text")
    }

    private fun log(s: String) { tvLog.text = s }
}
