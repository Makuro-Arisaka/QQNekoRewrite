package com.neko.rewrite.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.neko.rewrite.R

/**
 * 关于页 — 显示 App 版本与 GitHub 项目地址
 */
class AboutFragment : Fragment() {

    companion object {
        const val GITHUB_URL = "https://github.com/Makuro-Arisaka/QQNekoRewrite"
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_about, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val versionText = try {
            val info = requireContext().packageManager.getPackageInfo(requireContext().packageName, 0)
            "版本 ${info.versionName} (${info.longVersionCode})"
        } catch (_: PackageManager.NameNotFoundException) {
            "版本未知"
        }
        view.findViewById<TextView>(R.id.text_about_version).text = versionText

        view.findViewById<View>(R.id.btn_about_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<View>(R.id.btn_about_github).setOnClickListener {
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL)))
            }
        }
    }
}
