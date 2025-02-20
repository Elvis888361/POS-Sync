package com.example.possync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

class ItemAdapter(private val listener: ItemClickListener) :
    RecyclerView.Adapter<ItemAdapter.ViewHolder>(){
    private var items = mutableListOf<ERPNextItem>()
    private var itemLayout = R.layout.item_product_card

    fun setItemLayout(layoutRes: Int) {
        itemLayout = layoutRes
        notifyDataSetChanged() // Refresh the RecyclerView
    }
    fun submitList(newItems: List<ERPNextItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    interface ItemClickListener {
        fun onItemClick(item: ERPNextItem)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_product_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, listener)
    }
    override fun getItemCount(): Int = items.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(item: ERPNextItem, listener: ItemClickListener) {
            itemView.findViewById<TextView>(R.id.item_name).text = item.name
            itemView.findViewById<TextView>(R.id.item_price).text = "${item.price}"
            itemView.findViewById<TextView>(R.id.item_uom).text = item.uom
            itemView.findViewById<TextView>(R.id.item_qty).text = "${item.stockQty}"
            itemView.findViewById<ImageView>(R.id.item_image).setImageResource(R.drawable.placeholder_product)
            itemView.setOnClickListener { this@ItemAdapter.listener.onItemClick(item)
            }
    }

    inner class DiffCallback : DiffUtil.ItemCallback<ERPNextItem>() {
        override fun areItemsTheSame(oldItem: ERPNextItem, newItem: ERPNextItem): Boolean {
            return oldItem.itemCode == newItem.itemCode
        }

        override fun areContentsTheSame(oldItem: ERPNextItem, newItem: ERPNextItem): Boolean {
            return oldItem == newItem
        }
    }
}}
