package com.avoqado.pos.onlineorders

import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.presentation.TextosPedidosEnLinea
import com.avoqado.pos.onlineorders.presentation.marcaDelPedido
import com.avoqado.pos.onlineorders.presentation.sePuedeEntregar
import com.avoqado.pos.onlineorders.presentation.textoDeDinero
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D661 · R2-2 (Ruling 36): un pedido que se está devolviendo, ya devuelto o cerrado como «No lo recogió» nunca se ve «Pagado» ni ofrece
 * «Entregado». Precedencia: Devuelto > Devolviendo… > No lo recogió. Sin marcas (o con un SERVIDOR viejo) todo queda como hoy.
 */
class MarcasDeDevolucionTest {

    private val t = Instant.parse("2026-11-21T10:00:05Z")

    /** Un pedido «No, entran solos» ya cobrado con tarjeta, en «Por entregar». */
    private val pagado = OnlineOrder(
        orderId = "o1", onlineOrderId = "oo1", orderNumber = "T-0042", status = OnlineOrderStatus.ACCEPTED,
        paymentMode = "CARD_ONLINE", paymentStatus = "PAID", customerName = "Ana N.", customerPhone = null, note = null,
        createdAt = Instant.parse("2026-11-21T09:00:00Z"), acceptDeadlineAt = null, estimatedReadyAt = null,
        totalCents = 25050, remainingCents = 0, needsPrint = false, items = emptyList(), cardChargeStatus = "CAPTURED",
        requiresAcceptance = false,
    )

    @Test
    fun `R2-2 an order being refunded says Devolviendo and offers no Entregado`() {
        val p = pagado.copy(refundRequestedAt = t)
        assertEquals("Devolviendo…", marcaDelPedido(p))
        assertEquals(TextosPedidosEnLinea.DEVOLVIENDO, textoDeDinero(p))
        assertFalse(sePuedeEntregar(p))
    }

    @Test
    fun `R2-2 a refunded order says Devuelto, over Devolviendo and over No lo recogio`() {
        val p = pagado.copy(status = OnlineOrderStatus.COMPLETED, refundRequestedAt = t, refundedAt = t, notPickedUpAt = t)
        assertEquals("Devuelto", marcaDelPedido(p))
        assertEquals(TextosPedidosEnLinea.DEVUELTO, textoDeDinero(p))
        assertFalse(sePuedeEntregar(p))
        // Devolviendo gana sobre «No lo recogió» (la clienta reclamó después del cierre).
        assertEquals("Devolviendo…", marcaDelPedido(pagado.copy(refundRequestedAt = t, notPickedUpAt = t)))
    }

    @Test
    fun `R2-2 an order closed at the end of the day says No lo recogio`() {
        val p = pagado.copy(status = OnlineOrderStatus.COMPLETED, notPickedUpAt = t)
        assertEquals("No lo recogió", marcaDelPedido(p))
        assertEquals(TextosPedidosEnLinea.NO_LO_RECOGIO, textoDeDinero(p))
        assertFalse(sePuedeEntregar(p))
    }

    @Test
    fun `R2-2 without marks nothing changes`() {
        assertNull(marcaDelPedido(pagado))
        assertTrue(sePuedeEntregar(pagado))
        assertEquals(TextosPedidosEnLinea.PAGADO_CON_TARJETA, textoDeDinero(pagado))
    }
}
