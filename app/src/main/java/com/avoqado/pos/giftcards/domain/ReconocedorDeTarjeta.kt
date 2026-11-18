package com.avoqado.pos.giftcards.domain

import com.avoqado.pos.giftcards.data.PruebaDeTarjeta

object ReconocedorDeTarjeta {
    private val liga = Regex("^(?i:https?)://(?i:[^/\\s]+)/tarjeta/([A-Za-z0-9_-]{20,})/?$")
    private val codigo = Regex("[0-9A-Za-z]{17}")
    fun reconocer(crudo: String): PruebaDeTarjeta? {
        val texto = crudo.trim()
        liga.matchEntire(texto)?.let { return PruebaDeTarjeta.PageToken(it.groupValues[1]) }
        val limpio = texto.filterNot { it == '-' || it.isWhitespace() }
        return limpio.takeIf(codigo::matches)?.let { PruebaDeTarjeta.Codigo(it.uppercase()) }
    }

    /** Las letras que usa el SERVIDOR (Crockford Base32: sin I, L, O ni U). Espejo de `giftCardCode.ts`. */
    private const val ALFABETO = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    /**
     * D14 (Codex r5 #3): ¿es un código que el SERVIDOR pudo emitir? Mismo `normalizeGiftCardCode`: mayúsculas, I/L → 1,
     * O → 0, alfabeto y carácter de control (Luhn mod 32). Lo usa la segunda lectura de la pistola, que no debe convertir un
     * SKU desconocido con guiones («CAFE-REGULAR-000001») en una consulta de tarjeta. Una liga de tarjeta también cuenta.
     */
    fun esDelServidor(crudo: String): Boolean {
        val prueba = reconocer(crudo) ?: return false
        if (prueba !is PruebaDeTarjeta.Codigo) return true
        val limpio = prueba.code.replace('I', '1').replace('L', '1').replace('O', '0')
        if (limpio.length != 17 || limpio.any { it !in ALFABETO }) return false
        return caracterDeControl(limpio.substring(0, 16)) == limpio[16]
    }

    /** Luhn mod 32 sobre el alfabeto Crockford (`caracterDeControl` de `giftCardCode.ts`). */
    private fun caracterDeControl(payload: String): Char {
        val n = ALFABETO.length
        var factor = 2
        var suma = 0
        for (i in payload.indices.reversed()) {
            var sumando = factor * ALFABETO.indexOf(payload[i])
            factor = if (factor == 2) 1 else 2
            sumando = sumando / n + sumando % n
            suma += sumando
        }
        return ALFABETO[(n - suma % n) % n]
    }
}
