package com.desk.companion.services

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.desk.companion.utils.PreferenceHelper

class CompanionAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val context = applicationContext

        // Only enforce blocks when Master Switch is ARMED and Lockdown is ACTIVE
        if (!PreferenceHelper.isArmed(context) || !PreferenceHelper.isLockdownActive(context)) {
            return
        }

        val pkgName = event.packageName?.toString() ?: ""
        val className = event.className?.toString() ?: ""

        // 1. Block Settings App access (Prevents disabling Accessibility or App Force Stop)
        if (pkgName.contains("com.android.settings") || pkgName.contains("com.samsung.android.settings")) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        // 2. Intercept Power Menu (Prevents turning off or restarting the phone during lockdown)
        if (className.contains("GlobalActionsDialog", ignoreCase = true) ||
            className.contains("PowerDialog", ignoreCase = true) ||
            pkgName.contains("globalactions", ignoreCase = true)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    override fun onInterrupt() {}
}
