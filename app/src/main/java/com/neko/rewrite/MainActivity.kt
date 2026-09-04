package com.neko.rewrite

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.neko.rewrite.ui.LogFragment
import com.neko.rewrite.ui.OverviewFragment
import com.neko.rewrite.ui.SettingsFragment

/**
 * 主 Activity — Material You 底部导航
 *
 * 3 个标签页：概览 | 设置 | 日志
 */
class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView

    private val overviewFragment = OverviewFragment()
    private val settingsFragment = SettingsFragment()
    private val logFragment = LogFragment()
    private var activeFragment: Fragment = overviewFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Android 11+ 需要"所有文件访问"权限才能写入 /sdcard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }

        LogRecorder.init(this)

        bottomNav = findViewById(R.id.bottom_navigation)

        // 添加所有 fragment（隐藏除概览外的所有）
        supportFragmentManager.beginTransaction().apply {
            add(R.id.fragment_container, logFragment, LogFragment.TAG).hide(logFragment)
            add(R.id.fragment_container, settingsFragment, SettingsFragment.TAG).hide(settingsFragment)
            add(R.id.fragment_container, overviewFragment, OverviewFragment.TAG)
        }.commit()

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_overview -> switchFragment(overviewFragment)
                R.id.nav_settings -> switchFragment(settingsFragment)
                R.id.nav_logs -> switchFragment(logFragment)
                else -> false
            }
        }

        // 默认选中概览
        bottomNav.selectedItemId = R.id.nav_overview
    }

    private fun switchFragment(target: Fragment): Boolean {
        if (target === activeFragment) return true
        supportFragmentManager.beginTransaction()
            .hide(activeFragment)
            .show(target)
            .commit()
        activeFragment = target
        return true
    }
}