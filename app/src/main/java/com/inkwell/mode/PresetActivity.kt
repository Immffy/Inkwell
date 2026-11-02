package com.inkwell.mode

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/** 预设选择 + 三个滑块微调 */
class PresetActivity : AppCompatActivity() {

    private lateinit var rgPreset: RadioGroup
    private lateinit var tvPresetTip: TextView
    private lateinit var lbSat: TextView
    private lateinit var lbBlack: TextView
    private lateinit var lbWhite: TextView
    private lateinit var sbSat: SeekBar
    private lateinit var sbBlack: SeekBar
    private lateinit var sbWhite: SeekBar

    private var params: EinkParams = EinkParams.KALEIDO3
    private var updating = false
    private var currentPreset = "Kaleido 3（默认）"

    private val names = EinkParams.presetList().keys.toList()

    private val TIPS = mapOf(
        "Kaleido 3（默认）" to "市面上 99% 彩墨阅读器的实际观感基准，偏淡偏灰。饱和度约为 sRGB 的四到六成。",
        "Gallery 3（更艳）" to "粒子混合无滤光片，更亮更艳，是静态图的观感上限。但真机刷新要 1-2 秒。",
        "漫画优化" to "黑位压到 0.08 保住黑色轮廓线（漫画的灵魂），色阶提高让天空渐变不出色带。",
        "黑白纸质" to "注意白位 0.96 / 黑位 0.08 比彩色档更高——没有滤光片吃光，真实黑白电子纸反而更亮、对比更强、文字更锐利。",
        "Spectra 6（印刷感）" to "最接近印刷品的质感，色域比 Kaleido 3 宽一些。",
        "关闭效果（原色）" to "饱和度 1.0、白位 1.0、黑位 0 —— 屏幕完全恢复原色。"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preset)

        rgPreset = findViewById(R.id.rgPreset)
        tvPresetTip = findViewById(R.id.tvPresetTip)
        lbSat = findViewById(R.id.lbSat)
        lbBlack = findViewById(R.id.lbBlack)
        lbWhite = findViewById(R.id.lbWhite)
        sbSat = findViewById(R.id.sbSat)
        sbBlack = findViewById(R.id.sbBlack)
        sbWhite = findViewById(R.id.sbWhite)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        params = EinkPrefs.load(this)
        currentPreset = EinkPrefs.getPreset(this)

        buildPresets()
        paramsToUi()
        showTip(currentPreset)

        findViewById<Button>(R.id.btnApply).setOnClickListener {
            uiToParams()
            currentPreset = "自定义"
            val r = ConfigStore.saveAndApply(this, params, currentPreset, true)
            toast(if (r.fileOk) "已应用，约 2 秒内生效" else "配置文件写入失败，请查看诊断页")
        }

        findViewById<Button>(R.id.btnOff).setOnClickListener {
            ConfigStore.setEnabled(this, false)
            EinkPrefs.save(this, EinkParams.OFF)
            finish()
            toast("已关闭效果，屏幕将恢复原色")
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (updating) return
                uiToParams()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        }
        sbSat.setOnSeekBarChangeListener(listener)
        sbBlack.setOnSeekBarChangeListener(listener)
        sbWhite.setOnSeekBarChangeListener(listener)
    }

    private fun buildPresets() {
        names.forEachIndexed { i, name ->
            val rb = RadioButton(this).apply {
                text = name
                id = 2000 + i
                textSize = 13f
                setPadding(0, 8, 0, 8)
            }
            rgPreset.addView(rb)
        }
        // 回显上次选择
        val idx = names.indexOf(currentPreset)
        if (idx >= 0) rgPreset.check(2000 + idx)

        rgPreset.setOnCheckedChangeListener { _, id ->
            if (updating) return@setOnCheckedChangeListener
            val name = names.getOrNull(id - 2000) ?: return@setOnCheckedChangeListener
            params = EinkParams.byName(name)
            currentPreset = name
            paramsToUi()
            showTip(name)
            // 选「关闭效果」时同时把开关关掉，语义才一致
            val enabled = (name != "关闭效果（原色）")
            val r = ConfigStore.saveAndApply(this, params, name, enabled)
            toast(if (r.fileOk) "已切换到「$name」" else "配置文件写入失败，请查看诊断页")
        }
    }

    private fun showTip(name: String) {
        val t = TIPS[name]
        if (t.isNullOrBlank()) {
            tvPresetTip.visibility = android.view.View.GONE
        } else {
            tvPresetTip.visibility = android.view.View.VISIBLE
            tvPresetTip.text = t
        }
    }

    private fun paramsToUi() {
        updating = true
        sbSat.progress = (params.saturation * 100).toInt().coerceIn(0, 100)
        sbBlack.progress = (params.black * 100).toInt().coerceIn(0, 60)
        sbWhite.progress = ((params.white - 0.6f) * 100).toInt().coerceIn(0, 40)
        updateLabels()
        updating = false
    }

    private fun uiToParams() {
        params = EinkParams(
            saturation = sbSat.progress / 100f,
            black = sbBlack.progress / 100f,
            white = 0.6f + sbWhite.progress / 100f
        )
        updateLabels()
    }

    private fun updateLabels() {
        lbSat.text = "饱和度：%.2f".format(params.saturation)
        lbBlack.text = "黑位（墨黑）：%.2f  → sRGB %d".format(params.black, (params.black * 255).toInt())
        lbWhite.text = "白位（纸白）：%.2f  → sRGB %d".format(params.white, (params.white * 255).toInt())
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
