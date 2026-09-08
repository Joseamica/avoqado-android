package com.avoqado.pos.printing.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PoliticaDeReintentoTest {

    @Test
    fun `el primer reintento espera 2 segundos`() {
        assertEquals(PasoDeReintento.Esperar(2_000L), PoliticaDeReintento.siguientePaso(1))
    }

    @Test
    fun `las esperas van creciendo y el ultimo intento cae cerca del minuto`() {
        val esperas = (1..5).map { (PoliticaDeReintento.siguientePaso(it) as PasoDeReintento.Esperar).esperaMs }
        assertEquals(listOf(2_000L, 3_000L, 7_000L, 13_000L, 25_000L), esperas)
        // 6 intentos en total; el ultimo arranca a los 50 s del primero.
        assertEquals(50_000L, esperas.sum())
    }

    @Test
    fun `tras seis intentos se rinde`() {
        assertEquals(PasoDeReintento.Rendirse, PoliticaDeReintento.siguientePaso(6))
        assertEquals(6, PoliticaDeReintento.INTENTOS_MAXIMOS)
    }

    @Test
    fun `un conteo absurdo tambien se rinde en vez de reventar`() {
        assertEquals(PasoDeReintento.Rendirse, PoliticaDeReintento.siguientePaso(0))
        assertEquals(PasoDeReintento.Rendirse, PoliticaDeReintento.siguientePaso(-3))
        assertEquals(PasoDeReintento.Rendirse, PoliticaDeReintento.siguientePaso(99))
    }
}
