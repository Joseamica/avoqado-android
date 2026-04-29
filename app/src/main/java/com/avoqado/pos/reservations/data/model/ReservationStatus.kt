package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReservationStatus {
    @SerialName("PENDING") PENDING,
    @SerialName("CONFIRMED") CONFIRMED,
    @SerialName("CHECKED_IN") CHECKED_IN,
    @SerialName("COMPLETED") COMPLETED,
    @SerialName("CANCELLED") CANCELLED,
    @SerialName("NO_SHOW") NO_SHOW;

    val isActive: Boolean get() = this == PENDING || this == CONFIRMED || this == CHECKED_IN
    val isTerminal: Boolean get() = this == COMPLETED || this == CANCELLED || this == NO_SHOW
}
