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
 * 开小窗请求的接入口。
 *
 * 发送方有两个：HookLauncher（最近任务菜单）与外部广播（adb / 第三方调用）。
 * 以前本类只在 ForegroundService / KeepAliveService 的 onCreate 里运行时注册，
 * manifest 声明是被注释掉的 —— 于是进程被停或被系统半拉起时**根本没有接收器**，
 * 广播在投递阶段就被丢弃（result=0、零窗口、零日志），表现为「点了没反应」。
 * 现在 manifest 静态声明与本运行时注册并存，所以：
 * 1. 同一条广播会被投两次，必须去重（见 companion 里的 lastHandledKey）；
 * 2. 静态声明会把进程从零拉起来，此时 ControlService 的 binder 还没到位，
 *    必须等 binder 再建窗（见 [awaitControlService]），否则又走进「Shizuku 未绑定」那条静默分支。
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

        // 静态 + 运行时双注册：两次投递的处理必须合成一次，否则会建两个虚拟屏。
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
        // 进程被广播冷拉起时前台服务不在，把手/侧边栏面板都不在，用户会以为「只出来一个窗口」。
        // 这里补一次服务启动；无障碍（KeepAlive）模式下不能起前台服务，两者会互相顶掉。
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
     * 等 ControlService 的 binder 到位再建窗，最长 [BIND_TIMEOUT_MS]。
     *
     * [MiFreeform] 在 Application.onCreate 里挂了 sticky 的 binderReceivedListener，
     * 会异步 bindUserService；冷拉起时这一步要几十毫秒到几秒。直接 new FreeformView
     * 的话它的 init 判定为「未绑定」，只会弹个 Toast —— 又变成静默失败。
     *
     * 只发起一次绑定：Shizuku 的 binder 到位时那个 sticky 监听器会自己 bindUserService，
     * 本方法只需要轮询 pingBinder。以前这里是每 200ms 重发一次 initShizuku()，
     * 而 Shizuku 没跑时每次绑定都会抛异常并写一条诊断记录（实测 8 秒 41 条），
     * 通知跟着刷四十多次——重复尝试对“到位”这件事没有任何帮助。
     */
    private fun awaitControlService(context: Context, packageName: String, activityName: String, userId: Int, waitedMs: Long) {
        if (MiFreeform.me?.getControlService()?.asBinder()?.pingBinder() == true) {
            if (waitedMs > 0) Log.i(TAG, "control_service_ready after=${waitedMs}ms pkg=$packageName")
            createFreeform(context, packageName, activityName, userId)
            return
        }
        if (waitedMs >= BIND_TIMEOUT_MS) {
            // 到点仍未就绪：交给 FreeformView 的未绑定分支处理（它会记录并提示 Shizuku 没跑），
            // 这里只补一条「我们确实等过」的现场，便于区分「没收到广播」和「收到但起不来」。
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

    companion object {
        private const val TAG = "StartFreeformReceiver"
        // 双投递去重窗口：同一条广播的两次投递在同一毫秒级队列里，1.5 秒足够覆盖调度抖动。
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
