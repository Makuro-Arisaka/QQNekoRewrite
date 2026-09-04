package com.neko.rewrite

import android.util.Log
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * 日志记录器 — 同时写入 Android Logcat + XposedBridge + 文件
 */
object LogRecorder {

    private const val TAG = "NekoRewrite"
    private const val LOG_FILE = "neko_rewrite.log"
    private const val MAX_BUFFER = 500

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val logBuffer = LinkedList<String>()

    @Volatile var isReady: Boolean = false
        private set

    @Volatile
    private var logFile: File? = null

    /**
     * 从 QQ 进程初始化（通过 createPackageContext 访问模块目录）
     */
    fun initFromQqContext(qqContext: android.content.Context) {
        try {
            // 尝试访问模块自身目录
            val moduleContext = qqContext.createPackageContext(
                "com.neko.rewrite",
                android.content.Context.CONTEXT_IGNORE_SECURITY
            )
            logFile = File(moduleContext.filesDir, LOG_FILE)
            isReady = true
            log("LogRecorder", "日志系统就绪 (模块目录)", Level.INFO)
        } catch (e: Exception) {
            Log.w(TAG, "createPackageContext failed: ${e.message}, falling back to QQ dir")
            try {
                // 降级：写入 QQ 的 filesDir
                logFile = File(qqContext.filesDir, LOG_FILE)
                isReady = true
                log("LogRecorder", "日志系统就绪 (QQ 目录降级)", Level.WARN)
            } catch (e2: Exception) {
                Log.e(TAG, "Cannot init log file: ${e2.message}")
                isReady = false
            }
        }
    }

    /**
     * 从模块自身进程初始化（设置页面调用）
     */
    fun init(context: android.content.Context) {
        logFile = File(context.filesDir, LOG_FILE)
        isReady = true
    }

    @Synchronized
    fun log(tag: String, message: String, level: Level = Level.INFO) {
        val timestamp = dateFormat.format(Date())
        val line = "$timestamp ${level.emoji} [$tag] $message"

        // Android Logcat
        Log.println(level.priority, TAG, "[$tag] $message")

        // 内存缓冲
        logBuffer.add(line)
        while (logBuffer.size > MAX_BUFFER) logBuffer.removeFirst()

        // 文件写入
        try {
            logFile?.appendText(line + "\n")
        } catch (_: Exception) { }
    }

    fun info(tag: String, msg: String) = log(tag, msg, Level.INFO)
    fun success(tag: String, msg: String) = log(tag, msg, Level.SUCCESS)
    fun warn(tag: String, msg: String) = log(tag, msg, Level.WARN)
    fun error(tag: String, msg: String) = log(tag, msg, Level.ERROR)
    fun debug(tag: String, msg: String) = log(tag, msg, Level.DEBUG)
    fun hook(tag: String, msg: String) = log(tag, msg, Level.HOOK)
    fun ai(tag: String, msg: String) = log(tag, msg, Level.AI)
    fun msg(tag: String, msg: String) = log(tag, msg, Level.MSG)

    @Synchronized
    fun getRecentLogs(count: Int = 200): List<String> = logBuffer.takeLast(count)

    fun readLogFile(): String = try {
        logFile?.readText() ?: "日志文件未初始化"
    } catch (e: Exception) { "读取失败: ${e.message}" }

    fun exportTo(dest: File): Boolean = try {
        logFile?.copyTo(dest, overwrite = true); true
    } catch (e: Exception) { false }

    @Synchronized
    fun clear() {
        logBuffer.clear()
        try { logFile?.writeText("") } catch (_: Exception) { }
    }

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