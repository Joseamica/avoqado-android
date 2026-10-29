package com.avoqado.pos.giftcards.domain

import kotlin.math.min

data class MontoOfrecido(val montoCents: Int, val propinaCents: Int, val aviso: String?)
fun montoOfrecido(faltanteCents: Int, saldoCents: Int, propinaCents: Int): MontoOfrecido {
    val base = min(faltanteCents, saldoCents - propinaCents)
    return if (base > 0) MontoOfrecido(base, propinaCents, null)
    else MontoOfrecido(min(faltanteCents, saldoCents).coerceAtLeast(0), 0, when {
        saldoCents <= 0 -> TextosDeTarjetas.TARJETA_SIN_SALDO
        propinaCents > 0 -> TextosDeTarjetas.SALDO_NO_ALCANZA_PROPINA
        else -> null
    })
}
