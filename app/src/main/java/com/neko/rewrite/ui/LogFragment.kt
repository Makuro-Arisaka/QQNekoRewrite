package com.neko.rewrite.ui

import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.neko.rewrite.LogRecorder
import com.neko.rewrite.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * 日志 Fragment — 查看/刷新/清空/导出日志
 *
 * 刷新时机：首次创建、[onResume]、以及被底部导航切换显示时（[onHiddenChanged]）。
 * 底栏用的是 hide/show 而非 replace，切回来不会走 onResume，必须监听显隐。
 */
class LogFragment : Fragment() {

    companion object {
        const val TAG = "LogFragment"
    }

    private lateinit var textLog: TextView
    private lateinit var textLogPath: TextView
    private lateinit var logScroll: ScrollView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_logs, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        textLog = view.findViewById(R.id.text_log)
        textLogPath = view.findViewById(R.id.text_log_path)
        logScroll = view.findViewById(R.id.log_scroll)

        view.findViewById<View>(R.id.btn_refresh_log).setOnClickListener { refreshLog() }
        view.findViewById<View>(R.id.btn_clear_log).setOnClickListener { clearLog() }
        view.findViewById<View>(R.id.btn_export_log).setOnClickListener { exportLog() }

        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        if (view != null) refreshLog()
    }

    /** 底栏 hide/show 切换：从隐藏变为可见时刷新 */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && view != null) refreshLog()
    }

    private fun refreshLog() {
        try {
            val logContent = LogRecorder.readAll(200)
            textLogPath.text = "日志文件：${LogRecorder.logPath}"
            textLog.text = if (logContent.isBlank()) {
                "暂无日志。\n\n" +
                    "若模块已启用且 QQ 已重启仍无内容，请检查：\n" +
                    "1. LSPosed 中模块作用域包含 QQ\n" +
                    "2. 本 App 已授予「所有文件访问」权限（日志写在此处）\n" +
                    "3. 上方路径是否为可写目录"
            } else {
                logContent
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
