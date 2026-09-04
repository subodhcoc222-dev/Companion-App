package com.desk.companion.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import kotlinx.coroutines.*

object SoundManager {
    private var mediaPlayer: MediaPlayer? = null
    private var beepJob: Job? = null
    private var isPlaying = false

    fun startAlertSound(context: Context) {
        if (isPlaying) return
        isPlaying = true

        val customUriStr = PreferenceHelper.getCustomAlarmUri(context)

        if (!customUriStr.isNullOrEmpty()) {
            // Play user-selected Custom Ringtone
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(context, Uri.parse(customUriStr))
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
            } catch (e: Exception) {
                // Fallback to default beep if media fails
                startPulseBeep()
            }
        } else {
            // Play default medium frequency alert pulse
            startPulseBeep()
        }
    }

    private fun startPulseBeep() {
        beepJob = CoroutineScope(Dispatchers.Default).launch {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 75) // 75% Balanced Volume
            try {
                while (isActive) {
                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 250) // 250ms Alert Pulse
                    delay(750) // 750ms Rest Interval
                }
            } catch (_: Exception) {
            } finally {
                toneGen.release()
            }
        }
    }

    fun stopAlertSound() {
        isPlaying = false
        beepJob?.cancel()
        beepJob = null

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }
}
