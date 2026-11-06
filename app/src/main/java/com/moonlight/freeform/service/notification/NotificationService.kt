package com.moonlight.freeform.service.notification

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.IIntentSender
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.annotation.RequiresApi
import com.moonlight.freeform.R
import com.moonlight.freeform.app.MiFreeform
import com.moonlight.freeform.room.NotificationAppsEntity
import com.moonlight.freeform.systemapi.UserHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method
import java.util.Arrays.stream


/**
 * Notification listener class
 */
class NotificationService : NotificationListenerService(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    // In-app language switch: the "open in freeform" button text of taken-over notifications follows the
    // setting locale (the notification channel name is cached by the system and not updated on switch)
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(com.moonlight.freeform.utils.LocaleHelper.wrap(newBase))
    }

    private val scope = MainScope()
    private lateinit var notificationManager: NotificationManager
    private lateinit var sp: SharedPreferences
    private lateinit var viewModel: NotificationViewModel
    private var notificationApps = ArrayList<NotificationAppsEntity>()
    private val notificationAppsPackageName = ArrayList<String>()

    private var enable = false
    /**
     * Service started listening
     */
    override fun onListenerConnected() {
        super.onListenerConnected()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        sp = application.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        viewModel = NotificationViewModel(this)
        enable = sp.getBoolean("notify_freeform", false)
        getNotificationApps()

        sp.registerOnSharedPreferenceChangeListener(this)
    }

    private var lastNotificationTime = 0L

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (!enable || sbn?.packageName == MiFreeform.PACKAGE_NAME) return

        if (notificationApps.isNotEmpty() && sbn != null) {
            // If the notification was sent by a whitelisted app
            if (notificationAppsPackageName.contains(sbn.packageName)) {
                // Ignore non-clearable notifications; only accept messages
                if (!sbn.isClearable) return
                // If the timestamp equals the last one, treat it as the same notification.
                // Not sure why, but cancel produces two notifications, so this is the only workaround.
                if (sbn.postTime == lastNotificationTime) return
                // Post our custom notification
                freeformNotification(sbn)
                // Dismiss the source app's notification
                cancelNotification(sbn.key)
                lastNotificationTime = sbn.postTime
            }
        }
    }

    // key must be declared nullable: Huawei HarmonyOS invokes the callback with key=null on sp.clear();
    // Kotlin's non-null parameter check would throw NPE before the method body runs
    // (one root cause of the on-device crash when restoring default settings).
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        runCatching {
        when(key) {
            "notify_freeform" -> {
                enable = sp.getBoolean("notify_freeform", false)
            }
            // Called when the selected apps change
            "notify_freeform_changed" -> {
                getNotificationApps()
            }
        }
        }
    }

    private fun getNotificationApps() {
        scope.launch(Dispatchers.IO) {
            notificationApps = (viewModel.getAllNotificationApps().first() as ArrayList<NotificationAppsEntity>?)!!

            notificationAppsPackageName.clear()
            notificationApps.forEach {
                notificationAppsPackageName.add(it.packageName)
            }
        }
    }

    /**
     * Freeform notification
     */
    @SuppressLint("UseCompatLoadingForDrawables", "UnspecifiedImmutableFlag")
    private fun freeformNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras
        val title = extras?.getString(Notification.EXTRA_TITLE)
        val text = extras?.getString(Notification.EXTRA_TEXT)
        val smallIcon: Icon = sbn.notification.smallIcon
        val largeIcon: Icon? = try {
            sbn.notification.getLargeIcon()
        }catch (e: Exception) {
            null
        }
        // Create the notification channel
        val channel = NotificationChannel(
            CHANNEL_ID,
                getString(R.string.freeform_channel_id),
                NotificationManager.IMPORTANCE_HIGH
        )
        notificationManager.createNotificationChannel(channel)

        val intent = Intent(this, NotificationIntentService::class.java)
        intent.putExtra("packageName", sbn.packageName)
        intent.putExtra("userId", UserHandle.getUserId(sbn.user))
        val pendingIntent = PendingIntent.getService(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val freeFormButton = Notification.Action.Builder(smallIcon, getString(R.string.open_by_freeform), pendingIntent).build()

        // Configure the notification
        val notificationBuilder = Notification.Builder(this,
            CHANNEL_ID
        )
            .setContentTitle(title)
            .setContentText(text)
            .setWhen(System.currentTimeMillis())
            .setSmallIcon(smallIcon)
            .setAutoCancel(true)
            .setContentIntent(sbn.notification.contentIntent)
            .addAction(freeFormButton)
        if (largeIcon != null) notificationBuilder.setLargeIcon(largeIcon)
        val notification = notificationBuilder.build()
        // Dismiss after the notification is tapped
        notification.flags = Notification.FLAG_AUTO_CANCEL
        notificationManager.notify(2, notification)
    }

//    @RequiresApi(Build.VERSION_CODES.P)
//    @SuppressLint("DiscouragedPrivateApi")
//    private fun getIntent(pendingIntent: PendingIntent?): Intent? {
//        var intent: Intent? = null
//        try {
////            val getIntent: Method = PendingIntent::class.java.getDeclaredMethod("getIntent")
////            getIntent.isAccessible = true
//            val mTargetField = PendingIntent::class.java.getDeclaredField("mTarget")
//            mTargetField.isAccessible = true
//
//            val mTarget = mTargetField.get(pendingIntent)
//            Log.e(TAG, "$mTarget")
//
//            val iActivityManager = IActivityManager.Stub.asInterface(ShizukuBinderWrapper(SystemServiceHelper.getSystemService("activity")))
//
//            intent = iActivityManager?.getIntentForIntentSender(mTarget as IIntentSender)
//        } catch (e: Exception) {
//            Log.e(TAG, "$e")
//        }
//        return intent
//    }

    companion object {
        const val CHANNEL_ID = "CHANNEL_ID_SUNSHINE_FREEFORM_NOTIFICATION"
        private const val TAG = "NotificationService"
    }

}
