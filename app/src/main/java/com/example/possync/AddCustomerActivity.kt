package com.example.possync

import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

class AddCustomerActivity : AppCompatActivity() {

    // Logging tag
    private val TAG = "AddCustomerActivity"

    // Views from layout
    private lateinit var etCustomerName: TextInputEditText
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPhone: TextInputEditText
    private lateinit var etAddress: TextInputEditText
    private lateinit var btnAddCustomer: Button

    // Data class for field metadata
    data class FieldMeta(val fieldName: String, val fieldType: String, val optionsDoctype: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_customer)

        // Bind views
        etCustomerName = findViewById(R.id.etCustomerName)
        etEmail = findViewById(R.id.etEmail)
        etPhone = findViewById(R.id.etPhone)
        etAddress = findViewById(R.id.etAddress)
        btnAddCustomer = findViewById(R.id.btnAddCustomer)

        // Add customer button click listener
        btnAddCustomer.setOnClickListener {
            val payload = createCustomerPayload()
            if (payload != null) {
                Log.d(TAG, "Payload created: $payload")
                sendAddCustomerRequest(payload)
            }
        }
    }

    // Create initial payload
    private fun createCustomerPayload(): JSONObject? {
        val name = etCustomerName.text?.toString()?.trim() ?: ""
        val email = etEmail.text?.toString()?.trim() ?: ""
        val phone = etPhone.text?.toString()?.trim() ?: ""
        val address = etAddress.text?.toString()?.trim() ?: ""

        if (name.isEmpty()) {
            etCustomerName.error = "Customer name is required"
            return null
        }

        return JSONObject().apply {
            put("doctype", "Customer")
            put("customer_name", name)
            put("email_id", email)
            put("phone", phone)
            put("customer_group", "Commercial")
            put("territory", "All Territories")
            put("address", address)
        }
    }

    // Send request to add customer
    private fun sendAddCustomerRequest(payload: JSONObject) {
        val sharedPreferences = getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)

        if (sessionCookie == null || erpnextUrl == null) {
            Log.e(TAG, "Session expired or ERPNext URL not set")
            runOnUiThread {
                Toast.makeText(this, "Session expired or ERPNext URL not set", Toast.LENGTH_SHORT).show()
            }
            return
        }

        val url = "https://$erpnextUrl/api/resource/Customer"
        val requestBody = payload.toString().toRequestBody("application/json".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .removeHeader("Expect")
            .addHeader("Cookie", sessionCookie)
            .addHeader("Content-Type", "application/json")
            .build()

        Log.d(TAG, "Sending request to: $url")
        getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Request failed: ${e.message}")
                runOnUiThread {
                    AlertDialog.Builder(this@AddCustomerActivity)
                        .setTitle("Network Error")
                        .setMessage("Failed to add customer. Please check your internet connection and try again.")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                Log.d(TAG, "Response received: $responseBody")
                if (!response.isSuccessful) {
                    if (responseBody != null && responseBody.contains("MandatoryError")) {
                        Log.d(TAG, "Mandatory error detected")
                        // Handle mandatory errors with your specialized function.
                        handleMandatoryError(responseBody, payload)
                    } else {
                        Log.e(TAG, "Error response: ${response.message}")
                        runOnUiThread {
                            AlertDialog.Builder(this@AddCustomerActivity)
                                .setTitle("Error")
                                .setMessage("An error occurred while adding the customer. Please try again.")
                                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                .show()
                        }
                    }
                } else {
                    Log.d(TAG, "Customer added successfully")
                    runOnUiThread {
                        AlertDialog.Builder(this@AddCustomerActivity)
                            .setTitle("Success")
                            .setMessage("Customer added successfully!")
                            .setPositiveButton("OK") { dialog, _ ->
                                dialog.dismiss()
                                finish()
                            }
                            .show()
                    }
                }
            }
        })

    }

    // Handle mandatory error
    private fun handleMandatoryError(responseBody: String, payload: JSONObject) {
        try {
            val errorJson = JSONObject(responseBody)
            val missingFields = mutableListOf<String>()

            // Extract missing fields from exception message
            val exceptionMessage = errorJson.optString("exception", "")
            if (exceptionMessage.contains("MandatoryError")) {
                val parts = exceptionMessage.split(":")
                if (parts.size >= 2) {
                    val fieldsPart = parts.last().trim()
                    missingFields.addAll(fieldsPart.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                }
            }

            // Extract missing fields from _server_messages
            val serverMessages: JSONArray? = errorJson.optJSONArray("_server_messages")
            if (serverMessages != null) {
                for (i in 0 until serverMessages.length()) {
                    val msgString = serverMessages.optString(i)
                    try {
                        val msgJson = JSONObject(msgString)
                        val message = msgJson.optString("message", "")
                        if (message.contains("Value missing for Customer:")) {
                            val fieldName = message.substringAfter("Value missing for Customer:").trim()
                            val key = fieldName.replace(" ", "_").lowercase()
                            if (!missingFields.contains(key)) {
                                missingFields.add(key)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing server message: ${e.message}")
                        AlertDialog.Builder(this@AddCustomerActivity)
                            .setTitle("Error")
                            .setMessage("Error parsing server message: ${e.message}")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                }
            }

            if (missingFields.isNotEmpty()) {
                Log.d(TAG, "Missing fields: $missingFields")
                runOnUiThread {
                    fetchMissingFieldMetas(missingFields) { metaMap ->
                        showMandatoryFieldsDialog(metaMap, payload)
                    }
                }
            } else {
                Log.e(TAG, "No missing fields detected in error response")
                runOnUiThread {
                    Toast.makeText(this, "Unknown error: $responseBody", Toast.LENGTH_LONG).show()
                    AlertDialog.Builder(this@AddCustomerActivity)
                        .setTitle("Unknown Error")
                        .setMessage("Unknown error: $responseBody")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing error response: ${e.message}")
            runOnUiThread {
                Toast.makeText(this, "Error parsing error response: ${e.message}", Toast.LENGTH_LONG).show()
                AlertDialog.Builder(this@AddCustomerActivity)
                    .setTitle("Error")
                    .setMessage("Error parsing error response: ${e.message}")
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
        }
    }

    // Fetch metadata for missing fields
    private fun fetchMissingFieldMetas(
        missingFields: List<String>,
        callback: (Map<String, FieldMeta>) -> Unit
    ) {
        val metaMap = mutableMapOf<String, FieldMeta>()
        val sharedPreferences = getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)

        if (erpnextUrl == null || sessionCookie == null) {
            Log.e(TAG, "ERPNext URL or session cookie not set")
            callback(metaMap)
            return
        }

        var count = 0
        for (field in missingFields) {
            val metaUrl =
                "https://$erpnextUrl/api/resource/DocField?filters=[[\"parent\",\"=\",\"Customer\"],[\"fieldname\",\"=\",\"$field\"]]"
            val request = Request.Builder()
                .url(metaUrl)
                .get()
                .removeHeader("Expect")
                .addHeader("Cookie", sessionCookie)
                .build()

            Log.d(TAG, "Fetching metadata for field: $field")
            getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Failed to fetch metadata for $field: ${e.message}")
                    metaMap[field] = FieldMeta(field, "Data", null)
                    synchronized(this) { count++ }
                    if (count == missingFields.size) {
                        runOnUiThread { callback(metaMap) }
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val body = response.body?.string()
                    Log.d(TAG, "Metadata response for $field: $body")
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

                            metaMap[field] = FieldMeta(field, finalFieldType, optionsDoctype)
                            Log.d(TAG, "Field $field parsed as $finalFieldType with options: $optionsDoctype")
                        } else {
                            metaMap[field] = FieldMeta(field, "Data", null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing metadata for $field: ${e.message}")
                        AlertDialog.Builder(this@AddCustomerActivity)
                            .setTitle("Error")
                            .setMessage("Error parsing metadata for $field: ${e.message}")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                        metaMap[field] = FieldMeta(field, "Data", null)
                    }
                    synchronized(this) { count++ }
                    if (count == missingFields.size) {
                        runOnUiThread { callback(metaMap) }
                    }
                }
            })
        }
    }

    // Show dialog for mandatory fields
    private fun showMandatoryFieldsDialog(
        metaMap: Map<String, FieldMeta>,
        payload: JSONObject
    ) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Additional Information Required")
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 10)
        }

        val fieldViews = mutableMapOf<String, View>()

        for ((field, meta) in metaMap) {
            Log.d(TAG, "Creating UI for field: $field | Type: ${meta.fieldType} | Options: ${meta.optionsDoctype}")

            val label = TextView(this).apply {
                text = formatFieldName(field)
            }
            layout.addView(label)

            when (meta.fieldType) {
                "Link" -> {
                    if (!meta.optionsDoctype.isNullOrBlank()) {
                        val progressBar = ProgressBar(this).apply {
                            layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                        }
                        layout.addView(progressBar)

                        fetchLinkData(meta.optionsDoctype) { options ->
                            runOnUiThread {
                                layout.removeView(progressBar)
                                if (options.isNotEmpty()) {
                                    val spinner = Spinner(this).apply {
                                        adapter = ArrayAdapter(
                                            this@AddCustomerActivity,
                                            android.R.layout.simple_spinner_item,
                                            options
                                        ).also {
                                            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                                        }
                                    }
                                    layout.addView(spinner, layout.indexOfChild(progressBar))
                                    fieldViews[field] = spinner
                                } else {
                                    createTextInput(field, layout, fieldViews)
                                }
                            }
                        }
                    } else {
                        Log.w(TAG, "Link field $field has no options doctype")
                        createTextInput(field, layout, fieldViews)
                    }
                }

                "Select" -> {
                    val options = meta.optionsDoctype?.split(",")?.map { it.trim() } ?: emptyList()
                    if (options.isNotEmpty()) {
                        val spinner = Spinner(this).apply {
                            adapter = ArrayAdapter(
                                this@AddCustomerActivity,
                                android.R.layout.simple_spinner_item,
                                options
                            ).also {
                                it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                            }
                        }
                        layout.addView(spinner)
                        fieldViews[field] = spinner
                    } else {
                        Log.w(TAG, "Select field $field has no options")
                        createTextInput(field, layout, fieldViews)
                    }
                }

                else -> {
                    createTextInput(field, layout, fieldViews)
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
                            payload.put(field, value)
                        }
                    }
                    is Spinner -> {
                        val selected = view.selectedItem?.toString() ?: ""
                        if (selected.isNotEmpty()) {
                            payload.put(field, selected)
                        }
                    }
                }
            }
            Log.d(TAG, "Updated payload: $payload")
            sendAddCustomerRequest(payload)
        }
        builder.setNegativeButton("Cancel") { dialog, which -> dialog.dismiss() }
        builder.show()
    }

    // Fetch data for Link fields
    private fun fetchLinkData(linkDoctype: String, callback: (List<String>) -> Unit) {
        val sharedPreferences = getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)
        if (sessionCookie == null || erpnextUrl == null) {
            Log.e(TAG, "Session cookie or ERPNext URL not set")
            callback(emptyList())
            return
        }
        val url = "https://$erpnextUrl/api/resource/$linkDoctype?fields=[\"name\"]&limit_page_length=100"
        val request = Request.Builder()
            .url(url)
            .get()
            .removeHeader("Expect")
            .addHeader("Cookie", sessionCookie)
            .build()

        Log.d(TAG, "Fetching link data from: $url")
        getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to fetch link data: ${e.message}")
                callback(emptyList())
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                Log.d(TAG, "Link data response: $responseBody")
                if (!response.isSuccessful || responseBody == null) {
                    Log.e(TAG, "Error fetching link data: ${response.message}")
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
                    Log.e(TAG, "Error parsing link data: ${e.message}")
                    AlertDialog.Builder(this@AddCustomerActivity)
                        .setTitle("Error")
                        .setMessage("Error parsing link data: ${e.message}")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                    callback(emptyList())
                }
            }
        })
    }

    // Create text input for fields
    private fun createTextInput(
        field: String,
        layout: LinearLayout,
        fieldViews: MutableMap<String, View>
    ) {
        val editText = TextInputEditText(this).apply {
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

    // Format field names for display
    private fun formatFieldName(field: String): String {
        return field.split("_").joinToString(" ") { it.replaceFirstChar { it.uppercase() } }
    }


    private fun getUnsafeOkHttpClient(): OkHttpClient {
        try {
            val trustAllCerts = arrayOf<TrustManager>(
                object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                }
            )
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
}