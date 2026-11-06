package com.moonlight.freeform.utils

import android.app.ActivityManager
import android.content.Context

/**
 * @date 2021/2/1
 * Service-related utility class
 */
object ServiceUtils {
    /**
     * Whether a given service is currently running.
     *
     * @param mContext
     * @param serviceName package name + service class name (e.g. net.loonggg.testbackstage.TestService)
     * @return true if it is running, false otherwise
     */
    fun isServiceWork(mContext: Context, serviceName: String): Boolean {
        var isWork = false
        val myAM = mContext
                .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val myList: List<ActivityManager.RunningServiceInfo> = myAM.getRunningServices(40)
        if (myList.isEmpty()) {
            return false
        }
        for (i in myList.indices) {
            val mName: String = myList[i].service.className
            myList[i].service.className
            if (mName == serviceName) {
                isWork = true
                break
            }
        }
        return isWork
    }
}