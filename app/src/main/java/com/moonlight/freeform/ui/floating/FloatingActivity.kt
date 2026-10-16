package com.moonlight.freeform.ui.floating

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import com.moonlight.freeform.R
import com.moonlight.freeform.app.MiFreeform
import com.moonlight.freeform.service.ForegroundService
import com.moonlight.freeform.service.KeepAliveService
import com.moonlight.freeform.utils.PermissionUtils
import com.moonlight.freeform.utils.ServiceUtils

/**
 * 通过活动打开应用选择
 */
class FloatingActivity : AppCompatActivity() {

    //应用内语言切换：按设置包裹基础 Context（见 LocaleHelper）
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.moonlight.freeform.utils.LocaleHelper.wrap(newBase))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_floating)

        val sp = getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        when (sp.getInt("service_type", ForegroundService.SERVICE_TYPE)) {
            KeepAliveService.SERVICE_TYPE -> {
                if (PermissionUtils.isAccessibilitySettingsOn(this)) {
                    sp.edit().putBoolean("to_show_floating", !sp.getBoolean("to_show_floating", false)).apply()
                } else {
                    Toast.makeText(this, getString(R.string.require_accessibility), Toast.LENGTH_SHORT).show()
                }
            }
            ForegroundService.SERVICE_TYPE -> {
                if (ServiceUtils.isServiceWork(this, "com.moonlight.freeform.service.ForegroundService")) {
                    sp.edit().putBoolean("to_show_floating", !sp.getBoolean("to_show_floating", false)).apply()
                } else {
                    startForegroundService(Intent(this, ForegroundService::class.java))
                    if (ServiceUtils.isServiceWork(this, "com.moonlight.freeform.service.ForegroundService")) {
                        sp.edit().putBoolean("to_show_floating", !sp.getBoolean("to_show_floating", false)).apply()
                    } else {
                        Toast.makeText(this, getString(R.string.require_foreground), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        finish()
    }
}