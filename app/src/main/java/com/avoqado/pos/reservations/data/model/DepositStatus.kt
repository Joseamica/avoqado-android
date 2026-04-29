package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class DepositStatus {
    @SerialName("PENDING") PENDING,
    @SerialName("CARD_HOLD") CARD_HOLD,
    @SerialName("PAID") PAID,
    @SerialName("REFUNDED") REFUNDED,
    @SerialName("FORFEITED") FORFEITED,
}
