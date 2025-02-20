package com.example.possync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class CartAdapter(
    private val listener: CartItemListener
) : ListAdapter<CartItem, CartAdapter.ViewHolder>(DiffCallback()) {

    interface CartItemListener {
        fun onQuantityChanged(itemCode: String, newQuantity: Int)
        fun onPriceChanged(itemCode: String, newPrice: Double)
        fun onRemoveItem(itemCode: String)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_cart, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, listener)
    }

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val itemName: TextView = itemView.findViewById(R.id.itemName)
        private val itemUOM: TextView = itemView.findViewById(R.id.itemUOM)
        private val edtPrice: EditText = itemView.findViewById(R.id.edtPrice)
        private val txtQuantity: TextView = itemView.findViewById(R.id.txtQuantity)
        private val btnIncrease: Button = itemView.findViewById(R.id.btnIncrease)
        private val btnDecrease: Button = itemView.findViewById(R.id.btnDecrease)
        private val btnRemove: ImageButton = itemView.findViewById(R.id.btnRemove)

        fun bind(item: CartItem, listener: CartItemListener) {
            itemName.text = item.name
            itemUOM.text = item.uom
            edtPrice.setText("%.2f".format(item.price))
            txtQuantity.text = item.quantity.toString()

            btnIncrease.setOnClickListener {
                val newQuantity = item.quantity + 1
                listener.onQuantityChanged(item.itemCode, newQuantity)
            }

            btnDecrease.setOnClickListener {
                if (item.quantity > 1) {
                    val newQuantity = item.quantity - 1
                    listener.onQuantityChanged(item.itemCode, newQuantity)
                }
            }

            edtPrice.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val newPrice = edtPrice.text.toString().toDoubleOrNull() ?: item.price
                    listener.onPriceChanged(item.itemCode, newPrice)
                }
            }

            btnRemove.setOnClickListener {
                listener.onRemoveItem(item.itemCode)
            }
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<CartItem>() {
        override fun areItemsTheSame(oldItem: CartItem, newItem: CartItem): Boolean {
            return oldItem.itemCode == newItem.itemCode
        }

        override fun areContentsTheSame(oldItem: CartItem, newItem: CartItem): Boolean {
            return oldItem == newItem
        }
    }
}