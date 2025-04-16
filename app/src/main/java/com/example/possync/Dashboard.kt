package com.example.possync

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import okhttp3.*
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
import java.time.LocalDate

class Dashboard : AppCompatActivity() {
    // Example ViewModel (adjust or remove if you do not use one)
    private lateinit var viewModel: POSViewModel
    private val sharedPreferencesKey = "ERPNextPreferences"
    private val sessionCookieKey = "sessionCookie"
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: SalesHistoryAdapter
    private val salesInvoices = mutableListOf<SalesInvoice>()

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dashboard)

        viewModel = ViewModelProvider(this)[POSViewModel::class.java]

        // Example button to start a new sale
        val btnNewSale = findViewById<Button>(R.id.btn_new_sale)
        btnNewSale.setOnClickListener {
            startActivity(Intent(this, POS::class.java))
        }
        val btnNewCustomer = findViewById<Button>(R.id.btn_new_customer)
        btnNewCustomer.setOnClickListener {
            startActivity(Intent(this, AddCustomerActivity::class.java))
        }

        // (Optional) Setup a sales history RecyclerView
        recyclerView = findViewById(R.id.sales_history_list)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = SalesHistoryAdapter(salesInvoices)
        recyclerView.adapter = adapter
        val sharedPreferences = getSharedPreferences("session_cookie", Context.MODE_PRIVATE)
        var invoiceType = sharedPreferences.getString("invoice_type", "")
        if (invoiceType != null) {
            fetchLastFiveSales(invoiceType)
        }else{
            invoiceType="Sales Invoice"
            fetchLastFiveSales(invoiceType)
        }
        // Set adapter if needed...

        // IMPORTANT: Use BottomNavigationView (not NavigationView)
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
        val imageViewSettingsIcon = findViewById<ImageView>(R.id.settings_icon)
        imageViewSettingsIcon.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Call your API functions (adjust as needed)
        fetchCurrentCompany()
        fetchUserFullName()
        fetchTodaySales()
        fetchActiveCustomers()
    }

    // --- Helper function to create an OkHttpClient that bypasses SSL (if needed) ---
    private fun createClient(): OkHttpClient {
        val trustAllCertificates = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCertificates, SecureRandom())
        val sslSocketFactory = sslContext.socketFactory

        return OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustAllCertificates[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
    /**
     * Fetches the last 5 sales invoices ordered by the last modified date.
     */
    @SuppressLint("NotifyDataSetChanged")
    private fun fetchLastFiveSales(invoiceType:String) {
        salesInvoices.clear()
        adapter.notifyDataSetChanged()

        val sharedPreferences = getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)

        if (sessionCookie == null || erpnextUrl == null) {
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_LONG).show()
            return
        }

        // No filters used here; add filters if needed.
        val filtersArray = JSONArray()

        // Exclude invoice_id; use "name" as the invoice identifier.
        val fields = "[\"name\", \"status\", \"docstatus\", \"modified\", \"creation\", \"customer_name\", \"base_grand_total\", \"currency\"]"

        // Encode order_by parameter, replacing '+' with '%20' if needed.
        val orderByParam = URLEncoder.encode("modified desc", "UTF-8").replace("+", "%20")
        val extraParams = "&limit_page_length=5&order_by=$orderByParam"

        // Construct URL for the Sales Invoice endpoint.
        val url = if (filtersArray.length() > 0) {
            Log.e("Dashboard",invoiceType)
            if (invoiceType == "sales_invoice") {
                "https://$erpnextUrl/api/resource/Sales%20Invoice?filters=${URLEncoder.encode(filtersArray.toString(), "UTF-8")}&fields=${URLEncoder.encode(fields, "UTF-8")}" + extraParams
            }else{
                "https://$erpnextUrl/api/resource/POS%20Invoice?filters=${URLEncoder.encode(filtersArray.toString(), "UTF-8")}&fields=${URLEncoder.encode(fields, "UTF-8")}" + extraParams
            }

        } else {
            if (invoiceType == "sales_invoice") {
                "https://$erpnextUrl/api/resource/Sales%20Invoice?fields=${URLEncoder.encode(fields, "UTF-8")}" + extraParams
            }else{
                "https://$erpnextUrl/api/resource/POS%20Invoice?fields=${URLEncoder.encode(fields, "UTF-8")}" + extraParams
            }

        }

        Log.d("DashboardActivity", "Fetching from URL: $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Cookie", sessionCookie)
            .addHeader("Expect", "")
            .build()

        createClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    Toast.makeText(this@Dashboard, "Network Error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    val bodyString = response.body?.string()
                    Log.d("DashboardActivity", "Response body: $bodyString")
                    if (!response.isSuccessful) {
                        Toast.makeText(this@Dashboard, "Error: ${response.code} ${response.message}", Toast.LENGTH_LONG).show()
                    } else if (bodyString != null) {
                        try {
                            val jsonObject = JSONObject(bodyString)
                            val dataArray = jsonObject.optJSONArray("data")
                            if (dataArray != null && dataArray.length() > 0) {
                                for (i in 0 until dataArray.length()) {
                                    val invoiceObj = dataArray.getJSONObject(i)
                                    val invoice = SalesInvoice(
                                        invoiceNumber = invoiceObj.optString("name", "N/A"),
                                        status = invoiceObj.optString("status", "Unknown"),
                                        docStatus = invoiceObj.optInt("docstatus", -1),
                                        customerName = invoiceObj.optString("customer_name", ""),
                                        totalAmount = invoiceObj.optDouble("base_grand_total", 0.0),
                                        currency = invoiceObj.optString("currency", ""),
                                        // Use modified; if not available, fall back to creation date.
                                        modified = invoiceObj.optString(
                                            "modified",
                                            invoiceObj.optString("creation", "")
                                        ),
                                        invoiceId = invoiceObj.optString("name", "N/A")
                                    )
                                    salesInvoices.add(invoice)
                                }
                                adapter.notifyDataSetChanged()
                            } else {
                                Toast.makeText(this@Dashboard, "No sales records found.", Toast.LENGTH_SHORT).show()
                            }
                        } catch (e: JSONException) {
                            Toast.makeText(this@Dashboard, "JSON parsing error: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                            Log.e("DashboardActivity", "JSON parsing error", e)
                        }
                    }
                }
            }
        })
    }

    private fun fetchCurrentCompany() {
        val sharedPreferences = getSharedPreferences(sharedPreferencesKey, MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString(sessionCookieKey, null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)

        if (sessionCookie == null || erpnextUrl == null) {
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_LONG).show()
            return
        }

        val filters = "[[\"Company\", \"is_group\", \"=\", 0]]"
        val fields = "[\"name\"]"
        val url = "https://$erpnextUrl/api/resource/Company?filters=${URLEncoder.encode(filters, "UTF-8")}&fields=${URLEncoder.encode(fields, "UTF-8")}"

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Cookie", sessionCookie)
            .build()

        createClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    AlertDialog.Builder(this@Dashboard)
                        .setTitle("Network Error")
                        .setMessage("There was a problem connecting to the server. Please check your internet connection and try again.")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (!response.isSuccessful) {
                        AlertDialog.Builder(this@Dashboard)
                            .setTitle("Response Error")
                            .setMessage("Response error: ${response.code}")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    } else {
                        val responseBody = response.body?.string()
                        responseBody?.let {
                            try {
                                val jsonObject = JSONObject(it)
                                val dataArray = jsonObject.optJSONArray("data")
                                if (dataArray != null && dataArray.length() > 0) {
                                    val companyObject = dataArray.getJSONObject(0)
                                    val companyName = companyObject.optString("name", "No Company Found")
                                    findViewById<TextView>(R.id.company_name).text = "Current Company: $companyName"
                                } else {
                                    AlertDialog.Builder(this@Dashboard)
                                        .setTitle("Data Error")
                                        .setMessage("Company data not found")
                                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                        .show()
                                }
                            } catch (e: JSONException) {
                                // Log the detailed error for debugging purposes
                                Log.e("Dashboard", "JSON parsing error", e)
                                // Show a generic error message to the user
                                AlertDialog.Builder(this@Dashboard)
                                    .setTitle("Error")
                                    .setMessage("An error occurred while processing the data. Please try again.")
                                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                    .show()
                            }
                        }
                    }
                }
            }
        })

    }

    private fun fetchUserFullName() {
        val sharedPreferences = getSharedPreferences(sharedPreferencesKey, MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString(sessionCookieKey, null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)

        if (sessionCookie == null || erpnextUrl == null) {
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_LONG).show()
            return
        }

        val username = sharedPreferences.getString("username", null)
        if (username == null) {
            Toast.makeText(this, "User not found. Please log in again.", Toast.LENGTH_LONG).show()
            return
        }

        val filters = "[[\"User\", \"email\", \"=\", \"$username\"]]"
        val fields = "[\"full_name\"]"
        val url = "https://$erpnextUrl/api/resource/User?filters=${URLEncoder.encode(filters, "UTF-8")}&fields=${URLEncoder.encode(fields, "UTF-8")}"

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Cookie", sessionCookie)
            .build()

        createClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    AlertDialog.Builder(this@Dashboard)
                        .setTitle("Network Error")
                        .setMessage("There was a problem connecting to the server. Please check your internet connection and try again.")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (!response.isSuccessful) {
                        AlertDialog.Builder(this@Dashboard)
                            .setTitle("Response Error")
                            .setMessage("Response error: ${response.code}")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    } else {
                        val responseBody = response.body?.string()
                        responseBody?.let {
                            try {
                                val jsonObject = JSONObject(it)
                                val dataArray = jsonObject.optJSONArray("data")
                                if (dataArray != null && dataArray.length() > 0) {
                                    val userObject = dataArray.getJSONObject(0)
                                    val fullName = userObject.optString("full_name", "User")
                                    findViewById<TextView>(R.id.user_greeting).text = "Hello, $fullName"
                                } else {
                                    AlertDialog.Builder(this@Dashboard)
                                        .setTitle("Data Error")
                                        .setMessage("User data not found")
                                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                        .show()
                                }
                            } catch (e: JSONException) {
                                Log.e("Dashboard", "JSON parsing error", e)
                                AlertDialog.Builder(this@Dashboard)
                                    .setTitle("Error")
                                    .setMessage("An error occurred while processing your data. Please try again.")
                                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                    .show()
                            }
                        }
                    }
                }
            }
        })

    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun fetchTodaySales() {
        val sharedPreferences = getSharedPreferences(sharedPreferencesKey, MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString(sessionCookieKey, null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)

        if (sessionCookie == null || erpnextUrl == null) {
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_LONG).show()
            return
        }

        val today = LocalDate.now().toString()
        val filters = "[[\"docstatus\", \"=\", 1], [\"posting_date\", \"=\", \"$today\"]]"
        val fields = "[\"grand_total\"]"
        val invoiceType = sharedPreferences.getString("invoice_type", "")
        val url = if (invoiceType == "sales_invoice") {
            "https://$erpnextUrl/api/resource/Sales Invoice?filters=${URLEncoder.encode(filters, "UTF-8")}&fields=${URLEncoder.encode(fields, "UTF-8")}"
        } else {
            "https://$erpnextUrl/api/resource/POS Invoice?filters=${URLEncoder.encode(filters, "UTF-8")}&fields=${URLEncoder.encode(fields, "UTF-8")}"
        }

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Cookie", sessionCookie)
            .build()

        createClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    AlertDialog.Builder(this@Dashboard)
                        .setTitle("Network Error")
                        .setMessage("There was a problem connecting to the server. Please check your internet connection and try again.")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (!response.isSuccessful) {
                        AlertDialog.Builder(this@Dashboard)
                            .setTitle("Response Error")
                            .setMessage("Response error: ${response.code}")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    } else {
                        val responseBody = response.body?.string()
                        responseBody?.let {
                            try {
                                val jsonObject = JSONObject(it)
                                val dataArray = jsonObject.optJSONArray("data")
                                var totalSales = 0.0
                                if (dataArray != null) {
                                    for (i in 0 until dataArray.length()) {
                                        val invoice = dataArray.getJSONObject(i)
                                        totalSales += invoice.optDouble("grand_total", 0.0)
                                    }
                                }
                                findViewById<TextView>(R.id.today_sales).text = "KES %.2f".format(totalSales)
                            } catch (e: JSONException) {
                                Log.e("Dashboard", "JSON parsing error", e)
                                AlertDialog.Builder(this@Dashboard)
                                    .setTitle("Data Error")
                                    .setMessage("An error occurred while processing the data. Please try again.")
                                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                    .show()
                            }
                        }
                    }
                }
            }
        })

    }

    private fun fetchActiveCustomers() {
        val sharedPreferences = getSharedPreferences(sharedPreferencesKey, MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString(sessionCookieKey, null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)

        if (sessionCookie == null || erpnextUrl == null) {
            Toast.makeText(this, "Session expired. Please log in again.", Toast.LENGTH_LONG).show()
            return
        }

        val url = "https://$erpnextUrl/api/resource/Customer"

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Cookie", sessionCookie)
            .build()

        createClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    AlertDialog.Builder(this@Dashboard)
                        .setTitle("Network Error")
                        .setMessage("There was a problem connecting to the server. Please check your internet connection and try again.")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                runOnUiThread {
                    if (!response.isSuccessful) {
                        AlertDialog.Builder(this@Dashboard)
                            .setTitle("Response Error")
                            .setMessage("Response error: ${response.code}")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    } else {
                        val responseBody = response.body?.string()
                        responseBody?.let {
                            try {
                                val jsonObject = JSONObject(it)
                                val dataArray = jsonObject.optJSONArray("data")
                                val totalCount = dataArray?.length() ?: 0
                                findViewById<TextView>(R.id.active_customers).text = "$totalCount"
                            } catch (e: JSONException) {
                                Log.e("Dashboard", "JSON parsing error", e)
                                AlertDialog.Builder(this@Dashboard)
                                    .setTitle("Data Error")
                                    .setMessage("An error occurred while processing the data. Please try again.")
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
