package com.inkwell.mode

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * 把墨境磁贴注册成澎湃 OS / HyperOS 控制中心的「系统快捷开关」。
 *
 * ── 机制（AOSP 与 HyperOS 通用）──
 *
 * 已添加的磁贴存放在 Settings.Secure 的 `sysui_qs_tiles`，逗号分隔：
 *
 *   wifi,bt,cell,rotation,airplane,hotspot,
 *   custom(com.google.android.apps.wellbeing/.screen.ui.GrayscaleTileService),battery
 *
 * 系统原生磁贴是单个 spec（wifi / bt / hotspot / night ...），
 * 第三方 TileService 是 `custom(<包名>/<类名>)`。
 * HyperOS 的「编辑与排序」（MiuiQSCustomizerController）读的也是同一个 key，
 * 会把 custom() 项和系统磁贴一起列在「系统快捷开关」区域。
 *
 * 所以：用 root shell 执行 `settings put secure`，把我们的 spec 追加进去，
 * 磁贴就直接出现在常用开关里，和护眼模式并列 —— 不用去编辑页翻找、拖动。
 * 之后它完全按系统磁贴工作：点按切换、长按进设置、可在编辑页拖动排序/移除。
 *
 * ── 为什么用精确匹配 ──
 *
 * HyperOS 编辑页判断「某个系统磁贴是否已添加」时用了
 * `addedQuickSettingsListString.contains(tile)` 而非按逗号切分后精确比较，
 * 曾导致第三方包名里碰巧含系统 spec 时，该系统磁贴从列表里消失。
 * 这里一律按逗号切分后做 `==` 比较，不生成任何可能误伤的半截匹配项。
 *
 * 无 root 时本类不可用，但手动路径仍然有效：控制中心 → 编辑 →
 * 在「系统快捷开关」里往下滚找到「墨境」→ 点 + 添加。
 */
object QsTileInstaller {

    private const val KEY = "sysui_qs_tiles"
    /** 部分 Android 14+ ROM 改用此 key；只在它已存在时才同步追加 */
    private const val ALT_KEY = "sysui_quick_qs_tiles"

    data class Outcome(val ok: Boolean, val message: String)

    fun spec(ctx: Context): String = "custom(${ctx.packageName}/.EinkTileService)"

    /**
     * 读取 secure 表里的磁贴列表。
     * 返回 null 表示该 key 不存在（未初始化），调用方不应凭空创建它，
     * 否则会覆盖 ROM 的默认磁贴列表。
     */
    private fun read(key: String): String? {
        val r = su("settings get secure $key") ?: return null
        val v = r.trim()
        return if (v.isEmpty() || v == "null") null else v
    }

    /**
     * 一键加入。重复调用安全：已存在就跳过，绝不产生重复项。
     * 只读 + 单次写入，不改动任何其它磁贴的顺序。
     */
    fun install(ctx: Context): Outcome {
        if (!ConfigStore.hasRoot()) {
            return Outcome(
                false,
                "没有 root 授权，无法自动写入系统快捷开关。\n\n" +
                    "手动添加同样有效：下拉控制中心 → 编辑（铅笔）→ " +
                    "在「系统快捷开关」区域往下滚 → 找到「墨境」点 + → 完成。"
            )
        }

        val s = spec(ctx)
        var changed = false
        var touched = 0

        for (key in listOf(KEY, ALT_KEY)) {
            val cur = read(key) ?: continue
            touched++
            val parts = cur.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (parts.any { it == s }) continue          // 已存在，精确匹配
            parts.add(s)                                 // 追加到末尾，不动原有顺序
            val newVal = parts.joinToString(",")
            // 单引号会提前闭合 shell 引号、把后面的内容变成命令，宁可不做
            if (newVal.contains('\'')) {
                return Outcome(false, "磁贴列表里含有特殊字符，为避免破坏系统设置已中止写入。\n请在控制中心编辑页手动添加「墨境」。")
            }
            val put = su("settings put secure $key '$newVal'")
            if (put == null) return Outcome(false, "写入 $key 失败，su 可能被拒绝。")
            changed = true
        }

        if (touched == 0) {
            return Outcome(
                false,
                "这台设备的 secure 表里没有 $KEY（ROM 使用内置默认列表）。\n" +
                    "为避免清空原有磁贴，没有自动写入。请用控制中心编辑页手动添加「墨境」。"
            )
        }

        // 触发 SystemUI 重新读取磁贴列表。settings put 本身会触发 ContentObserver，
        // 这里再补一个广播，兼容 HyperOS 自定义控制器自己的缓存。
        su("am broadcast -a android.intent.action.QUICK_SETTINGS_CHANGED >/dev/null 2>&1")

        return Outcome(
            true,
            if (changed) {
                "已加入系统快捷开关 ✓\n\n" +
                    "下拉控制中心看一眼，「墨境」现在和护眼模式并列在常用开关里。\n" +
                    "点一下切换开/关，长按进显示模式页，也能在编辑页里拖动排序。\n\n" +
                    "如果暂时没出现，下拉一次控制中心刷新；仍没有就重启一次手机。"
            } else {
                "「墨境」已经在系统快捷开关里了，无需重复添加。\n\n" +
                    "下拉控制中心即可看到。点按切换开/关，长按进显示模式页。"
            }
        )
    }

    /** 查询是否已加入（供 UI 显示状态）。无 root 时返回 null 表示未知。 */
    fun isInstalled(ctx: Context): Boolean? {
        if (!ConfigStore.hasRoot()) return null
        val s = spec(ctx)
        val cur = read(KEY) ?: return null
        return cur.split(",").map { it.trim() }.any { it == s }
    }

    /** 从系统快捷开关里移除（回退用）。同样只做精确匹配，不动其它项。 */
    fun uninstall(ctx: Context): Outcome {
        if (!ConfigStore.hasRoot()) return Outcome(false, "没有 root 授权，请在控制中心编辑页手动移除。")
        val s = spec(ctx)
        var changed = false
        for (key in listOf(KEY, ALT_KEY)) {
            val cur = read(key) ?: continue
            val parts = cur.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val kept = parts.filter { it != s }
            if (kept.size == parts.size) continue
            val newVal = kept.joinToString(",")
            if (newVal.contains('\'')) {
                return Outcome(false, "磁贴列表里含有特殊字符，已中止写入。请在编辑页手动移除。")
            }
            val put = su("settings put secure $key '$newVal'")
            if (put == null) return Outcome(false, "写入 $key 失败。")
            changed = true
        }
        su("am broadcast -a android.intent.action.QUICK_SETTINGS_CHANGED >/dev/null 2>&1")
        return Outcome(
            true,
            if (changed) "已从系统快捷开关移除。下拉一次控制中心即可刷新。"
            else "系统快捷开关里本来就没有「墨境」。"
        )
    }

    /** 执行一条 root shell 命令。返回 stdout（失败返回 null）。 */
    private fun su(cmd: String): String? {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            p.errorStream.close()
            val code = p.waitFor()
            if (code == 0) out else null
        } catch (_: Throwable) {
            null
        }
    }
}
