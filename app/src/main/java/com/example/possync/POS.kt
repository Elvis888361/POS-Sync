package com.example.possync

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.badge.BadgeDrawable
import com.google.android.material.badge.BadgeUtils
import com.google.android.material.badge.ExperimentalBadgeUtils
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.net.URLEncoder
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class POS : AppCompatActivity(), ItemAdapter.ItemClickListener {

    // UI Components
    private lateinit var adapter: ItemAdapter
    private lateinit var loadingProgress: ProgressBar
    private lateinit var itemsRecyclerView: RecyclerView
    private lateinit var listIcon: ImageView
    private lateinit var gridIcon: ImageView
    private val gridLayoutManager = GridLayoutManager(this, 3)
    private val listLayoutManager = LinearLayoutManager(this)
    private lateinit var fabCart: FloatingActionButton
    private lateinit var searchBar: TextInputEditText
    private val allItems = mutableListOf<ERPNextItem>() // Stores all items
    private val filteredItems = mutableListOf<ERPNextItem>() // Stores filtered items
    // Networking
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var client: OkHttpClient

    // Caching
    private val itemCache = mutableMapOf<String, ERPNextItem>()
    private var lastCacheTime: Long = 0
    private val CACHE_VALIDITY = 15 * 60 * 1000 // 15 minutes
    private lateinit var viewModel: POSViewModel
    // Scanner
    private lateinit var scannerLauncher: ActivityResultLauncher<Intent>

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pos)

        // Initialize UI
        viewModel = ViewModelProvider(this).get(POSViewModel::class.java)
        loadingProgress = findViewById(R.id.loadingProgress)
        itemsRecyclerView = findViewById(R.id.items_recycler)
        fabCart = findViewById(R.id.fabCart)
        searchBar = findViewById(R.id.search_bar)
        itemsRecyclerView = findViewById(R.id.items_recycler)
        listIcon = findViewById(R.id.listIcon)
        gridIcon = findViewById(R.id.gridIcon)

        // Setup RecyclerView
        adapter = ItemAdapter(this)
        itemsRecyclerView.layoutManager = GridLayoutManager(this, 3)
        itemsRecyclerView.adapter = adapter


        // Initialize SharedPreferences and HTTP Client
        sharedPreferences = getSharedPreferences("ERPNextPreferences", MODE_PRIVATE)
        client = createClient()

        // Setup Scanner
        setupScannerLauncher()

        listIcon.setOnClickListener {
            switchToListView()
        }

        gridIcon.setOnClickListener {
            switchToGridView()
        }
        // Load Items
        if (shouldLoadFromNetwork()) {
            loadItems()
        } else {
            showCachedItems()
        }
        viewModel.cartItems.observe(this) { items ->
            updateCartBadge(items.size)
        }
        // Set up FAB click listener
        fabCart.setOnClickListener {
            startActivity(Intent(this, CartActivity::class.java))
        }
        setupSearchBar()

    }
    private fun switchToListView() {
        itemsRecyclerView.layoutManager = listLayoutManager
        adapter.setItemLayout(R.layout.item_product_list) // Use list layout
        listIcon.visibility = View.GONE
        gridIcon.visibility = View.VISIBLE
    }

    // Switch to grid view
    private fun switchToGridView() {
        itemsRecyclerView.layoutManager = gridLayoutManager
        adapter.setItemLayout(R.layout.item_product_card) // Use grid layout
        listIcon.visibility = View.VISIBLE
        gridIcon.visibility = View.GONE
    }
    // Set up search bar
    private fun setupSearchBar() {
        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterItems(s.toString())
            }

            override fun afterTextChanged(s: Editable?) {}
        })
    }
    // Filter items by name or item code
    private fun filterItems(query: String) {
        filteredItems.clear()
        if (query.isEmpty()) {
            filteredItems.addAll(allItems)
        } else {
            val lowerCaseQuery = query.lowercase()
            for (item in allItems) {
                if (item.name.lowercase().contains(lowerCaseQuery) || item.itemCode.lowercase().contains(lowerCaseQuery)) {
                    filteredItems.add(item)
                }
            }
        }
        adapter.submitList(filteredItems.toList())
    }
    @androidx.annotation.OptIn(ExperimentalBadgeUtils::class)
    @OptIn(ExperimentalBadgeUtils::class)
    private fun updateCartBadge(count: Int) {
        val fab = findViewById<FloatingActionButton>(R.id.fabCart)

        // Create or update the badge
        val badge = BadgeDrawable.create(this).apply {
            number = count
            backgroundColor = ContextCompat.getColor(this@POS, R.color.red)
            badgeTextColor = ContextCompat.getColor(this@POS, android.R.color.white)
        }

        // Attach the badge to the FAB
        BadgeUtils.attachBadgeDrawable(badge, fab)
    }

    private fun parseStockResponse(response: Response): Map<String, Double> {
        return try {
            val json = JSONObject(response.body?.string() ?: return emptyMap())
            val data = json.optJSONArray("data") ?: return emptyMap()

            mutableMapOf<String, Double>().apply {
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    put(item.getString("item_code"), item.getDouble("actual_qty"))
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }
    private fun updateItemList(items: List<ERPNextItem>) {
        CoroutineScope(Dispatchers.Main).launch {
            allItems.clear()
            allItems.addAll(items)
            filterItems("") // Show all items initially
            itemCache.clear()
            items.associateByTo(itemCache) { it.itemCode }
            lastCacheTime = System.currentTimeMillis()
            showLoading(false)
        }
    }
    private fun setupRecyclerView() {
        val itemsRecyclerView = findViewById<RecyclerView>(R.id.items_recycler)
        adapter = ItemAdapter(this)
        itemsRecyclerView.layoutManager = GridLayoutManager(this, 3)
        itemsRecyclerView.adapter = adapter
    }

    private fun createClient(): OkHttpClient {
        val trustAllCertificates = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        })

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCertificates, java.security.SecureRandom())
        }

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCertificates[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private fun loadItems() {
        showLoading(true)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Fetch POS Profile
                val posProfile = fetchPOSProfile()
                if (posProfile == null) {
                    withContext(Dispatchers.Main) {
                        showLoading(false)
                        Toast.makeText(this@POS, "Failed to fetch POS Profile", Toast.LENGTH_LONG).show()
                        AlertDialog.Builder(this@POS)
                            .setTitle("Error")
                            .setMessage("Failed to fetch POS Profile")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                    return@launch
                }

                // Get price list
                val priceList = posProfile.optString("selling_price_list")
                if (priceList.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        showLoading(false)
                        Toast.makeText(this@POS, "No Price List found", Toast.LENGTH_LONG).show()
                        AlertDialog.Builder(this@POS)
                            .setTitle("Error")
                            .setMessage("No Price List found")
                            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                    return@launch
                }

                // Fetch all items
                val items = fetchAllItems()
                val itemCodes = items.map { it.itemCode }

                // Fetch prices and stock in parallel
                val pricesDeferred = async { fetchBatchPrices(itemCodes, priceList) }
                val stockDeferred = async { fetchBatchStock(itemCodes) }
                val prices = pricesDeferred.await()
                val stock = stockDeferred.await()

                // Combine data
                val combinedItems = items.map { item ->
                    ERPNextItem(
                        itemCode = item.itemCode,
                        name = item.name,
                        uom = item.uom,
                        price = prices[item.itemCode] ?: 0.0,
                        stockQty = stock[item.itemCode] ?: 0.0,
                        barcode = item.barcode
                    )
                }

                // Update UI
                updateItemList(combinedItems)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showLoading(false)
                    Toast.makeText(this@POS, "Error loading items: ${e.message}", Toast.LENGTH_LONG).show()
                    AlertDialog.Builder(this@POS)
                        .setTitle("Error")
                        .setMessage("Error loading items: ${e.message}")
                        .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                        .show()
                }
            }
        }
    }
    private fun shouldLoadFromNetwork(): Boolean {
        return System.currentTimeMillis() - lastCacheTime > CACHE_VALIDITY || itemCache.isEmpty()
    }

    // Show cached items
    private fun showCachedItems() {
        allItems.clear()
        allItems.addAll(itemCache.values)
        filterItems("") // Show all items initially
        loadingProgress.visibility = View.GONE
    }

    private suspend fun fetchPOSProfile(): JSONObject? {
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null) ?: return null

        val url = "https://$erpnextUrl/api/resource/POS%20Profile?fields=${URLEncoder.encode("[\"name\", \"selling_price_list\"]", "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Cookie", sessionCookie ?: "")
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val json = JSONObject(response.body?.string() ?: return null)
            json.optJSONArray("data")?.getJSONObject(0)
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchAllItems(): List<ERPNextItem> {
        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null) ?: return emptyList()

        val url = "https://$erpnextUrl/api/resource/Item?fields=${URLEncoder.encode("[\"item_code\", \"item_name\", \"stock_uom\"]", "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Cookie", sessionCookie ?: "")
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return emptyList()

            val json = JSONObject(response.body?.string() ?: return emptyList())
            val data = json.optJSONArray("data") ?: return emptyList()

            List(data.length()) { i ->
                val item = data.getJSONObject(i)
                ERPNextItem(
                    itemCode = item.optString("item_code"),
                    name = item.optString("item_name"),
                    uom = item.optString("stock_uom"),
                    price = 0.0,
                    stockQty = 0.0,
                    barcode = item.optString("item_code")
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
    private suspend fun fetchBatchPrices(itemCodes: List<String>, priceList: String): Map<String, Double> {
        if (itemCodes.isEmpty()) return emptyMap()

        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null) ?: return emptyMap()

        val codesString = itemCodes.joinToString("\",\"", "[\"", "\"]")
        val url = "https://$erpnextUrl/api/resource/Item%20Price?filters=" +
                URLEncoder.encode("[[\"item_code\", \"in\", $codesString], [\"price_list\", \"=\", \"$priceList\"]]", "UTF-8") +
                "&fields=[\"item_code\",\"price_list_rate\"]"

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Cookie", sessionCookie ?: "")
            .build()

        return try {
            val response = client.newCall(request).execute()
            parsePriceResponse(response)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // Parse price response
    private fun parsePriceResponse(response: Response): Map<String, Double> {
        return try {
            val json = JSONObject(response.body?.string() ?: return emptyMap())
            val data = json.optJSONArray("data") ?: return emptyMap()

            mutableMapOf<String, Double>().apply {
                for (i in 0 until data.length()) {
                    val item = data.getJSONObject(i)
                    put(item.getString("item_code"), item.getDouble("price_list_rate"))
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }
    private suspend fun fetchBatchStock(itemCodes: List<String>): Map<String, Double> {
        if (itemCodes.isEmpty()) return emptyMap()

        val sessionCookie = sharedPreferences.getString("sessionCookie", null)
        val erpnextUrl = sharedPreferences.getString("ERPNextUrl", null) ?: return emptyMap()

        val codesString = itemCodes.joinToString("\",\"", "[\"", "\"]")
        val url = "https://$erpnextUrl/api/resource/Bin?filters=" +
                URLEncoder.encode("[[\"item_code\", \"in\", $codesString]]", "UTF-8") +
                "&fields=[\"item_code\",\"actual_qty\"]"

        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Cookie", sessionCookie ?: "")
            .build()

        return try {
            val response = client.newCall(request).execute()
            parseStockResponse(response)
        } catch (e: Exception) {
            emptyMap()
        }
    }
    private fun showLoading(show: Boolean) {
        loadingProgress.visibility = if (show) View.VISIBLE else View.GONE
        itemsRecyclerView.visibility = if (show) View.INVISIBLE else View.VISIBLE
    }


    override fun onItemClick(item: ERPNextItem) {
        viewModel.addToCart(item)
    }

    private fun setupScannerLauncher() {
        scannerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val barcode = result.data?.getStringExtra("SCAN_RESULT")
                barcode?.let { /* Handle barcode */ }
            }
        }
    }


    fun onScanBarcodeClick(view: View) {
        scannerLauncher.launch(Intent("com.google.zxing.client.android.SCAN"))
    }

    private fun observeCart() {
        viewModel.cartItems.observe(this) { items ->
            // Update cart badge
            updateCartBadge(items.size)
        }
    }

//    private fun updateCartSummary(items: List<CartItem>) {
//        val total = items.sumOf { it.price * it.quantity }
//        findViewById<TextView>(R.id.cart_total).text = "Total: $${"%.2f".format(total)}"
//    }

    fun proceedToCheckout(view: View) {
        startActivity(Intent(this, Payment::class.java))
    }
}