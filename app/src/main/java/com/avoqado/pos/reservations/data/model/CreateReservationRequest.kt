package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.Serializable

@Serializable
data class CreateReservationRequest(
    val customerId: String? = null,
    val guestName: String? = null,
    val guestPhone: String? = null,
    val guestEmail: String? = null,
    val partySize: Int,
    val startsAt: String, // ISO-8601 UTC
    val endsAt: String,
    val productId: String? = null,
    val classSessionId: String? = null,
    val tableId: String? = null,
    val assignedStaffId: String? = null,
    val channel: String = "DASHBOARD", // DASHBOARD | WALK_IN | PHONE | etc.
    val specialRequests: String? = null,
    val internalNotes: String? = null,
    val tags: List<String> = emptyList(),
)
