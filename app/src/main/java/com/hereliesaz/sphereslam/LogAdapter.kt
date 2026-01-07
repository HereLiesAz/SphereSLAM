package com.hereliesaz.sphereslam

import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private val logs = ArrayList<LogEntry>()

    fun setLogs(newLogs: List<LogEntry>) {
        logs.clear()
        logs.addAll(newLogs)
        notifyDataSetChanged()
    }

    fun addLogs(newLogs: List<LogEntry>) {
        val startPos = logs.size
        logs.addAll(newLogs)
        notifyItemRangeInserted(startPos, newLogs.size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(logs[position])
    }

    override fun getItemCount(): Int = logs.size

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val time: TextView = itemView.findViewById(R.id.logTime)
        private val tag: TextView = itemView.findViewById(R.id.logTag)
        private val message: TextView = itemView.findViewById(R.id.logMessage)

        fun bind(entry: LogEntry) {
            time.text = entry.timeString
            tag.text = entry.tag
            message.text = entry.message

            val color = when (entry.level) {
                Log.ERROR -> Color.RED
                Log.WARN -> Color.YELLOW
                Log.INFO -> Color.GREEN
                Log.DEBUG -> Color.CYAN
                else -> Color.LTGRAY
            }
            message.setTextColor(color)
        }
    }
}
