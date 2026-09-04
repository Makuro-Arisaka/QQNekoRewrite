package com.neko.rewrite

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 联系人过滤器
 *
 * 负责：
 * 1. 从 Hook 参数中识别并提取 Contact 信息（peerUid / chatType）
 * 2. 根据配置的 filterMode 判定当前消息是否应被改写
 */
object ContactFilter {

    /** 过滤模式常量 */
    const val MODE_OFF = 0          // 不限制
    const val MODE_WHITELIST = 1    // 仅白名单
    const val MODE_BLACKLIST = 2    // 排除黑名单

    data class ContactInfo(
        val peerUid: String? = null,
        val chatType: Int = 0
    ) {
        val isValid: Boolean get() = !peerUid.isNullOrBlank()
        val typeLabel: String
            get() = when (chatType) {
                1 -> "私聊"
                2 -> "群聊"
                else -> "未知($chatType)"
            }
    }

    /**
     * 从 Hook 参数列表中提取 Contact 信息。
     * 遍历所有参数，反射查找包含 peerUid 字段的对象。
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
        return try {
            // 先确认对象是 Contact（有 peerUid 或 peer 字段）
            var peerUid: String? = null
            for (field in uidFieldNames) {
                try {
                    val v = XposedHelpers.getObjectField(obj, field) as? String
                    if (!v.isNullOrBlank()) { peerUid = v; break }
                } catch (_: Throwable) { }
            }
            if (peerUid.isNullOrBlank()) return null

            // 提取 chatType（可选）
            var chatType = 0
            try { chatType = XposedHelpers.getIntField(obj, "chatType") } catch (_: Throwable) { }

            ContactInfo(peerUid, chatType)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 判定是否应跳过改写（true = 跳过，直接发原文）
     */
    fun shouldSkip(peerUid: String?): Boolean {
        val config = ConfigManager.config
        if (peerUid.isNullOrBlank()) return false  // 无法识别联系人时不做限制

        return when (config.filterMode) {
            MODE_OFF -> false
            MODE_WHITELIST -> peerUid !in config.whitelist
            MODE_BLACKLIST -> peerUid in config.blacklist
            else -> false
        }
    }

    /**
     * 记录联系人信息到日志（用于设置页"最近联系人"选择器）
     */
    fun logContact(peerUid: String, chatType: Int) {
        val label = when (chatType) {
            1 -> "私聊"
            2 -> "群聊"
            else -> "未知"
        }
        LogRecorder.msg("Contact", "peer=$peerUid ($label)")
    }
}