package com.desk.companion.services

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import com.desk.companion.utils.PreferenceHelper

class CompanionAccessibilityService : AccessibilityService() {

    private var dpm: DevicePolicyManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val context = applicationContext

        // Only enforce while Armed and in Lockdown
        if (!PreferenceHelper.isArmed(context) || !PreferenceHelper.isLockdownActive(context)) {
            return
        }

        val pkgName = event.packageName?.toString()?.lowercase() ?: ""
        val className = event.className?.toString()?.lowercase() ?: ""

        // 1. Block Settings App
        if (pkgName.contains("com.android.settings") || pkgName.contains("com.samsung.android.settings")) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        // 2. Kill Samsung / Vivo / Stock Power Dialogs
        val isPowerMenu = pkgName.contains("globalactions") ||
                pkgName.contains("globalaction") ||
                pkgName.contains("shutdown") ||
                className.contains("globalactions") ||
                className.contains("powerdialog") ||
                className.contains("shutdown")

        if (isPowerMenu) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            try {
                dpm?.lockNow()
            } catch (_: Exception) {}
        }
    }

    override fun onInterrupt() {}
}
