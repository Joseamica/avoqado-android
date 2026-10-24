package com.avoqado.pos.onlineorders

import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.presentation.SeccionesBandeja
import com.avoqado.pos.onlineorders.presentation.TipoDeDialogo
import com.avoqado.pos.onlineorders.presentation.debeQuedarseArriba
import com.avoqado.pos.onlineorders.presentation.pedidoDelDialogo
import com.avoqado.pos.onlineorders.presentation.primeraClaveDeLaLista
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Un pedido nuevo en «Por aceptar» no debe quedar ARRIBA de la ventana (W77Q9): decisión pura de «quedarse arriba» y de cuándo un
 * diálogo abierto deja de valer. Lo que se ve en la lista de verdad lo prueba `BandejaDeUnPedidoNuevoEnLaEscenaTest` (escritorio).
 */
class BandejaDeUnPedidoNuevoTest {

    private fun pedido(id: String, pago: String = "PENDING", total: Long = 20000, restante: Long = 20000) = OnlineOrder(
        orderId = id, onlineOrderId = "o-$id", orderNumber = id, status = OnlineOrderStatus.ACCEPTED,
        paymentMode = "PAY_AT_PICKUP", paymentStatus = pago, customerName = null, customerPhone = null, note = null,
        createdAt = Instant.parse("2026-10-22T10:00:00Z"), acceptDeadlineAt = null, estimatedReadyAt = null,
        totalCents = total, remainingCents = restante, needsPrint = false, items = emptyList(),
    )

    @Test
    fun `hasta arriba y la primera fila cambia - se pide quedarse arriba`() {
        assertTrue(debeQuedarseArriba(estabaArriba = true, primeraClaveAntes = "h-En preparación", primeraClaveAhora = "h-Por aceptar"))
    }

    @Test
    fun `hasta arriba y la primera fila no cambia - no se pide nada`() {
        assertFalse(debeQuedarseArriba(true, "h-En preparación", "h-En preparación"))
    }

    @Test
    fun `leyendo mas abajo nunca se pide quedarse arriba`() {
        assertFalse(debeQuedarseArriba(estabaArriba = false, primeraClaveAntes = "h-En preparación", primeraClaveAhora = "h-Por aceptar"))
        // y si el pendiente desaparece, tampoco: la lectura sigue anclada por clave
        assertFalse(debeQuedarseArriba(false, "h-Por aceptar", "h-En preparación"))
    }

    @Test
    fun `sin lista anterior no hay nada que sostener`() {
        assertFalse(debeQuedarseArriba(estabaArriba = true, primeraClaveAntes = null, primeraClaveAhora = "h-Por aceptar"))
    }

    @Test
    fun `la primera clave es la cabecera de la primera seccion con pedidos`() {
        val p = pedido("a")
        assertEquals("h-Por aceptar", primeraClaveDeLaLista(SeccionesBandeja(porAceptar = listOf(p), enPreparacion = listOf(p))))
        assertEquals("h-En preparación", primeraClaveDeLaLista(SeccionesBandeja(enPreparacion = listOf(p), hoy = listOf(p))))
        assertEquals("h-Listos", primeraClaveDeLaLista(SeccionesBandeja(listos = listOf(p))))
        assertEquals("h-Hoy", primeraClaveDeLaLista(SeccionesBandeja(hoy = listOf(p))))
        assertNull(primeraClaveDeLaLista(SeccionesBandeja()))
    }

    @Test
    fun `aceptar y rechazar valen solo mientras el pedido siga en Por aceptar`() {
        val p = pedido("a")
        val enPendientes = SeccionesBandeja(porAceptar = listOf(p))
        assertNotNull(pedidoDelDialogo("a", TipoDeDialogo.ACEPTAR, enPendientes))
        assertNotNull(pedidoDelDialogo("a", TipoDeDialogo.RECHAZAR, enPendientes))
        val yaAceptado = SeccionesBandeja(enPreparacion = listOf(p))
        assertNull(pedidoDelDialogo("a", TipoDeDialogo.ACEPTAR, yaAceptado))
        assertNull(pedidoDelDialogo("a", TipoDeDialogo.RECHAZAR, yaAceptado))
        assertNull(pedidoDelDialogo("a", TipoDeDialogo.ACEPTAR, SeccionesBandeja()))
    }

    @Test
    fun `cancelar vale en preparacion o listos y solo sin dinero`() {
        val sinDinero = pedido("a")
        assertNotNull(pedidoDelDialogo("a", TipoDeDialogo.CANCELAR, SeccionesBandeja(enPreparacion = listOf(sinDinero))))
        assertNotNull(pedidoDelDialogo("a", TipoDeDialogo.CANCELAR, SeccionesBandeja(listos = listOf(sinDinero))))
        // abonado: ya hay dinero
        val conAbono = pedido("a", pago = "PARTIAL", restante = 5000)
        assertNull(pedidoDelDialogo("a", TipoDeDialogo.CANCELAR, SeccionesBandeja(enPreparacion = listOf(conAbono))))
        // otra sección o ya no está
        assertNull(pedidoDelDialogo("a", TipoDeDialogo.CANCELAR, SeccionesBandeja(porAceptar = listOf(sinDinero))))
        assertNull(pedidoDelDialogo("a", TipoDeDialogo.CANCELAR, SeccionesBandeja(hoy = listOf(sinDinero))))
        assertNull(pedidoDelDialogo("a", TipoDeDialogo.CANCELAR, SeccionesBandeja()))
    }

    @Test
    fun `el dialogo es del pedido pedido y no de otro`() {
        val a = pedido("a"); val b = pedido("b")
        assertEquals("b", pedidoDelDialogo("b", TipoDeDialogo.ACEPTAR, SeccionesBandeja(porAceptar = listOf(a, b)))?.orderId)
    }
}
