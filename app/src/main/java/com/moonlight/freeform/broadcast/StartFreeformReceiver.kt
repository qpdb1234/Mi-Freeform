package com.moonlight.freeform.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.moonlight.freeform.ui.freeform.FreeformConfig
import com.moonlight.freeform.ui.freeform.FreeformHelper
import com.moonlight.freeform.ui.freeform.FreeformView

class StartFreeformReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra("packageName")
        val activityName = intent.getStringExtra("activityName")
        val userId = intent.getIntExtra("userId", 0)
        val extras = intent.getStringExtra("extras")

        if (packageName != null && activityName != null) {
            // 同一应用已有小窗时先清掉旧实例
            FreeformHelper.destroyExistingFreeform(packageName, userId)

            FreeformView(
                FreeformConfig(
                    useCustomConfig = false,
                    packageName = packageName,
                    activityName = activityName,
                    userId = userId
                ),
                context
            )
        }
    }
}