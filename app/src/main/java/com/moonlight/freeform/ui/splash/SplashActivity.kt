package com.moonlight.freeform.ui.splash

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.moonlight.freeform.R
import com.moonlight.freeform.app.MiFreeform
import com.moonlight.freeform.service.ForegroundService
import com.moonlight.freeform.service.KeepAliveService
import com.moonlight.freeform.ui.main.MainActivity
import com.moonlight.freeform.ui.permission.PermissionActivity
import com.moonlight.freeform.utils.PermissionUtils
import com.moonlight.freeform.utils.ServiceUtils
import com.tencent.bugly.crashreport.CrashReport

/**
 * Launch-routing page: it only makes the "privacy policy + permissions" decision and jumps immediately.
 *
 * The splash screen has been removed: this no longer loads any layout (the old logo page activity_splash was
 * removed), and it no longer does a Thread.sleep(500) delayed jump. Combined with Theme.MiFreeform.Splash
 * (transparent window + windowDisablePreview), nothing flashes on screen between tapping the icon and entering the settings page.
 */
class SplashActivity : AppCompatActivity() {

    //In-app language switch: wrap the base Context per the saved setting (see LocaleHelper)
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.moonlight.freeform.utils.LocaleHelper.wrap(newBase))
    }

    private lateinit var viewModel: SplashViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[SplashViewModel::class.java]

        if (viewModel.getIntSp("version_privacy", -1) < MiFreeform.VERSION_PRIVACY) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.privacy_title))
                .setMessage(getString(R.string.privacy_message))
                .setPositiveButton(getString(R.string.agree)) { _, _ ->
                    viewModel.putIntSp("version_privacy", MiFreeform.VERSION_PRIVACY)

                    CrashReport.initCrashReport(applicationContext)

                    toCheckPermission()
                }
                .setNegativeButton(getString(R.string.reject)) { _, _ ->
                    finish()
                }
                .setCancelable(false)
                .create().show()
        } else {
            toCheckPermission()
        }
    }

    private fun toCheckPermission() {
        if (checkPermission()) {
            //The guide screen was removed, but it can still be viewed manually q220917.2
            showMain()
        } else {
            showPermission()
        }
    }

    /**
     * Check the permissions Mi-Freeform needs
     */
    private fun checkPermission(): Boolean {
        when (viewModel.getIntSp("service_type", KeepAliveService.SERVICE_TYPE)) {
            ForegroundService.SERVICE_TYPE -> {
                if (!ServiceUtils.isServiceWork(this, "com.moonlight.freeform.service.ForegroundService")) {
                    startForegroundService(Intent(this, ForegroundService::class.java))
                }
            }
        }
        return PermissionUtils.checkOverlayPermission(this)
    }

    private fun showMain() {
        // FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS is only needed when "hide from Recents" is on;
        // that setting rebuilds the task itself when it changes, so this only handles the cold-start path.
        var flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (viewModel.getBooleanSp("hide_from_recent", false)) {
            flags = flags or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(flags))
        finish()
    }

    private fun showPermission() {
        startActivity(Intent(this, PermissionActivity::class.java))
        finish()
    }
}
