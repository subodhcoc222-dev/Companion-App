package com.desk.companion.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.desk.companion.LockdownActivity
import com.desk.companion.receivers.CompanionDeviceAdminReceiver
import com.desk.companion.utils.PreferenceHelper
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.*

class CompanionWatchdogService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var firebaseListener: ValueEventListener? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var isNetworkValidated = true
    private var isInterfaceEnabled = true
    private var graceJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "desk_companion_watchdog_channel"
        private const val ALARM_CHANNEL_ID = "desk_companion_lockdown_channel"
        private const val NOTIF_ID = 2001
        private const val ALARM_NOTIF_ID = 9001

        fun dismissLockdown(context: Context) {
            PreferenceHelper.setLockdownActive(context, false)
            context.sendBroadcast(Intent("com.desk.companion.DISMISS_LOCKDOWN"))
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(ALARM_NOTIF_ID)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIF_ID, buildForegroundNotification("Guard Active: Sentry Connected"))

        startNetworkMonitoring()
        startFirebaseListener()
        startHeartbeatWatchdogLoop()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Desk Companion Watchdog",
                NotificationManager.IMPORTANCE_LOW
            )
            manager.createNotificationChannel(channel)

            val alarmChannel = NotificationChannel(
                ALARM_CHANNEL_ID,
                "Desk Breach Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Triggers immediately on lockscreen during security breach"
                setBypassDnd(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            manager.createNotificationChannel(alarmChannel)
        }
    }

    private fun buildForegroundNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Desk Sentry Companion")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startNetworkMonitoring() {
        connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                isInterfaceEnabled = true
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (hasInternet) {
                    isNetworkValidated = true
                    cancelGracePeriod()
                } else {
                    isNetworkValidated = false
                    startGracePeriod(300_000L, "Local internet connection dropped (Packets Dead)")
                }
            }

            override fun onLost(network: Network) {
                isInterfaceEnabled = false
                isNetworkValidated = false
                startGracePeriod(60_000L, "Companion Wi-Fi / Mobile Data turned OFF")
            }
        }

        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }

    private fun startGracePeriod(delayMs: Long, reason: String) {
        if (graceJob?.isActive == true) return
        graceJob = serviceScope.launch {
            delay(delayMs)
            if (!isNetworkValidated && PreferenceHelper.isArmed(this@CompanionWatchdogService)) {
                triggerLockdown(reason)
            }
        }
    }

    private fun cancelGracePeriod() {
        graceJob?.cancel()
        graceJob = null
    }

    private fun startFirebaseListener() {
        val deviceId = PreferenceHelper.getPairedDeviceId(this)
        if (deviceId.isEmpty()) return

        val ref = FirebaseDatabase.getInstance().getReference("desk_sentry").child(deviceId)

        firebaseListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!PreferenceHelper.isArmed(this@CompanionWatchdogService)) return

                val isCharging = snapshot.child("is_charging").getValue(Boolean::class.java) ?: true
                val status = snapshot.child("status").getValue(String::class.java) ?: "OFFLINE"

                if (!isCharging) {
                    triggerLockdown("Camera Phone Charger UNPLUGGED!")
                } else if (status == "OFFLINE") {
                    triggerLockdown("Camera Phone Status set to OFFLINE!")
                } else {
                    if (PreferenceHelper.isLockdownActive(this@CompanionWatchdogService)) {
                        dismissLockdown(this@CompanionWatchdogService)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        ref.addValueEventListener(firebaseListener!!)
    }

    private fun startHeartbeatWatchdogLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(15_000)
                if (!PreferenceHelper.isArmed(this@CompanionWatchdogService)) continue

                val deviceId = PreferenceHelper.getPairedDeviceId(this@CompanionWatchdogService)
                if (deviceId.isNotEmpty()) {
                    FirebaseDatabase.getInstance().getReference("desk_sentry")
                        .child(deviceId)
                        .child("last_heartbeat")
                        .get().addOnSuccessListener { snap ->
                            val lastHb = snap.getValue(Long::class.java) ?: 0L
                            val currentTime = System.currentTimeMillis()

                            if (lastHb > 0 && (currentTime - lastHb) > 45_000) {
                                triggerLockdown("Dead Man's Switch: Camera Phone Powered Off or No Signal!")
                            }
                        }
                }
            }
        }
    }

    private fun triggerLockdown(reason: String) {
        if (PreferenceHelper.isLockdownActive(this)) return

        PreferenceHelper.setLockdownActive(this, true)
        PreferenceHelper.setLockdownReason(this, reason)

        // 1. FORCE INSTANT HARD-LOCK VIA DEVICE ADMIN
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, CompanionDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(adminComponent)) {
            try {
                dpm.lockNow()
            } catch (_: Exception) {}
        }

        // 2. Launch Alarm on Secure Keyguard after 250ms transition
        serviceScope.launch {
            delay(250)

            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
                "DeskCompanion:AlarmWakeLock"
            )
            wakeLock.acquire(10_000L)

            val intent = Intent(this@CompanionWatchdogService, LockdownActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                putExtra("reason", reason)
            }

            val pendingIntent = PendingIntent.getActivity(
                this@CompanionWatchdogService,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val alarmNotif = NotificationCompat.Builder(this@CompanionWatchdogService, ALARM_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("🚨 SENTRY BREACH DETECTED!")
                .setContentText(reason)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(pendingIntent, true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(false)
                .setOngoing(true)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(ALARM_NOTIF_ID, alarmNotif)

            try {
                startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        firebaseListener?.let {
            val id = PreferenceHelper.getPairedDeviceId(this)
            if (id.isNotEmpty()) {
                FirebaseDatabase.getInstance().getReference("desk_sentry").child(id).removeEventListener(it)
            }
        }
    }
}
