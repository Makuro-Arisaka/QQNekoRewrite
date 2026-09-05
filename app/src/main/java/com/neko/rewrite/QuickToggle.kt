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
 * - 通知以 **QQ 的包名**发出（代码运行在 QQ 进程），不会额外暴露模块包名。
 *   代价是它会落在 QQ 的通知分组里。
 * - 全部调用包 try/catch(Throwable)：通知权限被拒、渠道创建失败、
 *   或 ROM 定制导致异常时，一律降级为「不显示通知」，绝不影响消息发送。
 *
 * ## Android 12+ / 16+ 显示要点（曾导致「通知栏看不到」）
 *
 * 1. **渠道重要性必须是 IMPORTANCE_DEFAULT（"优先"）而非 LOW（"静音"）**。
 *    Android 16 起通知整理会把低优先级/静音通知移出状态栏、折叠进应用分组，
 *    QQ 本身消息通知又很多，IMPORTANCE_LOW 的通知实际等于隐形。
 *    为避免因此打扰用户，通知本身用 `setSound(null)` + `setVibrate(null)` +
 *    `setOnlyAlertOnce(true)` 静音，只保留「可见」。
 * 2. **渠道已存在且被系统/用户降为 IMPORTANCE_NONE 时不会自动恢复**，
 *    这里检测到阻塞就删掉重建；渠道 ID 升级到 v2 也是为了让老用户拿到新的重要性。
 * 3. **通知 ID 不能与 QQ 自己的通知撞号**，否则会被 QQ 的后续通知顶掉。
 */
object QuickToggle {

    /** v2：重要性由 LOW 提到 DEFAULT，见类注释第 1 点 */
    private const val CHANNEL_ID = "neko_quick_toggle_v2"
    private const val CHANNEL_ID_LEGACY = "neko_quick_toggle"
    private const val CHANNEL_NAME = "改写开关"
    /** 一个 QQ 极不可能使用的 ID，避免被 QQ 的消息通知顶掉 */
    private const val NOTIFICATION_ID = 5134081
    private const val REQUEST_TOGGLE = 1
    private const val REQUEST_OPEN = 2
    private const val MODULE_PACKAGE = "com.neko.rewrite"

    /**
     * 显示/刷新通知（按当前 [ConfigManager.config] 的启用状态渲染）。
     * 仅在配置开启时调用；[ConfigManager.config.quickToggle] 为 false 时应调用 [cancel]。
     */
    fun show(context: Context) {
        try {
            val enabled = ConfigManager.config.enabled
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (manager == null) {
                note("⚠️ 通知栏开关：拿不到 NotificationManager")
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !manager.areNotificationsEnabled()) {
                // Android 13+ 通知权限被拒（Android 8~12 也可能被用户在设置里整体关闭）
                note("⚠️ 通知栏开关：QQ 的通知权限被关闭，无法显示（请在系统设置里允许 QQ 通知）")
                XposedBridge.log("[NekoRewrite] ⚠️ 通知栏开关：areNotificationsEnabled=false")
            }

            createChannel(manager)
            manager.notify(NOTIFICATION_ID, buildNotification(context, enabled))
            note("🔔 通知栏开关已显示（改写=${if (enabled) "启用" else "停用"}）")
            XposedBridge.log("[NekoRewrite] 🔔 通知栏开关已显示（改写=${if (enabled) "启用" else "停用"}）")
        } catch (t: Throwable) {
            XposedBridge.log("[NekoRewrite] ⚠️ 通知栏开关显示失败 (${t.javaClass.simpleName}): ${t.message}")
            LogRecorder.warn("QuickToggle", "通知显示失败: ${t.message}")
        }
    }

    /**
     * QQ 启动阶段发布通知，并做两次延迟重发。
     *
     * 部分 ROM（以及 QQ 自身的通知清理逻辑）会在启动过程中清掉早期发布的通知，
     * 单纯在 Application.onCreate 发一次可能被吃掉，所以补两次重发。
     * 重发前再确认一次配置，避免用户在此期间关掉了开关。
     */
    fun showWithRetry(context: Context) {
        show(context)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        for (delay in longArrayOf(5_000L, 20_000L)) {
            handler.postDelayed({
                try {
                    if (ConfigManager.config.quickToggle) show(context)
                } catch (t: Throwable) {
                    XposedBridge.log("[NekoRewrite] ⚠️ 通知栏开关重发失败: ${t.message}")
                }
            }, delay)
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

    /**
     * 建立通知渠道。
     *
     * 与旧实现的两点差别：
     * - 重要性用 IMPORTANCE_DEFAULT（"优先"），否则 Android 16+ 会被归到静音区；
     * - 渠道若已被降为 IMPORTANCE_NONE（用户/系统关闭过），删掉重建，
     *   否则 `createNotificationChannel` 对已存在渠道不会改重要性。
     */
    private fun createChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        // 清理旧版遗留渠道（IMPORTANCE_LOW，且会和 v2 同时出现在系统设置里）
        runCatching { manager.deleteNotificationChannel(CHANNEL_ID_LEGACY) }

        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) {
            val blocked = existing.importance == NotificationManager.IMPORTANCE_NONE
            XposedBridge.log("[NekoRewrite] 🔔 通知渠道已存在 importance=${existing.importance}")
            if (!blocked) return
            // 渠道被关闭：删掉重建，争取恢复为可见
            runCatching { manager.deleteNotificationChannel(CHANNEL_ID) }
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT // 可见于状态栏与通知栏，不会被归到静音区
        ).apply {
            description = "快速启用 / 停用猫娘改写"
            setShowBadge(false)
            // 渠道层面就静音，避免首次发布时响铃
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    @Suppress("DEPRECATION") // setSound/setVibrate 在渠道层面已废弃，但仍是静音单条通知的唯一手段
    private fun buildNotification(context: Context, enabled: Boolean): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        // [切换] 按钮：携带「目标状态」而非「翻转」指令。
        // QQ 有多个进程，广播会被每个注册了接收器的进程各收到一次；
        // 若语义是「翻转」，收到 N 次就会翻 N 次、最终回到原状态。
        // 携带绝对目标值后，处理多次与处理一次结果一致（幂等）。
        val toggleIntent = Intent(MainHook.ACTION_TOGGLE_ENABLED)
            .setPackage(context.packageName)
            .putExtra(MainHook.EXTRA_ENABLED, !enabled)
        val togglePi = PendingIntent.getBroadcast(context, REQUEST_TOGGLE, toggleIntent, flags)

        // 点整条通知：打开模块设置 App
        val openPi = buildOpenAppIntent(context)?.let {
            PendingIntent.getActivity(context, REQUEST_OPEN, it, flags)
        }

        val actionTitle = if (enabled) "停用改写" else "启用改写"
        val statusText = if (enabled) "改写：已启用" else "改写：已停用"

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        builder
            // 用系统内置图标，避免依赖模块自身资源（模块资源在 QQ 进程中不可直接引用）
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🐱 NekoRewrite")
            .setContentText(statusText)
            .setOngoing(true)          // 常驻，不可滑动清除
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)    // 刷新时不重复提醒
            // 渠道是 DEFAULT 重要性（保证可见），但通知本身保持安静
            .setSound(null)
            .setVibrate(null)
            // 用 Action.Builder 而非已废弃的 addAction(int, CharSequence, PendingIntent)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(
                        context, android.R.drawable.ic_popup_sync
                    ),
                    actionTitle,
                    togglePi
                ).build()
            )

        if (openPi != null) builder.setContentIntent(openPi)

        return builder.build()
    }

    /** 打开模块设置页的 Intent（从 QQ 进程跨包启动模块 Activity） */
    private fun buildOpenAppIntent(context: Context): Intent? {
        return try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(MODULE_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                launchIntent
            } else {
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(MODULE_PACKAGE, "com.neko.rewrite.MainActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    /** 同时写 Xposed 日志与模块日志，便于在日志页定位通知问题 */
    private fun note(message: String) {
        LogRecorder.info("QuickToggle", message)
    }
}
