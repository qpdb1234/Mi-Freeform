package com.moonlight.freeform.ui.recent

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.WindowManager
import com.moonlight.freeform.R

class RecentActivity : AppCompatActivity() {

    //应用内语言切换：按设置包裹基础 Context（见 LocaleHelper）
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.moonlight.freeform.utils.LocaleHelper.wrap(newBase))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recent)

        window::class.java.getMethod("addSystemFlags", Int::class.javaPrimitiveType).invoke(window, 0x00080000)
    }
}