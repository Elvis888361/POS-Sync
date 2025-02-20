package com.example.possync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class InvoiceItemsAdapter(private val items: List<InvoiceItem>) :
    RecyclerView.Adapter<InvoiceItemsAdapter.ItemViewHolder>() {

    inner class ItemViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val itemName: TextView = itemView.findViewById(R.id.item_name)
        val itemQuantity: TextView = itemView.findViewById(R.id.item_quantity)
        val itemRate: TextView = itemView.findViewById(R.id.item_rate)
        val itemAmount: TextView = itemView.findViewById(R.id.item_amount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ItemViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_invoice, parent, false)
        return ItemViewHolder(view)
    }

    override fun onBindViewHolder(holder: ItemViewHolder, position: Int) {
        val item = items[position]
        holder.itemName.text = item.item_code  // Use a descriptive name if available
        holder.itemQuantity.text = " ${item.qty}"
        holder.itemRate.text = " ${String.format("%.2f", item.rate)}"
        val amount = item.qty * item.rate
        holder.itemAmount.text = " ${String.format("%.2f", amount)}"
    }

    override fun getItemCount(): Int = items.size
}
