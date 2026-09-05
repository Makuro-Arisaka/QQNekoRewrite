package com.neko.rewrite

import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 联系人过滤器
 *
 * 负责：
 * 1. 从 Hook 参数中识别并提取 Contact 信息（peerUid / peerUin / chatType）
 * 2. 维护「QQ号 ↔ peerUid」映射：
 *    - peerUid：私聊为 u_xxx 内部会话 ID，群聊通常就是群号字符串
 *    - peerUin：用户可读的 QQ 号 / 群号
 *    设置页白/黑名单存的是 QQ 号/群号，因此过滤时两者任一命中即视为匹配。
 *    peerUin 在不同 QQ 版本上字段名/所在对象不固定，采用反射枚举字段树 +
 *    跨参数扫描兜底（详见 [scanUinFields]）。
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

    /** uin 提取失败的一次性诊断日志开关（每进程只打一次） */
    @Volatile
    private var diagDone = false

    /**
     * 从 Hook 参数列表中提取 Contact 信息。
     * 遍历所有参数，反射查找包含 peerUid 字段的对象；
     * 若该对象上取不到 uin，再跨其余参数扫描（MsgRecord 等对象常带 peerUin）。
     */
    fun extractContact(args: Array<Any>): ContactInfo {
        for (arg in args) {
            val info = try { tryExtract(arg) } catch (_: Throwable) { null } ?: continue
            if (info.isValid) {
                val merged = if (info.peerUin.isNullOrBlank()) {
                    val uin = scanArgsForUin(args, skip = arg)
                    info.copy(peerUin = uin)
                } else {
                    info
                }
                if (merged.peerUin.isNullOrBlank()) logUinDiag(args, arg)
                return merged
            }
        }
        return ContactInfo()
    }

    private fun tryExtract(obj: Any): ContactInfo? {
        // Contact 可能有多个候选字段名（不同 QQ 版本）
        val uidFieldNames = listOf("peerUid", "uid", "peer")
        return try {
            var peerUid: String? = null
            for (field in uidFieldNames) {
                try {
                    val v = XposedHelpers.getObjectField(obj, field) as? String
                    if (!v.isNullOrBlank()) { peerUid = v; break }
                } catch (_: Throwable) { }
            }
            if (peerUid.isNullOrBlank()) return null

            // 「QQ号 ↔ peerUid」映射的另一半：uin（QQ号/群号），字段名随版本不定，用枚举扫描
            val peerUin = scanUinFields(obj)

            // 提取 chatType（可选）
            var chatType = 0
            try { chatType = XposedHelpers.getIntField(obj, "chatType") } catch (_: Throwable) { }

            ContactInfo(peerUid, peerUin, chatType)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 在对象自身的字段树（含父类）里找 uin 类 String 字段。
     * 命中名单：peerUin / uin / 以 "Uin" 结尾的字段；
     * 显式排除 senderUin / selfUin（发送者是本人，不能当联系人 QQ 号）。
     */
    private fun scanUinFields(obj: Any): String? {
        var c: Class<*>? = obj.javaClass
        var depth = 0
        while (c != null && c != Any::class.java && depth < 4) {
            for (f in c.declaredFields) {
                try {
                    val n = f.name
                    if (n == "senderUin" || n == "selfUin") continue
                    val isUinLike = n == "peerUin" || n == "uin" ||
                        (n.endsWith("Uin") && f.type == String::class.java)
                    if (!isUinLike) continue
                    f.isAccessible = true
                    val v = f.get(obj) as? String
                    if (!v.isNullOrBlank() && v != "0") return v
                } catch (_: Throwable) { }
            }
            c = c.superclass
            depth++
        }
        return null
    }

    /** uin 在联系人对象上找不到时，跨其余参数扫描（如 MsgRecord 上的 peerUin） */
    private fun scanArgsForUin(args: Array<Any>, skip: Any): String? {
        for (arg in args) {
            if (arg === skip || arg == null) continue
            val uin = try { scanUinFields(arg) } catch (_: Throwable) { null }
            if (!uin.isNullOrBlank()) return uin
        }
        return null
    }

    /**
     * uin 仍取不到时，把联系人对象的类名、全部 String 字段名与各参数类名打一次日志，
     * 便于下一轮直接定位字段（每进程仅一次）。
     */
    private fun logUinDiag(args: Array<Any>, contactObj: Any) {
        if (diagDone) return
        diagDone = true
        try {
            val sb = StringBuilder("[NekoRewrite] 🔍 uin 提取失败诊断 contactObj=")
                .append(contactObj.javaClass.name)
            val names = mutableListOf<String>()
            var c: Class<*>? = contactObj.javaClass
            var d = 0
            while (c != null && c != Any::class.java && d < 4) {
                for (f in c.declaredFields) if (f.type == String::class.java) names.add(f.name)
                c = c.superclass
                d++
            }
            sb.append(" stringFields=[").append(names.joinToString(",")).append("]")
            for ((i, a) in args.withIndex()) {
                sb.append(" | arg$i=").append(a?.javaClass?.name ?: "null")
            }
            XposedBridge.log(sb.toString())
        } catch (_: Throwable) { }
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
