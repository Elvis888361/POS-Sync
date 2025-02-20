package com.example.possync

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class MainActivity : AppCompatActivity() {
    private val client = getUnsafeOkHttpClient()
    private val sharedPreferencesKey = "ERPNextPreferences"
    private val erpnextUrlKey = "ERPNextUrl"
    private val sessionCookieKey = "sessionCookie"

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val usernameField = findViewById<EditText>(R.id.username)
        val passwordField = findViewById<EditText>(R.id.password)
        val erpnextUrlField = findViewById<EditText>(R.id.erpnext_url)
        val loginButton = findViewById<Button>(R.id.login_button)

        val sharedPreferences = getSharedPreferences(sharedPreferencesKey, Context.MODE_PRIVATE)
        val storedUrl = sharedPreferences.getString(erpnextUrlKey, "")
        if (!storedUrl.isNullOrEmpty()) {
            erpnextUrlField.setText(storedUrl)
        }

        loginButton.setOnClickListener {
            val username = usernameField.text.toString().trim()
            val password = passwordField.text.toString().trim()
            val erpnextUrl = erpnextUrlField.text.toString().trim()

            if (username.isEmpty() || password.isEmpty() || erpnextUrl.isEmpty()) {
                Toast.makeText(this, "Please enter username, password, and ERPNext URL", Toast.LENGTH_SHORT).show()
            } else {
                sharedPreferences.edit().putString(erpnextUrlKey, erpnextUrl).apply()
                authenticateUser(username, password, erpnextUrl)
            }
        }
    }

    private fun authenticateUser(username: String, password: String, erpnextUrl: String) {
        val url = "https://$erpnextUrl/api/method/login"
        val requestBody = FormBody.Builder()
            .add("usr", username)
            .add("pwd", password)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Network Error")
                        .setMessage(
                            "There was a problem connecting to the server. " +
                                    "Please check your internet connection and try again."
                        )
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string() ?: ""
                runOnUiThread {
                    try {
                        if (response.isSuccessful) {
                            val json = JSONObject(responseBody)
                            val message = json.optString("message", "")

                            if (message.contains("Logged in", ignoreCase = true)) {
                                val cookies = response.headers("Set-Cookie")
                                val sessionCookie = cookies.find { it.startsWith("sid=") }

                                if (sessionCookie != null) {
                                    val sharedPreferences = getSharedPreferences(
                                        sharedPreferencesKey,
                                        Context.MODE_PRIVATE
                                    )
                                    sharedPreferences.edit()
                                        .putString(sessionCookieKey, sessionCookie)
                                        .putString("username", username) // Store the username
                                        .apply()
                                }

                                val intent = Intent(this@MainActivity, Dashboard::class.java)
                                startActivity(intent)
                                finish()
                            } else {
                                AlertDialog.Builder(this@MainActivity)
                                    .setTitle("Login Failed")
                                    .setMessage("Login failed: $message")
                                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                    .show()
                            }
                        } else {
                            AlertDialog.Builder(this@MainActivity)
                                .setTitle("Login Failed")
                                .setMessage("Login failed: ${response.code} - $responseBody")
                                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                                .show()
                        }
                    } catch (e: Exception) {
                        // Display a generic error message without exception details.
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Error")
                            .setMessage("An error occurred while processing your login. Please try again.")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                }
            }
        })


    }

    private fun getUnsafeOkHttpClient(): OkHttpClient {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                @SuppressLint("TrustAllX509TrustManager")
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

                @SuppressLint("TrustAllX509TrustManager")
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAllCerts, SecureRandom())
            val sslSocketFactory = sslContext.socketFactory
            return OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        } catch (e: Exception) {
            AlertDialog.Builder(this@MainActivity)
                .setTitle("Error")
                .setMessage("Failed to create unsafe OkHttpClient")
                .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                .show()
            throw RuntimeException(e)
        }
    }
}
