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
    private var alertJob: Job? = null
    private var isPlaying = false

    fun startAlertSound(context: Context) {
        if (isPlaying) return
        isPlaying = true

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxAlarmVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)

        // 1. Force hardware alarm stream to 100% Maximum Volume
        audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarmVolume, 0)

        val customUriStr = PreferenceHelper.getCustomAlarmUri(context)

        if (!customUriStr.isNullOrEmpty()) {
            // Play Custom System Alarm at Forced 100% Volume
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
                    setVolume(1.0f, 1.0f) // 100% Speaker Output
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                startMaxBeepLoop(audioManager, maxAlarmVolume)
            }
        } else {
            // Play Siren Pulse at Forced 100% Volume
            startMaxBeepLoop(audioManager, maxAlarmVolume)
        }
    }

    private fun startMaxBeepLoop(audioManager: AudioManager, maxVol: Int) {
        alertJob = CoroutineScope(Dispatchers.Default).launch {
            val toneGen = ToneGenerator(AudioManager.STREAM_ALARM, 100) // 100% Peak Output
            try {
                while (isActive) {
                    // Continuous volume lock: forces volume back to MAX if someone presses volume down
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
                    toneGen.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 400) // Piercing Siren Pulse
                    delay(800)
                }
            } catch (_: Exception) {
            } finally {
                toneGen.release()
            }
        }
    }

    fun stopAlertSound() {
        isPlaying = false
        alertJob?.cancel()
        alertJob = null

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
    }
}
