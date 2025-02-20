package com.example.possync

import java.io.Serializable

data class ERPNextInvoice(
    val name: String = "",
    val customer: String = "", // Customer name
    val total: Double = 0.0, // Total amount (can also use grandTotal)
    val items: List<InvoiceItem> = emptyList(),        // Changed to List<InvoiceItem>
    val payments: List<InvoicePayment> = emptyList(),    // Changed to List<InvoicePayment>
    val id: String = "", // Invoice ID
    val amount: Double = 0.0,
    val date: String = "",
    val grandTotal: Double = 0.0, // Grand total amount
    val currency: String = "",
    val company: String = "",
    val companyaddress: String = "",
    val customername: String = "",
    val companytaxid: String = "",
    val invoiceNumber: String,
    val status: String,
    val docStatus: Int,
    val companyaddressdisplay: String = ""
) : Serializable {

    // Conversion function to map ERPNextInvoice to SalesInvoice
    fun toSalesInvoice(): SalesInvoice {
        return SalesInvoice(
            invoiceId = this.id,         // Map to invoiceId
            customerName = this.customer, // Map to customerName
            totalAmount = this.grandTotal, // Use grandTotal for totalAmount
            invoiceNumber = this.name,
            status = this.status,
            docStatus = this.docStatus,
            currency = this.currency,
            // Add other fields if needed
        )
    }
}

data class InvoiceItem(
    val item_code: String,
    val qty: Int,
    val rate: Double
) : Serializable

data class InvoicePayment(
    val mode_of_payment: String,
    val amount: Double
) : Serializable
