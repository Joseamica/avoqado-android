package com.avoqado.pos.orders.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.text.SimpleDateFormat
import java.util.Locale

// MARK: - Enums

enum class OrderStatus(val label: String) {
    PENDING("Pendiente"),
    COMPLETED("Completado"),
    CONFIRMED("Confirmado"),
    CANCELLED("Cancelado");
}

enum class PaymentStatus(val label: String) {
    PENDING("Sin pagar"),
    PARTIAL("Parcial"),
    PAID("Pagado");
}

enum class OrderType(val label: String) {
    DINE_IN("Para comer aqui"),
    TAKEOUT("Para llevar"),
    DELIVERY("Entrega"),
    PICKUP("Recoger");
}

// MARK: - Summary Model

@Serializable
data class OrderSummary(
    val id: String,
    val orderNumber: String = "",
    val status: String = "",
    val paymentStatus: String = "",
    val type: String? = null,
    val source: String? = null,
    val subtotal: Double = 0.0,
    val taxAmount: Double = 0.0,
    val discountAmount: Double = 0.0,
    val total: Double = 0.0,
    val itemCount: Int = 0,
    val staffName: String? = null,
    val customerName: String? = null,
    val createdAt: String = "",
) {
    @Transient
    val orderStatus: OrderStatus = when (status.uppercase()) {
        "PENDING" -> OrderStatus.PENDING
        "COMPLETED" -> OrderStatus.COMPLETED
        "CONFIRMED" -> OrderStatus.CONFIRMED
        "CANCELLED" -> OrderStatus.CANCELLED
        else -> OrderStatus.PENDING
    }

    @Transient
    val orderPaymentStatus: PaymentStatus = when (paymentStatus.uppercase()) {
        "PARTIAL" -> PaymentStatus.PARTIAL
        "PAID" -> PaymentStatus.PAID
        else -> PaymentStatus.PENDING
    }

    @Transient
    val orderType: OrderType? = when (type?.uppercase()) {
        "DINE_IN" -> OrderType.DINE_IN
        "TAKEOUT" -> OrderType.TAKEOUT
        "DELIVERY" -> OrderType.DELIVERY
        "PICKUP" -> OrderType.PICKUP
        else -> null
    }

    @Transient
    val formattedTotal: String = "$%,.2f".format(total)

    @Transient
    val timeDisplay: String = parseTimeDisplay(createdAt)

    @Transient
    val dateGroup: String = parseDateGroup(createdAt)
}

// MARK: - Detail Model

@Serializable
data class OrderDetail(
    val id: String,
    val orderNumber: String = "",
    val status: String = "",
    val paymentStatus: String = "",
    val type: String? = null,
    val source: String? = null,
    val subtotal: Double = 0.0,
    val taxAmount: Double = 0.0,
    val discountAmount: Double = 0.0,
    val total: Double = 0.0,
    val itemCount: Int = 0,
    val staffName: String? = null,
    val customerName: String? = null,
    val createdAt: String = "",
    val specialRequests: String? = null,
    val items: List<OrderDetailItem>? = null,
    val payments: List<OrderPaymentInfo>? = null,
) {
    @Transient
    val orderStatus: OrderStatus = when (status.uppercase()) {
        "PENDING" -> OrderStatus.PENDING
        "COMPLETED" -> OrderStatus.COMPLETED
        "CONFIRMED" -> OrderStatus.CONFIRMED
        "CANCELLED" -> OrderStatus.CANCELLED
        else -> OrderStatus.PENDING
    }

    @Transient
    val orderPaymentStatus: PaymentStatus = when (paymentStatus.uppercase()) {
        "PARTIAL" -> PaymentStatus.PARTIAL
        "PAID" -> PaymentStatus.PAID
        else -> PaymentStatus.PENDING
    }

    @Transient
    val orderType: OrderType? = when (type?.uppercase()) {
        "DINE_IN" -> OrderType.DINE_IN
        "TAKEOUT" -> OrderType.TAKEOUT
        "DELIVERY" -> OrderType.DELIVERY
        "PICKUP" -> OrderType.PICKUP
        else -> null
    }

    @Transient
    val formattedTotal: String = "$%,.2f".format(total)

    @Transient
    val timeDisplay: String = parseTimeDisplay(createdAt)

    @Transient
    val dateGroup: String = parseDateGroup(createdAt)
}

// MARK: - Item Models

@Serializable
data class OrderDetailItem(
    val id: String,
    val productId: String? = null,
    val productName: String = "",
    val quantity: Int = 1,
    val unitPrice: Double = 0.0,
    val total: Double = 0.0,
    val notes: String? = null,
    val modifiers: List<OrderItemModifier>? = null,
)

@Serializable
data class OrderItemModifier(
    val name: String = "",
    val price: Double = 0.0,
)

// MARK: - Payment Info

@Serializable
data class OrderPaymentInfo(
    val id: String,
    val amount: Double = 0.0,
    val tipAmount: Double = 0.0,
    val method: String = "",
    val status: String = "",
    val createdAt: String = "",
    val processedBy: String? = null,
) {
    @Transient
    val methodLabel: String = when (method.uppercase()) {
        "CASH" -> "Efectivo"
        "CARD", "CREDIT_CARD", "DEBIT_CARD" -> "Tarjeta"
        "TRANSFER" -> "Transferencia"
        else -> method
    }

    @Transient
    val timeDisplay: String = parseTimeDisplay(createdAt)
}

// MARK: - Paginated Response

@Serializable
data class OrderPage(
    val orders: List<OrderSummary> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pageCount: Int = 1,
) {
    val hasMore: Boolean get() = page < pageCount
}

// MARK: - Date Helpers

private val isoFormats = listOf(
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    "yyyy-MM-dd'T'HH:mm:ss'Z'",
    "yyyy-MM-dd'T'HH:mm:ssXXX",
    "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
)

private fun parseDate(isoString: String): java.util.Date? {
    for (fmt in isoFormats) {
        try {
            val sdf = SimpleDateFormat(fmt, Locale.US)
            sdf.isLenient = false
            return sdf.parse(isoString)
        } catch (_: Exception) {
            // try next format
        }
    }
    return null
}

private fun parseTimeDisplay(isoString: String): String {
    val date = parseDate(isoString) ?: return ""
    return SimpleDateFormat("HH:mm", Locale.US).format(date)
}

private fun parseDateGroup(isoString: String): String {
    val date = parseDate(isoString) ?: return ""
    val today = java.util.Calendar.getInstance()
    val cal = java.util.Calendar.getInstance().apply { time = date }

    return when {
        cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) -> "Hoy"
        cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) - 1 -> "Ayer"
        else -> SimpleDateFormat("d 'de' MMMM", Locale("es", "MX")).format(date)
    }
}
