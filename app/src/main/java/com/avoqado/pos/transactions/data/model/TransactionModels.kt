package com.avoqado.pos.transactions.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

@Serializable
data class Transaction(
    val id: String,
    val orderNumber: String? = null,
    val status: String = "COMPLETED",
    val paymentMethod: String? = null,
    val subtotal: Int = 0,
    val discount: Int = 0,
    val tip: Int = 0,
    val total: Int = 0,
    val items: List<TransactionItem> = emptyList(),
    val customerName: String? = null,
    val createdAt: String? = null,
    val staffName: String? = null,
) {
    val totalDisplay: String get() = "$${String.format("%.2f", total / 100.0)}"
    val paymentMethodDisplay: String
        get() = when (paymentMethod) {
            "CARD" -> "Tarjeta"
            "CASH" -> "Efectivo"
            else -> paymentMethod ?: "—"
        }
    val statusDisplay: String
        get() = when (status) {
            "COMPLETED" -> "Completada"
            "CANCELLED" -> "Cancelada"
            "PENDING" -> "Pendiente"
            "REFUNDED" -> "Reembolsada"
            else -> status
        }

    // MARK: - Date grouping (matching iOS: "Hoy", "Ayer", formatted date)

    @Transient
    private val parsedDateTime: LocalDateTime? = createdAt?.let {
        try {
            LocalDateTime.parse(it, DateTimeFormatter.ISO_DATE_TIME)
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
            } catch (_: DateTimeParseException) {
                try {
                    LocalDate.parse(it, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }

    val dateGroup: String
        get() {
            val date = parsedDateTime?.toLocalDate() ?: return "Sin fecha"
            val today = LocalDate.now()
            return when {
                date == today -> "Hoy"
                date == today.minusDays(1) -> "Ayer"
                else -> date.format(DateTimeFormatter.ofPattern("d 'de' MMMM"))
            }
        }

    val timeDisplay: String?
        get() = parsedDateTime?.format(DateTimeFormatter.ofPattern("HH:mm"))
}

@Serializable
data class TransactionItem(
    val id: String? = null,
    val name: String,
    val quantity: Int = 1,
    val unitPrice: Int = 0,
    val total: Int = 0,
)

@Serializable
data class TransactionsResponse(
    val success: Boolean = true,
    val data: List<Transaction> = emptyList(),
    val meta: PaginationMeta? = null,
)

@Serializable
data class PaginationMeta(
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
    val pageCount: Int = 1,
)

enum class TransactionFilter(val label: String) {
    ALL("Todas"),
    TODAY("Hoy"),
    WEEK("Esta semana"),
    MONTH("Este mes"),
}
