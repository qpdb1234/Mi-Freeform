package com.moonlight.freeform.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.*
import com.google.android.material.color.DynamicColors
import com.moonlight.freeform.BuildConfig
import com.moonlight.freeform.IControlService
import com.moonlight.freeform.service.ControlService
import com.moonlight.freeform.utils.DiagnosticsLog
import com.tencent.bugly.crashreport.CrashReport
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.sui.Sui

/**
 * @author sunshine
 * @date 2021/3/17
 */
class MiFreeform : Application() {
    val isRunning = MutableLiveData(false)
    private var controlService: IControlService? = null

    private val onRequestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SUI_CODE && grantResult == PackageManager.PERMISSION_GRANTED) {
                initShizuku()
            }
        }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(SUI_CODE)
        } else {
            initShizuku()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        isRunning.postValue(false)
    }

    private val userServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(
                BuildConfig.APPLICATION_ID,
                ControlService::class.java.name
            )
        ).processNameSuffix("service")

    private val userServiceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName?) {
            isRunning.value = false
            controlService = null
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            controlService = IControlService.Stub.asInterface(service)
            if (controlService!!.init()) isRunning.value = true
        }
    }

    companion object {
        var me: MiFreeform? = null
        private const val TAG = "MiFreeForm"
        /**
         * Package name of this application itself.
         *
         * Must not be hardcoded: applicationId may change, and HookLauncher uses it
         * to call createPackageContext to obtain this app's code. A hardcoded value
         * would cause the Context lookup to fail.
         */
        val PACKAGE_NAME: String = com.moonlight.freeform.BuildConfig.APPLICATION_ID
        // Software version – indicates when the intro screen should be shown again
        const val VERSION = 1
        // Privacy policy version, used to gate the privacy dialog
        const val VERSION_PRIVACY = 1
        const val APP_SETTINGS_NAME = "app_settings"
        private const val SUI_CODE = 0

        /**
         * Records a failure into the replayable diagnostics log.
         *
         * Previously this only appended to a StringBuilder that was never read,
         * effectively discarding the info. Now it writes to both DiagnosticsLog
         * (persisted to disk, survives reboot) and logcat.
         */
        fun addLog(tag: String, functionName: String, e: Exception) {
            val context = me ?: return
            DiagnosticsLog.record(context, tag, "$functionName: ${e.javaClass.simpleName}: ${e.message}")
        }

        init {
            Sui.init(BuildConfig.APPLICATION_ID)
        }
    }

    override fun onCreate() {
        super.onCreate()
        me = this

        // Migrate old preference keys after renames; must run before any settings read
        com.moonlight.freeform.utils.SettingsKeyMigration.migrate(this)

        DynamicColors.applyToActivitiesIfAvailable(this);

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addRequestPermissionResultListener(onRequestPermissionResultListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        val sp = getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        if (sp.getInt("version_privacy", -1) >= VERSION_PRIVACY) {
            CrashReport.initCrashReport(applicationContext)
        }
    }

    override fun attachBaseContext(base: Context) {
        // In-app language switch: wrap base Context per setting so Application-level
        // resources (including applicationContext used by Services) resolve the correct
        // locale; takes effect on next process start after the setting changes.
        super.attachBaseContext(com.moonlight.freeform.utils.LocaleHelper.wrap(base))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("")
        }
    }

    private fun bindShizukuService() {
        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection)
        } catch (e: Exception) {
            addLog(TAG, "bindShizukuService", e)
        }
    }

    fun initShizuku() {
        if (controlService?.asBinder()?.pingBinder() == true) return
        bindShizukuService()
    }

    fun initShizuku(callback: ShizukuBindCallback) {
        initShizuku()
    }

    fun getControlService() = controlService

    interface ShizukuBindCallback {
        fun onBind()
    }
}
