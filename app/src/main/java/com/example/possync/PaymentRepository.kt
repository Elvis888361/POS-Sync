package com.example.possync

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class PaymentRepository(private val context: Context) {

    private val sharedPreferences =
        context.getSharedPreferences("ERPNextPreferences", Context.MODE_PRIVATE)

    /**
     * Submits payments for the given invoice.
     *
     * First, this method updates the invoice’s payments.
     * Then, it submits the invoice by updating docstatus to 1.
     * Finally, it performs a GET request to fetch the complete invoice details.
     */
    fun submitPayments(
        invoice: ERPNextInvoice,
        payments: List<InvoicePayment>,
        callback: (ERPNextInvoice?, String?) -> Unit
    ) {
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
            ?: run {
                callback(null, "Session expired")
                return
            }
        val domain = sharedPreferences.getString("ERPNextUrl", null)
            ?: run {
                callback(null, "Domain not configured")
                return
            }
        // Use invoice.id if available; otherwise, fallback to invoice.name.
        val invoiceIdentifier = invoice.id.ifBlank { invoice.name }

        // Determine invoice type.
        // First, try to get the stored type, then fallback to checking the identifier prefix.
        val storedInvoiceType = sharedPreferences.getString("invoice_type", "")
        val isSalesInvoice = if (storedInvoiceType.isNullOrBlank()) {
            // Fallback: if the stored type is empty then assume it's a sales invoice if the ID starts with "ACC-SINV"
            invoiceIdentifier.startsWith("ACC-SINV")
        } else {
            storedInvoiceType.equals("sales_invoice", ignoreCase = true) ||
                    storedInvoiceType.equals("Sales Invoice", ignoreCase = true)
        }

        Log.d("PaymentRepository", "Invoice identifier: $invoiceIdentifier, stored type: '$storedInvoiceType', isSalesInvoice: $isSalesInvoice")

        // --- Step 1: Update the invoice's payments ---
        val updateUrl = if (isSalesInvoice) {
            "https://$domain/api/resource/Sales%20Invoice/$invoiceIdentifier"
        } else {
            "https://$domain/api/resource/POS%20Invoice/$invoiceIdentifier"
        }

        val updatePayload = JSONObject().apply {
            put("data", JSONObject().apply {
                // Update payments array
                put("payments", JSONArray().apply {
                    payments.forEach { payment ->
                        put(JSONObject().apply {
                            put("mode_of_payment", payment.mode_of_payment)
                            put("amount", payment.amount)
                        })
                    }
                })
            })
        }
        Log.d("PaymentRepository", "Update URL: $updateUrl")
        Log.d("PaymentRepository", "Update payload: $updatePayload")

        val updateRequest = Request.Builder()
            .url(updateUrl)
            .addHeader("Cookie", sessionCookie)
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json")
            .put(
                RequestBody.create(
                    "application/json".toMediaTypeOrNull(),
                    updatePayload.toString()
                )
            )
            .build()

        getUnsafeClient().newCall(updateRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("PaymentRepository", "Network error during update: ${e.message}")
                Handler(Looper.getMainLooper()).post {
                    if (context is Activity && !context.isFinishing) {
                        AlertDialog.Builder(context)
                            .setTitle("Network Error")
                            .setMessage(
                                "There was a problem connecting to the server. " +
                                        "Please check your internet connection and try again.\n\nError: ${e.message}"
                            )
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                }
                callback(null, "Network error during update: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val updateBody = response.body?.string()
                Log.d("PaymentRepository", "Update response code: ${response.code}, body: $updateBody")

                if (!response.isSuccessful) {
                    if (context is Activity && !context.isFinishing) {
                        Handler(Looper.getMainLooper()).post {
                            AlertDialog.Builder(context)
                                .setTitle("Update Failed")
                                .setMessage(
                                    "Update request failed with HTTP ${response.code}.\n" +
                                            "Message: ${response.message}\nDetails: ${updateBody ?: "No details available"}"
                                )
                                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                .show()
                        }
                    }
                    callback(null, "HTTP ${response.code} during update: ${response.message}\n$updateBody")
                    return
                }
                // --- Proceed to Step 2: Submit the invoice ---
                submitInvoice(invoiceIdentifier, callback, isSalesInvoice)
            }
        })
    }

    /**
     * Submits the invoice by sending a PUT request that sets docstatus to 1 (Submitted).
     * After a successful submission, it calls fetchFullInvoice() to retrieve complete invoice details.
     */
    private fun submitInvoice(
        invoiceIdentifier: String,
        callback: (ERPNextInvoice?, String?) -> Unit,
        isSalesInvoice: Boolean
    ) {
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
            ?: run {
                callback(null, "Session expired")
                return
            }
        val domain = sharedPreferences.getString("ERPNextUrl", null)
            ?: run {
                callback(null, "Domain not configured")
                return
            }
        val submitUrl = if (isSalesInvoice) {
            "https://$domain/api/resource/Sales%20Invoice/$invoiceIdentifier"
        } else {
            "https://$domain/api/resource/POS%20Invoice/$invoiceIdentifier"
        }
        Log.d("PaymentRepository", "Submit URL: $submitUrl")

        // Build the payload to update docstatus to 1
        val submitPayload = JSONObject().apply {
            put("data", JSONObject().apply {
                put("docstatus", 1)
            })
        }
        Log.d("PaymentRepository", "Submit payload: $submitPayload")

        val submitRequest = Request.Builder()
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

        getUnsafeClient().newCall(submitRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("PaymentRepository", "Network error during submit: ${e.message}")
                callback(null, "Network error during submit: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val submitBody = response.body?.string()
                Log.d("PaymentRepository", "Submit response code: ${response.code}, body: $submitBody")
                if (!response.isSuccessful) {
                    callback(null, "HTTP ${response.code} during submit: ${response.message}\n$submitBody")
                    return
                }
                // --- Proceed to Step 3: Fetch the complete invoice details ---
                fetchFullInvoice(invoiceIdentifier, callback, isSalesInvoice)
            }
        })
    }

    /**
     * Fetches the complete Sales Invoice document via a GET request.
     */
    private fun fetchFullInvoice(
        invoiceIdentifier: String,
        callback: (ERPNextInvoice?, String?) -> Unit,
        isSalesInvoice: Boolean
    ) {
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
            ?: run {
                callback(null, "Session expired")
                return
            }
        val domain = sharedPreferences.getString("ERPNextUrl", null)
            ?: run {
                callback(null, "Domain not configured")
                return
            }
        val getUrl = if (isSalesInvoice) {
            "https://$domain/api/resource/Sales%20Invoice/$invoiceIdentifier"
        } else {
            "https://$domain/api/resource/POS%20Invoice/$invoiceIdentifier"
        }
        Log.d("PaymentRepository", "GET URL: $getUrl")

        val getRequest = Request.Builder()
            .url(getUrl)
            .addHeader("Cookie", sessionCookie)
            .addHeader("Accept", "application/json")
            .build()

        getUnsafeClient().newCall(getRequest).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("PaymentRepository", "Network error during fetch: ${e.message}")
                callback(null, "Network error during fetch: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val getBody = response.body?.string()
                Log.d("PaymentRepository", "GET response code: ${response.code}, body: $getBody")
                if (!response.isSuccessful) {
                    callback(null, "HTTP ${response.code} during fetch: ${response.message}\n$getBody")
                    return
                }
                try {
                    val json = JSONObject(getBody ?: "")
                    val data = json.getJSONObject("data")
                    // Parse all expected fields. (Ensure keys match your ERPNext output.)
                    val fullInvoice = ERPNextInvoice(
                        name = data.getString("name"),
                        customer = data.getString("customer"),
                        total = data.getDouble("total"),
                        grandTotal = data.getDouble("grand_total"),
                        currency = data.getString("currency"),
                        id = data.getString("name"),
                        payments = parsePayments(data),
                        items = parseItems(data),
                        company = data.optString("company", ""),
                        companyaddress = data.optString("company_address", ""),
                        customername = data.optString("customer_name", ""),
                        companytaxid = data.optString("company_tax_id", ""),
                        companyaddressdisplay = data.optString("company_address_display", ""),
                        amount = data.optDouble("amount", 0.0),
                        date = data.optString("date", ""),
                        invoiceNumber = data.getString("name"),
                        status = data.optString("status", ""),
                        docStatus = data.optInt("docstatus", 0),
                        modified = data.optString("modified", "")
                    )
                    callback(fullInvoice, null)
                } catch (e: Exception) {
                    Log.e("PaymentRepository", "Parse error during GET: ${e.message}")
                    callback(null, "Parse error during GET: ${e.message}")
                }
            }
        })
    }

    /**
     * Parses the payments array from the invoice JSON.
     */
    private fun parsePayments(data: JSONObject): List<InvoicePayment> {
        val paymentsList = mutableListOf<InvoicePayment>()
        if (data.has("payments")) {
            val paymentsArray = data.getJSONArray("payments")
            for (i in 0 until paymentsArray.length()) {
                val payObj = paymentsArray.getJSONObject(i)
                paymentsList.add(
                    InvoicePayment(
                        mode_of_payment = payObj.optString("mode_of_payment", ""),
                        amount = payObj.optDouble("amount", 0.0)
                    )
                )
            }
        }
        return paymentsList
    }

    /**
     * Parses the items array from the invoice JSON.
     */
    private fun parseItems(data: JSONObject): List<InvoiceItem> {
        val itemsList = mutableListOf<InvoiceItem>()
        if (data.has("items")) {
            val itemsArray = data.getJSONArray("items")
            for (i in 0 until itemsArray.length()) {
                val itemObj = itemsArray.getJSONObject(i)
                itemsList.add(
                    InvoiceItem(
                        item_code = itemObj.optString("item_code", ""),
                        qty = itemObj.optInt("qty", 0),
                        rate = itemObj.optDouble("rate", 0.0)
                    )
                )
            }
        }
        return itemsList
    }

    /**
     * Returns an OkHttpClient that trusts all SSL certificates.
     * Note: This bypasses SSL validation. Use only for testing.
     */
    private fun getUnsafeClient(): OkHttpClient {
        val trustAllCertificates = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCertificates, SecureRandom())
        }

        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val newRequest = originalRequest.newBuilder()
                    .removeHeader("Expect")
                    .build()
                chain.proceed(newRequest)
            }
            .sslSocketFactory(sslContext.socketFactory, trustAllCertificates[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
