package com.avoqado.pos.kds.presentation

import com.avoqado.pos.kds.data.ComandaImpresa
import org.junit.Assert.assertEquals
import org.junit.Test

class BannerDeRepartoTest {

    private fun aviso(id: String, folio: String, createdAt: Long, salio: Boolean = true, proveedor: String? = null) =
        ComandaImpresa(kdsId = id, orderNumber = folio, createdAt = createdAt, salioEnPapel = salio, proveedor = proveedor)

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

    @Test
    fun `dice el proveedor del pedido mas reciente aunque haya dos canales`() {
        val avisos = listOf(aviso("k1", "101", 1, proveedor = "RAPPI"), aviso("k2", "12", 2, proveedor = "UBER_EATS"))
        assertEquals("Nuevo pedido de Uber Eats #12 (2 nuevos)", textoDeLaFranjaDeReparto(avisos, "delivery"))
    }

    @Test
    fun `sin proveedor en el aviso usa el de los canales`() {
        assertEquals("Nuevo pedido de Rappi #101", textoDeLaFranjaDeReparto(listOf(aviso("k1", "101", 1)), "Rappi"))
    }
}
