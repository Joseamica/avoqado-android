package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.Serializable

@Serializable
data class RescheduleRequest(val startsAt: String, val endsAt: String)

@Serializable
data class CancelReservationRequest(val reason: String? = null)
