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
import com.desk.companion.utils.PreferenceHelper
import com.google.firebase.database.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventLogActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEventLogBinding
    private var eventsRef: DatabaseReference? = null
    private var listener: ValueEventListener? = null

    // Date -> List of Events mapping
    private val groupedEvents = linkedMapOf<String, MutableList<SentryEvent>>()
    private val dateKeys = mutableListOf<String>()

    private var selectedDate: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventLogBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.rvDateList.layoutManager = LinearLayoutManager(this)
        binding.rvDayEvents.layoutManager = LinearLayoutManager(this)

        binding.btnBack.setOnClickListener {
            handleBackPress()
        }

        loadFirebaseEvents()
    }

    private fun loadFirebaseEvents() {
        val deviceId = PreferenceHelper.getPairedDeviceId(this)
        if (deviceId.isEmpty()) {
            binding.tvEmptyState.visibility = View.VISIBLE
            binding.tvEmptyState.text = "No Camera Phone Paired."
            return
        }

        eventsRef = FirebaseDatabase.getInstance().getReference("desk_sentry").child(deviceId).child("events")

        listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                groupedEvents.clear()
                dateKeys.clear()

                for (child in snapshot.children) {
                    val title = child.child("title").getValue(String::class.java)
                        ?: child.child("event").getValue(String::class.java)
                        ?: "Sentry Alert"
                    val desc = child.child("description").getValue(String::class.java)
                        ?: child.child("message").getValue(String::class.java)
                        ?: ""
                    val ts = child.child("timestamp").getValue(Long::class.java) ?: System.currentTimeMillis()
                    val severity = child.child("severity").getValue(String::class.java)
                        ?: child.child("type").getValue(String::class.java)
                        ?: "INFO"

                    val event = SentryEvent(title, desc, ts, severity)
                    val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))

                    if (!groupedEvents.containsKey(dateKey)) {
                        groupedEvents[dateKey] = mutableListOf()
                    }
                    groupedEvents[dateKey]?.add(event)
                }

                // Show newest dates first
                dateKeys.addAll(groupedEvents.keys.reversed())

                if (dateKeys.isEmpty()) {
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

        eventsRef?.addValueEventListener(listener!!)
    }

    private fun showDateListView() {
        selectedDate = null
        binding.tvToolbarTitle.text = "Sentry Event Logs"
        binding.rvDateList.visibility = View.VISIBLE
        binding.layoutDayReport.visibility = View.GONE

        binding.rvDateList.adapter = DateAdapter(dateKeys, groupedEvents) { date ->
            showDayReportView(date)
        }
    }

    private fun showDayReportView(date: String) {
        selectedDate = date
        binding.tvToolbarTitle.text = "Audit: $date"
        binding.tvSelectedDateHeader.text = "📅 Complete Day Report for $date"
        binding.rvDateList.visibility = View.GONE
        binding.layoutDayReport.visibility = View.VISIBLE

        val dayEvents = groupedEvents[date]?.reversed() ?: emptyList()
        binding.rvDayEvents.adapter = EventLogAdapter(dayEvents)
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
        listener?.let { eventsRef?.removeEventListener(it) }
    }

    // Adapter for Level 1: Date Rows
    class DateAdapter(
        private val dates: List<String>,
        private val eventMap: Map<String, List<SentryEvent>>,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<DateAdapter.DateViewHolder>() {

        class DateViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvDate: TextView = view.findViewById(android.R.id.text1)
            val tvCount: TextView = view.findViewById(android.R.id.text2)
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
            val count = eventMap[date]?.size ?: 0

            holder.tvDate.text = "📅 $date"
            holder.tvDate.setTextColor(Color.WHITE)
            holder.tvDate.textSize = 16f

            holder.tvCount.text = "$count Security Events Logged • Tap to view report →"
            holder.tvCount.setTextColor(Color.parseColor("#38BDF8"))
            holder.tvCount.textSize = 13f

            holder.itemView.setOnClickListener { onClick(date) }
        }

        override fun getItemCount(): Int = dates.size
    }
}
