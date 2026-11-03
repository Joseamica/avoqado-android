package com.avoqado.pos.onlineorders

import com.avoqado.pos.onlineorders.data.encabezadoDeComanda
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.presentation.SeccionesBandeja
import com.avoqado.pos.onlineorders.presentation.formatoPesos
import com.avoqado.pos.onlineorders.presentation.TextosPedidosEnLinea
import com.avoqado.pos.onlineorders.presentation.TipoDeDialogo
import com.avoqado.pos.onlineorders.presentation.pedidoDelDialogo
import com.avoqado.pos.onlineorders.presentation.sePuedeAceptar
import com.avoqado.pos.onlineorders.presentation.sePuedeCobrar
import com.avoqado.pos.onlineorders.presentation.textoDeDinero
import com.avoqado.pos.printing.data.model.MARCA_NEGRITA_ENCABEZADO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Pedidos en línea fase 2: la tarjeta en la bandeja de ANDROID/WINDOWS (textos del brief, literales). */
class TarjetaEnLineaTest {
    private fun pedido(
        modo: String = "CARD_ONLINE", status: OnlineOrderStatus = OnlineOrderStatus.PENDING_ACCEPTANCE, pago: String = "PENDING",
        restante: Long = 20000, cardStatus: String? = "AUTHORIZED", cobro: String? = null, motivo: String? = null,
    ) = OnlineOrder(
        orderId = "p1", onlineOrderId = "o-p1", orderNumber = "W-17", status = status, paymentMode = modo, paymentStatus = pago,
        customerName = "Ana", customerPhone = null, note = null, createdAt = Instant.parse("2026-11-02T10:00:00Z"),
        acceptDeadlineAt = null, estimatedReadyAt = null, totalCents = 20000, remainingCents = restante, needsPrint = true,
        items = emptyList(), cardStatus = cardStatus, cardChargeStatus = cobro, rejectReason = motivo,
    )

    @Test fun `the five money texts of a card order`() {
        assertEquals("Con tarjeta: se cobra al aceptar", textoDeDinero(pedido()))
        assertEquals("Confirmando el pago…", textoDeDinero(pedido(cardStatus = "CONFIRMING")))
        assertEquals("Pagado con tarjeta", textoDeDinero(pedido(status = OnlineOrderStatus.ACCEPTED, pago = "PAID", restante = 0, cardStatus = null, cobro = "CAPTURED")))
        assertEquals("Pagado con tarjeta", textoDeDinero(pedido(status = OnlineOrderStatus.COMPLETED, restante = 0, cardStatus = null, cobro = "CAPTURED")))
        assertEquals("Tarjeta cobrada: devuélvela desde el panel", textoDeDinero(pedido(status = OnlineOrderStatus.REJECTED, pago = "PAID", restante = 0, cobro = "CAPTURED")))
        assertEquals("No se cobró la tarjeta", textoDeDinero(pedido(status = OnlineOrderStatus.EXPIRED, cardStatus = null, cobro = "RELEASED")))
        assertEquals("No se cobró la tarjeta", textoDeDinero(pedido(status = OnlineOrderStatus.CANCELLED_BY_CUSTOMER, cobro = "ABANDONED")))
        assertEquals("No se cobró la tarjeta", textoDeDinero(pedido(status = OnlineOrderStatus.REJECTED, cobro = "CAPTURE_FAILED")))
    }

    @Test fun `a closed card order with the charge in doubt never says it was not charged`() {
        for (c in listOf("RELEASING", "AUTHORIZED", "CAPTURING", "ALGO_NUEVO", null))
            assertEquals("Liberando la tarjeta…", textoDeDinero(pedido(status = OnlineOrderStatus.REJECTED, cobro = c)))
    }

    @Test fun `paid by another path says Pagado without mentioning the card`() {
        // El servidor acepta sin capturar y suelta la tarjeta si la cuenta ya venía pagada (capture.service.ts:94).
        for (c in listOf("RELEASING", "RELEASED", "AUTHORIZED", "OTRO", null))
            assertEquals("Pagado", textoDeDinero(pedido(status = OnlineOrderStatus.ACCEPTED, pago = "PAID", restante = 0, cardStatus = null, cobro = c)))
    }

    @Test fun `pay at pickup texts do not change`() {
        assertEquals("Cobrar al recoger ${formatoPesos(20000)}", textoDeDinero(pedido(modo = "PAY_AT_PICKUP", cardStatus = null)))
        assertEquals("Pagado", textoDeDinero(pedido(modo = "PAY_AT_PICKUP", pago = "PAID", restante = 0, cardStatus = null)))
    }

    @Test fun `Cobrar is never offered on a card order`() {
        assertFalse(sePuedeCobrar(pedido(status = OnlineOrderStatus.ACCEPTED, restante = 20000), permiso = true))
        assertFalse(sePuedeCobrar(pedido(status = OnlineOrderStatus.READY, pago = "PAID", restante = 0), permiso = true))
        assertTrue(sePuedeCobrar(pedido(modo = "PAY_AT_PICKUP", status = OnlineOrderStatus.ACCEPTED, cardStatus = null), permiso = true))
        assertFalse(sePuedeCobrar(pedido(modo = "PAY_AT_PICKUP", status = OnlineOrderStatus.ACCEPTED, cardStatus = null), permiso = false))
    }

    @Test fun `Aceptar is off while CONFIRMING, also in the dialog`() {
        val confirmando = pedido(cardStatus = "CONFIRMING")
        val apartada = pedido()
        assertFalse(sePuedeAceptar(confirmando)); assertTrue(sePuedeAceptar(apartada))
        val secciones = SeccionesBandeja(porAceptar = listOf(confirmando))
        assertNull(pedidoDelDialogo("p1", TipoDeDialogo.ACEPTAR, secciones))
        assertEquals(confirmando, pedidoDelDialogo("p1", TipoDeDialogo.RECHAZAR, secciones))
        assertEquals(apartada, pedidoDelDialogo("p1", TipoDeDialogo.ACEPTAR, SeccionesBandeja(porAceptar = listOf(apartada))))
    }

    @Test fun `the ticket says PAGADO CON TARJETA only if the charge was captured`() {
        fun comanda(c: String?) = encabezadoDeComanda(pedido(status = OnlineOrderStatus.ACCEPTED, pago = "PAID", restante = 0, cobro = c))
        assertEquals(listOf("Ana", "${MARCA_NEGRITA_ENCABEZADO}PAGADO CON TARJETA"), comanda("CAPTURED"))
        // La cuenta se pagó por otro camino y la tarjeta se soltó: el encabezado normal, sin mencionarla.
        for (c in listOf("RELEASING", "RELEASED", "OTRO", null)) assertEquals(listOf("Ana", "${MARCA_NEGRITA_ENCABEZADO}Pagado"), comanda(c))
        assertTrue(encabezadoDeComanda(pedido(modo = "PAY_AT_PICKUP", cardStatus = null)).none { it.contains("TARJETA") })
    }

    @Test fun `the received reject reason texts`() {
        assertEquals("No se pudo cobrar la tarjeta", TextosPedidosEnLinea.textoDelMotivo("PAYMENT_FAILED"))
        assertEquals("Sin ingredientes", TextosPedidosEnLinea.textoDelMotivo("OUT_OF_ITEMS"))
        assertEquals("Cerrado", TextosPedidosEnLinea.textoDelMotivo("CLOSED"))
        assertEquals("Muy ocupados", TextosPedidosEnLinea.textoDelMotivo("TOO_BUSY"))
        assertEquals("Otro", TextosPedidosEnLinea.textoDelMotivo("OTHER"))
        assertNull(TextosPedidosEnLinea.textoDelMotivo(null))
        assertNull(TextosPedidosEnLinea.textoDelMotivo("ALGO_NUEVO"))
    }
}
