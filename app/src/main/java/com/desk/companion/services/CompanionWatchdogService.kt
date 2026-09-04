package com.desk.companion.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
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
        private const val NOTIF_ID = 2001

        fun dismissLockdown(context: Context) {
            PreferenceHelper.setLockdownActive(context, false)
            context.stopService(Intent(context, KioskOverlayService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildForegroundNotification("Guard Active: Sentry Connected"))

        startNetworkMonitoring()
        startFirebaseListener()
        startHeartbeatWatchdogLoop()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Desk Companion Watchdog",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Desk Sentry Companion")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startNetworkMonitoring() {
        connectivityManager = getSystemService(Context.NETWORK_SERVICE) as ConnectivityManager

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
                    startGracePeriod(300_000L, "Local internet connection dropped (Packets Dead)") // 5 Minutes Buffer
                }
            }

            override fun onLost(network: Network) {
                isInterfaceEnabled = false
                isNetworkValidated = false
                startGracePeriod(60_000L, "Companion Wi-Fi / Mobile Data turned OFF") // 60 Seconds Buffer
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
                    // Conditions recovered: auto dismiss
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
                delay(15_000) // Check every 15 seconds
                if (!PreferenceHelper.isArmed(this@CompanionWatchdogService)) continue

                val deviceId = PreferenceHelper.getPairedDeviceId(this@CompanionWatchdogService)
                if (deviceId.isNotEmpty()) {
                    FirebaseDatabase.getInstance().getReference("desk_sentry")
                        .child(deviceId)
                        .child("last_heartbeat")
                        .get().addOnSuccessListener { snap ->
                            val lastHb = snap.getValue(Long::class.java) ?: 0L
                            val currentTime = System.currentTimeMillis()

                            // Dead Man's Switch: If no heartbeat in 45 seconds -> Sentry Phone Dead
                            if (lastHb > 0 && (currentTime - lastHb) > 45_000) {
                                triggerLockdown("Dead Man's Switch: Camera Phone Powered Off or No Signal!")
                            }
                        }
                }
            }
        }
    }

    private fun triggerLockdown(reason: String) {
        PreferenceHelper.setLockdownActive(this, true)
        PreferenceHelper.setLockdownReason(this, reason)

        val overlayIntent = Intent(this, KioskOverlayService::class.java)
        startService(overlayIntent)
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
        stopService(Intent(this, KioskOverlayService::class.java))
    }
}
