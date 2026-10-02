package com.avoqado.pos.orders.data.model

import com.avoqado.pos.core.util.Plurales
import org.junit.Assert.assertEquals
import org.junit.Test

class OrderPaymentInfoEtiquetaTest {

    private fun etiqueta(method: String) = OrderPaymentInfo(id = "p1", method = method).methodLabel

    @Test
    fun `P2 una transferencia del server se ve como Transferencia y no BANK_TRANSFER`() {
        assertEquals("Transferencia", etiqueta("BANK_TRANSFER"))
        assertEquals("Transferencia", etiqueta("TRANSFER"))
    }

    @Test
    fun `los demas metodos del enum del server salen en espanol`() {
        assertEquals("Efectivo", etiqueta("CASH"))
        assertEquals("Tarjeta", etiqueta("CREDIT_CARD"))
        assertEquals("Cartera digital", etiqueta("DIGITAL_WALLET"))
        assertEquals("Criptomoneda", etiqueta("CRYPTOCURRENCY"))
        assertEquals("Otro", etiqueta("OTHER"))
    }

    @Test
    fun `P3 un pedido de importe libre sin productos no dice 0 articulos`() {
        assertEquals("Importe libre", Plurales.articulosDelPedido(0))
        assertEquals("1 artículo", Plurales.articulosDelPedido(1))
        assertEquals("3 artículos", Plurales.articulosDelPedido(3))
    }
}
