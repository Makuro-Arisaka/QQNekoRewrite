package com.neko.rewrite

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.color.DynamicColors
import com.neko.rewrite.ui.LogFragment
import com.neko.rewrite.ui.OverviewFragment
import com.neko.rewrite.ui.SettingsFragment

/**
 * 主 Activity — Material You 底部导航
 *
 * 3 个标签页：概览 | 设置 | 日志
 *
 * 沉浸式系统栏 + 动态取色（参照 LSPosed 管理器这类 Material3 应用的做法）：
 *  - Android 12+ 通过 [DynamicColors] 应用 Material You（Monet）动态取色，
 *    使界面配色与系统/其他 Material You 应用保持一致；更早版本回退到主题内静态色板。
 *  - 用 edge-to-edge 让内容延伸到透明状态栏与导航栏（含手势小白条）之下，
 *    仅通过 inset 把正文/底部导航顶开，栏位背景统一为 [attr.colorSurface]，观感无缝。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var bottomNav: BottomNavigationView

    private val overviewFragment = OverviewFragment()
    private val settingsFragment = SettingsFragment()
    private val logFragment = LogFragment()
    private var activeFragment: Fragment = overviewFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        // 必须在 super.onCreate 之前应用动态取色 overlay，窗口主题才生效
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
        super.onCreate(savedInstanceState)
        setupImmersiveSystemBars()
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
        applySystemBarInsets()

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

    override fun onResume() {
        super.onResume()
        // 用户可能在上面那步刚授予「所有文件访问」，回来后把日志目标切到共享目录，
        // 这样 QQ 进程写的日志模块进程才读得到
        LogRecorder.reinit(this)
    }

    /**
     * 让窗口内容延伸到状态栏 / 导航栏（含手势小白条）之下：
     *  - 关闭 decorFitsSystemWindows（旧版 Android 也可用，底层兼容）
     *  - 状态栏与导航栏设为透明
     *  - 依据当前日/夜间模式设置栏位图标深浅，避免图标与背景撞色
     */
    private fun setupImmersiveSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isLightTheme = nightMode != Configuration.UI_MODE_NIGHT_YES
        controller.isAppearanceLightStatusBars = isLightTheme
        controller.isAppearanceLightNavigationBars = isLightTheme
    }

    /**
     * 把系统栏 inset 应用为内边距：
     *  - 顶部（状态栏）落到 fragment 容器，正文不被状态图标遮挡，
     *    其上一条栏位区域由根布局 [colorSurface] 背景填充 → 无缝沉浸。
     *  - 底部（导航/小白条）落到 BottomNavigationView，
     *    让其 [colorSurface] 背景继续延伸到屏幕最底，导航项浮在小白条之上。
     */
    private fun applySystemBarInsets() {
        val fragmentContainer = findViewById<android.view.View>(R.id.fragment_container)

        // 顶部 inset → fragment 容器 paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(fragmentContainer) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, 0)
            insets
        }
        ViewCompat.requestApplyInsets(fragmentContainer)

        // 底部 inset → 底部导航 paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(bottomNav) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, 0, 0, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(bottomNav)
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