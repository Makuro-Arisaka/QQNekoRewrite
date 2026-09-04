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
import com.google.android.material.switchmaterial.SwitchMaterial
import com.neko.rewrite.ConfigManager
import com.neko.rewrite.MainHook
import com.neko.rewrite.PromptManager
import com.neko.rewrite.ProviderPresets
import com.neko.rewrite.R
import com.neko.rewrite.model.ModuleConfig

class SettingsFragment : Fragment() {

    companion object {
        const val TAG = "SettingsFragment"
    }

    private lateinit var switchEnabled: SwitchMaterial
    private lateinit var switchShowToast: SwitchMaterial
    private lateinit var switchStartupToast: SwitchMaterial
    private lateinit var switchAsyncRewrite: SwitchMaterial
    private lateinit var editRewriteTimeout: EditText
    private lateinit var editApiKey: EditText
    private lateinit var spinnerProvider: Spinner
    private lateinit var editEndpoint: EditText
    private lateinit var spinnerModel: Spinner
    private lateinit var editCustomModel: EditText
    private lateinit var seekbarTemperature: SeekBar
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
        editCustomModel = view.findViewById(R.id.edit_custom_model)
        seekbarTemperature = view.findViewById(R.id.seekbar_temperature)
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

    private fun setupProviderSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, providers.map { it.name })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerProvider.adapter = adapter
        spinnerProvider.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val provider = providers[position]
                // 初次加载或重选同一项：不覆盖已保存的自定义端点 / 模型
                if (provider.name == currentProvider.name) return
                currentProvider = provider
                // 用户主动切换提供方：自动填充预设端点与默认模型
                editEndpoint.setText(provider.apiEndpoint)
                if (provider.models.isNotEmpty()) {
                    spinnerModel.visibility = View.VISIBLE
                    editCustomModel.visibility = View.GONE
                    val modelAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, provider.models)
                    modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    spinnerModel.adapter = modelAdapter
                    spinnerModel.setSelection(provider.models.indexOf(provider.defaultModel).coerceAtLeast(0))
                } else {
                    spinnerModel.visibility = View.GONE
                    editCustomModel.visibility = View.VISIBLE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
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
        spinnerProvider.setSelection(providers.indexOf(provider).coerceAtLeast(0))

        // 端点
        editEndpoint.setText(prefs.getString("api_endpoint", ConfigManager.DEFAULT_ENDPOINT) ?: ConfigManager.DEFAULT_ENDPOINT)

        // 模型
        val model = prefs.getString("model", provider.defaultModel) ?: provider.defaultModel
        bindModelView(provider, model)

        val temp = prefs.getFloat("temperature", 0.8f)
        seekbarTemperature.progress = (temp * 100).toInt()
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
            spinnerModel.visibility = View.VISIBLE
            editCustomModel.visibility = View.GONE
            val modelAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, provider.models)
            modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerModel.adapter = modelAdapter
            spinnerModel.setSelection(provider.models.indexOf(model).coerceAtLeast(0))
        } else {
            spinnerModel.visibility = View.GONE
            editCustomModel.visibility = View.VISIBLE
            editCustomModel.setText(model)
        }
    }

    private fun setupListeners() {
        seekbarTemperature.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                textTemperature.text = String.format("%.1f", progress / 100f)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnResetPrompt.setOnClickListener {
            editPrompt.setText(PromptManager.DEFAULT_PROMPT)
            showStatus("提示词已重置", false)
        }

        btnSave.setOnClickListener { saveConfig() }
        btnAddWhitelist.setOnClickListener { showAddUidDialog(true) }
        btnAddBlacklist.setOnClickListener { showAddUidDialog(false) }
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
                setTextColor(resources.getColor(R.color.md_theme_on_surface_variant, null))
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
            setTextColor(resources.getColor(R.color.md_theme_on_surface, null))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val deleteBtn = com.google.android.material.button.MaterialButton(requireContext()).apply {
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
            spinnerModel.selectedItem?.toString() ?: currentProvider.defaultModel
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

    private fun saveConfig() {
        try {
            val temperature = seekbarTemperature.progress / 100f
            val maxTokens = editMaxTokens.text.toString().toIntOrNull() ?: 500
            val provider = currentProvider.name
            val endpoint = editEndpoint.text.toString().trim()
            val model = getSelectedModel()
            val apiKey = editApiKey.text.toString().trim()
            val prompt = editPrompt.text.toString().trim()

            if (apiKey.isEmpty()) {
                showStatus("请填写 API Key", true)
                return
            }
            if (endpoint.isEmpty()) {
                showStatus("请填写 API 端点", true)
                return
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

            val intent = Intent(MainHook.ACTION_CONFIG_UPDATE).apply {
                setPackage("com.tencent.mobileqq")
                putExtra(MainHook.EXTRA_API_KEY, apiKey)
                putExtra(MainHook.EXTRA_API_ENDPOINT, endpoint)
                putExtra(MainHook.EXTRA_MODEL, model)
                putExtra(MainHook.EXTRA_PROVIDER, provider)
                putExtra(MainHook.EXTRA_TEMPERATURE, temperature)
                putExtra(MainHook.EXTRA_MAX_TOKENS, maxTokens)
                putExtra(MainHook.EXTRA_PROMPT, prompt)
                putExtra(MainHook.EXTRA_ENABLED, switchEnabled.isChecked)
                putExtra(MainHook.EXTRA_SHOW_TOAST, switchShowToast.isChecked)
                putExtra(MainHook.EXTRA_SHOW_STARTUP_TOAST, switchStartupToast.isChecked)
                putExtra(MainHook.EXTRA_ASYNC_REWRITE, switchAsyncRewrite.isChecked)
                putExtra(MainHook.EXTRA_REWRITE_TIMEOUT, rewriteTimeout)
                putExtra(MainHook.EXTRA_FILTER_MODE, filterMode)
                putStringArrayListExtra(MainHook.EXTRA_WHITELIST, ArrayList(whitelist))
                putStringArrayListExtra(MainHook.EXTRA_BLACKLIST, ArrayList(blacklist))
                putExtra(MainHook.EXTRA_LAST_UPDATED, timestamp)
            }
            requireActivity().sendBroadcast(intent)

            showStatus("配置已保存。QQ 运行中即时生效，否则下次启动自动加载", false)
        } catch (e: Exception) {
            showStatus("保存失败: ${e.message}", true)
        }
    }

    private fun showStatus(message: String, isError: Boolean) {
        textStatus.text = message
        textStatus.setTextColor(
            if (isError) resources.getColor(R.color.status_error, null)
            else resources.getColor(R.color.status_ok, null)
        )
    }
}
