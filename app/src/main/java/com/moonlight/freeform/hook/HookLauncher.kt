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

    //Optimization: the way to open Mi-Freeform from Recents changed to "after tapping the app icon"
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
                // Both the broadcast action and the receiving package come from BuildConfig:
                // they must not be hardcoded, otherwise after a package-name change it goes to the wrong package.
                //
                // An explicit component is required; setPackage alone is not enough:
                // an implicit broadcast (action + setPackage only) is **not** delivered after Android 8 to receivers
                // statically declared in the manifest, and even --include-stopped-packages does not save it in practice.
                // So while "the process is still alive" it only worked via the receiver ForegroundService registers at runtime,
                // and once the process was swiped away / force-stopped, tapping Mi-Freeform in Recents was completely silent
                // (measured: zero process, zero window, zero log). Switching to an explicit component lets the same broadcast
                // pull the process up from scratch. The class name uses StartFreeformReceiver::class.java.name so a Java package rename won't point to the wrong place.
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
