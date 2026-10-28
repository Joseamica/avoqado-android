package com.avoqado.pos.giftcards.domain

import com.avoqado.pos.giftcards.data.PruebaDeTarjeta

object ReconocedorDeTarjeta {
    private val liga = Regex("^(?i:https?)://(?i:[^/\\s]+)/tarjeta/([A-Za-z0-9_-]{20,})/?$")
    private val codigo = Regex("[0-9A-Za-z]{17}")
    fun reconocer(crudo: String): PruebaDeTarjeta? {
        val texto = crudo.trim()
        liga.matchEntire(texto)?.let { return PruebaDeTarjeta.PageToken(it.groupValues[1]) }
        val limpio = texto.filterNot { it == '-' || it.isWhitespace() }
        return limpio.takeIf(codigo::matches)?.let(PruebaDeTarjeta::Codigo)
    }
}
