package com.inkwell.mode

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * 显示模式页。
 *
 * 0.2.2 起改为「一屏三模」为主、面板型号档位收进折叠区。
 * 参考联想小新彩墨柔光版按内容分档的思路 —— 用户不需要知道 Kaleido 3
 * 是什么，只需要知道「我现在要看绘本」还是「我要读小说」。
 */
class PresetActivity : AppCompatActivity() {

    private lateinit var boxModes: LinearLayout
    private lateinit var tvModeTip: TextView
    private lateinit var btnAdvanced: Button
    private lateinit var rgAdvanced: RadioGroup
    private lateinit var tvPresetTip: TextView
    private lateinit var lbSat: TextView
    private lateinit var lbBlack: TextView
    private lateinit var lbWhite: TextView
    private lateinit var lbWarm: TextView
    private lateinit var sbSat: SeekBar
    private lateinit var sbBlack: SeekBar
    private lateinit var sbWhite: SeekBar
    private lateinit var sbWarm: SeekBar

    private val modeButtons = LinkedHashMap<String, Button>()
    private var params: EinkParams = EinkParams.KALEIDO3
    private var currentMode: String = DisplayMode.COLOR_PAPER
    private var advancedVisible = false
    private var updating = false

    private val advancedNames = EinkParams.presetList().keys.toList()

    private val ADV_TIPS = mapOf(
        "Kaleido 3（默认）" to "市面上 99% 彩墨阅读器的观感基准，偏淡偏灰。",
        "Gallery 3（更艳）" to "粒子混合无滤光片，更亮更艳，静态图观感上限。",
        "漫画优化" to "黑位压到 0.08 保住黑色轮廓线，色阶提高让天空渐变不出色带。",
        "黑白纸质" to "白位 0.96 / 黑位 0.08 比彩色档更高，文字更锐利，带米白纸底。",
        "Spectra 6（印刷感）" to "最接近印刷品的质感，色域比 Kaleido 3 宽一些。",
        "关闭效果（原色）" to "屏幕完全恢复原色。"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_preset)

        boxModes = findViewById(R.id.boxModes)
        tvModeTip = findViewById(R.id.tvModeTip)
        btnAdvanced = findViewById(R.id.btnAdvanced)
        rgAdvanced = findViewById(R.id.rgAdvanced)
        tvPresetTip = findViewById(R.id.tvPresetTip)
        lbSat = findViewById(R.id.lbSat)
        lbBlack = findViewById(R.id.lbBlack)
        lbWhite = findViewById(R.id.lbWhite)
        lbWarm = findViewById(R.id.lbWarm)
        sbSat = findViewById(R.id.sbSat)
        sbBlack = findViewById(R.id.sbBlack)
        sbWhite = findViewById(R.id.sbWhite)
        sbWarm = findViewById(R.id.sbWarm)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        params = EinkPrefs.load(this)
        currentMode = restoreMode()

        buildModeCards()
        buildAdvanced()
        paramsToUi()
        refreshModeUi()

        btnAdvanced.setOnClickListener {
            advancedVisible = !advancedVisible
            rgAdvanced.visibility = if (advancedVisible) android.view.View.VISIBLE
                else android.view.View.GONE
            btnAdvanced.text = if (advancedVisible) "收起进阶档位 ▲" else "进阶档位（按面板型号细分）▼"
        }

        findViewById<Button>(R.id.btnApply).setOnClickListener {
            uiToParams()
            currentMode = DisplayMode.CUSTOM
            EinkPrefs.setMode(this, DisplayMode.CUSTOM)
            val r = ConfigStore.saveAndApply(this, params, "自定义", true)
            refreshModeUi()
            toast(if (r.fileOk) "已应用，约 2 秒内生效" else "配置文件写入失败，请查看诊断页")
        }

        findViewById<Button>(R.id.btnOff).setOnClickListener {
            selectMode(DisplayMode.ORIGINAL)
            toast("已恢复原色")
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                if (!updating) uiToParams()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        }
        sbSat.setOnSeekBarChangeListener(listener)
        sbBlack.setOnSeekBarChangeListener(listener)
        sbWhite.setOnSeekBarChangeListener(listener)
        sbWarm.setOnSeekBarChangeListener(listener)
    }

    /**
     * 恢复当前模式。
     * 从 0.2.1 升级上来的用户没有 K_MODE，用老预设名归类，避免一进来显示错档。
     */
    private fun restoreMode(): String {
        val saved = EinkPrefs.getMode(this)
        if (saved.isNotBlank()) return saved
        val legacy = DisplayMode.fromLegacyPreset(EinkPrefs.getPreset(this))
        EinkPrefs.setMode(this, legacy)
        return legacy
    }

    private fun buildModeCards() {
        for (mode in DisplayMode.ALL) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                setPadding(14, 12, 14, 12)
            }

            val btn = Button(this).apply {
                text = mode
                isAllCaps = false
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            btn.setOnClickListener { selectMode(mode) }

            val usage = TextView(this).apply {
                text = DisplayMode.usage(mode)
                setTextColor(0xFF555555.toInt())
                textSize = 11.5f
                setLineSpacing(3f, 1f)
                setPadding(0, 2, 0, 4)
            }
            val nums = TextView(this).apply {
                text = DisplayMode.numbers(mode)
                setTextColor(0xFF999999.toInt())
                textSize = 10.5f
            }

            card.addView(btn)
            card.addView(usage)
            card.addView(nums)

            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            boxModes.addView(card, lp)
            modeButtons[mode] = btn
        }
    }

    /** 点选三模：写入模式 + 参数 + 开关，一次下发 */
    private fun selectMode(mode: String) {
        currentMode = mode
        params = DisplayMode.params(mode)
        val enabled = !DisplayMode.isOff(mode)

        EinkPrefs.setMode(this, mode)
        val r = ConfigStore.saveAndApply(this, params, mode, enabled)

        paramsToUi()
        refreshModeUi()
        toast(if (r.fileOk) "已切换到「$mode」" else "配置文件写入失败，请查看诊断页")
    }

    private fun refreshModeUi() {
        for ((mode, btn) in modeButtons) {
            val on = mode == currentMode
            btn.text = if (on) "$mode   ✓ 当前" else mode
            btn.backgroundTintList = ColorStateList.valueOf(
                if (on) 0xFF2C5F3D.toInt() else 0xFFEDE9E0.toInt()
            )
            btn.setTextColor(if (on) 0xFFFFFFFF.toInt() else 0xFF333333.toInt())
        }
        tvModeTip.text = when {
            currentMode == DisplayMode.CUSTOM -> "当前：自定义参数（拖过滑块或选了进阶档位）"
            else -> "当前：${DisplayMode.usage(currentMode)}\n${DisplayMode.numbers(currentMode)}"
        }
    }

    private fun buildAdvanced() {
        advancedNames.forEachIndexed { i, name ->
            val rb = RadioButton(this).apply {
                text = name
                id = 3000 + i
                textSize = 12.5f
                setPadding(0, 7, 0, 7)
            }
            rgAdvanced.addView(rb)
        }
        rgAdvanced.setOnCheckedChangeListener { _, id ->
            if (updating) return@setOnCheckedChangeListener
            val name = advancedNames.getOrNull(id - 3000) ?: return@setOnCheckedChangeListener
            params = EinkParams.byName(name)
            currentMode = DisplayMode.CUSTOM
            EinkPrefs.setMode(this, DisplayMode.CUSTOM)
            val enabled = !name.contains("关闭")
            ConfigStore.saveAndApply(this, params, name, enabled)
            paramsToUi()
            refreshModeUi()
            showAdvTip(name)
            toast("已切换到「$name」")
        }
    }

    private fun showAdvTip(name: String) {
        val t = ADV_TIPS[name]
        if (t.isNullOrBlank()) {
            tvPresetTip.visibility = android.view.View.GONE
        } else {
            tvPresetTip.visibility = android.view.View.VISIBLE
            tvPresetTip.text = t
        }
    }

    private fun paramsToUi() {
        updating = true
        // +0.5 四舍五入：0.97f 用裸 toInt() 会算成 36（浮点 36.999...），
        // 滑块一进页面就显示 0.96，和真实下发的参数对不上。
        sbSat.progress = (params.saturation * 100f + 0.5f).toInt().coerceIn(0, 100)
        sbBlack.progress = (params.black * 100f + 0.5f).toInt().coerceIn(0, 60)
        sbWhite.progress = ((params.white - 0.6f) * 100f + 0.5f).toInt().coerceIn(0, 40)
        sbWarm.progress = (params.warmth * 100f + 0.5f).toInt().coerceIn(0, 100)
        updateLabels()
        updating = false
    }

    private fun uiToParams() {
        params = EinkParams(
            saturation = sbSat.progress / 100f,
            black = sbBlack.progress / 100f,
            white = 0.6f + sbWhite.progress / 100f,
            warmth = sbWarm.progress / 100f
        )
        updateLabels()
    }

    private fun updateLabels() {
        lbSat.text = "饱和度：%.2f".format(params.saturation)
        lbBlack.text = "黑位（墨黑）：%.2f  → sRGB %d".format(params.black, (params.black * 255).toInt())
        lbWhite.text = "白位（纸白）：%.2f  → sRGB %d".format(params.white, (params.white * 255).toInt())
        lbWarm.text = "纸色暖度：%.2f".format(params.warmth)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
