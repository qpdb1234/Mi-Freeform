package com.moonlight.freeform.hook

import com.moonlight.freeform.hook.utils.XLog
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookMyself : IXposedHookLoadPackage {
    override fun handleLoadPackage(p0: XC_LoadPackage.LoadPackageParam) {
        // 与 applicationId 保持一致：用 BuildConfig 而不是写死字符串，
        // 否则改了包名并存安装时这里永远匹配不上，Xposed 自检会一直失败。
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
