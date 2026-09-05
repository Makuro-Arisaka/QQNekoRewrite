package com.neko.rewrite

import android.content.Context
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * peerUid ↔ QQ号 持久映射表
 *
 * 背景：QQ 9.1.35 发送链路上的 kernel Contact 对象只有 [chatType, guildId, peerUid]
 * 三个字段（诊断 v2 实锤），运行时扫描拿不到对方 QQ 号。而「收到的消息」
 * MsgRecord 上一定带 senderUin（String）且 senderUid == peerUid（私聊收到对方消息时），
 * 部分版本还有 peerUin。因此在收信侧 Hook 监听器回调，持续建立映射并落盘，
 * 发送侧 Hook 用 peerUid 查表即可还原 QQ 号。
 *
 * 落盘位置：QQ 自身 files 目录（模块代码运行在 QQ 进程内，有完全读写权），
 * 文件 neko_uid_map.json，格式 {"<uid>": "<uin>", ...}。
 *
 * 群聊无需映射：QQNT 里群聊 Contact.peerUid 本身就是群号字符串。
 */
object UidMap {

    private const val MAP_FILE = "neko_uid_map.json"
    private const val MAX_ENTRIES = 3000

    private val map = ConcurrentHashMap<String, String>()

    @Volatile
    private var file: File? = null

    /** 已 Hook 过的监听器类，防止 hookAllMethods 重复注册导致回调翻倍 */
    private val hookedClasses = HashSet<String>()

    /** 在 Application.onCreate 时调用（QQ 进程内） */
    fun init(context: Context) {
        try {
            file = File(context.filesDir, MAP_FILE)
            load()
            LogRecorder.msg("UidMap", "已加载映射表 ${map.size} 条")
        } catch (t: Throwable) {
            XposedBridge.log("[NekoRewrite] UidMap init 失败: ${t.message}")
        }
    }

    /** 在 handleLoadPackage 时调用：Hook 监听器注册点，抓到监听器实例后 Hook 其收信回调 */
    fun install(classLoader: ClassLoader) {
        try {
            val svc = XposedHelpers.findClass(
                "com.tencent.qqnt.kernel.api.impl.KernelMsgServiceImpl", classLoader)
            XposedBridge.hookAllMethods(svc, "addKernelMsgListener", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val listener = param.args.firstOrNull() ?: return
                    hookListener(listener)
                }
            })
            XposedBridge.log("[NekoRewrite] 🗺️ UidMap: addKernelMsgListener Hook 已注册")
        } catch (t: Throwable) {
            XposedBridge.log("[NekoRewrite] ⚠️ UidMap: KernelMsgServiceImpl 不可用: ${t.message}")
        }
    }

    private fun hookListener(listener: Any) {
        val cls = listener.javaClass
        val first = synchronized(hookedClasses) { hookedClasses.add(cls.name) }
        if (!first) return

        for (name in listOf("onRecvMsg", "onRecvActiveMsg", "onRecvGroupSvrMsg")) {
            try {
                XposedBridge.hookAllMethods(cls, name, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        for (a in param.args) feedAll(a)
                    }
                })
            } catch (_: Throwable) { }
        }
        XposedBridge.log("[NekoRewrite] 🗺️ UidMap: 收信监听已挂钩 ${cls.name}")
    }

    private fun feedAll(arg: Any?) {
        when (arg) {
            is Collection<*> -> for (e in arg) if (e != null) feedRecord(e)
            is Array<*> -> for (e in arg) if (e != null) feedRecord(e)
            else -> if (arg != null) feedRecord(arg)
        }
    }

    /**
     * 从一条 MsgRecord 提取映射。仅私聊（peerUid 以 u_ 开头）需要：
     * - 优先 peerUin 字段（部分版本存在，String 或数值）
     * - 否则当 senderUid == peerUid（消息由对方发来）时用 senderUin
     *   （避免把自己发的消息里 senderUin=本人 误当对方 QQ 号）
     */
    fun feedRecord(rec: Any) {
        try {
            val uid = getStringField(rec, "peerUid") ?: return
            if (!uid.startsWith("u_")) return

            var uin = getStringField(rec, "peerUin") ?: getNumField(rec, "peerUin")
            if (uin == null) {
                val senderUid = getStringField(rec, "senderUid")
                val senderUin = getStringField(rec, "senderUin") ?: getNumField(rec, "senderUin")
                if (senderUid == uid && senderUin != null) uin = senderUin
            }
            if (uin != null && uin.length in 5..12 && uin.all { it.isDigit() }) {
                put(uid, uin)
            }
        } catch (_: Throwable) { }
    }

    fun get(uid: String): String? = map[uid]

    fun size(): Int = map.size

    private fun put(uid: String, uin: String) {
        if (map.put(uid, uin) == uin) return  // 已存在且相同，无需落盘
        if (map.size > MAX_ENTRIES) { map.remove(uid); return }
        save()
    }

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        try {
            val obj = JSONObject(f.readText())
            for (key in obj.keys()) {
                val v = obj.optString(key, "")
                if (v.isNotEmpty()) map[key] = v
            }
        } catch (t: Throwable) {
            XposedBridge.log("[NekoRewrite] ⚠️ UidMap 读取失败: ${t.message}")
        }
    }

    private fun save() {
        val f = file ?: return
        try {
            synchronized(this) {
                val obj = JSONObject()
                for ((k, v) in map) obj.put(k, v)
                val tmp = File(f.parentFile, "$MAP_FILE.tmp")
                tmp.writeText(obj.toString())
                if (!tmp.renameTo(f)) {
                    f.writeText(obj.toString())
                    tmp.delete()
                }
            }
        } catch (_: Throwable) { }
    }

    private fun getStringField(obj: Any, name: String): String? {
        return try { XposedHelpers.getObjectField(obj, name) as? String } catch (_: Throwable) { null }
    }

    private fun getNumField(obj: Any, name: String): String? {
        return try {
            (XposedHelpers.getObjectField(obj, name) as? Number)
                ?.takeIf { it.toLong() >= 10000 }?.toString()
        } catch (_: Throwable) { null }
    }
}
