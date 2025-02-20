package com.example.possync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SalesHistoryAdapter(private val sales: List<Sale>) : RecyclerView.Adapter<SalesHistoryAdapter.ViewHolder>() {

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val saleId: TextView = itemView.findViewById(R.id.sale_id)
        val saleDate: TextView = itemView.findViewById(R.id.sale_date)
        val saleAmount: TextView = itemView.findViewById(R.id.sale_amount)
        val customerName: TextView = itemView.findViewById(R.id.customer_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sale_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sale = sales[position]
        holder.saleId.text = "Sale #${sale.id}"
        holder.saleDate.text = sale.date
        holder.saleAmount.text = sale.amount
        holder.customerName.text = "Customer: ${sale.customerName}"
    }

    override fun getItemCount(): Int {
        return sales.size
    }

    data class Sale(
        val id: String,
        val date: String,
        val amount: String,
        val customerName: String
    )
}