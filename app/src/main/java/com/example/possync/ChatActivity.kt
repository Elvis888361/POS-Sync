package com.example.possync

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.*

class ChatActivity : AppCompatActivity() {

    private lateinit var editTextMessage: EditText
    private lateinit var buttonSend: Button
    private lateinit var recyclerViewChat: RecyclerView
    private lateinit var chatAdapter: ChatAdapter

    // Replace with your OpenAI API key (for demo purposes only)
    private val openAiApiKey = ""

    companion object {
        private const val TAG = "ChatActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        editTextMessage = findViewById(R.id.editTextMessage)
        buttonSend = findViewById(R.id.buttonSend)
        recyclerViewChat = findViewById(R.id.recyclerViewChat)
        chatAdapter = ChatAdapter(mutableListOf())
        recyclerViewChat.layoutManager = LinearLayoutManager(this)
        recyclerViewChat.adapter = chatAdapter

        buttonSend.setOnClickListener {
            val message = editTextMessage.text.toString().trim()
            if (message.isNotEmpty()) {
                addChatMessage("You: $message", isUser = true)
                processUserMessage(message)
                editTextMessage.text.clear()
            }
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

    /**
     * Adds a chat message to the RecyclerView and scrolls to the bottom.
     */
    private fun addChatMessage(message: String, isUser: Boolean) {
        runOnUiThread {
            chatAdapter.addMessage(ChatMessage(message, isUser))
            recyclerViewChat.smoothScrollToPosition(chatAdapter.itemCount - 1)
        }
    }

    /**
     * Process the user's message.
     * If it looks like an ERPNext query (based on keywords), we generate a full query spec.
     * Otherwise, we simply chat normally.
     */
    private fun processUserMessage(query: String) {
        if (isERPNextQuery(query)) {
            generateERPNextQuery(query) { querySpec ->
                if (querySpec == null) {
                    chatWithLLM(query) { answer ->
                        addChatMessage("Bot: $answer", isUser = false)
                    }
                } else {
                    queryERPNextWithAdvancedParameters(querySpec)
                }
            }
        } else {
            chatWithLLM(query) { answer ->
                addChatMessage("Bot: $answer", isUser = false)
            }
        }
    }

    /**
     * A simple keyword check to decide if the query is about ERPNext data.
     */
    private fun isERPNextQuery(query: String): Boolean {
        val keywords = listOf("customer", "invoice", "sales", "item", "supplier", "purchase", "order", "stock")
        return keywords.any { query.contains(it, ignoreCase = true) }
    }

    /**
     * Uses OpenAI Chat API to generate a JSON specification for an ERPNext query.
     */
    private fun generateERPNextQuery(query: String, callback: (JSONObject?) -> Unit) {
        val prompt = """
            You are an assistant that generates ERPNext REST API query specifications.
            Given the user query: "$query", generate a JSON object with the following keys:
            - "query_type": can be "list" or "aggregate".
            - "doctype": the ERPNext doctype that best answers the query.
            - "filters": a JSON array representing filters to apply (in ERPNext filter format).
            - "fields": a JSON array of field names to retrieve.
            - "analysis": if "query_type" is "aggregate", then this can be "COUNT", "SUM", or "AVG". Otherwise, return an empty string.
            - "aggregation_field": if analysis is "SUM" or "AVG", specify the field to aggregate; otherwise, empty string.
            
            Output only the JSON object.
        """.trimIndent()

        val url = "https://api.openai.com/v1/chat/completions"
        val jsonBody = JSONObject().apply {
            put("model", "gpt-3.5-turbo")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are a helpful ERPNext assistant.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = jsonBody.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $openAiApiKey")
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    addChatMessage("Bot: Error generating ERPNext query - ${e.message}", isUser = false)
                }
                callback(null)
            }
            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                try {
                    val jsonResponse = JSONObject(responseBody)
                    val choices = jsonResponse.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val message = choices.getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                        // Attempt to parse the LLM output as a JSON object.
                        val querySpec = JSONObject(message)
                        callback(querySpec)
                    } else {
                        callback(null)
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        addChatMessage("Bot: Error parsing ERPNext query generation - ${e.message}", isUser = false)
                    }
                    callback(null)
                }
            }
        })
    }

    /**
     * Queries ERPNext using the generated query specification.
     *
     * Supports two query types:
     * - "list": simply retrieve the records (with given filters and fields).
     * - "aggregate": retrieve the records and perform client-side aggregation (COUNT, SUM, or AVG).
     */
    private fun queryERPNextWithAdvancedParameters(querySpec: JSONObject) {
        val doctype = querySpec.optString("doctype")
        val filters = querySpec.optJSONArray("filters")?.toString() ?: "[]"
        val fields = querySpec.optJSONArray("fields")?.toString() ?: "[]"
        val queryType = querySpec.optString("query_type").uppercase()
        val analysis = querySpec.optString("analysis").uppercase()
        val aggregationField = querySpec.optString("aggregation_field")

        val sharedPreferences = getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
        if (erpnextUrl.isNullOrEmpty() || sessionCookie.isNullOrEmpty() || doctype.isEmpty()) {
            runOnUiThread {
                Toast.makeText(this, "ERPNext URL, session cookie, or doctype not set", Toast.LENGTH_SHORT).show()
            }
            return
        }

        // URL-encode filters, fields, and doctype
        val encodedFilters = URLEncoder.encode(filters, "UTF-8")
        val encodedFields = URLEncoder.encode(fields, "UTF-8")
        val encodedDoctype = URLEncoder.encode(doctype, "UTF-8")

        // Only include "fields" parameter if not empty
        val urlBuilder = StringBuilder("https://$erpnextUrl/api/resource/$encodedDoctype?filters=$encodedFilters")
        if (fields != "[]" && fields.isNotEmpty()) {
            urlBuilder.append("&fields=$encodedFields")
        }
        urlBuilder.append("&limit_page_length=100")
        val url = urlBuilder.toString()

        Log.d(TAG, "Querying ERPNext with URL: $url")
        val request = Request.Builder()
            .url(url)
            .get()
            .removeHeader("Expect")
            .addHeader("Cookie", sessionCookie)
            .build()

        getUnsafeOkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { addChatMessage("Bot: Error querying ERPNext - ${e.message}", isUser = false) }
            }
            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                try {
                    val jsonObj = JSONObject(responseBody)
                    when {
                        jsonObj.has("data") -> {
                            val dataArray = jsonObj.getJSONArray("data")
                            if (queryType == "LIST") {
                                val result = StringBuilder("ERPNext Records:\n")
                                for (i in 0 until dataArray.length()) {
                                    val record = dataArray.getJSONObject(i)
                                    result.append(record.toString()).append("\n")
                                }
                                runOnUiThread { addChatMessage("Bot: $result", isUser = false) }
                            } else if (queryType == "AGGREGATE") {
                                when (analysis) {
                                    "COUNT" -> {
                                        val count = dataArray.length()
                                        runOnUiThread { addChatMessage("Bot: Total count for $doctype: $count", isUser = false) }
                                    }
                                    "SUM" -> {
                                        var sum = 0.0
                                        for (i in 0 until dataArray.length()) {
                                            val record = dataArray.getJSONObject(i)
                                            sum += record.optDouble(aggregationField, 0.0)
                                        }
                                        runOnUiThread { addChatMessage("Bot: Sum of $aggregationField for $doctype: $sum", isUser = false) }
                                    }
                                    "AVG" -> {
                                        var sum = 0.0
                                        for (i in 0 until dataArray.length()) {
                                            val record = dataArray.getJSONObject(i)
                                            sum += record.optDouble(aggregationField, 0.0)
                                        }
                                        val avg = if (dataArray.length() > 0) sum / dataArray.length() else 0.0
                                        runOnUiThread { addChatMessage("Bot: Average of $aggregationField for $doctype: $avg", isUser = false) }
                                    }
                                    else -> {
                                        runOnUiThread { addChatMessage("Bot: Unsupported aggregation type: $analysis", isUser = false) }
                                    }
                                }
                            } else {
                                runOnUiThread { addChatMessage("Bot: Unsupported query type: $queryType", isUser = false) }
                            }
                        }
                        jsonObj.has("exc") -> {
                            runOnUiThread { addChatMessage("Bot: ERPNext Error: ${jsonObj.getString("exc")}", isUser = false) }
                        }
                        jsonObj.has("message") -> {
                            runOnUiThread { addChatMessage("Bot: ERPNext Message: ${jsonObj.getString("message")}", isUser = false) }
                        }
                        else -> {
                            runOnUiThread { addChatMessage("Bot: Unexpected ERPNext response: $responseBody", isUser = false) }
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { addChatMessage("Bot: Error parsing ERPNext response - ${e.message}", isUser = false) }
                }
            }
        })
    }


    /**
     * Sends a normal conversation query to the OpenAI Chat API.
     */
    private fun chatWithLLM(query: String, callback: (String) -> Unit) {
        val prompt = "User: $query\n\nAnswer in detail:"
        val url = "https://api.openai.com/v1/chat/completions"
        val jsonBody = JSONObject().apply {
            put("model", "gpt-3.5-turbo")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", "You are a helpful assistant.")
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = jsonBody.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Authorization", "Bearer $openAiApiKey")
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    addChatMessage("Bot: Error contacting LLM - ${e.message}", isUser = false)
                }
                callback("Sorry, I encountered an error.")
            }
            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                try {
                    val jsonResponse = JSONObject(responseBody)
                    val choices = jsonResponse.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val answer = choices.getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                        callback(answer.trim())
                    } else {
                        callback("I'm sorry, I couldn't generate a response.")
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        addChatMessage("Bot: Error parsing LLM response - ${e.message}", isUser = false)
                    }
                    callback("Sorry, there was a parsing error.")
                }
            }
        })
    }

    /**
     * Returns an OkHttpClient that trusts all SSL certificates.
     * (For development only; do not use in production.)
     */
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
