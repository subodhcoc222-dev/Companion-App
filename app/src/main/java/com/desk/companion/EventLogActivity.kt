package com.desk.companion

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.desk.companion.adapters.EventLogAdapter
import com.desk.companion.adapters.SentryEvent
import com.desk.companion.databinding.ActivityEventLogBinding
import com.desk.companion.databinding.ItemSlotCardBinding
import com.desk.companion.utils.PreferenceHelper
import com.google.firebase.database.*
import org.json.JSONObject
import java.util.Locale

data class SlotData(
    val slotNumber: Int,
    val presentSec: Long = 0L,
    val absentSec: Long = 0L,
    val officialBreakSec: Long = 0L
)

data class DayReport(
    val date: String,
    val dayName: String,
    val slots: Map<Int, SlotData>,
    val totalStudySec: Long,
    val totalBreakSec: Long,
    val totalAbsentSec: Long
)

class EventLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEventLogBinding
    private var sentryRef: DatabaseReference? = null
    private var rootListener: ValueEventListener? = null

    private val dayReportsMap = linkedMapOf<String, DayReport>()
    private val securityLogsMap = linkedMapOf<String, MutableList<SentryEvent>>()
    private val availableDates = mutableListOf<String>()

    private var selectedDate: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvDateList.layoutManager = LinearLayoutManager(this)
        binding.rvSecurityLogs.layoutManager = LinearLayoutManager(this)

        binding.btnBack.setOnClickListener {
            handleBackPress()
        }

        loadAllDataFromFirebase()
    }

    private fun loadAllDataFromFirebase() {
        val deviceId = PreferenceHelper.getPairedDeviceId(this)
        if (deviceId.isEmpty()) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.tvEmptyState.text = "No Camera Phone Paired."
            return
        }

        sentryRef = FirebaseDatabase.getInstance().getReference("desk_sentry").child(deviceId)

        rootListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                dayReportsMap.clear()
                securityLogsMap.clear()
                availableDates.clear()

                // 1. Parse Daily Slot Reports from 'events' node
                val eventsSnapshot = snapshot.child("events")
                for (child in eventsSnapshot.children) {
                    val dateKey = child.key ?: continue
                    val rawJson = child.getValue(String::class.java) ?: continue

                    try {
                        val json = JSONObject(rawJson)
                        val dayName = json.optString("dayName", dateKey)
                        val slotsJson = json.optJSONObject("slots")

                        val slotMap = mutableMapOf<Int, SlotData>()
                        var sumStudy = 0L
                        var sumBreak = 0L
                        var sumAbsent = 0L

                        for (i in 1..5) {
                            val slotObj = slotsJson?.optJSONObject(i.toString())
                            val pres = slotObj?.optLong("presentSec") ?: 0L
                            val abs = slotObj?.optLong("absentSec") ?: 0L
                            val brk = slotObj?.optLong("officialBreakSec") ?: 0L

                            sumStudy += pres
                            sumBreak += brk
                            sumAbsent += abs

                            slotMap[i] = SlotData(i, pres, abs, brk)
                        }

                        dayReportsMap[dateKey] = DayReport(
                            date = dateKey,
                            dayName = dayName,
                            slots = slotMap,
                            totalStudySec = sumStudy,
                            totalBreakSec = sumBreak,
                            totalAbsentSec = sumAbsent
                        )
                    } catch (_: Exception) {}
                }

                // 2. Parse Security Tamper Logs from 'security_logs' node
                val secSnapshot = snapshot.child("security_logs")
                for (dateChild in secSnapshot.children) {
                    val dateKey = dateChild.key ?: continue
                    val list = mutableListOf<SentryEvent>()

                    for (eventItem in dateChild.children) {
                        val type = eventItem.child("type").getValue(String::class.java) ?: "SECURITY"
                        val detail = eventItem.child("detail").getValue(String::class.java) ?: ""
                        val timeStr = eventItem.child("time").getValue(String::class.java) ?: ""
                        val ts = eventItem.child("timestamp").getValue(Long::class.java) ?: 0L

                        val isCritical = type.contains("UNPLUGGED") || type.contains("BREACH")
                        val title = if (type == "CHARGER_UNPLUGGED") "🚨 Power Unplugged" else "⚡ Power Connected"

                        list.add(SentryEvent(
                            title = "$title ($timeStr)",
                            description = detail,
                            timestamp = ts,
                            severity = if (isCritical) "CRITICAL" else "INFO"
                        ))
                    }
                    securityLogsMap[dateKey] = list
                }

                // Collect and reverse dates (newest first)
                availableDates.addAll(dayReportsMap.keys.reversed())

                if (availableDates.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.rvDateList.visibility = View.GONE
                } else {
                    binding.tvEmptyState.visibility = View.GONE
                    if (selectedDate == null) {
                        showDateListView()
                    } else {
                        showDayReportView(selectedDate!!)
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
        }

        sentryRef?.addValueEventListener(rootListener!!)
    }

    private fun showDateListView() {
        selectedDate = null
        binding.tvToolbarTitle.text = "Sentry Event Logs"
        binding.rvDateList.visibility = View.VISIBLE
        binding.layoutDayReport.visibility = View.GONE

        binding.rvDateList.adapter = DateSummaryAdapter(availableDates, dayReportsMap) { date ->
            showDayReportView(date)
        }
    }

    private fun showDayReportView(date: String) {
        selectedDate = date
        val report = dayReportsMap[date] ?: return

        binding.tvToolbarTitle.text = "Audit Report"
        binding.rvDateList.visibility = View.GONE
        binding.layoutDayReport.visibility = View.VISIBLE

        // Header info & 3 Badges
        binding.tvSelectedDayName.text = report.dayName
        binding.tvSelectedDateHeader.text = "Date: ${report.date}"
        binding.tvTotalStudyTime.text = formatDuration(report.totalStudySec)
        binding.tvTotalBreaks.text = formatDuration(report.totalBreakSec)
        binding.tvTotalUnexcused.text = formatDuration(report.totalAbsentSec)

        // Populate Slot 1 to 5 Cards
        binding.layoutSlotsContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (i in 1..5) {
            val slotBinding = ItemSlotCardBinding.inflate(inflater, binding.layoutSlotsContainer, false)
            val slotData = report.slots[i] ?: SlotData(i)

            slotBinding.tvSlotTitle.text = "SLOT $i"
            slotBinding.tvSlotStudyTime.text = formatDuration(slotData.presentSec)
            slotBinding.tvSlotBreakTime.text = formatDuration(slotData.officialBreakSec)
            slotBinding.tvSlotAbsentTime.text = formatDuration(slotData.absentSec)

            if (slotData.presentSec > 0 || slotData.absentSec > 0 || slotData.officialBreakSec > 0) {
                slotBinding.tvSlotStatus.text = "Recorded"
                slotBinding.tvSlotStatus.setTextColor(Color.parseColor("#10B981"))
            } else {
                slotBinding.tvSlotStatus.text = "No Activity"
                slotBinding.tvSlotStatus.setTextColor(Color.parseColor("#64748B"))
            }

            binding.layoutSlotsContainer.addView(slotBinding.root)
        }

        // Security Logs List
        val secLogs = securityLogsMap[date]?.reversed() ?: emptyList()
        if (secLogs.isEmpty()) {
            binding.tvNoSecurityLogs.visibility = View.VISIBLE
            binding.rvSecurityLogs.visibility = View.GONE
        } else {
            binding.tvNoSecurityLogs.visibility = View.GONE
            binding.rvSecurityLogs.visibility = View.VISIBLE
            binding.rvSecurityLogs.adapter = EventLogAdapter(secLogs)
        }
    }

    private fun formatDuration(totalSec: Long): String {
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.getDefault(), "%dh %02dm %02ds", h, m, s)
        } else {
            String.format(Locale.getDefault(), "%02dm %02ds", m, s)
        }
    }

    private fun handleBackPress() {
        if (selectedDate != null) {
            showDateListView()
        } else {
            finish()
        }
    }

    override fun onBackPressed() {
        handleBackPress()
    }

    override fun onDestroy() {
        super.onDestroy()
        rootListener?.let { sentryRef?.removeEventListener(it) }
    }

    // Adapter for Level 1: Date Cards
    class DateSummaryAdapter(
        private val dates: List<String>,
        private val reports: Map<String, DayReport>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<DateSummaryAdapter.DateViewHolder>() {

        class DateViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvDate: TextView = view.findViewById(android.R.id.text1)
            val tvSubtitle: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DateViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            view.setBackgroundColor(Color.parseColor("#1E293B"))
            view.setPadding(32, 24, 32, 24)
            return DateViewHolder(view)
        }

        override fun onBindViewHolder(holder: DateViewHolder, position: Int) {
            val date = dates[position]
            val report = reports[date]

            holder.tvDate.text = "📅 ${report?.dayName ?: date}"
            holder.tvDate.setTextColor(Color.WHITE)
            holder.tvDate.textSize = 15f

            val studyTime = (report?.totalStudySec ?: 0L) / 60
            holder.tvSubtitle.text = "Total Study: ${studyTime} mins • Tap to view report →"
            holder.tvSubtitle.setTextColor(Color.parseColor("#38BDF8"))
            holder.tvSubtitle.textSize = 12f

            holder.itemView.setOnClickListener { onClick(date) }
        }

        override fun getItemCount(): Int = dates.size
    }
}
