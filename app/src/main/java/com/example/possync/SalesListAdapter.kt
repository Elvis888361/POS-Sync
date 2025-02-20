package com.example.possync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

// A general click listener interface for invoices.
interface OnInvoiceClickListeners {
    fun onInvoiceClicked(invoice: SalesInvoice)
}

class SalesListAdapter(
    private var items: List<SalesInvoice> = listOf(),
    private val listener: OnInvoiceClickListeners? = null
) : RecyclerView.Adapter<SalesListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvInvoiceNumber: TextView = view.findViewById(R.id.tv_invoice_number)
        val tvStatus: TextView = view.findViewById(R.id.tv_status)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sales_invoice, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val invoice = items[position]
        holder.tvInvoiceNumber.text = "Invoice: ${invoice.invoiceNumber}"
        holder.tvStatus.text = "Status: ${invoice.status} (docStatus: ${invoice.docStatus})"

        // Every invoice item is clickable; the activity can decide how to handle each type.
        holder.itemView.setOnClickListener {
            listener?.onInvoiceClicked(invoice)
        }
    }

    override fun getItemCount(): Int = items.size

    // Efficiently update the list using DiffUtil.
    fun updateList(newItems: List<SalesInvoice>) {
        val diffCallback = SalesInvoiceDiffCallback(items, newItems)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        items = newItems
        diffResult.dispatchUpdatesTo(this)
    }
}

// DiffUtil callback for efficient list updates.
class SalesInvoiceDiffCallback(
    private val oldList: List<SalesInvoice>,
    private val newList: List<SalesInvoice>
) : DiffUtil.Callback() {
    override fun getOldListSize(): Int = oldList.size

    override fun getNewListSize(): Int = newList.size

    override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition].invoiceNumber == newList[newItemPosition].invoiceNumber
    }

    override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
        return oldList[oldItemPosition] == newList[newItemPosition]
    }
}
