package com.avoqado.pos.transactions.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

@Serializable
data class Transaction(
    val id: String,
    val orderNumber: String? = null,
    val status: String = "COMPLETED",
    val method: String? = null,
    val amount: Double = 0.0,
    val tipAmount: Double = 0.0,
    val items: List<TransactionItem> = emptyList(),
    val customerName: String? = null,
    val createdAt: String? = null,
    val staffName: String? = null,
    val cardBrand: String? = null,
    val maskedPan: String? = null,
    val referenceNumber: String? = null,
) {
    val paymentMethod: String? get() = method

    /** Total = amount + tip (in dollars) */
    val totalAmount: Double get() = amount + tipAmount

    val totalDisplay: String get() = "$${String.format("%.2f", totalAmount)}"
    val formattedAmount: String get() = "$${String.format("%.2f", amount)}"
    val formattedTip: String get() = "$${String.format("%.2f", tipAmount)}"

    /** Smart description: "Visa ****1234", "Efectivo", "Tarjeta", etc. */
    val methodDescription: String
        get() = when (method) {
            "CASH" -> "Efectivo"
            "CARD" -> {
                val brand = cardBrand ?: ""
                if (!maskedPan.isNullOrEmpty() && maskedPan.length >= 4) {
                    val last4 = maskedPan.takeLast(4)
                    "$brand ****$last4".trim()
                } else {
                    brand.ifEmpty { "Tarjeta" }
                }
            }
            "CREDIT_CARD" -> "Tarjeta de crédito"
            "DEBIT_CARD" -> "Tarjeta de débito"
            "TRANSFER" -> "Transferencia"
            else -> method?.replace("_", " ")
                ?.replaceFirstChar { it.uppercase() } ?: "—"
        }

    val statusDisplay: String
        get() = when (status) {
            "COMPLETED" -> "Completada"
            "CANCELLED" -> "Cancelada"
            "PENDING" -> "Pendiente"
            "REFUNDED" -> "Reembolsada"
            else -> status
        }

    // MARK: - Date parsing

    @Transient
    val parsedDateTime: LocalDateTime? = createdAt?.let {
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
                date.year != today.year -> date.format(
                    DateTimeFormatter.ofPattern("d 'de' MMMM, yyyy", Locale("es")),
                )
                else -> date.format(
                    DateTimeFormatter.ofPattern("d 'de' MMMM", Locale("es")),
                )
            }
        }

    val timeDisplay: String?
        get() = parsedDateTime?.format(DateTimeFormatter.ofPattern("HH:mm"))

    /** Short formatted date for detail view: "27/01/26, 11:20" */
    val dateShortDisplay: String?
        get() = parsedDateTime?.format(DateTimeFormatter.ofPattern("dd/MM/yy, HH:mm"))
}

@Serializable
data class TransactionItem(
    val id: String? = null,
    val productName: String = "",
    val quantity: Int = 1,
    val unitPrice: Double = 0.0,
    val amount: Double = 0.0,
    val productImageUrl: String? = null,
    val modifiers: List<TransactionItemModifier> = emptyList(),
) {
    val name: String get() = productName
    val formattedTotal: String get() = "$${String.format("%.2f", amount)}"
}

@Serializable
data class TransactionItemModifier(
    val id: String? = null,
    val name: String = "",
    val price: Double = 0.0,
)

@Serializable
data class TransactionsResponse(
    val success: Boolean = true,
    val data: List<Transaction> = emptyList(),
    val meta: PaginationMeta? = null,
)

@Serializable
data class TransactionDetailResponse(
    val success: Boolean = true,
    val transaction: Transaction? = null,
)

@Serializable
data class PaginationMeta(
    val total: Int = 0,
    val page: Int = 1,
    val pageSize: Int = 20,
    val pageCount: Int = 1,
)

// MARK: - Filter Models

enum class TransactionActiveFilter {
    METHOD, STAFF, SUBTOTAL, TIP, TOTAL,
}

enum class AmountOperator(val label: String, val symbol: String) {
    GREATER_THAN("Mayor que", ">"),
    LESS_THAN("Menor que", "<"),
    EQUAL_TO("Igual a", "="),
    BETWEEN("Entre", "entre");

    fun matches(value: Double, v1: Double, v2: Double?): Boolean = when (this) {
        GREATER_THAN -> value > v1
        LESS_THAN -> value < v1
        EQUAL_TO -> kotlin.math.abs(value - v1) < 0.01
        BETWEEN -> {
            val low = v2?.let { minOf(v1, it) } ?: v1
            val high = v2?.let { maxOf(v1, it) } ?: v1
            value in low..high
        }
    }

    fun summary(v1: Double, v2: Double?): String = when (this) {
        GREATER_THAN -> "> $${String.format("%.0f", v1)}"
        LESS_THAN -> "< $${String.format("%.0f", v1)}"
        EQUAL_TO -> "= $${String.format("%.0f", v1)}"
        BETWEEN -> "$${String.format("%.0f", v1)} – $${v2?.let { String.format("%.0f", it) } ?: "?"}"
    }
}

data class TransactionFilters(
    val methods: Set<String> = emptySet(),
    val staffNames: Set<String> = emptySet(),
    val subtotalOperator: AmountOperator? = null,
    val subtotalValue1: Double? = null,
    val subtotalValue2: Double? = null,
    val tipOperator: AmountOperator? = null,
    val tipValue1: Double? = null,
    val tipValue2: Double? = null,
    val totalOperator: AmountOperator? = null,
    val totalValue1: Double? = null,
    val totalValue2: Double? = null,
) {
    val hasActiveFilters: Boolean
        get() = methods.isNotEmpty() || staffNames.isNotEmpty() ||
            subtotalOperator != null || tipOperator != null || totalOperator != null

    fun clearAll() = TransactionFilters()

    fun apply(transactions: List<Transaction>): List<Transaction> = transactions.filter { tx ->
        if (methods.isNotEmpty() && tx.method !in methods) return@filter false
        if (staffNames.isNotEmpty()) {
            val name = tx.staffName
            if (name.isNullOrEmpty() || name !in staffNames) return@filter false
        }
        if (subtotalOperator != null && subtotalValue1 != null) {
            if (!subtotalOperator.matches(tx.amount, subtotalValue1, subtotalValue2)) return@filter false
        }
        if (tipOperator != null && tipValue1 != null) {
            if (!tipOperator.matches(tx.tipAmount, tipValue1, tipValue2)) return@filter false
        }
        if (totalOperator != null && totalValue1 != null) {
            if (!totalOperator.matches(tx.totalAmount, totalValue1, totalValue2)) return@filter false
        }
        true
    }
}
