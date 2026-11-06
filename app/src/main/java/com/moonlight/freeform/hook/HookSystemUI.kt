package com.moonlight.freeform.hook

import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * @author sunshine
 * @date 2021/2/28
 * After investigation, rotating the screen inside a freeform crashed SystemUI because the set method on DisplayLayout was called with null; so we save the previous arguments and, when we hit a null, feed the saved one back in.
 */
class HookSystemUI : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        //Only present on Android 11
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && lpparam?.packageName == "com.android.systemui") {
            val clazz = XposedHelpers.findClass("com.android.systemui.wm.DisplayLayout", lpparam.classLoader)
            var lastObj: Any? = null
            XposedBridge.hookAllMethods(clazz, "set", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam?) {
                    if (param != null) {
                        val obj = param.args[0]
                        if (obj != null) {
                            lastObj = obj
                        } else {
                            param.args[0] = lastObj
                        }
                    }
                }
            })
        }
    }
}