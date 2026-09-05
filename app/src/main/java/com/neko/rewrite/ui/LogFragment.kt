package com.neko.rewrite.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.neko.rewrite.ConfigBroadcast
import com.neko.rewrite.ConfigManager
import com.neko.rewrite.LogRecorder
import com.neko.rewrite.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 运行日志（二级页）
 *
 * 入口在概览页「关于」上方。本页有一个「启用运行日志」开关（默认关闭）：
 *  - 用户拨到开启 → 若尚未授予「所有文件访问」，先跳转系统设置申请；
 *    - 授权成功才真正开启文件日志并展示日志；
 *    - 授权失败（用户返回 / 拒绝）则保持关闭，绝不偷偷开启。
 *  - 关闭开关 → 停止写入（已产生的日志文件仍可读 / 导出）。
 *
 * 日志来源：模块进程自身 + QQ 进程（跨进程读 QQ 私有目录），由 [LogRecorder] 合并。
 * 刷新时机：开启后每 2.5s 自动刷新一次，外加手动「刷新」与切回本页时刷新。
 */
class LogFragment : Fragment() {

    companion object {
        const val TAG = "LogFragment"
    }

    private lateinit var switchLog: MaterialSwitch
    private lateinit var textLogStatus: TextView
    private lateinit var textLogPath: TextView
    private lateinit var textLog: TextView
    private lateinit var logScroll: ScrollView

    private val prefs by lazy {
        requireActivity().getSharedPreferences(ConfigManager.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    }

    private lateinit var permissionLauncher: ActivityResultLauncher<Intent>

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (LogRecorder.fileLoggingEnabled && isResumed) {
                refreshLog()
                mainHandler.postDelayed(this, 2500)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_logs, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        switchLog = view.findViewById(R.id.switch_log_enabled)
        textLogStatus = view.findViewById(R.id.text_log_status)
        textLogPath = view.findViewById(R.id.text_log_path)
        textLog = view.findViewById(R.id.text_log)
        logScroll = view.findViewById(R.id.log_scroll)

        view.findViewById<View>(R.id.btn_log_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.btn_refresh_log).setOnClickListener { refreshLog() }
        view.findViewById<View>(R.id.btn_clear_log).setOnClickListener { clearLog() }
        view.findViewById<View>(R.id.btn_export_log).setOnClickListener { exportLog() }

        // 申请「所有文件访问」后回到本页：若已授权则开启，否则提示并保持关闭
        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (Environment.isExternalStorageManager()) {
                switchLog.isChecked = true // 触发 onCheckedChanged → 已授权 → 真正开启
            } else {
                switchLog.isChecked = false
                Toast.makeText(
                    requireContext(),
                    "需要「所有文件访问」权限才能记录日志",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        switchLog.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (Environment.isExternalStorageManager()) {
                    enableLogging()
                } else {
                    // 先回退开关状态，等权限结果回来再决定是否置真，避免「开了却没权限」
                    switchLog.isChecked = false
                    requestAllFilesPermission()
                }
            } else {
                disableLogging()
            }
        }

        restoreState()
    }

    override fun onResume() {
        super.onResume()
        if (LogRecorder.fileLoggingEnabled) {
            refreshLog()
            startTicker()
        }
    }

    override fun onPause() {
        super.onPause()
        stopTicker()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopTicker()
    }

    /**
     * 进入页面时恢复上次状态：
     *  - 上次开启且权限仍在 → 直接开启（恢复记录）；
     *  - 上次开启但权限已失效 → 关着，并提示重新开启；
     *  - 从未开启 → 关着。
     */
    private fun restoreState() {
        val wantEnabled = prefs.getBoolean("log_enabled", false)
        if (wantEnabled && Environment.isExternalStorageManager()) {
            switchLog.isChecked = true // 触发 onCheckedChanged → 已授权 → 真正开启
        } else {
            switchLog.isChecked = false
            if (wantEnabled) {
                textLogStatus.text = "⚠️ 上次授予的权限已失效，请重新开启日志"
                textLogStatus.setTextColor(requireContext().getColor(R.color.status_warn))
            }
            updateUiDisabled()
        }
    }

    private fun requestAllFilesPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
            data = Uri.parse("package:${requireActivity().packageName}")
        }
        try {
            permissionLauncher.launch(intent)
        } catch (_: Exception) {
            switchLog.isChecked = false
            Toast.makeText(requireContext(), "无法打开权限设置页", Toast.LENGTH_SHORT).show()
        }
    }

    /** 真正开启文件日志：选可写目标 → 持久化 logEnabled 广播给 QQ → 刷新 UI */
    private fun enableLogging() {
        val ok = LogRecorder.enable(requireContext())
        if (!ok) {
            switchLog.isChecked = false
            Toast.makeText(
                requireContext(),
                "无法创建日志文件，请检查存储权限后重试",
                Toast.LENGTH_LONG
            ).show()
            persistLogEnabled(false)
            updateUiDisabled()
            return
        }
        persistLogEnabled(true)
        updateUiEnabled()
        refreshLog()
        startTicker()
    }

    private fun disableLogging() {
        LogRecorder.disable()
        persistLogEnabled(false)
        updateUiDisabled()
        refreshLog()
        stopTicker()
    }

    /**
     * 把 logEnabled 改动连同「当前已保存的完整配置」一起发给 QQ。
     * 必须从模块自身 SP 读完整配置（含 api_key / enabled 等），
     * 不能从内存默认 config 重建 —— 否则广播会清掉用户已填的 Key。
     */
    private fun persistLogEnabled(enabled: Boolean) {
        try {
            val base = ConfigManager.readLocalConfig(requireActivity())
            val newConfig = base.copy(
                logEnabled = enabled,
                lastUpdated = System.currentTimeMillis()
            )
            val ts = ConfigManager.saveFromSettings(requireActivity(), newConfig)
            ConfigBroadcast.send(requireActivity(), newConfig, ts)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "保存日志开关失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateUiEnabled() {
        textLogStatus.text = "● 记录中"
        textLogStatus.setTextColor(requireContext().getColor(R.color.status_ok))
    }

    private fun updateUiDisabled() {
        textLogStatus.text = "○ 已停止"
        textLogStatus.setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
    }

    /** 解析当前主题下的一个颜色属性（随 Material You 动态取色变化） */
    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        requireContext().theme.resolveAttribute(attr, tv, true)
        return tv.data
    }

    private fun startTicker() {
        mainHandler.removeCallbacks(tickRunnable)
        mainHandler.postDelayed(tickRunnable, 2500)
    }

    private fun stopTicker() {
        mainHandler.removeCallbacks(tickRunnable)
    }

    private fun refreshLog() {
        try {
            if (LogRecorder.fileLoggingEnabled) {
                val content = LogRecorder.readAll(200)
                textLogPath.text = "日志文件：${LogRecorder.logPath}"
                textLog.text = if (content.isBlank()) {
                    "等待日志…（QQ 运行中、发送消息后会出现在这里）"
                } else {
                    content
                }
            } else {
                textLogPath.text = "（日志功能未启用）"
                textLog.text = "日志功能未开启。\n\n" +
                    "开启上方开关后，将开始记录 QQ 的改写日志（需要「所有文件访问」权限）。"
            }
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        } catch (e: Exception) {
            textLog.text = "读取日志失败: ${e.message}"
        }
    }

    private fun clearLog() {
        LogRecorder.clear()
        textLog.text = "日志已清空\n"
        logScroll.fullScroll(View.FOCUS_DOWN)
        Toast.makeText(requireContext(), "日志已清空", Toast.LENGTH_SHORT).show()
    }

    private fun exportLog() {
        try {
            val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val exportFile = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "NekoRewrite_log_$dateStr.txt"
            )
            if (LogRecorder.exportTo(exportFile)) {
                Toast.makeText(
                    requireContext(),
                    "✅ 日志已导出到: ${exportFile.absolutePath}",
                    Toast.LENGTH_LONG
                ).show()
                LogRecorder.info("Settings", "日志已导出: ${exportFile.name}")
                refreshLog()
            } else {
                Toast.makeText(requireContext(), "❌ 导出失败（无日志内容）", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "❌ 导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
