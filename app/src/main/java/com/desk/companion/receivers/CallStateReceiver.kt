package com.desk.companion.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import com.desk.companion.services.KioskOverlayService

class CallStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
            when (stateStr) {
                TelephonyManager.EXTRA_STATE_RINGING, TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    // Incoming or active emergency call: temporarily pause overlay display
                    KioskOverlayService.setCallInProgress(context, true)
                }
                TelephonyManager.EXTRA_STATE_IDLE -> {
                    // Call ended: restore overlay enforcement immediately if locked down
                    KioskOverlayService.setCallInProgress(context, false)
                }
            }
        }
    }
}
