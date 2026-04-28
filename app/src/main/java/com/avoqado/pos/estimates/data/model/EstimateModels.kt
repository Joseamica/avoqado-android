package com.avoqado.pos.estimates.data.model

import kotlinx.serialization.Serializable
import java.util.Locale

// MARK: - Enums

enum class EstimateStatus(val label: String) {
    DRAFT("Borrador"),
    SENT("Enviado"),
    ACCEPTED("Aceptado"),
    REJECTED("Rechazado"),
    EXPIRED("Expirado"),
    CONVERTED("Convertido"),
}

// MARK: - Models

@Serializable
data class Estimate(
    val id: String,
    val number: String? = null,
    val status: String = "DRAFT",
    val customerId: String? = null,
    val customerName: String? = null,
    val customerEmail: String? = null,
    val customerPhone: String? = null,
    val notes: String? = null,
    val subtotal: Double = 0.0,
    val taxAmount: Double = 0.0,
    val total: Double = 0.0,
    val validUntil: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val items: List<EstimateItem>? = null,
) {
    val estimateStatus: EstimateStatus
        get() = when (status) {
            "DRAFT" -> EstimateStatus.DRAFT
            "SENT" -> EstimateStatus.SENT
            "ACCEPTED" -> EstimateStatus.ACCEPTED
            "REJECTED" -> EstimateStatus.REJECTED
            "EXPIRED" -> EstimateStatus.EXPIRED
            "CONVERTED" -> EstimateStatus.CONVERTED
            else -> EstimateStatus.DRAFT
        }

    val displayTotal: String
        get() = "$${String.format(Locale.US, "%.2f", total)}"

    val displayNumber: String
        get() = number ?: "—"

    val displayDate: String
        get() {
            val instant = com.avoqado.pos.core.util.VenueDateTimeFormatter.parseIso(createdAt)
                ?: return createdAt?.take(10) ?: "—"
            return instant.atZone(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        }

    val itemCount: Int
        get() = items?.size ?: 0
}

@Serializable
data class EstimatesListResponse(
    val success: Boolean = true,
    val estimates: List<Estimate> = emptyList(),
    val data: List<Estimate> = emptyList(),
) {
    /** Resolve whichever field the API actually populates. */
    val resolved: List<Estimate>
        get() = estimates.ifEmpty { data }
}

@Serializable
data class EstimateItem(
    val id: String,
    val productId: String? = null,
    val productName: String? = null,
    val description: String? = null,
    val quantity: Int = 1,
    val unitPrice: Double = 0.0,
    val taxRate: Double = 0.0,
    val total: Double = 0.0,
) {
    val displayUnitPrice: String
        get() = "$${String.format(Locale.US, "%.2f", unitPrice)}"

    val displayTotal: String
        get() = "$${String.format(Locale.US, "%.2f", total)}"
}
