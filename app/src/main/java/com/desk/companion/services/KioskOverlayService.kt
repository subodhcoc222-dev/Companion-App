package com.desk.companion.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.cardview.widget.CardView
import com.desk.companion.R
import com.desk.companion.utils.PreferenceHelper
import com.desk.companion.utils.SoundManager

class KioskOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    companion object {
        private var isCallActive = false
        private var instance: KioskOverlayService? = null

        fun setCallInProgress(context: Context, inProgress: Boolean) {
            isCallActive = inProgress
            instance?.updateCallVisibility()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showOverlay()
    }

    private fun showOverlay() {
        if (overlayView != null || !Settings.canDrawOverlays(this)) return

        val inflater = LayoutInflater.from(this)
        overlayView = inflater.inflate(R.layout.overlay_lockdown, null)

        val isNight = PreferenceHelper.isNightQuietTime(this)

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            if (isNight) {
                screenBrightness = 0.01f
            }
        }

        val root = overlayView?.findViewById<View>(R.id.overlayRoot)
        val tvStatus = overlayView?.findViewById<TextView>(R.id.tvLockStatus)
        val cardReason = overlayView?.findViewById<CardView>(R.id.cardReason)
        val tvReason = overlayView?.findViewById<TextView>(R.id.tvBreachReason)
        val etPin = overlayView?.findViewById<EditText>(R.id.etEmergencyPin)
        val btnUnlock = overlayView?.findViewById<Button>(R.id.btnUnlockEmergency)

        tvReason?.text = PreferenceHelper.getLockdownReason(this)

        if (isNight) {
            root?.setBackgroundColor(Color.BLACK)
            cardReason?.visibility = View.GONE
            tvStatus?.text = "🔒 SENTRY OFFLINE (NIGHT STEALTH LOCK)"
            tvStatus?.setTextColor(Color.parseColor("#475569"))
        } else {
            SoundManager.startAlertSound(this)
        }

        btnUnlock?.setOnClickListener {
            val enteredPin = etPin?.text?.toString()?.trim() ?: ""
            val masterPin = PreferenceHelper.getMasterPin(this)

            if (masterPin != null && enteredPin == masterPin) {
                Toast.makeText(this, "Master PIN Accepted. Lockdown Dismissed.", Toast.LENGTH_SHORT).show()
                CompanionWatchdogService.dismissLockdown(this)
            } else {
                Toast.makeText(this, "Incorrect Master PIN!", Toast.LENGTH_SHORT).show()
                etPin?.text?.clear()
            }
        }

        try {
            windowManager?.addView(overlayView, layoutParams)
        } catch (_: Exception) {}

        updateCallVisibility()
    }

    fun updateCallVisibility() {
        overlayView?.post {
            overlayView?.visibility = if (isCallActive) View.GONE else View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        SoundManager.stopAlertSound()
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (_: Exception) {}
            overlayView = null
        }
        instance = null
    }
}
