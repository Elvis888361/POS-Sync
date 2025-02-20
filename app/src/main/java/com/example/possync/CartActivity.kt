package com.example.possync

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
import com.google.android.material.floatingactionbutton.FloatingActionButton
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
import java.text.SimpleDateFormat
import java.util.Date
import javax.net.ssl.*

class CartActivity : AppCompatActivity(), CartAdapter.CartItemListener {

    // New member variables to store the draft invoice ID and its customer.
    private var currentDraftInvoiceId: String? = null
    private var loadedDraftCustomer: String? = null

    private lateinit var viewModel: POSViewModel
    private lateinit var adapter: CartAdapter
    private lateinit var customerSpinner: Spinner

    private lateinit var fabAddCustomField: FloatingActionButton
    private lateinit var customFieldsContainer: LinearLayout
    data class CustomField(
        val fieldName: String,
        val fieldValue: String,
        val fieldLabel: String

    )
    fun formatFieldLabel(fieldName: String): String {
        // Converts "custom_defer_etims_submission" to "Custom Defer Etims Submission"
        return fieldName.split("_")
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_cart)

        // Check if a draft invoice ID was passed from SalesListActivity.
        // If so, store it and load the draft.
        val draftInvoiceId = intent.getStringExtra("DRAFT_INVOICE_ID")
        if (draftInvoiceId != null) {
            currentDraftInvoiceId = draftInvoiceId
            loadDraftInvoice(draftInvoiceId)
        }

        viewModel = ViewModelProvider(this).get(POSViewModel::class.java)
        customerSpinner = findViewById(R.id.customerSpinner)

        setupRecyclerView()
        observeCart()
        fetchCustomers()
        fabAddCustomField = findViewById(R.id.fabAddCustomField)
        customFieldsContainer = findViewById(R.id.customFieldsContainer)

        // Set FAB click listener to show dialog for adding a custom field
        fabAddCustomField.setOnClickListener {
            showAddCustomFieldDialog()
        }

        // Checkout button click handler
        findViewById<Button>(R.id.btnCheckout).setOnClickListener {
            val customer = customerSpinner.selectedItem?.toString()
            val cartItems = viewModel.cartItems.value ?: emptyList()
                // Create a list to hold the custom fields.
            val customFields = mutableListOf<CustomField>()

            // Iterate over each child view in the customFieldsContainer.
            for (i in 0 until customFieldsContainer.childCount) {
                val child = customFieldsContainer.getChildAt(i)
                // Assume each custom field view is a LinearLayout with:
                // a TextView (showing the field label) and an EditText (for the field value)
                if (child is LinearLayout && child.childCount >= 2) {
                    val labelView = child.getChildAt(0) as? TextView
                    val inputView = child.getChildAt(1) as? EditText
                    if (labelView != null && inputView != null) {
                        // Get the text from the label view.
                        // This value might be something like "Custom Defer Etims Submission".
                        val rawLabel = labelView.text.toString()

                        // Derive a field name from the label:
                        // e.g. "Custom Defer Etims Submission" becomes "custom_defer_etims_submission"
                        val fieldName = rawLabel.replace(" ", "_").lowercase()

                        // Generate a properly formatted label from the field name.
                        val fieldLabel = formatFieldLabel(fieldName)

                        // Get the user-entered value from the EditText.
                        val fieldValue = inputView.text.toString()

                        // Create a CustomField object and add it to the list.
                        customFields.add(CustomField(fieldName, fieldValue, fieldLabel))
                    }
                }
            }

            if (customer.isNullOrEmpty() || cartItems.isEmpty()) {
                Toast.makeText(this, "Please select customer and add items", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            proceedToCheckout(customer, cartItems, customFields)
        }
    }
    private fun showAddCustomFieldDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Add Custom Field")

        // Create a vertical LinearLayout to hold dialog input fields
        val dialogLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        // EditText for entering the field name
        val fieldNameEditText = EditText(this).apply {
            hint = "Field Name"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        dialogLayout.addView(fieldNameEditText)

        // EditText for entering a default value or description
        val fieldValueEditText = EditText(this).apply {
            hint = "Default Value"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        dialogLayout.addView(fieldValueEditText)

        builder.setView(dialogLayout)

        builder.setPositiveButton("Add") { dialog, _ ->
            val fieldName = fieldNameEditText.text.toString().trim()
            val fieldValue = fieldValueEditText.text.toString().trim()
            if (fieldName.isNotEmpty()) {
                // Save the custom field in SharedPreferences (acting as cookies)
                saveCustomField(fieldName, fieldValue)
                // Dynamically add the custom field view to the container
                addCustomFieldToUI(fieldName, fieldValue)
            } else {
                Toast.makeText(this, "Field name cannot be empty", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.dismiss() }
        builder.show()
    }

    private fun saveCustomField(fieldName: String, fieldValue: String) {
        val sharedPreferences = getSharedPreferences("CustomFields", Context.MODE_PRIVATE)
        val editor = sharedPreferences.edit()
        // Save the field name and its default value
        editor.putString(fieldName, fieldValue)
        editor.apply()
    }

    private fun addCustomFieldToUI(fieldName: String, fieldValue: String) {
        // Create a horizontal layout to hold the label and input field
        val fieldLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(8, 8, 8, 8)
        }

        // Label for the custom field
        val label = TextView(this).apply {
            text = fieldName
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        fieldLayout.addView(label)

        // Input field for the user to enter data later; the hint shows the default value
        val input = EditText(this).apply {
            hint = fieldValue
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        fieldLayout.addView(input)

        // Add the new field layout to the custom fields container
        customFieldsContainer.addView(fieldLayout)
    }
    /**
     * Loads a draft invoice from ERPNext using its ID.
     * In addition to the invoice items, it also reads the customer.
     */
    private fun loadDraftInvoice(invoiceId: String) {
        val sharedPreferences = getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)

        if (sessionCookie == null || erpnextUrl == null) {
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_LONG).show()
            return
        }

        // Construct the URL to fetch a single Sales Invoice record by its ID.
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
                    Toast.makeText(this@CartActivity, "Failed to load draft invoice: ${e.message}", Toast.LENGTH_LONG).show()
                    AlertDialog.Builder(this@CartActivity)
                        .setTitle("Error")
                        .setMessage("Failed to load draft invoice: ${e.message}")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(this@CartActivity, "Error loading draft invoice: ${response.message}", Toast.LENGTH_LONG).show()
                        AlertDialog.Builder(this@CartActivity)
                            .setTitle("Error")
                            .setMessage("Error loading draft invoice: ${response.message}")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                    return
                }
                val responseBody = response.body?.string()
                try {
                    val json = JSONObject(responseBody)
                    val data = json.getJSONObject("data")

                    // Store the customer from the draft invoice.
                    val customerFromInvoice = data.optString("customer", "")
                    if (customerFromInvoice.isNotEmpty()) {
                        loadedDraftCustomer = customerFromInvoice
                    }

                    // Extract invoice items
                    val itemsArray = data.optJSONArray("items")
                    val cartItems = mutableListOf<CartItem>()
                    if (itemsArray != null) {
                        for (i in 0 until itemsArray.length()) {
                            val itemObj = itemsArray.getJSONObject(i)
                            val itemCode = itemObj.optString("item_code", "")
                            val qty = itemObj.optInt("qty", 0)
                            val rate = itemObj.optDouble("rate", 0.0)
                            val uom = itemObj.optString("uom", "")
                            // Create a CartItem (adjust the properties as needed)
                            val cartItem = CartItem(
                                itemCode = itemCode,
                                quantity = qty,
                                price = rate,
                                name = itemCode,   // You can use a proper name if available
                                uom = uom          // Update if unit-of-measure is provided
                            )
                            cartItems.add(cartItem)
                        }
                    }
                    runOnUiThread {
                        (viewModel.cartItems as? MutableLiveData)?.value = cartItems
                        Toast.makeText(this@CartActivity, "Draft invoice loaded: $invoiceId", Toast.LENGTH_SHORT).show()
                        AlertDialog.Builder(this@CartActivity)
                            .setTitle("Error")
                            .setMessage("Draft invoice loaded: $invoiceId")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }

                } catch (e: JSONException) {
                    runOnUiThread {
                        Toast.makeText(this@CartActivity, "Error parsing draft invoice", Toast.LENGTH_LONG).show()
                        AlertDialog.Builder(this@CartActivity)
                            .setTitle("Error")
                            .setMessage("Error parsing draft invoice")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                }
            }
        })
    }

    private fun setupRecyclerView() {
        adapter = CartAdapter(this)
        findViewById<RecyclerView>(R.id.cartRecycler).apply {
            layoutManager = LinearLayoutManager(this@CartActivity)
            adapter = this@CartActivity.adapter
        }
    }

    private fun observeCart() {
        viewModel.cartItems.observe(this) { items ->
            adapter.submitList(items)
            updateTotalAmount()
        }
    }

    private fun updateTotalAmount() {
        val total = viewModel.cartItems.value?.sumOf { it.price * it.quantity } ?: 0.0
        findViewById<TextView>(R.id.totalAmount).text = "Total: $${"%.2f".format(total)}"
    }

    /**
     * Fetches customers and populates the spinner.
     * If a draft invoice had a customer loaded, it selects that customer.
     */
    private fun fetchCustomers() {
        val sharedPreferences = getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)

        if (sessionCookie == null || erpnextUrl == null) {
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_LONG).show()
            return
        }

        val filters = "[[\"Customer\", \"disabled\", \"=\", 0]]"
        val fields = "[\"name\", \"customer_name\"]"
        val url = "https://$erpnextUrl/api/resource/Customer?filters=${URLEncoder.encode(filters, "UTF-8")}&fields=${URLEncoder.encode(fields, "UTF-8")}"

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Cookie", sessionCookie)
            .build()

        getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@CartActivity, "Failed to fetch customers: ${e.message}", Toast.LENGTH_LONG).show()
                    AlertDialog.Builder(this@CartActivity)
                        .setTitle("Error")
                        .setMessage("Failed to fetch customers: ${e.message}")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (!response.isSuccessful) {
                        Toast.makeText(this@CartActivity, "Failed to fetch customers: ${response.message}", Toast.LENGTH_LONG).show()
                        AlertDialog.Builder(this@CartActivity)
                            .setTitle("Error")
                            .setMessage("Failed to fetch customers: ${response.message}")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    } else {
                        val responseBody = response.body?.string()
                        responseBody?.let {
                            try {
                                val jsonObject = JSONObject(it)
                                val dataArray = jsonObject.getJSONArray("data")
                                val customerList = mutableListOf<String>()

                                for (i in 0 until dataArray.length()) {
                                    val customer = dataArray.getJSONObject(i)
                                    val customerName = customer.getString("name")
                                    customerList.add(customerName)
                                }

                                val adapter = ArrayAdapter(
                                    this@CartActivity,
                                    android.R.layout.simple_spinner_item,
                                    customerList
                                )
                                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                                customerSpinner.adapter = adapter

                                // If a customer was loaded from the draft invoice, select it.
                                loadedDraftCustomer?.let { customer ->
                                    val index = customerList.indexOf(customer)
                                    if (index >= 0) {
                                        customerSpinner.setSelection(index)
                                    }
                                }
                            } catch (e: Exception) {
                                Toast.makeText(this@CartActivity, "Error parsing customer data", Toast.LENGTH_LONG).show()
                                AlertDialog.Builder(this@CartActivity)
                                    .setTitle("Error")
                                    .setMessage("Error parsing customer data")
                                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                    .show()
                            }
                        }
                    }
                }
            }
        })
    }

    /**
     * Fetches the payment methods from the given POS Profile.
     */
    private fun fetchPaymentMethods(
        sessionCookie: String,
        erpnextUrl: String,
        profileName: String,
        callback: (JSONArray?) -> Unit
    ) {
        val encodedDoctype = URLEncoder.encode("POS Profile", "UTF-8").replace("+", "%20")
        val encodedProfileName = URLEncoder.encode(profileName, "UTF-8").replace("+", "%20")
        val fieldsParam = URLEncoder.encode("[\"payments\",\"payments.mode_of_payment\",\"payments.default\"]", "UTF-8")
        val url = "https://$erpnextUrl/api/resource/$encodedDoctype/$encodedProfileName?fields=$fieldsParam"

        Log.d("CartActivity", "Fetching payment methods from URL: $url")

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Cookie", sessionCookie)
            .build()

        getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d("CartActivity", "Failed to fetch payment methods: ${e.message}")
                AlertDialog.Builder(this@CartActivity)
                    .setTitle("Error")
                    .setMessage("Failed to fetch payment methods: ${e.message}")
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .show()
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    Log.d("CartActivity", "Failed to fetch payment methods. Response code: ${response.code}")
                    AlertDialog.Builder(this@CartActivity)
                        .setTitle("Error")
                        .setMessage("Failed to fetch payment methods. Response code: ${response.code}")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                    callback(null)
                    return
                }
                val bodyStr = response.body?.string()
                if (bodyStr == null) {
                    callback(null)
                    return
                }
                try {
                    val json = JSONObject(bodyStr)
                    val data = json.optJSONObject("data")
                    val payments = data?.optJSONArray("payments")
                    if (payments != null) {
                        for (i in 0 until payments.length()) {
                            val pm = payments.getJSONObject(i)
                            Log.d("CartActivity", "Payment Method [$i]: ${pm.toString()}")
                        }
                    } else {
                        Log.d("CartActivity", "No payment methods found in POS Profile.")
                        AlertDialog.Builder(this@CartActivity)
                            .setTitle("Error")
                            .setMessage("No payment methods found in POS Profile.")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                    callback(payments)
                } catch (e: Exception) {
                    Log.d("CartActivity", "Error parsing payment methods: ${e.message}")
                    AlertDialog.Builder(this@CartActivity)
                        .setTitle("Error")
                        .setMessage("Error parsing payment methods: ${e.message}")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                    callback(null)
                }
            }
        })
    }

    /**
     * Proceeds to checkout by creating or updating a Sales Invoice.
     * If a draft invoice was loaded (currentDraftInvoiceId is not null), it will update that invoice.
     */
    private fun createCustomErpnextField(cfield: String, cvalue: String, clabel: String, invoiceType:String) {
        // Retrieve ERPNext credentials from SharedPreferences
        val sharedPreferences = getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)

        if (sessionCookie == null || erpnextUrl == null) {
            runOnUiThread {
                Toast.makeText(this, "Session expired", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Build the URL for creating a Custom Field
        val url = "https://$erpnextUrl/api/resource/Custom Field"
        val invoice_oi = if (invoiceType == "sales_invoice") {
            "Sales Invoice"
        } else {
            "POS Invoice"
        }

        // Create a JSON payload for the custom field.
        // Here, 'dt' specifies the doctype in which the custom field will appear (Sales Invoice).
        // 'fieldname' is the unique name of the field, and 'label' is the human-readable name.
        // Other parameters like 'fieldtype', 'insert_after', and 'hidden' are set according to your needs.
        val payload = JSONObject().apply {
            put("dt", invoice_oi)                  // Target doctype
            put("fieldname", cfield)                     // Custom field name (e.g., "custom_defer_etims_submission")
            put("label", clabel)                         // Custom field label (e.g., "Defer eTims Submission")
            put("fieldtype", "Data")                    // Field type ("Check" for a checkbox field; adjust as needed)
            put("insert_after", "update_stock")                           // Set to 1 if you want the field hidden
            // You can add additional properties as required.
        }

        // Create a request body with the JSON payload.
        val requestBody = payload.toString().toRequestBody("application/json".toMediaTypeOrNull())

        // Build the HTTP request
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Cookie", sessionCookie)
            .addHeader("Content-Type", "application/json")
            .build()

        // Execute the request asynchronously
        getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(
                        this@CartActivity,
                        "Failed to create custom field: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    AlertDialog.Builder(this@CartActivity)
                        .setTitle("Error")
                        .setMessage("Failed to create custom field: ${e.message}")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                Log.d("CustomField", "Response: $responseBody")
                if (!response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(
                            this@CartActivity,
                            "Error: ${response.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        AlertDialog.Builder(this@CartActivity)
                            .setTitle("Error")
                            .setMessage("Error: ${response.message}")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(
                            this@CartActivity,
                            "Custom field created successfully!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })
    }

    private fun proceedToCheckout(customer: String, cartItems: List<CartItem>, customFields: List<CustomField>) {
        val sharedPreferences = getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)
        val currentUser = sharedPreferences.getString("username", null)


        if (sessionCookie == null || erpnextUrl == null || currentUser == null) {
            Toast.makeText(this, "Session expired", Toast.LENGTH_SHORT).show()
            return
        }

        Log.d("CartActivity", "Proceeding to checkout. Customer: $customer, Cart items count: ${cartItems.size}")

        fetchApplicablePOSProfile(currentUser, sessionCookie, erpnextUrl) { posProfile ->
            if (posProfile == null) {
                runOnUiThread {
                    Toast.makeText(this@CartActivity, "No valid POS profile found", Toast.LENGTH_SHORT).show()
                    AlertDialog.Builder(this@CartActivity)
                        .setTitle("Error")
                        .setMessage("No valid POS profile found")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
                return@fetchApplicablePOSProfile
            }

            Log.d("CartActivity", "Fetched POS Profile: $posProfile")

            val requiredFields = listOf("company")
            val missingFields = requiredFields.filter { !posProfile.has(it) }
            if (missingFields.isNotEmpty()) {
                runOnUiThread {
                    Toast.makeText(
                        this@CartActivity,
                        "POS Profile missing: ${missingFields.joinToString()}",
                        Toast.LENGTH_LONG
                    ).show()
                    AlertDialog.Builder(this@CartActivity)
                        .setTitle("Error")
                        .setMessage("POS Profile missing: ${missingFields.joinToString()}")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
                return@fetchApplicablePOSProfile
            }

            val totalAmount = cartItems.sumOf { it.price * it.quantity }
            val invoiceData = JSONObject().apply {
                put("is_pos", 1)
                put("pos_profile", posProfile.getString("name"))
                put("company", posProfile.getString("company"))
                put("customer", customer)
                put("total", totalAmount)
                put("net_total", totalAmount)
                put("grand_total", totalAmount)
                put("base_grand_total", totalAmount)
                put("rounded_total", totalAmount)
                put("due_date", SimpleDateFormat("yyyy-MM-dd").format(Date()))
                put("items", JSONArray(cartItems.map {
                    JSONObject().apply {
                        put("item_code", it.itemCode)
                        put("qty", it.quantity)
                        put("rate", it.price)
                        put("amount", it.price * it.quantity)
                    }
                }))
            }

// Add each custom field (using its field name as key and field value as value)
            for (customField in customFields) {
                Log.d("Checkout", "Field Name: ${customField.fieldName} - Value: ${customField.fieldValue}")
                invoiceData.put(customField.fieldName, customField.fieldValue)
            }


            val posProfileName = posProfile.getString("name")
            fetchPaymentMethods(sessionCookie, erpnextUrl, posProfileName) { paymentsArray ->
                val paymentsChildArray = JSONArray()
                if (paymentsArray != null && paymentsArray.length() > 0) {
                    var defaultFound = false
                    for (i in 0 until paymentsArray.length()) {
                        val pm = paymentsArray.getJSONObject(i)
                        val mode = pm.optString("mode_of_payment", "")
                        val isDefault = pm.optBoolean("default", false)
                        if (isDefault) {
                            defaultFound = true
                            paymentsChildArray.put(JSONObject().apply {
                                put("mode_of_payment", mode)
                                put("amount", totalAmount)
                            })
                        } else {
                            paymentsChildArray.put(JSONObject().apply {
                                put("mode_of_payment", mode)
                                put("amount", 0)
                            })
                        }
                    }
                    if (!defaultFound && paymentsArray.length() > 0) {
                        val first = paymentsArray.getJSONObject(0)
                        val mode = first.optString("mode_of_payment", "")
                        paymentsChildArray.put(0, JSONObject().apply {
                            put("mode_of_payment", mode)
                            put("amount", totalAmount)
                        })
                    }
                } else {
                    Log.d("CartActivity", "No payment methods found; payments array will be empty.")
                    AlertDialog.Builder(this@CartActivity)
                        .setTitle("Error")
                        .setMessage("No payment methods found; payments array will be empty.")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }

                invoiceData.put("payments", paymentsChildArray)
                // If we are editing an existing draft invoice, update it.
                if (currentDraftInvoiceId != null) {
                    updateSalesInvoice(invoiceData, sessionCookie, erpnextUrl, currentDraftInvoiceId!!)
                } else {
                    // In another Activity or Fragment
                    val prefs = getSharedPreferences("session_cookie", Context.MODE_PRIVATE)
                    val invoiceType = prefs.getString("invoice_type", "") // "sales_invoice" is the default value
                    if (invoiceType != null) {
                        createSalesInvoice(invoiceData, sessionCookie, erpnextUrl, invoiceType, customFields)
                    }
                }
            }
        }
    }

    /**
     * Fetches an applicable POS Profile for the current user.
     */
    private fun fetchApplicablePOSProfile(
        user: String,
        cookie: String,
        domain: String,
        callback: (JSONObject?) -> Unit
    ) {
        val filters = "[[\"POS Profile User\", \"user\", \"=\", \"$user\"]]"
        val url = "https://$domain/api/resource/POS%20Profile?fields=[\"*\"]&filters=${URLEncoder.encode(filters, "UTF-8")}"

        val request = Request.Builder()
            .url(url)
            .addHeader("Cookie", cookie)
            .build()

        getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val json = JSONObject(response.body?.string() ?: "")
                    val profiles = json.getJSONArray("data")
                    callback(if (profiles.length() > 0) profiles.getJSONObject(0) else null)
                } catch (e: Exception) {
                    callback(null)
                }
            }
        })
    }

    /**
     * Creates a Sales Invoice using the provided invoice data.
     */
    // Data class for field metadata (if not already declared)
    // Modified createSalesInvoice function with mandatory field handling
    private fun createSalesInvoice(
        invoiceData: JSONObject,
        sessionCookie: String,
        erpnextUrl: String,
        invoiceType: String,
        customFields: List<CustomField>
    ) {
        if (invoiceType == "sales_invoice") {
            for (customField in customFields) {
                Log.d("Checkout", "Field Name: ${customField.fieldName} - Value: ${customField.fieldValue}")
                val cfield=customField.fieldName
                val cvalie=customField.fieldValue
                val clabel=customField.fieldLabel
                createCustomErpnextField(cfield,cvalie,clabel,invoiceType)
            }
            Log.d("SalesInvoice", "Request Payload: ${invoiceData.toString()}")
            val url = "https://$erpnextUrl/api/resource/Sales Invoice"
            val requestBody = invoiceData.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Cookie", sessionCookie)
                .addHeader("Content-Type", "application/json")
                .build()

            getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        Toast.makeText(
                            this@CartActivity,
                            "Failed to create sales invoice: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        AlertDialog.Builder(this@CartActivity)
                            .setTitle("Error")
                            .setMessage("Failed to create sales invoice: ${e.message}")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string()
                    Log.d("SalesInvoice", "Full Response: $responseBody")
                    if (!response.isSuccessful) {
                        // If ERPNext indicates a mandatory field error, handle it.
                        if (responseBody != null && responseBody.contains("MandatoryError")) {
                            handleInvoiceMandatoryError(
                                responseBody,
                                invoiceData,
                                invoiceType,
                                sessionCookie,
                                erpnextUrl,
                                customFields
                            )
                        } else {
                            runOnUiThread {
                                Toast.makeText(
                                    this@CartActivity,
                                    "Error: ${response.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                                AlertDialog.Builder(this@CartActivity)
                                    .setTitle("Error")
                                    .setMessage("Error: ${response.message}")
                                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                    .show()
                            }
                        }
                    } else {
                        try {
                            val jsonResponse = JSONObject(responseBody)
                            val dataObject = jsonResponse.optJSONObject("data")
                                ?: throw JSONException("Missing 'data' object in response")

                            // Process the invoice data as needed.
                            val invoiceName = dataObject.optString("name", "")
                            val customer = dataObject.optString("customer", "")
                            val total = dataObject.optDouble("total", 0.0)
                            val amount = dataObject.optDouble("amount", 0.0)
                            val date = dataObject.optString("date", "")
                            val grandTotal = dataObject.optDouble("grand_total", 0.0)
                            val currency = dataObject.optString("currency", "USD")

                            // Process items
                            val itemsArray = dataObject.optJSONArray("items")
                            val itemsList = mutableListOf<InvoiceItem>()
                            if (itemsArray != null) {
                                for (i in 0 until itemsArray.length()) {
                                    val itemJson = itemsArray.getJSONObject(i)
                                    val itemCode = itemJson.optString("item_code", "")
                                    val qty = itemJson.optInt("qty", 0)
                                    val rate = itemJson.optDouble("rate", 0.0)
                                    itemsList.add(InvoiceItem(item_code = itemCode, qty = qty, rate = rate))
                                }
                            }

                            // Process payments
                            val paymentsArray = dataObject.optJSONArray("payments")
                            val paymentsList = mutableListOf<InvoicePayment>()
                            if (paymentsArray != null) {
                                for (i in 0 until paymentsArray.length()) {
                                    val paymentJson = paymentsArray.getJSONObject(i)
                                    val mode = paymentJson.optString(
                                        "mode_of_payment",
                                        paymentJson.optString("mode", "")
                                    )
                                    val payAmount = paymentJson.optDouble("amount", 0.0)
                                    paymentsList.add(InvoicePayment(mode_of_payment = mode, amount = payAmount))
                                }
                            }

                            val erpNextInvoice = ERPNextInvoice(
                                name = invoiceName,
                                customer = customer,
                                total = total,
                                items = itemsList,
                                payments = paymentsList,
                                id = invoiceName,
                                amount = amount,
                                date = date,
                                grandTotal = grandTotal,
                                currency = currency,
                                company = dataObject.optString("company", ""),
                                companyaddress = dataObject.optString("company_address", ""),
                                customername = dataObject.optString("customer_name", ""),
                                companytaxid = dataObject.optString("company_tax_id", ""),
                                invoiceNumber = invoiceName,
                                status = dataObject.optString("status", ""),
                                docStatus = dataObject.optInt("docstatus", 0),
                                companyaddressdisplay = dataObject.optString("company_address_display", "")
                            )

                            // Navigate to PaymentActivity with the created invoice.
                            val intent = Intent(this@CartActivity, PaymentActivity::class.java).apply {
                                putExtra("INVOICE", erpNextInvoice)
                            }
                            startActivity(intent)
                            finish()

                        } catch (e: JSONException) {
                            runOnUiThread {
                                Toast.makeText(
                                    this@CartActivity,
                                    "Failed to process invoice",
                                    Toast.LENGTH_LONG
                                ).show()
                                AlertDialog.Builder(this@CartActivity)
                                    .setTitle("Error")
                                    .setMessage("Failed to process invoice")
                                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                    .show()
                            }
                        }
                    }
                }
            })

        } else {
            // POS Invoice branch (similar structure as Sales Invoice)
            for (customField in customFields) {
                Log.d("Checkout", "Field Name: ${customField.fieldName} - Value: ${customField.fieldValue}")
                val cfield=customField.fieldName
                val cvalie=customField.fieldValue
                val clabel=customField.fieldLabel
                createCustomErpnextField(cfield,cvalie,clabel,invoiceType)
            }
            Log.d("POSInvoice", "Request Payload: ${invoiceData.toString()}")
            val url = "https://$erpnextUrl/api/resource/POS Invoice"
            val requestBody = invoiceData.toString().toRequestBody("application/json".toMediaTypeOrNull())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .addHeader("Cookie", sessionCookie)
                .addHeader("Content-Type", "application/json")
                .build()

            getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        Toast.makeText(
                            this@CartActivity,
                            "Failed to create POS invoice: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                        AlertDialog.Builder(this@CartActivity)
                            .setTitle("Error")
                            .setMessage("Failed to create POS invoice: ${e.message}")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string()
                    Log.d("POSInvoice", "Full Response: $responseBody")
                    if (!response.isSuccessful) {
                        if (responseBody != null && responseBody.contains("MandatoryError")) {
                            handleInvoiceMandatoryError(
                                responseBody,
                                invoiceData,
                                invoiceType,
                                sessionCookie,
                                erpnextUrl,
                                customFields
                            )
                        } else {
                            runOnUiThread {
                                Toast.makeText(
                                    this@CartActivity,
                                    "Error: ${response.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                                AlertDialog.Builder(this@CartActivity)
                                    .setTitle("Error")
                                    .setMessage("Error: ${response.message}")
                                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                    .show()
                            }
                        }
                    } else {
                        try {
                            val jsonResponse = JSONObject(responseBody)
                            val dataObject = jsonResponse.optJSONObject("data")
                                ?: throw JSONException("Missing 'data' object in response")

                            val invoiceName = dataObject.optString("name", "")
                            val customer = dataObject.optString("customer", "")
                            val total = dataObject.optDouble("total", 0.0)
                            val amount = dataObject.optDouble("amount", 0.0)
                            val date = dataObject.optString("date", "")
                            val grandTotal = dataObject.optDouble("grand_total", 0.0)
                            val currency = dataObject.optString("currency", "USD")

                            val itemsArray = dataObject.optJSONArray("items")
                            val itemsList = mutableListOf<InvoiceItem>()
                            if (itemsArray != null) {
                                for (i in 0 until itemsArray.length()) {
                                    val itemJson = itemsArray.getJSONObject(i)
                                    val itemCode = itemJson.optString("item_code", "")
                                    val qty = itemJson.optInt("qty", 0)
                                    val rate = itemJson.optDouble("rate", 0.0)
                                    itemsList.add(InvoiceItem(item_code = itemCode, qty = qty, rate = rate))
                                }
                            }

                            val paymentsArray = dataObject.optJSONArray("payments")
                            val paymentsList = mutableListOf<InvoicePayment>()
                            if (paymentsArray != null) {
                                for (i in 0 until paymentsArray.length()) {
                                    val paymentJson = paymentsArray.getJSONObject(i)
                                    val mode = paymentJson.optString(
                                        "mode_of_payment",
                                        paymentJson.optString("mode", "")
                                    )
                                    val payAmount = paymentJson.optDouble("amount", 0.0)
                                    paymentsList.add(InvoicePayment(mode_of_payment = mode, amount = payAmount))
                                }
                            }

                            val erpNextInvoice = ERPNextInvoice(
                                name = invoiceName,
                                customer = customer,
                                total = total,
                                items = itemsList,
                                payments = paymentsList,
                                id = invoiceName,
                                amount = amount,
                                date = date,
                                grandTotal = grandTotal,
                                currency = currency,
                                company = dataObject.optString("company", ""),
                                companyaddress = dataObject.optString("company_address", ""),
                                customername = dataObject.optString("customer_name", ""),
                                companytaxid = dataObject.optString("company_tax_id", ""),
                                invoiceNumber = invoiceName,
                                status = dataObject.optString("status", ""),
                                docStatus = dataObject.optInt("docstatus", 0),
                                companyaddressdisplay = dataObject.optString("company_address_display", "")
                            )

                            val intent = Intent(this@CartActivity, PaymentActivity::class.java).apply {
                                putExtra("INVOICE", erpNextInvoice)
                            }
                            startActivity(intent)
                            finish()

                        } catch (e: JSONException) {
                            runOnUiThread {
                                Toast.makeText(
                                    this@CartActivity,
                                    "Failed to process invoice",
                                    Toast.LENGTH_LONG
                                ).show()
                                AlertDialog.Builder(this@CartActivity)
                                    .setTitle("Error")
                                    .setMessage("Failed to process invoice")
                                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                    .show()
                            }
                        }
                    }
                }
            })
        }
    }

    // -------------------------------------------------------------------------------------
// Handle mandatory error response for invoice creation
    private fun handleInvoiceMandatoryError(
        responseBody: String,
        invoiceData: JSONObject,
        invoiceType: String,
        sessionCookie: String,
        erpnextUrl: String,
        customFields: List<CustomField>
    ) {
        try {
            val errorJson = JSONObject(responseBody)
            val missingFields = mutableListOf<String>()

            // Try to parse the exception message.
            val exceptionMessage = errorJson.optString("exception", "")
            if (exceptionMessage.contains("MandatoryError")) {
                val parts = exceptionMessage.split(":")
                if (parts.size >= 2) {
                    val fieldsPart = parts.last().trim()
                    missingFields.addAll(fieldsPart.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                }
            }

            // Also try to parse missing fields from _server_messages.
            val serverMessages: JSONArray? = errorJson.optJSONArray("_server_messages")
            if (serverMessages != null) {
                for (i in 0 until serverMessages.length()) {
                    val msgString = serverMessages.optString(i)
                    try {
                        val msgJson = JSONObject(msgString)
                        val message = msgJson.optString("message", "")
                        // Check for both Sales Invoice and POS Invoice
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
                        Log.e("InvoiceError", "Error parsing server message: ${e.message}")
                    }
                }
            }

            if (missingFields.isNotEmpty()) {
                Log.d("InvoiceError", "Missing fields: $missingFields")
                runOnUiThread {
                    fetchMissingFieldMetasForInvoice(
                        missingFields,
                        sessionCookie,
                        erpnextUrl,
                        invoiceType
                    ) { metaMap ->
                        showMandatoryFieldsDialogForInvoice(
                            metaMap,
                            invoiceData,
                            invoiceType,
                            sessionCookie,
                            erpnextUrl,
                            customFields
                        )
                    }
                }
            } else {
                runOnUiThread {
                    Toast.makeText(
                        this@CartActivity,
                        "Unknown error: $responseBody",
                        Toast.LENGTH_LONG
                    ).show()
                    AlertDialog.Builder(this@CartActivity)
                        .setTitle("Unknown Error")
                        .setMessage("Unknown error: $responseBody")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(
                    this@CartActivity,
                    "Error parsing error response: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
                AlertDialog.Builder(this@CartActivity)
                    .setTitle("Error")
                    .setMessage("Error parsing error response: ${e.message}")
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
        }
    }

    // -------------------------------------------------------------------------------------
// Fetch metadata for each missing field based on the parent doctype (Sales Invoice or POS Invoice)
    private fun fetchMissingFieldMetasForInvoice(
        missingFields: List<String>,
        sessionCookie: String,
        erpnextUrl: String,
        invoiceType: String,
        callback: (Map<String, AddCustomerActivity.FieldMeta>) -> Unit
    ) {
        val metaMap = mutableMapOf<String, AddCustomerActivity.FieldMeta>()
        var count = 0
        // Use the proper parent doctype for metadata lookup.
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
// Show a dialog with dynamically generated input fields for each missing mandatory field
    private fun showMandatoryFieldsDialogForInvoice(
        metaMap: Map<String, AddCustomerActivity.FieldMeta>,
        invoiceData: JSONObject,
        invoiceType: String,
        sessionCookie: String,
        erpnextUrl: String,
        customFields: List<CustomField>
    ) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Additional Information Required")
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val fieldViews = mutableMapOf<String, View>()

        for ((field, meta) in metaMap) {
            Log.d(
                "InvoiceField",
                "Creating UI for field: $field | Type: ${meta.fieldType} | Options: ${meta.optionsDoctype}"
            )
            val label = TextView(this).apply {
                text = formatFieldName(field)
            }
            layout.addView(label)

            when (meta.fieldType) {
                "Link" -> {
                    if (!meta.optionsDoctype.isNullOrBlank()) {
                        val progressBar = ProgressBar(this)
                        layout.addView(progressBar)
                        fetchLinkDataForInvoice(meta.optionsDoctype, sessionCookie, erpnextUrl) { options ->
                            runOnUiThread {
                                layout.removeView(progressBar)
                                if (options.isNotEmpty()) {
                                    val spinner = Spinner(this).apply {
                                        adapter = ArrayAdapter(
                                            this@CartActivity,
                                            android.R.layout.simple_spinner_item,
                                            options
                                        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                                    }
                                    layout.addView(spinner)
                                    fieldViews[field] = spinner
                                } else {
                                    createTextInputForInvoice(field, layout, fieldViews)
                                }
                            }
                        }
                    } else {
                        createTextInputForInvoice(field, layout, fieldViews)
                    }
                }
                "Select" -> {
                    val options = meta.optionsDoctype?.split(",")?.map { it.trim() } ?: emptyList()
                    if (options.isNotEmpty()) {
                        val spinner = Spinner(this).apply {
                            adapter = ArrayAdapter(
                                this@CartActivity,
                                android.R.layout.simple_spinner_item,
                                options
                            ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                        }
                        layout.addView(spinner)
                        fieldViews[field] = spinner
                    } else {
                        createTextInputForInvoice(field, layout, fieldViews)
                    }
                }
                else -> {
                    createTextInputForInvoice(field, layout, fieldViews)
                }
            }
        }

        builder.setView(layout)
        builder.setPositiveButton("Submit") { dialog, which ->
            // Add the extra values to the invoice payload
            for ((field, view) in fieldViews) {
                when (view) {
                    is EditText -> {
                        val value = view.text.toString().trim()
                        if (value.isNotEmpty()) {
                            invoiceData.put(field, value)
                        }
                    }
                    is Spinner -> {
                        val selected = view.selectedItem?.toString() ?: ""
                        if (selected.isNotEmpty()) {
                            invoiceData.put(field, selected)
                        }
                    }
                }
            }
            Log.d("InvoiceUpdate", "Updated invoice payload: $invoiceData")
            // Resubmit the invoice with the updated data
            createSalesInvoice(invoiceData, sessionCookie, erpnextUrl, invoiceType, customFields)
        }
        builder.setNegativeButton("Cancel") { dialog, which -> dialog.dismiss() }
        builder.show()
    }

    // -------------------------------------------------------------------------------------
// Fetch options for a Link field
    private fun fetchLinkDataForInvoice(
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
// Create a simple text input for the field if no Link/Select options are available
    private fun createTextInputForInvoice(
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
// Format a field name (e.g. "customer_name" becomes "Customer Name")
    private fun formatFieldName(field: String): String {
        return field.split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }


    /**
     * Updates an existing Sales Invoice using its ID and the provided invoice data.
     */
    private fun updateSalesInvoice(
        invoiceData: JSONObject,
        sessionCookie: String,
        erpnextUrl: String,
        invoiceId: String
    ) {
        Log.d("SalesInvoice", "Update Request Payload: ${invoiceData.toString()}")
        val url = "https://$erpnextUrl/api/resource/Sales%20Invoice/${URLEncoder.encode(invoiceId, "UTF-8")}"
        val requestBody = invoiceData.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .put(requestBody)
            .addHeader("Cookie", sessionCookie)
            .addHeader("Content-Type", "application/json")
            .build()

        getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@CartActivity, "Failed to update sales invoice: ${e.message}", Toast.LENGTH_LONG).show()
                    AlertDialog.Builder(this@CartActivity)
                        .setTitle("Error")
                        .setMessage("Failed to update sales invoice: ${e.message}")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                Log.d("SalesInvoice", "Update Response: $responseBody")
                if (!response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(this@CartActivity, "Error updating sales invoice: ${response.message}", Toast.LENGTH_LONG).show()
                        AlertDialog.Builder(this@CartActivity)
                            .setTitle("Error")
                            .setMessage("Error updating sales invoice: ${response.message}")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                } else {
                    try {
                        val jsonResponse = JSONObject(responseBody)
                        val dataObject = jsonResponse.optJSONObject("data")
                            ?: throw JSONException("Missing 'data' object in response")

                        val invoiceName = dataObject.optString("name", "")
                        val customer = dataObject.optString("customer", "")
                        val total = dataObject.optDouble("total", 0.0)
                        val amount = dataObject.optDouble("amount", 0.0)
                        val date = dataObject.optString("date", "")
                        val grandTotal = dataObject.optDouble("grand_total", 0.0)
                        val currency = dataObject.optString("currency", "USD")

                        val itemsArray = dataObject.optJSONArray("items")
                        val itemsList = mutableListOf<InvoiceItem>()
                        if (itemsArray != null) {
                            for (i in 0 until itemsArray.length()) {
                                val itemJson = itemsArray.getJSONObject(i)
                                val itemCode = itemJson.optString("item_code", "")
                                val qty = itemJson.optInt("qty", 0)
                                val rate = itemJson.optDouble("rate", 0.0)
                                itemsList.add(InvoiceItem(item_code = itemCode, qty = qty, rate = rate))
                            }
                        }

                        val paymentsArray = dataObject.optJSONArray("payments")
                        val paymentsList = mutableListOf<InvoicePayment>()
                        if (paymentsArray != null) {
                            for (i in 0 until paymentsArray.length()) {
                                val paymentJson = paymentsArray.getJSONObject(i)
                                val mode = paymentJson.optString("mode_of_payment", paymentJson.optString("mode", ""))
                                val payAmount = paymentJson.optDouble("amount", 0.0)
                                paymentsList.add(InvoicePayment(mode_of_payment = mode, amount = payAmount))
                            }
                        }

                        val erpNextInvoice = ERPNextInvoice(
                            name = invoiceName,
                            customer = customer,
                            total = total,
                            items = itemsList,
                            payments = paymentsList,
                            id = invoiceName,
                            amount = amount,
                            date = date,
                            grandTotal = grandTotal,
                            currency = currency,
                            company =dataObject.optString("company", ""),
                            companyaddress = dataObject.optString("company_address", ""),
                            customername = dataObject.optString("customer_name", ""),
                            companytaxid = dataObject.optString("company_tax_id", ""),
                            invoiceNumber = invoiceName,
                            status = dataObject.optString("status", ""),
                            docStatus = dataObject.optInt("docstatus", 0),
                            companyaddressdisplay = dataObject.optString("company_address_display", "")
                        )

                        runOnUiThread {
                            // Navigate to PaymentActivity with the updated invoice.
                            val intent = Intent(this@CartActivity, PaymentActivity::class.java).apply {
                                putExtra("INVOICE", erpNextInvoice)
                            }
                            startActivity(intent)
                            finish()
                        }

                    } catch (e: JSONException) {
                        runOnUiThread {
                            Toast.makeText(this@CartActivity, "Failed to process updated invoice", Toast.LENGTH_LONG).show()
                            AlertDialog.Builder(this@CartActivity)
                                .setTitle("Error")
                                .setMessage("Failed to process updated invoice")
                                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                .show()
                        }
                    }
                }
            }
        })
    }

    /**
     * Returns an OkHttpClient that accepts all SSL certificates (unsafe).
     * Use only for development or testing.
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

    // CartAdapter.CartItemListener implementations
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
