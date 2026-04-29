package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WaitlistEntry(
    val id: String,
    val venueId: String,
    val customerId: String? = null,
    val customer: CustomerLite? = null,
    val guestName: String? = null,
    val guestPhone: String? = null,
    val partySize: Int = 1,
    val desiredStartAt: String,
    val desiredEndAt: String? = null,
    val status: WaitlistStatus,
    val position: Int,
    val notifiedAt: String? = null,
    val responseDeadline: String? = null,
    val promotedReservationId: String? = null,
    val notes: String? = null,
    val createdAt: String,
    val updatedAt: String,
) {
    val displayName: String get() = customer?.fullName ?: guestName ?: "Sin nombre"
    val displayPhone: String? get() = customer?.phone ?: guestPhone
}

@Serializable
enum class WaitlistStatus {
    @SerialName("WAITING") WAITING,
    @SerialName("NOTIFIED") NOTIFIED,
    @SerialName("PROMOTED") PROMOTED,
    @SerialName("EXPIRED") EXPIRED,
    @SerialName("CANCELLED") CANCELLED,
}

@Serializable
data class AddToWaitlistRequest(
    val customerId: String? = null,
    val guestName: String? = null,
    val guestPhone: String? = null,
    val partySize: Int? = null,
    val desiredStartAt: String,
    val desiredEndAt: String? = null,
    val notes: String? = null,
)

@Serializable
data class PromoteWaitlistRequest(val reservationId: String)
