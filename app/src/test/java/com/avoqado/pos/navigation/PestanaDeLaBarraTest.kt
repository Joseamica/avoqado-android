package com.avoqado.pos.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class PestanaDeLaBarraTest {

    private val visibles = listOf(MainTab.CALENDAR, MainTab.CHECKOUT, MainTab.MORE)

    @Test
    fun `P2 una pantalla abierta desde Mas deja marcada Mas y no la pestana de inicio`() {
        // «Lista de espera» no es de ninguna pestaña: antes caía en la de inicio (Calendario).
        assertEquals(MainTab.MORE, PestanaDeLaBarra.marcada(deLaRuta = null, ultima = MainTab.MORE, visibles = visibles, inicio = MainTab.CALENDAR))
    }

    @Test
    fun `la pestana de la ruta actual siempre gana`() {
        assertEquals(MainTab.CHECKOUT, PestanaDeLaBarra.marcada(MainTab.CHECKOUT, ultima = MainTab.MORE, visibles = visibles, inicio = MainTab.CALENDAR))
    }

    @Test
    fun `si la ultima pestana ya no es visible cae en la de inicio`() {
        assertEquals(MainTab.CALENDAR, PestanaDeLaBarra.marcada(null, ultima = MainTab.TABLES, visibles = visibles, inicio = MainTab.CALENDAR))
    }

    @Test
    fun `P2 tocar Mas con la lista de espera encima regresa a la raiz de Mas`() {
        assertEquals(PestanaDeLaBarra.AlTocar.VOLVER_A_SU_RAIZ, PestanaDeLaBarra.alTocar(MainTab.MORE, marcada = MainTab.MORE, enUnaPestana = false))
    }

    @Test
    fun `tocar otra pestana o la misma estando en su raiz navega como siempre`() {
        assertEquals(PestanaDeLaBarra.AlTocar.NAVEGAR, PestanaDeLaBarra.alTocar(MainTab.CHECKOUT, marcada = MainTab.MORE, enUnaPestana = false))
        assertEquals(PestanaDeLaBarra.AlTocar.NAVEGAR, PestanaDeLaBarra.alTocar(MainTab.MORE, marcada = MainTab.MORE, enUnaPestana = true))
    }
}
