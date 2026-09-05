package com.desk.companion.receivers

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class CompanionDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        enableKioskFeatures(context)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        enableKioskFeatures(context)
    }

    private fun enableKioskFeatures(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, CompanionDeviceAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(context.packageName)) {
            // Companion app ko LockTask (Kiosk) mode me enter hone ki permission grant karein
            dpm.setLockTaskPackages(adminComponent, arrayOf(context.packageName))

            // LOCK_TASK_FEATURE_NONE: Power menu (Global Actions), Home, Notifications aur Recents ko system level par block karta hai
            dpm.setLockTaskFeatures(adminComponent, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
        }
    }
}
