package com.avoqado.pos.reservations.domain

enum class VenueMode(val storageValue: String, val displayLabel: String) {
    STANDARD("standard", "Estándar"),
    RESERVATIONS("reservations", "Reservas");

    companion object {
        const val STORAGE_KEY = "KEY_VENUE_MODE"
        fun fromStorage(raw: String?): VenueMode = entries.firstOrNull { it.storageValue == raw } ?: STANDARD
    }
}
