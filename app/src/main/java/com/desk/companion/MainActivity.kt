package com.desk.companion

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.desk.companion.databinding.ActivityMainBinding
import com.desk.companion.receivers.CompanionDeviceAdminReceiver
import com.desk.companion.services.CompanionWatchdogService
import com.desk.companion.utils.PreferenceHelper
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isSwitchProgrammatic = false
    private var firebaseListener: ValueEventListener? = null

    private val ringtonePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri: Uri? = result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            if (uri != null) {
                val ringtone = RingtoneManager.getRingtone(this, uri)
                val title = ringtone.getTitle(this) ?: "Custom Alarm"
                PreferenceHelper.setCustomAlarmUri(this, uri.toString(), title)
                binding.tvAlarmTitle.text = title
            } else {
                PreferenceHelper.setCustomAlarmUri(this, null, "Standard Pulse Beep")
                binding.tvAlarmTitle.text = "Standard Pulse Beep"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkInitialSetup()
        setupUI()
        observeFirebase()
    }

    private fun checkInitialSetup() {
        // Step 1: Initial Master PIN creation if not exists
        if (PreferenceHelper.getMasterPin(this) == null) {
            showSetPinDialog(isFirstTime = true)
        }

        // Step 2: Initial Paired Device ID configuration
        if (PreferenceHelper.getPairedDeviceId(this).isEmpty()) {
            showPairDeviceDialog()
        }
    }

    private fun setupUI() {
        val isArmed = PreferenceHelper.isArmed(this)
        updateMasterSwitchUI(isArmed)

        val pairedId = PreferenceHelper.getPairedDeviceId(this)
        binding.tvPairedId.text = if (pairedId.isNotEmpty()) "Paired Sentry: #$pairedId" else "Not Paired"
        binding.tvAlarmTitle.text = PreferenceHelper.getCustomAlarmTitle(this)

        // Master Switch Listener
        binding.switchMaster.setOnCheckedChangeListener { _, isChecked ->
            if (isSwitchProgrammatic) return@setOnCheckedChangeListener

            if (isChecked) {
                // One-tap Armed
                PreferenceHelper.setArmed(this, true)
                updateMasterSwitchUI(true)
                startWatchdogService()
                observeFirebase()
                Toast.makeText(this, "🛡️ Desk Sentry Armed: 24/7 Guard Active", Toast.LENGTH_SHORT).show()
            } else {
                // Disarm requires Master PIN
                showDisarmPinDialog()
            }
        }

        // Change Master PIN button (requires old PIN validation)
        binding.btnChangePin.setOnClickListener {
            showVerifyCurrentPinDialog {
                showSetPinDialog(isFirstTime = false)
            }
        }

        // Change Paired Device ID button (requires Master PIN validation)
        binding.btnChangeDevice.setOnClickListener {
            showVerifyCurrentPinDialog {
                showPairDeviceDialog()
            }
        }

        // Custom Ringtone / Beep Picker
        binding.btnChangeAlarm.setOnClickListener {
            val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Select Alarm Sound")
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                val currentUri = PreferenceHelper.getCustomAlarmUri(this@MainActivity)
                if (currentUri != null) {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(currentUri))
                }
            }
            ringtonePickerLauncher.launch(intent)
        }

        // Snapshot Trigger Button
        binding.btnRequestSnap.setOnClickListener {
            val deviceId = PreferenceHelper.getPairedDeviceId(this)
            if (deviceId.isNotEmpty()) {
                FirebaseDatabase.getInstance().getReference("desk_sentry")
                    .child(deviceId)
                    .child("commands")
                    .child("request_snap")
                    .setValue(true)
                Toast.makeText(this, "Requesting Sentry Snapshot...", Toast.LENGTH_SHORT).show()
            }
        }

        // Permissions Assistant Button
        binding.btnPermissions.setOnClickListener {
            checkAndRequestSystemPermissions()
        }
    }

    private fun updateMasterSwitchUI(isArmed: Boolean) {
        isSwitchProgrammatic = true
        binding.switchMaster.isChecked = isArmed
        isSwitchProgrammatic = false

        if (isArmed) {
            binding.tvMasterStatus.text = "● SYSTEM ARMED"
            binding.tvMasterStatus.setTextColor(Color.parseColor("#10B981"))
        } else {
            binding.tvMasterStatus.text = "○ DISARMED (SAFE MODE)"
            binding.tvMasterStatus.setTextColor(Color.parseColor("#94A3B8"))
        }
    }

    private fun startWatchdogService() {
        val serviceIntent = Intent(this, CompanionWatchdogService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun observeFirebase() {
        val deviceId = PreferenceHelper.getPairedDeviceId(this)
        if (deviceId.isEmpty()) return

        val ref = FirebaseDatabase.getInstance().getReference("desk_sentry").child(deviceId)

        firebaseListener?.let { ref.removeEventListener(it) }

        firebaseListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val status = snapshot.child("status").getValue(String::class.java) ?: "OFFLINE"
                val battery = snapshot.child("battery_level").getValue(Int::class.java) ?: 0
                val isCharging = snapshot.child("is_charging").getValue(Boolean::class.java) ?: false
                val lastHb = snapshot.child("last_heartbeat").getValue(Long::class.java) ?: 0L
                val base64Snap = snapshot.child("latest_snapshot_base64").getValue(String::class.java)

                // Update Telemetry UI
                binding.tvSentryStatus.text = "● $status"
                binding.tvSentryStatus.setTextColor(
                    if (status == "ONLINE") Color.parseColor("#10B981") else Color.parseColor("#EF4444")
                )
                binding.tvBattery.text = "$battery% ${if (isCharging) "⚡" else ""}"

                if (lastHb > 0) {
                    val timeStr = SimpleDateFormat("hh:mm:ss a", Locale.getDefault()).format(Date(lastHb))
                    binding.tvLastHeartbeat.text = "Last sync: $timeStr"
                }

                // Decode Base64 Snapshot Image
                if (!base64Snap.isNullOrEmpty()) {
                    try {
                        val decodedBytes = Base64.decode(base64Snap, Base64.NO_WRAP)
                        val bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
                        binding.ivSnapshot.setImageBitmap(bitmap)
                        binding.tvSnapTime.text = "Last Snapshot: Updated Live"
                    } catch (_: Exception) {}
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        ref.addValueEventListener(firebaseListener!!)
    }

    // 2-Step PIN Creation / Change Dialog
    private fun showSetPinDialog(isFirstTime: Boolean) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val etPin = EditText(this).apply {
            hint = "Enter 4-Digit Master PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
        }
        val etConfirm = EditText(this).apply {
            hint = "Re-enter to Confirm PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
        }

        layout.addView(etPin)
        layout.addView(etConfirm)

        AlertDialog.Builder(this)
            .setTitle(if (isFirstTime) "Create Master PIN" else "Set New Master PIN")
            .setMessage("This PIN will be required to Disarm, change settings, or modify Device ID.")
            .setView(layout)
            .setCancelable(!isFirstTime)
            .setPositiveButton("Save PIN") { _, _ ->
                val p1 = etPin.text.toString().trim()
                val p2 = etConfirm.text.toString().trim()

                if (p1.length == 4 && p1 == p2) {
                    PreferenceHelper.setMasterPin(this, p1)
                    Toast.makeText(this, "Master PIN Saved Successfully", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "PINs did not match or length was not 4 digits!", Toast.LENGTH_LONG).show()
                    showSetPinDialog(isFirstTime)
                }
            }
            .show()
    }

    private fun showVerifyCurrentPinDialog(onSuccess: () -> Unit) {
        val etPin = EditText(this).apply {
            hint = "Enter Current PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
        }

        AlertDialog.Builder(this)
            .setTitle("Authentication Required")
            .setView(etPin)
            .setPositiveButton("Verify") { _, _ ->
                val input = etPin.text.toString().trim()
                val master = PreferenceHelper.getMasterPin(this)
                if (master != null && input == master) {
                    onSuccess()
                } else {
                    Toast.makeText(this, "Incorrect Master PIN!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDisarmPinDialog() {
        val etPin = EditText(this).apply {
            hint = "Enter Master PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
        }

        AlertDialog.Builder(this)
            .setTitle("Disarm Desk Sentry")
            .setMessage("Enter the Master PIN to deactivate the Companion Watchdog.")
            .setView(etPin)
            .setCancelable(false)
            .setPositiveButton("Disarm") { _, _ ->
                val input = etPin.text.toString().trim()
                val master = PreferenceHelper.getMasterPin(this)
                if (master != null && input == master) {
                    PreferenceHelper.setArmed(this, false)
                    updateMasterSwitchUI(false)
                    stopService(Intent(this, CompanionWatchdogService::class.java))
                    Toast.makeText(this, "Desk Companion Disarmed (Safe Mode)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Wrong PIN! Sentry Remains Armed.", Toast.LENGTH_SHORT).show()
                    updateMasterSwitchUI(true)
                }
            }
            .setNegativeButton("Cancel") { _, _ ->
                updateMasterSwitchUI(true)
            }
            .show()
    }

    private fun showPairDeviceDialog() {
        val etId = EditText(this).apply {
            hint = "6-Digit Sentry Cloud ID"
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
        }

        AlertDialog.Builder(this)
            .setTitle("Pair Sentry Camera Phone")
            .setMessage("Enter the 6-digit Cloud ID displayed on the Camera Phone.")
            .setView(etId)
            .setPositiveButton("Pair Device") { _, _ ->
                val id = etId.text.toString().trim()
                if (id.length == 6) {
                    PreferenceHelper.setPairedDeviceId(this, id)
                    binding.tvPairedId.text = "Paired Sentry: #$id"
                    observeFirebase()
                    Toast.makeText(this, "Device #$id Paired", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "ID must be exactly 6 digits!", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun checkAndRequestSystemPermissions() {
        // 1. Overlay Permission
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Enable 'Display over other apps' permission", Toast.LENGTH_LONG).show()
            return
        }

        // 2. Device Admin Permission
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, CompanionDeviceAdminReceiver::class.java)
        if (!dpm.isAdminActive(adminComponent)) {
            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Protects the Desk Companion app from unauthorized uninstallation.")
            }
            startActivity(intent)
            return
        }

        Toast.makeText(this, "All Core Security Permissions Active!", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        val id = PreferenceHelper.getPairedDeviceId(this)
        if (id.isNotEmpty() && firebaseListener != null) {
            FirebaseDatabase.getInstance().getReference("desk_sentry").child(id).removeEventListener(firebaseListener!!)
        }
    }
}
