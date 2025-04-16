package com.example.possync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SalesHistoryAdapter(private val salesList: List<SalesInvoice>) :
    RecyclerView.Adapter<SalesHistoryAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val saleId: TextView = itemView.findViewById(R.id.sale_id)
        val saleDate: TextView = itemView.findViewById(R.id.sale_date)
        val saleAmount: TextView = itemView.findViewById(R.id.sale_amount)
        val customerName: TextView = itemView.findViewById(R.id.customer_name)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sale_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sale = salesList[position]
        holder.saleId.text = "Sale #${sale.invoiceNumber}"
        // You can format the modified date as needed.
        holder.saleDate.text = sale.modified ?: "Unknown date"
        holder.saleAmount.text = "${sale.currency}${sale.totalAmount}"
        holder.customerName.text = "Customer: ${sale.customerName}"
    }

    override fun getItemCount(): Int = salesList.size
}
