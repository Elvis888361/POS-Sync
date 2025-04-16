package com.example.possync

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.lang.reflect.Method
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import android.print.PrintManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import java.io.OutputStream
import android.content.Intent
import androidx.core.content.FileProvider

class ReceiptActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_BLUETOOTH_CONNECT = 1
    }
    private lateinit var createDocumentLauncher: ActivityResultLauncher<String>

    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("SetTextI18n", "MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_receipt)

        // Header Views (company info)
        val companyNameTextView: TextView = findViewById(R.id.company_name)
        val companyAddressTextView: TextView = findViewById(R.id.company_address)

        // Invoice Header Views
        val invoiceNumberTextView: TextView = findViewById(R.id.invoice_number)
        val invoiceDateTextView: TextView = findViewById(R.id.invoice_date)
        val customerNameTextView: TextView = findViewById(R.id.customer_name)

        // RecyclerView for invoice items
        val itemsRecyclerView: RecyclerView = findViewById(R.id.items_recycler_view)

        // Footer Summary Views
        val totalTextView: TextView = findViewById(R.id.footer_total)
        val taxTextView: TextView = findViewById(R.id.footer_tax)
        val grandTotalTextView: TextView = findViewById(R.id.footer_grand_total)

        // Print Receipt Button (Android PrintManager)
        val printReceiptButton: Button = findViewById(R.id.print_receipt)
        // Second Button for Bluetooth printing
        val printReceipt2Button: Button = findViewById(R.id.print_receipt2)

        // Retrieve the ERPNextInvoice from the intent
        val invoice = intent.getSerializableExtra("INVOICE") as? ERPNextInvoice
        Log.e("ReceiptActivity", "$invoice")
        val back = findViewById<Button>(R.id.back)
        back.setOnClickListener {
            startActivity(Intent(this, Dashboard::class.java))
        }

        if (invoice != null) {
            companyNameTextView.text = if (invoice.company.isNotBlank()) invoice.company else "N/A"
            companyAddressTextView.text = if (invoice.companyaddress.isNotBlank()) invoice.companyaddress else "N/A"

            invoiceNumberTextView.text = " ${invoice.id}"
            invoiceDateTextView.text = " " + invoice.date.ifEmpty {
                SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date())
            }
            customerNameTextView.text = " ${invoice.customer}"

            itemsRecyclerView.layoutManager = LinearLayoutManager(this)
            if (invoice.items.isNotEmpty()) {
                itemsRecyclerView.adapter = InvoiceItemsAdapter(invoice.items)
            } else {
                Toast.makeText(this, "No items in this invoice", Toast.LENGTH_SHORT).show()
            }

            val calculatedTax = (invoice.grandTotal - invoice.total).let { if (it > 0) it else 0.0 }
            totalTextView.text = "${invoice.currency} ${String.format("%.2f", invoice.total)}"
            taxTextView.text = "${invoice.currency} ${String.format("%.2f", calculatedTax)}"
            grandTotalTextView.text = "${invoice.currency} ${String.format("%.2f", invoice.grandTotal)}"
        } else {
            Toast.makeText(this, "No invoice data available", Toast.LENGTH_SHORT).show()
            companyNameTextView.text = "N/A"
            companyAddressTextView.text = "N/A"
            invoiceNumberTextView.text = "Invoice #: N/A"
            invoiceDateTextView.text = "Date: N/A"
            customerNameTextView.text = "Customer: N/A"
            totalTextView.text = "Total: N/A"
            taxTextView.text = "Tax: N/A"
            grandTotalTextView.text = "Grand Total: N/A"
        }
        printReceiptButton.setOnClickListener {
            Toast.makeText(this, "Printing Receipt...", Toast.LENGTH_SHORT).show()
            val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
            // Get the root view of the receipt layout (the ScrollView with id receipt_root)
            val receiptView = findViewById<View>(R.id.receipt_root)
            if (receiptView != null) {
                val jobName = "${getString(R.string.app_name)} Receipt"
                printManager.print(jobName, ReceiptPrintAdapter(this, receiptView), null)
            } else {
                Toast.makeText(this, "Nothing to print", Toast.LENGTH_SHORT).show()
                AlertDialog.Builder(this@ReceiptActivity)
                    .setTitle("Error")
                    .setMessage("Nothing to print")
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
        }

        printReceipt2Button.setOnClickListener {
            printReceiptViaBluetooth()
        }
        createDocumentLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri: Uri? ->
            if (uri != null) {
                saveReceiptToUri(uri)
            } else {
                Toast.makeText(this, "Download cancelled", Toast.LENGTH_SHORT).show()
            }
        }

        // Assuming you have a button with id "download" in your layout.
        val downloadButton: View = findViewById(R.id.download)
        downloadButton.setOnClickListener {
            // Launch the file picker. The suggested filename is generated based on the current timestamp.
            createDocumentLauncher.launch("receipt_${System.currentTimeMillis()}.png")
        }
        val sendToWhatsAppButton: View = findViewById(R.id.send_to_whatsapp)
        sendToWhatsAppButton.setOnClickListener {
            sendReceiptToWhatsApp()
        }

    }
    private fun sendReceiptToWhatsApp() {
        // Convert your receipt view (with id "receipt_root") to a Bitmap.
        val receiptView = findViewById<View>(R.id.receipt_root)
        val bitmap = getBitmapFromView(receiptView)

        try {
            // Save the bitmap to a temporary file in the cache directory.
            val file = File(cacheDir, "receipt.png")
            val outputStream = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            outputStream.flush()
            outputStream.close()

            // Make the file readable by other apps.
            file.setReadable(true, false)

            // Get a content URI using FileProvider.
            // Replace 'your.package.name' with your actual package name.
            val uri: Uri = FileProvider.getUriForFile(this, "$packageName.provider", file)

            // Create the share intent.
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/*"  // Use a generic image MIME type
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                setPackage("com.whatsapp")
            }


            // Verify that WhatsApp is installed.
            try {
                packageManager.getPackageInfo("com.whatsapp", PackageManager.GET_ACTIVITIES)
                // WhatsApp is installed, proceed with sending.
                startActivity(sendIntent)
            } catch (e: PackageManager.NameNotFoundException) {
                Toast.makeText(this, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error sending to WhatsApp: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    // Saves the receipt view as a PNG image to the provided URI.
    private fun saveReceiptToUri(uri: Uri) {
        // Run file writing in a background thread.
        Thread {
            try {
                // Convert the receipt view (assumed to have id "receipt_root") to a bitmap.
                val receiptView = findViewById<View>(R.id.receipt_root)
                val bitmap = getBitmapFromView(receiptView)

                // Open the output stream using the content resolver.
                val outputStream: OutputStream? = contentResolver.openOutputStream(uri)
                if (outputStream != null) {
                    // Write the bitmap as a PNG.
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, outputStream)
                    outputStream.flush()
                    outputStream.close()

                    runOnUiThread {
                        Toast.makeText(this, "Receipt downloaded successfully", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    runOnUiThread {
                        Toast.makeText(this, "Unable to open output stream", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Error saving file: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // Utility function to convert a view to a Bitmap.

    private fun printReceiptViaBluetooth() {
        // Check for BLUETOOTH_CONNECT permission
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Bluetooth connect permission not granted", Toast.LENGTH_SHORT)
                .show()
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                REQUEST_BLUETOOTH_CONNECT
            )
            return
        }

        // Get the Bluetooth adapter
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not supported on this device", Toast.LENGTH_SHORT)
                .show()
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        // Retrieve paired devices and select a printer device.
        val pairedDevices: Set<BluetoothDevice> = bluetoothAdapter.bondedDevices
        if (pairedDevices.isEmpty()) {
            Toast.makeText(this, "No paired Bluetooth devices found", Toast.LENGTH_SHORT).show()
            return
        }
        // Adjust this filter as needed (here we pick a device with "Printer" in its name)
        val printerDevice: BluetoothDevice =
            pairedDevices.firstOrNull { it.name.contains("Printer", true) } ?: pairedDevices.first()

        // Run the connection and printing in a background thread.
        Thread {
            try {
                val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                var socket: BluetoothSocket? = null

                // Try the standard socket connection.
                try {
                    socket = printerDevice.createRfcommSocketToServiceRecord(uuid)
                    socket.connect()
                } catch (e: Exception) {
                    Log.e("Bluetooth", "Standard connection failed, trying fallback", e)
                    // Fallback using reflection to create the socket on channel 1.
                    val method: Method = printerDevice.javaClass.getMethod(
                        "createRfcommSocket",
                        Int::class.javaPrimitiveType
                    )
                    socket = method.invoke(printerDevice, 1) as BluetoothSocket
                    socket.connect()
                }

                val outputStream = socket?.outputStream

                // Convert the receipt view (with id "receipt_root") into a Bitmap.
                val receiptView = findViewById<View>(R.id.receipt_root)
                val bitmap = getBitmapFromView(receiptView)

                // Convert the Bitmap into ESC/POS commands.
                val escPosData = convertBitmapToEscPos(bitmap)
                outputStream?.write(escPosData)
                outputStream?.flush()

                socket?.close()

                runOnUiThread {
                    Toast.makeText(this, "Printed Receipt via Bluetooth", Toast.LENGTH_SHORT)
                        .show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Error printing: ${e.message}", Toast.LENGTH_SHORT)
                        .show()
                }
            }
        }.start()
    }

    // Utility function to convert a view into a Bitmap.
    private fun getBitmapFromView(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }

    // Converts a Bitmap to ESC/POS command data (monochrome image printing).
    private fun convertBitmapToEscPos(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        // Each line must be a multiple of 8 pixels wide.
        val bytesPerLine = (width + 7) / 8
        val baos = ByteArrayOutputStream()
        // Initialize the printer (ESC @).
        baos.write(byteArrayOf(0x1B, 0x40))
        // Set line spacing to 24 dots (optional, adjust as needed).
        baos.write(byteArrayOf(0x1B, 0x33, 24))

        // Loop through each row.
        for (y in 0 until height) {
            // ESC * m nL nH: Set bit image mode (mode 33 for 24-dot double density).
            baos.write(byteArrayOf(0x1B, 0x2A, 33, (bytesPerLine and 0xFF).toByte(), ((bytesPerLine shr 8) and 0xFF).toByte()))
            // Process each byte for this row.
            for (x in 0 until bytesPerLine) {
                var b: Int = 0
                for (bit in 0 until 8) {
                    val pixelX = x * 8 + bit
                    if (pixelX < width) {
                        val pixel = bitmap.getPixel(pixelX, y)
                        // Convert pixel to grayscale.
                        val red = (pixel shr 16) and 0xFF
                        val green = (pixel shr 8) and 0xFF
                        val blue = pixel and 0xFF
                        val luminance = (red * 0.3 + green * 0.59 + blue * 0.11).toInt()
                        // Threshold to determine black (if dark) or white.
                        if (luminance < 128) {
                            b = b or (1 shl (7 - bit))
                        }
                    }
                }
                baos.write(b)
            }
            // End the line.
            baos.write(0x0A)
        }
        return baos.toByteArray()
    }
}
