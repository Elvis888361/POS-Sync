package com.example.possync

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class QuickActionsAdapter : RecyclerView.Adapter<QuickActionsAdapter.ViewHolder>() {

    private val actions = listOf(
        QuickAction("New Sale", R.drawable.ic_pos),
        QuickAction("Sales History", R.drawable.ic_receipt),
        QuickAction("Customers", R.drawable.ic_customers),
        QuickAction("Inventory", R.drawable.ic_inventory)
    )

    private var itemClickListener: ((Int) -> Unit)? = null

    fun setOnItemClickListener(listener: (Int) -> Unit) {
        itemClickListener = listener
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_quick_action, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val action = actions[position]
        holder.bind(action)
        holder.itemView.setOnClickListener {
            itemClickListener?.invoke(position)
        }
    }

    override fun getItemCount(): Int = actions.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun bind(action: QuickAction) {
            itemView.findViewById<TextView>(R.id.action_name).text = action.name
            itemView.findViewById<ImageView>(R.id.action_icon).setImageResource(action.icon)
        }
    }
}

data class QuickAction(val name: String, val icon: Int)