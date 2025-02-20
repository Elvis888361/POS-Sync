package com.example.possync

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class PaymentViewModel(private val context: Context) : ViewModel() {
    private val repository = PaymentRepository(context)
    private val _paymentMethods = MutableLiveData<List<PaymentMethod>?>()
    private val _paymentState = MutableLiveData<PaymentState>()

    val paymentMethods: MutableLiveData<List<PaymentMethod>?> get() = _paymentMethods
    val paymentState: LiveData<PaymentState> get() = _paymentState

//    fun loadPaymentMethods(user: String, domain: String) {
//        _paymentState.value = PaymentState.Loading
//        repository.fetchPaymentMethods(user, domain) { methods, error ->
//            if (methods != null) {
//                _paymentMethods.postValue(methods)
//                _paymentState.postValue(PaymentState.Success(null))
//            } else {
//                _paymentState.postValue(PaymentState.Error(error ?: "Failed to load payment methods"))
//            }
//        }
//    }

    // Updated processPayments() now accepts an ERPNextInvoice object instead of a String.
    fun processPayments(invoice: ERPNextInvoice, payments: List<InvoicePayment>) {
        _paymentState.value = PaymentState.Loading
        repository.submitPayments(invoice, payments) { updatedInvoice, error ->
            if (updatedInvoice != null) {
                _paymentState.postValue(PaymentState.Success(updatedInvoice))
            } else {
                _paymentState.postValue(PaymentState.Error(error ?: "Unknown payment error"))
            }
        }
    }
}
