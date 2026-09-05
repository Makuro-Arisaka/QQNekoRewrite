package com.neko.rewrite

import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/**
 * 日志记录器 —— 输出到 Android Logcat + 内存缓冲。
 *
 * 说明：运行日志查看页已移除，本类不再落盘写文件。
 * 诊断信息通过 Logcat 输出（Xposed 框架 / Logcat 可捕获）。
 * 「模块是否已在 QQ 中生效」的检测改由 [LspStatus] 心跳广播承担
 * （跨 UID 文件读取被 0700 目录与 SELinux 双重阻断，实测不可行）。
 */
object LogRecorder {

    private const val TAG = "NekoRewrite"
    private const val MAX_BUFFER = 500

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val logBuffer = LinkedList<String>()

    @Synchronized
    fun log(tag: String, message: String, level: Level = Level.INFO) {
        val timestamp = dateFormat.format(Date())
        val line = "$timestamp ${level.emoji} [$tag] $message"
        // Android Logcat（Xposed 框架可捕获，便于无 App 界面时排查）
        Log.println(level.priority, TAG, "[$tag] $message")
        // 内存缓冲（保留最近若干条，供需要时 dump）
        logBuffer.add(line)
        while (logBuffer.size > MAX_BUFFER) logBuffer.removeFirst()
    }

    fun info(tag: String, msg: String) = log(tag, msg, Level.INFO)
    fun success(tag: String, msg: String) = log(tag, msg, Level.SUCCESS)
    fun warn(tag: String, msg: String) = log(tag, msg, Level.WARN)
    fun error(tag: String, msg: String) = log(tag, msg, Level.ERROR)
    fun debug(tag: String, msg: String) = log(tag, msg, Level.DEBUG)
    fun hook(tag: String, msg: String) = log(tag, msg, Level.HOOK)
    fun ai(tag: String, msg: String) = log(tag, msg, Level.AI)
    fun msg(tag: String, msg: String) = log(tag, msg, Level.MSG)

    enum class Level(val emoji: String, val priority: Int) {
        INFO("ℹ️", Log.INFO),
        SUCCESS("✅", Log.INFO),
        WARN("⚠️", Log.WARN),
        ERROR("❌", Log.ERROR),
        DEBUG("🔍", Log.DEBUG),
        HOOK("🪝", Log.INFO),
        AI("🤖", Log.INFO),
        MSG("💬", Log.INFO)
    }
}
