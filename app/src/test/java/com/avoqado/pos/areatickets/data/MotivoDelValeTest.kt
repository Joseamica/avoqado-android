package com.avoqado.pos.areatickets.data

import com.avoqado.pos.areatickets.presentation.intentoDeVale
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D4 (tarjeta hz:e82938e1:D4, 18-nov-2026): el motivo del descuento que eligió la caja viaja en los renglones del VALE y en los
 * productos SUELTOS del cobro de vales, con la misma regla que Mostrador (`OrderRepository`): nunca en cortesía ni en blanco, y el
 * comentario sólo con motivo. Antes no viajaba y «Descuentos» del DASHBOARD los mostraba «Sin motivo».
 */
class MotivoDelValeTest {
    private fun linea(
        id: String,
        motivo: String? = null,
        comentario: String? = null,
        cortesia: Boolean = false,
        descuento: String? = "d-10",
    ) = CartItem(
        id = id,
        type = CartItemType.ProductItem("p-$id"),
        name = "Jamón",
        unitPrice = 10000,
        quantity = 1,
        itemDiscountId = descuento,
        isCortesia = cortesia,
        discountReason = motivo,
        discountReasonComment = comentario,
    )

    @Test
    fun `el vale manda el motivo y el comentario de cada renglon`() {
        val intento = intentoDeVale(
            CartState(
                items = listOf(
                    linea("a", motivo = "Cliente frecuente", comentario = "Viene diario"),
                    linea("b"),
                    linea("c", motivo = "  ", comentario = "sin motivo no viaja"),
                ),
            ),
            ahora = 1,
        )
        val (a, b, c) = intento.lineas
        assertEquals("Cliente frecuente", a.discountReason)
        assertEquals("Viene diario", a.discountReasonComment)
        assertEquals("d-10", a.discountId)
        assertNull(b.discountReason)
        assertNull(b.discountReasonComment)
        assertNull(c.discountReason)
        assertNull(c.discountReasonComment)
    }

    @Test
    fun `una cortesia nunca manda motivo, como Mostrador`() {
        val cortesia = linea("x", motivo = "Cumpleaños", comentario = "Mesa 4", cortesia = true)
        assertNull(cortesia.motivoQueViaja())
        assertNull(cortesia.comentarioQueViaja())
    }

    @Test
    fun `el motivo no cambia la misma mercancia (la copia guardada del carrito no lo trae)`() {
        val conMotivo = IntentoDeVale(
            lineas = listOf(IssueAreaTicketLineRequest("1", "p", "1", discountId = "d", discountReason = "Frecuente", discountReasonComment = "c")),
        )
        val sinMotivo = listOf(IssueAreaTicketLineRequest("2", "p", "1", discountId = "d"))
        assertTrue(conMotivo.mismaMercanciaQue(sinMotivo))
    }

    @Test
    fun `un vale guardado antes del cambio se lee sin motivo, y uno nuevo lo guarda`() {
        val json = Json { ignoreUnknownKeys = true }
        val viejo = json.decodeFromString<IntentoDeVale>(
            """{"lineas":[{"clientLineId":"1","productId":"p","quantity":"1","discountId":"d"}],"hora":5}""",
        )
        assertNull(viejo.lineas.single().discountReason)
        val nuevo = IntentoDeVale(lineas = listOf(IssueAreaTicketLineRequest("1", "p", "1", discountId = "d", discountReason = "Frecuente")))
        assertEquals("Frecuente", json.decodeFromString<IntentoDeVale>(json.encodeToString(nuevo)).lineas.single().discountReason)
    }

    @Test
    fun `el suelto del cobro de vales lleva el motivo al serializarse`() {
        val item = NormalCheckoutItem(productId = "p", quantity = 1, discountId = "d", discountReason = "Frecuente", discountReasonComment = "c")
        val texto = Json.encodeToString(item)
        assertTrue(texto, texto.contains("\"discountReason\":\"Frecuente\""))
        assertTrue(texto, texto.contains("\"discountReasonComment\":\"c\""))
    }

    @Test
    fun `un suelto con ajuste de precio no manda el motivo (el ajuste no viaja en el cobro de vales)`() {
        val conAjuste = linea("s", motivo = "Producto dañado", comentario = "Bajé a $80").also { it.priceAdjustment = 8000 }
        assertNull(conAjuste.motivoDelSuelto())
        assertNull(conAjuste.comentarioDelSuelto())
        val sinAjuste = linea("t", motivo = "Cliente frecuente", comentario = "Viene diario")
        assertEquals("Cliente frecuente", sinAjuste.motivoDelSuelto())
        assertEquals("Viene diario", sinAjuste.comentarioDelSuelto())
    }
}
