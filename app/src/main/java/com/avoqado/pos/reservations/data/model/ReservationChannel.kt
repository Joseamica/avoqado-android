package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReservationChannel {
    @SerialName("DASHBOARD") DASHBOARD,
    @SerialName("WEB") WEB,
    @SerialName("PHONE") PHONE,
    @SerialName("WHATSAPP") WHATSAPP,
    @SerialName("APP") APP,
    @SerialName("WALK_IN") WALK_IN,
    @SerialName("THIRD_PARTY") THIRD_PARTY;

    val displayLabel: String get() = when (this) {
        DASHBOARD -> "Dashboard"
        WEB -> "Web"
        PHONE -> "Teléfono"
        WHATSAPP -> "WhatsApp"
        APP -> "App"
        WALK_IN -> "Walk-in"
        THIRD_PARTY -> "Externo"
    }
}
