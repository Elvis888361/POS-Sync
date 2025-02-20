package com.example.possync
import org.json.JSONObject

data class CartItem(
    val itemCode: String,
    val name: String,
    var price: Double,
    var quantity: Int,
    val uom:String
) {
    fun toJSON(): JSONObject {
        return JSONObject().apply {
            put("item_code", itemCode)
            put("qty", quantity)
            put("rate", price)
            put("description", name)
            put("uom", uom)
        }
    }
}
