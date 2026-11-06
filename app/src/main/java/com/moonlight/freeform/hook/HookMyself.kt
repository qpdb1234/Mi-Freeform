package com.moonlight.freeform.hook

import com.moonlight.freeform.hook.utils.XLog
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookMyself : IXposedHookLoadPackage {
    override fun handleLoadPackage(p0: XC_LoadPackage.LoadPackageParam) {
        // Keep it in sync with applicationId: use BuildConfig instead of a hardcoded string,
        // otherwise after a package-name change this never matches and the Xposed self-check always fails.
        if (p0.packageName == com.moonlight.freeform.BuildConfig.APPLICATION_ID) {
//            XposedHelpers.findAndHookMethod(
//                "com.moonlight.freeform.ui.permission.PermissionActivity",
//                p0.classLoader,
//                "checkXposedPermission",
//                Boolean::class.javaPrimitiveType,
//                object : XC_MethodHook() {
//                    override fun beforeHookedMethod(param: MethodHookParam) {
//                        param.args[0] = true
//                    }
//                }
//            )
//            XposedHelpers.findAndHookMethod(
//                "com.moonlight.freeform.ui.main.HomeFragment",
//                p0.classLoader,
//                "checkXposedPermission",
//                Boolean::class.javaPrimitiveType,
//                object : XC_MethodHook() {
//                    override fun beforeHookedMethod(param: MethodHookParam) {
//                        param.args[0] = true
//                    }
//                }
//            )
            XposedHelpers.findAndHookMethod(
                "com.moonlight.freeform.hook.utils.HookTest",
                p0.classLoader,
                "checkXposed",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = true
                    }
                }
            )
        }
    }


}
