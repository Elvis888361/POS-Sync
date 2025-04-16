package com.example.possync

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText

class PaymentActivity : AppCompatActivity() {

    private lateinit var viewModel: PaymentViewModel
    private lateinit var progressBar: ProgressBar
    private lateinit var paymentMethodsContainer: LinearLayout
    private lateinit var tvInvoiceTotal: TextView
    private lateinit var tvInvoiceGrandTotal: TextView
    private lateinit var tvTotalPaid: TextView
    private lateinit var tvRemainingAmount: TextView
    private lateinit var btnConfirmPayment: Button
    private lateinit var currentInvoice: ERPNextInvoice

    // Tracks whether the default payment method has been manually edited.
    private var defaultUserEdited = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize session and set layout
        SessionManager.initialize(this)
        setContentView(R.layout.activity_payment)

        // Initialize views
        progressBar = findViewById(R.id.progressBar)
        paymentMethodsContainer = findViewById(R.id.paymentMethodsContainer)
        tvInvoiceTotal = findViewById(R.id.tvInvoiceTotal)
        tvInvoiceGrandTotal = findViewById(R.id.tvInvoiceGrandTotal)
        tvTotalPaid = findViewById(R.id.tvTotalPaid)
        tvRemainingAmount = findViewById(R.id.tvRemainingAmount)
        btnConfirmPayment = findViewById(R.id.btnConfirmPayment)

        // Retrieve the invoice (including its payments) from the Intent
        currentInvoice = intent.getSerializableExtra("INVOICE") as? ERPNextInvoice ?: run {
            Toast.makeText(this, "Invalid invoice data", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        Log.d("PaymentActivity", "Invoice: $currentInvoice")

        // Initialize ViewModel for processing payments (submission)
        viewModel = ViewModelProvider(this, PaymentViewModelFactory(applicationContext))
            .get(PaymentViewModel::class.java)

        setupUI()
        setupObservers()
        // Display payment methods passed from the previous activity
        displayPaymentMethods()
    }

    private fun setupUI() {
        tvInvoiceGrandTotal.text =
            "Grand Total: ${currentInvoice.currency} ${"%.2f".format(currentInvoice.grandTotal)}"
        tvInvoiceTotal.text =
            "Total: ${currentInvoice.currency} ${"%.2f".format(currentInvoice.total)}"
        // Initially, display the full grand total in both the default payment field
        // and in the remaining amount (if the user hasn’t made any changes yet).
        tvRemainingAmount.text =
            "Remaining: ${currentInvoice.currency} ${"%.2f".format(0.0)}"
        // Initially, the paid amount is simply what is in the default field.
        tvTotalPaid.text = "Paid: ${currentInvoice.currency} ${"%.2f".format(currentInvoice.grandTotal)}"
        btnConfirmPayment.setOnClickListener {
            validateAndProcessPayments()
        }
    }

    private fun setupObservers() {
        viewModel.paymentState.observe(this) { state ->
            when (state) {
                is PaymentState.Loading -> showLoading(true)
                is PaymentState.Success -> handlePaymentSuccess(state.invoice)
                is PaymentState.Error -> handlePaymentError(state.message)
            }
        }
    }

    /**
     * Displays the payment methods.
     *
     * The first method is the default. Initially its amount is set to the grand total and
     * it is auto‑updated (i.e. “deducted from”) when non‑default methods get an amount—
     * unless the user manually edits it.
     *
     * Non‑default methods are initially unchecked and disabled.
     * When checked, they become editable. Their entered amounts (if any) are validated so that
     * the overall total does not exceed the grand total.
     */
    @SuppressLint("InflateParams")
    private fun displayPaymentMethods() {
        paymentMethodsContainer.removeAllViews()
        val invoicePayments = currentInvoice.payments
        if (invoicePayments.isNullOrEmpty()) {
            Toast.makeText(this, "No payment methods available", Toast.LENGTH_LONG).show()
            btnConfirmPayment.isEnabled = false
            return
        }
        btnConfirmPayment.isEnabled = true

        invoicePayments.forEachIndexed { index, payment ->
            val itemView = layoutInflater.inflate(R.layout.item_payment_method, null)
            val tvMethodName = itemView.findViewById<TextView>(R.id.tvMethodName)
            val etAmount = itemView.findViewById<TextInputEditText>(R.id.etAmount)
            val cbMethod = itemView.findViewById<MaterialCheckBox>(R.id.cbMethod)

            tvMethodName.text = payment.mode_of_payment
            cbMethod.text = payment.mode_of_payment

            if (index == 0) {
                // Default payment method.
                cbMethod.isChecked = true
                // Initially set to the full grand total.
                etAmount.setText("%.2f".format(currentInvoice.grandTotal))
                // Allow editing so the user can override the auto‑deduction if desired.
                etAmount.isEnabled = true

                // When the user manually edits the default field, mark it as edited.
                etAmount.setOnFocusChangeListener { _, hasFocus ->
                    if (!hasFocus) {
                        defaultUserEdited = true
                        // Also check that the manually entered default does not exceed
                        // the maximum allowed (i.e. grandTotal minus non‑default amounts).
                        val nonDefaultSum = sumNonDefaultAmounts()
                        val currentVal = etAmount.text.toString().toDoubleOrNull() ?: 0.0
                        val maxDefault = currentInvoice.grandTotal - nonDefaultSum
                        if (currentVal > maxDefault) {
                            etAmount.error = "Max allowed is ${"%.2f".format(maxDefault)}"
                            etAmount.setText("%.2f".format(maxDefault))
                        }
                        updateTotalPaid()
                    }
                }
                // Also watch changes to the default field.
                etAmount.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        // Only run this if the user has edited the field.
                        if (defaultUserEdited) {
                            val nonDefaultSum = sumNonDefaultAmounts()
                            val currentVal = s.toString().toDoubleOrNull() ?: 0.0
                            val maxDefault = currentInvoice.grandTotal - nonDefaultSum
                            if (currentVal > maxDefault) {
                                etAmount.error = "Max allowed is ${"%.2f".format(maxDefault)}"
                                etAmount.setText("%.2f".format(maxDefault))
                                etAmount.setSelection(etAmount.text?.length ?: 0)
                            }
                            updateTotalPaid()
                        }
                    }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                })
            } else {
                // Non‑default payment methods.
                cbMethod.isChecked = false
                etAmount.setText("")
                etAmount.isEnabled = false

                cbMethod.setOnCheckedChangeListener { _, checked ->
                    etAmount.isEnabled = checked
                    if (!checked) {
                        etAmount.setText("")
                    }
                    updateTotalPaid()
                }

                // Add a TextWatcher to validate input.
                etAmount.addTextChangedListener(object : TextWatcher {
                    override fun afterTextChanged(s: Editable?) {
                        // Calculate the sum of non‑default amounts from other fields.
                        var sumOthers = 0.0
                        for (j in 1 until paymentMethodsContainer.childCount) {
                            if (j == index) continue
                            val otherView = paymentMethodsContainer.getChildAt(j)
                            val otherCb = otherView.findViewById<MaterialCheckBox>(R.id.cbMethod)
                            if (otherCb.isChecked) {
                                val otherEt = otherView.findViewById<TextInputEditText>(R.id.etAmount)
                                sumOthers += otherEt.text.toString().toDoubleOrNull() ?: 0.0
                            }
                        }
                        // For the current non‑default field, determine the maximum allowed.
                        // (If the default has not been manually edited, it is auto‑computed.)
                        val maxForThis = if (defaultUserEdited) {
                            // In this case the user’s default value is used.
                            val defaultVal = (paymentMethodsContainer.getChildAt(0)
                                .findViewById<TextInputEditText>(R.id.etAmount)).text.toString().toDoubleOrNull() ?: 0.0
                            currentInvoice.grandTotal - (sumOthers + defaultVal)
                        } else {
                            // Auto‑computed default is (grandTotal – sum of other non‑default amounts).
                            currentInvoice.grandTotal - sumOthers
                        }
                        val currentValue = s.toString().toDoubleOrNull() ?: 0.0
                        if (currentValue > maxForThis) {
                            etAmount.error = "Max allowed is ${"%.2f".format(maxForThis)}"
                            etAmount.setText("%.2f".format(maxForThis))
                            etAmount.setSelection(etAmount.text?.length ?: 0)
                        }
                        updateTotalPaid()
                    }
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                })
            }
            paymentMethodsContainer.addView(itemView)
        }
        // Always update totals after setting up the UI.
        updateTotalPaid()
    }

    /**
     * Sums the amounts entered in all non‑default payment method fields (i.e. index 1 onward).
     */
    private fun sumNonDefaultAmounts(): Double {
        var sum = 0.0
        for (i in 1 until paymentMethodsContainer.childCount) {
            val child = paymentMethodsContainer.getChildAt(i)
            val cb = child.findViewById<MaterialCheckBox>(R.id.cbMethod)
            if (cb.isChecked) {
                val et = child.findViewById<TextInputEditText>(R.id.etAmount)
                sum += et.text.toString().toDoubleOrNull() ?: 0.0
            }
        }
        return sum
    }

    /**
     * Recalculates and updates the following:
     * - The default payment method’s amount (if it has not been manually edited)
     *   is set to: grandTotal – (sum of non‑default amounts).
     * - The Paid amount is the sum of all payment method amounts.
     * - The Remaining amount is: grandTotal – Paid.
     */
    private fun updateTotalPaid() {
        val nonDefaultSum = sumNonDefaultAmounts()
        // Get the default method’s TextInputEditText.
        if (paymentMethodsContainer.childCount > 0) {
            val defaultChild = paymentMethodsContainer.getChildAt(0)
            val defaultEt = defaultChild.findViewById<TextInputEditText>(R.id.etAmount)
            if (!defaultUserEdited) {
                // Auto‑update default so that default + non‑default = grandTotal.
                defaultEt.setText("%.2f".format(currentInvoice.grandTotal - nonDefaultSum))
            }
            // Read the (possibly user‑edited) default amount.
            val defaultAmount = defaultEt.text.toString().toDoubleOrNull() ?: 0.0
            // Compute the overall paid amount.
            val totalPaid = nonDefaultSum + defaultAmount
            // Compute the remaining amount (could be positive if underpaid, or negative if overpaid).
            val remaining = currentInvoice.grandTotal - totalPaid
            tvTotalPaid.text = "Paid: ${currentInvoice.currency} ${"%.2f".format(totalPaid)}"
            tvRemainingAmount.text = "Remaining: ${currentInvoice.currency} ${"%.2f".format(remaining)}"
        }
    }

    /**
     * Validates that the sum of all payments does not exceed the grand total.
     * (It’s acceptable if the paid amount is less than the grand total, allowing for partial payment.)
     * Then, if valid, processes the payments.
     */
    private fun validateAndProcessPayments() {
        val payments = collectValidPayments() ?: return
        val totalPaid = payments.sumOf { it.amount }
        if (totalPaid > currentInvoice.grandTotal) {
            Toast.makeText(
                this,
                "Total payment exceeds grand total by ${currentInvoice.currency} ${"%.2f".format(totalPaid - currentInvoice.grandTotal)}",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        // You might allow partial payments (totalPaid < grandTotal) or require full payment.
        val updatedInvoice = currentInvoice.copy(
            payments = payments,
            amount = totalPaid
        )
        viewModel.processPayments(updatedInvoice, payments)
    }

    /**
     * Iterates over the payment method views and collects valid payments.
     */
    private fun collectValidPayments(): List<InvoicePayment>? {
        val payments = mutableListOf<InvoicePayment>()
        for (i in 0 until paymentMethodsContainer.childCount) {
            val child = paymentMethodsContainer.getChildAt(i)
            val cb = child.findViewById<MaterialCheckBox>(R.id.cbMethod)
            val et = child.findViewById<TextInputEditText>(R.id.etAmount)
            if (cb.isChecked) {
                val amount = et.text.toString().toDoubleOrNull() ?: 0.0
                if (amount < 0) {
                    et.error = "Enter valid amount"
                    return null
                }
                payments.add(InvoicePayment(cb.text.toString(), amount))
            }
        }
        if (payments.isEmpty()) {
            Toast.makeText(this, "Select at least one payment method", Toast.LENGTH_SHORT).show()
            return null
        }
        return payments
    }

    private fun showAmountMismatchError(paid: Double) {
        val difference = currentInvoice.grandTotal - paid
        val message = if (difference > 0)
            "Underpaid by ${currentInvoice.currency} ${"%.2f".format(difference)}"
        else
            "Overpaid by ${currentInvoice.currency} ${"%.2f".format(-difference)}"
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun handlePaymentSuccess(invoice: ERPNextInvoice?) {
        invoice?.let {
            // If items are missing, you might merge them from currentInvoice if needed.
            // Otherwise, simply pass the invoice to ReceiptActivity.
            val intent = Intent(this, ReceiptActivity::class.java).apply {
                putExtra("INVOICE", it)
            }
            startActivity(intent)
            finish()
        } ?: run {
            Toast.makeText(this, "Payment successful but no invoice received", Toast.LENGTH_SHORT).show()
            finish()
        }
    }




    private fun handlePaymentError(message: String) {
        Toast.makeText(this, "Payment failed: $message", Toast.LENGTH_LONG).show()
    }

    private fun showLoading(show: Boolean) {
        progressBar.visibility = if (show) View.VISIBLE else View.GONE
        btnConfirmPayment.isEnabled = !show
    }
}