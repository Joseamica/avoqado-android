package com.avoqado.pos.kds.presentation

import com.avoqado.pos.kds.data.ComandaImpresa
import org.junit.Assert.assertEquals
import org.junit.Test

class BannerDeRepartoTest {

    private fun aviso(id: String, folio: String, createdAt: Long, salio: Boolean = true) =
        ComandaImpresa(kdsId = id, orderNumber = folio, createdAt = createdAt, salioEnPapel = salio)

    @Test
    fun `sin avisos no hay texto`() {
        assertEquals("", textoDeLaFranjaDeReparto(emptyList(), "Rappi"))
    }

    @Test
    fun `un pedido impreso`() {
        assertEquals("Nuevo pedido de Rappi #101", textoDeLaFranjaDeReparto(listOf(aviso("k1", "101", 1)), "Rappi"))
    }

    @Test
    fun `varios pedidos dicen el mas reciente y cuantos son`() {
        val avisos = listOf(aviso("k2", "102", 2), aviso("k1", "101", 1))
        assertEquals("Nuevo pedido de Rappi #102 (2 nuevos)", textoDeLaFranjaDeReparto(avisos, "Rappi"))
    }

    @Test
    fun `el que no salio en papel lo dice`() {
        assertEquals(
            "Nuevo pedido de Rappi #101 · no salió en papel, revisa la impresora",
            textoDeLaFranjaDeReparto(listOf(aviso("k1", "101", 1, salio = false)), "Rappi"),
        )
    }
}
