package com.neko.rewrite

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.neko.rewrite.model.ModuleConfig
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * Xposed 模块入口
 */
class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val QQ_PACKAGE = "com.tencent.mobileqq"
        const val ACTION_CONFIG_UPDATE = "com.neko.rewrite.CONFIG_UPDATE"
        /** 通知栏快速开关点击：翻转改写总开关 */
        const val ACTION_TOGGLE_ENABLED = "com.neko.rewrite.TOGGLE_ENABLED"
        const val EXTRA_API_KEY = "api_key"
        const val EXTRA_API_ENDPOINT = "api_endpoint"
        const val EXTRA_MODEL = "model"
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_TEMPERATURE = "temperature"
        const val EXTRA_MAX_TOKENS = "max_tokens"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_ENABLED = "enabled"
        const val EXTRA_SHOW_TOAST = "show_toast"
        const val EXTRA_SHOW_STARTUP_TOAST = "show_startup_toast"
        const val EXTRA_QUICK_TOGGLE = "quick_toggle"
        const val EXTRA_LOG_ENABLED = "log_enabled"
        const val EXTRA_ASYNC_REWRITE = "async_rewrite"
        const val EXTRA_REWRITE_TIMEOUT = "rewrite_timeout_ms"
        const val EXTRA_FILTER_MODE = "filter_mode"
        const val EXTRA_WHITELIST = "whitelist"
        const val EXTRA_BLACKLIST = "blacklist"
        const val EXTRA_LAST_UPDATED = "last_updated"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != QQ_PACKAGE) return

        try {
            XposedBridge.log("[NekoRewrite] ========================================")
            XposedBridge.log("[NekoRewrite] 🚀 模块已加载！QQ 进程: ${lpparam.processName}")
            XposedBridge.log("[NekoRewrite] ========================================")

            hookApplication(lpparam)
            MessageInterceptor.install(lpparam.classLoader)

            XposedBridge.log("[NekoRewrite] ✅ 所有 Hook 安装流程完成")
        } catch (e: Throwable) {
            XposedBridge.log("[NekoRewrite] ❌ handleLoadPackage 崩溃: ${e.javaClass.name}: ${e.message}")
        }
    }

    private fun hookApplication(lpparam: XC_LoadPackage.LoadPackageParam) {
        val processName = lpparam.processName
        try {
            XposedHelpers.findAndHookMethod(
                "com.tencent.common.app.BaseApplicationImpl",
                lpparam.classLoader,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val context = param.thisObject as android.content.Context
                            XposedBridge.log("[NekoRewrite] 📱 QQ Application.onCreate 已触发")

                            ConfigManager.init(context)
                            XposedBridge.log("[NekoRewrite] 📄 配置来源: ${ConfigManager.source.label}")

                            // 必须在配置加载之后再初始化日志：logEnabled 决定 QQ 进程是否落盘
                            LogRecorder.initFromQqContext(context, ConfigManager.config.logEnabled)
                            XposedBridge.log("[NekoRewrite] 📋 日志系统: ${if (LogRecorder.isReady) "OK" else "降级模式"}")

                            // 供概览页判断「模块是否真的在 QQ 里生效」
                            LogRecorder.markMounted(context, processName)

                            // 传入 QQ Context 给消息拦截器（用于 Toast）
                            MessageInterceptor.setContext(context)

                            // 注册广播接收器（配置更新 + 通知栏快速开关）
                            registerConfigReceiver(context)

                            // 通知栏快速开关默认关闭，由设置项控制
                            refreshQuickToggle(context, retry = true)

                            // 启动 Toast 默认关闭，避免暴露模块存在；由设置项控制
                            if (ConfigManager.config.showStartupToast) {
                                android.os.Handler(context.mainLooper).post {
                                    android.widget.Toast.makeText(context, "🐱 NekoRewrite 已加载！", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }

                            XposedBridge.log("[NekoRewrite] ✅ 初始化完成")
                        } catch (e: Throwable) {
                            XposedBridge.log("[NekoRewrite] ❌ 初始化失败: ${e.javaClass.name}: ${e.message}")
                        }
                    }
                }
            )
            XposedBridge.log("[NekoRewrite] 🪝 Application Hook 已注册 (等待 QQ 启动...)")
        } catch (e: Throwable) {
            XposedBridge.log("[NekoRewrite] ❌ Application Hook 注册失败: ${e.javaClass.name}: ${e.message}")
        }
    }

    /**
     * 注册广播接收器：
     * - [ACTION_CONFIG_UPDATE]：接收设置页面的配置更新
     * - [ACTION_TOGGLE_ENABLED]：通知栏快速开关翻转改写总开关
     */
    private fun registerConfigReceiver(context: android.content.Context) {
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        ACTION_TOGGLE_ENABLED -> handleQuickToggle(ctx, intent)
                        ACTION_CONFIG_UPDATE -> handleConfigUpdate(ctx, intent)
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction(ACTION_CONFIG_UPDATE)
                addAction(ACTION_TOGGLE_ENABLED)
            }
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            XposedBridge.log("[NekoRewrite] 📡 广播接收器已注册（配置更新 / 快速开关）")
        } catch (e: Exception) {
            XposedBridge.log("[NekoRewrite] ⚠️ 广播接收器注册失败: ${e.message}")
        }
    }

    /** 设置页保存配置：写入 QQ 侧 SP 并热更新 */
    private fun handleConfigUpdate(context: Context, intent: Intent) {
        try {
            XposedBridge.log("[NekoRewrite] 📡 收到配置更新广播")

            // 未被广播覆盖的字段沿用 QQ 侧已存的值，避免被默认值清空
            val prefs = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
            val incoming = ModuleConfig(
                        enabled = intent.getBooleanExtra(EXTRA_ENABLED, true),
                        apiEndpoint = intent.getStringExtra(EXTRA_API_ENDPOINT) ?: ConfigManager.DEFAULT_ENDPOINT,
                        apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: "",
                        model = intent.getStringExtra(EXTRA_MODEL) ?: ConfigManager.DEFAULT_MODEL,
                        provider = intent.getStringExtra(EXTRA_PROVIDER) ?: "DeepSeek (深度求索)",
                        temperature = intent.getFloatExtra(EXTRA_TEMPERATURE, 0.8f),
                        maxTokens = intent.getIntExtra(EXTRA_MAX_TOKENS, 500),
                        showToast = intent.getBooleanExtra(EXTRA_SHOW_TOAST, true),
                        showStartupToast = intent.getBooleanExtra(EXTRA_SHOW_STARTUP_TOAST, prefs.getBoolean("show_startup_toast", false)),
                        quickToggle = intent.getBooleanExtra(EXTRA_QUICK_TOGGLE, prefs.getBoolean("quick_toggle", false)),
                        logEnabled = intent.getBooleanExtra(EXTRA_LOG_ENABLED, prefs.getBoolean("log_enabled", false)),
                        asyncRewrite = intent.getBooleanExtra(EXTRA_ASYNC_REWRITE, prefs.getBoolean("async_rewrite", true)),
                        rewriteTimeoutMs = intent.getIntExtra(EXTRA_REWRITE_TIMEOUT, prefs.getInt("rewrite_timeout_ms", 8000)),
                        filterMode = intent.getIntExtra(EXTRA_FILTER_MODE, prefs.getInt("filter_mode", 0)),
                        whitelist = intent.getStringArrayListExtra(EXTRA_WHITELIST)?.toSet()
                            ?: prefs.getStringSet("whitelist", emptySet()) ?: emptySet(),
                        blacklist = intent.getStringArrayListExtra(EXTRA_BLACKLIST)?.toSet()
                            ?: prefs.getStringSet("blacklist", emptySet()) ?: emptySet(),
                        systemPrompt = intent.getStringExtra(EXTRA_PROMPT) ?: PromptManager.DEFAULT_PROMPT,
                        // 时间戳沿用模块侧写入值，保证多来源仲裁时两侧一致
                        lastUpdated = intent.getLongExtra(EXTRA_LAST_UPDATED, System.currentTimeMillis())
                    )

            ConfigManager.applyBroadcast(context, incoming)
            LogRecorder.success("Config", "广播更新配置: 模型=${incoming.model} ts=${incoming.lastUpdated}")

            // 日志开关可能随本次配置改变：按最新 logEnabled 重新初始化 QQ 侧日志写入，
            // 避免用户要重启 QQ 才能开始/停止记录
            LogRecorder.initFromQqContext(context, incoming.logEnabled)

            // 通知栏开关的显隐可能随本次配置改变，同步刷新
            refreshQuickToggle(context)
        } catch (e: Exception) {
            XposedBridge.log("[NekoRewrite] ⚠️ 配置更新处理失败: ${e.message}")
        }
    }

    /**
     * 通知栏快速开关：把改写总开关设为广播携带的目标值。
     *
     * 这里用**绝对目标值**（[EXTRA_ENABLED]）而不是「翻转」：
     * QQ 有多个进程，每个注册了接收器的进程都会收到这条广播，
     * 「翻转」语义会被执行 N 次、结果回到原状态；绝对值是幂等的。
     *
     * 以【当前时间】作为新时间戳写入 QQ 侧 SP —— 它比模块侧保存的时间戳更新，
     * 因此即便 QQ 重启，多来源仲裁仍会选中这份配置，开关状态得以保持。
     * （用户之后在设置页保存会写入更新的时间戳，自然覆盖之。）
     */
    private fun handleQuickToggle(context: Context, intent: Intent) {
        try {
            val target = intent.getBooleanExtra(EXTRA_ENABLED, !ConfigManager.config.enabled)
            if (target == ConfigManager.config.enabled) {
                // 已被同一次点击的其它进程处理过：只刷新通知，重复写入会把时间戳推新
                QuickToggle.show(context)
                return
            }

            val updated = ConfigManager.config.copy(
                enabled = target,
                lastUpdated = System.currentTimeMillis()
            )
            ConfigManager.applyBroadcast(context, updated)
            XposedBridge.log("[NekoRewrite] 🔔 快速开关：改写已${if (target) "启用" else "停用"}")
            LogRecorder.success("QuickToggle", "改写已${if (target) "启用" else "停用"}")
            QuickToggle.show(context)
        } catch (t: Throwable) {
            XposedBridge.log("[NekoRewrite] ❌ 快速开关切换失败: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * 按当前配置显示或移除通知栏开关。
     * @param retry true = QQ 启动阶段，额外做两次延迟重发（防止被启动过程清掉）
     */
    private fun refreshQuickToggle(context: Context, retry: Boolean = false) {
        try {
            if (ConfigManager.config.quickToggle) {
                LogRecorder.info("QuickToggle", "配置要求显示常驻通知，正在发布...")
                if (retry) QuickToggle.showWithRetry(context) else QuickToggle.show(context)
            } else {
                LogRecorder.info("QuickToggle", "通知栏快速开关未启用（设置页可开启）")
                QuickToggle.cancel(context)
            }
        } catch (t: Throwable) {
            XposedBridge.log("[NekoRewrite] ⚠️ 通知栏开关刷新失败: ${t.message}")
            LogRecorder.warn("QuickToggle", "通知栏开关刷新失败: ${t.message}")
        }
    }
}