package com.desk.companion.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.desk.companion.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SentryEvent(
    val title: String = "",
    val description: String = "",
    val timestamp: Long = 0L,
    val severity: String = "INFO" // INFO, WARNING, CRITICAL
)

class EventLogAdapter(private val events: List<SentryEvent>) :
    RecyclerView.Adapter<EventLogAdapter.EventViewHolder>() {

    class EventViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvEventTitle)
        val tvTime: TextView = view.findViewById(R.id.tvEventTime)
        val tvDesc: TextView = view.findViewById(R.id.tvEventDesc)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_event_log, parent, false)
        return EventViewHolder(view)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        val item = events[position]
        holder.tvTitle.text = item.title
        holder.tvDesc.text = item.description

        if (item.timestamp > 0) {
            val sdf = SimpleDateFormat("dd MMM, hh:mm:ss a", Locale.getDefault())
            holder.tvTime.text = sdf.format(Date(item.timestamp))
        } else {
            holder.tvTime.text = "Just now"
        }

        when (item.severity.uppercase(Locale.ROOT)) {
            "CRITICAL" -> holder.tvTitle.setTextColor(Color.parseColor("#EF4444")) // Red
            "WARNING" -> holder.tvTitle.setTextColor(Color.parseColor("#F59E0B"))  // Yellow/Orange
            else -> holder.tvTitle.setTextColor(Color.parseColor("#38BDF8"))       // Blue/Info
        }
    }

    override fun getItemCount(): Int = events.size
}
