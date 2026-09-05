package com.desk.companion.services

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.desk.companion.utils.PreferenceHelper

class CompanionAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastToastTime = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Check if Desk Companion is currently ARMED
        if (!PreferenceHelper.isArmed(this)) return

        val pkgName = event.packageName?.toString() ?: ""
        val className = event.className?.toString() ?: ""

        // Samsung One UI Power Menu packages & dialogs
        val isSamsungGlobalActions = pkgName == "com.samsung.android.globalactions" ||
                pkgName == "android" && className.contains("GlobalActions", ignoreCase = true) ||
                pkgName == "com.android.systemui" && className.contains("GlobalActions", ignoreCase = true)

        if (isSamsungGlobalActions) {
            // 1. Instantly dismiss the Power Menu
            performGlobalAction(GLOBAL_ACTION_BACK)

            @Suppress("DEPRECATION")
            sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))

            // 2. Alert the user
            val now = System.currentTimeMillis()
            if (now - lastToastTime > 2000) {
                lastToastTime = now
                mainHandler.post {
                    Toast.makeText(
                        applicationContext,
                        "🛡️ Power Off is Blocked while Desk Sentry is Armed!",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    override fun onInterrupt() {}
}
