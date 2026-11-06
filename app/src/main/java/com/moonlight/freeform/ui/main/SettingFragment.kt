package com.moonlight.freeform.ui.main

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SeekBarPreference
import androidx.preference.SwitchPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.moonlight.freeform.R
import com.moonlight.freeform.app.MiFreeform
import com.moonlight.freeform.service.ForegroundService
import com.moonlight.freeform.service.KeepAliveService
import com.moonlight.freeform.ui.choose_apps.ChooseAppsActivity
import com.moonlight.freeform.ui.freeform.FreeformView
import com.moonlight.freeform.ui.permission.PermissionActivity
import com.moonlight.freeform.ui.view.IntegerSimpleMenuPreference
import com.moonlight.freeform.utils.PermissionUtils
import com.moonlight.freeform.utils.SettingsBackup
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class SettingFragment : PreferenceFragmentCompat(), Preference.OnPreferenceClickListener,
    Preference.OnPreferenceChangeListener {

    private lateinit var sp: SharedPreferences
    private lateinit var accessibilityRFAR: ActivityResultLauncher<Intent>

    // The SAF "where to save the backup" system callback. CreateDocument only calls back after the user confirms; canceling the selection doesn't call back.
    private lateinit var createBackupLauncher: ActivityResultLauncher<String>

    // The SAF "which backup file to restore from" system callback.
    private lateinit var openBackupLauncher: ActivityResultLauncher<Array<String>>

    // The three status cards at the top (Xposed / Shizuku / Accessibility), migrated from the original home page.
    private var statusCards: StatusCards? = null

    // After dragging the handle to change position, the service writes sidebar_pos_x_dp / sidebar_pos_y_dp back to SP.
    // The Preference framework doesn't auto-refresh the UI for external SP changes, so we listen ourselves and sync the seek bars and value chips,
    // achieving "after dragging the handle, the numbers and sliders in settings change immediately". The same process shares one SharedPreferences instance,
    // so the service's writes call back directly here.
    private val spSyncListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            // When the handle size changes, the two position sliders' upper bounds must be recomputed. This can't be hooked on onPreferenceChange:
            // SeekBarPreference.setValue (the tap-the-chip input path) internally calls persistInt directly;
            // only dragging goes through callChangeListener, so only the SP callback covers both change paths.
            SIDEBAR_WIDTH_DP, SIDEBAR_LENGTH_DP -> updateSidebarRange()
            SIDEBAR_POS_X, SIDEBAR_POS_Y -> {
                val pref = findPreference<SeekBarPreference>(key) ?: return@OnSharedPreferenceChangeListener
                val v = sp.getInt(key, pref.value)
                // Don't write back when the value is the same, to avoid the pointless round-trip "user drags slider -> persist -> this listener -> persist again"
                if (pref.value != v) pref.value = v
            }
        }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = MiFreeform.APP_SETTINGS_NAME
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE
        setPreferencesFromResource(R.xml.settings, null)

        sp = requireActivity().getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        accessibilityRFAR = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
                findPreference<IntegerSimpleMenuPreference>(SERVICE_TYPE)!!.setValueIndex(0)
            } else {
                sp.edit().putInt("service_type", ForegroundService.SERVICE_TYPE).apply()
                findPreference<IntegerSimpleMenuPreference>(SERVICE_TYPE)!!.setValueIndex(1)
                requireContext().startForegroundService(Intent(requireContext(), ForegroundService::class.java))
            }
        }

        // Backup: pop the system file picker to let the user choose a save location, then write the settings JSON once we get the Uri.
        // MIME must be passed explicitly: the no-arg constructor uses "*/*", and the created file's type in MediaStore
        // wouldn't match application/json, so the picker would refuse to select it on restore (reporting "no app can handle this") q261013.3.
        createBackupLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument(BACKUP_MIME_TYPE)) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            exportSettings(uri)
        }

        // Restore: pop the system file picker to let the user choose the backup file to import.
        openBackupLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@registerForActivityResult
            importSettings(uri)
        }

        findPreference<Preference>(QUICK_FLOATING_APP)!!.onPreferenceClickListener = this
        findPreference<Preference>(NOTIFICATION_FREEFORM_APPS)!!.onPreferenceClickListener = this
        findPreference<Preference>(RESET_OVERLAY_SETTING)!!.onPreferenceClickListener = this
        findPreference<Preference>(BACKUP_SETTINGS)!!.onPreferenceClickListener = this
        findPreference<Preference>(RESTORE_SETTINGS)!!.onPreferenceClickListener = this
        findPreference<SwitchPreference>(SHOW_FLOATING)!!.onPreferenceChangeListener = this
        findPreference<SwitchPreference>(NOTIFY_FREEFORM)!!.onPreferenceChangeListener = this
        findPreference<SwitchPreference>(HIDE_FROM_RECENT)!!.onPreferenceChangeListener = this
        findPreference<IntegerSimpleMenuPreference>(SERVICE_TYPE)!!.onPreferenceChangeListener = this
        findPreference<IntegerSimpleMenuPreference>(APP_LANGUAGE)!!.onPreferenceChangeListener = this
        // One-tap reset all settings to defaults: on click show a confirm dialog, then execute.
        // (The old freeform-scale data input screen was removed; numeric input is now handled by NumericSeekBarPreference itself)
        findPreference<Preference>(RESET_ALL_SETTINGS)!!.onPreferenceClickListener = this

        // Sidebar X/Y axis lock: once locked, the corresponding position slider is disabled (and the service side also forbids dragging that axis).
        findPreference<SwitchPreference>(SIDEBAR_LOCK_X)!!.onPreferenceChangeListener = this
        findPreference<SwitchPreference>(SIDEBAR_LOCK_Y)!!.onPreferenceChangeListener = this
        findPreference<SeekBarPreference>(SIDEBAR_POS_X)?.isEnabled = !sp.getBoolean(SIDEBAR_LOCK_X, true)
        findPreference<SeekBarPreference>(SIDEBAR_POS_Y)?.isEnabled = !sp.getBoolean(SIDEBAR_LOCK_Y, false)
        updateSidebarRange()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The three status cards serve as the list header. Use ConcatAdapter rather than addView:
        // PreferenceFragmentCompat's list is a RecyclerView; addView-ing a child View not created by the adapter
        // crashes during layout (RecyclerView only accepts items added through the adapter).
        val headerAdapter = StatusCardsAdapter(this) { holder ->
            val existing = statusCards
            if (existing != null && existing.container === holder) {
                // The header item was recycled after scrolling off-screen and re-bound when scrolling back, getting the same container:
                // the cards are already inside it, so just refresh the status. Previously this rebuilt every time,
                // so after repeated scrolling a pile of duplicate cards accumulated at the top.
                existing.refresh()
            } else {
                // The container is new (first time, or RecyclerView switched Holder); rebuild the three cards.
                statusCards = StatusCards(this, holder).also { cards ->
                    // Reuse the launcher already registered by the fragment (registerForActivityResult must be called before STARTED;
                    // it can't be registered after onBindViewHolder, or it throws IllegalStateException).
                    cards.onGoToAccessibilitySettings = {
                        accessibilityRFAR.launch(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                    cards.init()
                    cards.refresh()
                }
            }
        }
        listView.adapter = headerAdapter.wrap(listView.adapter)
    }

    /**
     * Used by the status card's "go switch keep-alive method" navigation: scroll to the keep-alive method item.
     */
    fun scrollToServiceType() {
        findPreference<IntegerSimpleMenuPreference>(SERVICE_TYPE)?.let {
            listView.scrollToPosition(getPreferencePosition(it))
        }
    }

    private fun getPreferencePosition(preference: Preference): Int {
        val screen = preferenceScreen
        for (i in 0 until screen.preferenceCount) {
            if (screen.getPreference(i) == preference) return i
        }
        return 0
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when(preference.key) {
            QUICK_FLOATING_APP -> {
                requireActivity().startActivity(Intent(requireActivity(), ChooseAppsActivity::class.java).putExtra("type", 1))
            }
            NOTIFICATION_FREEFORM_APPS -> {
                requireActivity().startActivity(Intent(requireActivity(), ChooseAppsActivity::class.java).putExtra("type", 2))
            }
            RESET_OVERLAY_SETTING -> {
                sp.edit().apply {
                    val screenWidth = min(requireContext().resources.displayMetrics.heightPixels, requireContext().resources.displayMetrics.widthPixels)

                    putInt("floating_position_portrait_y", 0)
                    putInt("floating_position_landscape_y", 0)
                    putInt("sidebar_pos_x_dp", 0)
                    putInt("sidebar_pos_y_dp", 0)
                    putInt(FreeformView.REMEMBER_X, 0)
                    putInt(FreeformView.REMEMBER_Y, 0)
                    putInt(FreeformView.REMEMBER_LAND_X, 0)
                    putInt(FreeformView.REMEMBER_LAND_Y, 0)
                    putInt(FreeformView.REMEMBER_HEIGHT, (screenWidth * 1.6 * 0.75).roundToInt())
                    putInt(FreeformView.REMEMBER_LAND_HEIGHT, (screenWidth * 0.8).roundToInt())
                    apply()
                }
                Snackbar.make(requireView(), getString(R.string.reset_success), Snackbar.LENGTH_SHORT).show()
            }
            BACKUP_SETTINGS -> {
                // System file picker, letting the user choose where to store the backup file (downloads, docs, cloud all work).
                createBackupLauncher.launch(BACKUP_FILE_NAME)
            }
            RESTORE_SETTINGS -> {
                // System file picker, letting the user pick a previously exported backup file.
                openBackupLauncher.launch(arrayOf(BACKUP_MIME_TYPE, "application/json", "application/octet-stream"))
            }
            RESET_ALL_SETTINGS -> {
                // Requires a confirmation tap before restoring defaults, to avoid clearing all custom parameters by accident
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(getString(R.string.reset_all_settings))
                    .setMessage(getString(R.string.reset_all_settings_confirm))
                    .setNegativeButton(getString(R.string.cancel)) { dialog, _ -> dialog.dismiss() }
                    .setPositiveButton(getString(R.string.done)) { _, _ -> resetAllSettings() }
                    .show()
            }
        }
        return true
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        when(preference.key) {
            SIDEBAR_LOCK_X -> {
                // Locking the X axis disables the X position slider; newValue isn't persisted yet, so compute directly from it
                findPreference<SeekBarPreference>(SIDEBAR_POS_X)?.isEnabled = !(newValue as Boolean)
            }
            SIDEBAR_LOCK_Y -> {
                findPreference<SeekBarPreference>(SIDEBAR_POS_Y)?.isEnabled = !(newValue as Boolean)
            }
            SHOW_FLOATING -> {
                if (newValue as Boolean) {
                    when(sp.getInt(SERVICE_TYPE, ForegroundService.SERVICE_TYPE)) {
                        KeepAliveService.SERVICE_TYPE -> {
                            if (!PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
                                Toast.makeText(requireContext(), getString(R.string.require_accessibility), Toast.LENGTH_SHORT).show()
                                startActivity(Intent(requireActivity(), PermissionActivity::class.java))
                                requireActivity().finish()
                                return false
                            }
                        }
                        ForegroundService.SERVICE_TYPE -> {
                            requireContext().startForegroundService(Intent(requireContext(), ForegroundService::class.java))
                        }
                    }
                }
            }
            NOTIFY_FREEFORM -> {
                if (newValue as Boolean && !PermissionUtils.checkNotificationListenerPermission(requireContext())) {
                    Toast.makeText(requireContext(), getString(R.string.require_notification), Toast.LENGTH_SHORT).show()
                    startActivity(Intent(requireActivity(), PermissionActivity::class.java))
                    requireActivity().finish()
                    return false
                }
            }
            HIDE_FROM_RECENT -> {
                // This switch takes effect via a task attribute, and the task attribute is read only once at task creation,
                // so right after toggling we rebuild the task with the new flag, rather than requiring "swipe MiFreeform away and re-enter".
                applyHideFromRecentImmediately(newValue as Boolean)
            }
            APP_LANGUAGE -> {
                // Language takes effect by wrapping the Context in attachBaseContext (see LocaleHelper); the context must be rebuilt to read the new value.
                // At callback time the framework hasn't persisted yet (persistInt runs only after the listener returns true), so we commit ourselves first,
                // then restart the foreground service (going through attachBaseContext again) and recreate the settings page;
                // the accessibility service is bound by the system, and its text updates only on the next rebind.
                sp.edit().putInt(APP_LANGUAGE, newValue as Int).commit()
                if (sp.getInt(SERVICE_TYPE, ForegroundService.SERVICE_TYPE) == ForegroundService.SERVICE_TYPE) {
                    requireContext().stopService(Intent(requireContext(), ForegroundService::class.java))
                    requireContext().startForegroundService(Intent(requireContext(), ForegroundService::class.java))
                }
                requireActivity().recreate()
            }
            SERVICE_TYPE -> {
                when(newValue as Int) {
                    KeepAliveService.SERVICE_TYPE -> {
                        if (PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
                            requireContext().stopService(Intent(requireContext(), ForegroundService::class.java))
                        } else {
                            accessibilityRFAR.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            Toast.makeText(requireContext(), getString(R.string.require_start_accessibility), Toast.LENGTH_SHORT).show()
                            return false
                        }
                    }
                    ForegroundService.SERVICE_TYPE -> {
                        if (PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
                            accessibilityRFAR.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            Toast.makeText(requireContext(), getString(R.string.require_stop_accessibility), Toast.LENGTH_SHORT).show()
                            return false
                        } else {
                            requireContext().startForegroundService(Intent(requireContext(), ForegroundService::class.java))
                        }
                    }
                }
            }
        }
        return true
    }

    /**
     * Makes the "hide from recents" switch take effect on the spot.
     *
     * excludeFromRecents is a task-level attribute; the system reads it from the launch intent only once,
     * at the very moment a **new task** is created—an existing task can't be changed.
     * Measured: relying on FLAG_ACTIVITY_CLEAR_TASK alone doesn't work—CLEAR_TASK means "reuse the same task in place,
     * clearing the Activities inside it"; the task itself and its original root intent remain,
     * so the newly added EXCLUDE_FROM_RECENTS is never read (logcat shows START carried 0x10008000,
     * but dumpsys activity recents still lists the task as before).
     * Here we first truly delete this app's task via finishAndRemoveTask (empty tasks get wiped from recents by the system),
     * then create a new task; only the new task decides whether to appear in recents based on the current switch state.
     * The cold-start path was already correct: SplashActivity uses taskAffinity=":splash"
     * and excludeFromRecents=true separately in the manifest, so when it launches MainActivity it's necessarily a new task,
     * and the new task's hidden attribute is exactly determined by the launch intent's flag.
     * The settings value has already been written to SharedPreferences by the Preference framework, so after rebuild we read the new state.
     */
    private fun applyHideFromRecentImmediately(hide: Boolean) {
        val appContext = requireContext().applicationContext
        var flags = Intent.FLAG_ACTIVITY_NEW_TASK
        if (hide) {
            flags = flags or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
        }
        // Wait for the switch animation to finish before rebuilding, otherwise the toggle gives no feedback;
        // use a main-thread Handler rather than view.postDelayed: the Activity is already finished, so the view is unreliable.
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                appContext.startActivity(
                    Intent(appContext, MainActivity::class.java).addFlags(flags)
                )
            }
        }, 400)
        runCatching { requireActivity().finishAndRemoveTask() }
    }

    /** Exports all settings to the location the user picked in the system file picker. */
    private fun exportSettings(uri: Uri) {
        try {
            val out = requireContext().contentResolver.openOutputStream(uri, "wt")
            if (out == null) {
                Toast.makeText(requireContext(), getString(R.string.backup_failed), Toast.LENGTH_SHORT).show()
                return
            }
            out.use {
                if (SettingsBackup.export(requireContext(), it)) {
                    Toast.makeText(requireContext(), getString(R.string.backup_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.backup_failed), Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Throwable) {
            // When the user picks a read-only location or the system denies access, contentResolver throws
            // SecurityException / FileNotFoundException; not catching it would crash directly.
            android.util.Log.w("SettingFragment", "export_failed", e)
            Toast.makeText(requireContext(), getString(R.string.backup_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /** Restores settings from the backup file the user picked in the system file picker. */
    private fun importSettings(uri: Uri) {
        try {
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                if (SettingsBackup.import(requireContext(), input)) {
                    // The settings page reads values from SharedPreferences, so after restore it must be redrawn to show new values immediately.
                    view?.post {
                        setPreferencesFromResource(R.xml.settings, null)
                        onResume()
                    }
                    Toast.makeText(requireContext(), getString(R.string.restore_success), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), getString(R.string.restore_failed), Toast.LENGTH_SHORT).show()
                }
                return
            }
            Toast.makeText(requireContext(), getString(R.string.restore_failed), Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            // When the user picks an unopenable file or lacks read permission, contentResolver throws; not catching it would crash.
            android.util.Log.w("SettingFragment", "import_failed", e)
            Toast.makeText(requireContext(), getString(R.string.restore_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * One-tap reset: clears all settings in SharedPreferences and reapplies the xml defaults.
     * The shortcut apps / notification-takeover app lists live in the Room database, not in SP, so they're unaffected and preserved;
     * version / version_privacy are also preserved, to avoid the guide / privacy page popping again on restart after reset.
     *
     * Note: don't call setPreferencesFromResource() directly inside the click callback,
     * because at that moment the RecyclerView is dispatching events, and the internal notifyDataSetChanged() would throw
     * IllegalStateException (Cannot call this method while RecyclerView is computing
     * a layout or scrolling). Use recreate() to cleanly rebuild the Activity so the
     * Fragment lifecycle runs again and listeners and the ConcatAdapter are restored correctly.
     */
    private fun resetAllSettings() {
        val keepVersion = sp.getInt("version", MiFreeform.VERSION)
        val keepPrivacy = sp.getInt("version_privacy", MiFreeform.VERSION_PRIVACY)
        sp.edit()
            .clear()
            .putInt("version", keepVersion)
            .putInt("version_privacy", keepPrivacy)
            .commit()
        // Reapply default values according to android:defaultValue
        PreferenceManager.setDefaultValues(requireContext(), MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE, R.xml.settings, true)
        Toast.makeText(requireContext(), R.string.reset_all_success, Toast.LENGTH_SHORT).show()
        // Defer the rebuild to the next frame: this call comes from the confirm dialog's button callback, when the dialog is dismissing and
        // the settings list's RecyclerView may still be finishing this event dispatch/layout. A synchronous recreate() on some
        // devices conflicts with dialog dismissal or layout and throws IllegalStateException, crashing; after post the
        // click event and dismiss are both done, and runCatching provides a fallback so a rebuild failure won't crash.
        val root = view
        if (root != null) {
            root.post { runCatching { if (isAdded) requireActivity().recreate() } }
        } else {
            runCatching { requireActivity().recreate() }
        }
    }

    /**
     * Recomputes the X/Y position sliders' range based on the current screen and handle size, eliminating the "dead values where dragging does nothing".
     *
     * The service-side geometry (identical in ForegroundService / KeepAliveService) is:
     *   x = positionX * max(0, (screenWidth - width) / 2 - offsetX)
     *   y = clamp(offsetY, -screenHeight / 2, screenHeight / 2)
     * So:
     * - Once offsetX exceeds (screenWidth - width) / 2, max(0, …) is always 0 and the handle is pinned to the screen center,
     *   dragging the slider further right does nothing;
     * - Once offsetY exceeds (screenHeight - length) / 2, the window gets clamped by the system to the top/bottom edges of the app area—also a wasted drag.
     * These two bounds depend on orientation and handle width/height; XML can only write a fixed fallback, so we recompute on entering the page,
     * on rotation, and on size changes; incidentally clamping previously stored out-of-range old values (e.g. from older versions) back into range.
     */
    private fun updateSidebarRange() {
        val widthDp = sp.getInt(SIDEBAR_WIDTH_DP, DEFAULT_SIDEBAR_WIDTH_DP)
        val lengthDp = sp.getInt(SIDEBAR_LENGTH_DP, DEFAULT_SIDEBAR_LENGTH_DP)
        val metrics = resources.displayMetrics
        val density = metrics.density
        // Keep consistent with the two services' value-fetching: configure displayMetrics as screenWidth / screenHeight per current orientation
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val screenWidth = if (portrait) min(metrics.widthPixels, metrics.heightPixels)
        else max(metrics.widthPixels, metrics.heightPixels)
        val screenHeight = if (portrait) max(metrics.widthPixels, metrics.heightPixels)
        else min(metrics.widthPixels, metrics.heightPixels)

        val widthPx = (widthDp * density).roundToInt()
        val lengthPx = (lengthDp * density).roundToInt()
        val maxXDp = ((screenWidth - widthPx) / 2f / density).roundToInt().coerceAtLeast(0)
        val maxYDp = ((screenHeight - lengthPx) / 2f / density).roundToInt().coerceAtLeast(0)

        // Set the range first, then clamp: writing value before the range updates would clamp against the old range
        findPreference<SeekBarPreference>(SIDEBAR_POS_X)?.let { pref ->
            pref.min = 0
            pref.max = maxXDp
            val clamped = pref.value.coerceIn(0, maxXDp)
            if (clamped != pref.value) pref.value = clamped
        }
        findPreference<SeekBarPreference>(SIDEBAR_POS_Y)?.let { pref ->
            pref.min = -maxYDp
            pref.max = maxYDp
            val clamped = pref.value.coerceIn(-maxYDp, maxYDp)
            if (clamped != pref.value) pref.value = clamped
        }
    }

    override fun onStart() {
        super.onStart()
        sp.registerOnSharedPreferenceChangeListener(spSyncListener)
    }

    override fun onStop() {
        sp.unregisterOnSharedPreferenceChangeListener(spSyncListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (PermissionUtils.isAccessibilitySettingsOn(requireContext())) {
            findPreference<IntegerSimpleMenuPreference>(SERVICE_TYPE)!!.setValueIndex(0)
        }
        // The home page was removed; the three cards' status refresh is moved to the settings page
        statusCards?.refresh()
        // After rotation the screen width/height swap, so the position sliders' range is recomputed accordingly
        updateSidebarRange()
    }

    companion object {
        private const val QUICK_FLOATING_APP = "quick_floating_app"
        private const val NOTIFICATION_FREEFORM_APPS = "notification_freeform_apps"
        private const val SHOW_FLOATING = "show_floating"
        private const val NOTIFY_FREEFORM = "notify_freeform"
        // Hide MiFreeform's own card from recents (key in settings.xml is hide_from_recent)
        private const val HIDE_FROM_RECENT = "hide_from_recent"
        private const val RESET_OVERLAY_SETTING = "reset_overlay_setting"
        // The one-tap reset-all-defaults item (key in settings.xml is reset_all_settings)
        private const val RESET_ALL_SETTINGS = "reset_all_settings"
        // The keys for the backup_settings / restore_settings items in settings.xml;
        // these definitions were missing here before, so findPreference<Preference>(BACKUP_SETTINGS) didn't compile.
        private const val BACKUP_SETTINGS = "backup_settings"
        private const val RESTORE_SETTINGS = "restore_settings"
        // The default file name / type when the SAF picker pops up; the user can still rename and change location in the picker.
        private const val BACKUP_FILE_NAME = "MiFreeform-settings.json"
        private const val BACKUP_MIME_TYPE = "application/json"
        private const val SERVICE_TYPE = "service_type"
        // In-app language switch item (key in settings.xml is app_language; value range see LocaleHelper)
        private const val APP_LANGUAGE = "app_language"
        private const val FREEFORM_SCALE_PORTRAIT = "freeform_scale_portrait"
        private const val FREEFORM_SCALE_LANDSCAPE = "freeform_scale_landscape"
        // Sidebar geometry settings: X/Y position sliders and X/Y axis-lock switches
        private const val SIDEBAR_POS_X = "sidebar_pos_x_dp"
        private const val SIDEBAR_POS_Y = "sidebar_pos_y_dp"
        private const val SIDEBAR_LOCK_X = "sidebar_lock_x"
        private const val SIDEBAR_LOCK_Y = "sidebar_lock_y"
        // The handle size items; the keys and default values must stay consistent with settings.xml and the getIntSp calls in both services
        private const val SIDEBAR_WIDTH_DP = "sidebar_width_dp"
        private const val SIDEBAR_LENGTH_DP = "sidebar_length_dp"
        private const val DEFAULT_SIDEBAR_WIDTH_DP = 8
        private const val DEFAULT_SIDEBAR_LENGTH_DP = 64
    }
}