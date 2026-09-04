package com.neko.rewrite.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI Chat Completions API 请求/响应模型
 */

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float = 0.8f,
    @SerialName("max_tokens")
    val maxTokens: Int = 500
)

@Serializable
data class ChatMessage(
    val role: String,   // "system" | "user" | "assistant"
    val content: String
)

@Serializable
data class ChatResponse(
    val choices: List<ChatChoice>
)

@Serializable
data class ChatChoice(
    val message: ChatMessage
)