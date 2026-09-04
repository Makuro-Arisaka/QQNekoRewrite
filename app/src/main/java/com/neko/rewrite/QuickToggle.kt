package com.neko.rewrite

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import de.robv.android.xposed.XposedBridge

/**
 * 通知栏快速开关。
 *
 * 在 QQ 的通知栏显示一条常驻通知，无需打开模块 App 即可一键启用/停用改写：
 *
 * ```
 * 🐱 NekoRewrite
 * 改写：已启用          [切换]
 * ```
 *
 * ## 设计取舍
 *
 * - **默认关闭**（[ModuleConfig.quickToggle] = false）。常驻通知比启动 Toast 更显眼、
 *   且长期存在，会持续暴露模块存在；仅在用户确实需要免开 App 快速切换时才开启。
 * - 通知渠道设为 **IMPORTANCE_LOW**（无声音、不悬浮），把打扰降到最低。
 * - 通知以 **QQ 的包名**发出（代码运行在 QQ 进程），不会额外暴露模块包名。
 * - 全部调用包 try/catch(Throwable)：通知权限被拒、渠道创建失败、
 *   或 ROM 定制导致异常时，一律降级为「不显示通知」，绝不影响消息发送。
 */
object QuickToggle {

    private const val CHANNEL_ID = "neko_quick_toggle"
    private const val CHANNEL_NAME = "改写开关"
    private const val NOTIFICATION_ID = 20001

    /**
     * 显示/刷新通知（按当前 [ConfigManager.config] 的启用状态渲染）。
     * 仅在配置开启时调用；[ConfigManager.config.quickToggle] 为 false 时应调用 [cancel]。
     */
    fun show(context: Context) {
        try {
            val enabled = ConfigManager.config.enabled
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            createChannel(manager)

            val manager2 = manager
            manager2.notify(NOTIFICATION_ID, buildNotification(context, enabled))
            XposedBridge.log("[NekoRewrite] 🔔 通知栏开关已显示（改写=${if (enabled) "启用" else "停用"}）")
        } catch (t: Throwable) {
            XposedBridge.log("[NekoRewrite] ⚠️ 通知栏开关显示失败 (${t.javaClass.simpleName}): ${t.message}")
            LogRecorder.warn("QuickToggle", "通知显示失败: ${t.message}")
        }
    }

    /** 移除常驻通知（用户关闭该功能时调用） */
    fun cancel(context: Context) {
        try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return
            manager.cancel(NOTIFICATION_ID)
            XposedBridge.log("[NekoRewrite] 🔕 通知栏开关已移除")
        } catch (t: Throwable) {
            XposedBridge.log("[NekoRewrite] ⚠️ 通知栏开关移除失败: ${t.message}")
        }
    }

    private fun createChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW // 无声音、不悬浮，尽量不打扰
        ).apply {
            description = "快速启用 / 停用猫娘改写"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(context: Context, enabled: Boolean): Notification {
        val toggleIntent = Intent(MainHook.ACTION_TOGGLE_ENABLED).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val togglePi = PendingIntent.getBroadcast(context, 1, toggleIntent, flags)

        val actionTitle = if (enabled) "停用改写" else "启用改写"
        val statusText = if (enabled) "改写：已启用" else "改写：已停用"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        return builder
            // 用系统内置图标，避免依赖模块自身资源（模块资源在 QQ 进程中不可直接引用）
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🐱 NekoRewrite")
            .setContentText(statusText)
            .setOngoing(true)          // 常驻，不可滑动清除
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)    // 刷新时不重复提醒
            .addAction(android.R.drawable.ic_popup_sync, actionTitle, togglePi)
            .build()
    }
}
