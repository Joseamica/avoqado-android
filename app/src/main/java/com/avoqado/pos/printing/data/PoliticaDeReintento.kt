package com.avoqado.pos.printing.data

/**
 * Cuándo volver a intentar una comanda que no salió.
 *
 * 🔴 Existe porque el POS se rendía al PRIMER intento: un tropiezo de segundos en la LAN
 * (Testarudo, cada mañana) se convertía en un pedido que la cocina nunca vio. Un POS de
 * Windows no lo sufría porque el spooler reintenta por su cuenta; esto es nuestro spooler.
 *
 * PURA a propósito — sin reloj, sin sockets, sin Android — para poder barrer los seis
 * intentos en un test de milisegundos en vez de esperar un minuto real.
 *
 * Los intentos caen a los 0, 2, 5, 12, 25 y 50 segundos. El tope de ~1 minuto NO es
 * arbitrario: pasado ese rato el cajero ya le cantó el pedido a la cocina, y un papel que
 * brota después hace que el barista lo prepare dos veces.
 */
object PoliticaDeReintento {

    /** Espera ANTES de cada reintento. El intento #1 es inmediato y no aparece aquí. */
    private val ESPERAS_MS = listOf(2_000L, 3_000L, 7_000L, 13_000L, 25_000L)

    val INTENTOS_MAXIMOS: Int = ESPERAS_MS.size + 1

    fun siguientePaso(intentosYaHechos: Int): PasoDeReintento {
        if (intentosYaHechos < 1 || intentosYaHechos >= INTENTOS_MAXIMOS) return PasoDeReintento.Rendirse
        return PasoDeReintento.Esperar(ESPERAS_MS[intentosYaHechos - 1])
    }
}

sealed interface PasoDeReintento {
    data class Esperar(val esperaMs: Long) : PasoDeReintento
    data object Rendirse : PasoDeReintento
}
