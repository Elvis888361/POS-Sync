package com.example.possync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class POSRepository(private val context: Context) {
    private val sharedPreferences =
        context.getSharedPreferences("ERPNextPreferences", Context.MODE_PRIVATE)
    private val gson = Gson()

    // Increase timeouts to 60 seconds
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(TimeoutInterceptor())
        .build()

    private fun getBaseUrl(): String {
        val url = sharedPreferences.getString("erpnextUrlKey", "") ?: ""
        return if (url.startsWith("http")) url else "https://$url"
    }

    suspend fun createERPNextInvoice(
        items: List<CartItem>
    ): Pair<ERPNextInvoice?, Exception?> {
        return try {
            if (!isNetworkAvailable()) {
                return Pair(null, Exception("No internet connection"))
            }

            val sessionCookie = sharedPreferences.getString("sessionCookie", null)
                ?: return Pair(null, Exception("Session expired. Please login again"))

            // Fetch the payments child table from the POS Profile.
            // (We assume the POS Profile is named "POS-Profile".)
            val posPayments = fetchPOSProfilePayments(sessionCookie)
            Log.d("POSRepository", "Fetched POS Profile Payments: ${posPayments?.toString() ?: "No payments found"}")

            // Build the invoice payload (including items and payments).
            val invoiceBody = buildInvoiceBody(items, posPayments)
            val jsonMediaType = "application/json".toMediaType()
            // Append ?ignore_mandatory=1 so that ERPNext accepts our custom payments field.
            val url = "${getBaseUrl()}/api/resource/Sales%20Invoice?ignore_mandatory=1"
            val request = Request.Builder()
                .url(url)
                .post(invoiceBody.toRequestBody(jsonMediaType))
                .addHeader("Cookie", sessionCookie)
                .addHeader("Content-Type", "application/json")
                .build()

            val response = executeRequest(request)

            if (response.isSuccessful) {
                response.use {
                    val responseBody = it.body?.string() ?: ""
                    try {
                        val invoice = parseInvoiceResponse(responseBody)
                        Pair(invoice, null)
                    } catch (e: Exception) {
                        Pair(null, Exception("Invalid response format: ${e.message}"))
                    }
                }
            } else {
                val errorBody = response.body?.string() ?: "Unknown error"
                Pair(null, Exception("Server error: ${response.code} - $errorBody"))
            }
        } catch (e: IOException) {
            Pair(null, Exception("Connection failed: ${e.message}"))
        } catch (e: Exception) {
            Pair(null, Exception("Unexpected error: ${e.message}"))
        }
    }

    private suspend fun fetchPOSProfilePayments(sessionCookie: String): JSONArray? {
        val url = "${getBaseUrl()}/api/resource/POS%20Profile/POS-Profile?fields=" +
                URLEncoder.encode("[\"payments\"]", "UTF-8")
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Cookie", sessionCookie)
            .build()
        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        if (!response.isSuccessful) {
            Log.d("POSRepository", "Failed to fetch POS Profile. Response code: ${response.code}")
            return null
        }
        val bodyStr = response.body?.string() ?: return null
        val json = JSONObject(bodyStr)
        val data = json.optJSONObject("data")
        val payments = data?.optJSONArray("payments")
        if (payments != null) {
            for (i in 0 until payments.length()) {
                val pm = payments.getJSONObject(i)
                Log.d("POSRepository", "Payment Method [$i]: ${pm.toString()}")
            }
        } else {
            Log.d("POSRepository", "No payments found in POS Profile.")
        }
        return payments
    }

    private fun buildInvoiceBody(items: List<CartItem>, posPayments: JSONArray?): String {
        val totalAmount = items.sumOf { it.price * it.quantity }
        val paymentsArray = JSONArray()

        if (posPayments != null && posPayments.length() > 0) {
            var defaultFound = false
            for (i in 0 until posPayments.length()) {
                val pm = posPayments.getJSONObject(i)
                val mode = pm.optString("mode_of_payment", "")
                val isDefault = pm.optBoolean("default", false)
                if (isDefault) {
                    defaultFound = true
                    paymentsArray.put(JSONObject().apply {
                        put("mode_of_payment", mode)
                        put("amount", totalAmount)
                    })
                } else {
                    paymentsArray.put(JSONObject().apply {
                        put("mode_of_payment", mode)
                        put("amount", 0)
                    })
                }
            }
            if (!defaultFound && posPayments.length() > 0) {
                val first = posPayments.getJSONObject(0)
                val mode = first.optString("mode_of_payment", "")
                paymentsArray.put(0, JSONObject().apply {
                    put("mode_of_payment", mode)
                    put("amount", totalAmount)
                })
            }
        } // Else: paymentsArray remains empty.

        return JSONObject().apply {
            put("customer", "POS-Customer")
            put("pos_profile", "POS-Profile")
            put("docstatus", 0)
            put("items", buildItemsArray(items))
            put("payments", paymentsArray)
        }.toString()
    }

    private fun buildItemsArray(items: List<CartItem>): JSONArray {
        return JSONArray().apply {
            items.forEach { item ->
                put(JSONObject().apply {
                    put("item_code", item.itemCode)
                    put("qty", item.quantity)
                    put("rate", item.price)
                })
            }
        }
    }

    private suspend fun executeRequest(request: Request): Response {
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute()
        }
    }

    private fun parseInvoiceResponse(json: String): ERPNextInvoice {
        val jsonObject = JSONObject(json)
        val data = jsonObject.getJSONObject("data")
        return gson.fromJson(data.toString(), ERPNextInvoice::class.java)
    }

    class TimeoutInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request().newBuilder().build()
            return try {
                chain.proceed(request)
            } catch (e: IOException) {
                throw IOException("Connection timeout. Please check your network", e)
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return connectivityManager.activeNetwork?.let { network ->
            connectivityManager.getNetworkCapabilities(network)?.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET
            ) == true
        } ?: false
    }
}
