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
