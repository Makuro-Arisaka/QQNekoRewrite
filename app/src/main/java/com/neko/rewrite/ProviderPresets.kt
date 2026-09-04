package com.neko.rewrite

/**
 * 预设 AI 提供方配置
 *
 * 参考 DeepSeek Harness 中的提供方配置，提取常用 OpenAI 兼容 API 的提供方。
 * 用户可选择预设提供方自动填充 API 端点和模型，也可选择"自定义"手动配置。
 */
object ProviderPresets {

    data class Provider(
        val name: String,           // 显示名称
        val apiEndpoint: String,    // API 端点
        val defaultModel: String,   // 默认模型
        val models: List<String>,   // 可选模型列表
        val requiresApiKey: Boolean = true,
        val description: String = ""
    )

    val ALL_PROVIDERS: List<Provider> = listOf(
        Provider(
            name = "自定义",
            apiEndpoint = "",
            defaultModel = "",
            models = emptyList(),
            requiresApiKey = true,
            description = "手动填写 API 端点和模型"
        ),
        Provider(
            name = "DeepSeek (深度求索)",
            apiEndpoint = "https://api.deepseek.com/v1/chat/completions",
            defaultModel = "deepseek-chat",
            models = listOf(
                "deepseek-chat",           // DeepSeek-V3
                "deepseek-reasoner",       // DeepSeek-R1
            ),
            requiresApiKey = true,
            description = "国产高性价比模型，需在 platform.deepseek.com 获取 API Key"
        ),
        Provider(
            name = "OpenAI",
            apiEndpoint = "https://api.openai.com/v1/chat/completions",
            defaultModel = "gpt-4o-mini",
            models = listOf(
                "gpt-4o-mini",
                "gpt-4o",
                "gpt-4.1-mini",
                "gpt-4.1-nano",
                "o4-mini",
            ),
            requiresApiKey = true,
            description = "需在 platform.openai.com 获取 API Key"
        ),
        Provider(
            name = "智谱 AI (GLM)",
            apiEndpoint = "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            defaultModel = "glm-4-flash",
            models = listOf(
                "glm-4-flash",
                "glm-4-plus",
                "glm-4-air",
                "glm-4-long",
            ),
            requiresApiKey = true,
            description = "国产模型，需在 open.bigmodel.cn 获取 API Key"
        ),
        Provider(
            name = "通义千问 (Qwen)",
            apiEndpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            defaultModel = "qwen-plus",
            models = listOf(
                "qwen-turbo",
                "qwen-plus",
                "qwen-max",
                "qwen3-235b-a22b",
            ),
            requiresApiKey = true,
            description = "阿里云模型，需在 dashscope.aliyun.com 获取 API Key"
        ),
        Provider(
            name = "Moonshot (Kimi)",
            apiEndpoint = "https://api.moonshot.cn/v1/chat/completions",
            defaultModel = "moonshot-v1-8k",
            models = listOf(
                "moonshot-v1-8k",
                "moonshot-v1-32k",
                "moonshot-v1-128k",
            ),
            requiresApiKey = true,
            description = "月之暗面 Kimi，需在 platform.moonshot.cn 获取 API Key"
        ),
        Provider(
            name = "硅基流动 (SiliconFlow)",
            apiEndpoint = "https://api.siliconflow.cn/v1/chat/completions",
            defaultModel = "deepseek-ai/DeepSeek-V3",
            models = listOf(
                "deepseek-ai/DeepSeek-V3",
                "deepseek-ai/DeepSeek-R1",
                "Qwen/Qwen2.5-7B-Instruct",
                "Qwen/Qwen2.5-72B-Instruct",
                "meta-llama/Llama-3.3-70B-Instruct",
            ),
            requiresApiKey = true,
            description = "国产模型聚合平台，免费额度，需在 siliconflow.cn 获取 API Key"
        ),
        Provider(
            name = "DeepSeek (官方)",
            apiEndpoint = "https://api.deepseek.com/v1/chat/completions",
            defaultModel = "deepseek-chat",
            models = listOf("deepseek-chat", "deepseek-reasoner"),
            requiresApiKey = true,
            description = "DeepSeek 官方 API"
        ),
    )

    /** 根据名称查找提供方 */
    fun findByName(name: String): Provider? {
        return ALL_PROVIDERS.find { it.name == name }
    }
}