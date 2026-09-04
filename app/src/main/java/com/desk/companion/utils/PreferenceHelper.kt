package com.desk.companion.utils

import android.content.Context
import android.content.SharedPreferences

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
}
