package com.desk.companion.utils

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

object PreferenceHelper {
    private const val PREF_NAME = "desk_companion_secure_prefs"

    private const val KEY_ARMED = "is_armed"
    private const val KEY_MASTER_PIN = "master_pin"
    private const val KEY_PAIRED_DEVICE_ID = "paired_device_id"
    private const val KEY_LOCKDOWN_ACTIVE = "is_lockdown_active"
    private const val KEY_LOCKDOWN_REASON = "lockdown_reason"
    private const val KEY_CUSTOM_ALARM_URI = "custom_alarm_uri"
    private const val KEY_CUSTOM_ALARM_TITLE = "custom_alarm_title"
    private const val KEY_FORCE_24_7 = "force_24_7_lockdown"

    // Night Quiet Hours Keys
    private const val KEY_NIGHT_QUIET_ENABLED = "night_quiet_enabled"
    private const val KEY_NIGHT_START_HOUR = "night_start_hour"
    private const val KEY_NIGHT_START_MINUTE = "night_start_minute"
    private const val KEY_NIGHT_END_HOUR = "night_end_hour"
    private const val KEY_NIGHT_END_MINUTE = "night_end_minute"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isArmed(context: Context): Boolean = getPrefs(context).getBoolean(KEY_ARMED, false)
    fun setArmed(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean(KEY_ARMED, value).apply()

    fun getMasterPin(context: Context): String? = getPrefs(context).getString(KEY_MASTER_PIN, null)
    fun setMasterPin(context: Context, pin: String) = getPrefs(context).edit().putString(KEY_MASTER_PIN, pin).apply()

    fun getPairedDeviceId(context: Context): String = getPrefs(context).getString(KEY_PAIRED_DEVICE_ID, "") ?: ""
    fun setPairedDeviceId(context: Context, id: String) = getPrefs(context).edit().putString(KEY_PAIRED_DEVICE_ID, id).apply()

    fun isLockdownActive(context: Context): Boolean = getPrefs(context).getBoolean(KEY_LOCKDOWN_ACTIVE, false)
    fun setLockdownActive(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean(KEY_LOCKDOWN_ACTIVE, value).apply()

    fun getLockdownReason(context: Context): String = getPrefs(context).getString(KEY_LOCKDOWN_REASON, "Sentry Disconnected") ?: "Sentry Disconnected"
    fun setLockdownReason(context: Context, reason: String) = getPrefs(context).edit().putString(KEY_LOCKDOWN_REASON, reason).apply()

    fun getCustomAlarmUri(context: Context): String? = getPrefs(context).getString(KEY_CUSTOM_ALARM_URI, null)
    fun setCustomAlarmUri(context: Context, uri: String?, title: String?) {
        getPrefs(context).edit()
            .putString(KEY_CUSTOM_ALARM_URI, uri)
            .putString(KEY_CUSTOM_ALARM_TITLE, title ?: "Custom Ringtone")
            .apply()
    }
    fun getCustomAlarmTitle(context: Context): String = getPrefs(context).getString(KEY_CUSTOM_ALARM_TITLE, "Standard Pulse Beep") ?: "Standard Pulse Beep"

    fun isForce247(context: Context): Boolean = getPrefs(context).getBoolean(KEY_FORCE_24_7, true)
    fun setForce247(context: Context, value: Boolean) = getPrefs(context).edit().putBoolean(KEY_FORCE_24_7, value).apply()

    // --- Night Quiet Hours Setters & Getters ---
    fun isNightQuietEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_NIGHT_QUIET_ENABLED, true)
    fun setNightQuietEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_NIGHT_QUIET_ENABLED, enabled).apply()

    fun getNightStartHour(context: Context): Int = getPrefs(context).getInt(KEY_NIGHT_START_HOUR, 23) // Default 11 PM
    fun getNightStartMinute(context: Context): Int = getPrefs(context).getInt(KEY_NIGHT_START_MINUTE, 0)
    fun getNightEndHour(context: Context): Int = getPrefs(context).getInt(KEY_NIGHT_END_HOUR, 6) // Default 6 AM
    fun getNightEndMinute(context: Context): Int = getPrefs(context).getInt(KEY_NIGHT_END_MINUTE, 0)

    fun setNightQuietTimes(context: Context, startH: Int, startM: Int, endH: Int, endM: Int) {
        getPrefs(context).edit()
            .putInt(KEY_NIGHT_START_HOUR, startH)
            .putInt(KEY_NIGHT_START_MINUTE, startM)
            .putInt(KEY_NIGHT_END_HOUR, endH)
            .putInt(KEY_NIGHT_END_MINUTE, endM)
            .apply()
    }

    /**
     * Checks whether the current moment falls inside the defined Night Quiet Hours.
     * Accurately handles overnight windows (e.g., 23:00 to 06:00).
     */
    fun isNightQuietTime(context: Context): Boolean {
        if (!isNightQuietEnabled(context)) return false

        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = getNightStartHour(context) * 60 + getNightStartMinute(context)
        val endMinutes = getNightEndHour(context) * 60 + getNightEndMinute(context)

        return if (startMinutes < endMinutes) {
            // Same day window (e.g. 01:00 to 05:00)
            currentMinutes in startMinutes until endMinutes
        } else {
            // Overnight window (e.g. 23:00 to 06:00)
            currentMinutes >= startMinutes || currentMinutes < endMinutes
        }
    }
}
