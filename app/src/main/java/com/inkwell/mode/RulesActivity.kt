package com.inkwell.mode

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar

/**
 * 按 App 切黑白档。
 *
 * 需要「使用情况访问」权限才能查前台 App。没授权时给出提示和跳转入口，
 * 而不是静默失效 —— 静默失效最让人困惑。
 */
class RulesActivity : AppCompatActivity() {

    private lateinit var tvPkgs: TextView
    private lateinit var tvPerm: TextView
    private lateinit var etPkg: EditText

    private val QUICK = listOf(
        "微信读书" to "com.tencent.weread",
        "起点读书" to "com.qidian.QDReader",
        "掌阅" to "com.chaozh.iReader",
        "知乎" to "com.zhihu.android",
        "Chrome" to "com.android.chrome",
        "系统桌面" to "com.miui.home"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules)

        tvPkgs = findViewById(R.id.tvPkgs)
        tvPerm = findViewById(R.id.tvPerm)
        etPkg = findViewById(R.id.etPkg)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        buildQuickAdd()
        refresh()

        findViewById<Button>(R.id.btnAdd).setOnClickListener {
            val pkg = etPkg.text.toString().trim()
            if (pkg.isBlank()) return@setOnClickListener
            val set = ConfigStore.loadBwAppsLocal(this).toMutableSet()
            set.add(pkg)
            save(set)
            etPkg.setText("")
            toast("已加入：$pkg")
        }

        findViewById<Button>(R.id.btnClear).setOnClickListener {
            save(emptySet())
            toast("已清空")
        }

        checkPerm()
    }

    override fun onResume() {
        super.onResume()
        checkPerm()
    }

    private fun buildQuickAdd() {
        val box = findViewById<LinearLayout>(R.id.boxQuick)
        val current = ConfigStore.loadBwAppsLocal(this)
        for ((label, pkg) in QUICK) {
            val b = Button(this).apply {
                text = if (current.contains(pkg)) "$label ✓" else label
                isAllCaps = false
                textSize = 12f
            }
            b.setOnClickListener {
                val set = ConfigStore.loadBwAppsLocal(this).toMutableSet()
                if (set.contains(pkg)) set.remove(pkg) else set.add(pkg)
                save(set)
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 6 }
            box.addView(b, lp)
        }
    }

    private fun save(set: Set<String>) {
        val ok = ConfigStore.saveBwApps(this, set)
        refresh()
        // 名单变了要重新下发一次，让模块读到新的 bw= 行
        ConfigStore.saveAndApply(
            this, EinkPrefs.load(this), EinkPrefs.getPreset(this), EinkPrefs.isEnabled(this)
        )
        if (!ok) toast("写入失败，请查看诊断页")
    }

    private fun refresh() {
        val s = ConfigStore.loadBwAppsLocal(this)
        tvPkgs.text = if (s.isEmpty()) "（空）" else s.joinToString("\n")
        val box = findViewById<LinearLayout>(R.id.boxQuick)
        for (i in 0 until box.childCount) {
            val b = box.getChildAt(i) as? Button ?: continue
            val pkg = QUICK.getOrNull(i)?.second ?: continue
            val label = QUICK[i].first
            b.text = if (s.contains(pkg)) "$label ✓" else label
        }
    }

    /** 检查「使用情况访问」权限 */
    private fun checkPerm() {
        val granted = hasUsageAccess()
        if (granted) {
            tvPerm.visibility = android.view.View.GONE
        } else {
            tvPerm.visibility = android.view.View.VISIBLE
            tvPerm.text = "⚠️ 未授予「使用情况访问」权限，按 App 切档不会生效。\n点击这里去设置开启。"
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
