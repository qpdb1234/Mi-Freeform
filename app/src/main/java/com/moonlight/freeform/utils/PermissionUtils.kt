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
     * 「缺悬浮窗权限」这条诊断记录的 tag。写记录与读记录（常驻通知要不要给「去授权」
     * 按钮）两边共用同一个常量，避免字符串写错就不动了。
     */
    const val DIAG_TAG_OVERLAY_MISSING = "overlay_permission_missing"

    /**
     * 从哪个版本开始「代码自己跳授权页」会把全进程悬浮窗毒掉。
     *
     * 实测造毒的是 HyperOS 14（API 34）；`mForceHideNonSystemOverlayWindow` 字段虽然从
     * Android 12 就有，但 12 上重新授权会清掉标记（诊断报告 §0.4）。34 以下保留旧跳转。
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
     * 这次有资格用哪种非系统悬浮窗类型，返回 null 表示**两种都不要试**。
     *
     * HyperOS 14（Android 14）实测：在没资格的状态下调一次 `addView`，会被系统记成一次
     * 「被拒的非系统悬浮窗请求」，而该进程之后创建的、以及已经存在的**所有**非系统悬浮窗
     * 都会被永久置为 `mForceHideNonSystemOverlayWindow=true`——重新授权不解除，
     * 只有重启进程才恢复。所以「先试一种、抛异常再换下一种」在这个系统上是致命的写法，
     * 资格必须在 addView 之前裁决清楚。
     *
     * 优先级沿用旧行为：无障碍服务确实开着时用 ACCESSIBILITY_OVERLAY（能盖在系统界面之上），
     * 否则用普通 APPLICATION_OVERLAY。
     */
    fun pickOverlayWindowType(context: Context): Int? = when {
        isAccessibilitySettingsOn(context) -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        checkOverlayPermission(context) -> WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else -> null
    }

    /**
     * 常驻通知这条通道此刻用户到底看不看得见。
     *
     * 本应用 targetSdk 32，Android 13+ 上 POST_NOTIFICATIONS 是安装时自动授予的，所以正常情况下
     * 不需要运行时申请；但用户可以事后在系统设置里把米窗的通知整体关掉（HyperOS 还有“智能管控”
     * 会自行关），那时常驻通知连同上面的「去授权」按钮一起消失。
     * 只告知不跳系统设置页，是因为代码自己跳那一次在 Android 14 上会把全进程悬浮窗隐藏掉（见上面注释），
     * 而这里只读一个本地状态，不涉及任何跳转。
     */
    fun areNotificationsVisible(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * 缺悬浮窗权限时的统一告知：落一条可回放记录 + 弹 Toast。
     *
     * Android 14（API 34）及以上**绝不自动跳系统设置页**，理由如下；34 以下沿用旧版的
     * 一键直达（见 [openOverlayPermissionPage]）。
     *
     * 为什么不跳（HyperOS 14 实测，诊断报告 §1.12）：走过一次「开窗被拒」的分支之后，
     * 分支末尾那次 `startActivity(ACTION_MANAGE_OVERLAY_PERMISSION)` 会让本进程之后创建的
     * 所有非系统悬浮窗被置为 `mForceHideNonSystemOverlayWindow=true`——小窗、侧边栏、面板
     * 一起消失，重新授权不解除，只能重启进程甚至重启手机。真机定量：保留这一跳 4/4 造毒，
     * 只去掉这一跳（其余照旧）0/4 造毒。反过来**单独**跳这一个设置页反倒不造毒，
     * 所以授权入口照旧保留，只是改由用户主动点：常驻通知上的「去授权」-> [PermissionActivity]
     * 权限页 -> 用户点「悬浮窗」那张卡片。
     *
     * 为什么旧系统要保留那一跳：造毒的那个字段是 Android 12 才引入的，而 12 上重新
     * 授权就会把标记清掉（实测 10/12 不会进入该状态），所以 ≤33 上这一跳只有便利没有代价；
     * 为了 14 的修复把它从 10/12 用户手里拿掉是不划算的行为回退。
     *
     * 授权指引写在记录正文里，因为常驻通知显示的正是最近一条记录（F3）。
     * 但这条通道本身可能已经断了：用户把米窗的通知整体关掉时，[areNotificationsVisible] 读到 false，
     * 「点通知上的去授权」就成了死指引，所以这时换一条指向应用图标的说法。
     *
     * @param scene 面向用户的场景描述，例如「侧边栏把手挂不出来」。
     *              调用方必须传 `context.getString(R.string.scene_*)`，不能在这里写中文字面量——
     *              这段正文会直接出现在用户的通知里。
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
     * 跳系统「显示在其他应用界面上层」设置页。
     *
     * 只能由 34 以下的 [guideToGrantOverlayPermission] 或用户主动点击调用：Android 14 上
     * 代码自己跳这一次会把本进程之后的所有悬浮窗毒掉（见上面的注释）。
     * 部分定制 ROM 没有这个页面，所以自带 try/catch，不能把异常抛回调用方的窗口创建流程里。
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