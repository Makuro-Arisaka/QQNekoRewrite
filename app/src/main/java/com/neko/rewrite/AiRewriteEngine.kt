package com.neko.rewrite

import com.neko.rewrite.model.ChatRequest
import com.neko.rewrite.model.ChatResponse
import com.neko.rewrite.model.ModuleConfig
import de.robv.android.xposed.XposedBridge
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * AI 改写引擎
 *
 * — 空 API Key 直接跳过（不发起请求）
 * — 401/429 等错误区分记录
 * — 网络超时/不可达区分记录
 * — 所有异常降级为原文
 */
object AiRewriteEngine {

    private const val TAG = "AiEngine"
    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /** 未配置超时（timeoutMs <= 0）时，等待在途请求的上限，避免无限期挂起 */
    private const val DEFAULT_WAIT_MS = 20_000L

    /** 在途改写请求表：key（模型+提示词+原文）→ 正在执行的请求 */
    private val inFlight = ConcurrentHashMap<String, InFlightCall>()

    /**
     * 一个正在执行中的改写请求。
     *
     * 相同 key 的后到请求会等待其结果，而【不会】再发一次 AI 调用 ——
     * 用户在队列降级为同步路径、或快速连发相同内容时，避免重复消耗 token。
     */
    private class InFlightCall {
        val latch = CountDownLatch(1)

        @Volatile
        var result: String? = null

        fun complete(value: String) {
            result = value
            latch.countDown()
        }

        /** 等待在途请求完成；超时、被中断或结果为空时一律回退原文 */
        fun await(fallback: String, timeoutMs: Int): String {
            return try {
                val waitMs = if (timeoutMs > 0) timeoutMs.toLong() else DEFAULT_WAIT_MS
                if (latch.await(waitMs, TimeUnit.MILLISECONDS)) {
                    result ?: fallback
                } else {
                    XposedBridge.log("[NekoRewrite] ⏱️ 等待在途改写超时，使用原文")
                    fallback
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                fallback
            }
        }
    }

    /**
     * 预检：当前配置是否允许发起 AI 调用
     */
    fun canRewrite(): Boolean {
        if (!ConfigManager.config.enabled) return false
        if (ConfigManager.config.apiKey.isBlank()) return false
        return true
    }

    /**
     * 同步改写文本（带超时和降级）
     *
     * @param originalText 原始消息文本
     * @param timeoutMs 本次调用的总超时，超时即降级为原文；<=0 表示不限制
     * @return 改写后的文本，失败时返回原文
     */
    fun rewrite(originalText: String, timeoutMs: Int = ConfigManager.config.rewriteTimeoutMs): String {
        // 空文本直接返回
        if (originalText.isBlank()) return originalText

        val config = ConfigManager.config

        // API Key 为空 → 跳过 AI 调用
        if (config.apiKey.isBlank()) {
            LogRecorder.warn(TAG, "API Key 为空，跳过 AI 调用")
            return originalText
        }

        val key = RewriteCache.buildKey(originalText, config.systemPrompt, config.model)

        // 1) 缓存命中：直接复用上次结果，跳过网络调用（省 token / 降延迟）
        RewriteCache.getByKey(key)?.let { cached ->
            XposedBridge.log("[NekoRewrite] 💾 改写缓存命中，跳过 AI 调用")
            LogRecorder.debug(TAG, "改写缓存命中")
            return cached
        }

        // 2) 并发合并：同一 key 的请求已在执行中，等待其结果而非重复调用 AI
        val pending = inFlight[key]
        if (pending != null) {
            XposedBridge.log("[NekoRewrite] 🔗 相同改写正在执行，复用其结果（避免重复消耗 token）")
            LogRecorder.debug(TAG, "并发请求合并")
            return pending.await(originalText, timeoutMs)
        }

        // 3) 正常执行，并把结果共享给期间到达的相同请求
        val call = InFlightCall()
        inFlight[key] = call
        return try {
            performRewrite(originalText, timeoutMs, config).also { call.complete(it) }
        } finally {
            inFlight.remove(key)
            call.latch.countDown() // 异常时兜底放行等待者，避免永久挂起
        }
    }

    /** 真正发起一次 AI 请求（调用方已做过缓存与并发合并判断） */
    private fun performRewrite(
        originalText: String,
        timeoutMs: Int,
        config: ModuleConfig
    ): String {
        val requestBody = ChatRequest(
            model = config.model,
            messages = PromptManager.buildMessages(config.systemPrompt, originalText),
            temperature = config.temperature,
            maxTokens = config.maxTokens
        )

        val request = Request.Builder()
            .url(config.apiEndpoint)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(requestBody).toRequestBody(JSON_MEDIA))
            .build()

        return try {
            XposedBridge.log("[NekoRewrite] 🤖 调用 AI: ${config.apiEndpoint.take(40)}...")
            LogRecorder.debug(TAG, "调用 AI API: ${config.apiEndpoint.take(40)}... 模型=${config.model}")

            // callTimeout 覆盖连接+读+写的总耗时，作为硬性熔断；
            // newBuilder() 共享连接池与线程池，不会额外建连
            val callClient = if (timeoutMs > 0) {
                client.newBuilder().callTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS).build()
            } else {
                client
            }

            val response = callClient.newCall(request).execute()
            val body = response.body?.string() ?: throw Exception("Empty response body")

            when (response.code) {
                401 -> {
                    XposedBridge.log("[NekoRewrite] ❌ API Key 无效 (401)，请检查 API Key")
                    LogRecorder.error(TAG, "API Key 无效 (401)")
                    throw Exception("API key invalid (401)")
                }
                429 -> {
                    XposedBridge.log("[NekoRewrite] ⚠️ API 限流 (429)，稍后重试")
                    LogRecorder.warn(TAG, "API 限流 (429)")
                    throw Exception("Rate limited (429)")
                }
                in 400..499 -> {
                    XposedBridge.log("[NekoRewrite] ❌ API 客户端错误 ${response.code}: ${body.take(100)}")
                    LogRecorder.error(TAG, "API 客户端错误 ${response.code}")
                    throw Exception("API client error ${response.code}")
                }
                in 500..599 -> {
                    XposedBridge.log("[NekoRewrite] ⚠️ API 服务器错误 ${response.code}")
                    LogRecorder.warn(TAG, "API 服务器错误 ${response.code}")
                    throw Exception("API server error ${response.code}")
                }
                !in 200..299 -> {
                    XposedBridge.log("[NekoRewrite] ❌ API 异常状态码 ${response.code}")
                    LogRecorder.error(TAG, "API 异常状态码 ${response.code}")
                    throw Exception("API unexpected status ${response.code}")
                }
            }

            // 200-299: 成功
            val chatResponse = json.decodeFromString<ChatResponse>(body)
            val rewritten = chatResponse.choices.firstOrNull()
                ?.message?.content
                ?.trim()
                ?: throw Exception("Empty choice in response")

            XposedBridge.log("[NekoRewrite] 🤖 AI 返回: ${rewritten.take(80)}")
            LogRecorder.debug(TAG, "AI 返回: ${rewritten.take(80)}${if (rewritten.length > 80) "..." else ""}")

            val result = rewritten.ifEmpty { originalText }
            // 仅缓存成功改写（≠ 原文）；失败已在上方 catch 中降级、不会到达此处
            if (result != originalText) {
                RewriteCache.put(originalText, result, config.systemPrompt, config.model)
                XposedBridge.log("[NekoRewrite] 💾 改写结果已缓存（共 ${RewriteCache.size()} 条）")
            }
            result

        } catch (e: SocketTimeoutException) {
            XposedBridge.log("[NekoRewrite] ⏱️ AI 请求超时: ${e.message}")
            LogRecorder.warn(TAG, "AI 请求超时: ${e.message}")
            originalText
        } catch (e: IOException) {
            // callTimeout 触发时 OkHttp 抛的是 IOException("timeout")，不是 SocketTimeoutException
            val msg = e.message.orEmpty()
            if (msg.contains("timeout", ignoreCase = true)) {
                XposedBridge.log("[NekoRewrite] ⏱️ AI 请求总超时 (${timeoutMs}ms)")
                LogRecorder.warn(TAG, "AI 请求总超时 (${timeoutMs}ms)")
            } else {
                XposedBridge.log("[NekoRewrite] 🌐 AI 网络错误: $msg")
                LogRecorder.error(TAG, "AI 网络错误: $msg")
            }
            originalText
        } catch (e: UnknownHostException) {
            XposedBridge.log("[NekoRewrite] 🌐 AI 服务器不可达: ${e.message}")
            LogRecorder.warn(TAG, "AI 服务器不可达: ${e.message}")
            originalText
        } catch (e: SSLException) {
            XposedBridge.log("[NekoRewrite] 🔒 SSL 连接错误: ${e.message}")
            LogRecorder.error(TAG, "SSL 连接错误: ${e.message}")
            originalText
        } catch (e: Exception) {
            XposedBridge.log("[NekoRewrite] ❌ AI 异常: ${e.message}")
            LogRecorder.error(TAG, "AI 改写失败: ${e.message}")
            originalText
        }
    }
}