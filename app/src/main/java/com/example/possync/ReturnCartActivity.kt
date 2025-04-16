package com.example.possync

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

class ReturnCartActivity : AppCompatActivity(), CartAdapter.CartItemListener {

    private lateinit var viewModel: POSViewModel
    private lateinit var adapter: CartAdapter
    private var originalInvoiceId: String? = null

    // UI references
    private lateinit var customerSpinner: Spinner
    private lateinit var tvTotalAmount: TextView

    // We store the entire original invoice JSON here.
    private var originalInvoiceData: JSONObject? = null

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_return_cart)

        // Get the original invoice ID passed from the previous screen.
        originalInvoiceId = intent.getStringExtra("RETURN_INVOICE_ID")

        viewModel = ViewModelProvider(this).get(POSViewModel::class.java)
        adapter = CartAdapter(this)

        // Set up RecyclerView (if you need to display the items for reference)
        findViewById<RecyclerView>(R.id.cartRecycler).apply {
            layoutManager = LinearLayoutManager(this@ReturnCartActivity)
            adapter = this@ReturnCartActivity.adapter
        }

        // Get references to the customer Spinner and total amount TextView.
        customerSpinner = findViewById(R.id.customerSpinner)
        tvTotalAmount = findViewById(R.id.totalAmount)

        // Observe cart items (for UI display only)
        viewModel.cartItems.observe(this) { items ->
            adapter.submitList(items)
            updateTotalAmount()
        }

        // Load the full invoice details from ERPNext.
        originalInvoiceId?.let { loadReturnInvoice(it) }

        // When the button is pressed, create and post the return invoice.
        findViewById<Button>(R.id.btnCheckout).apply {
            text = "Complete Return"
            setOnClickListener {
                completeReturn()
            }
        }
    }

    /**
     * Updates the total amount TextView for UI purposes.
     */
    private fun updateTotalAmount() {
        val total = viewModel.cartItems.value?.sumOf { it.price * it.quantity } ?: 0.0
        tvTotalAmount.text = "Total: $${"%.2f".format(total)}"
    }

    /**
     * Loads the Sales Invoice from ERPNext using its ID.
     * The full JSON (all fields) is stored in [originalInvoiceData].
     * For UI purposes, the customer and items are extracted.
     */
    private fun loadReturnInvoice(invoiceId: String) {
        val sharedPreferences = getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)

        if (sessionCookie == null || erpnextUrl == null) {
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_LONG).show()
            AlertDialog.Builder(this@ReturnCartActivity)
                .setTitle("Error")
                .setMessage("Session expired. Please log in again.")
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .show()
            return
        }

        // Build the URL to fetch the Sales Invoice record.
        val prefs = getSharedPreferences("session_cookie", Context.MODE_PRIVATE)
        val invoiceType = prefs.getString("invoice_type", "")
        val url = if (invoiceType == "sales_invoice") {
            "https://$erpnextUrl/api/resource/Sales%20Invoice/${URLEncoder.encode(invoiceId, "UTF-8")}"
        } else {
            "https://$erpnextUrl/api/resource/POS%20Invoice/${URLEncoder.encode(invoiceId, "UTF-8")}"
        }
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Cookie", sessionCookie)
            .build()

        getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@ReturnCartActivity, "Failed to load invoice: ${e.message}", Toast.LENGTH_LONG).show()
                    AlertDialog.Builder(this@ReturnCartActivity)
                        .setTitle("Error")
                        .setMessage("Failed to load invoice: ${e.message}")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(this@ReturnCartActivity, "Error loading invoice: ${response.message}", Toast.LENGTH_LONG).show()
                        AlertDialog.Builder(this@ReturnCartActivity)
                            .setTitle("Error")
                            .setMessage("Session expired. Please log in again.")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                    return
                }
                val responseBody = response.body?.string()
                try {
                    val json = JSONObject(responseBody)
                    // Save the entire invoice JSON.
                    val data = json.getJSONObject("data")
                    Log.e("ReturnCartActivity", data.toString())
                    originalInvoiceData = data

                    // For UI: update customer spinner.
                    val customer = data.optString("customer", "")
                    runOnUiThread {
                        val customerList = listOf(customer)
                        val spinnerAdapter = ArrayAdapter(
                            this@ReturnCartActivity,
                            android.R.layout.simple_spinner_item,
                            customerList
                        )
                        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                        customerSpinner.adapter = spinnerAdapter
                    }

                    // For UI: update the cart items list.
                    val itemsArray = data.optJSONArray("items")
                    val cartItems = mutableListOf<CartItem>()
                    if (itemsArray != null) {
                        for (i in 0 until itemsArray.length()) {
                            val itemObj = itemsArray.getJSONObject(i)
                            val itemCode = itemObj.optString("item_code", "")
                            val qty = itemObj.optInt("qty", 0)
                            val rate = itemObj.optDouble("rate", 0.0)
                            val uom = itemObj.optString("uom", "")
                            val negativeQty = if (qty > 0) -qty else qty
                            val cartItem = CartItem(
                                itemCode = itemCode,
                                quantity = negativeQty,
                                price = rate,
                                name = itemCode,
                                uom = uom
                            )
                            cartItems.add(cartItem)
                        }
                    }
                    runOnUiThread {
                        (viewModel.cartItems as? MutableLiveData)?.value = cartItems
                        Toast.makeText(this@ReturnCartActivity, "Invoice loaded for return: $invoiceId", Toast.LENGTH_SHORT).show()
                        AlertDialog.Builder(this@ReturnCartActivity)
                            .setTitle("Error")
                            .setMessage("Invoice loaded for return: $invoiceId")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                } catch (e: JSONException) {
                    runOnUiThread {
                        Toast.makeText(this@ReturnCartActivity, "Error parsing invoice", Toast.LENGTH_LONG).show()
                        AlertDialog.Builder(this@ReturnCartActivity)
                            .setTitle("Error")
                            .setMessage("Error parsing invoice")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                }
            }
        })
    }

    /**
     * Creates a new return invoice by cloning the original invoice JSON and then modifying
     * only the necessary fields.
     *
     * After saving the return invoice, it is automatically submitted.
     */
    private fun completeReturn() {
        if (originalInvoiceData == null) {
            Toast.makeText(this, "Original invoice data not loaded", Toast.LENGTH_SHORT).show()
            AlertDialog.Builder(this@ReturnCartActivity)
                .setTitle("Error")
                .setMessage("Original invoice data not loaded")
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .show()
            return
        }

        // Clone the original JSON.
        val returnInvoiceData = JSONObject(originalInvoiceData.toString())

        // Remove fields that should not be copied.
        returnInvoiceData.remove("name")
        returnInvoiceData.remove("docstatus")
        returnInvoiceData.remove("creation")
        returnInvoiceData.remove("modified")
        returnInvoiceData.remove("modified_by")
        returnInvoiceData.remove("outstanding_amount") // Ensure outstanding is recalculated

        // Set return flags.
        returnInvoiceData.put("is_return", 1)
        returnInvoiceData.put("return_against", originalInvoiceId)

        // Invert payment fields (make them negative).
        if (returnInvoiceData.has("paid_amount")) {
            val originalPaid = returnInvoiceData.optDouble("paid_amount", 0.0)
            returnInvoiceData.put("paid_amount", -originalPaid)
        }
        if (returnInvoiceData.has("base_paid_amount")) {
            val originalBasePaid = returnInvoiceData.optDouble("base_paid_amount", 0.0)
            returnInvoiceData.put("base_paid_amount", -originalBasePaid)
        }

        // Update items: invert quantities and recalculate amounts.
        val itemsArray = returnInvoiceData.optJSONArray("items")
        if (itemsArray != null) {
            for (i in 0 until itemsArray.length()) {
                val item = itemsArray.getJSONObject(i)
                val qty = item.optDouble("qty", 0.0)
                // Invert quantity (if already negative, it stays negative)
                item.put("qty", -qty)
                // Recalculate amount as rate * new qty
                val rate = item.optDouble("rate", 0.0)
                item.put("amount", rate * -qty)
                // Invert stock_qty if present
                if (item.has("stock_qty")) {
                    val stockQty = item.optDouble("stock_qty", 0.0)
                    item.put("stock_qty", -stockQty)
                }
            }
        }

        // Invert parent-level quantity fields and totals.
        val totals = arrayOf("total", "net_total", "grand_total", "base_grand_total", "rounded_total", "total_qty")
        totals.forEach { field ->
            if (returnInvoiceData.has(field)) {
                returnInvoiceData.put(field, -returnInvoiceData.optDouble(field, 0.0))
            }
        }

        // Invert tax totals.
        returnInvoiceData.put("total_taxes_and_charges", -returnInvoiceData.optDouble("total_taxes_and_charges", 0.0))
        returnInvoiceData.put("base_total_taxes_and_charges", -returnInvoiceData.optDouble("base_total_taxes_and_charges", 0.0))

        // Ensure paid_amount does not exceed grand_total in absolute terms.
        val grandTotal = returnInvoiceData.optDouble("grand_total", 0.0)
        val paidAmount = returnInvoiceData.optDouble("paid_amount", 0.0)
        if (Math.abs(paidAmount) > Math.abs(grandTotal)) {
            returnInvoiceData.put("paid_amount", grandTotal)
        }

        // Round grand_total and paid_amount to no decimal precision.
        val rawGrandTotal = returnInvoiceData.optDouble("grand_total", 0.0)
        val roundedGrandTotal = Math.round(rawGrandTotal).toDouble()
        returnInvoiceData.put("grand_total", roundedGrandTotal)

        val rawPaidAmount = returnInvoiceData.optDouble("paid_amount", 0.0)
        val roundedPaidAmount = Math.round(rawPaidAmount).toDouble()
        if (Math.abs(roundedPaidAmount) > Math.abs(roundedGrandTotal)) {
            returnInvoiceData.put("paid_amount", roundedGrandTotal)
        } else {
            returnInvoiceData.put("paid_amount", roundedPaidAmount)
        }

        // Process Payment Table rows, if any.
        if (returnInvoiceData.has("payments")) {
            val paymentsArray = returnInvoiceData.getJSONArray("payments")
            for (i in 0 until paymentsArray.length()) {
                val paymentRow = paymentsArray.getJSONObject(i)
                val originalPaymentAmount = paymentRow.optDouble("amount", 0.0)
                // Force the payment row amount to be negative (round to no decimals)
                val newPaymentAmount = -Math.round(Math.abs(originalPaymentAmount)).toDouble()
                paymentRow.put("amount", newPaymentAmount)
            }
        }

        // --- Merge tax details from the original invoice ---
        // Ensure that the 'included_in_print_rate' field is set correctly.
        // If the original tax record does not have it (or is not loaded), force a default value (e.g., 1).
        // Process Taxes: assign the included_in_print_rate checkbox from original invoice.
        // Do NOT remove taxes from returnInvoiceData before the if condition.
// returnInvoiceData.remove("taxes")

        if (originalInvoiceData?.has("taxes") == true) {
            val origTaxes = originalInvoiceData!!.getJSONArray("taxes")
            if (origTaxes.length() == 0) {
                // Original invoice has an empty taxes array; remove taxes from returnInvoiceData.
                returnInvoiceData.remove("taxes")
            } else if (returnInvoiceData.has("taxes")) {
                // Merge the checkbox value for each tax in the return invoice.
                val returnTaxes = returnInvoiceData.getJSONArray("taxes")
                for (i in 0 until returnTaxes.length()) {
                    val taxRow = returnTaxes.getJSONObject(i)
                    var includedInPrintRate = false
                    // Look for a matching tax row in the original invoice.
                    for (j in 0 until origTaxes.length()) {
                        val origTax = origTaxes.getJSONObject(j)
                        if (taxRow.optString("account_head") == origTax.optString("account_head")) {
                            includedInPrintRate = origTax.optBoolean("included_in_print_rate", false)
                            break
                        }
                    }
                    taxRow.put("included_in_print_rate", includedInPrintRate)
                }
            }
        } else {
            // If the original invoice doesn't have a "taxes" field, remove taxes from returnInvoiceData.
            returnInvoiceData.remove("taxes")
        }




        // --- End of tax merging ---

        Log.d("ReturnCartActivity", "Return Payload: ${returnInvoiceData}")

        // Get session and ERPNext details.
        val sharedPreferences = getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)

        if (sessionCookie == null || erpnextUrl == null) {
            Toast.makeText(this, "Session expired", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences("session_cookie", Context.MODE_PRIVATE)
        val invoiceType = prefs.getString("invoice_type", "")

        // Post the return invoice.
        postReturnInvoice(returnInvoiceData, sessionCookie, erpnextUrl, invoiceType,
            originalInvoiceData!!
        )
    }



    // -------------------------------------------------------------------------------------
// Posts the return invoice payload to ERPNext.
    private fun postReturnInvoice(
        returnInvoiceData: JSONObject,
        sessionCookie: String,
        erpnextUrl: String,
        invoiceType: String?,

        originalInvoiceData: JSONObject // Pass the original invoice data here
    ) {
        // Merge tax details: copy included_in_print_rate from the original invoice
        if (originalInvoiceData.has("taxes") && returnInvoiceData.has("taxes")) {
            val originalTaxes = originalInvoiceData.getJSONArray("taxes")
            val returnTaxes = returnInvoiceData.getJSONArray("taxes")
            for (i in 0 until returnTaxes.length()) {
                val returnTax = returnTaxes.getJSONObject(i)
                // Option: match taxes by a unique key (for example, "account_head" or "description")
                for (j in 0 until originalTaxes.length()) {
                    val origTax = originalTaxes.getJSONObject(j)
                    if (returnTax.optString("account_head") == origTax.optString("account_head")) {
                        // Copy the included_in_print_rate field from original tax entry
                        returnTax.put("included_in_print_rate", origTax.opt("included_in_print_rate"))
                        break
                    }
                }
            }
        }

        // Now continue with building the URL based on invoice type
        val url = if (invoiceType == "sales_invoice") {
            "https://$erpnextUrl/api/resource/Sales%20Invoice"
        } else {
            "https://$erpnextUrl/api/resource/POS%20Invoice"
        }
        val requestBody = returnInvoiceData.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Cookie", sessionCookie)
            .addHeader("Content-Type", "application/json")
            .build()

        getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@ReturnCartActivity, "Failed to complete return: ${e.message}", Toast.LENGTH_LONG).show()
                    AlertDialog.Builder(this@ReturnCartActivity)
                        .setTitle("Error")
                        .setMessage("Failed to complete return: ${e.message}")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                Log.d("ReturnCartActivity", "Return Save Response: $responseBody")
                if (!response.isSuccessful) {
                    // Check for mandatory errors.
                    if (responseBody != null && responseBody.contains("MandatoryError")) {
                        handleReturnMandatoryError(responseBody, returnInvoiceData, invoiceType ?: "", sessionCookie, erpnextUrl,originalInvoiceData)
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@ReturnCartActivity, "Error: ${response.message}", Toast.LENGTH_LONG).show()
                            AlertDialog.Builder(this@ReturnCartActivity)
                                .setTitle("Error")
                                .setMessage("Error: ${response.message}")
                                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                .show()
                        }
                    }
                } else {
                    try {
                        // Parse response to get the new return invoice name.
                        val json = JSONObject(responseBody)
                        val data = json.getJSONObject("data")
                        val docName = data.getString("name")
                        // Now submit the return invoice.
                        submitReturnInvoice(docName)
                    } catch (e: JSONException) {
                        runOnUiThread {
                            Toast.makeText(this@ReturnCartActivity, "Return created but failed to parse response", Toast.LENGTH_LONG).show()
                            AlertDialog.Builder(this@ReturnCartActivity)
                                .setTitle("Error")
                                .setMessage("Return created but failed to parse response")
                                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                .show()
                        }
                    }
                }
            }
        })
    }


    // -------------------------------------------------------------------------------------
// Handles mandatory field errors for the return invoice.
    private fun handleReturnMandatoryError(
        responseBody: String,
        returnInvoiceData: JSONObject,
        invoiceType: String,
        sessionCookie: String,
        erpnextUrl: String,
        originalInvoiceData: JSONObject
    ) {
        try {
            val errorJson = JSONObject(responseBody)
            val missingFields = mutableListOf<String>()

            // Parse exception message.
            val exceptionMessage = errorJson.optString("exception", "")
            if (exceptionMessage.contains("MandatoryError")) {
                val parts = exceptionMessage.split(":")
                if (parts.size >= 2) {
                    val fieldsPart = parts.last().trim()
                    missingFields.addAll(fieldsPart.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                }
            }

            // Parse missing fields from _server_messages.
            val serverMessages: JSONArray? = errorJson.optJSONArray("_server_messages")
            if (serverMessages != null) {
                for (i in 0 until serverMessages.length()) {
                    val msgString = serverMessages.optString(i)
                    try {
                        val msgJson = JSONObject(msgString)
                        val message = msgJson.optString("message", "")
                        if (message.contains("Value missing for Sales Invoice:") ||
                            message.contains("Value missing for POS Invoice:")
                        ) {
                            val fieldName = message.substringAfter(":").trim()
                            val key = fieldName.replace(" ", "_").lowercase()
                            if (!missingFields.contains(key)) {
                                missingFields.add(key)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ReturnError", "Error parsing server message: ${e.message}")
                    }
                }
            }

            if (missingFields.isNotEmpty()) {
                Log.d("ReturnError", "Missing fields: $missingFields")
                runOnUiThread {
                    fetchMissingFieldMetasForReturn(missingFields, sessionCookie, erpnextUrl, invoiceType) { metaMap ->
                        showMandatoryFieldsDialogForReturn(metaMap, returnInvoiceData, invoiceType, sessionCookie, erpnextUrl,originalInvoiceData)
                    }
                }
            } else {
                runOnUiThread {
                    Toast.makeText(this@ReturnCartActivity, "Unknown error: $responseBody", Toast.LENGTH_LONG).show()
                    AlertDialog.Builder(this@ReturnCartActivity)
                        .setTitle("Unknown Error")
                        .setMessage("Unknown error: $responseBody")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this@ReturnCartActivity, "Error parsing error response: ${e.message}", Toast.LENGTH_LONG).show()
                AlertDialog.Builder(this@ReturnCartActivity)
                    .setTitle("Error")
                    .setMessage("Error parsing error response: ${e.message}")
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
        }
    }

    // -------------------------------------------------------------------------------------
// Fetch metadata for each missing field.
    private fun fetchMissingFieldMetasForReturn(
        missingFields: List<String>,
        sessionCookie: String,
        erpnextUrl: String,
        invoiceType: String,
        callback: (Map<String, AddCustomerActivity.FieldMeta>) -> Unit
    ) {
        val metaMap = mutableMapOf<String, AddCustomerActivity.FieldMeta>()
        var count = 0
        val parentDoctype = if (invoiceType == "sales_invoice") "Sales Invoice" else "POS Invoice"
        for (field in missingFields) {
            val metaUrl =
                "https://$erpnextUrl/api/resource/DocField?filters=[[\"parent\",\"=\",\"$parentDoctype\"],[\"fieldname\",\"=\",\"$field\"]]"
            val request = Request.Builder()
                .url(metaUrl)
                .get()
                .addHeader("Cookie", sessionCookie)
                .build()

            getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    metaMap[field] = AddCustomerActivity.FieldMeta(field, "Data", null)
                    synchronized(this) {
                        count++
                        if (count == missingFields.size) {
                            runOnUiThread { callback(metaMap) }
                        }
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string()
                    try {
                        val json = JSONObject(body)
                        val dataArray = json.optJSONArray("data")
                        if (dataArray != null && dataArray.length() > 0) {
                            val fieldObj = dataArray.getJSONObject(0)
                            val fieldType = fieldObj.optString("fieldtype", "Data").lowercase()
                            val options = fieldObj.optString("options", "")
                            val finalFieldType = when {
                                fieldType == "link" -> "Link"
                                fieldType == "select" -> "Select"
                                else -> "Data"
                            }
                            val optionsDoctype = when (finalFieldType) {
                                "Link" -> options
                                "Select" -> options.split("\n").joinToString(",")
                                else -> null
                            }
                            metaMap[field] =
                                AddCustomerActivity.FieldMeta(field, finalFieldType, optionsDoctype)
                        } else {
                            metaMap[field] = AddCustomerActivity.FieldMeta(field, "Data", null)
                        }
                    } catch (e: Exception) {
                        metaMap[field] = AddCustomerActivity.FieldMeta(field, "Data", null)
                    }
                    synchronized(this) {
                        count++
                        if (count == missingFields.size) {
                            runOnUiThread { callback(metaMap) }
                        }
                    }
                }
            })
        }
    }

    // -------------------------------------------------------------------------------------
// Display a dialog prompting the user for the missing fields.
    private fun showMandatoryFieldsDialogForReturn(
        metaMap: Map<String, AddCustomerActivity.FieldMeta>,
        returnInvoiceData: JSONObject,
        invoiceType: String,
        sessionCookie: String,
        erpnextUrl: String,
        originalInvoiceData: JSONObject
    ) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Additional Information Required")
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val fieldViews = mutableMapOf<String, View>()

        for ((field, meta) in metaMap) {
            Log.d("ReturnField", "Creating UI for field: $field | Type: ${meta.fieldType} | Options: ${meta.optionsDoctype}")
            val label = TextView(this).apply {
                text = formatFieldName(field)
            }
            layout.addView(label)

            when (meta.fieldType) {
                "Link" -> {
                    if (!meta.optionsDoctype.isNullOrBlank()) {
                        val progressBar = ProgressBar(this)
                        layout.addView(progressBar)
                        fetchLinkDataForReturn(meta.optionsDoctype, sessionCookie, erpnextUrl) { options ->
                            runOnUiThread {
                                layout.removeView(progressBar)
                                if (options.isNotEmpty()) {
                                    val spinner = Spinner(this).apply {
                                        adapter = ArrayAdapter(
                                            this@ReturnCartActivity,
                                            android.R.layout.simple_spinner_item,
                                            options
                                        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                                    }
                                    layout.addView(spinner)
                                    fieldViews[field] = spinner
                                } else {
                                    createTextInputForReturn(field, layout, fieldViews)
                                }
                            }
                        }
                    } else {
                        createTextInputForReturn(field, layout, fieldViews)
                    }
                }

                "Select" -> {
                    val options = meta.optionsDoctype?.split(",")?.map { it.trim() } ?: emptyList()
                    if (options.isNotEmpty()) {
                        val spinner = Spinner(this).apply {
                            adapter = ArrayAdapter(
                                this@ReturnCartActivity,
                                android.R.layout.simple_spinner_item,
                                options
                            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                        }
                        layout.addView(spinner)
                        fieldViews[field] = spinner
                    } else {
                        createTextInputForReturn(field, layout, fieldViews)
                    }
                }

                else -> {
                    createTextInputForReturn(field, layout, fieldViews)
                }
            }
        }

        builder.setView(layout)
        builder.setPositiveButton("Submit") { dialog, which ->
            for ((field, view) in fieldViews) {
                when (view) {
                    is EditText -> {
                        val value = view.text.toString().trim()
                        if (value.isNotEmpty()) {
                            returnInvoiceData.put(field, value)
                        }
                    }
                    is Spinner -> {
                        val selected = view.selectedItem?.toString() ?: ""
                        if (selected.isNotEmpty()) {
                            returnInvoiceData.put(field, selected)
                        }
                    }
                }
            }
            Log.d("ReturnInvoiceUpdate", "Updated return invoice payload: $returnInvoiceData")
            postReturnInvoice(returnInvoiceData, sessionCookie, erpnextUrl, invoiceType,originalInvoiceData)
        }
        builder.setNegativeButton("Cancel") { dialog, which -> dialog.dismiss() }
        builder.show()
    }

    // -------------------------------------------------------------------------------------
// Fetch options for a Link field.
    private fun fetchLinkDataForReturn(
        linkDoctype: String,
        sessionCookie: String,
        erpnextUrl: String,
        callback: (List<String>) -> Unit
    ) {
        val url = "https://$erpnextUrl/api/resource/$linkDoctype?fields=[\"name\"]&limit_page_length=100"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Cookie", sessionCookie)
            .build()

        getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(emptyList())
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (!response.isSuccessful || responseBody == null) {
                    callback(emptyList())
                    return
                }
                try {
                    val json = JSONObject(responseBody)
                    val dataArray = json.getJSONArray("data")
                    val options = mutableListOf<String>()
                    for (i in 0 until dataArray.length()) {
                        val obj = dataArray.getJSONObject(i)
                        options.add(obj.optString("name"))
                    }
                    callback(options)
                } catch (e: Exception) {
                    callback(emptyList())
                }
            }
        })
    }

    // -------------------------------------------------------------------------------------
// Create a simple text input if no Link/Select options are available.
    private fun createTextInputForReturn(
        field: String,
        layout: LinearLayout,
        fieldViews: MutableMap<String, View>
    ) {
        val editText = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            hint = "Enter ${formatFieldName(field)}"
            inputType = when {
                field.contains("date", true) -> InputType.TYPE_CLASS_DATETIME
                field.contains("email", true) -> InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                field.contains("phone", true) -> InputType.TYPE_CLASS_PHONE
                else -> InputType.TYPE_CLASS_TEXT
            }
        }
        layout.addView(editText)
        fieldViews[field] = editText
    }

    // -------------------------------------------------------------------------------------
// Helper: Format field names (e.g. "customer_name" → "Customer Name").
    private fun formatFieldName(field: String): String {
        return field.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }


    /**
     * Submits the return invoice by updating its docstatus to 1.
     * After a successful update, the full invoice details are fetched and passed
     * to the next activity (e.g., ReceiptActivity).
     */
    private fun submitReturnInvoice(docName: String) {
        val sharedPreferences = getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
            ?: run {
                runOnUiThread {
                    Toast.makeText(this@ReturnCartActivity, "Session expired", Toast.LENGTH_SHORT).show()
                    AlertDialog.Builder(this@ReturnCartActivity)
                        .setTitle("Error")
                        .setMessage("Session expired")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
                return
            }
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)
            ?: run {
                runOnUiThread {
                    Toast.makeText(this@ReturnCartActivity, "Domain not configured", Toast.LENGTH_SHORT).show()
                    AlertDialog.Builder(this@ReturnCartActivity)
                        .setTitle("Error")
                        .setMessage("Domain not configured")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
                return
            }

        // Build the URL to update the Sales Invoice document.
        val prefs = getSharedPreferences("session_cookie", Context.MODE_PRIVATE)
        val invoiceType = prefs.getString("invoice_type", "")
        val submitUrl = if (invoiceType == "sales_invoice") {
            "https://$erpnextUrl/api/resource/Sales%20Invoice/${URLEncoder.encode(docName, "UTF-8")}"
        } else {
            "https://$erpnextUrl/api/resource/POS%20Invoice/${URLEncoder.encode(docName, "UTF-8")}"
        }
        Log.d("ReturnCartActivity", "Submit URL: $submitUrl")

        // Build the payload to update docstatus to 1.
        val submitPayload = JSONObject().apply {
            put("data", JSONObject().apply {
                put("docstatus", 1)
            })
        }
        Log.d("ReturnCartActivity", "Submit payload: $submitPayload")

        val request = Request.Builder()
            .url(submitUrl)
            .addHeader("Cookie", sessionCookie)
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json")
            .put(
                RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    submitPayload.toString()
                )
            )
            .build()

        getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("ReturnCartActivity", "Network error during submit: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this@ReturnCartActivity, "Network error during submit: ${e.message}", Toast.LENGTH_LONG).show()
                    AlertDialog.Builder(this@ReturnCartActivity)
                        .setTitle("Network Error")
                        .setMessage("Network error during submit: ${e.message}")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                Log.d("ReturnCartActivity", "Submit response code: ${response.code}, body: $responseBody")
                if (!response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(
                            this@ReturnCartActivity,
                            "HTTP ${response.code} during submit: ${response.message}\n$responseBody",
                            Toast.LENGTH_LONG
                        ).show()
                        AlertDialog.Builder(this@ReturnCartActivity)
                            .setTitle("Error")
                            .setMessage("HTTP ${response.code} during submit: ${response.message}\n" +
                                    "$responseBody")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                    return
                }
                // After successful submission, fetch the complete invoice details.
                fetchFullInvoice(docName) { invoiceJson, error ->
                    runOnUiThread {
                        invoiceJson?.let {
                            // Parse the JSON into an ERPNextInvoice object.
                            val erpInvoice = parseInvoice(it)
                            // Start ReceiptActivity and pass the ERPNextInvoice object.
                            val intent = Intent(this@ReturnCartActivity, ReceiptActivity::class.java).apply {
                                putExtra("INVOICE", erpInvoice)
                            }
                            startActivity(intent)
                            finish()
                        } ?: run {
                            Toast.makeText(
                                this@ReturnCartActivity,
                                "Payment successful but no invoice received: $error",
                                Toast.LENGTH_SHORT
                            ).show()
                            AlertDialog.Builder(this@ReturnCartActivity)
                                .setTitle("Error")
                                .setMessage("Payment successful but no invoice received: $error")
                                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                .show()
                            finish()
                        }
                    }
                }
            }
        })
    }

    /**
     * Fetches the full invoice details using its identifier.
     *
     * @param invoiceIdentifier The unique identifier of the invoice.
     * @param callback A lambda with two parameters: a JSONObject representing the invoice (or null)
     * and an error message (or null).
     */
    private fun fetchFullInvoice(invoiceIdentifier: String, callback: (JSONObject?, String?) -> Unit) {
        val sharedPreferences = getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
            ?: run {
                callback(null, "Session expired")
                return
            }
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)
            ?: run {
                callback(null, "Domain not configured")
                return
            }
        val prefs = getSharedPreferences("session_cookie", Context.MODE_PRIVATE)
        val invoiceType = prefs.getString("invoice_type", "")
        val fetchUrl = if (invoiceType == "sales_invoice") {
            "https://$erpnextUrl/api/resource/Sales%20Invoice/${URLEncoder.encode(invoiceIdentifier, "UTF-8")}"
        } else {
            "https://$erpnextUrl/api/resource/POS%20Invoice/${URLEncoder.encode(invoiceIdentifier, "UTF-8")}"
        }
        Log.d("ReturnCartActivity", "Fetch URL: $fetchUrl")

        val request = Request.Builder()
            .url(fetchUrl)
            .addHeader("Cookie", sessionCookie)
            .get()
            .build()

        getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null, "Network error during fetching invoice: ${e.message}")
                AlertDialog.Builder(this@ReturnCartActivity)
                    .setTitle("Network Error")
                    .setMessage("Network error during fetching invoice: ${e.message}")
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .show()
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                Log.d("ReturnCartActivity", "Fetch response code: ${response.code}, body: $responseBody")
                if (!response.isSuccessful) {
                    callback(null, "HTTP ${response.code} during fetching invoice: ${response.message}\n$responseBody")
                    AlertDialog.Builder(this@ReturnCartActivity)
                        .setTitle("Error")
                        .setMessage("HTTP ${response.code} during fetching invoice: ${response.message}\n" +
                                "$responseBody")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                    return
                }
                try {
                    val json = JSONObject(responseBody)
                    val data = json.getJSONObject("data")
                    callback(data, null)
                } catch (e: Exception) {
                    callback(null, "Error parsing invoice: ${e.message}")
                }
            }
        })
    }

    /**
     * Parses a JSONObject into an ERPNextInvoice.
     */
    private fun parseInvoice(json: JSONObject): ERPNextInvoice {
        // Parse basic fields
        val name = json.optString("name")
        val customer = json.optString("customer")
        val total = json.optDouble("total", 0.0)
        val grandTotal = json.optDouble("grand_total", 0.0)
        val status = json.optString("status")
        val docStatus = json.optInt("docstatus", 0)

        // Parse invoice items (if available under "items")
        val items = mutableListOf<InvoiceItem>()
        val itemsArray = json.optJSONArray("items")
        if (itemsArray != null) {
            for (i in 0 until itemsArray.length()) {
                val itemJson = itemsArray.getJSONObject(i)
                val itemCode = itemJson.optString("item_code")
                val qty = itemJson.optDouble("qty", 0.0).toInt()
                val rate = itemJson.optDouble("rate", 0.0)
                items.add(InvoiceItem(item_code = itemCode, qty = qty, rate = rate))
            }
        }

        // Parse payments (if available under "payments")
        val payments = mutableListOf<InvoicePayment>()
        val paymentsArray = json.optJSONArray("payments")
        if (paymentsArray != null) {
            for (i in 0 until paymentsArray.length()) {
                val paymentJson = paymentsArray.getJSONObject(i)
                val mode = paymentJson.optString("mode_of_payment")
                val amount = paymentJson.optDouble("amount", 0.0)
                payments.add(InvoicePayment(mode_of_payment = mode, amount = amount))
            }
        }

        return ERPNextInvoice(
            name = name,
            customer = customer,
            total = total,
            items = items,
            payments = payments,
            id = json.optString("id"),
            amount = total,
            date = json.optString("posting_date"),
            grandTotal = grandTotal,
            currency = json.optString("currency"),
            company = json.optString("company"),
            companyaddress = json.optString("company_address"),
            customername = json.optString("customer_name"),
            companytaxid = json.optString("tax_id"),
            invoiceNumber = json.optString("invoice_number"),
            status = status,
            docStatus = docStatus,
            companyaddressdisplay = json.optString("company_address_display"),
            modified = json.optString("modified")
        )
    }

    /**
     * Returns an OkHttpClient that accepts all SSL certificates.
     * (For development only—do not use in production.)
     */
    private fun getUnsafeOkHttpClient(): OkHttpClient {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())

            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        } catch (e: Exception) {
            throw RuntimeException("Failed to create unsafe OkHttpClient", e)
        }
    }

    // --- CartAdapter.CartItemListener implementations ---
    override fun onQuantityChanged(itemCode: String, newQuantity: Int) {
        viewModel.updateCartItem(itemCode, newQuantity)
        updateTotalAmount()
    }

    override fun onPriceChanged(itemCode: String, newPrice: Double) {
        viewModel.updateCartItemPrice(itemCode, newPrice)
        updateTotalAmount()
    }

    override fun onRemoveItem(itemCode: String) {
        viewModel.removeCartItem(itemCode)
    }
}
