package com.avoqado.pos.payment.domain

/**
 * D10 (27-oct, founder «A»): lo que paga una parte «por producto». Cada parte paga la parte PROPORCIONAL del saldo de la
 * venta según lo que valen sus renglones (su precio menos su descuento propio y, si es el producto gratis del premio, menos
 * el premio), así el descuento de toda la cuenta se reparte entre los productos como lo hace el SERVIDOR.
 *
 * La parte es TODO lo que falta cuando lo elegido es todo lo que queda, cuando saldría en $0 (el `/pay` la rechazaría) o
 * cuando dejaría ≤ 1 ¢ (el SERVIDOR da por pagada una orden con 1 ¢ vivo y ya no deja cobrarlo). Espejo de iOS.
 */
data class ParteProporcional(val cents: Int, val todoLoQueFalta: Boolean)

fun parteProporcional(pesosElegidos: Long, pesosRestantes: Long, saldoCents: Int): ParteProporcional {
    if (saldoCents <= 0) return ParteProporcional(0, todoLoQueFalta = true)
    if (pesosRestantes <= 0 || pesosElegidos >= pesosRestantes) return ParteProporcional(saldoCents, todoLoQueFalta = true)
    // Redondeo a la mitad hacia arriba, en enteros.
    val parte = ((2L * pesosElegidos.coerceAtLeast(0) * saldoCents + pesosRestantes) / (2L * pesosRestantes)).toInt()
    if (parte <= 0 || saldoCents - parte <= 1) return ParteProporcional(saldoCents, todoLoQueFalta = true)
    return ParteProporcional(parte, todoLoQueFalta = false)
}
