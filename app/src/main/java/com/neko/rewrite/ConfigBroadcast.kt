package com.neko.rewrite

import android.content.Context
import android.content.Intent
import com.neko.rewrite.model.ModuleConfig

/**
 * 把完整 [ModuleConfig] 通过 [MainHook.ACTION_CONFIG_UPDATE] 广播给 QQ 进程。
 *
 * ## 为什么必须发送「完整」配置
 *
 * QQ 端的接收器（[MainHook.handleConfigUpdate]）会用广播里的字段**整体重建**一份
 * [ModuleConfig]：任何缺失的字段都会回落成默认值（例如 `api_key` 变空字符串、
 * `enabled` 变 `true`），从而**覆盖掉用户已保存的真实配置**。
 *
 * 因此这里必须发送完整配置，绝不可只发单个字段 —— 这也是抽成公共方法、让设置页与
 * 日志页共用同一套 extras 的原因，避免两处不同步导致某次广播悄悄清空配置。
 */
object ConfigBroadcast {

    fun send(context: Context, config: ModuleConfig, timestamp: Long) {
        val intent = Intent(MainHook.ACTION_CONFIG_UPDATE).apply {
            setPackage("com.tencent.mobileqq")
            putExtra(MainHook.EXTRA_API_KEY, config.apiKey)
            putExtra(MainHook.EXTRA_API_ENDPOINT, config.apiEndpoint)
            putExtra(MainHook.EXTRA_MODEL, config.model)
            putExtra(MainHook.EXTRA_PROVIDER, config.provider)
            putExtra(MainHook.EXTRA_TEMPERATURE, config.temperature)
            putExtra(MainHook.EXTRA_MAX_TOKENS, config.maxTokens)
            putExtra(MainHook.EXTRA_PROMPT, config.systemPrompt)
            putExtra(MainHook.EXTRA_ENABLED, config.enabled)
            putExtra(MainHook.EXTRA_SHOW_TOAST, config.showToast)
            putExtra(MainHook.EXTRA_SHOW_STARTUP_TOAST, config.showStartupToast)
            putExtra(MainHook.EXTRA_ASYNC_REWRITE, config.asyncRewrite)
            putExtra(MainHook.EXTRA_REWRITE_TIMEOUT, config.rewriteTimeoutMs)
            putExtra(MainHook.EXTRA_LAST_UPDATED, timestamp)
        }
        context.sendBroadcast(intent)
    }
}
