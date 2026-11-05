package com.moonlight.freeform.hook

import android.annotation.SuppressLint
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.moonlight.freeform.R
import com.moonlight.freeform.app.MiFreeform
import com.moonlight.freeform.broadcast.StartFreeformReceiver
import com.moonlight.freeform.hook.utils.XLog
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookLauncher : IXposedHookLoadPackage {

    private var mUserContext: Context? = null

    override fun handleLoadPackage(param: XC_LoadPackage.LoadPackageParam) {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            hookLauncherAfterQ(param.classLoader)
        }
    }

    //优化 多任务打开米窗的方式更改为点击应用图标后发现
    private fun hookLauncherAfterQ(classLoader: ClassLoader) {
        val taskMenuViewClass = XposedHelpers.findClass("com.android.quickstep.views.TaskMenuView", classLoader)

        XposedBridge.hookAllMethods(
            taskMenuViewClass,
            "addMenuOptions",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val taskMenuViewObj = param.thisObject as ViewGroup
                    val mOptionLayout = XposedHelpers.getObjectField(taskMenuViewObj, "mOptionLayout") as LinearLayout

                    val menuOptionView = LayoutInflater.from(getUserContext()).inflate(R.layout.task_view_menu_option, taskMenuViewObj, false)
                    mOptionLayout.addView(menuOptionView)

                    addFreeformFunctionAfterQ(taskMenuViewObj, menuOptionView)
                }
            }
        )
    }

    private fun addFreeformFunctionAfterQ(paramObj: Any, menuOptionView: View) {
        try {
            val taskView = XposedHelpers.getObjectField(paramObj, "mTaskView") as FrameLayout
            val taskObj = XposedHelpers.callMethod(taskView, "getTask")
            val taskKeyObj = XposedHelpers.getObjectField(taskObj, "key")
            val userId = XposedHelpers.getIntField(taskKeyObj, "userId")
            val topComponent = XposedHelpers.callMethod(taskObj, "getTopComponent") as ComponentName

            menuOptionView.setOnClickListener {
                // 广播 action 与接收方所在包都取自 BuildConfig：
                // 不能写死字符串，否则改包名后会发到错误的包去。
                //
                // 必须写显式组件，不能只靠 setPackage：
                // 隐式广播（只有 action + setPackage）在 Android 8 以后**不会**投给 manifest
                // 里静态声明的接收器，实测连 --include-stopped-packages 也救不回来。
                // 于是「进程还活着」时它靠 ForegroundService 运行时注册的那个接收器才生效，
                // 进程一旦被划掉/force-stop，最近任务里点米窗就是彻底静默无反应（实测：
                // 零进程、零窗口、零日志）。改成显式组件后同一发广播能把进程从零拉起来。
                // 类名用 StartFreeformReceiver::class.java.name，这样重命名 Java 包时不会指错。
                val intent = Intent("${MiFreeform.PACKAGE_NAME}.start_freeform").apply {
                    component = ComponentName(
                        MiFreeform.PACKAGE_NAME,
                        StartFreeformReceiver::class.java.name
                    )
                    putExtra("packageName", topComponent.packageName)
                    putExtra("activityName", topComponent.className)
                    putExtra("userId", userId)
                }
                taskView.context.sendBroadcast(intent)
            }
        } catch (e: Exception) {
            XLog.d("HookLauncher $e")
        }
    }

    private fun getUserContext(): Context {
        return if (null != mUserContext) mUserContext!!
        else {
            val activityThread = Class.forName("android.app.ActivityThread")
            val currentActivityThread = activityThread.getMethod("currentActivityThread").invoke(null)
            val application = activityThread.getMethod("getApplication").invoke(currentActivityThread) as Application
            mUserContext = application.createPackageContext(MiFreeform.PACKAGE_NAME, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
            mUserContext!!
        }
    }
}
