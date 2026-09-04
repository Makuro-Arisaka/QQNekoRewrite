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
 */
class LogFragment : Fragment() {

    companion object {
        const val TAG = "LogFragment"
    }

    private lateinit var textLog: TextView
    private lateinit var logScroll: ScrollView

    private val logFile by lazy { File(requireActivity().filesDir, "neko_rewrite.log") }

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
        logScroll = view.findViewById(R.id.log_scroll)

        view.findViewById<View>(R.id.btn_refresh_log).setOnClickListener { refreshLog() }
        view.findViewById<View>(R.id.btn_clear_log).setOnClickListener { clearLog() }
        view.findViewById<View>(R.id.btn_export_log).setOnClickListener { exportLog() }

        refreshLog()
    }

    override fun onResume() {
        super.onResume()
        refreshLog()
    }

    private fun refreshLog() {
        try {
            val logContent = if (logFile.exists()) {
                val lines = logFile.readLines()
                val recent = if (lines.size > 200) lines.takeLast(200) else lines
                recent.joinToString("\n")
            } else {
                "日志文件尚未创建。\n请在 LSPosed 中启用模块并重启 QQ 后查看。"
            }
            textLog.text = logContent.ifEmpty { "暂无日志" }
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
                Toast.makeText(requireContext(), "❌ 导出失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "❌ 导出失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}