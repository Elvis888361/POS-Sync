package com.example.possync

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ReportActivity : AppCompatActivity() {

    private lateinit var tvReportTitle: TextView
    private lateinit var etDateFrom: EditText
    private lateinit var etDateTo: EditText
    private lateinit var btnApplyFilters: Button
    private lateinit var recyclerReport: RecyclerView
    private lateinit var progressBarReport: ProgressBar
    private lateinit var reportAdapter: ReportAdapter
    private lateinit var chartWebView: WebView

    // Data class representing an invoice.
    data class Invoice(val invoiceNumber: String, val date: String, val amount: Double, val status: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)

        tvReportTitle = findViewById(R.id.tvReportTitle)
        etDateFrom = findViewById(R.id.etDateFrom)
        etDateTo = findViewById(R.id.etDateTo)
        btnApplyFilters = findViewById(R.id.btnApplyFilters)
        recyclerReport = findViewById(R.id.recyclerReport)
        progressBarReport = findViewById(R.id.progressBarReport)
        chartWebView = findViewById(R.id.chartWebView)

        recyclerReport.layoutManager = LinearLayoutManager(this)
        reportAdapter = ReportAdapter()
        recyclerReport.adapter = reportAdapter

        // Prepopulate date fields with today's date.
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        etDateFrom.setText(today)
        etDateTo.setText(today)

        // Setup date pickers.
        etDateFrom.setOnClickListener { showDatePicker(etDateFrom) }
        etDateTo.setOnClickListener { showDatePicker(etDateTo) }

        // Retrieve ERPNext preferences (ERPNext URL, invoice type, session cookie).
        val spErp = getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
        var erpnextUrl = spErp.getString("ERPNextUrl", "your-erpnext-instance.com")
        // Ensure URL has a proper scheme.
        if (erpnextUrl != null && !erpnextUrl.startsWith("http://") && !erpnextUrl.startsWith("https://")) {
            erpnextUrl = "https://$erpnextUrl"
        }
        val invoiceType = spErp.getString("invoiceType", "sales_invoice") ?: "sales_invoice"
        tvReportTitle.text = "${invoiceType.capitalize()} Invoice Report"

        // Generate the report with default date filters.
        generateReportData(invoiceType, mapOf("Date From" to etDateFrom.text.toString(),
            "Date To" to etDateTo.text.toString()), erpnextUrl)

        btnApplyFilters.setOnClickListener {
            progressBarReport.visibility = View.VISIBLE
            val filters = mapOf(
                "Date From" to etDateFrom.text.toString(),
                "Date To" to etDateTo.text.toString()
            )
            generateReportData(invoiceType, filters, erpnextUrl)
        }

        // Setup bottom navigation.
        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.nav_view)
        bottomNavigationView.setOnNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_home -> {
                    startActivity(Intent(this, Dashboard::class.java))
                    true
                }
                R.id.nav_orders -> {
                    startActivity(Intent(this, SalesListActivity::class.java))
                    true
                }
                R.id.nav_chat -> {
                    startActivity(Intent(this, ChatActivity::class.java))
                    true
                }
                R.id.nav_inbox -> {
                    startActivity(Intent(this, ReportActivity::class.java))
                    true
                }
                else -> false
            }
        }
    }

    // Opens a DatePickerDialog and updates the provided EditText with the selected date.
    private fun showDatePicker(editText: EditText) {
        val calendar = Calendar.getInstance()
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH)
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        DatePickerDialog(this, { _, selectedYear, selectedMonth, selectedDay ->
            val formattedDate = String.format("%04d-%02d-%02d", selectedYear, selectedMonth + 1, selectedDay)
            editText.setText(formattedDate)
        }, year, month, day).show()
    }

    // Generates the report by fetching ERPNext data and computing summary metrics.
    private fun generateReportData(invoiceType: String, filters: Map<String, String>, erpnextUrl: String?) {
        progressBarReport.visibility = View.VISIBLE

        fetchInvoicesFromERPNext(invoiceType, filters, erpnextUrl) { invoices, error ->
            runOnUiThread {
                if (error != null) {
                    Toast.makeText(this, "Error fetching data: $error", Toast.LENGTH_LONG).show()
                    progressBarReport.visibility = View.GONE
                    return@runOnUiThread
                }

                if (invoices != null) {
                    // Compute summary metrics.
                    val totalInvoices = invoices.size
                    val totalAmount = invoices.sumOf { it.amount }
                    val statusCounts = invoices.groupingBy { it.status }.eachCount()

                    // Build report rows for the RecyclerView.
                    val reportRows = mutableListOf<Map<String, Any>>()
                    reportRows.add(mapOf("Metric" to "Invoice Type", "Value" to invoiceType.capitalize()))
                    reportRows.add(mapOf("Metric" to "Total Invoices", "Value" to totalInvoices))
                    reportRows.add(mapOf("Metric" to "Total Amount", "Value" to "$$totalAmount"))
                    for ((status, count) in statusCounts) {
                        reportRows.add(mapOf("Metric" to "Invoices $status", "Value" to count))
                    }
                    reportAdapter.updateData(reportRows)

                    // Update the WebView chart with the status distribution.
                    updateWebChart(statusCounts)
                } else {
                    Toast.makeText(this, "No data available", Toast.LENGTH_SHORT).show()
                }
                progressBarReport.visibility = View.GONE
            }
        }
    }

    // Builds an HTML string using Chart.js to render a pie chart, then loads it into the WebView.
    private fun updateWebChart(statusCounts: Map<String, Int>) {
        // Build JavaScript arrays for labels and data.
        val labels = statusCounts.keys.joinToString(separator = "\",\"")
        val values = statusCounts.values.joinToString(separator = ",")
        val html = """
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <script src="https://cdn.jsdelivr.net/npm/chart.js"></script>
            </head>
            <body style="margin:0;padding:0;">
              <canvas id="pieChart" style="width:100%;height:100%;"></canvas>
              <script>
                var ctx = document.getElementById('pieChart').getContext('2d');
                var chart = new Chart(ctx, {
                    type: 'pie',
                    data: {
                        labels: ["$labels"],
                        datasets: [{
                            data: [$values],
                            backgroundColor: [
                                'rgba(255, 99, 132, 0.6)',
                                'rgba(54, 162, 235, 0.6)',
                                'rgba(255, 206, 86, 0.6)',
                                'rgba(75, 192, 192, 0.6)',
                                'rgba(153, 102, 255, 0.6)',
                                'rgba(255, 159, 64, 0.6)'
                            ]
                        }]
                    },
                    options: {
                        responsive: true,
                        maintainAspectRatio: false
                    }
                });
              </script>
            </body>
            </html>
        """.trimIndent()

        chartWebView.settings.javaScriptEnabled = true
        chartWebView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }

    // Fetches invoices from ERPNext using its REST API.
    private fun fetchInvoicesFromERPNext(
        invoiceType: String,
        filters: Map<String, String>,
        erpnextUrl: String?,
        callback: (List<Invoice>?, String?) -> Unit
    ) {
        if (erpnextUrl.isNullOrEmpty()) {
            callback(null, "ERPNext URL not configured")
            return
        }

        // Retrieve session cookie.
        val spErp = getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
        val sessionCookie = spErp.getString("sessionCookie", null) ?: run {
            callback(null, "Session expired")
            return
        }

        // Determine endpoint based on invoice type.
        val encodedEndpoint = if (invoiceType.equals("sales_invoice", ignoreCase = true))
            "Sales%20Invoice" else "POS%20Invoice"

        // Build the API URL.
        var url = "$erpnextUrl/api/resource/$encodedEndpoint?fields=[\"name\",\"posting_date\",\"total\",\"status\"]"
        val dateFrom = filters["Date From"]
        val dateTo = filters["Date To"]
        if (!dateFrom.isNullOrEmpty() && !dateTo.isNullOrEmpty()) {
            val filterJson = "[[\"posting_date\",\">=\",\"$dateFrom\"],[\"posting_date\",\"<=\",\"$dateTo\"]]"
            url += "&filters=" + URLEncoder.encode(filterJson, "UTF-8")
        }
        Log.d("ReportActivity", "Fetch URL: $url")

        val client = createClient()
        val request = Request.Builder()
            .url(url)
            .addHeader("Cookie", sessionCookie)
            .get()
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                callback(null, "Network error: ${e.localizedMessage}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    callback(null, "HTTP ${response.code} error: ${response.message}\n$responseBody")
                    return
                }
                try {
                    val json = JSONObject(responseBody)
                    val dataArray = json.getJSONArray("data")
                    val invoiceList = mutableListOf<Invoice>()
                    for (i in 0 until dataArray.length()) {
                        val invoiceObject = dataArray.getJSONObject(i)
                        val name = invoiceObject.getString("name")
                        val postingDate = invoiceObject.getString("posting_date")
                        val total = invoiceObject.getDouble("total")
                        val status = invoiceObject.getString("status")
                        invoiceList.add(Invoice(name, postingDate, total, status))
                    }
                    callback(invoiceList, null)
                } catch (e: Exception) {
                    callback(null, "JSON Parsing Error: ${e.localizedMessage}")
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
}
