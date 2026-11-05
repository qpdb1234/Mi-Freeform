package com.moonlight.freeform.ui.floating

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.moonlight.freeform.R
import com.moonlight.freeform.app.MiFreeform
import com.moonlight.freeform.service.ForegroundService
import com.moonlight.freeform.service.KeepAliveService
import com.moonlight.freeform.utils.PermissionUtils

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
                // 把「打开侧边栏」直接投给服务的 onStartCommand，而不是先写 to_show_floating 再查服务在不在。
                // 旧写法在这里有个必输的竞态：startForegroundService 是异步的，紧跟其后那一次
                // ServiceUtils.isServiceWork 读到的必然还是 false（服务的 onCreate 都还没跑到），
                // 于是进程冷启动后的第一次点击只会弹一句「请确保前台服务在运行」，第二次才有效。
                // 复用常驻通知同一条 ACTION_TOGGLE_FLOATING 通道之后，服务无论是已在运行、
                // 还是正被这次调用拉起来，都会在 onStartCommand 里收到同一个动作，不需要任何存活探测。
                try {
                    startForegroundService(
                        Intent(this, ForegroundService::class.java)
                            .setAction(ForegroundService.ACTION_TOGGLE_FLOATING)
                    )
                } catch (e: Exception) {
                    // 前台服务起不来（后台启动限制等）才退回提示；这条路径必须留痕，
                    // 否则用户看到的仍然是「点了没反应」。
                    Log.w(TAG, "start_foreground_service_failed", e)
                    Toast.makeText(this, getString(R.string.require_foreground), Toast.LENGTH_SHORT).show()
                }
            }
        }

        finish()
    }

    private companion object {
        private const val TAG = "FloatingActivity"
    }
}