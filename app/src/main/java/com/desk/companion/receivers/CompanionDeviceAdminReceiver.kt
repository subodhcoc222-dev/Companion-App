package com.desk.companion.receivers

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

class CompanionDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        enableDeviceOwnerPolicies(context)
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        enableDeviceOwnerPolicies(context)
    }

    private fun enableDeviceOwnerPolicies(context: Context) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(context, CompanionDeviceAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(context.packageName)) {
            // Prevent unauthorized uninstallation of the companion app
            dpm.setUninstallBlocked(adminComponent, context.packageName, true)
        }
    }
}
