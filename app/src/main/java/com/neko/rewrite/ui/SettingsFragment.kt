package com.neko.rewrite.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import com.neko.rewrite.ApiProbe
import com.neko.rewrite.ConfigBroadcast
import com.neko.rewrite.ConfigManager
import com.neko.rewrite.PromptManager
import com.neko.rewrite.ProviderPresets
import com.neko.rewrite.QuickTileService
import com.neko.rewrite.R
import com.neko.rewrite.model.ModuleConfig

class SettingsFragment : Fragment() {

    companion object {
        const val TAG = "SettingsFragment"
    }

    private lateinit var switchEnabled: MaterialSwitch
    private lateinit var switchShowToast: MaterialSwitch
    private lateinit var switchStartupToast: MaterialSwitch
    private lateinit var switchAsyncRewrite: MaterialSwitch
    private lateinit var editRewriteTimeout: EditText
    private lateinit var editApiKey: EditText
    private lateinit var spinnerProvider: MaterialAutoCompleteTextView
    private lateinit var editEndpoint: EditText
    private lateinit var spinnerModel: MaterialAutoCompleteTextView
    private lateinit var layoutModel: TextInputLayout
    private lateinit var layoutCustomModel: TextInputLayout
    private lateinit var editCustomModel: EditText
    private lateinit var btnCheckConnection: MaterialButton
    private lateinit var btnFetchModels: MaterialButton
    private lateinit var textProbeStatus: TextView
    private lateinit var sliderTemperature: Slider
    private lateinit var textTemperature: TextView
    private lateinit var editMaxTokens: EditText
    private lateinit var editPrompt: EditText
    private lateinit var btnResetPrompt: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var textStatus: TextView

    // 联系人过滤
    private lateinit var toggleFilterMode: MaterialButtonToggleGroup
    private lateinit var containerWhitelist: LinearLayout
    private lateinit var containerBlacklist: LinearLayout
    private lateinit var btnAddWhitelist: MaterialButton
    private lateinit var btnAddBlacklist: MaterialButton

    private var whitelist = mutableSetOf<String>()
    private var blacklist = mutableSetOf<String>()

    private val providers = ProviderPresets.ALL_PROVIDERS
    private var currentProvider: ProviderPresets.Provider = providers[1] // DeepSeek 默认

    private val prefs by lazy { requireActivity().getSharedPreferences(ConfigManager.PREFS_NAME, android.content.Context.MODE_PRIVATE) }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        switchEnabled = view.findViewById(R.id.switch_enabled)
        switchShowToast = view.findViewById(R.id.switch_show_toast)
        switchStartupToast = view.findViewById(R.id.switch_startup_toast)
        switchAsyncRewrite = view.findViewById(R.id.switch_async_rewrite)
        editRewriteTimeout = view.findViewById(R.id.edit_rewrite_timeout)
        editApiKey = view.findViewById(R.id.edit_api_key)
        spinnerProvider = view.findViewById(R.id.spinner_provider)
        editEndpoint = view.findViewById(R.id.edit_endpoint)
        spinnerModel = view.findViewById(R.id.spinner_model)
        layoutModel = view.findViewById(R.id.layout_model)
        layoutCustomModel = view.findViewById(R.id.layout_custom_model)
        editCustomModel = view.findViewById(R.id.edit_custom_model)
        btnCheckConnection = view.findViewById(R.id.btn_check_connection)
        btnFetchModels = view.findViewById(R.id.btn_fetch_models)
        textProbeStatus = view.findViewById(R.id.text_probe_status)
        sliderTemperature = view.findViewById(R.id.slider_temperature)
        textTemperature = view.findViewById(R.id.text_temperature)
        editMaxTokens = view.findViewById(R.id.edit_max_tokens)
        editPrompt = view.findViewById(R.id.edit_prompt)
        btnResetPrompt = view.findViewById(R.id.btn_reset_prompt)
        btnSave = view.findViewById(R.id.btn_save)
        textStatus = view.findViewById(R.id.text_status)

        toggleFilterMode = view.findViewById(R.id.toggle_filter_mode)
        containerWhitelist = view.findViewById(R.id.container_whitelist)
        containerBlacklist = view.findViewById(R.id.container_blacklist)
        btnAddWhitelist = view.findViewById(R.id.btn_add_whitelist)
        btnAddBlacklist = view.findViewById(R.id.btn_add_blacklist)

        setupProviderSpinner()
        loadConfig()
        setupListeners()
    }

    override fun onPause() {
        super.onPause()
        saveConfig()
    }

    /**
     * 底栏用 hide/show 切换 Fragment，切走时不会触发 [onPause]。
     * 这里补一次保存，保证「改完开关立刻切到概览页」时配置已经落盘并广播给 QQ。
     */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) saveConfig(auto = true)
    }

    private fun setupProviderSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, providers.map { it.name })
        spinnerProvider.setAdapter(adapter)
        spinnerProvider.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val provider = providers[position]
            // 初次加载或重选同一项：不覆盖已保存的自定义端点 / 模型
            if (provider.name == currentProvider.name) return@OnItemClickListener
            currentProvider = provider
            // 用户主动切换提供方：自动填充预设端点与默认模型
            editEndpoint.setText(provider.apiEndpoint)
            if (provider.models.isNotEmpty()) {
                layoutModel.visibility = View.VISIBLE
                layoutCustomModel.visibility = View.GONE
                val modelAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, provider.models)
                spinnerModel.setAdapter(modelAdapter)
                spinnerModel.setText(provider.defaultModel, false)
            } else {
                layoutModel.visibility = View.GONE
                layoutCustomModel.visibility = View.VISIBLE
            }
        }
    }

    private fun loadConfig() {
        switchEnabled.isChecked = prefs.getBoolean("enabled", true)
        switchShowToast.isChecked = prefs.getBoolean("show_toast", true)
        switchStartupToast.isChecked = prefs.getBoolean("show_startup_toast", false)
        switchAsyncRewrite.isChecked = prefs.getBoolean("async_rewrite", true)
        editRewriteTimeout.setText(prefs.getInt("rewrite_timeout_ms", 8000).toString())
        editApiKey.setText(prefs.getString("api_key", "") ?: "")

        // 提供方
        val providerName = prefs.getString("provider", "DeepSeek (深度求索)") ?: "DeepSeek (深度求索)"
        val provider = ProviderPresets.findByName(providerName) ?: providers[1]
        currentProvider = provider
        spinnerProvider.setText(provider.name, false)

        // 端点
        editEndpoint.setText(prefs.getString("api_endpoint", ConfigManager.DEFAULT_ENDPOINT) ?: ConfigManager.DEFAULT_ENDPOINT)

        // 模型
        val model = prefs.getString("model", provider.defaultModel) ?: provider.defaultModel
        bindModelView(provider, model)

        val temp = prefs.getFloat("temperature", 0.8f)
        // Slider 有 0.05 步长，需对齐后回填，否则抛 IllegalArgumentException
        sliderTemperature.value = (Math.round(temp * 20f) / 20f).coerceIn(0f, 1f)
        textTemperature.text = String.format("%.1f", temp)

        editMaxTokens.setText(prefs.getInt("max_tokens", 500).toString())
        editPrompt.setText(prefs.getString("system_prompt", PromptManager.DEFAULT_PROMPT) ?: PromptManager.DEFAULT_PROMPT)

        // 联系人过滤
        val mode = prefs.getInt("filter_mode", 0)
        when (mode) {
            1 -> toggleFilterMode.check(R.id.btn_filter_white)
            2 -> toggleFilterMode.check(R.id.btn_filter_black)
            else -> toggleFilterMode.check(R.id.btn_filter_off)
        }
        whitelist = (prefs.getStringSet("whitelist", emptySet()) ?: emptySet()).toMutableSet()
        blacklist = (prefs.getStringSet("blacklist", emptySet()) ?: emptySet()).toMutableSet()
        renderLists()
    }

    /** 根据提供方是否有预设模型列表，决定显示模型下拉或自由输入 */
    private fun bindModelView(provider: ProviderPresets.Provider, model: String) {
        if (provider.models.isNotEmpty()) {
            layoutModel.visibility = View.VISIBLE
            layoutCustomModel.visibility = View.GONE
            val modelAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, provider.models)
            spinnerModel.setAdapter(modelAdapter)
            spinnerModel.setText(model, false)
        } else {
            layoutModel.visibility = View.GONE
            layoutCustomModel.visibility = View.VISIBLE
            editCustomModel.setText(model)
        }
    }

    private fun setupListeners() {
        sliderTemperature.addOnChangeListener { _, value, _ ->
            textTemperature.text = String.format("%.1f", value)
        }

        // 开关类控件：改动即刻落盘 + 广播，切到概览页/通知栏开关都能马上生效
        switchEnabled.setOnCheckedChangeListener { _, _ -> saveConfig(auto = true) }
        switchShowToast.setOnCheckedChangeListener { _, _ -> saveConfig(auto = true) }
        switchStartupToast.setOnCheckedChangeListener { _, _ -> saveConfig(auto = true) }
        switchAsyncRewrite.setOnCheckedChangeListener { _, _ -> saveConfig(auto = true) }
        toggleFilterMode.addOnButtonCheckedListener { _, _, _ -> saveConfig(auto = true) }

        btnResetPrompt.setOnClickListener {
            editPrompt.setText(PromptManager.DEFAULT_PROMPT)
            showStatus("提示词已重置", false)
        }

        btnSave.setOnClickListener { saveConfig() }
        btnAddWhitelist.setOnClickListener { showAddUidDialog(true) }
        btnAddBlacklist.setOnClickListener { showAddUidDialog(false) }
        btnCheckConnection.setOnClickListener { checkConnection() }
        btnFetchModels.setOnClickListener { fetchModels() }
    }

    // ===== 名单管理 =====

    private fun renderLists() {
        renderList(containerWhitelist, whitelist, true)
        renderList(containerBlacklist, blacklist, false)
    }

    private fun renderList(container: LinearLayout, list: MutableSet<String>, isWhite: Boolean) {
        container.removeAllViews()
        if (list.isEmpty()) {
            val emptyText = TextView(requireContext()).apply {
                text = "（空）"
                textSize = 13f
                setTextColor(themeAttrColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(8, 4, 8, 4)
            }
            container.addView(emptyText)
            return
        }
        for (uid in list) {
            container.addView(createListRow(uid, isWhite))
        }
    }

    private fun createListRow(uid: String, isWhite: Boolean): View {
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(8, 4, 8, 4)
        }

        val label = TextView(requireContext()).apply {
            text = uid
            textSize = 14f
            setTextColor(themeAttrColor(com.google.android.material.R.attr.colorOnSurface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val deleteBtn = com.google.android.material.button.MaterialButton(
            requireContext(), null, androidx.appcompat.R.attr.borderlessButtonStyle
        ).apply {
            text = "✕"
            textSize = 12f
            insetTop = 0
            insetBottom = 0
            minHeight = 0
            setTextColor(resources.getColor(R.color.status_error, null))
        }
        deleteBtn.setOnClickListener {
            if (isWhite) whitelist.remove(uid) else blacklist.remove(uid)
            renderLists()
        }

        row.addView(label)
        row.addView(deleteBtn)
        return row
    }

    private fun showAddUidDialog(isWhite: Boolean) {
        val input = EditText(requireContext()).apply {
            hint = "输入联系人 UID（可从日志 peer= 获取）"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }
        AlertDialog.Builder(requireContext())
            .setTitle(if (isWhite) "添加白名单" else "添加黑名单")
            .setView(input)
            .setPositiveButton("添加") { _, _ ->
                val uid = input.text.toString().trim()
                if (uid.isNotEmpty()) {
                    if (isWhite) whitelist.add(uid) else blacklist.add(uid)
                    renderLists()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ===== 保存 =====

    private fun getSelectedModel(): String {
        return if (currentProvider.models.isNotEmpty()) {
            spinnerModel.text.toString().ifEmpty { currentProvider.defaultModel }
        } else {
            editCustomModel.text.toString().trim()
        }
    }

    private fun getFilterMode(): Int {
        return when (toggleFilterMode.checkedButtonId) {
            R.id.btn_filter_white -> 1
            R.id.btn_filter_black -> 2
            else -> 0
        }
    }

    /**
     * @param auto true = 开关变化/切页触发的自动保存：不做填写完整性校验
     *             （否则「没填 Key 时关掉模块」会保存失败，状态丢失），
     *             仅在确实缺关键配置时给一行轻提示。
     */
    private fun saveConfig(auto: Boolean = false) {
        try {
            val temperature = sliderTemperature.value
            val maxTokens = editMaxTokens.text.toString().toIntOrNull() ?: 500
            val provider = currentProvider.name
            val endpoint = editEndpoint.text.toString().trim()
            val model = getSelectedModel()
            val apiKey = editApiKey.text.toString().trim()
            val prompt = editPrompt.text.toString().trim()

            if (!auto) {
                if (apiKey.isEmpty()) {
                    showStatus("请填写 API Key", true)
                    return
                }
                if (endpoint.isEmpty()) {
                    showStatus("请填写 API 端点", true)
                    return
                }
            }

            val filterMode = getFilterMode()
            // 超时限制在 1~60 秒，避免填 0（永不超时）把发送线程拖死
            val rewriteTimeout = editRewriteTimeout.text.toString()
                .toIntOrNull()?.coerceIn(1_000, 60_000) ?: 8_000

            val newConfig = ModuleConfig(
                enabled = switchEnabled.isChecked,
                apiEndpoint = endpoint,
                apiKey = apiKey,
                model = model,
                provider = provider,
                maxTokens = maxTokens,
                temperature = temperature,
                showToast = switchShowToast.isChecked,
                showStartupToast = switchStartupToast.isChecked,
                asyncRewrite = switchAsyncRewrite.isChecked,
                rewriteTimeoutMs = rewriteTimeout,
                filterMode = filterMode,
                whitelist = whitelist.toSet(),
                blacklist = blacklist.toSet(),
                systemPrompt = prompt
            )

            // 写入模块侧两条持久通道（SP + JSON）并加盖时间戳，
            // QQ 重启后即使没有广播也能读到这份配置
            val timestamp = ConfigManager.saveFromSettings(requireActivity(), newConfig)

            // 通过广播把完整配置即时同步给 QQ（含本页未改动的字段，避免覆盖）
            ConfigBroadcast.send(requireActivity(), newConfig, timestamp)

            // 若用户已把「猫娘改写」磁贴添加到下拉菜单，顺手同步一下磁贴状态
            QuickTileService.refresh(requireActivity())

            showStatus(
                if (apiKey.isEmpty()) "配置已保存，但 API Key 未填写 —— 改写不会生效"
                else "配置已保存。QQ 运行中即时生效，否则下次启动自动加载",
                isError = apiKey.isEmpty()
            )
        } catch (e: Exception) {
            showStatus("保存失败: ${e.message}", true)
        }
    }

    private fun showStatus(message: String, isError: Boolean) {
        // 自动保存可能发生在视图销毁后（切页/退后台），此时没有可写的状态栏
        if (!::textStatus.isInitialized || view == null) return
        textStatus.text = message
        textStatus.setTextColor(
            if (isError) resources.getColor(R.color.status_error, null)
            else resources.getColor(R.color.status_ok, null)
        )
    }

    // ===== 检查连接 / 获取可用模型 =====

    private var probing = false

    /** 当前输入的 API 端点 */
    private fun getEndpointInput(): String = editEndpoint.text.toString().trim()

    /** 当前输入的 API Key */
    private fun getApiKeyInput(): String = editApiKey.text.toString().trim()

    /** 当前选择的模型（预设下拉或自定义输入） */
    private fun getModelInput(): String {
        val provider = currentProvider
        return if (provider.models.isNotEmpty()) {
            spinnerModel.text.toString().ifEmpty { provider.defaultModel }
        } else {
            editCustomModel.text.toString().trim()
        }
    }

    /** 设置探测状态文本（带颜色：成功绿 / 失败红） */
    private fun setProbeStatus(message: String, isError: Boolean) {
        textProbeStatus.text = message
        textProbeStatus.setTextColor(
            if (isError) resources.getColor(R.color.status_error, null)
            else resources.getColor(R.color.status_ok, null)
        )
    }

    /** 后台执行探测，期间禁用按钮防重入 */
    private fun runProbeAsync(block: () -> String, onUi: (String) -> Unit) {
        if (probing) return
        probing = true
        btnCheckConnection.isEnabled = false
        btnFetchModels.isEnabled = false
        textProbeStatus.text = "⏳ 请求中…"

        Thread {
            val result = try {
                block()
            } catch (e: Throwable) {
                "❌ ${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"
            }
            val activity = activity ?: return@Thread
            activity.runOnUiThread {
                probing = false
                btnCheckConnection.isEnabled = true
                btnFetchModels.isEnabled = true
                onUi(result)
            }
        }.apply { isDaemon = true }.start()
    }

    /** 检查连接：发一个最小 chat 请求验证端点 + API Key */
    private fun checkConnection() {
        val endpoint = getEndpointInput()
        val apiKey = getApiKeyInput()
        val model = getModelInput()
        if (endpoint.isEmpty() || apiKey.isEmpty()) {
            setProbeStatus("请先填写 API 端点与 API Key", true)
            return
        }
        runProbeAsync(
            block = { ApiProbe.probe(endpoint, apiKey, model) },
            onUi = { msg -> setProbeStatus(msg, msg.contains("❌") || msg.contains("⏱️") || msg.contains("🌐") || msg.contains("🔒") || msg.contains("⚠️")) }
        )
    }

    /** 获取可用模型：从端点推导 /models 拉取真实列表，成功则填充模型下拉 */
    private fun fetchModels() {
        val endpoint = getEndpointInput()
        val apiKey = getApiKeyInput()
        if (endpoint.isEmpty() || apiKey.isEmpty()) {
            setProbeStatus("请先填写 API 端点与 API Key", true)
            return
        }
        runProbeAsync(
            block = {
                val result = ApiProbe.ModelsResult()
                val ok = ApiProbe.fetchModels(endpoint, apiKey, result)
                if (ok) {
                    // 收集到模型 → 交给 UI 线程填充
                    modelFetchQueue = result.models
                    "✅ 获取到 ${result.models.size} 个可用模型"
                } else {
                    "❌ ${result.error}"
                }
            },
            onUi = { msg ->
                if (msg.startsWith("✅")) {
                    fillModelsIntoSpinner(modelFetchQueue)
                    setProbeStatus(msg, false)
                } else {
                    setProbeStatus(msg, true)
                }
            }
        )
    }

    /** 线程间传递抓取到的模型列表 */
    @Volatile
    private var modelFetchQueue: List<String> = emptyList()

    /**
     * 把抓取到的模型填充进模型控件：
     * 有预设模型下拉则刷新其数据；自定义提供方则切到下拉展示。
     * 尽量保留当前已输入的模型（若在列表中）。
     */
    private fun fillModelsIntoSpinner(models: List<String>) {
        if (models.isEmpty()) return
        val activity = activity ?: return

        // 当前模型输入（用于回填高亮）
        val currentModel = getModelInput()

        // 切到下拉视图
        layoutModel.visibility = View.VISIBLE
        layoutCustomModel.visibility = View.GONE

        val modelAdapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, models)
        spinnerModel.setAdapter(modelAdapter)

        // 若当前输入正好在列表里，选中它；否则默认选第一个
        val idx = if (currentModel.isNotEmpty()) models.indexOf(currentModel) else -1
        spinnerModel.setText(models[if (idx >= 0) idx else 0], false)
    }

    /** 解析当前主题（含 Android 12+ Material You 动态取色）下的一个颜色属性 */
    private fun themeAttrColor(attrRes: Int): Int {
        val tv = android.util.TypedValue()
        val ctx = requireContext()
        if (ctx.theme.resolveAttribute(attrRes, tv, true)) {
            if (tv.type in android.util.TypedValue.TYPE_FIRST_COLOR_INT..
                android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data
            }
            if (tv.resourceId != 0) {
                return resources.getColor(tv.resourceId, null)
            }
        }
        return resources.getColor(R.color.md_theme_on_surface_variant, null)
    }
}
