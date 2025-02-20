package com.example.possync

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.SharedPreferences

object SessionManager {
    private lateinit var sharedPreferences: SharedPreferences

    fun initialize(context: Context) {
        sharedPreferences = context.getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
    }

    fun getCurrentUser(): String? {
        return sharedPreferences.getString("username", null)
    }

    fun getDomain(): String? {
        return sharedPreferences.getString("ERPNextUrl", null)
    }

    fun getSessionCookie(): String? {
        return sharedPreferences.getString("sessionCookie", null)
    }
}