package com.desk.companion.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.desk.companion.utils.PreferenceHelper

class CompanionAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val context = applicationContext

        // Enforce blocks only when Armed and Lockdown is Active
        if (!PreferenceHelper.isArmed(context) || !PreferenceHelper.isLockdownActive(context)) {
            return
        }

        val pkgName = event.packageName?.toString() ?: ""
        val className = event.className?.toString() ?: ""

        // 1. Block Settings App
        if (pkgName.contains("com.android.settings") || pkgName.contains("com.samsung.android.settings")) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            return
        }

        // 2. Intercept Samsung, Vivo & Stock Android Power Menus
        val isPowerPackage = pkgName.contains("globalactions", ignoreCase = true) ||
                pkgName.contains("globalaction", ignoreCase = true) ||
                pkgName.contains("shutdown", ignoreCase = true) ||
                pkgName.contains("power", ignoreCase = true)

        val isPowerClass = className.contains("GlobalActions", ignoreCase = true) ||
                className.contains("PowerDialog", ignoreCase = true) ||
                className.contains("ShutdownActivity", ignoreCase = true)

        if (isPowerPackage || isPowerClass || inspectNodeForPowerKeywords(rootInActiveWindow)) {
            // Instantly dismiss the restart/power menu
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)

            // Re-assert Overlay on top
            val overlayIntent = Intent(context, KioskOverlayService::class.java)
            context.startService(overlayIntent)
        }
    }

    private fun inspectNodeForPowerKeywords(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (text.contains("power off") || text.contains("restart") || text.contains("reboot") ||
            text.contains("switch off") || text.contains("emergency mode") ||
            desc.contains("power off") || desc.contains("restart")) {
            return true
        }

        for (i in 0 until node.childCount) {
            if (inspectNodeForPowerKeywords(node.getChild(i))) return true
        }
        return false
    }

    override fun onInterrupt() {}
}
