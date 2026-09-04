package com.neko.rewrite

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * API 连接探测 / 可用模型获取工具（运行在模块 App 进程，供设置页使用）。
 *
 * 与 `AiRewriteEngine` 独立：入参直接用设置页【当前输入】的 endpoint / apiKey / model，
 * 无需先保存配置即可测试。这样用户填好就能"检查连接 / 获取可用模型"。
 *
 * ## 检查连接
 * 向 endpoint 发一个 `max_tokens=1` 的最小 chat 请求：
 * - 2xx → 连接与鉴权均正常
 * - 401/403 → Key 无效
 * - 4xx（模型不存在等）→ 端点/模型有问题，但鉴权已过
 * - 网络异常 → 分类提示
 *
 * ## 获取可用模型
 * 把 endpoint 末尾的 `/chat/completions` 替换为 `/models` 后发 GET：
 * - 预设提供方（DeepSeek/OpenAI/智谱/通义/Kimi/硅基）均支持 OpenAI 兼容
 *   `GET .../models`，返回 `data[].id`，故可统一推导。
 * - "自定义"只要填的是 chat/completions 端点，同样能推导。
 */
object ApiProbe {

    private const val TAG = "ApiProbe"
    private val json = Json { ignoreUnknownKeys = true }

    // 复用与 AiRewriteEngine 一致的超时策略：连接 5s、读 15s，另加总 callTimeout 兜底
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    @Serializable
    private data class ProbeRequest(
        val model: String,
        val messages: List<ProbeMessage>,
        @SerialName("max_tokens")
        val maxTokens: Int = 1
    )

    @Serializable
    private data class ProbeMessage(val role: String, val content: String)

    @Serializable
    private data class ModelsResponse(
        val data: List<ModelInfo> = emptyList()
    )

    @Serializable
    private data class ModelInfo(val id: String)

    /** 模型获取结果：成功返回模型列表，失败返回 null（原因见 [error]） */
    class ModelsResult {
        var models: List<String> = emptyList()
        var error: String? = null
        val ok: Boolean get() = error == null
    }

    /**
     * 检查连接。返回可直接展示给用户的文本。
     */
    fun probe(endpoint: String, apiKey: String, model: String): String {
        if (endpoint.isBlank() || apiKey.isBlank()) {
            return "请先填写 API 端点与 API Key"
        }
        val body = ProbeRequest(
            model = model.ifBlank { "deepseek-chat" },
            messages = listOf(ProbeMessage("user", "ping"))
        )
        val request = Request.Builder()
            .url(endpoint.trim())
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(ProbeRequest.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                when (resp.code) {
                    in 200..299 -> "✅ 连接正常，API Key 有效（HTTP ${resp.code}）"
                    401, 403 -> "❌ 鉴权失败（HTTP ${resp.code}），请检查 API Key"
                    in 400..499 -> "⚠️ 端点可达但返回 ${resp.code}（多为模型名无效）：${brief(resp.body?.string())}"
                    in 500..599 -> "⚠️ 服务端错误（HTTP ${resp.code}），请稍后重试"
                    else -> "⚠️ 意外响应（HTTP ${resp.code}）"
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            "⏱️ 连接超时，请检查端点地址或网络"
        } catch (e: java.net.UnknownHostException) {
            "🌐 无法解析域名，请检查端点地址"
        } catch (e: javax.net.ssl.SSLException) {
            "🔒 SSL 错误：${e.message ?: "证书/握手失败"}"
        } catch (e: IOException) {
            "🌐 网络错误：${e.message ?: e.javaClass.simpleName}"
        } catch (e: Exception) {
            "❌ 探测失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 获取可用模型。成功填入 [out].models；失败填 [out].error。
     * 返回 false 表示失败。
     */
    fun fetchModels(endpoint: String, apiKey: String, out: ModelsResult): Boolean {
        if (endpoint.isBlank() || apiKey.isBlank()) {
            out.error = "请先填写 API 端点与 API Key"
            return false
        }
        val modelsUrl = deriveModelsUrl(endpoint.trim())
        if (modelsUrl == null) {
            out.error = "无法从端点推导模型列表地址，请确认端点以 /chat/completions 结尾"
            return false
        }

        val request = Request.Builder()
            .url(modelsUrl)
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                val bodyStr = resp.body?.string().orEmpty()
                when (resp.code) {
                    in 200..299 -> {
                        try {
                            val parsed = json.decodeFromString(ModelsResponse.serializer(), bodyStr)
                            val ids = parsed.data.map { it.id }.filter { it.isNotBlank() }.distinct()
                            if (ids.isEmpty()) {
                                out.error = "返回了模型列表但为空，可能需调整模型服务配置"
                                false
                            } else {
                                out.models = ids
                                true
                            }
                        } catch (e: Exception) {
                            out.error = "解析模型列表失败：${e.message ?: "响应格式异常"}"
                            false
                        }
                    }
                    401, 403 -> {
                        out.error = "鉴权失败（HTTP ${resp.code}），请检查 API Key"
                        false
                    }
                    in 400..499 -> {
                        out.error = "该提供方可能不支持模型列表接口（HTTP ${resp.code}）：${brief(bodyStr)}"
                        false
                    }
                    in 500..599 -> {
                        out.error = "服务端错误（HTTP ${resp.code}），请稍后重试"
                        false
                    }
                    else -> {
                        out.error = "意外响应（HTTP ${resp.code}）"
                        false
                    }
                }
            }
        } catch (e: java.net.SocketTimeoutException) {
            out.error = "⏱️ 连接超时，请检查端点地址或网络"
            false
        } catch (e: java.net.UnknownHostException) {
            out.error = "🌐 无法解析域名，请检查端点地址"
            false
        } catch (e: javax.net.ssl.SSLException) {
            out.error = "🔒 SSL 错误：${e.message ?: "证书/握手失败"}"
            false
        } catch (e: IOException) {
            out.error = "🌐 网络错误：${e.message ?: e.javaClass.simpleName}"
            false
        } catch (e: Exception) {
            out.error = "❌ 请求失败：${e.message ?: e.javaClass.simpleName}"
            false
        }
    }

    /** 把 chat/completions 端点推导为同级 /models 端点 */
    private fun deriveModelsUrl(chatUrl: String): String? {
        val trimmed = chatUrl.trim().removeSuffix("/")
        return when {
            trimmed.endsWith("/chat/completions") ->
                trimmed.removeSuffix("/chat/completions") + "/models"
            else -> null
        }
    }

    /** 截取错误正文，避免界面被超长 JSON 撑爆 */
    private fun brief(s: String?, max: Int = 120): String {
        if (s.isNullOrBlank()) return ""
        val oneLine = s.replace('\n', ' ').trim()
        return if (oneLine.length > max) oneLine.take(max) + "…" else oneLine
    }
}
