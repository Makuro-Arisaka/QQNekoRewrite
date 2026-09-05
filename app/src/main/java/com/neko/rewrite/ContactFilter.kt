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
 *
 *    ⚠️ QQ 9.1.35 的 kernelpublic.nativeinterface.Contact 实测只有
 *    [guildId, peerUid] 两个 String 字段（诊断日志实锤），peerUin 若存在
 *    只可能是数值型；因此扫描逻辑：支持数值 uin、有界嵌套、集合元素，
 *    仍失败则打印全字段名:类型诊断（每进程一次）。
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
     * 若该对象上取不到 uin，再跨其余参数扫描（含集合元素与嵌套对象）。
     */
    fun extractContact(args: Array<Any>): ContactInfo {
        for (arg in args) {
            val info = try { tryExtract(arg) } catch (_: Throwable) { null } ?: continue
            if (info.isValid) {
                var merged = if (info.peerUin.isNullOrBlank()) {
                    info.copy(peerUin = scanArgsForUin(args, skip = arg))
                } else {
                    info
                }
                // 运行时扫描拿不到 QQ 号时，查收信侧建立的持久映射（UidMap）
                if (merged.peerUin.isNullOrBlank() && !info.peerUid.isNullOrBlank()) {
                    val mapped = UidMap.get(info.peerUid!!)
                    if (mapped != null) merged = merged.copy(peerUin = mapped)
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

            // 「QQ号 ↔ peerUid」映射的另一半：uin（QQ号/群号），字段名/类型随版本不定
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
     * 在对象字段树里找 uin。
     * - depth=0（参数/联系人对象本身）：接受 peerUin / uin / *Uin（排除 senderUin/selfUin），
     *   值可为 String 或数值（QQ 号可超 int 上限，按 Number.toString）
     * - depth>0（嵌套对象，仅 com.tencent 类）：只接受无歧义的 peerUin，
     *   防止把运行时对象里的「本人 uin」误当联系人号
     * - 嵌套最深 2 层、总扫描对象数 ≤ 48，避免性能问题
     */
    private fun scanUinFields(obj: Any, depth: Int = 0, budget: IntArray = intArrayOf(48)): String? {
        if (depth > 2 || budget[0] <= 0) return null
        budget[0]--
        var c: Class<*>? = obj.javaClass
        var d = 0
        while (c != null && c != Any::class.java && d < 4) {
            for (f in c.declaredFields) {
                try {
                    val n = f.name
                    if (n == "senderUin" || n == "selfUin") continue
                    val hit = if (depth == 0) {
                        n == "peerUin" || n == "uin" || n.endsWith("Uin")
                    } else {
                        n == "peerUin"
                    }
                    if (!hit) continue
                    f.isAccessible = true
                    val s = when (val v = f.get(obj)) {
                        null -> null
                        is String -> v.ifBlank { null }
                        is Number -> if (v.toLong() >= 10000) v.toString() else null
                        else -> null
                    }
                    if (!s.isNullOrBlank()) return s
                } catch (_: Throwable) { }
            }
            c = c.superclass
            d++
        }
        // 嵌套：仅继续扫 QQ 自带的普通对象字段（跳过 String/基本类型/集合/Map）
        if (depth < 2) {
            c = obj.javaClass
            d = 0
            while (c != null && c != Any::class.java && d < 3) {
                for (f in c.declaredFields) {
                    try {
                        if (f.type.isPrimitive || f.type == String::class.java) continue
                        if (f.name.startsWith("this$")) continue
                        f.isAccessible = true
                        val v = f.get(obj) ?: continue
                        if (v is Collection<*> || v is Map<*, *>) continue
                        val cn = v.javaClass.name
                        if (!cn.startsWith("com.tencent") && !cn.startsWith("kotlin.")) continue
                        val r = scanUinFields(v, depth + 1, budget)
                        if (r != null) return r
                    } catch (_: Throwable) { }
                }
                c = c.superclass
                d++
            }
        }
        return null
    }

    /** uin 在联系人对象上找不到时，跨其余参数扫描（集合参数逐元素扫） */
    private fun scanArgsForUin(args: Array<Any>, skip: Any): String? {
        val budget = intArrayOf(48)
        for (arg in args) {
            if (arg === skip || arg == null) continue
            if (arg is Collection<*>) {
                for (e in arg.take(20)) {
                    if (e == null) continue
                    val r = try { scanUinFields(e, 0, budget) } catch (_: Throwable) { null }
                    if (r != null) return r
                }
            } else {
                val r = try { scanUinFields(arg, 0, budget) } catch (_: Throwable) { null }
                if (r != null) return r
            }
        }
        return null
    }

    /**
     * uin 仍取不到时，把联系人对象与各参数的「全部字段名:类型」打一次日志，
     * 供下一轮直接精确定位（每进程仅一次）。
     */
    private fun logUinDiag(args: Array<Any>, contactObj: Any) {
        if (diagDone) return
        diagDone = true
        try {
            fun dumpFields(clazz: Class<*>, maxDepth: Int, cap: Int): String {
                val out = mutableListOf<String>()
                var c: Class<*>? = clazz
                var d = 0
                while (c != null && c != Any::class.java && d < maxDepth && out.size < cap) {
                    for (f in c.declaredFields) {
                        if (out.size >= cap) break
                        out.add("${f.name}:${f.type.simpleName}")
                    }
                    c = c.superclass
                    d++
                }
                return out.joinToString(",")
            }
            val sb = StringBuilder("[NekoRewrite] 🔍 uin 提取失败诊断 v2 contactObj=")
                .append(contactObj.javaClass.name)
                .append(" fields=[").append(dumpFields(contactObj.javaClass, 4, 40)).append("]")
            for ((i, a) in args.withIndex()) {
                if (i > 6) break
                sb.append(" | arg$i=")
                if (a == null) sb.append("null")
                else {
                    sb.append(a.javaClass.name)
                    if (a !is Number && a !is String && a !is Boolean) {
                        val fd = try { dumpFields(a.javaClass, 1, 24) } catch (_: Throwable) { "?" }
                        sb.append(":[").append(fd).append("]")
                    }
                }
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
