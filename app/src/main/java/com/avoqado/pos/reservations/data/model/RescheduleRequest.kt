package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class RescheduleNotificationChannel { push, whatsapp, email, sms, none }

@Serializable
data class RescheduleRequest(
    val startsAt: String,
    val endsAt: String,
    val notificationChannel: RescheduleNotificationChannel? = null,
    val customMessage: String? = null,
    val allowOverCapacity: Boolean? = null,
)

@Serializable
data class CancelReservationRequest(val reason: String? = null)
