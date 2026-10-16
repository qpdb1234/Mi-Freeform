package com.moonlight.freeform.ui.guide

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.navigation.findNavController
import androidx.navigation.ui.navigateUp
import com.moonlight.freeform.R
import com.moonlight.freeform.databinding.ActivityFreeformGuideBinding

class GuideActivity : AppCompatActivity() {

    //应用内语言切换：按设置包裹基础 Context（见 LocaleHelper）
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.moonlight.freeform.utils.LocaleHelper.wrap(newBase))
    }

    private lateinit var binding: ActivityFreeformGuideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFreeformGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)

    }
}