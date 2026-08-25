package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Reservation(
    val id: String,
    val venueId: String,
    val confirmationCode: String,
    val cancelSecret: String,
    val status: ReservationStatus,
    val channel: ReservationChannel,
    val startsAt: String,            // ISO-8601 UTC — converted via VenueDateTimeFormatter at display time
    val endsAt: String,
    val duration: Int,
    val customerId: String? = null,
    val customer: CustomerLite? = null,
    val guestName: String? = null,
    val guestPhone: String? = null,
    val guestEmail: String? = null,
    val partySize: Int = 1,
    val spotIds: List<String> = emptyList(),
    val tableId: String? = null,
    val table: TableLite? = null,
    val productId: String? = null,
    val product: ProductLite? = null,
    val classSessionId: String? = null,
    val classSession: ClassSessionLite? = null,
    val assignedStaffId: String? = null,
    val assignedStaff: StaffLite? = null,
    // Populated on the check-in response for multi-service bookings — one entry per booked
    // service. `id` IS the productId. Null/empty on older responses or single-service bookings
    // (those still resolve via [productId]/[product] above); default keeps decoding additive-safe.
    val services: List<ReservationServiceLite>? = null,
    val depositAmount: String? = null,    // BigDecimal as string
    val depositStatus: DepositStatus? = null,
    val depositPaidAt: String? = null,
    val depositRefundedAt: String? = null,
    val createdById: String? = null,
    val createdBy: StaffLite? = null,
    val confirmedAt: String? = null,
    val checkedInAt: String? = null,
    val completedAt: String? = null,
    val cancelledAt: String? = null,
    val noShowAt: String? = null,
    val cancelledBy: String? = null,
    val cancellationReason: String? = null,
    val specialRequests: String? = null,
    val internalNotes: String? = null,
    val tags: List<String> = emptyList(),
    val createdAt: String,
    val updatedAt: String,
) {
    val displayName: String get() = customer?.fullName ?: guestName ?: "Sin nombre"
    val displayPhone: String? get() = customer?.phone ?: guestPhone
    val displayServiceName: String? get() = product?.name ?: classSession?.productName
}

@Serializable
data class CustomerLite(
    val id: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val phone: String? = null,
    val email: String? = null,
) {
    val fullName: String get() = listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { phone ?: email ?: id }
}

@Serializable
data class TableLite(
    val id: String,
    val number: String,
    val capacity: Int? = null,
)

@Serializable
data class ProductLite(
    val id: String,
    val name: String,
    val durationMinutes: Int? = null,
    val price: String? = null,

    /** Acomodo del salón. Traduce un id de `spotIds` a algo legible. */
    val layoutConfig: LayoutConfig? = null,
)

/**
 * One booked service on a multi-service reservation, as returned in the check-in response's
 * `services[]` array. `id` IS the productId (server does not send a separate field). No
 * `categoryId`/modifiers/quantity here — those are resolved client-side (categoryId via
 * [com.avoqado.pos.pos.data.ProductsRepository]) or defaulted (quantity = 1) when building a
 * printable comanda line.
 */
@Serializable
data class ReservationServiceLite(
    val id: String,
    val name: String,
    val duration: Int? = null,
)

@Serializable
data class ClassSessionLite(
    val id: String,
    /**
     * 🔴 En una clase el instructor vive AQUÍ, no en la reserva: medido en la
     * base, 44 de 44 sesiones lo traen y 0 de 9 reservas de clase. Quien quiera
     * decir "con Sofía" tiene que mirar este campo primero.
     */
    val assignedStaff: StaffLite? = null,
    val product: ProductLite? = null,
    val productId: String? = null,
    val productName: String? = null,
    val capacity: Int,
    val attendeeCount: Int = 0,
)

@Serializable
data class StaffLite(
    val id: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val avatarUrl: String? = null,
) {
    val displayName: String get() = listOfNotNull(firstName, lastName).joinToString(" ").ifBlank { id }
}

/**
 * El acomodo del salón de una clase: tapetes, reformers, bicis.
 *
 * Espejo exacto de lo que escribe `ClassLayoutEditor.tsx` del dashboard y de lo
 * que lee el `SeatPicker` del widget de reservas. Los tres tienen que coincidir
 * por nombre; si allá cambia, aquí también, en el mismo trabajo.
 */
@Serializable
data class LayoutConfig(
    /** circle · bike · mat · reformer · bed · chair · generic */
    val iconType: String? = null,
    val rows: Int = 0,
    val cols: Int = 0,
    val showInstructor: Boolean = false,
    val spots: List<LayoutSpot> = emptyList(),
) {
    /** "3" -> "3". Devuelve null si ese lugar ya no existe en el acomodo. */
    fun labelFor(spotId: String): String? =
        spots.firstOrNull { it.id == spotId }?.label

    /**
     * "3" -> "Tapete 3" · "Bici 5" · "Cama 2", según lo que el negocio configuró.
     *
     * 🔴 Antes todo salía como "Lugar 3", incluso en un estudio de yoga con tapetes o en
     * uno de ciclismo con bicis. El acomodo YA sabe de qué es —`iconType` lo guarda desde
     * que se arma en el dashboard— y no usarlo hacía que el kiosco hablara en un idioma
     * que no es el del negocio. Quien llega a su clase de spinning busca su BICI, no su
     * "lugar".
     *
     * Un tipo que no conocemos cae en "Lugar", que es cierto para cualquier acomodo.
     */
    fun spotLabelFor(spotId: String): String? {
        val numero = labelFor(spotId) ?: return null
        val sustantivo = when (iconType) {
            "mat" -> "Tapete"
            "bike" -> "Bici"
            "bed" -> "Cama"
            "reformer" -> "Reformer"
            "chair" -> "Silla"
            else -> "Lugar"
        }
        return "$sustantivo $numero"
    }
}

@Serializable
data class LayoutSpot(
    val id: String,
    val row: Int = 0,
    val col: Int = 0,
    val label: String = "",
    val enabled: Boolean = true,
)
