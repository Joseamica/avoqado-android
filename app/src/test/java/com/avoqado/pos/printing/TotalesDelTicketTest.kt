package com.avoqado.pos.printing

import com.avoqado.pos.printing.data.model.TotalesDelPapel
import com.avoqado.pos.printing.data.model.TotalesDelTicket
import org.junit.Assert.assertEquals
import org.junit.Test

/** IVA por producto, plan 7 (regla de cuadre; r1 #3): el papel del cobro sale de lo COBRADO. Espejo de iOS. */
class TotalesDelTicketTest {

    private fun cuadra(t: TotalesDelPapel, propina: Int) = t.subtotal - t.descuento + t.cargo + t.impuesto + propina == t.total

    @Test
    fun `P1 el servidor cobro 99 y el carrito estimaba 98_99 - el descuento absorbe el centavo y el papel cuadra`() {
        val t = TotalesDelTicket.desdeLoCobrado(cobrado = 9900, propina = 0, subtotalBruto = 10000, cargo = 0, impuestoAgregado = 0)
        assertEquals(TotalesDelPapel(subtotal = 10000, descuento = 100, cargo = 0, impuesto = 0, total = 9900), t)
        assertEquals(true, cuadra(t, 0))
    }

    @Test
    fun `P1 lo cobrado supera el bruto - el subtotal pasa a ser lo cobrado, sin descuento negativo`() {
        val t = TotalesDelTicket.desdeLoCobrado(cobrado = 9900, propina = 500, subtotalBruto = 9899, cargo = 0, impuestoAgregado = 0)
        assertEquals(TotalesDelPapel(subtotal = 9900, descuento = 0, cargo = 0, impuesto = 0, total = 10400), t)
        assertEquals(true, cuadra(t, 500))
    }

    @Test
    fun `cargo por servicio en su renglon`() {
        val t = TotalesDelTicket.desdeLoCobrado(cobrado = 11500, propina = 1000, subtotalBruto = 10000, cargo = 1500, impuestoAgregado = 0)
        assertEquals(TotalesDelPapel(subtotal = 10000, descuento = 0, cargo = 1500, impuesto = 0, total = 12500), t)
    }

    @Test
    fun `control - IVA agregado de una venta vieja restaurada (antes de D13) no se cuenta dos veces`() {
        val t = TotalesDelTicket.desdeLoCobrado(cobrado = 11600, propina = 0, subtotalBruto = 10000, cargo = 0, impuestoAgregado = 1600)
        assertEquals(TotalesDelPapel(subtotal = 10000, descuento = 0, cargo = 0, impuesto = 1600, total = 11600), t)
    }

    @Test
    fun `control - un cobro raro menor que cargo mas impuesto cae a un solo renglon, nunca a un subtotal negativo`() {
        val t = TotalesDelTicket.desdeLoCobrado(cobrado = 1000, propina = 0, subtotalBruto = 0, cargo = 1500, impuestoAgregado = 0)
        assertEquals(TotalesDelPapel(subtotal = 1000, descuento = 0, cargo = 0, impuesto = 0, total = 1000), t)
    }
}
