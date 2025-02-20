// POSViewModel.kt
package com.example.possync

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class POSViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = POSRepository(application)

    private val _items = MutableLiveData<List<ERPNextItem>>()
    val items: LiveData<List<ERPNextItem>> = _items

    val cartItems: LiveData<List<CartItem>> = CartRepository.cartItems

    private val _paymentState = MutableLiveData<PaymentState>()
    val paymentState: LiveData<PaymentState> = _paymentState

    private val _paymentMethods = MutableLiveData<List<String>>()
    val paymentMethods: LiveData<List<String>> = _paymentMethods

    private val _dashboardStats = MutableLiveData<DashboardStats>()
    val dashboardStats: LiveData<DashboardStats> = _dashboardStats

    fun loadDashboardStats() {
        viewModelScope.launch {
            delay(1000)
            _dashboardStats.value = DashboardStats(
                todaySales = 500.0,
                activeCustomers = 10,
                lowStockItems = 0
            )
        }
    }

    fun loadPaymentMethods() {
        viewModelScope.launch {
            try {
                _paymentMethods.value = listOf("Cash", "Card", "Mobile Money")
            } catch (e: Exception) {
                _paymentState.value = PaymentState.Error("Failed to load payment methods")
            }
        }
    }

    fun processPayment(method: String) {
        viewModelScope.launch {
            _paymentState.value = PaymentState.Loading
            val (invoice, error) = repository.createERPNextInvoice(
                CartRepository.getItems()
            )
            if (error != null) {
                _paymentState.value = PaymentState.Error(error.message ?: "Unknown error")
            } else {
                val salesInvoice = invoice as SalesInvoice // Cast ERPNextInvoice to SalesInvoice
                _paymentState.value = PaymentState.Success(invoice)
                CartRepository.clear()
            }
        }
    }

    init {
        loadItemsFromERPNext()
    }

    private fun loadItemsFromERPNext() {
        viewModelScope.launch {
            delay(1000)
            _items.value = listOf(
                ERPNextItem("001", "Product 1", "pcs", 100.0, 123456.0, "001"),
                ERPNextItem("002", "Product 2", "pcs", 50.0, 54321.0,"002")
            )
        }
    }

    fun addToCart(item: ERPNextItem) {
        val cartItem = CartItem(
            itemCode = item.itemCode,
            name = item.name,
            price = item.price,
            quantity = 1,
            uom = item.uom
        )
        CartRepository.addItem(cartItem)
    }

    fun updateCartItem(itemCode: String, newQuantity: Int) {
        CartRepository.updateItem(itemCode, newQuantity)
    }

    fun updateCartItemPrice(itemCode: String, newPrice: Double) {
        CartRepository.updatePrice(itemCode, newPrice)
    }

    fun removeCartItem(itemCode: String) {
        CartRepository.removeItem(itemCode)
    }

    fun findItemByBarcode(barcode: String) {
        val item = _items.value?.find { it.barcode == barcode }
        if (item != null) {
            addToCart(item)
        } else {
            _paymentState.value = PaymentState.Error("Item with barcode $barcode not found")
        }
    }
    fun clearCart() {
        CartRepository.clear()
    }
}