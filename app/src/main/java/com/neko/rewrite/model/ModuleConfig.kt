package com.neko.rewrite.model

import kotlinx.serialization.Serializable

/**
 * 模块配置数据类
 */
@Serializable
data class ModuleConfig(
    val enabled: Boolean = true,
    val apiEndpoint: String = "https://api.deepseek.com/v1/chat/completions",
    val apiKey: String = "",
    val model: String = "deepseek-chat",
    /** 选中的 AI 提供方预设名称；"自定义" 表示手动填写端点与模型 */
    val provider: String = "DeepSeek (深度求索)",
    val maxTokens: Int = 500,
    val timeoutSeconds: Int = 10,
    val temperature: Float = 0.8f,
    val showToast: Boolean = true,          // 改写成功时显示 Toast 预览
    /** QQ 启动时是否弹「已加载」Toast（默认关闭，避免暴露模块存在） */
    val showStartupToast: Boolean = false,
    /**
     * 通知栏快速开关：在 QQ 通知栏显示一条常驻通知，可一键启用/停用改写。
     * 默认关闭 —— 常驻通知比 Toast 更显眼，会持续暴露模块存在，
     * 仅在用户确实需要免开 App 快速切换时才开启。
     */
    val quickToggle: Boolean = false,
    /**
     * 是否把运行日志写入文件。
     *
     * 默认关闭：日志里含被拦截的原文与 AI 返回结果，属于敏感内容，
     * 且写文件本身有 I/O 开销。需要排查问题时在「运行日志」页手动开启，
     * 开启时会先向系统申请「所有文件访问」权限（日志要写到 QQ 也能写的共享目录）。
     */
    val logEnabled: Boolean = false,
    /**
     * 异步改写：拦截后先放行发送线程，AI 改写完成后再重放原方法。
     * 关闭则退回同步改写（阻塞发送线程，但行为最保守）。
     */
    val asyncRewrite: Boolean = true,
    /** 单次 AI 改写的总超时（毫秒），超时即降级为原文 */
    val rewriteTimeoutMs: Int = 8000,
    val filterMode: Int = 0,                // 联系人过滤: 0=不限制, 1=仅白名单, 2=排除黑名单
    val whitelist: Set<String> = emptySet(),
    val blacklist: Set<String> = emptySet(),
    val systemPrompt: String = "", // 默认值在 ConfigManager 中设置
    /**
     * 配置写入时间戳（毫秒）。
     * 用于多来源仲裁：QQ 进程与模块进程各存一份配置，
     * 取 lastUpdated 最大的那份，避免旧配置覆盖新配置。
     */
    val lastUpdated: Long = 0L
)