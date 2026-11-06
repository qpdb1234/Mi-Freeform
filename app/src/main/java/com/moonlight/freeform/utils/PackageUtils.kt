package com.moonlight.freeform.utils

import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserHandle
import androidx.annotation.RequiresApi


/**
 * @author sunshine
 * @date 2021/3/22
 * Package-related helper functions
 */
object PackageUtils {
    
    //Whether this app is installed
    fun hasInstallThisPackage(packageName: String, packageManager: PackageManager): Boolean {
        return try {
            packageManager.getApplicationInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    //Whether a given user has this app installed
    fun hasInstallThisPackageWithUserId(
        packageName: String,
        launcherApps: LauncherApps,
        userHandle: UserHandle
    ): Boolean {
        return try {
            launcherApps.getApplicationInfo(packageName, 0, userHandle)
            true
        } catch (e: Exception) {
            false
        }
    }
}