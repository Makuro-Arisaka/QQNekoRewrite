package com.neko.rewrite

import android.widget.Toast

/**
 * UI 辅助工具
 *
 * 在 QQ 进程中显示 Toast 提示。
 * 需要通过反射获取 QQ 的 Context 或 Handler。
 */
object UiHelper {

    private var qqContext: android.content.Context? = null

    fun setContext(context: android.content.Context) {
        qqContext = context
    }

    fun showToast(message: String) {
        val ctx = qqContext ?: return
        android.os.Handler(ctx.mainLooper).post {
            Toast.makeText(ctx, "[Neko] $message", Toast.LENGTH_SHORT).show()
        }
    }
}