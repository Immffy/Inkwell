package com.inkwell.mode

import android.app.Activity
import android.os.Bundle

/**
 * 透明执行器：桌面快捷按钮 / 长按图标快捷方式 / 通知栏 Action 都指向这里。
 *
 * 没有布局、没有界面，执行完动作立刻 finish()。
 * 配合 Theme.NoDisplay，用户看到的是「点一下图标，屏幕颜色就变了」，
 * 不会闪出任何 Activity。
 *
 * 配置下发放到后台线程：ConfigStore 里可能经 su 写持久化文件，
 * 若 Magisk / KernelSU 弹授权框，waitFor() 会阻塞。
 * 放在主线程会让「点一下」卡住甚至 ANR。
 */
class QuickActionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getStringExtra(QuickActions.EXTRA) ?: QuickActions.TOGGLE
        val app = applicationContext
        Thread {
            try {
                QuickActions.execute(app, id)
            } catch (_: Throwable) {
                // 快捷入口绝不能崩，静默失败即可
            }
        }.start()
        finish()
    }
}
