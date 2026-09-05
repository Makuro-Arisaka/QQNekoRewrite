package com.neko.rewrite

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.neko.rewrite.model.ModuleConfig
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 配置管理器
 *
 * ## 跨进程同步模型
 *
 * 模块有两个进程，各自持有一份配置副本：
 *
 * | 进程 | 存储位置 | 谁写入 | 生命周期 |
 * |------|---------|--------|---------|
 * | 模块 App (com.neko.rewrite) | 自己的 shared_prefs + files/neko_config.json | 设置页 | 持久 |
 * | QQ (com.tencent.mobileqq)   | 自己的 shared_prefs | 广播接收器 | 持久 |
 *
 * 两者位于不同的 /data/data 目录，互不可见。因此读取时**同时探测多个来源**，
 * 用 [ModuleConfig.lastUpdated] 时间戳仲裁，取最新的一份：
 *
 * 1. QQ 进程 SP —— 广播热更新的结果，QQ 运行时最及时
 * 2. XSharedPreferences —— 直接读模块 APK 的 SP 文件，QQ 重启后仍能拿到
 * 3. 模块 filesDir 的 JSON 快照 —— XSharedPreferences 被 SELinux 拦截时的兜底
 *
 * 三条通道中任一可用，配置就不会丢。
 */
object ConfigManager {

    const val PACKAGE_NAME = "com.neko.rewrite"
    const val PREFS_NAME = "neko_rewrite_config"
    private const val CONFIG_FILE = "neko_config.json"
    private const val KEY_LAST_UPDATED = "last_updated"

    const val DEFAULT_ENDPOINT = "https://api.deepseek.com/v1/chat/completions"
    const val DEFAULT_MODEL = "deepseek-chat"

    private const val TAG = "ConfigManager"
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /** 配置来源，用于日志与设置页诊断 */
    enum class Source(val label: String) {
        DEFAULT("默认配置（未保存过）"),
        QQ_PREFS("QQ 进程 SP · 广播同步"),
        MODULE_PREFS("模块 SP · 跨进程直读"),
        MODULE_JSON("模块 JSON · 跨进程兜底")
    }

    /** 一次成功的配置读取结果 */
    private data class Snapshot(
        val updated: Long,
        val source: Source,
        val config: ModuleConfig
    )

    @Volatile
    var config: ModuleConfig = ModuleConfig(apiEndpoint = DEFAULT_ENDPOINT, model = DEFAULT_MODEL)
        private set

    /** 当前生效配置的来源 */
    @Volatile
    var source: Source = Source.DEFAULT
        private set

    /** API Key 是否有效（非空） */
    val hasValidApiKey: Boolean
        get() = config.apiKey.isNotBlank()

    // region 读取

    /**
     * QQ 进程初始化：探测所有配置来源，取时间戳最新的一份
     */
    fun init(qqContext: Context) {
        val candidates = ArrayList<Snapshot>()
        val problems = ArrayList<String>()

        val localSnapshot = readPrefs(
            qqContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            Source.QQ_PREFS
        )
        if (localSnapshot != null) candidates.add(localSnapshot)

        val remoteSnapshot = readXSharedPreferences()
        if (remoteSnapshot != null) candidates.add(remoteSnapshot)
        else problems.add("XSharedPreferences 不可用")

        val jsonSnapshot = readModuleJson(qqContext)
        if (jsonSnapshot != null) candidates.add(jsonSnapshot)
        else problems.add("JSON 快照不可用")

        // 旧版本保存的配置没有时间戳，给最小值 1 以保持可用但优先级最低
        val winner = candidates.filter { it.updated > 0 }.maxByOrNull { it.updated }

        if (winner == null) {
            source = Source.DEFAULT
            XposedBridge.log("[NekoRewrite] ⚠️ 未找到已保存的配置，使用默认值 ($DEFAULT_MODEL)")
            if (problems.isNotEmpty()) {
                XposedBridge.log("[NekoRewrite] ↳ 只读通道: ${problems.joinToString("; ")}")
            }
        } else {
            config = winner.config
            source = winner.source
            XposedBridge.log("[NekoRewrite] 📄 配置来源: ${source.label} (ts=${winner.updated})")
            XposedBridge.log("[NekoRewrite] ⚙️ 生效配置: 启用=${config.enabled}, 模型=${config.model}, Key=${if (hasValidApiKey) "已设置" else "未设置"}")
        }
    }

    fun reload(context: Context) = init(context)

    /**
     * 模块进程内读取自己 SP 中的完整配置。
     * 用于不经过 UI 构造完整广播（例如日志页切换「启用日志」时，
     * 需要把当前已保存的真实配置连同 logEnabled 一起发给 QQ，
     * 不能从内存里的默认 config 重建，否则会清掉用户已填的 API Key）。
     */
    fun readLocalConfig(context: Context): ModuleConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return readPrefs(prefs, Source.MODULE_PREFS)?.config ?: config
    }

    /**
     * 从 SharedPreferences 读取配置。
     * XSharedPreferences 与普通 SharedPreferences 都实现该接口，可复用同一套解析逻辑。
     */
    private fun readPrefs(prefs: SharedPreferences, from: Source): Snapshot? {
        val hasAnyKey = prefs.contains("api_key") || prefs.contains(KEY_LAST_UPDATED)
        if (!hasAnyKey) return null

        return try {
            val stored = prefs.getLong(KEY_LAST_UPDATED, 0L)
            val apiKey = prefs.getString("api_key", "") ?: ""
            // 无时间戳但有 Key ⇒ 旧版本配置，标记为最低优先级而非丢弃
            val updated = when {
                stored > 0L -> stored
                apiKey.isNotBlank() -> 1L
                else -> 0L
            }
            if (updated == 0L) return null

            Snapshot(
                updated = updated,
                source = from,
                config = ModuleConfig(
                    enabled = prefs.getBoolean("enabled", true),
                    apiEndpoint = prefs.getString("api_endpoint", DEFAULT_ENDPOINT) ?: DEFAULT_ENDPOINT,
                    model = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL,
                    provider = prefs.getString("provider", "DeepSeek (深度求索)") ?: "DeepSeek (深度求索)",
                    apiKey = apiKey,
                    maxTokens = prefs.getInt("max_tokens", 500),
                    timeoutSeconds = prefs.getInt("timeout_seconds", 10),
                    temperature = prefs.getFloat("temperature", 0.8f),
                    showToast = prefs.getBoolean("show_toast", true),
                    showStartupToast = prefs.getBoolean("show_startup_toast", false),
                    quickToggle = prefs.getBoolean("quick_toggle", false),
                    logEnabled = prefs.getBoolean("log_enabled", false),
                    asyncRewrite = prefs.getBoolean("async_rewrite", true),
                    rewriteTimeoutMs = prefs.getInt("rewrite_timeout_ms", 8000),
                    filterMode = prefs.getInt("filter_mode", 0),
                    whitelist = prefs.getStringSet("whitelist", emptySet())?.toSet() ?: emptySet(),
                    blacklist = prefs.getStringSet("blacklist", emptySet())?.toSet() ?: emptySet(),
                    systemPrompt = prefs.getString("system_prompt", PromptManager.DEFAULT_PROMPT)
                        ?: PromptManager.DEFAULT_PROMPT,
                    lastUpdated = updated
                )
            )
        } catch (e: Exception) {
            XposedBridge.log("[NekoRewrite] ❌ 配置解析失败 (${from.label}): ${e.message}")
            null
        }
    }

    /**
     * 通道 2：通过 XSharedPreferences 直接读模块 APK 的 SP 文件。
     * 这是 QQ 重启后仍能拿到配置的关键 —— 不依赖模块进程是否运行。
     *
     * 注意：该类来自 compileOnly 的 Xposed API，只在被 Hook 的 QQ 进程中存在，
     * 因此整体包在 try/catch(Throwable) 内，模块自身进程不会触碰此方法。
     */
    private fun readXSharedPreferences(): Snapshot? {
        return try {
            val xsp = XSharedPreferences(PACKAGE_NAME, PREFS_NAME)
            if (!xsp.file.exists()) {
                XposedBridge.log("[NekoRewrite] ⚠️ XSP: 模块 SP 文件不存在")
                return null
            }
            xsp.reload()
            val snapshot = readPrefs(xsp, Source.MODULE_PREFS)
            if (snapshot == null) {
                XposedBridge.log("[NekoRewrite] ⚠️ XSP: 文件可读但无有效配置")
            }
            snapshot
        } catch (t: Throwable) {
            // NoClassDefFoundError / SecurityException / SELinux 拒绝 都落到这里
            XposedBridge.log("[NekoRewrite] ⚠️ XSP 不可用 (${t.javaClass.simpleName}): ${t.message}")
            null
        }
    }

    /**
     * 通道 3：读模块 filesDir 下的 JSON 快照。
     * 与通道 2 内容相同、介质不同，用于 XSharedPreferences 被权限拦截时兜底。
     */
    private fun readModuleJson(qqContext: Context): Snapshot? {
        return try {
            val moduleContext = qqContext.createPackageContext(
                PACKAGE_NAME,
                Context.CONTEXT_IGNORE_SECURITY
            )
            val file = File(moduleContext.filesDir, CONFIG_FILE)
            if (!file.exists()) return null

            val text = file.readText()
            if (text.isBlank()) return null

            val parsed = json.decodeFromString<ModuleConfig>(text)
            val updated = if (parsed.lastUpdated > 0) parsed.lastUpdated else 1L
            Snapshot(updated, Source.MODULE_JSON, parsed.copy(lastUpdated = updated))
        } catch (e: Exception) {
            null
        }
    }

    // endregion

    // region 写入

    /**
     * 设置页保存配置（在模块 App 进程调用）。
     *
     * 同时写入三条通道并加盖时间戳，保证 QQ 无论走哪条路径都能读到最新值：
     * - 模块 SP（配 XSharedPreferences 读取）
     * - 模块 JSON 快照（配 createPackageContext 读取）
     * - 广播（配 QQ 进程 SP，实现运行时立即生效）
     */
    fun saveFromSettings(context: Context, newConfig: ModuleConfig): Long {
        val stamped = newConfig.copy(lastUpdated = System.currentTimeMillis())
        config = stamped

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        applyTo(prefs.edit(), stamped).commit() // commit: 同步落盘，确保后续 chmod 时文件已存在

        writeJsonSnapshot(context, stamped)
        relaxFilePermissions(context)

        Log.i(TAG, "配置已保存 ts=${stamped.lastUpdated} 模型=${stamped.model}")
        return stamped.lastUpdated
    }

    /** 把配置字段写入 SharedPreferences（模块进程与 QQ 进程共用） */
    fun applyTo(editor: SharedPreferences.Editor, newConfig: ModuleConfig): SharedPreferences.Editor {
        return editor
            .putBoolean("enabled", newConfig.enabled)
            .putString("api_endpoint", newConfig.apiEndpoint)
            .putString("api_key", newConfig.apiKey)
            .putString("model", newConfig.model)
            .putString("provider", newConfig.provider)
            .putInt("timeout_seconds", newConfig.timeoutSeconds)
            .putFloat("temperature", newConfig.temperature)
            .putInt("max_tokens", newConfig.maxTokens)
            .putBoolean("show_toast", newConfig.showToast)
            .putBoolean("show_startup_toast", newConfig.showStartupToast)
            .putBoolean("quick_toggle", newConfig.quickToggle)
            .putBoolean("log_enabled", newConfig.logEnabled)
            .putBoolean("async_rewrite", newConfig.asyncRewrite)
            .putInt("rewrite_timeout_ms", newConfig.rewriteTimeoutMs)
            .putInt("filter_mode", newConfig.filterMode)
            .putStringSet("whitelist", newConfig.whitelist)
            .putStringSet("blacklist", newConfig.blacklist)
            .putString("system_prompt", newConfig.systemPrompt)
            .putLong(KEY_LAST_UPDATED, newConfig.lastUpdated)
    }

    /**
     * QQ 进程收到广播后调用：写入 QQ 侧 SP。
     * 时间戳沿用广播带来的值，保证与模块侧一致。
     */
    fun applyBroadcast(context: Context, incoming: ModuleConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        applyTo(prefs.edit(), incoming).commit()
        config = incoming
        XposedBridge.log("[NekoRewrite] 📡 配置已热更新 (ts=${incoming.lastUpdated}) 模型=${incoming.model}")
    }

    private fun writeJsonSnapshot(context: Context, newConfig: ModuleConfig) {
        runCatching {
            File(context.filesDir, CONFIG_FILE).writeText(json.encodeToString(newConfig))
        }.onFailure {
            Log.w(TAG, "JSON 快照写入失败: ${it.message}")
        }
    }

    /**
     * 尽力放开文件权限，让 QQ 进程能跨进程读到配置。
     * LSPosed 通常会代为修复权限，这里做一次自力更生的尝试以提高成功率；
     * 即便失败，广播通道仍然可用。
     */
    private fun relaxFilePermissions(context: Context) {
        runCatching {
            val prefsDir = File(context.filesDir.parentFile, "shared_prefs")
            prefsDir.setReadable(true, false)
            prefsDir.setExecutable(true, false)
            File(prefsDir, "$PREFS_NAME.xml").setReadable(true, false)
        }
        runCatching {
            context.filesDir.setReadable(true, false)
            context.filesDir.setExecutable(true, false)
            File(context.filesDir, CONFIG_FILE).setReadable(true, false)
        }
    }

    // endregion
}
