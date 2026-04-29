package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.Serializable

@Serializable
data class UpdateReservationRequest(
    val customerId: String? = null,
    val guestName: String? = null,
    val guestPhone: String? = null,
    val guestEmail: String? = null,
    val partySize: Int? = null,
    val duration: Int? = null,
    val productId: String? = null,
    val tableId: String? = null,
    val assignedStaffId: String? = null,
    val specialRequests: String? = null,
    val internalNotes: String? = null,
    val tags: List<String>? = null,
)
