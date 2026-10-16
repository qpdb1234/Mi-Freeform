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
 * 启动路由页，只做「隐私政策 + 权限」判断然后立刻跳转。
 *
 * 开屏画面已经删除：这里不再加载任何布局（原来的 logo 页 activity_splash 已移除），
 * 也不再 Thread.sleep(500) 延时跳转。配合 Theme.MiFreeform.Splash
 * （透明窗口 + windowDisablePreview），点图标到进入设置页之间屏幕上不会闪出任何东西。
 */
class SplashActivity : AppCompatActivity() {

    //应用内语言切换：按设置包裹基础 Context（见 LocaleHelper）
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
            //移除了引导界面，但是仍然可以自行查看 q220917.2
            showMain()
        } else {
            showPermission()
        }
    }

    /**
     * 检查米窗所需要的权限
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
        // 只有开了「从最近任务隐藏」才需要 EXCLUDE_FROM_RECENTS；
        // 该设置改动时会自己重建任务，所以这里只负责冷启动那条路径。
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
