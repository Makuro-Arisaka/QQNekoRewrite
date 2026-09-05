package com.neko.rewrite

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

/**
 * LSP 状态心跳：QQ 进程里的模块定期向模块 App 发广播，
 * 概览页以「最近一次心跳时间」判断模块是否真的在 QQ 中生效。
 *
 * 为什么用广播而不是文件：QQ 私有目录 0700 且 SELinux 隔离，
 * 跨 UID 文件读取（createPackageContext + openFileInput）永远失败
 * （v0.96~v0.102 三个版本实锤）；应用间广播无需任何权限、不受目录权限限制。
 *
 * 心跳同时写入模块侧 SP：App 关着的时候心跳来了没人收没关系，
 * 下次打开概览页还能看到 10 分钟内的最近一次心跳。
 */
object LspStatus {

    const val ACTION_HEARTBEAT = "com.neko.rewrite.LSP_HEARTBEAT"
    const val EXTRA_PROC = "proc"
    const val EXTRA_TS = "ts"
    const val EXTRA_ENABLED = "enabled"

    private const val MODULE_PACKAGE = "com.neko.rewrite"

    /** 心跳新鲜窗口：QQ 退后台被冻结时心跳会停，放宽到 10 分钟避免误报 */
    private const val FRESH_WINDOW_MS = 10 * 60_000L
    private const val KEY_LAST_SEEN = "lsp_last_seen"
    private const val KEY_PROC = "lsp_proc"

    @Volatile
    private var lastSeenMem = 0L

    @Volatile
    private var procMem: String? = null

    /** QQ 进程调用：发一条心跳广播（仅主进程，启动即发 + 每 60 秒一次） */
    fun send(context: Context, processName: String) {
        try {
            context.sendBroadcast(
                Intent(ACTION_HEARTBEAT)
                    .setPackage(MODULE_PACKAGE)
                    .putExtra(EXTRA_PROC, processName)
                    .putExtra(EXTRA_TS, System.currentTimeMillis())
                    .putExtra(EXTRA_ENABLED, ConfigManager.config.enabled)
            )
        } catch (_: Throwable) { }
    }

    /** 模块进程调用：接收心跳，写内存 + SP */
    fun onHeartbeat(prefs: SharedPreferences, proc: String?, ts: Long) {
        if (ts <= 0L) return
        lastSeenMem = ts
        procMem = proc ?: "?"
        prefs.edit().putLong(KEY_LAST_SEEN, ts).putString(KEY_PROC, procMem).apply()
    }

    /** 最近一次心跳 (时间戳, 进程名)；从无记录返回 null */
    fun lastHeartbeat(prefs: SharedPreferences): Pair<Long, String>? {
        val ts = maxOf(lastSeenMem, prefs.getLong(KEY_LAST_SEEN, 0L))
        if (ts <= 0L) return null
        return ts to (procMem ?: prefs.getString(KEY_PROC, "?") ?: "?")
    }

    /** 心跳是否仍新鲜（未超出 [FRESH_WINDOW_MS]） */
    fun isFresh(ts: Long): Boolean = System.currentTimeMillis() - ts < FRESH_WINDOW_MS
}
