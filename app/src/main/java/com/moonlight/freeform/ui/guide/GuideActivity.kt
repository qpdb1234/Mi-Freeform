package com.moonlight.freeform.ui.guide

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import androidx.navigation.findNavController
import androidx.navigation.ui.navigateUp
import com.moonlight.freeform.R
import com.moonlight.freeform.databinding.ActivityFreeformGuideBinding

class GuideActivity : AppCompatActivity() {

    //In-app language switch: wrap the base Context per the saved setting (see LocaleHelper)
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