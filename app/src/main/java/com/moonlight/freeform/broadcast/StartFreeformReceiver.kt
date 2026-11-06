package com.moonlight.freeform.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.moonlight.freeform.R
import com.moonlight.freeform.app.MiFreeform
import com.moonlight.freeform.service.ForegroundService
import com.moonlight.freeform.ui.freeform.FreeformConfig
import com.moonlight.freeform.ui.freeform.FreeformHelper
import com.moonlight.freeform.ui.freeform.FreeformView
import com.moonlight.freeform.utils.DiagnosticsLog

/**
 * Entry point for freeform-start requests.
 *
 * There are two senders: HookLauncher (recent-tasks menu) and external broadcasts (adb / third-party calls).
 * Previously this class was only registered at runtime in the onCreate of ForegroundService / KeepAliveService,
 * and the manifest declaration was commented out—so when the process was stopped or half-resurrected by the system
 * there was **no receiver at all**, and the broadcast was dropped at the delivery stage (result=0, zero windows, zero logs),
 * appearing as "tapping does nothing". Now the static manifest declaration and the runtime registration coexist, so:
 * 1. The same broadcast is delivered twice and must be deduplicated (see lastHandledKey in the companion);
 * 2. The static declaration may cold-start the process from scratch, at which point ControlService's binder is not ready,
 *    so we must wait for the binder before creating the window (see [awaitControlService]), otherwise it falls into the silent "Shizuku not bound" branch again.
 */
class StartFreeformReceiver : BroadcastReceiver() {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra("packageName")
        val activityName = intent.getStringExtra("activityName")
        val userId = intent.getIntExtra("userId", 0)
        val extras = intent.getStringExtra("extras")

        if (packageName == null || activityName == null) {
            Log.w(TAG, "start_freeform_rejected missing_extras extras=$extras")
            DiagnosticsLog.recordRes(context, "start_freeform_rejected", R.string.diag_start_freeform_rejected)
            return
        }

        // Static + runtime double registration: the two deliveries must be merged into one handling, otherwise two virtual displays would be created.
        val key = "$packageName/$activityName/$userId"
        synchronized(COMPONENT_LOCK) {
            val now = SystemClock.uptimeMillis()
            if (key == lastHandledKey && now - lastHandledUptime < DEDUPE_WINDOW_MS) {
                Log.d(TAG, "start_freeform_dedupe_skip $key")
                return
            }
            lastHandledKey = key
            lastHandledUptime = now
        }

        val appContext = context.applicationContext ?: context
        // When the process is cold-started by the broadcast, the foreground service is absent—so are the handle and the sidebar panel—and the user thinks "only one window appeared".
        // We start the service once here; in accessibility (KeepAlive) mode the foreground service must not be started, as the two would override each other.
        val sp = appContext.getSharedPreferences(MiFreeform.APP_SETTINGS_NAME, Context.MODE_PRIVATE)
        val foregroundMode = sp.getInt("service_type", ForegroundService.SERVICE_TYPE) == ForegroundService.SERVICE_TYPE
        if (foregroundMode && !ForegroundService.isAlive()) {
            Log.i(TAG, "start_freeform_cold_start pkg=$packageName")
            DiagnosticsLog.recordRes(appContext, "receiver_cold_start", R.string.diag_receiver_cold_start, packageName)
            try {
                appContext.startForegroundService(Intent(appContext, ForegroundService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "start_foreground_service_failed pkg=$packageName", e)
            }
        }

        awaitControlService(appContext, packageName, activityName, userId, 0L)
    }

    /**
     * Waits for ControlService's binder to be ready before creating the window, up to [BIND_TIMEOUT_MS].
     *
     * [MiFreeform] registers a sticky binderReceivedListener in Application.onCreate,
     * which asynchronously calls bindUserService; on a cold start this takes anywhere from tens of ms to seconds.
     * If we directly `new FreeformView`, its init judges "not bound" and only pops a Toast—again a silent failure.
     *
     * We initiate binding only once: when Shizuku's binder arrives, that sticky listener calls bindUserService itself,
     * so this method only needs to poll pingBinder. Previously it re-sent initShizuku() every 200ms,
     * and when Shizuku wasn't running each bind attempt threw an exception and wrote a diagnostic record (measured: 41 records in 8s),
     * firing notifications forty-some times—repeated attempts do nothing to help things "arrive".
     */
    private fun awaitControlService(context: Context, packageName: String, activityName: String, userId: Int, waitedMs: Long) {
        if (MiFreeform.me?.getControlService()?.asBinder()?.pingBinder() == true) {
            if (waitedMs > 0) Log.i(TAG, "control_service_ready after=${waitedMs}ms pkg=$packageName")
            createFreeform(context, packageName, activityName, userId)
            return
        }
        if (waitedMs >= BIND_TIMEOUT_MS) {
            // Still not ready at the deadline: let FreeformView's not-bound branch handle it (it logs and hints that Shizuku isn't running);
            // here we only add a "we really did wait" record, to distinguish "broadcast never received" from "received but couldn't start".
            Log.w(TAG, "control_service_timeout waited=${waitedMs}ms pkg=$packageName")
            DiagnosticsLog.recordRes(context, "receiver_bind_timeout", R.string.diag_receiver_bind_timeout, packageName)
            createFreeform(context, packageName, activityName, userId)
            return
        }
        if (waitedMs == 0L) {
            MiFreeform.me?.initShizuku()
        }
        mainHandler.postDelayed({ awaitControlService(context, packageName, activityName, userId, waitedMs + POLL_INTERVAL_MS) }, POLL_INTERVAL_MS)
    }

    private fun createFreeform(context: Context, packageName: String, activityName: String, userId: Int) {
        // When the app already has a freeform, destroy the old instance first
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

    companion object {
        private const val TAG = "StartFreeformReceiver"
        // Double-delivery dedup window: the two deliveries of one broadcast land in the same ms-level queue, and 1.5s is enough to cover scheduling jitter.
        private const val DEDUPE_WINDOW_MS = 1500L
        private const val POLL_INTERVAL_MS = 200L
        private const val BIND_TIMEOUT_MS = 8000L

        private val COMPONENT_LOCK = Any()

        @Volatile
        private var lastHandledKey: String? = null

        @Volatile
        private var lastHandledUptime = 0L
    }
}
