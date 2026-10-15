package com.avoqado.pos.loyalty.data

import kotlinx.serialization.Serializable

/** Por qué una venta NO sumó sello. Espejo EXACTO, por nombre, del `SaleStampReason` del servidor. */
enum class SaleStampReason { NOT_PAID, WINDOW_EXPIRED, OTHER_CUSTOMER, DAILY_LIMIT, PENDING, ERROR }

/**
 * Qué pasó con el sello de ESTA venta para ESTE cliente: el campo `loyalty` del attach
 * (`POST /mobile/venues/:venueId/payments[/:paymentId]/customer`) o el `data` del GET
 * `/mobile/venues/:venueId/payments/:paymentId/loyalty`.
 *
 * Todos los campos con default: un servidor viejo que no manda el campo, o que manda uno a medias, se lee igual y la
 * pantalla simplemente no pinta nada. `reason` viaja como texto y se traduce en [reasonCode]: un motivo NUEVO del
 * servidor no puede tumbar la lectura de la respuesta entera.
 */
@Serializable
data class SaleStampOutcome(
    val stampsEnabled: Boolean = false,
    val stamped: Boolean = false,
    val reason: String? = null,
    val stampsEarned: Int = 0,
    val stampsRequired: Int = 0,
    val rewardReady: Boolean = false,
) {
    val reasonCode: SaleStampReason?
        get() = SaleStampReason.entries.firstOrNull { it.name == reason }
}
