package com.neko.rewrite

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 日志记录器 —— 输出到 Android Logcat + 内存缓冲，并提供「模块是否已在 QQ 中生效」的心跳检测。
 *
 * 说明：运行日志查看页已移除，本类不再落盘写文件（避免无 UI 时产生无人查看的文件）。
 * 诊断信息通过 Logcat 输出（Xposed 框架 / Logcat 可捕获）；
 * QQ 进程通过 [markMounted] 写入心跳文件，供模块 App 的概览页判断「是否已挂载」。
 */
object LogRecorder {

    private const val TAG = "NekoRewrite"
    private const val MOUNT_FILE_NAME = "qq_mounted"
    private const val MAX_BUFFER = 500

    private const val MODULE_PACKAGE = "com.neko.rewrite"
    private const val QQ_PACKAGE = "com.tencent.mobileqq"
    private const val SHARED_DIR_NAME = "NekoRewrite"

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val logBuffer = LinkedList<String>()

    /** 跨进程共享目录下的心跳文件（两个进程指向同一处，优先用于「已挂载」检测） */
    @Volatile
    private var sharedFile: File? = null

    /** 本进程读取心跳时的候选文件（按优先级） */
    private val readCandidates = ArrayList<File>()

    // region 初始化

    /** 模块进程初始化：登记读取候选（用于概览页的「已挂载」检测），不写任何文件 */
    fun init(context: Context) {
        if (readCandidates.isNotEmpty()) return
        prepare(candidatesFor(context))
    }

    /** QQ 进程初始化：登记读取候选（含共享心跳位置） */
    fun initFromQqContext(qqContext: Context) {
        prepare(candidatesFor(qqContext))
    }

    private fun candidatesFor(context: Context): List<File> = buildList {
        sharedExternalFile()?.let { add(it) }
        add(File(context.filesDir, MOUNT_FILE_NAME))
        packageFile(context, if (context.packageName == QQ_PACKAGE) MODULE_PACKAGE else QQ_PACKAGE)?.let { add(it) }
    }

    private fun prepare(candidates: List<File>) {
        readCandidates.clear()
        readCandidates.addAll(candidates)
        sharedFile = candidates.firstOrNull()
    }

    /**
     * 跨进程共享的心跳文件位置。Android 11+ 起公共目录不再允许 File 直写，
     * 未授权时该路径虽可算出，但实际 I/O 会失败 —— 此时心跳退而求其次写本进程私有目录，
     * 由 [lastMounted] 的顺序探测兜底。
     */
    private fun sharedExternalFile(): File? = try {
        val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        File(File(base, SHARED_DIR_NAME), MOUNT_FILE_NAME)
    } catch (_: Exception) {
        null
    }

    /** 另一个包的私有目录下的心跳文件（能否真正访问取决于跨 UID 读取权限） */
    private fun packageFile(context: Context, pkg: String): File? = try {
        val target = if (pkg == context.packageName) context
        else context.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
        File(target.filesDir, MOUNT_FILE_NAME)
    } catch (_: Exception) {
        null
    }

    // endregion

    // region 写入（Logcat + 内存）

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

    // endregion

    // region 挂载心跳

    /**
     * QQ 进程调用：写入「模块已生效」心跳。
     *
     * 同时写共享目录与本进程私有目录：
     * - 共享目录可读（双方都授权）时，模块进程直接读到；
     * - 否则模块进程退而读取 QQ 私有目录里的心跳（需跨 UID 读取权限）。
     */
    fun markMounted(context: Context, processName: String) {
        val content = "ts=${System.currentTimeMillis()}\nproc=$processName\n"
        runCatching {
            sharedFile?.let { File(it.parentFile, MOUNT_FILE_NAME) }?.writeText(content)
        }
        runCatching {
            File(context.filesDir, MOUNT_FILE_NAME).writeText(content)
        }
    }

    /** 模块进程读取心跳；返回 (时间戳, 进程名)，读不到返回 null */
    fun lastMounted(): Pair<Long, String>? {
        for (file in readCandidates) {
            readMount(file)?.let { return it }
        }
        return null
    }

    private fun readMount(file: File): Pair<Long, String>? {
        return try {
            if (!file.exists()) return null
            val map = file.readLines()
                .mapNotNull { line ->
                    val i = line.indexOf('=')
                    if (i > 0) line.substring(0, i) to line.substring(i + 1) else null
                }
                .toMap()
            val ts = map["ts"]?.toLongOrNull() ?: return null
            ts to (map["proc"] ?: "?")
        } catch (_: Exception) {
            null
        }
    }

    // endregion

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
