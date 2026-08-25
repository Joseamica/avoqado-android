package com.avoqado.pos.kiosk.domain

import java.time.Instant

/**
 * La ventana de check-in del kiosco, como función pura.
 *
 * ```
 * inicio − 20 min   ≤   ahora   <   inicio + tolerancia
 * ```
 *
 * 🔴 **Es un ESPEJO de la regla del servidor**, no una regla nueva: allá vive en
 * `evaluateKioskWindow` (`avoqado-server/src/services/reservation/checkIn.service.ts`)
 * y es la que de verdad autoriza — responde 422 `CHECK_IN_OUTSIDE_WINDOW` si no
 * coincide. Aquí sólo decide qué PINTAR. Si la regla cambia allá, cambia aquí en
 * el mismo trabajo o el kiosco enseñará una lista que el servidor va a rechazar.
 *
 * El `<` de arriba es estricto, igual que allá: en el instante exacto del cierre
 * gana el no-show, no el check-in (el job marca con `deadline <= now`).
 */
object KioskWindow {

    /** Cuánto antes del inicio se abre. Igual que `KIOSK_EARLY_CHECK_IN_MIN` del servidor. */
    const val EARLY_MIN = 20L

    /** Igual que el default de `ReservationSettings.noShowGraceMin` del servidor. */
    const val DEFAULT_GRACE_MIN = 15

    fun isOpen(startsAt: Instant, now: Instant, graceMin: Int): Boolean {
        val open = startsAt.minusSeconds(EARLY_MIN * 60L)
        val close = startsAt.plusSeconds(graceMin * 60L)
        return !now.isBefore(open) && now.isBefore(close)
    }
}
