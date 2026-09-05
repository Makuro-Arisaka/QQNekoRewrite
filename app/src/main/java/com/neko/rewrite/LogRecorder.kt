package com.neko.rewrite

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * 日志记录器 — 同时写入 Android Logcat + 文件（+ 内存缓冲）
 *
 * ## 日志文件为什么以前永远是空的
 *
 * 模块运行在两个进程，而且分属不同的 /data/data 目录：
 *
 * | 进程 | 写模块私有目录 | 写 QQ 私有目录 |
 * |------|---------------|---------------|
 * | 模块 App (com.neko.rewrite) | ✅ | ❌ |
 * | QQ (com.tencent.mobileqq)   | ❌ | ✅ |
 *
 * 旧实现在 QQ 进程里把日志目标设成「模块私有目录」，`appendText` 每次都因权限
 * 失败，却被 `catch (_: Exception) {}` 吞掉，于是 `isReady=true` 而文件从未创建，
 * 日志页只能一直显示「日志文件未创建」。
 *
 * ## 现在的做法
 *
 * 1. 按优先级挑一个**双方都能读写**的位置作为写入目标：
 *    - 共享外部存储 `Documents/NekoRewrite/neko_rewrite.log`
 *      （需要「所有文件访问」权限，MainActivity 启动时引导授予）
 *    - 本进程私有目录（兜底，至少保留本进程日志）
 * 2. 选定前**真实试写一次**（[isWritable]），不再「初始化成功但每条静默失败」。
 * 3. 读取时（[readAll]）按 共享文件 → 模块私有 → QQ 私有 顺序合并，
 *    即便共享目录不可用，也能把各进程私有文件里的日志拼出来。
 */
object LogRecorder {

    private const val TAG = "NekoRewrite"
    const val LOG_FILE_NAME = "neko_rewrite.log"
    private const val MOUNT_FILE_NAME = "qq_mounted"
    private const val MAX_BUFFER = 500
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val TRIM_KEEP_BYTES = 256 * 1024L

    private const val MODULE_PACKAGE = "com.neko.rewrite"
    private const val QQ_PACKAGE = "com.tencent.mobileqq"
    private const val SHARED_DIR_NAME = "NekoRewrite"

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val logBuffer = LinkedList<String>()

    @Volatile
    var isReady: Boolean = false
        private set

    /** 当前进程实际写入的日志文件；null = 没有任何可写位置 */
    @Volatile
    private var writeTarget: File? = null

    /** 跨进程共享的日志文件（两个进程指向同一个文件，优先读/写它） */
    @Volatile
    private var sharedFile: File? = null

    /** 本进程读取日志时的候选文件（按优先级） */
    private val readCandidates = ArrayList<File>()

    /** 当前日志文件路径，供 UI 展示诊断 */
    val logPath: String
        get() = writeTarget?.absolutePath ?: "（无可用写入位置）"

    /** 是否正在把日志写进文件（由「运行日志」页的开关控制） */
    @Volatile
    var fileLoggingEnabled: Boolean = false
        private set

    // region 初始化 / 开关

    /** QQ 进程初始化；@param enabled 是否启用文件日志（来自模块配置） */
    fun initFromQqContext(qqContext: Context, enabled: Boolean) {
        val candidates = buildList {
            sharedExternalFile()?.let { add(it) }
            add(File(qqContext.filesDir, LOG_FILE_NAME))            // QQ 私有目录：一定能写
            packageFile(qqContext, MODULE_PACKAGE)?.let { add(it) } // 旧路径：能写就继续沿用
        }
        prepare(candidates)
        if (enabled) enableFrom(candidates, who = "QQ 进程")
    }

    /** 模块进程初始化：只登记读取候选，不创建任何文件（等用户在日志页开启） */
    fun init(context: Context) {
        if (readCandidates.isNotEmpty()) return
        prepare(moduleCandidates(context))
    }

    /** 权限状态变化后（例如刚授予「所有文件访问」）重新评估写入目标 */
    fun reinit(context: Context) {
        val candidates = moduleCandidates(context)
        val shared = sharedExternalFile()
        val changed = shared != null && writeTarget?.absolutePath != shared.absolutePath
        if (readCandidates.isEmpty() || changed) {
            prepare(candidates)
            if (fileLoggingEnabled || writeTarget == null) {
                // 已开启则重新挑目标；未开启时若之前失败过也再试一次
                if (fileLoggingEnabled) enableFrom(candidates, who = "模块进程")
            }
        }
    }

    /**
     * 开启文件日志（日志页开关调用）：挑一个可写目标并真实试写。
     * @return 是否成功；false = 没有任何可写位置，调用方应保持开关关闭
     */
    fun enable(context: Context): Boolean = enableFrom(moduleCandidates(context), who = "模块进程")

    /** 关闭文件日志：不再落盘，但已经产生的日志文件仍可阅读/导出 */
    fun disable() {
        fileLoggingEnabled = false
        Log.i(TAG, "文件日志已关闭")
    }

    private fun enableFrom(candidates: List<File>, who: String): Boolean {
        val target = candidates.firstOrNull { isWritable(it) }
        writeTarget = target
        isReady = target != null
        fileLoggingEnabled = target != null

        if (target == null) {
            Log.w(TAG, "无法开启文件日志：没有任何可写位置")
            return false
        }
        // 让另一个进程有机会读到本进程私有目录里的日志（best effort）
        relaxPermissions(target)
        log("LogRecorder", "文件日志已开启（$who）: $logPath", Level.SUCCESS)
        return true
    }

    private fun prepare(candidates: List<File>) {
        readCandidates.clear()
        readCandidates.addAll(candidates)
        sharedFile = candidates.firstOrNull()
    }

    private fun moduleCandidates(context: Context): List<File> = buildList {
        sharedExternalFile()?.let { add(it) }
        add(File(context.filesDir, LOG_FILE_NAME))        // 模块私有目录
        packageFile(context, QQ_PACKAGE)?.let { add(it) } // QQ 私有目录（若 QQ 已放开权限）
    }

    /**
     * 共享外部存储上的日志文件 —— 两个进程都能写、都能读，是唯一能跨进程汇总的位置。
     * Android 11+ 起公共目录不再允许 File 直写（除非持有「所有文件访问」），
     * 未授权时返回 null，交给后续候选兜底。
     */
    private fun sharedExternalFile(): File? {
        return try {
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            File(File(base, SHARED_DIR_NAME), LOG_FILE_NAME)
        } catch (_: Exception) {
            null
        }
    }

    /** 另一个包的私有目录下的同名日志文件（能否真正访问取决于对方是否放开权限） */
    private fun packageFile(context: Context, pkg: String): File? = try {
        val target = if (pkg == context.packageName) context
        else context.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
        File(target.filesDir, LOG_FILE_NAME)
    } catch (_: Exception) {
        null
    }

    /** 真实试写：目录可进 + 文件可建 + 文件可写 */
    private fun isWritable(file: File): Boolean {
        return try {
            val dir = file.parentFile ?: return false
            if (!dir.exists() && !dir.mkdirs()) return false
            if (!dir.canWrite()) return false
            if (!file.exists() && !file.createNewFile()) return false
            file.canWrite()
        } catch (_: Exception) {
            false
        }
    }

    private fun relaxPermissions(file: File?) {
        runCatching {
            file?.parentFile?.let { dir ->
                dir.setReadable(true, false)
                dir.setExecutable(true, false)
            }
            file?.setReadable(true, false)
        }
    }

    // endregion

    // region 写入

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
        val file = writeTarget
        if (file == null) {
            Log.w(TAG, "[$tag] 日志无可用写入位置，仅输出到 Logcat: $message")
            return
        }
        try {
            trimIfNeeded(file)
            FileOutputStream(file, true).bufferedWriter(Charsets.UTF_8).use { it.appendLine(line) }
        } catch (e: Exception) {
            Log.w(TAG, "写入日志文件失败 (${e.javaClass.simpleName}): ${e.message}")
        }
    }

    /**
     * QQ 进程调用：写入「模块已生效」心跳。
     *
     * 概览页的「是否已挂载」以前靠「日志文件是否存在」判断，但日志现在由模块进程
     * 自己也会写，那个条件恒为真、失去意义。改用 QQ 侧写入的心跳文件判断，
     * 共享目录不可用时模块读不到它，状态会如实显示「未检测到」而不是误报。
     */
    fun markMounted(context: Context, processName: String) {
        runCatching {
            val file = sharedFile?.let { File(it.parentFile, MOUNT_FILE_NAME) }
                ?: File(context.filesDir, MOUNT_FILE_NAME)
            file.writeText("ts=${System.currentTimeMillis()}\nproc=$processName\n")
        }
    }

    /** 模块进程读取心跳；返回 (时间戳, 进程名)，读不到返回 null */
    fun lastMounted(): Pair<Long, String>? {
        val file = sharedFile?.let { File(it.parentFile, MOUNT_FILE_NAME) } ?: return null
        return try {
            if (!file.exists()) return null
            val map = file.readLines()
                .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
                .toMap()
            val ts = map["ts"]?.toLongOrNull() ?: return null
            ts to (map["proc"] ?: "?")
        } catch (_: Exception) {
            null
        }
    }

    /** 超过体积上限时裁掉前半部分，保留最近的 [TRIM_KEEP_BYTES] */
    private fun trimIfNeeded(file: File) {
        try {
            if (file.length() < MAX_FILE_BYTES) return
            val keep = file.readText().takeLast(TRIM_KEEP_BYTES.toInt())
            file.writeText(keep.substring(keep.indexOf('\n') + 1))
        } catch (_: Exception) {
        }
    }

    fun info(tag: String, msg: String) = log(tag, msg, Level.INFO)
    fun success(tag: String, msg: String) = log(tag, msg, Level.SUCCESS)
    fun warn(tag: String, msg: String) = log(tag, msg, Level.WARN)
    fun error(tag: String, msg: String) = log(tag, msg, Level.ERROR)
    fun debug(tag: String, msg: String) = log(tag, msg, Level.DEBUG)
    fun hook(tag: String, msg: String) = log(tag, msg, Level.HOOK)
    fun ai(tag: String, msg: String) = log(tag, msg, Level.AI)
    fun msg(tag: String, msg: String) = log(tag, msg, Level.MSG)

    // endregion

    // region 读取 / 维护

    @Synchronized
    fun getRecentLogs(count: Int = 200): List<String> = logBuffer.takeLast(count)

    /**
     * 读取可拿到的全部日志：
     * - 共享文件非空 ⇒ 它是唯一写入目标，直接返回（避免与其它来源重复）
     * - 否则合并各进程私有文件里的日志
     */
    @Synchronized
    fun readAll(maxLines: Int = 200): String {
        val shared = sharedFile
        if (shared != null) {
            val text = readTextOrEmpty(shared)
            if (text.isNotBlank()) return tail(text, maxLines)
        }

        val chunks = readCandidates.map { readTextOrEmpty(it) }.filter { it.isNotBlank() }
        if (chunks.isEmpty()) return ""
        return tail(chunks.joinToString("\n"), maxLines)
    }

    @Suppress("unused")
    fun readLogFile(): String = readAll()

    private fun readTextOrEmpty(file: File): String = try {
        if (file.exists() && file.length() > 0) file.readText().trimEnd() else ""
    } catch (_: Exception) {
        ""
    }

    private fun tail(text: String, maxLines: Int): String {
        val lines = text.split("\n")
        return if (lines.size > maxLines) lines.takeLast(maxLines).joinToString("\n") else text
    }

    /** 导出：优先拷贝共享/私有日志文件，都没有时退化为内存缓冲 */
    fun exportTo(dest: File): Boolean {
        val source = (listOfNotNull(sharedFile) + readCandidates)
            .firstOrNull { it.exists() && it.length() > 0 }
        return try {
            if (source != null) {
                source.copyTo(dest, overwrite = true)
            } else {
                if (logBuffer.isEmpty()) return false
                dest.writeText(logBuffer.joinToString("\n"))
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun clear() {
        logBuffer.clear()
        for (file in (listOfNotNull(sharedFile) + readCandidates)) {
            runCatching { if (file.exists()) file.writeText("") }
        }
    }

    // endregion

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
