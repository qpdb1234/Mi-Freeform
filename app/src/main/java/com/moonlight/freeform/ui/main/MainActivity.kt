package com.moonlight.freeform.ui.main

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.moonlight.freeform.R
import com.moonlight.freeform.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    //应用内语言切换：按设置包裹基础 Context（见 LocaleHelper）
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.moonlight.freeform.utils.LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 顶部标题栏与底部导航均已移除，内容需要避开状态栏
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingContainer) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(0, statusBarHeight, 0, 0)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(binding.settingContainer.id, SettingFragment())
                .commit()
        }
    }
}
