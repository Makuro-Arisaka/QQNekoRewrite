package com.neko.rewrite

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 联系人过滤器
 *
 * 负责：
 * 1. 从 Hook 参数中识别并提取 Contact 信息（peerUid / peerUin / chatType）
 * 2. 维护「QQ号 ↔ peerUid」映射：QQ NT 的 Contact 同时携带
 *    - peerUid：形如 u_xxx 的内部会话 ID（群聊时通常就是群号字符串）
 *    - peerUin：用户可读的 QQ 号 / 群号
 *    设置页白/黑名单存的是 QQ 号/群号，因此过滤时两者任一命中即视为匹配。
 * 3. 根据配置的 filterMode 判定当前消息是否应被改写
 */
object ContactFilter {

    /** 过滤模式常量 */
    const val MODE_OFF = 0          // 不限制
    const val MODE_WHITELIST = 1    // 仅白名单
    const val MODE_BLACKLIST = 2    // 排除黑名单

    data class ContactInfo(
        val peerUid: String? = null,
        val peerUin: String? = null,
        val chatType: Int = 0
    ) {
        val isValid: Boolean get() = !peerUid.isNullOrBlank() || !peerUin.isNullOrBlank()

        /** 过滤匹配用的候选键（QQ号优先，其次内部 uid） */
        val matchKeys: List<String>
            get() = listOf(peerUin, peerUid).filterNotNull().filter { it.isNotBlank() }

        val typeLabel: String
            get() = when (chatType) {
                1 -> "私聊"
                2 -> "群聊"
                else -> "未知($chatType)"
            }
    }

    /**
     * 从 Hook 参数列表中提取 Contact 信息。
     * 遍历所有参数，反射查找包含 peerUid / peerUin 字段的对象。
     */
    fun extractContact(args: Array<Any>): ContactInfo {
        for (arg in args) {
            val info = try { tryExtract(arg) } catch (_: Throwable) { null } ?: continue
            if (info.isValid) return info
        }
        return ContactInfo()
    }

    private fun tryExtract(obj: Any): ContactInfo? {
        // Contact 可能有多个候选字段名（不同 QQ 版本）
        val uidFieldNames = listOf("peerUid", "uid", "peer")
        val uinFieldNames = listOf("peerUin", "uin")
        return try {
            var peerUid: String? = null
            for (field in uidFieldNames) {
                try {
                    val v = XposedHelpers.getObjectField(obj, field) as? String
                    if (!v.isNullOrBlank()) { peerUid = v; break }
                } catch (_: Throwable) { }
            }

            // 「QQ号 ↔ peerUid」映射的另一半：peerUin（QQ号/群号）
            var peerUin: String? = null
            for (field in uinFieldNames) {
                try {
                    val v = XposedHelpers.getObjectField(obj, field) as? String
                    if (!v.isNullOrBlank()) { peerUin = v; break }
                } catch (_: Throwable) { }
            }

            if (peerUid.isNullOrBlank() && peerUin.isNullOrBlank()) return null

            // 提取 chatType（可选）
            var chatType = 0
            try { chatType = XposedHelpers.getIntField(obj, "chatType") } catch (_: Throwable) { }

            ContactInfo(peerUid, peerUin, chatType)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 判定是否应跳过改写（true = 跳过，直接发原文）。
     * 白/黑名单存的是 QQ 号/群号（peerUin），Hook 侧拿到的是内部 peerUid；
     * 匹配时两者任一命中即可（旧版仅存 uid 的条目也继续生效）。
     */
    fun shouldSkip(contact: ContactInfo): Boolean {
        val config = ConfigManager.config
        val keys = contact.matchKeys
        if (keys.isEmpty()) return false  // 无法识别联系人时不做限制

        return when (config.filterMode) {
            MODE_OFF -> false
            MODE_WHITELIST -> keys.none { it in config.whitelist }
            MODE_BLACKLIST -> keys.any { it in config.blacklist }
            else -> false
        }
    }

    /** 记录联系人信息到日志（uid 与 uin 一并记录，便于排查映射是否生效） */
    fun logContact(contact: ContactInfo) {
        val label = when (contact.chatType) {
            1 -> "私聊"
            2 -> "群聊"
            else -> "未知"
        }
        LogRecorder.msg("Contact", "peer=${contact.peerUid ?: "?"} uin=${contact.peerUin ?: "?"} ($label)")
    }
}
