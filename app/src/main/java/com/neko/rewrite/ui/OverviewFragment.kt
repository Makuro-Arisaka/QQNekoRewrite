package com.neko.rewrite.ui

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
    }

    private lateinit var textLsposedStatus: TextView
    private lateinit var textModuleEnabled: TextView
    private lateinit var textApiEndpoint: TextView
    private lateinit var textApiModel: TextView
    private lateinit var textApiConnectivity: TextView
    private lateinit var textConfigStore: TextView

    private val prefs by lazy { requireActivity().getSharedPreferences(ConfigManager.PREFS_NAME, android.content.Context.MODE_PRIVATE) }
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
        textLsposedStatus = view.findViewById(R.id.text_lsposed_status)
        textModuleEnabled = view.findViewById(R.id.text_module_enabled)
        textApiEndpoint = view.findViewById(R.id.text_api_endpoint)
        textApiModel = view.findViewById(R.id.text_api_model)
        textApiConnectivity = view.findViewById(R.id.text_api_connectivity)
        textConfigStore = view.findViewById(R.id.text_config_store)
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        refreshJob?.cancel()
    }

    private fun refreshAll() {
        refreshJob?.cancel()
        refreshJob = CoroutineScope(Dispatchers.Main).launch {
            refreshLsposedStatus()
            refreshModuleEnabled()
            refreshConfigStore()
            refreshApiConfig()
            val apiOk = withContext(Dispatchers.IO) { testApiConnectivity() }
            updateConnectivityStatus(apiOk)
        }
    }

    private fun refreshLsposedStatus() {
        val hasConfig = prefs.contains("api_key") && (prefs.getString("api_key", "") ?: "").isNotEmpty()
        val hasLog = try {
            val logFile = java.io.File(requireActivity().filesDir, "neko_rewrite.log")
            logFile.exists() && logFile.length() > 0
        } catch (_: Exception) { false }

        if (hasConfig || hasLog) {
            textLsposedStatus.text = "✅ 已挂载"
            textLsposedStatus.setTextColor(resources.getColor(R.color.status_ok, null))
        } else {
            textLsposedStatus.text = "⚠️ 未检测到"
            textLsposedStatus.setTextColor(resources.getColor(R.color.status_warn, null))
        }
    }

    private fun refreshModuleEnabled() {
        val enabled = prefs.getBoolean("enabled", true)
        if (enabled) {
            textModuleEnabled.text = "✅ 已启用"
            textModuleEnabled.setTextColor(resources.getColor(R.color.status_ok, null))
        } else {
            textModuleEnabled.text = "⏸️ 已禁用"
            textModuleEnabled.setTextColor(resources.getColor(R.color.status_warn, null))
        }
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
            timestamp <= 0L -> "⚠️ 未保存" to R.color.status_warn
            hasPrefs && hasJson -> "✅ SP + JSON 双通道" to R.color.status_ok
            hasPrefs -> "✅ 仅 SP" to R.color.status_ok
            hasJson -> "✅ 仅 JSON" to R.color.status_ok
            else -> "⚠️ 未写入文件" to R.color.status_warn
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
            textApiConnectivity.text = "✅ 可达"
            textApiConnectivity.setTextColor(resources.getColor(R.color.status_ok, null))
        } else {
            textApiConnectivity.text = "❌ 不可达"
            textApiConnectivity.setTextColor(resources.getColor(R.color.status_error, null))
        }
    }
}