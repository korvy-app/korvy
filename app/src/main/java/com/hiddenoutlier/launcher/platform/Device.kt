package com.hiddenoutlier.launcher.platform

import android.app.ActivityManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.hiddenoutlier.launcher.core.DeviceSpecs
import com.hiddenoutlier.launcher.core.DeviceTiers
import com.hiddenoutlier.launcher.core.Tier

object Device {
    fun specs(context: Context): DeviceSpecs {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return DeviceSpecs(
            totalRamMb = mem.totalMem / (1024 * 1024),
            cores = Runtime.getRuntime().availableProcessors(),
            sdk = Build.VERSION.SDK_INT,
            lowRamDevice = am.isLowRamDevice,
            performanceClass = if (Build.VERSION.SDK_INT >= 31) Build.VERSION.MEDIA_PERFORMANCE_CLASS else 0,
        )
    }

    fun tier(context: Context): Tier = DeviceTiers.classify(specs(context))

    fun isDefaultHome(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = context.getSystemService(RoleManager::class.java)
            return rm.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val home = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        return home?.activityInfo?.packageName == context.packageName
    }

    /** The system dialog (Android 10+) or the Default apps screen to make this the home app. */
    fun homeRoleIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= 29) context.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME)
        else Intent(Settings.ACTION_HOME_SETTINGS)
}
