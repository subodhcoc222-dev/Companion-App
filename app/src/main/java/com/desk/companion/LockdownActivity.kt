package com.desk.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.desk.companion.databinding.ActivityLockdownBinding
import com.desk.companion.services.CompanionWatchdogService
import com.desk.companion.utils.PreferenceHelper

class LockdownActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockdownBinding
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finishLockdown()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake screen up and show over Lockscreen without unlocking keyguard underneath
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        binding = ActivityLockdownBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val reason = intent.getStringExtra("reason") ?: PreferenceHelper.getLockdownReason(this)
        binding.tvLockReason.text = reason

        startAlarmSoundAndVibration()

        binding.btnUnlock.setOnClickListener {
            val enteredPin = binding.etPin.text.toString().trim()
            val masterPin = PreferenceHelper.getMasterPin(this)

            if (masterPin != null && enteredPin == masterPin) {
                CompanionWatchdogService.dismissLockdown(this)
                finishLockdown()
                Toast.makeText(this, "Alarm Disarmed Successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "INCORRECT PIN!", Toast.LENGTH_SHORT).show()
                binding.etPin.text.clear()
            }
        }

        val filter = IntentFilter("com.desk.companion.DISMISS_LOCKDOWN")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(closeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(closeReceiver, filter)
        }
    }

    private fun startAlarmSoundAndVibration() {
        try {
            val customUri = PreferenceHelper.getCustomAlarmUri(this)
            val alarmUri = if (customUri != null) {
                Uri.parse(customUri)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@LockdownActivity, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {}

        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val pattern = longArrayOf(0, 1000, 500, 1000, 500)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 1000, 500), 0)
        }
    }

    private fun finishLockdown() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            vibrator?.cancel()
        } catch (_: Exception) {}
        finish()
    }

    override fun onBackPressed() {
        // Block back press from closing the alarm
    }

    override fun onDestroy() {
        super.onDestroy()
        finishLockdown()
        try {
            unregisterReceiver(closeReceiver)
        } catch (_: Exception) {}
    }
}
