package com.inkwell.mode

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var tvModule: TextView
    private lateinit var tvRoot: TextView
    private lateinit var tvLog: TextView
    private lateinit var tvPkgs: TextView
    private lateinit var btnToggle: Button
    private lateinit var rgPreset: RadioGroup
    private lateinit var lbSat: TextView
    private lateinit var lbBlack: TextView
    private lateinit var lbWhite: TextView
    private lateinit var sbSat: SeekBar
    private lateinit var sbBlack: SeekBar
    private lateinit var sbWhite: SeekBar
    private lateinit var etPkg: EditText

    private var params: EinkParams = EinkParams.KALEIDO3
    private var updating = false
    private var hasRoot = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 显示版本号：排查问题时先确认装的到底是哪一版
        findViewById<TextView>(R.id.tvVersion).text = "v${BuildConfig.GIT_VERSION}"

        tvModule = findViewById(R.id.tvModule)
        tvRoot = findViewById(R.id.tvRoot)
        tvLog = findViewById(R.id.tvLog)
        tvPkgs = findViewById(R.id.tvPkgs)
        btnToggle = findViewById(R.id.btnToggle)
        rgPreset = findViewById(R.id.rgPreset)
        lbSat = findViewById(R.id.lbSat)
        lbBlack = findViewById(R.id.lbBlack)
        lbWhite = findViewById(R.id.lbWhite)
        sbSat = findViewById(R.id.sbSat)
        sbBlack = findViewById(R.id.sbBlack)
        sbWhite = findViewById(R.id.sbWhite)
        etPkg = findViewById(R.id.etPkg)

        params = EinkPrefs.load(this)
        buildPresets()
        paramsToUi()
        refreshPkgs()

        btnToggle.setOnClickListener {
            val on = !EinkPrefs.isEnabled(this)
            EinkPrefs.setEnabled(this, on)
            val r = PropWriter.apply(this, params, on)
            log("setprop -> ${if (r.ok) "OK" else "FAILED"}\n${r.cmd}")
            syncToggle()
        }

        findViewById<Button>(R.id.btnApply).setOnClickListener {
            uiToParams()
            EinkPrefs.save(this, params)
            val r = PropWriter.apply(this, params, EinkPrefs.isEnabled(this))
            log("应用微调 -> ${if (r.ok) "OK" else "FAILED"}\n${r.cmd}\n" +
                ColorMatrixBuilder.dumpForDebug(params))
        }

        findViewById<Button>(R.id.btnAddPkg).setOnClickListener {
            val pkg = etPkg.text.toString().trim()
            if (pkg.isBlank()) return@setOnClickListener
            val set = loadPkgs().toMutableSet().apply { add(pkg) }
            savePkgs(set)
            etPkg.setText("")
        }

        findViewById<Button>(R.id.btnClearPkg).setOnClickListener {
            savePkgs(emptySet())
        }

        findViewById<Button>(R.id.btnTestRaw).setOnClickListener {
            val r = PropWriter.testRawMatrix()
            log("1015 直接下发 -> ${if (r.ok) "OK" else "FAILED"}\n${r.cmd}\n\n" +
                "若屏幕立刻变化：1015 有效，说明 SurfaceFlinger 侧没问题。\n" +
                "若无变化：本机的 transaction code 不是 1015，需查 AOSP ISurfaceComposer.aidl。")
        }

        findViewById<Button>(R.id.btnResetRaw).setOnClickListener {
            val r = PropWriter.resetRawMatrix()
            log("reset -> ${if (r.ok) "OK" else "FAILED"}")
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (updating) return
                uiToParams()
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        }
        sbSat.setOnSeekBarChangeListener(listener)
        sbBlack.setOnSeekBarChangeListener(listener)
        sbWhite.setOnSeekBarChangeListener(listener)

        checkStatus()
        syncToggle()
    }

    private fun buildPresets() {
        val names = EinkParams.presetList().keys.toList()
        names.forEachIndexed { i, name ->
            val rb = RadioButton(this).apply {
                text = name
                id = 2000 + i
                textSize = 13f
            }
            rgPreset.addView(rb)
        }
        // 回显上次选的预设（否则打开 App 时六个选项全空，看不出当前是哪一个）
        val savedIdx = names.indexOf(EinkPrefs.getPreset(this))
        if (savedIdx >= 0) rgPreset.check(2000 + savedIdx)

        rgPreset.setOnCheckedChangeListener { _, id ->
            if (updating) return@setOnCheckedChangeListener
            val name = names.getOrNull(id - 2000) ?: return@setOnCheckedChangeListener
            params = EinkParams.byName(name)
            EinkPrefs.save(this, params)
            EinkPrefs.setPreset(this, name)
            paramsToUi()
            val r = PropWriter.applyPreset(this, name)
            log("预设「$name」-> ${if (r.ok) "OK" else "FAILED"}\n${r.cmd}\n" +
                ColorMatrixBuilder.dumpForDebug(params))
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

    private fun syncToggle() {
        val on = EinkPrefs.isEnabled(this)
        btnToggle.text = if (on) "关闭墨水屏效果" else "开启墨水屏效果"
    }

    private fun loadPkgs(): Set<String> {
        return try {
            val f = File(PropConfig.BW_APPS_FILE)
            if (!f.exists()) emptySet()
            else f.readLines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        } catch (_: Throwable) { emptySet() }
    }

    private fun savePkgs(set: Set<String>) {
        val r = PropWriter.writeBwApps(set)
        log("黑白名单写入 -> ${if (r.ok) "OK" else "FAILED"}\n${r.cmd}")
        refreshPkgs()
    }

    private fun refreshPkgs() {
        val s = loadPkgs()
        tvPkgs.text = if (s.isEmpty()) "（空）" else s.joinToString("\n")
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
        tvModule.setTextColor(
            if (active) 0xFF1E8449.toInt() else 0xFFC0392B.toInt()
        )

        Thread {
            hasRoot = PropWriter.hasRoot()
            runOnUiThread {
                tvRoot.text = if (hasRoot) "root：已获取 ✓" else "root：未获取（调参需要 root 执行 setprop）"
                if (!hasRoot) {
                    log("未获取 root。本模块依赖 root 执行 setprop 下发参数。\n" +
                        "若已 root 但检测失败，请在 Magisk 中确认本 App 已授权。")
                }
            }
        }.start()
    }

    private fun log(s: String) { tvLog.text = s }
}
