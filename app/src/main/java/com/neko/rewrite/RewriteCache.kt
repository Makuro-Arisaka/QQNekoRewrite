package com.neko.rewrite

import java.util.LinkedHashMap

/**
 * 改写结果 LRU 缓存。
 *
 * - key = 模型 + 系统提示词 + 原文（用不可见分隔符拼接，避免边界歧义误命中）。
 *   更换提示词 / 模型后旧结果自动失效，不会拿到张冠李戴的改写。
 * - 仅缓存「成功改写」（改写结果 ≠ 原文）；失败一律不缓存，避免瞬时错误被钉死。
 * - 上限 [MAX_ENTRIES]，超出按最近最少使用（LRU）淘汰。
 * - 读写为进程内存态，QQ 重启后清空，属正常降级（首几条重新走 API）。
 * - 所有访问加锁，供异步改写线程与同步线程安全共用。
 */
object RewriteCache {

    private const val MAX_ENTRIES = 200
    private const val SEP = "\u0001" // 不可见分隔符，区分 模型 / 提示词 / 原文 三段

    private val lock = Any()

    private val store = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean {
            return size > MAX_ENTRIES
        }
    }

    /**
     * 组装缓存 key。设为 public 以便 [AiRewriteEngine] 用同一 key 做并发请求合并
     * （缓存与在途请求表必须口径一致，否则合并会张冠李戴）。
     */
    fun buildKey(original: String, systemPrompt: String, model: String): String {
        return "$model$SEP$systemPrompt$SEP$original"
    }

    fun get(original: String, systemPrompt: String, model: String): String? {
        return getByKey(buildKey(original, systemPrompt, model))
    }

    /** 按已组装好的 key 查询缓存 */
    fun getByKey(key: String): String? {
        synchronized(lock) { return store[key] }
    }

    fun put(original: String, rewritten: String, systemPrompt: String, model: String) {
        val key = buildKey(original, systemPrompt, model)
        synchronized(lock) { store[key] = rewritten }
    }

    /** 当前缓存条目数，仅供概览/调试展示 */
    fun size(): Int = synchronized(lock) { store.size }
}
