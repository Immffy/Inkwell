package com.inkwell.mode

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * 按 App 指定显示模式。
 *
 * 0.2.2 起不再只是「名单内切黑白档」，而是可以给每个 App 指定
 * 原色 / 彩纸 / 墨纸中的任意一档。模块侧轮询前台 App 后自动换档。
 *
 * 需要「使用情况访问」权限才能查前台 App。没授权时给出提示和跳转入口，
 * 而不是静默失效 —— 静默失效最让人困惑。
 */
class RulesActivity : AppCompatActivity() {

    private lateinit var tvPkgs: TextView
    private lateinit var tvPerm: TextView
    private lateinit var etPkg: EditText
    private lateinit var btnModePick: Button
    private lateinit var tvModeHint: TextView

    private var selectedMode: String = DisplayMode.INK_PAPER

    private val QUICK = listOf(
        "微信读书" to "com.tencent.weread",
        "起点读书" to "com.qidian.QDReader",
        "掌阅" to "com.chaozh.iReader",
        "知乎" to "com.zhihu.android",
        "Chrome" to "com.android.chrome",
        "系统相机" to "com.android.camera2",
        "系统桌面" to "com.miui.home"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)

        tvPkgs = findViewById(R.id.tvPkgs)
        tvPerm = findViewById(R.id.tvPerm)
        etPkg = findViewById(R.id.etPkg)
        btnModePick = findViewById(R.id.btnModePick)
        tvModeHint = findViewById(R.id.tvModeHint)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        updateModePicker()
        btnModePick.setOnClickListener { pickMode { m -> selectedMode = m; updateModePicker() } }

        buildQuickAdd()
        refresh()

        findViewById<Button>(R.id.btnAdd).setOnClickListener {
            val pkg = etPkg.text.toString().trim()
            if (pkg.isBlank()) {
                toast("先填包名")
                return@setOnClickListener
            }
            val map = EinkPrefs.getAppRuleModes(this).toMutableMap()
            map[pkg] = selectedMode
            save(map)
            etPkg.setText("")
            toast("已设置：$pkg → $selectedMode")
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            save(emptyMap())
            toast("已清空")
        }

        checkPerm()
    }

    override fun onResume() {
        super.onResume()
        checkPerm()
        refresh()
    }

    /** 三模选择对话框 */
    private fun pickMode(onPick: (String) -> Unit) {
        val labels = DisplayMode.ALL.map { mode ->
            "$mode\n   ${DisplayMode.usage(mode).lines().firstOrNull() ?: ""}"
        }
        val items = Array<CharSequence>(labels.size) { labels[it] }
        AlertDialog.Builder(this)
            .setTitle("选择显示模式")
            .setItems(items) { _, which -> onPick(DisplayMode.ALL[which]) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateModePicker() {
        btnModePick.text = "$selectedMode  ▼"
        tvModeHint.text = DisplayMode.usage(selectedMode).replace("\n", " ")
    }

    private fun buildQuickAdd() {
        val box = findViewById<LinearLayout>(R.id.boxQuick)
        for ((label, pkg) in QUICK) {
            val b = Button(this).apply {
                isAllCaps = false
                textSize = 12f
            }
            b.setOnClickListener {
                val current = EinkPrefs.getAppRuleModes(this)
                val options = DisplayMode.ALL.toMutableList()
                val labels = options.map { m ->
                    if (current[pkg] == m) "$m  ✓ 当前" else m
                }.plus("移除规则")
                val items = Array<CharSequence>(labels.size) { labels[it] }
                AlertDialog.Builder(this)
                    .setTitle("$label  →  用哪个模式？")
                    .setItems(items) { _, which ->
                        val map = current.toMutableMap()
                        if (which == options.size) {
                            map.remove(pkg)
                        } else {
                            map[pkg] = options[which]
                        }
                        save(map)
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6 }
            box.addView(b, lp)
        }
    }

    private fun save(map: Map<String, String>) {
        ConfigStore.saveAppRuleModes(this, map)
        refresh()
    }

    private fun refresh() {
        val rules = EinkPrefs.getAppRuleModes(this)
        tvPkgs.text = if (rules.isEmpty()) {
            "（空）"
        } else {
            rules.entries.joinToString("\n") { "${it.key}\n   → ${it.value}" }
        }
        val box = findViewById<LinearLayout>(R.id.boxQuick)
        for (i in 0 until box.childCount) {
            val b = box.getChildAt(i) as? Button ?: continue
            val pair = QUICK.getOrNull(i) ?: continue
            val label = pair.first
            val mode = rules[pair.second]
            b.text = if (mode == null) label else "$label  →  $mode ✓"
        }
    }

    /** 检查「使用情况访问」权限 */
    private fun checkPerm() {
        val granted = hasUsageAccess()
        if (granted) {
            tvPerm.visibility = android.view.View.GONE
        } else {
            tvPerm.visibility = android.view.View.VISIBLE
            tvPerm.text = "⚠️ 未授予「使用情况访问」权限，按 App 自动换档不会生效。\n点击这里去设置开启。"
            tvPerm.setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                } catch (_: Throwable) {
                    toast("请手动在设置中搜索「使用情况访问」")
                }
            }
        }
    }

    private fun hasUsageAccess(): Boolean {
        return try {
            val aom = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = aom.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                android.os.Process.myUid(),
                packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
