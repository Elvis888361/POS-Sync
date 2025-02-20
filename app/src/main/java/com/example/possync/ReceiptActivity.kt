package com.example.possync

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.print.PrintManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReceiptActivity : AppCompatActivity() {

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receipt)

        // Header Views (company info)
        val companyNameTextView: TextView = findViewById(R.id.company_name)
        val companyAddressTextView: TextView = findViewById(R.id.company_address)

        // Invoice Header Views
        val invoiceNumberTextView: TextView = findViewById(R.id.invoice_number)
        val invoiceDateTextView: TextView = findViewById(R.id.invoice_date)
        val customerNameTextView: TextView = findViewById(R.id.customer_name)

        // RecyclerView for invoice items
        val itemsRecyclerView: RecyclerView = findViewById(R.id.items_recycler_view)

        // Footer Summary Views
        val totalTextView: TextView = findViewById(R.id.footer_total)
        val taxTextView: TextView = findViewById(R.id.footer_tax)
        val grandTotalTextView: TextView = findViewById(R.id.footer_grand_total)

        // Print Receipt Button
        val printReceiptButton: Button = findViewById(R.id.print_receipt)

        // Retrieve the ERPNextInvoice from the intent
        val invoice = intent.getSerializableExtra("INVOICE") as? ERPNextInvoice
        Log.e("ReceiptActivity", "$invoice")

        if (invoice != null) {
            // Use company data from the invoice; if not available, default to "N/A"
            companyNameTextView.text = if (invoice.company.isNotBlank()) invoice.company else "N/A"
            companyAddressTextView.text = if (invoice.companyaddress.isNotBlank()) invoice.companyaddress else "N/A"

            // Invoice header
            invoiceNumberTextView.text = " ${invoice.id}"
            invoiceDateTextView.text = " " + invoice.date.ifEmpty {
                SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
            }
            customerNameTextView.text = " ${invoice.customer}"

            // Setup RecyclerView for items
            itemsRecyclerView.layoutManager = LinearLayoutManager(this)
            if (invoice.items.isNotEmpty()) {
                itemsRecyclerView.adapter = InvoiceItemsAdapter(invoice.items)
            } else {
                Toast.makeText(this, "No items in this invoice", Toast.LENGTH_SHORT).show()
            }

            // Calculate tax as (grandTotal - total), defaulting to 0 if negative or zero
            val calculatedTax = (invoice.grandTotal - invoice.total).let { if (it > 0) it else 0.0 }

            totalTextView.text = "${invoice.currency} ${String.format("%.2f", invoice.total)}"
            taxTextView.text = "${invoice.currency} ${String.format("%.2f", calculatedTax)}"
            grandTotalTextView.text = "${invoice.currency} ${String.format("%.2f", invoice.grandTotal)}"
        } else {
            Toast.makeText(this, "No invoice data available", Toast.LENGTH_SHORT).show()
            companyNameTextView.text = "N/A"
            companyAddressTextView.text = "N/A"
            invoiceNumberTextView.text = "Invoice #: N/A"
            invoiceDateTextView.text = "Date: N/A"
            customerNameTextView.text = "Customer: N/A"
            totalTextView.text = "Total: N/A"
            taxTextView.text = "Tax: N/A"
            grandTotalTextView.text = "Grand Total: N/A"
        }

        // Print button: Use Android's PrintManager with our ReceiptPrintAdapter
        printReceiptButton.setOnClickListener {
            Toast.makeText(this, "Printing Receipt...", Toast.LENGTH_SHORT).show()
            val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
            // Get the root view of the receipt layout (the ScrollView with id receipt_root)
            val receiptView = findViewById<View>(R.id.receipt_root)
            if (receiptView != null) {
                val jobName = "${getString(R.string.app_name)} Receipt"
                printManager.print(jobName, ReceiptPrintAdapter(this, receiptView), null)
            } else {
                Toast.makeText(this, "Nothing to print", Toast.LENGTH_SHORT).show()
                AlertDialog.Builder(this@ReceiptActivity)
                    .setTitle("Error")
                    .setMessage("Nothing to print")
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
        }
    }
}
