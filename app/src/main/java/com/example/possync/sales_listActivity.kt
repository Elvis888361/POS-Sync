package com.example.possync

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.tabs.TabLayout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.SecureRandom
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

// A general click listener interface for invoices.
interface OnInvoiceClickListener {
    fun onInvoiceClicked(invoice: SalesInvoice)
}

class SalesListActivity : AppCompatActivity() {

    private val sharedPreferencesKey = "ERPNextPreferences"
    private val sessionCookieKey = "sessionCookie"

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SalesListAdapter
    private val salesInvoices = mutableListOf<SalesInvoice>()
    private lateinit var searchView: SearchView

    // Hold the currently selected tab filter and search query.
    private var currentFilter = "All"
    private var currentSearchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sales_list)

        // Set up RecyclerView.
        recyclerView = findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = SalesListAdapter(salesInvoices, object : OnInvoiceClickListeners {
            override fun onInvoiceClicked(invoice: SalesInvoice) {
                var docName=invoice.invoiceNumber
                when (invoice.docStatus) {
                    0 -> { // Draft – open for editing
                        when (invoice.status) {
                            "Draft" -> {
                                // Open for editing.
                                val intent = Intent(this@SalesListActivity, CartActivity::class.java).apply {
                                    putExtra("DRAFT_INVOICE_ID", invoice.invoiceNumber)
                                }
                                startActivity(intent)
                            }
                            "Paid", "Overdue" -> {
                                // Show a dialog offering a print layout or return.
                                AlertDialog.Builder(this@SalesListActivity)
                                    .setTitle("Select Action")
                                    .setMessage("What would you like to do with Sales Invoice ${invoice.invoiceNumber}?")
                                    .setPositiveButton("Print Layout") { dialog, _ ->
                                        fetchFullInvoice(docName) { invoiceJson, error ->
                                            runOnUiThread {
                                                invoiceJson?.let {
                                                    // Parse the JSON into an ERPNextInvoice object.
                                                    val erpInvoice = parseInvoice(it)
                                                    // Start ReceiptActivity and pass the ERPNextInvoice object.
                                                    val intent = Intent(this@SalesListActivity, ReceiptActivity::class.java).apply {
                                                        putExtra("INVOICE", erpInvoice)
                                                    }
                                                    startActivity(intent)
                                                    finish()
                                                } ?: run {
                                                    Toast.makeText(
                                                        this@SalesListActivity,
                                                        "Payment successful but no invoice received: $error",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                    AlertDialog.Builder(this@SalesListActivity)
                                                        .setTitle("Error")
                                                        .setMessage("Payment successful but no invoice received: $error")
                                                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                                        .show()
                                                    finish()
                                                }
                                            }
                                        }
                                    }
                                    .setNegativeButton("Return") { dialog, _ ->
                                        // Navigate to ReturnCartActivity.
                                        val intent = Intent(this@SalesListActivity, ReturnCartActivity::class.java).apply {
                                            putExtra("RETURN_INVOICE_ID", invoice.invoiceNumber)
                                        }
                                        startActivity(intent)
                                    }
                                    .setNeutralButton("Cancel", null)
                                    .show()
                            }
                            else -> {
                                // Fallback for other statuses in draft mode.
                                val intent = Intent(this@SalesListActivity, CartActivity::class.java).apply {
                                    putExtra("DRAFT_INVOICE_ID", invoice.invoiceNumber)
                                }
                                startActivity(intent)
                            }
                        }
                    }
                    1 -> {
                        // For status code 1, show the dialog offering actions.
                        AlertDialog.Builder(this@SalesListActivity)
                            .setTitle("Select Action")
                            .setMessage("What would you like to do with Sales Invoice ${invoice.invoiceNumber}?")
                            .setPositiveButton("Print Layout") { dialog, _ ->

                                fetchFullInvoice(docName) { invoiceJson, error ->
                                    runOnUiThread {
                                        invoiceJson?.let {
                                            // Parse the JSON into an ERPNextInvoice object.
                                            val erpInvoice = parseInvoice(it)
                                            // Start ReceiptActivity and pass the ERPNextInvoice object.
                                            val intent = Intent(this@SalesListActivity, ReceiptActivity::class.java).apply {
                                                putExtra("INVOICE", erpInvoice)
                                            }
                                            startActivity(intent)
                                            finish()
                                        } ?: run {
                                            Toast.makeText(
                                                this@SalesListActivity,
                                                "Payment successful but no invoice received: $error",
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            AlertDialog.Builder(this@SalesListActivity)
                                                .setTitle("Error")
                                                .setMessage("Payment successful but no invoice received: $error")
                                                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                                .show()
                                            finish()
                                        }
                                    }
                                }
                            }
                            .setNegativeButton("Return") { dialog, _ ->
                                val intent = Intent(this@SalesListActivity, ReturnCartActivity::class.java).apply {
                                    putExtra("RETURN_INVOICE_ID", invoice.invoiceNumber)
                                }
                                startActivity(intent)
                            }
                            .setNeutralButton("Cancel", null)
                            .show()
                    }
                    2 -> { // Cancelled – simply notify the user.
                        Toast.makeText(
                            this@SalesListActivity,
                            "Invoice ${invoice.invoiceNumber} is Cancelled.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    else -> {
                        // Default action for any other document status.
                        Toast.makeText(
                            this@SalesListActivity,
                            "Invoice ${invoice.invoiceNumber} selected",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

            }
        })

        recyclerView.adapter = adapter

        // Set up SearchView to filter as text changes.
        searchView = findViewById(R.id.search_view)
        val sharedPreferences = getSharedPreferences("session_cookie", Context.MODE_PRIVATE)
        val invoiceType = sharedPreferences.getString("invoice_type","")
        if (invoiceType != null) {
            Log.e("SalesListActivity",invoiceType)
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }
            override fun onQueryTextChange(newText: String?): Boolean {
                currentSearchQuery = newText.orEmpty()
                if (invoiceType != null) {
                    fetchSalesInvoices(currentFilter, currentSearchQuery,invoiceType)
                }
                return true
            }
        })

        // Set up TabLayout with statuses.
        val tabLayout = findViewById<TabLayout>(R.id.tab_layout)
        listOf("All", "Draft", "Cancelled", "Overdue", "Paid", "Return").forEach { status ->
            tabLayout.addTab(tabLayout.newTab().setText(status))
        }

        // Load default filter ("All") on startup.
        if (invoiceType != null) {
            fetchSalesInvoices("All", currentSearchQuery, invoiceType)
        }

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentFilter = tab.text.toString()
                if (invoiceType != null) {
                    fetchSalesInvoices(currentFilter, currentSearchQuery,invoiceType)
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {
                currentFilter = tab.text.toString()
                if (invoiceType != null) {
                    fetchSalesInvoices(currentFilter, currentSearchQuery, invoiceType)
                }
            }
        })
        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.nav_view)
        bottomNavigationView.setOnNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_home -> {
                    // Launch the SalesListActivity when "Orders" is selected
                    startActivity(Intent(this, Dashboard::class.java))
                    true
                }
                R.id.nav_orders -> {
                    // Launch the SalesListActivity when "Orders" is selected
                    startActivity(Intent(this, SalesListActivity::class.java))
                    true
                }
                R.id.nav_chat -> {
                    startActivity(Intent(this, ChatActivity::class.java))
                    true
                }
                R.id.nav_inbox ->{
                    startActivity(Intent(this, ReportActivity::class.java))
                    true
                }
                // Handle other menu items if needed...
                else -> false
            }
        }
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

        createClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null, "Network error during fetching invoice: ${e.message}")
                AlertDialog.Builder(this@SalesListActivity)
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
                    AlertDialog.Builder(this@SalesListActivity)
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
     * Creates an OkHttpClient that forces HTTP/1.1 and adds common headers.
     */
    private fun createClient(): OkHttpClient {
        val trustAllCertificates = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCertificates, SecureRandom())
        val sslSocketFactory = sslContext.socketFactory

        // Interceptor to remove any "Expect" header and add "Accept" header.
        val headerInterceptor = Interceptor { chain ->
            val originalRequest = chain.request()
            val newRequest = originalRequest.newBuilder()
                .removeHeader("Expect")
                .header("Accept", "application/json")
                .build()
            chain.proceed(newRequest)
        }

        return OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .addInterceptor(headerInterceptor)
            .sslSocketFactory(sslSocketFactory, trustAllCertificates[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /**
     * Fetch sales invoices based on the selected filter and search query.
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun fetchSalesInvoices(
        filter: String,
        searchQuery: String = "",
        invoiceType: String
    ) {
        // Clear current list
        salesInvoices.clear()
        adapter.notifyDataSetChanged()

        val sharedPreferences = getSharedPreferences(sharedPreferencesKey, Context.MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString(sessionCookieKey, null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)

        if (sessionCookie == null || erpnextUrl == null) {
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_LONG).show()
            AlertDialog.Builder(this@SalesListActivity)
                .setTitle("Error")
                .setMessage("Session expired. Please log in again.")
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .show()
            return
        }

        // Build filters as a JSON array
        val filtersArray = JSONArray()

        when (filter) {
            "Draft" -> {
                val condition = JSONArray().apply {
                    put("docstatus")
                    put("=")
                    put(0)
                }
                filtersArray.put(condition)
            }
            "Cancelled" -> {
                val condition = JSONArray().apply {
                    put("docstatus")
                    put("=")
                    put(2)
                }
                filtersArray.put(condition)
            }
            "Overdue" -> {
                val condition = JSONArray().apply {
                    put("status")
                    put("=")
                    put("Overdue")
                }
                filtersArray.put(condition)
            }
            "Paid" -> {
                val condition = JSONArray().apply {
                    put("status")
                    put("=")
                    put("Paid")
                }
                filtersArray.put(condition)
            }
            "Return" -> {
                val condition = JSONArray().apply {
                    put("status")
                    put("=")
                    put("Return")
                }
                filtersArray.put(condition)
            }
            // "All" or any other value adds no specific filter condition.
        }

        // If a search query is provided, add a "like" filter condition for the invoice name.
        if (searchQuery.isNotEmpty()) {
            val searchCondition = JSONArray().apply {
                put("name")
                put("like")
                put("%$searchQuery%")
            }
            filtersArray.put(searchCondition)
        }

        val fields = "[\"name\", \"status\", \"docstatus\"]"
        // Construct the URL including filters if any are present.
        val url = if (filtersArray.length() > 0) {
            Log.e("SalesListActivity",invoiceType)
            if (invoiceType == "sales_invoice") {
                Log.e("SalesListActivity",invoiceType)
                "https://$erpnextUrl/api/resource/Sales%20Invoice?filters=${URLEncoder.encode(filtersArray.toString(), "UTF-8")}&fields=${URLEncoder.encode(fields, "UTF-8")}"
            } else {
                "https://$erpnextUrl/api/resource/POS%20Invoice?filters=${URLEncoder.encode(filtersArray.toString(), "UTF-8")}&fields=${URLEncoder.encode(fields, "UTF-8")}"
            }
        } else {
            Log.e("SalesListActivity",invoiceType)
            if (invoiceType == "sales_invoice") {
                Log.e("SalesListActivity",invoiceType)
                "https://$erpnextUrl/api/resource/Sales%20Invoice?fields=${URLEncoder.encode(fields, "UTF-8")}"
            } else {
                "https://$erpnextUrl/api/resource/POS%20Invoice?fields=${URLEncoder.encode(fields, "UTF-8")}"
            }
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Cookie", sessionCookie)
            // Override any "Expect" header to avoid 417 errors
            .addHeader("Expect", "")
            .build()

        createClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@SalesListActivity, "Network Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                    AlertDialog.Builder(this@SalesListActivity)
                        .setTitle("Network Error")
                        .setMessage("Network Error: ${e.localizedMessage}")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }
            @SuppressLint("NotifyDataSetChanged")
            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    Log.e("SalesListActivity", "$response")
                    if (!response.isSuccessful) {
                        Toast.makeText(this@SalesListActivity, "Error: ${response.code} ${response.message}", Toast.LENGTH_LONG).show()
                        AlertDialog.Builder(this@SalesListActivity)
                            .setTitle("Error")
                            .setMessage("Error: ${response.code} ${response.message}")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    } else {
                        response.body?.string()?.let { bodyString ->
                            try {
                                val jsonObject = JSONObject(bodyString)
                                val dataArray = jsonObject.optJSONArray("data")
                                if (dataArray != null) {
                                    for (i in 0 until dataArray.length()) {
                                        val invoiceObj = dataArray.getJSONObject(i)
                                        val invoice = SalesInvoice(
                                            invoiceNumber = invoiceObj.optString("name", "N/A"),
                                            status = invoiceObj.optString("status", "Unknown"),
                                            docStatus = invoiceObj.optInt("docstatus", -1),
                                            invoiceId = invoiceObj.optString("invoice_id", ""),
                                            customerName = invoiceObj.optString("customer_name", ""),
                                            totalAmount = invoiceObj.optDouble("total_amount", 0.0),
                                            currency = invoiceObj.optString("currency", ""),
                                            modified = invoiceObj.optString("modified","")
                                        )
                                        salesInvoices.add(invoice)
                                    }
                                    adapter.notifyDataSetChanged()
                                } else {

                                }
                            } catch (e: JSONException) {
                                Log.e("SalesListActivity", "JSON parsing error: ${e.localizedMessage}", e)
                                AlertDialog.Builder(this@SalesListActivity)
                                    .setTitle("Error")
                                    .setMessage("JSON parsing error: ${e.localizedMessage}")
                                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                    .show()
                            }
                        }
                    }
                }
            }
        })
    }
}
