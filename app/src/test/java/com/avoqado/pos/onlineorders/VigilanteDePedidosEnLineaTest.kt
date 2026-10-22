package com.avoqado.pos.onlineorders

import androidx.lifecycle.LifecycleOwner
import com.avoqado.pos.onlineorders.data.VigilanteDePedidosEnLinea
import com.avoqado.pos.onlineorders.domain.Inbox
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task A2: el vigilante global de «Pedidos en línea». Reloj virtual y repositorio falso: nada de red ni de tiempo real.
 * Cada prueba detiene su ciclo con `onStop` (un `delay` vivo hace girar a `runTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VigilanteDePedidosEnLineaTest {

    private val owner = mockk<LifecycleOwner>(relaxed = true)

    private fun pedido(id: String, folio: String = id) = OnlineOrder(
        orderId = id, onlineOrderId = "o-$id", orderNumber = folio, status = OnlineOrderStatus.PENDING_ACCEPTANCE,
        paymentMode = "PAY_AT_PICKUP", paymentStatus = "PENDING", customerName = null, customerPhone = null, note = null,
        createdAt = Instant.parse("2026-10-22T10:00:00Z"), acceptDeadlineAt = null, estimatedReadyAt = null,
        totalCents = 10000, remainingCents = 10000, needsPrint = false, items = emptyList(),
    )

    private fun inbox(pendientes: List<OnlineOrder> = emptyList(), active: Int = 0, storeOff: Boolean = false) = Inbox(
        serverTime = Instant.parse("2026-10-22T10:00:00Z"), pending = pendientes.size, active = active, done = 0,
        orders = pendientes, storeOff = storeOff, truncated = false, nextCursor = null,
    )

    /** El repositorio falso: cada llamada se anota (estado, comoBandeja) y responde lo que diga [respuesta]. */
    private class Falso(var respuesta: Result<Inbox>) {
        val llamadas = mutableListOf<Pair<String, Boolean>>()
        suspend fun consultar(status: String, comoBandeja: Boolean): Result<Inbox> {
            llamadas += status to comoBandeja
            return respuesta
        }
    }

    private class Fixture(
        val falso: Falso,
        var puedeLeer: Boolean = true,
        var tienePlan: Boolean = true,
    ) {
        var sonidos = 0
        lateinit var vigilante: VigilanteDePedidosEnLinea
    }

    private fun TestScope.armar(
        respuesta: Result<Inbox>,
        puedeLeer: Boolean = true,
        tienePlan: Boolean = true,
    ): Fixture {
        val f = Fixture(Falso(respuesta), puedeLeer, tienePlan)
        f.vigilante = VigilanteDePedidosEnLinea(
            consultar = f.falso::consultar,
            puedeLeer = { f.puedeLeer },
            tienePlan = { f.tienePlan },
            sonar = { f.sonidos++ },
            scope = backgroundScope,
        )
        return f
    }

    @Test
    fun `polls every 10s with background header and no tray header`() = runTest {
        val f = armar(Result.success(inbox()))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals("consulta al arrancar", 1, f.falso.llamadas.size)
        advanceTimeBy(9_999); runCurrent()
        assertEquals("antes de 10 s no vuelve a consultar", 1, f.falso.llamadas.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, f.falso.llamadas.size)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(3, f.falso.llamadas.size)
        // El vigilante nunca manda la cabecera de bandeja (comoBandeja = false); la de fondo la pone el repositorio siempre.
        assertTrue(f.falso.llamadas.all { it == "pending" to false })
        f.vigilante.onStop(owner)
    }

    @Test
    fun `backs off to 60s when store off and nothing pending`() = runTest {
        val f = armar(Result.success(inbox(storeOff = true)))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals(1, f.falso.llamadas.size)
        advanceTimeBy(59_999); runCurrent()
        assertEquals("con la tienda apagada y sin pendientes no martilla cada 10 s", 1, f.falso.llamadas.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, f.falso.llamadas.size)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `sound repeats every 4s while pending and stops when list empties`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals("suena en cuanto llega el pedido", 1, f.sonidos)
        advanceTimeBy(3_999); runCurrent()
        assertEquals(1, f.sonidos)
        advanceTimeBy(1); runCurrent()
        assertEquals("a los 4 s vuelve a sonar", 2, f.sonidos)
        advanceTimeBy(4_000); runCurrent()
        assertEquals(3, f.sonidos)
        // Alguien acepta: el siguiente sondeo (a los 10 s) trae la lista vacía y el sonido para ahí mismo.
        f.falso.respuesta = Result.success(inbox())
        advanceTimeBy(2_000); runCurrent() // t = 10 s
        assertTrue(f.vigilante.pendientes.value.isEmpty())
        val alVaciar = f.sonidos
        advanceTimeBy(60_000); runCurrent()
        assertEquals("con la lista vacía no suena más", alVaciar, f.sonidos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `silenciarHastaNuevo stops the sound until a new order arrives`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.vigilante.onStart(owner)
        runCurrent()
        f.vigilante.silenciarHastaNuevo()
        runCurrent()
        val antes = f.sonidos
        advanceTimeBy(30_000); runCurrent()
        assertEquals("silenciado: el mismo pedido ya no suena", antes, f.sonidos)
        f.falso.respuesta = Result.success(inbox(listOf(pedido("a"), pedido("b"))))
        advanceTimeBy(10_000); runCurrent()
        assertTrue("un pedido nuevo vuelve a sonar", f.sonidos > antes)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `stops on onStop and resumes on onStart`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.vigilante.onStart(owner)
        runCurrent()
        f.vigilante.onStop(owner)
        val consultas = f.falso.llamadas.size
        val sonidos = f.sonidos
        advanceTimeBy(120_000); runCurrent()
        assertEquals("en segundo plano no consulta", consultas, f.falso.llamadas.size)
        assertEquals("en segundo plano no suena", sonidos, f.sonidos)
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals("al volver consulta de inmediato", consultas + 1, f.falso.llamadas.size)
        assertTrue(f.sonidos > sonidos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `does not run without orders read`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))), puedeLeer = false)
        f.vigilante.onStart(owner)
        runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals("sin orders:read no hay ni una consulta", 0, f.falso.llamadas.size)
        assertEquals(0, f.sonidos)
        assertTrue(f.vigilante.pendientes.value.isEmpty())
        // Si le dan el permiso, empieza solo.
        f.puedeLeer = true
        advanceTimeBy(10_000); runCurrent()
        assertTrue(f.falso.llamadas.isNotEmpty())
        f.vigilante.onStop(owner)
    }

    @Test
    fun `keeps working without plan while orders exist and slows to 5 min without plan and without orders`() = runTest {
        // Sin plan pero con un pedido abierto (en preparación): sigue cada 10 s (R14).
        val f = armar(Result.success(inbox(active = 1)), tienePlan = false)
        f.vigilante.onStart(owner)
        runCurrent()
        assertTrue("hay pedidos abiertos", f.vigilante.hayPedidosAbiertos.value)
        advanceTimeBy(10_000); runCurrent()
        assertEquals("sin plan pero con pedidos abiertos sondea cada 10 s", 2, f.falso.llamadas.size)

        // Se cierran todos: sin plan y sin pedidos abiertos baja a cada 5 min.
        f.falso.respuesta = Result.success(inbox())
        advanceTimeBy(10_000); runCurrent()
        assertFalse(f.vigilante.hayPedidosAbiertos.value)
        val n = f.falso.llamadas.size
        advanceTimeBy(299_999); runCurrent()
        assertEquals("no consulta antes de 5 min", n, f.falso.llamadas.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(n + 1, f.falso.llamadas.size)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `network failure sets sinConexion and keeps last list`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.vigilante.onStart(owner)
        runCurrent()
        assertFalse(f.vigilante.sinConexion.value)
        f.falso.respuesta = Result.failure(IOException("sin red"))
        advanceTimeBy(10_000); runCurrent()
        assertTrue(f.vigilante.sinConexion.value)
        assertEquals("conserva la última lista", listOf("a"), f.vigilante.pendientes.value.map { it.orderId })
        assertTrue("y sigue contando pedidos abiertos", f.vigilante.hayPedidosAbiertos.value)
        f.falso.respuesta = Result.success(inbox(listOf(pedido("a"))))
        advanceTimeBy(10_000); runCurrent()
        assertFalse("al volver la red se apaga el aviso", f.vigilante.sinConexion.value)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `expired order disappears and sound stops`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a"), pedido("b")))))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals(2, f.vigilante.pendientes.value.size)
        // El pedido «a» vence y «b» lo cancela el comensal: el siguiente sondeo ya no los trae.
        f.falso.respuesta = Result.success(inbox())
        advanceTimeBy(10_000); runCurrent()
        assertTrue(f.vigilante.pendientes.value.isEmpty())
        val sonidos = f.sonidos
        advanceTimeBy(30_000); runCurrent()
        assertEquals("ya no suena", sonidos, f.sonidos)
        f.vigilante.onStop(owner)
    }
}
