package com.neko.rewrite.ui

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.neko.rewrite.ConfigManager
import com.neko.rewrite.R
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class OverviewFragment : Fragment() {

    companion object {
        const val TAG = "OverviewFragment"
        const val GITHUB_URL = "https://github.com/Makuro-Arisaka/QQNekoRewrite"
    }

    private lateinit var textModuleEnabled: TextView
    private lateinit var textApiEndpoint: TextView
    private lateinit var textApiModel: TextView
    private lateinit var textApiConnectivity: TextView
    private lateinit var textConfigStore: TextView
    private lateinit var textAppVersion: TextView

    private val prefs by lazy { requireActivity().getSharedPreferences(ConfigManager.PREFS_NAME, android.content.Context.MODE_PRIVATE) }

    /**
     * 监听模块 SP 变化：QS 磁贴（与 App 同进程）点按时会改写 enabled，
     * 必须实时刷新本页的「模块状态」，否则 App 内用磁贴开关后概览页不更新。
     * 用 view?.post 把 UI 更新抛回主线程（磁贴 commit 在 Binder 线程）。
     */
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        view?.post { refreshModuleStatus() }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private var refreshJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_overview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        textModuleEnabled = view.findViewById(R.id.text_module_enabled)
        textApiEndpoint = view.findViewById(R.id.text_api_endpoint)
        textApiModel = view.findViewById(R.id.text_api_model)
        textApiConnectivity = view.findViewById(R.id.text_api_connectivity)
        textConfigStore = view.findViewById(R.id.text_config_store)
        textAppVersion = view.findViewById(R.id.text_app_version)

        textAppVersion.text = try {
            val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            "版本 ${info.versionName} (${info.longVersionCode})"
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            "版本未知"
        }

        view.findViewById<View>(R.id.btn_about_github).setOnClickListener {
            runCatching {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(GITHUB_URL)))
            }
        }

        // 注册 SP 监听，App 内用 QS 磁贴开关时实时刷新
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
    }

    override fun onResume() {
        super.onResume()
        if (view != null) refreshAll()
    }

    /**
     * 底栏用 hide/show 切换 Fragment，切回来不会触发 [onResume]，
     * 必须在这里刷新，否则设置页改完开关后概览仍是旧状态。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) refreshAll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshJob?.cancel()
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    private fun refreshAll() {
        refreshJob?.cancel()
        refreshJob = CoroutineScope(Dispatchers.Main).launch {
            refreshModuleEnabled()
            refreshConfigStore()
            refreshApiConfig()
            val apiOk = withContext(Dispatchers.IO) { testApiConnectivity() }
            updateConnectivityStatus(apiOk)
        }
    }

    private fun refreshModuleEnabled() {
        val enabled = prefs.getBoolean("enabled", true)
        if (enabled) {
            textModuleEnabled.text = "已启用"
            textModuleEnabled.setTextColor(resources.getColor(R.color.status_ok, null))
        } else {
            textModuleEnabled.text = "已禁用"
            textModuleEnabled.setTextColor(resources.getColor(R.color.status_warn, null))
        }
    }

    /** SP 被外部改动（如 QS 磁贴切换）时刷新模块启停状态与配置落盘指示（不含联网探测） */
    private fun refreshModuleStatus() {
        refreshModuleEnabled()
        refreshConfigStore()
    }

    /**
     * 显示配置已落入哪些跨进程读取通道。
     * 只有至少一条通道可用，QQ 重启后才能自动恢复配置。
     */
    private fun refreshConfigStore() {
        val timestamp = prefs.getLong("last_updated", 0L)
        val hasPrefs = File(File(requireActivity().filesDir.parentFile, "shared_prefs"), "${ConfigManager.PREFS_NAME}.xml").exists()
        val hasJson = File(requireActivity().filesDir, "neko_config.json").exists()

        val (label, colorId) = when {
            timestamp <= 0L -> "未保存" to R.color.status_warn
            hasPrefs && hasJson -> "SP + JSON 双通道" to R.color.status_ok
            hasPrefs -> "仅 SP" to R.color.status_ok
            hasJson -> "仅 JSON" to R.color.status_ok
            else -> "未写入文件" to R.color.status_warn
        }
        textConfigStore.text = label
        textConfigStore.setTextColor(resources.getColor(colorId, null))
    }

    private fun refreshApiConfig() {
        val endpoint = prefs.getString("api_endpoint", ConfigManager.DEFAULT_ENDPOINT) ?: ConfigManager.DEFAULT_ENDPOINT
        val model = prefs.getString("model", ConfigManager.DEFAULT_MODEL) ?: ConfigManager.DEFAULT_MODEL
        textApiEndpoint.text = endpoint
        textApiModel.text = model
    }

    private fun testApiConnectivity(): Boolean {
        val endpoint = prefs.getString("api_endpoint", ConfigManager.DEFAULT_ENDPOINT) ?: ConfigManager.DEFAULT_ENDPOINT
        val baseUrl = try {
            val uri = java.net.URI(endpoint)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) { endpoint }

        return try {
            val request = okhttp3.Request.Builder().url(baseUrl).head().build()
            val response = client.newCall(request).execute()
            response.isSuccessful || response.code in 400..499
        } catch (_: Exception) { false }
    }

    private fun updateConnectivityStatus(ok: Boolean) {
        if (ok) {
            textApiConnectivity.text = "可达"
            textApiConnectivity.setTextColor(resources.getColor(R.color.status_ok, null))
        } else {
            textApiConnectivity.text = "不可达"
            textApiConnectivity.setTextColor(resources.getColor(R.color.status_error, null))
        }
    }
}