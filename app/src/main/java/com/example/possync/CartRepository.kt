// CartRepository.kt
package com.example.possync

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

object CartRepository {
    private val _cartItems = mutableListOf<CartItem>()
    private val _cartItemsLiveData = MutableLiveData<List<CartItem>>()

    val cartItems: LiveData<List<CartItem>> get() = _cartItemsLiveData

    fun getItems() = _cartItems.toList()

    fun addItem(item: CartItem) {
        val existing = _cartItems.find { it.itemCode == item.itemCode }
        if (existing != null) {
            existing.quantity += item.quantity
        } else {
            _cartItems.add(item)
        }
        notifyChanges()
    }

    fun updateItem(itemCode: String, quantity: Int) {
        _cartItems.find { it.itemCode == itemCode }?.let {
            it.quantity = quantity
            notifyChanges()
        }
    }

    fun updatePrice(itemCode: String, newPrice: Double) {
        _cartItems.find { it.itemCode == itemCode }?.let {
            it.price = newPrice
            notifyChanges()
        }
    }

    fun removeItem(itemCode: String) {
        _cartItems.removeAll { it.itemCode == itemCode }
        notifyChanges()
    }

    fun clear() {
        _cartItems.clear()
        notifyChanges()
    }

    private fun notifyChanges() {
        _cartItemsLiveData.postValue(_cartItems.toList())
    }
}