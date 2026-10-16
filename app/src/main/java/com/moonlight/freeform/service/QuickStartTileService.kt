package com.moonlight.freeform.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.preference.PreferenceManager
import com.moonlight.freeform.R
import com.moonlight.freeform.app.MiFreeform
import com.moonlight.freeform.ui.floating.ChooseAppFloatingView
import com.moonlight.freeform.ui.floating.FloatingActivity
import kotlinx.coroutines.DelicateCoroutinesApi
import java.io.DataOutputStream
import java.lang.reflect.Method

/**
 * @author sunshine
 * @date 2021/2/27
 * 通知栏快捷磁贴服务
 */
@DelicateCoroutinesApi
class QuickStartTileService : TileService() {

    //应用内语言切换：磁贴标题由系统按 manifest 标签绘制，这里只影响本服务自己的文案
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.moonlight.freeform.utils.LocaleHelper.wrap(newBase))
    }

    override fun onClick() {
        super.onClick()
        startActivity(Intent(this, FloatingActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        collapseStatusBar()
    }

    @SuppressLint("WrongConstant")
    private fun collapseStatusBar() {
        if (Build.VERSION.SDK_INT >= 31) {
            MiFreeform.me?.getControlService()?.execShell("cmd statusbar collapse", false)
        } else {
            val service = getSystemService("statusbar") ?: return
            try {
                val clazz = Class.forName("android.app.StatusBarManager")
                val collapse: Method? = clazz.getMethod("collapsePanels")
                collapse?.isAccessible = true
                collapse?.invoke(service)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}