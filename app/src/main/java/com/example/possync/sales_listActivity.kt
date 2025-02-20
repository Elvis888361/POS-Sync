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
                // Handle invoice click based on status.
                when (invoice.status) {
                    "Draft" -> {
                        // Open for editing.
                        val intent = Intent(this@SalesListActivity, CartActivity::class.java)
                        intent.putExtra("DRAFT_INVOICE_ID", invoice.invoiceNumber)
                        startActivity(intent)
                    }
                    "Paid", "Overdue" -> {
                        // Show a dialog offering a print layout or return.
                        AlertDialog.Builder(this@SalesListActivity)
                            .setTitle("Select Action")
                            .setMessage("What would you like to do with Sales Invoice ${invoice.invoiceNumber}?")
                            .setPositiveButton("Print Layout") { dialog, _ ->
                                // Launch print preview activity or handle print.
                                Toast.makeText(this@SalesListActivity, "Print Layout not implemented", Toast.LENGTH_SHORT).show()
                            }
                            .setNegativeButton("Return") { dialog, _ ->
                                // Navigate to ReturnCartActivity.
                                val intent = Intent(this@SalesListActivity, ReturnCartActivity::class.java)
                                intent.putExtra("RETURN_INVOICE_ID", invoice.invoiceNumber)
                                startActivity(intent)
                            }
                            .setNeutralButton("Cancel", null)
                            .show()
                    }
                    else -> {
                        Toast.makeText(this@SalesListActivity, "Invoice ${invoice.invoiceNumber} selected", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
        recyclerView.adapter = adapter

        // Set up SearchView to filter as text changes.
        searchView = findViewById(R.id.search_view)
        val sharedPreferences = getSharedPreferences("ERPNextPreferences", Context.MODE_PRIVATE)
        val invoiceType = sharedPreferences.getString("invoice_type","sales_invoice")
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
                                            currency = invoiceObj.optString("currency", "")
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
