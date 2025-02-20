package com.example.possync

import java.io.Serializable

data class SalesInvoice(
    val invoiceId: String,
    val customerName: String,
    val totalAmount: Double,
    val invoiceNumber: String,
    val status: String,
    val docStatus: Int,
    var currency: String
    // Add other fields as needed
) : Serializable
fun SalesInvoice.toERPNextInvoice(): ERPNextInvoice {
    return ERPNextInvoice(
        name = this.invoiceId,
        customer = this.customerName,
        grandTotal = this.totalAmount,
        id = this.invoiceId,
        currency = "USD",
        total = TODO(),
        items = TODO(),
        payments = TODO(),
        amount = TODO(),
        date = TODO(),
        company = TODO(),
        companyaddress = TODO(),
        customername = TODO(),
        companytaxid = TODO(),
        invoiceNumber = TODO(),
        status = TODO(),
        docStatus = TODO(),
        companyaddressdisplay = TODO()
    )
}