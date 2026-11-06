package com.avoqado.pos.customers.presentation

import com.avoqado.pos.articles.data.model.CreditPack
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * D15 (hz:433063d4:D15): un paquete que el panel apagó no se ofrece en la cuadrícula ni en «Vender paquete» de la ficha. Antes
 * se cobraba y el SERVIDOR respondía 400 «El paquete no está activo»: dinero cobrado sin entregar.
 */
class PaquetesVendiblesTest {
    @Test
    fun `el apagado no se ofrece, el activo y el sin dato (servidor viejo) si`() {
        val activo = CreditPack(id = "a", active = true)
        val apagado = CreditPack(id = "b", active = false)
        val sinDato = CreditPack(id = "c", active = null)

        assertEquals(listOf("a", "c"), paquetesVendibles(listOf(activo, apagado, sinDato)).map { it.id })
    }
}
