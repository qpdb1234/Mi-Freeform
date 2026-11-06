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
 * Open the app chooser via an activity.
 */
class FloatingActivity : AppCompatActivity() {

    //In-app language switch: wrap the base Context per the saved setting (see LocaleHelper)
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
                // Send "open the sidebar" straight to the service's onStartCommand, instead of first writing to_show_floating and then probing whether the service is up.
                // The old approach had a guaranteed race here: startForegroundService is asynchronous, so the very next
                // ServiceUtils.isServiceWork call still reads false (the service's onCreate hasn't even run yet),
                // so the first click after a cold process start only pops a "make sure the foreground service is running" toast, and only the second click works.
                // Once we reuse the persistent notification's ACTION_TOGGLE_FLOATING channel, the service receives the same
                // action in onStartCommand whether it is already running or is being brought up by this very call, so no liveness probe is needed.
                try {
                    startForegroundService(
                        Intent(this, ForegroundService::class.java)
                            .setAction(ForegroundService.ACTION_TOGGLE_FLOATING)
                    )
                } catch (e: Exception) {
                    // Only fall back to a prompt when the foreground service cannot start (background-start limits, etc.); this path must leave a trace,
                    // otherwise the user still just sees "nothing happens when I tap".
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