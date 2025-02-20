package com.example.possync

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.*
import com.example.possync.ReportAdapter
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class ReportActivity : AppCompatActivity() {

    private lateinit var spinnerReports: Spinner
    private lateinit var filtersContainer: LinearLayout
    private lateinit var btnApplyFilters: Button
    private lateinit var recyclerReport: RecyclerView
    private lateinit var progressBarReport: ProgressBar

    // Hardcoded list of reports for demonstration.
    private val reportList = mutableListOf("Sales Report", "Inventory Report", "Customer Report")
    // A map to store dynamically generated filter EditTexts.
    private val customFilters = mutableMapOf<String, EditText>()
    private lateinit var reportAdapter: ReportAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_report)

        spinnerReports = findViewById(R.id.spinnerReports)
        filtersContainer = findViewById(R.id.filtersContainer)
        btnApplyFilters = findViewById(R.id.btnApplyFilters)
        recyclerReport = findViewById(R.id.recyclerReport)
        progressBarReport = findViewById(R.id.progressBarReport)

        recyclerReport.layoutManager = LinearLayoutManager(this)
        reportAdapter = ReportAdapter()
        recyclerReport.adapter = reportAdapter

        spinnerReports.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, reportList)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        spinnerReports.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>, view: View?, position: Int, id: Long
            ) {
                val selectedReport = reportList[position]
                // Dynamically load filters for the selected report.
                loadReportFilters(selectedReport)
                // Fetch report data with no filters initially.
                fetchReportData(selectedReport, emptyMap())
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }

        btnApplyFilters.setOnClickListener {
            val selectedReport = spinnerReports.selectedItem as String
            // Collect filter values from the dynamic fields.
            val filters = customFilters.mapValues { it.value.text.toString() }
            fetchReportData(selectedReport, filters)
        }
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

    // Dynamically create filter fields based on the report name.
    private fun loadReportFilters(reportName: String) {
        filtersContainer.removeAllViews()
        customFilters.clear()

        when (reportName) {
            "Sales Report" -> {
                addFilterField("Date From")
                addFilterField("Date To")
                addFilterField("Customer")
            }
            "Inventory Report" -> {
                addFilterField("Warehouse")
                addFilterField("Item Code")
            }
            "Customer Report" -> {
                addFilterField("Customer Group")
                addFilterField("Country")
            }
        }
        val visibility = if (filtersContainer.childCount > 0) View.VISIBLE else View.GONE
        filtersContainer.visibility = visibility
        btnApplyFilters.visibility = visibility
    }

    // Helper to add a filter field (label and EditText) to the filters container.
    private fun addFilterField(filterName: String) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        val label = TextView(this).apply {
            text = filterName
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val editText = EditText(this).apply {
            hint = "Enter $filterName"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        layout.addView(label)
        layout.addView(editText)
        filtersContainer.addView(layout)
        customFilters[filterName] = editText
    }

    // Calls the ERPNext report API with the report name and any filters.
    private fun fetchReportData(reportName: String, filters: Map<String, String>) {
        progressBarReport.visibility = View.VISIBLE

        val sharedPreferences = getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)
        if (sessionCookie == null || erpnextUrl == null) {
            runOnUiThread {
                progressBarReport.visibility = View.GONE
                Toast.makeText(this, "Session expired", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // Example endpoint for running a report; adjust as needed.
        val url = "https://$erpnextUrl/api/method/frappe.desk.query_report.get_report"
        val payload = JSONObject().apply {
            put("report_name", reportName)
            put("filters", JSONObject(filters))
        }

        val requestBody = payload.toString().toRequestBody("application/json".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Cookie", sessionCookie)
            .addHeader("Content-Type", "application/json")
            .build()

        getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    progressBarReport.visibility = View.GONE
                    Toast.makeText(this@ReportActivity, "Failed to fetch report: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                runOnUiThread { progressBarReport.visibility = View.GONE }
                if (!response.isSuccessful) {
                    runOnUiThread {
                        Toast.makeText(this@ReportActivity, "Error: ${response.message}", Toast.LENGTH_LONG).show()
                    }
                } else {
                    try {
                        val jsonResponse = JSONObject(responseBody)
                        val dataArray = jsonResponse.getJSONArray("data")
                        val reportData = mutableListOf<Map<String, Any>>()
                        for (i in 0 until dataArray.length()) {
                            val row = dataArray.getJSONObject(i)
                            val rowMap = mutableMapOf<String, Any>()
                            val keys = row.keys()
                            while (keys.hasNext()) {
                                val key = keys.next()
                                rowMap[key] = row.get(key)
                            }
                            reportData.add(rowMap)
                        }
                        runOnUiThread {
                            reportAdapter.updateData(reportData)
                        }
                    } catch (e: JSONException) {
                        runOnUiThread {
                            Toast.makeText(this@ReportActivity, "Failed to parse report data", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        })
    }

    // Placeholder for getUnsafeOkHttpClient() – replace with your actual OkHttpClient configuration.
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

}
