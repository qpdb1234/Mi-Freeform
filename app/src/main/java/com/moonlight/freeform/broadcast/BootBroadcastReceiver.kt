package com.moonlight.freeform.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.moonlight.freeform.app.MiFreeform
import com.moonlight.freeform.service.ForegroundService

/**
 * @author sunshine
 * @date 2021/3/8
 */
class BootBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sp = context.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        if (intent.action == action_boot) {
            //回退默认改为前台服务（service_type 默认已为前台），未设置时开机也走前台
            if (sp.getInt("service_type", ForegroundService.SERVICE_TYPE) == ForegroundService.SERVICE_TYPE)
                context.startForegroundService(Intent(context, ForegroundService::class.java))
        }
    }

    companion object {
        const val action_boot = "android.intent.action.BOOT_COMPLETED"
    }
}
