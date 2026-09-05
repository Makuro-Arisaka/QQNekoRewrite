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
            // 收信侧 Hook：从 MsgRecord 持续建立 peerUid ↔ QQ号 持久映射
            UidMap.install(lpparam.classLoader)

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

                            // 映射表落盘在 QQ 自身 files 目录（本进程有完全读写权）
                            UidMap.init(context)

                            // 日志诊断走 Logcat；挂载心跳供概览页判断「模块是否真的在 QQ 里生效」
                            LogRecorder.initFromQqContext(context)
                            XposedBridge.log("[NekoRewrite] 📋 日志系统已初始化（诊断信息输出到 Logcat）")

                            LogRecorder.markMounted(context, processName)

                            // 传入 QQ Context 给消息拦截器（用于 Toast）
                            MessageInterceptor.setContext(context)

                            // 注册广播接收器（仅接收设置页 / 磁贴发来的配置更新）
                            registerConfigReceiver(context)

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
     * 注册广播接收器：接收设置页面 / Quick Settings 磁贴发来的配置更新。
     *
     * 之前的实现里这里还接收 [ACTION_TOGGLE_ENABLED]（通知栏开关翻转改写总开关），
     * 但由于 QQ 多进程竞态，那条通知总被误删。现在一键开关改为 Quick Settings 磁贴，
     * 由 SystemUI 托管、与 QQ 进程解耦，不再需要这条广播。
     */
    private fun registerConfigReceiver(context: android.content.Context) {
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        ACTION_CONFIG_UPDATE -> handleConfigUpdate(ctx, intent)
                    }
                }
            }

            val filter = IntentFilter().apply {
                addAction(ACTION_CONFIG_UPDATE)
            }
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            XposedBridge.log("[NekoRewrite] 📡 广播接收器已注册（配置更新）")
        } catch (e: Exception) {
            XposedBridge.log("[NekoRewrite] ⚠️ 广播接收器注册失败: ${e.message}")
        }
    }

    /** 设置页 / 磁贴保存配置：写入 QQ 侧 SP 并热更新 */
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
        } catch (e: Exception) {
            XposedBridge.log("[NekoRewrite] ⚠️ 配置更新处理失败: ${e.message}")
        }
    }
}
