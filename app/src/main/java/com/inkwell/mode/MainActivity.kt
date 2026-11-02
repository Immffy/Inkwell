package com.inkwell.mode

import android.content.ComponentName
import android.os.Bundle
import android.service.quicksettings.TileService
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * 主界面：只放「状态 + 总开关 + 恢复默认 + 四个入口」。
 * 具体调参拆到子界面，避免一个页面塞太多导致滑动混乱。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvModule: TextView
    private lateinit var tvRoot: TextView
    private lateinit var tvState: TextView
    private lateinit var tvVersion: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnRestore: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvModule = findViewById(R.id.tvModule)
        tvRoot = findViewById(R.id.tvRoot)
        tvState = findViewById(R.id.tvState)
        tvVersion = findViewById(R.id.tvVersion)
        btnToggle = findViewById(R.id.btnToggle)
        btnRestore = findViewById(R.id.btnRestore)

        tvVersion.text = "v${BuildConfig.GIT_VERSION}"

        // 返回按钮：主界面不需要
        findViewById<MaterialToolbar>(R.id.toolbar).navigationIcon = null

        btnToggle.setOnClickListener {
            val next = !EinkPrefs.isEnabled(this)
            val r = ConfigStore.setEnabled(this, next)
            syncState()
            toast(
                if (next) "已开启，约 2 秒内生效"
                else "已关闭，约 2 秒内恢复原色"
            )
            if (!r.fileOk) toast("警告：配置文件写入失败，模块可能读不到")
        }

        // 恢复默认：一步到位 —— 关效果 + 参数复位 + 立刻下发
        btnRestore.setOnClickListener {
            EinkPrefs.save(this, EinkParams.OFF)
            EinkPrefs.setPreset(this, "关闭效果（原色）")
            val r = ConfigStore.setEnabled(this, false)
            syncState()
            toast(if (r.fileOk) "已恢复默认显示" else "配置文件写入失败，请查看诊断页")
        }

        findViewById<Button>(R.id.btnPreset).setOnClickListener {
            startActivity(android.content.Intent(this, PresetActivity::class.java))
        }
        findViewById<Button>(R.id.btnRules).setOnClickListener {
            startActivity(android.content.Intent(this, RulesActivity::class.java))
        }
        findViewById<Button>(R.id.btnDiag).setOnClickListener {
            startActivity(android.content.Intent(this, DiagActivity::class.java))
        }

        syncState()
        checkStatus()
        // 让通知栏快捷开关的图标状态与实际同步
        TileService.requestListeningState(
            this,
            ComponentName(this, EinkTileService::class.java)
        )
    }

    override fun onResume() {
        super.onResume()
        syncState()
        checkStatus()
    }

    private fun syncState() {
        val on = EinkPrefs.isEnabled(this)
        btnToggle.text = if (on) "关闭墨水屏效果" else "开启墨水屏效果"
        tvState.text = if (on) "效果：开启中" else "效果：已关闭（原色）"
        tvState.setTextColor(if (on) 0xFF1E8449.toInt() else 0xFF888888.toInt())
        btnRestore.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun checkStatus() {
        val active = EinkPrefs.isModuleActive()
        tvModule.text = if (active) {
            "模块状态：已激活 ✓"
        } else {
            "模块状态：未激活\n" +
            "① LSPosed 中启用本模块\n" +
            "② 作用域勾选「系统框架 / android」和「墨境」本身\n" +
            "③ 重启手机\n" +
            "（若屏幕效果已生效但这里显示未激活，多半是漏勾「墨境」，不影响使用）"
        }
        tvModule.setTextColor(if (active) 0xFF1E8449.toInt() else 0xFFC0392B.toInt())

        Thread {
            val root = ConfigStore.hasRoot()
            runOnUiThread {
                tvRoot.text = if (root) {
                    "root：已获取 ✓（可选增强，开机更早生效）"
                } else {
                    "root：未获取（不影响使用，开关走文件通道）"
                }
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
