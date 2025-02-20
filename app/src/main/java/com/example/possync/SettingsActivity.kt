package com.example.possync

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class SettingsActivity : AppCompatActivity() {

    // Use the same SharedPreferences file (or another one) to store persistent settings.
    private val prefsFile = "session_cookie"
    private val sharedPreferencesKey = "ERPNextPreferences"
    private val sessionCookieKey = "sessionCookie"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)

        // Apply system window insets for edge-to-edge display.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // Initialize views.
        val switchErpnextIntegration = findViewById<Switch>(R.id.switchErpnextIntegration)
        val erpnextDetailsLayout = findViewById<LinearLayout>(R.id.erpnextDetailsLayout)
        val switchEnablePrinter = findViewById<Switch>(R.id.switchEnablePrinter)
        val etPrinterIp = findViewById<EditText>(R.id.etPrinterIp)
        val rgInvoiceType = findViewById<RadioGroup>(R.id.rgInvoiceType)
        val switchAutoSync = findViewById<Switch>(R.id.switchAutoSync)
        val switchDarkMode = findViewById<Switch>(R.id.switchDarkMode)
        val spinnerLanguage = findViewById<Spinner>(R.id.spinnerLanguage)
        val etErpnextUrl = findViewById<EditText>(R.id.etErpnextUrl)

        // Load previously saved settings (if any) to pre-populate the form.
        val prefs = getSharedPreferences(prefsFile, Context.MODE_PRIVATE)
        loadSettings(prefs, rgInvoiceType, switchErpnextIntegration, erpnextDetailsLayout,
            etErpnextUrl, switchAutoSync, switchEnablePrinter,
            etPrinterIp, switchDarkMode, spinnerLanguage)

        // Toggle ERPNext details layout based on switch state.
        switchErpnextIntegration.setOnCheckedChangeListener { _, isChecked ->
            erpnextDetailsLayout.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Toggle Printer IP field based on switch state.
        switchEnablePrinter.setOnCheckedChangeListener { _, isChecked ->
            etPrinterIp.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        // Save button handler: persist all settings in SharedPreferences.
        findViewById<Button>(R.id.btnSaveSettings).setOnClickListener {
            // Retrieve Invoice Type
            val selectedInvoiceId = rgInvoiceType.checkedRadioButtonId
            val selectedInvoiceButton = findViewById<RadioButton>(selectedInvoiceId)
            val invoiceType = when (selectedInvoiceButton.id) {
                R.id.rbSalesInvoice -> "sales_invoice"
                R.id.rbPosInvoice -> "pos_invoice"
                else -> ""
            }

            // ERPNext settings
            val isErpnextEnabled = switchErpnextIntegration.isChecked
            val erpnextUrl = etErpnextUrl.text.toString().trim()
//            val erpnextApiKey = etErpnextApiKey.text.toString().trim()

            // Auto-Sync setting
            val isAutoSyncEnabled = switchAutoSync.isChecked

            // Printer settings
            val isPrinterEnabled = switchEnablePrinter.isChecked
            val printerIp = etPrinterIp.text.toString().trim()

            // App Preferences: Dark mode and Language
            val isDarkModeEnabled = switchDarkMode.isChecked
            val language = spinnerLanguage.selectedItem.toString()

            // Save settings into SharedPreferences.
            with(prefs.edit()) {
                putString("invoice_type", invoiceType)
                putBoolean("erpnext_enabled", isErpnextEnabled)
                if (isErpnextEnabled) {
                    putString("erpnext_url", erpnextUrl)
                }
                putBoolean("auto_sync", isAutoSyncEnabled)
                putBoolean("printer_enabled", isPrinterEnabled)
                if (isPrinterEnabled) {
                    putString("printer_ip", printerIp)
                }
                putBoolean("dark_mode", isDarkModeEnabled)
                putString("language", language)
                apply() // Save asynchronously; they persist until explicitly changed.
            }

            Toast.makeText(this, "Settings saved successfully!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadSettings(
        prefs: android.content.SharedPreferences,
        rgInvoiceType: RadioGroup,
        switchErpnextIntegration: Switch,
        erpnextDetailsLayout: LinearLayout,
        etErpnextUrl: EditText,
        switchAutoSync: Switch,
        switchEnablePrinter: Switch,
        etPrinterIp: EditText,
        switchDarkMode: Switch,
        spinnerLanguage: Spinner
    ) {
        val sharedPreferences = getSharedPreferences(sharedPreferencesKey, Context.MODE_PRIVATE)
        val sessionCookie = sharedPreferences.getString(sessionCookieKey, null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null)
        // Invoice Type
        val invoiceType = prefs.getString("invoice_type", "sales_invoice")
        if (invoiceType == "sales_invoice") {
            rgInvoiceType.check(R.id.rbSalesInvoice)
        } else {
            rgInvoiceType.check(R.id.rbPosInvoice)
        }

        // ERPNext settings
        val isErpnextEnabled = prefs.getBoolean("erpnext_enabled", false)
        switchErpnextIntegration.isChecked = isErpnextEnabled
        erpnextDetailsLayout.visibility = if (isErpnextEnabled) View.VISIBLE else View.GONE
        etErpnextUrl.setText(prefs.getString("erpnext_url", erpnextUrl))

        // Auto-Sync setting
        switchAutoSync.isChecked = prefs.getBoolean("auto_sync", false)

        // Printer settings
        val isPrinterEnabled = prefs.getBoolean("printer_enabled", false)
        switchEnablePrinter.isChecked = isPrinterEnabled
        etPrinterIp.visibility = if (isPrinterEnabled) View.VISIBLE else View.GONE
        etPrinterIp.setText(prefs.getString("printer_ip", ""))

        // App Preferences: Dark mode and Language
        switchDarkMode.isChecked = prefs.getBoolean("dark_mode", false)
        // For spinnerLanguage, you might need to set the selection manually based on the saved language.
        val savedLanguage = prefs.getString("language", "English")
        val languageOptions = resources.getStringArray(R.array.language_options)
        val languageIndex = languageOptions.indexOf(savedLanguage)
        if (languageIndex >= 0) {
            spinnerLanguage.setSelection(languageIndex)
        }
    }
}
