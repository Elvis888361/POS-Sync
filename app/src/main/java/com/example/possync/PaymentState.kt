package com.example.possync

data class Payment(val method: String, val amount: Double)
data class PaymentMethod(val name: String, val type: String)

sealed class PaymentState {
    data object Loading : PaymentState()
    data class Success(val invoice: ERPNextInvoice?) : PaymentState()
    data class Error(val message: String) : PaymentState()
}