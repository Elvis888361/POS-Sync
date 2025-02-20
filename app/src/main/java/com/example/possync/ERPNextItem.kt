package com.example.possync

data class ERPNextItem(
    val itemCode: String,
    val name:String,
    val uom: String,
    val price: Double,
    val stockQty: Double,
    val barcode: Any
)
