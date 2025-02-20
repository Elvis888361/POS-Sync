package com.example.possync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ReportAdapter : RecyclerView.Adapter<ReportAdapter.ReportViewHolder>() {

    private val reportData = mutableListOf<Map<String, Any>>()

    fun updateData(data: List<Map<String, Any>>) {
        reportData.clear()
        reportData.addAll(data)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ReportViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        return ReportViewHolder(view)
    }

    override fun onBindViewHolder(holder: ReportViewHolder, position: Int) {
        val row = reportData[position]
        if (row.isNotEmpty()) {
            // Display the first entry on the primary text view.
            val firstEntry = row.entries.first()
            holder.text1.text = "${firstEntry.key}: ${firstEntry.value}"
            // Concatenate the rest of the row entries.
            val otherEntries = row.entries.drop(1).joinToString(" | ") { "${it.key}: ${it.value}" }
            holder.text2.text = otherEntries
        }
    }

    override fun getItemCount(): Int = reportData.size

    class ReportViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val text1: TextView = itemView.findViewById(android.R.id.text1)
        val text2: TextView = itemView.findViewById(android.R.id.text2)
    }
}
