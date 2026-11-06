package com.moonlight.freeform.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Settings.SettingNotFoundException
import android.text.TextUtils.SimpleStringSplitter
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import com.moonlight.freeform.R
import com.moonlight.freeform.service.KeepAliveService

/**
 * @date 2022/8/26
 * @author sunshine0523
 */
object PermissionUtils {

    /**
     * The tag for the "overlay permission missing" diagnostic record. The writer and the reader (whether the
     * ongoing notification shows a "grant" button) share the same constant, to avoid silently breaking on a typo.
     */
    const val DIAG_TAG_OVERLAY_MISSING = "overlay_permission_missing"

    /**
     * From which version does "code auto-navigating to the grant page" poison overlay windows process-wide.
     *
     * Measured poisoner: HyperOS 14 (API 34); the `mForceHideNonSystemOverlayWindow` field has existed since
     * Android 12, but on 12 re-granting clears the flag (diagnostics report §0.4). Below 34 the old navigation is kept.
     */
    private const val OVERLAY_POISON_MIN_SDK = 34

    fun checkNotificationListenerPermission(context: Context): Boolean {
        var enable = false
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        if (flat != null) {
            enable = flat.contains(context.packageName)
        }
        return enable
    }

    fun checkOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    /**
     * Which non-system overlay window type this attempt is eligible for; returning null means **neither should be tried**.
     *
     * Measured on HyperOS 14 (Android 14): calling `addView` once in an ineligible state is recorded by the system as a
     * "rejected non-system overlay request", after which **all** non-system overlay windows this process later creates, and
     * already-existing ones, are permanently set to `mForceHideNonSystemOverlayWindow=true`—re-granting does not clear it;
     * only restarting the process recovers. So "try one type, catch the exception, try the next" is a fatal pattern on this system;
     * eligibility must be decided clearly before addView.
     *
     * The priority follows the old behavior: use ACCESSIBILITY_OVERLAY when the accessibility service is genuinely on (it can cover system UI),
     * otherwise use the regular APPLICATION_OVERLAY.
     */
    fun pickOverlayWindowType(context: Context): Int? = when {
        isAccessibilitySettingsOn(context) -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        checkOverlayPermission(context) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else -> null
    }

    /**
     * Whether the user can actually see the ongoing-notification channel right now.
     *
     * This app targets SDK 32, and on Android 13+ POST_NOTIFICATIONS is granted automatically at install, so normally
     * no runtime request is needed; but the user can later turn off all MiFreeform notifications in system settings (HyperOS also
     * has a "smart control" that may turn them off on its own), at which point the ongoing notification and its "grant" button disappear together.
     * We only inform without navigating to system settings because that self-initiated navigation on Android 14 would hide overlay windows process-wide (see the note above),
     * whereas here we only read a local state and perform no navigation.
     */
    fun areNotificationsVisible(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * Unified notification when the overlay permission is missing: persist a replayable record + show a Toast.
     *
     * On Android 14 (API 34) and above we **never auto-navigate to the system settings page**, for the reasons below; below 34 the old one-tap shortcut is kept (see [openOverlayPermissionPage]).
     *
     * Why we don't navigate (measured on HyperOS 14, diagnostics report §1.12): after going through a "window creation rejected" branch once,
     * the `startActivity(ACTION_MANAGE_OVERLAY_PERMISSION)` at the end of that branch sets all non-system overlay windows this process later creates
     * to `mForceHideNonSystemOverlayWindow=true`—freeforms, sidebar, and panel all vanish, re-granting does not clear it, and only restarting the process
     * or even the phone helps. Quantitative on real device: keeping this navigation poisoned 4/4; removing only this navigation (everything else unchanged) poisoned 0/4.
     * Conversely, navigating to this settings page **alone** does not poison, so the grant entry point is still kept—just triggered by an explicit user tap:
     * the ongoing notification's "grant" -> [PermissionActivity] permission page -> the user taps the "overlay" card.
     *
     * Why the navigation is kept on older systems: the poisoning field was only introduced in Android 12, and on 12 re-granting
     * clears the flag (measured: 10/12 never enter this state), so on ≤33 this navigation is purely convenient with no cost;
     * taking it away from 10/12 users for the 14 fix would be an unwarranted behavior regression.
     *
     * The grant guidance is written into the record body, because the ongoing notification shows exactly the latest record (F3).
     * But this channel itself may be broken: when the user has turned off all MiFreeform notifications, [areNotificationsVisible] reads false,
     * making "tap grant on the notification" a dead hint, so in that case we switch to wording that points at the app icon.
     *
     * @param scene a user-facing scene description, e.g. "the sidebar handle couldn't be attached".
     *              Callers must pass `context.getString(R.string.scene_*)` and not write Chinese literals here—
     *              this body text appears directly in the user's notification.
     */
    fun guideToGrantOverlayPermission(context: Context, scene: String) {
        val notificationVisible = areNotificationsVisible(context)
        DiagnosticsLog.recordRes(
            context, DIAG_TAG_OVERLAY_MISSING,
            if (notificationVisible) R.string.diag_overlay_missing else R.string.diag_overlay_missing_no_notification,
            scene
        )
        Toast.makeText(
            context,
            context.getString(
                if (notificationVisible) R.string.request_overlay_permission
                else R.string.request_overlay_permission_via_app
            ),
            Toast.LENGTH_LONG
        ).show()
        if (Build.VERSION.SDK_INT < OVERLAY_POISON_MIN_SDK) {
            openOverlayPermissionPage(context)
        }
    }

    /**
     * Navigates to the system "display over other apps" settings page.
     *
     * May only be called by [guideToGrantOverlayPermission] on below 34 or by an explicit user tap: on Android 14
     * this code-initiated navigation would poison all overlay windows this process later creates (see the note above).
     * Some custom ROMs lack this page, so a try/catch is built in; the exception must not be thrown back into the caller's window-creation flow.
     */
    fun openOverlayPermissionPage(context: Context) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            addLogCompat(context, "openOverlayPermissionPage", e)
        }
    }

    private fun addLogCompat(context: Context, where: String, e: Exception) {
        DiagnosticsLog.record(context, DIAG_TAG_OVERLAY_MISSING, "$where failed: ${e.javaClass.simpleName}")
    }

    fun isAccessibilitySettingsOn(context: Context): Boolean {
        var accessibilityEnabled = 0
        val service = context.packageName + "/com.moonlight.freeform.service.KeepAliveService"
        try {
            accessibilityEnabled = Settings.Secure.getInt(
                context.applicationContext.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED
            )
        } catch (e: SettingNotFoundException) {
        }
        val mStringColonSplitter = SimpleStringSplitter(':')
        if (accessibilityEnabled == 1) {
            val settingValue = Settings.Secure.getString(
                context.applicationContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (settingValue != null) {
                mStringColonSplitter.setString(settingValue)
                while (mStringColonSplitter.hasNext()) {
                    val accessibilityService = mStringColonSplitter.next()
                    if (accessibilityService.equals(service, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }
}