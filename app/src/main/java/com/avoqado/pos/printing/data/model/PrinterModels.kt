package com.avoqado.pos.printing.data.model

data class PrinterDevice(
    val name: String,
    val address: String,
    val isConnected: Boolean = false,
)

data class ReceiptData(
    val venueName: String,
    val orderNumber: String?,
    val items: List<ReceiptItem>,
    val subtotal: Int,
    val discount: Int = 0,
    val tip: Int = 0,
    val total: Int,
    val paymentMethod: String,
    val date: String,
)

data class ReceiptItem(
    val name: String,
    val quantity: Int,
    val price: Int,
)
