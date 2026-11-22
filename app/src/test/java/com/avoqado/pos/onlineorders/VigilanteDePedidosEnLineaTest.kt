package com.avoqado.pos.onlineorders

import androidx.lifecycle.LifecycleOwner
import com.avoqado.pos.onlineorders.data.VigilanteDePedidosEnLinea
import com.avoqado.pos.onlineorders.data.tarjetasQueAvisan
import com.avoqado.pos.onlineorders.presentation.TextosPedidosEnLinea
import com.avoqado.pos.onlineorders.presentation.textoDelBanner
import com.avoqado.pos.onlineorders.domain.ConteosPorPagar
import com.avoqado.pos.onlineorders.domain.CursoresPorPagar
import com.avoqado.pos.onlineorders.domain.Inbox
import com.avoqado.pos.onlineorders.domain.ListaPorPagar
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.presentation.seccionDeFoliosVisible
import com.avoqado.pos.onlineorders.presentation.numeroDelMenu
import com.avoqado.pos.onlineorders.domain.ComandaPorImprimir
import com.avoqado.pos.onlineorders.domain.TarjetaPorAbrir
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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

    private fun inbox(
        pendientes: List<OnlineOrder> = emptyList(), active: Int = 0, storeOff: Boolean = false, autoPaused: Boolean = false,
    ) = Inbox(
        serverTime = Instant.parse("2026-10-22T10:00:00Z"), pending = pendientes.size, active = active, done = 0,
        orders = pendientes, storeOff = storeOff, truncated = false, nextCursor = null, autoPaused = autoPaused,
    )

    /** El repositorio falso: cada llamada se anota (estado, comoBandeja) y responde lo que diga [respuesta]. */
    private class Falso(var respuesta: Result<Inbox>) {
        val llamadas = mutableListOf<Pair<String, Boolean>>()
        var antes: () -> Unit = {}
        var demora = 0L
        suspend fun consultar(status: String, comoBandeja: Boolean): Result<Inbox> {
            llamadas += status to comoBandeja
            antes()
            if (demora > 0) delay(demora)
            return respuesta
        }
    }

    private class Fixture(
        val falso: Falso,
        var puedeLeer: Boolean = true,
        var tienePlan: Boolean = true,
    ) {
        var sonidos = 0
        var venue: String? = "v1"
        var seVe = true
        /** Ruling 48: una ventana de la caja es la activa (WINDOWS); en ANDROID siempre. */
        var activa = true
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
            venueActual = { f.venue },
            seVe = { f.seVe },
        )
        return f
    }

    @Test
    fun `polls every 10s and with the window in view sends the tray header (D662)`() = runTest {
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
        // D662: con la app a la vista en CUALQUIER pantalla manda la cabecera de bandeja (la tienda de «Mi equipo» sigue abierta).
        assertTrue(f.falso.llamadas.all { it == "pending" to true })
        f.vigilante.onStop(owner)
    }

    @Test
    fun `D662 a minimized window keeps watching but does not keep the store open`() = runTest {
        val f = armar(Result.success(inbox()))
        f.seVe = false
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals("pending" to false, f.falso.llamadas.last())
        f.seVe = true
        advanceTimeBy(10_000); runCurrent()
        assertEquals("al volver a verse, el siguiente sondeo ya late", "pending" to true, f.falso.llamadas.last())
        f.vigilante.onStop(owner)
    }

    @Test
    fun `D662 a poll-now never sends the heartbeat`() = runTest {
        val f = armar(Result.success(inbox()))
        f.vigilante.onStart(owner)
        runCurrent()
        val n = f.falso.llamadas.size
        f.vigilante.sondearYa() // la Bandeja aceptó y pidió un sondeo ya: puede salir después de onStop
        runCurrent()
        assertEquals("pending" to false, f.falso.llamadas[n])
        f.vigilante.onStop(owner)
    }

    @Test
    fun `D662 a store that is OFF does not send the heartbeat after reading it (Codex p1 #4)`() = runTest {
        val f = armar(Result.success(inbox(storeOff = true)))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals("la primera no sabe: late", "pending" to true, f.falso.llamadas.last())
        advanceTimeBy(60_000); runCurrent()
        assertEquals("ya sabe que está apagada: no late", "pending" to false, f.falso.llamadas.last())
        f.vigilante.onStop(owner)
    }

    @Test
    fun `D662 the poll that finds the store turned ON beats right away (Codex p1-r2 #1)`() = runTest {
        val f = armar(Result.success(inbox(storeOff = true)))
        f.vigilante.onStart(owner)
        runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals("pending" to false, f.falso.llamadas.last())
        val n = f.falso.llamadas.size
        f.falso.respuesta = Result.success(inbox(storeOff = false)) // la prenden en el panel
        advanceTimeBy(60_000); runCurrent()
        assertEquals(
            "la consulta que descubre que está prendida no latía; la caja late en ese momento, sin esperar otra vuelta",
            listOf("pending" to false, "pending" to true),
            f.falso.llamadas.drop(n),
        )
        f.vigilante.onStop(owner)
    }

    @Test
    fun `D662 no extra poll when the window was minimized while learning the store is ON (Codex p1-r3 #1)`() = runTest {
        val f = armar(Result.success(inbox(storeOff = true)))
        f.vigilante.onStart(owner)
        runCurrent()
        val n = f.falso.llamadas.size
        f.falso.respuesta = Result.success(inbox(storeOff = false)) // la prenden en el panel…
        f.falso.antes = { f.seVe = false } // …y minimizan la ventana mientras la consulta va en camino
        advanceTimeBy(60_000); runCurrent()
        assertEquals("sin consulta extra: la caja ya no se ve", listOf("pending" to false), f.falso.llamadas.drop(n))
        f.falso.antes = {}
        f.seVe = true // la restauran
        advanceTimeBy(10_000); runCurrent()
        assertEquals("el compás late a los 10 s, como antes", "pending" to true, f.falso.llamadas.last())
        f.vigilante.onStop(owner)
    }

    @Test
    fun `D662 several poll-nows in a row queue at most one ahead of the heartbeat (Codex p1-r2 #4)`() = runTest {
        val f = armar(Result.success(inbox()))
        f.vigilante.onStart(owner)
        runCurrent()
        val n = f.falso.llamadas.size
        f.falso.demora = 29_000 // red lenta
        repeat(4) { f.vigilante.sondearYa() } // aceptan 4 pedidos seguidos
        runCurrent()
        advanceTimeBy(29_001); runCurrent()
        assertEquals(
            "una sola consulta rápida en fila; el latido del compás sale en cuanto termina",
            listOf("pending" to false, "pending" to true),
            f.falso.llamadas.drop(n),
        )
        f.vigilante.onStop(owner)
    }

    @Test
    fun `D662 a poll-now asked while another runs still reads once more`() = runTest {
        val f = armar(Result.success(inbox()))
        f.vigilante.onStart(owner)
        runCurrent()
        val m = f.falso.llamadas.size // el compás vuelve a los 10 s: aquí sólo cuentan los «sondear ya»
        f.falso.demora = 5_000
        f.vigilante.sondearYa()
        runCurrent() // ya va en camino
        f.vigilante.sondearYa() // otro pedido aceptado mientras tanto: su resultado no puede perderse
        advanceTimeBy(5_001); runCurrent()
        assertEquals("la segunda sale después de la primera", m + 2, f.falso.llamadas.size)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `D662 the long wait ends when the venue changes (Codex p1 #3)`() = runTest {
        val f = armar(Result.success(inbox(storeOff = true)))
        f.vigilante.onStart(owner)
        runCurrent()
        val n = f.falso.llamadas.size
        f.venue = "v2" // la cajera cambia a otra sucursal mientras la caja espera 60 s
        advanceTimeBy(10_000); runCurrent()
        assertEquals("la sucursal nueva se consulta a los 10 s, no al minuto", n + 1, f.falso.llamadas.size)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `D662 without plan but with the store ON it keeps the 10 s heartbeat`() = runTest {
        val f = armar(Result.success(inbox(storeOff = false)), tienePlan = false)
        f.vigilante.onStart(owner)
        runCurrent()
        val n = f.falso.llamadas.size
        advanceTimeBy(10_000); runCurrent()
        assertEquals("con la tienda encendida no baja a 5 min: el plan puede volver desde otro equipo", n + 1, f.falso.llamadas.size)
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
        assertTrue("tras el primer sondeo bueno vuelve a sonar", f.sonidos > sonidos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `an order gone in the first fresh poll after resume never rings`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.vigilante.onStart(owner)
        runCurrent()
        f.vigilante.onStop(owner)
        val sonidos = f.sonidos
        // En segundo plano el pedido venció: el primer sondeo al volver ya viene vacío.
        f.falso.respuesta = Result.success(inbox())
        f.vigilante.onStart(owner)
        runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertEquals("no suena con datos viejos", sonidos, f.sonidos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `a field change on the same order does not add an extra ring`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals(1, f.sonidos)
        advanceTimeBy(4_000); runCurrent()
        assertEquals(2, f.sonidos)
        // Mismo pedido, otro campo (cambia el folio): el sondeo de los 10 s no reinicia el compás de 4 s.
        f.falso.respuesta = Result.success(inbox(listOf(pedido("a", folio = "99"))))
        advanceTimeBy(4_000); runCurrent() // t = 8 s
        assertEquals(3, f.sonidos)
        advanceTimeBy(2_000); runCurrent() // t = 10 s: sondeo con el campo cambiado
        assertEquals("el sondeo no da un timbre extra", 3, f.sonidos)
        advanceTimeBy(2_000); runCurrent() // t = 12 s
        assertEquals("sigue el compás de 4 s", 4, f.sonidos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `a poll that returns after a venue switch is discarded`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.falso.antes = { f.venue = "v2" } // la sucursal cambia mientras el sondeo va en vuelo
        f.vigilante.onStart(owner)
        runCurrent()
        assertTrue("el resultado de la sucursal vieja no se guarda", f.vigilante.pendientes.value.isEmpty())
        assertEquals(0, f.sonidos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `a discarded poll from the old branch also clears its list and open orders state`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")), active = 2)))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals(1, f.vigilante.pendientes.value.size)
        assertTrue(f.vigilante.hayPedidosAbiertos.value)
        // La sucursal cambia mientras el siguiente sondeo va en vuelo: se descarta, y NO queda el banner de la otra sucursal.
        f.falso.antes = { f.venue = "v2" }
        advanceTimeBy(10_000); runCurrent()
        assertTrue("el banner de la sucursal vieja no se queda", f.vigilante.pendientes.value.isEmpty())
        assertFalse("tampoco los pedidos abiertos de la vieja", f.vigilante.hayPedidosAbiertos.value)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `a failed poll after a venue switch does not keep the old branch list`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")), active = 1)))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals(1, f.vigilante.pendientes.value.size)
        f.venue = "v2"
        f.falso.respuesta = Result.failure(IOException("sin red"))
        advanceTimeBy(10_000); runCurrent()
        assertTrue(f.vigilante.pendientes.value.isEmpty())
        assertFalse(f.vigilante.hayPedidosAbiertos.value)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `sondearYa polls once right away and does not start a second loop`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals(1, f.falso.llamadas.size)
        f.falso.respuesta = Result.success(inbox())
        f.vigilante.sondearYa()
        runCurrent()
        assertEquals("un sondeo inmediato", 2, f.falso.llamadas.size)
        assertTrue("y la lista ya se vació", f.vigilante.pendientes.value.isEmpty())
        advanceTimeBy(10_000); runCurrent()
        assertEquals("el compás sigue siendo uno solo, cada 10 s", 3, f.falso.llamadas.size)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `sondearYa without an active cycle does nothing`() = runTest {
        val f = armar(Result.success(inbox()))
        f.vigilante.sondearYa()
        runCurrent()
        assertEquals(0, f.falso.llamadas.size)
    }

    @Test
    fun `double onStart does not double the polls`() = runTest {
        val f = armar(Result.success(inbox()))
        f.vigilante.onStart(owner)
        f.vigilante.onStart(owner)
        runCurrent()
        val alArrancar = f.falso.llamadas.size
        advanceTimeBy(30_000); runCurrent()
        // Tres ciclos más de 10 s: uno por ciclo, no dos.
        assertEquals(alArrancar + 3, f.falso.llamadas.size)
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

        // Se cierran todos: sin plan, sin pedidos abiertos y con la tienda apagada baja a cada 5 min.
        f.falso.respuesta = Result.success(inbox(storeOff = true))
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
    fun `non network failure keeps last list and does not claim sinConexion`() = runTest {
        val f = armar(Result.success(inbox(listOf(pedido("a")))))
        f.vigilante.onStart(owner)
        runCurrent()
        f.falso.respuesta = Result.failure(IllegalStateException("403"))
        advanceTimeBy(10_000); runCurrent()
        assertFalse("un 403 o un JSON roto no es «sin conexión»", f.vigilante.sinConexion.value)
        assertEquals(listOf("a"), f.vigilante.pendientes.value.map { it.orderId })
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

    // MARK: - Tienda híbrida (A2): los folios por pagar

    private fun folioPorPagar(id: String) = pedido(id, folio = "W${id.uppercase()}AAA".take(5)).copy(requiresAcceptance = false)

    private fun listaFolios(n: Int, toPay: Int = n, toDeliver: Int = 0, hayMas: Boolean = false, poll: Int = 30, disponible: Boolean = true) = ListaPorPagar(
        disponible, true, poll, ConteosPorPagar(toPay, toDeliver), (1..n).map { folioPorPagar("f$it") }, emptyList(),
        CursoresPorPagar(if (hayMas) "c" else null, null),
    )

    private class Folios(var respuesta: Result<ListaPorPagar>) {
        val latidos = mutableListOf<Boolean>()
        /** R2-1: si cada lectura se anunció como caja que avisa (`x-online-orders-card-alert`). */
        val avisos = mutableListOf<Boolean>()
        var guardada: ListaPorPagar? = null
        suspend fun leer(conLatido: Boolean, avisa: () -> Boolean): Result<ListaPorPagar> { latidos += conLatido; avisos += avisa(); return respuesta }
    }

    private fun TestScope.armarConFolios(
        folios: Folios, puedeCobrar: Boolean = true, inbox: Inbox = inbox(), enCola: () -> Map<String, Long> = { emptyMap() },
        conTarjeta: () -> List<com.avoqado.pos.onlineorders.data.FolioCobradoConTarjeta> = { emptyList() },
    ): Fixture {
        val f = Fixture(Falso(Result.success(inbox)))
        f.vigilante = VigilanteDePedidosEnLinea(
            consultar = f.falso::consultar, puedeLeer = { f.puedeLeer }, tienePlan = { f.tienePlan }, sonar = { f.sonidos++ },
            scope = backgroundScope, venueActual = { f.venue },
            consultarPorPagar = folios::leer, puedeCobrar = { puedeCobrar }, foliosGuardados = { folios.guardada },
            efectivoEnCola = { enCola() },
            foliosConTarjeta = { _ -> conTarjeta() },
            seVe = { f.seVe },
        )
        return f
    }

    @Test
    fun `A5 the menu number leaves out a folio this device charged by card until the server counts it`() = runTest {
        val marca = com.avoqado.pos.onlineorders.data.FolioCobradoConTarjeta("f2", montoCents = 10000, saldoAntesCents = 10000, enMs = 0)
        val folios = Folios(Result.success(listaFolios(3)))
        val f = armarConFolios(folios, conTarjeta = { listOf(marca) })
        f.vigilante.onStart(owner); runCurrent()
        assertEquals("la lista todavía lo trae con todo su saldo: ya no es «por pagar»", 2, f.vigilante.foliosPorPagar.value)

        // El SERVIDOR ya lo cuenta (salió de «Por pagar»): el número es el de la lista, sin restar nada más.
        folios.respuesta = Result.success(listaFolios(3).let { it.copy(porPagar = it.porPagar.filter { p -> p.orderId != "f2" }, counts = ConteosPorPagar(2, 1)) })
        advanceTimeBy(30_000); runCurrent()
        assertEquals(2, f.vigilante.foliosPorPagar.value)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `A3 the menu number leaves out what this device charged in cash, also without network`() = runTest {
        // La pantalla pinta en «Por entregar» lo cobrado aquí sin subir; el número del menú dice lo mismo.
        var cola = mapOf("f2" to 13400L)
        val folios = Folios(Result.success(listaFolios(3)))
        val f = armarConFolios(folios, enCola = { cola })
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(2, f.vigilante.foliosPorPagar.value)

        // Sin red se cobra otro: el siguiente sondeo falla, pero el número ya no lo cuenta.
        folios.respuesta = Result.failure(IOException("sin red"))
        cola = mapOf("f2" to 13400L, "f3" to 9500L)
        advanceTimeBy(30_000); runCurrent()
        assertEquals(1, f.vigilante.foliosPorPagar.value)
        f.vigilante.onStop(owner)
    }

    /** R102: una respuesta más vieja que la copia guardada no mueve el número del menú. */
    @Test
    fun `an old to-pay response does not move the menu number`() = runTest {
        val folios = Folios(Result.success(listaFolios(3)))
        val f = armarConFolios(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(3, f.vigilante.foliosPorPagar.value)

        folios.respuesta = Result.success(listaFolios(1).copy(masViejaQueLaCopia = true, copiaGuardada = false))
        advanceTimeBy(30_000); runCurrent()
        assertEquals(3, f.vigilante.foliosPorPagar.value)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `the watcher never rings for a folio and keeps them out of the pending list`() = runTest {
        val folios = Folios(Result.success(listaFolios(3)))
        val f = armarConFolios(folios)
        f.vigilante.onStart(owner)
        runCurrent()
        assertEquals("el conteo de folios llega al menú", 3, f.vigilante.foliosPorPagar.value)
        assertEquals("un folio nunca suena", 0, f.sonidos)
        assertTrue(f.vigilante.pendientes.value.isEmpty())
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, f.sonidos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `the heartbeat only goes with payments create`() = runTest {
        val con = Folios(Result.success(listaFolios(1)))
        val a = armarConFolios(con, puedeCobrar = true)
        a.vigilante.onStart(owner); runCurrent()
        assertEquals(listOf(true), con.latidos)
        a.vigilante.onStop(owner)

        val sin = Folios(Result.success(listaFolios(1)))
        val b = armarConFolios(sin, puedeCobrar = false)
        b.vigilante.onStart(owner); runCurrent()
        assertEquals("sin payments:create se lee la lista pero no se late", listOf(false), sin.latidos)
        b.vigilante.onStop(owner)
    }

    @Test
    fun `folios are polled every pollSeconds and open the entry`() = runTest {
        val folios = Folios(Result.success(listaFolios(1, poll = 30)))
        val f = armarConFolios(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(1, folios.latidos.size)
        assertTrue("con folios el menú se atiende aunque el plan venza", f.vigilante.hayPedidosAbiertos.value)
        advanceTimeBy(29_999); runCurrent()
        assertEquals(1, folios.latidos.size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, folios.latidos.size)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `the menu number never contradicts the list and a failure keeps it`() = runTest {
        val folios = Folios(Result.success(listaFolios(2, toPay = 3)))
        val f = armarConFolios(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals("un folio dañado se saltó: el número es el de la lista", 2, f.vigilante.foliosPorPagar.value)

        folios.respuesta = Result.failure(IOException("sin red"))
        advanceTimeBy(30_000); runCurrent()
        assertEquals("sin red se conserva el último número", 2, f.vigilante.foliosPorPagar.value)

        folios.respuesta = Result.success(listaFolios(0, disponible = false))
        advanceTimeBy(30_000); runCurrent()
        assertEquals("el servidor dice que no hay sección: cero", 0, f.vigilante.foliosPorPagar.value)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `cold start without network takes the number from the saved list`() = runTest {
        val folios = Folios(Result.failure(IOException("sin red")))
        folios.guardada = listaFolios(4)
        val f = armarConFolios(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(4, f.vigilante.foliosPorPagar.value)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `without orders read no folio is read`() = runTest {
        val folios = Folios(Result.success(listaFolios(2)))
        val f = armarConFolios(folios)
        f.puedeLeer = false
        f.vigilante.onStart(owner); runCurrent()
        assertTrue(folios.latidos.isEmpty())
        assertEquals(0, f.vigilante.foliosPorPagar.value)
        f.vigilante.onStop(owner)
    }

    // MARK: - D661 (Task 11): pagados con tarjeta en línea que esperan que una caja los abra

    private fun tarjeta(id: String, cobrando: Boolean = false) = TarjetaPorAbrir(
        orderId = id, orderNumber = "T-$id", customerName = "Ana N.", totalCents = 25050,
        createdAt = Instant.parse("2026-11-20T18:00:00Z"), cobrando = cobrando,
    )

    /** Un pedido «No, entran solos» ya cobrado con tarjeta (abierto por una caja) con su comanda pendiente. */
    private fun pagadoNadie(id: String) = pedido(id, folio = "T-$id").copy(
        status = OnlineOrderStatus.ACCEPTED, paymentMode = "CARD_ONLINE", paymentStatus = "PAID", requiresAcceptance = false,
        remainingCents = 0, needsPrint = true, cardChargeStatus = "CAPTURED",
        items = listOf(com.avoqado.pos.onlineorders.domain.OnlineOrderItem("p1", null, "Flautas", 2, null, emptyList())),
    )

    private fun listaD661(
        tarjetas: List<TarjetaPorAbrir> = emptyList(),
        comandas: List<ComandaPorImprimir> = emptyList(),
        porPagar: List<OnlineOrder> = emptyList(),
        porEntregar: List<OnlineOrder> = emptyList(),
        showTile: Boolean = true,
        poll: Int = 30,
    ) = ListaPorPagar(
        true, showTile, poll, ConteosPorPagar(porPagar.size, porEntregar.size), porPagar, porEntregar, CursoresPorPagar(null, null),
        tarjetasPorAbrir = tarjetas, comandasPorImprimir = comandas,
    )

    /** La comanda: lo que se leyó por su propio camino (Ruling 14) y lo que se mandó a imprimir. */
    private class Comandas {
        val leidos = mutableListOf<String>()
        val impresas = mutableListOf<OnlineOrder>()
        val pedidos = mutableMapOf<String, OnlineOrder>()
        /** Cuánto tarda cada lectura del pedido, y cuántas hubo a la vez como máximo. */
        var demora = 0L
        private var enVuelo = 0
        var maximoEnVuelo = 0
        suspend fun leer(id: String): Result<OnlineOrder> {
            leidos += id
            enVuelo++
            maximoEnVuelo = maxOf(maximoEnVuelo, enVuelo)
            try {
                if (demora > 0) delay(demora)
            } finally {
                enVuelo--
            }
            return pedidos[id]?.let { Result.success(it) } ?: Result.failure(IOException("sin red"))
        }
        /** R2-4: pedidos cuya impresora se queda esperando para siempre (un permiso USB que nadie contesta). */
        var atoradas = emptySet<String>()
        val enCurso = mutableSetOf<String>()
        suspend fun imprimir(p: OnlineOrder) {
            if (p.orderId in atoradas) {
                enCurso += p.orderId
                kotlinx.coroutines.awaitCancellation()
            }
            impresas += p
        }
    }

    private fun TestScope.armarD661(
        folios: Folios,
        comandas: Comandas = Comandas(),
        inbox: Inbox = inbox(),
        puedeImprimir: suspend (String) -> Boolean = { true },
        descansar: (String) -> Unit = {},
    ): Fixture {
        val f = Fixture(Falso(Result.success(inbox)))
        f.vigilante = VigilanteDePedidosEnLinea(
            consultar = f.falso::consultar, puedeLeer = { f.puedeLeer }, tienePlan = { f.tienePlan }, sonar = { f.sonidos++ },
            scope = backgroundScope, venueActual = { f.venue },
            consultarPorPagar = folios::leer, puedeCobrar = { true }, foliosGuardados = { folios.guardada },
            leerPedido = comandas::leer, imprimirComanda = comandas::imprimir, puedeImprimirComanda = puedeImprimir,
            descansarComanda = descansar, seVe = { f.seVe }, ventanaActiva = { f.activa },
        )
        return f
    }

    /** Ruling 19: lo que dice la banda de los pagados en línea (la MISMA regla que el sonido). Vacío = no se ve. */
    private fun banda(f: Fixture): List<String> =
        tarjetasQueAvisan(f.vigilante.tarjetasPorAbrir.value, f.vigilante.tarjetasEnVivo.value).map { it.orderId }

    @Test
    fun `rings every 4s while the server reports paid orders to open`() = runTest {
        val folios = Folios(Result.success(listaD661(listOf(tarjeta("t1")))))
        val f = armarD661(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals("suena en cuanto la lectura en vivo lo trae", 1, f.sonidos)
        assertEquals(listOf("t1"), f.vigilante.tarjetasPorAbrir.value.map { it.orderId })
        advanceTimeBy(4_000); runCurrent()
        assertEquals(2, f.sonidos)
        advanceTimeBy(4_000); runCurrent()
        assertEquals(3, f.sonidos)
        // Una caja lo abrió: la siguiente lectura (a los 30 s, su pollSeconds) ya no lo trae y el sonido para en ese instante.
        folios.respuesta = Result.success(listaD661())
        advanceTimeBy(22_000); runCurrent()
        val n = f.sonidos
        assertTrue(f.vigilante.tarjetasPorAbrir.value.isEmpty())
        advanceTimeBy(40_000); runCurrent()
        assertEquals("sin pedidos por abrir no vuelve a sonar", n, f.sonidos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `a paid order another register is charging does not ring`() = runTest {
        val folios = Folios(Result.success(listaD661(listOf(tarjeta("t1", cobrando = true)))))
        val f = armarD661(folios)
        f.vigilante.onStart(owner); runCurrent()
        advanceTimeBy(20_000); runCurrent()
        assertEquals("cobrando = otra caja ya lo atiende", 0, f.sonidos)
        assertEquals("pero se sigue viendo", 1, f.vigilante.tarjetasPorAbrir.value.size)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `a saved or old copy never rings`() = runTest {
        // Arranque en frío sin red: la copia guardada da el número del menú, pero nunca suena (pudo abrirse hace rato).
        val folios = Folios(Result.failure(IOException("sin red")))
        folios.guardada = listaD661(listOf(tarjeta("t1")))
        val f = armarD661(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(listOf("t1"), f.vigilante.tarjetasPorAbrir.value.map { it.orderId })
        advanceTimeBy(20_000); runCurrent()
        assertEquals(0, f.sonidos)
        // Una respuesta más vieja que la copia (R102) tampoco lo despierta.
        folios.respuesta = Result.success(listaD661(listOf(tarjeta("t1"))).copy(masViejaQueLaCopia = true, copiaGuardada = false))
        advanceTimeBy(10_000); runCurrent()
        assertEquals(0, f.sonidos)
        // Una lectura EN VIVO sí suena …
        folios.respuesta = Result.success(listaD661(listOf(tarjeta("t1"))))
        advanceTimeBy(30_000); runCurrent()
        assertTrue(f.sonidos > 0)
        // … y si la red se cae, lo último se sigue viendo pero deja de sonar: es una copia vieja.
        folios.respuesta = Result.failure(IOException("sin red"))
        advanceTimeBy(30_000); runCurrent()
        val n = f.sonidos
        assertEquals(listOf("t1"), f.vigilante.tarjetasPorAbrir.value.map { it.orderId })
        advanceTimeBy(20_000); runCurrent()
        assertEquals(n, f.sonidos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `after restart a pending paid order still rings`() = runTest {
        val folios = Folios(Result.success(listaD661(listOf(tarjeta("t1")))))
        val antes = armarD661(folios)
        antes.vigilante.onStart(owner); runCurrent()
        assertEquals(1, antes.sonidos)
        // A segundo plano y de vuelta sin red: lo de antes no suena (puede estar abierto desde hace rato).
        antes.vigilante.onStop(owner)
        folios.respuesta = Result.failure(IOException("sin red"))
        antes.vigilante.onStart(owner); runCurrent()
        advanceTimeBy(8_000); runCurrent()
        assertEquals(1, antes.sonidos)
        antes.vigilante.onStop(owner)
        // La app se cerró y se volvió a abrir: un vigilante nuevo, sin nada en memoria. El pedido sigue apartado en el servidor.
        folios.respuesta = Result.success(listaD661(listOf(tarjeta("t1"))))
        folios.guardada = listaD661(listOf(tarjeta("t1")))
        val despues = armarD661(folios)
        despues.vigilante.onStart(owner); runCurrent()
        assertEquals("suena por la lectura en vivo del servidor", 1, despues.sonidos)
        despues.vigilante.onStop(owner)
    }

    @Test
    fun `a folio paid at the register never rings`() = runTest {
        val enCaja = folioPorPagar("f1")
        val pagadoEnCaja = folioPorPagar("f2").copy(status = OnlineOrderStatus.ACCEPTED, paymentStatus = "PAID", remainingCents = 0)
        val folios = Folios(Result.success(listaD661(porPagar = listOf(enCaja), porEntregar = listOf(pagadoEnCaja))))
        val f = armarD661(folios)
        f.vigilante.onStart(owner); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, f.sonidos)
        assertTrue(f.vigilante.tarjetasPorAbrir.value.isEmpty())
        f.vigilante.onStop(owner)
    }

    @Test
    fun `changing venue clears the list`() = runTest {
        val folios = Folios(Result.success(listaD661(listOf(tarjeta("t1")))))
        val f = armarD661(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(1, f.vigilante.tarjetasPorAbrir.value.size)
        f.venue = "v2"
        folios.respuesta = Result.failure(IOException("sin red"))
        advanceTimeBy(10_000); runCurrent()
        assertTrue("lo de la sucursal anterior no se queda", f.vigilante.tarjetasPorAbrir.value.isEmpty())
        val n = f.sonidos
        advanceTimeBy(40_000); runCurrent()
        assertEquals("ni suena", n, f.sonidos)
        assertEquals(0, numeroDelMenu(f.vigilante.pendientes.value.size, f.vigilante.foliosPorPagar.value, f.vigilante.tarjetasPorAbrir.value.size))
        f.vigilante.onStop(owner)
    }

    @Test
    fun `the section stays visible and polling fast while tarjetasPorAbrir is not empty, even after a mode change`() = runTest {
        // El negocio pasó a «Mi equipo» (o apagó la tienda): la sección ya no sale por la tienda, pero el apartado sigue esperando.
        val lista = listaD661(listOf(tarjeta("t1")), showTile = false, poll = 30)
        assertTrue(seccionDeFoliosVisible(lista))
        assertFalse(seccionDeFoliosVisible(listaD661(showTile = false)))
        assertFalse("un «no disponible» la esconde igual", seccionDeFoliosVisible(lista.copy(disponible = false)))
        val folios = Folios(Result.success(lista))
        val f = armarD661(folios)
        f.tienePlan = false
        f.vigilante.onStart(owner); runCurrent()
        assertTrue("el menú se atiende aunque el plan venza", f.vigilante.hayPedidosAbiertos.value)
        assertEquals(1, folios.latidos.size)
        advanceTimeBy(30_000); runCurrent()
        assertEquals("obedece el pollSeconds de 30 s que manda el servidor", 2, folios.latidos.size)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `a paid nadie card order with needsPrint prints its kitchen ticket once from a fresh read, even if the open response was lost, the cashier left the screen, the app restarted, the list paginated or the day closed`() = runTest {
        // c1 está en la página de «Por entregar»; c2 no (otra página, o el cierre del día ya lo pasó a COMPLETED): se lee solo.
        val comandas = Comandas()
        comandas.pedidos["c2"] = pagadoNadie("c2").copy(status = OnlineOrderStatus.COMPLETED)
        val folios = Folios(Result.success(listaD661(
            comandas = listOf(ComandaPorImprimir("c1", "T-c1"), ComandaPorImprimir("c2", "T-c2")),
            porEntregar = listOf(pagadoNadie("c1")),
        )))
        // Nadie tiene la pantalla abierta: sólo el vigilante global.
        val f = armarD661(folios, comandas)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(listOf("c1", "c2"), comandas.impresas.map { it.orderId }.sorted())
        assertEquals("sólo se lee el que no está en la página", listOf("c2"), comandas.leidos)
        assertEquals("con sus renglones", "Flautas", comandas.impresas.first { it.orderId == "c2" }.items.single().name)
        // El servidor ya las confirmó: salen de su lista y no se vuelven a imprimir.
        folios.respuesta = Result.success(listaD661())
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, comandas.impresas.size)
        f.vigilante.onStop(owner)

        // La respuesta de abrir se perdió y la app se reinició: la comanda sale de la lectura nueva, una vez.
        comandas.pedidos["c3"] = pagadoNadie("c3")
        folios.respuesta = Result.success(listaD661(comandas = listOf(ComandaPorImprimir("c3", "T-c3"))))
        val otra = armarD661(folios, comandas)
        otra.vigilante.onStart(owner); runCurrent()
        assertEquals(listOf("c1", "c2", "c3"), comandas.impresas.map { it.orderId }.sorted())
        otra.vigilante.onStop(owner)

        // Una copia guardada (sin red) nunca dispara una comanda, y un pedido que ya no la pide (needsPrint false) tampoco.
        comandas.pedidos["c4"] = pagadoNadie("c4")
        folios.respuesta = Result.failure(IOException("sin red"))
        folios.guardada = listaD661(comandas = listOf(ComandaPorImprimir("c4", "T-c4")))
        val sinRed = armarD661(folios, comandas)
        sinRed.vigilante.onStart(owner); runCurrent()
        assertEquals(3, comandas.impresas.size)
        comandas.pedidos["c5"] = pagadoNadie("c5").copy(needsPrint = false)
        folios.respuesta = Result.success(listaD661(comandas = listOf(ComandaPorImprimir("c5", "T-c5"))))
        advanceTimeBy(10_000); runCurrent()
        assertEquals(3, comandas.impresas.size)
        sinRed.vigilante.onStop(owner)
    }

    // MARK: - Revisión del PR #308, Important #1 (Ruling 19): la banda propia del timbre de los pagados en línea

    private fun fuente(ruta: String) = listOf(java.io.File(ruta), java.io.File("app/$ruta")).first { it.exists() }.readText()

    @Test
    fun `the paid online banner shows on any screen while there is something to open on a live read`() = runTest {
        // Lo que se ve es lo que suena: sin el que otra caja está cobrando, y con el conteo cuando hay más de uno.
        val folios = Folios(Result.success(listaD661(listOf(tarjeta("t1"), tarjeta("t2", cobrando = true)))))
        val f = armarD661(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(listOf("t1"), banda(f))
        assertEquals("Pedido pagado en línea · ábrelo para cobrar", TextosPedidosEnLinea.avisoDePagadosEnLinea(banda(f).size))
        folios.respuesta = Result.success(listaD661(listOf(tarjeta("t1"), tarjeta("t3"))))
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf("t1", "t3"), banda(f))
        assertEquals("Pedido pagado en línea · ábrelo para cobrar (2 por abrir)", TextosPedidosEnLinea.avisoDePagadosEnLinea(banda(f).size))
        // «En cualquier pantalla»: va en la barra de arriba de las DOS disposiciones del NavGraph (tablet y teléfono).
        val navGraph = fuente("src/main/java/com/avoqado/pos/navigation/AvoqadoNavGraph.kt")
        assertEquals(2, Regex("""BannerDePagadosEnLinea\(vigilanteDePedidos\)""").findAll(navGraph).count())
        f.vigilante.onStop(owner)
    }

    @Test
    fun `the paid online banner is not shown from a saved or stale copy`() = runTest {
        // Arranque en frío sin red: la copia guardada trae uno por abrir (cuenta en el menú) pero la banda no sale: pudo abrirse hace rato.
        val folios = Folios(Result.failure(IOException("sin red")))
        folios.guardada = listaD661(listOf(tarjeta("t1")))
        val f = armarD661(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(listOf("t1"), f.vigilante.tarjetasPorAbrir.value.map { it.orderId })
        assertTrue(banda(f).isEmpty())
        // Una respuesta más vieja que la copia (R102) tampoco la enseña.
        folios.respuesta = Result.success(listaD661(listOf(tarjeta("t1"))).copy(masViejaQueLaCopia = true, copiaGuardada = false))
        advanceTimeBy(10_000); runCurrent()
        assertTrue(banda(f).isEmpty())
        // Una lectura en vivo sí …
        folios.respuesta = Result.success(listaD661(listOf(tarjeta("t1"))))
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf("t1"), banda(f))
        // … y si la red se cae, se esconde aunque la lista se siga viendo en la pantalla.
        folios.respuesta = Result.failure(IOException("sin red"))
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf("t1"), f.vigilante.tarjetasPorAbrir.value.map { it.orderId })
        assertTrue(banda(f).isEmpty())
        // De vuelta del segundo plano, nada hasta la primera lectura en vivo de esta vuelta.
        folios.respuesta = Result.success(listaD661(listOf(tarjeta("t1"))))
        advanceTimeBy(10_000); runCurrent()
        assertEquals(listOf("t1"), banda(f))
        f.vigilante.onStop(owner)
        folios.respuesta = Result.failure(IOException("sin red"))
        f.vigilante.onStart(owner); runCurrent()
        assertTrue(banda(f).isEmpty())
        f.vigilante.onStop(owner)
    }

    @Test
    fun `the paid online banner disappears when the last one is opened`() = runTest {
        val folios = Folios(Result.success(listaD661(listOf(tarjeta("t1"), tarjeta("t2")))))
        val f = armarD661(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(listOf("t1", "t2"), banda(f))
        // Una caja abrió t1: la relectura inmediata (la pantalla la pide al abrir) ya no lo trae.
        folios.respuesta = Result.success(listaD661(listOf(tarjeta("t2"))))
        f.vigilante.sondearFoliosYa(); runCurrent()
        assertEquals(listOf("t2"), banda(f))
        // Otra caja está cobrando t2: ya no cuenta.
        folios.respuesta = Result.success(listaD661(listOf(tarjeta("t2", cobrando = true))))
        f.vigilante.sondearFoliosYa(); runCurrent()
        assertTrue(banda(f).isEmpty())
        // Abierto el último, nada.
        folios.respuesta = Result.success(listaD661())
        f.vigilante.sondearFoliosYa(); runCurrent()
        assertTrue(banda(f).isEmpty())
        f.vigilante.onStop(owner)
    }

    @Test
    fun `the paid online banner coexists with the Mi equipo banner`() = runTest {
        // Un pedido de «Mi equipo» por aceptar Y uno pagado en línea por abrir: se ven las dos bandas a la vez.
        val folios = Folios(Result.success(listaD661(listOf(tarjeta("t1")))))
        val f = armarD661(folios, inbox = inbox(listOf(pedido("p1", folio = "W-17"))))
        f.vigilante.onStart(owner); runCurrent()
        assertEquals("Nuevo pedido en línea #W-17", textoDelBanner(f.vigilante.pendientes.value.map { it.orderNumber }))
        assertEquals(listOf("t1"), banda(f))
        // Aceptar el de «Mi equipo» no esconde la de los pagados en línea, ni al revés.
        f.falso.respuesta = Result.success(inbox())
        f.vigilante.sondearYa(); runCurrent()
        assertTrue(f.vigilante.pendientes.value.isEmpty())
        assertEquals(listOf("t1"), banda(f))
        f.falso.respuesta = Result.success(inbox(listOf(pedido("p2", folio = "W-18"))))
        folios.respuesta = Result.success(listaD661())
        f.vigilante.sondearYa(); f.vigilante.sondearFoliosYa(); runCurrent()
        assertEquals(listOf("p2"), f.vigilante.pendientes.value.map { it.orderId })
        assertTrue(banda(f).isEmpty())
        // En el NavGraph van una debajo de la otra, en las dos disposiciones: la de «Mi equipo» (D662, sin tocar) y la propia.
        val navGraph = fuente("src/main/java/com/avoqado/pos/navigation/AvoqadoNavGraph.kt")
        val juntas = Regex("""BannerDePedidoEnLinea\(vigilanteDePedidos\)\s+(//[^\n]*\s+)?com\.avoqado\.pos\.onlineorders\.presentation\.BannerDePagadosEnLinea\(vigilanteDePedidos\)""")
        assertEquals(2, juntas.findAll(navGraph).count())
        f.vigilante.onStop(owner)
    }

    // MARK: - Revisión del PR #308, Important #2: la comanda no se lee si este aparato no la puede sacar

    @Test
    fun `with no kitchen printer no order is read for its kitchen ticket`() = runTest {
        val comandas = Comandas()
        comandas.pedidos["c1"] = pagadoNadie("c1"); comandas.pedidos["c2"] = pagadoNadie("c2")
        val folios = Folios(Result.success(listaD661(comandas = listOf(ComandaPorImprimir("c1", "T-c1"), ComandaPorImprimir("c2", "T-c2")))))
        var hayImpresora = false
        val preguntas = mutableListOf<String>()
        val f = armarD661(folios, comandas, puedeImprimir = { id -> preguntas += id; hayImpresora })
        f.vigilante.onStart(owner); runCurrent()
        advanceTimeBy(90_000); runCurrent()
        // Cuatro lecturas de «Por pagar» (cada 30 s) y ni una lectura del pedido: el aparato contestó solo, sin red.
        assertEquals(4, folios.latidos.size)
        assertEquals(emptyList<String>(), comandas.leidos)
        assertTrue(comandas.impresas.isEmpty())
        assertTrue(preguntas.isNotEmpty())
        // Se conecta la impresora de cocina: en la siguiente lectura sí se leen y salen.
        hayImpresora = true
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf("c1", "c2"), comandas.leidos)
        assertEquals(listOf("c1", "c2"), comandas.impresas.map { it.orderId })
        f.vigilante.onStop(owner)
    }

    @Test
    fun `kitchen tickets are read one at a time in a single walk`() = runTest {
        val comandas = Comandas()
        comandas.demora = 20_000
        val ids = listOf("c1", "c2", "c3")
        ids.forEach { comandas.pedidos[it] = pagadoNadie(it) }
        val folios = Folios(Result.success(listaD661(comandas = ids.map { ComandaPorImprimir(it, "T-$it") })))
        val f = armarD661(folios, comandas)
        f.vigilante.onStart(owner); runCurrent()
        // La vuelta tarda 60 s (tres lecturas de 20 s); la lectura de los 30 s trae la misma lista y no abre otra vuelta encima.
        advanceTimeBy(59_000); runCurrent()
        assertEquals("de una en una", 1, comandas.maximoEnVuelo)
        assertEquals(ids, comandas.leidos)
        advanceTimeBy(2_000); runCurrent()
        assertEquals(ids, comandas.impresas.map { it.orderId })
        f.vigilante.onStop(owner)
    }

    // MARK: - Ronda 2 (Codex r1)

    /** R2-1 (Ruling 35): sólo el compás del vigilante, con la ventana a la vista, le dice al SERVIDOR que esta caja avisa. */
    @Test
    fun `R2-1 only the watcher cycle with the window in view announces it can alert`() = runTest {
        val folios = Folios(Result.success(listaD661(listOf(tarjeta("t1")))))
        val f = armarD661(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals("a la vista, el compás se anuncia", listOf(true), folios.avisos)
        // Minimizada (WINDOWS): sigue leyendo, pero ya no se anuncia (ni banda visible ni, quizá, sonido).
        f.seVe = false
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf(true, false), folios.avisos)
        // De vuelta a la vista: la siguiente lectura del compás se anuncia otra vez.
        f.seVe = true
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf(true, false, true), folios.avisos)
        // La relectura inmediata (tras «Abrir y cobrar») nunca se anuncia: no mueve el compás y puede salir después de `onStop`.
        f.vigilante.sondearFoliosYa(); runCurrent()
        assertEquals(listOf(true, false, true, false), folios.avisos)
        assertEquals("el latido de folios no cambia", listOf(true, true, true, true), folios.latidos)
        f.vigilante.onStop(owner)
    }

    /** R2-4 (regresión de la ronda 1): una comanda atorada en una impresora no frena a las demás. */
    @Test
    fun `R2-4 a ticket stuck on one printer does not stop the next one`() = runTest {
        val comandas = Comandas()
        comandas.pedidos["a"] = pagadoNadie("a"); comandas.pedidos["b"] = pagadoNadie("b")
        comandas.atoradas = setOf("a") // su impresora USB espera un permiso que nadie contesta
        val folios = Folios(Result.success(listaD661(comandas = listOf(ComandaPorImprimir("a", "T-a"), ComandaPorImprimir("b", "T-b")))))
        val f = armarD661(folios, comandas)
        f.vigilante.onStart(owner); runCurrent()
        advanceTimeBy(VigilanteDePedidosEnLinea.ESPERA_POR_COMANDA_MS + 1_000); runCurrent()
        assertEquals("B sale aunque A siga atorada", listOf("b"), comandas.impresas.map { it.orderId })
        assertTrue("A sigue en su trabajo: nadie la canceló", "a" in comandas.enCurso)
        assertEquals("y sin lecturas a la vez", 1, comandas.maximoEnVuelo)
        f.vigilante.onStop(owner)
    }

    /** R2-4: la lista que llega mientras una vuelta corre no se pierde: se recorre en cuanto la vuelta termina. */
    @Test
    fun `R2-4 a list that arrives while a walk is running is walked right after it`() = runTest {
        val comandas = Comandas()
        comandas.demora = 40_000 // la lectura de c1 tarda: la vuelta sigue viva cuando llega la siguiente lista
        comandas.pedidos["c1"] = pagadoNadie("c1"); comandas.pedidos["c2"] = pagadoNadie("c2")
        val folios = Folios(Result.success(listaD661(comandas = listOf(ComandaPorImprimir("c1", "T-c1")))))
        val f = armarD661(folios, comandas)
        f.vigilante.onStart(owner); runCurrent()
        // A los 30 s llega c2 (otra caja abrió otro pedido) mientras la vuelta sigue leyendo c1.
        folios.respuesta = Result.success(listaD661(comandas = listOf(ComandaPorImprimir("c1", "T-c1"), ComandaPorImprimir("c2", "T-c2"))))
        advanceTimeBy(30_000); runCurrent()
        assertTrue(comandas.impresas.isEmpty())
        comandas.demora = 0
        // La vuelta termina a los 40 s y recorre en seguida la lista que llegó: c2 no espera a la lectura de los 60 s.
        advanceTimeBy(11_000); runCurrent()
        assertTrue("c2 salió con la lista que llegó a media vuelta", comandas.impresas.any { it.orderId == "c2" })
        assertEquals(1, comandas.maximoEnVuelo)
        f.vigilante.onStop(owner)
    }

    /** R2-6 (menor de la re-revisión): un pedido cuya lectura falla entra al mismo descanso que una comanda que no salió. */
    @Test
    fun `R2-6 an order whose read fails rests like a ticket that did not print`() = runTest {
        val comandas = Comandas() // c1 no está en `pedidos`: su lectura falla
        val descansos = mutableListOf<String>()
        val folios = Folios(Result.success(listaD661(comandas = listOf(ComandaPorImprimir("c1", "T-c1")))))
        val f = armarD661(folios, comandas, descansar = { descansos += it })
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(listOf("c1"), comandas.leidos)
        assertEquals(listOf("c1"), descansos)
        f.vigilante.onStop(owner)
    }

    // MARK: - Ronda 3 · Familia 1 (Ruling 48): quién le dice a la tienda «aquí avisan»

    @Test
    fun `R3 Family 1 a window that is visible but not active does not announce it can alert`() = runTest {
        val folios = Folios(Result.success(listaD661(listOf(tarjeta("t1")))))
        val f = armarD661(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(listOf(true), folios.avisos)
        // Un navegador maximizado encima de la caja de WINDOWS: sin minimizar, pero ya no es la ventana activa.
        f.activa = false
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf(true, false), folios.avisos)
        // El timbre, la banda y el sondeo no cambian: sólo lo que se anuncia.
        assertEquals(listOf("t1"), banda(f))
        assertEquals(listOf(true, true), folios.latidos)
        // De vuelta a la caja: la siguiente lectura del compás se anuncia otra vez.
        f.activa = true
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf(true, false, true), folios.avisos)
        // Minimizada sigue sin anunciarse (R2-1).
        f.seVe = false
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf(true, false, true, false), folios.avisos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `R3 Family 1 ANDROID never publishes the active window, so a visible tablet keeps announcing`() = runTest {
        // Nadie publica `VentanaActiva` en ANDROID: el vigilante sin el parámetro la lee y se anuncia como en la ronda 2.
        assertTrue(com.avoqado.pos.onlineorders.data.VentanaActiva.activa.value)
        val folios = Folios(Result.success(listaD661(listOf(tarjeta("t1")))))
        val f = armarConFolios(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(listOf(true), folios.avisos)
        f.vigilante.onStop(owner)
    }

    // MARK: - Ronda 5 · Familia 1 (Ruling 78): volver a la caja la despierta

    /**
     * La única caja de WINDOWS se anunciaba (compás de 30 s). Le abren el DASHBOARD encima: la siguiente lectura sale sin la cabecera y el
     * SERVIDOR (con la tienda apagada en ese momento) contesta 600 s. Al regresar a la caja se lee YA, con la cabecera, sin esperar los 10
     * minutos. Una vez por regreso: con la caja ya anunciada, ir y venir no agrega lecturas hasta su compás.
     */
    @Test
    fun `R5 Family 1 regaining focus after a slow unannounced read reads right away with the header, once per regain`() = runTest {
        val folios = Folios(Result.success(listaD661(listOf(tarjeta("t1")), poll = 30)))
        val f = armarD661(folios)
        f.vigilante.onStart(owner); runCurrent()
        assertEquals(listOf(true), folios.avisos)
        // El DASHBOARD encima: sin foco. La lectura sale sin cabecera y el SERVIDOR contesta 600 s.
        f.activa = false
        folios.respuesta = Result.success(listaD661(poll = 600))
        advanceTimeBy(30_000); runCurrent()
        assertEquals(listOf(true, false), folios.avisos)
        // Diez segundos después regresa a la caja (la tienda ya está prendida otra vez).
        folios.respuesta = Result.success(listaD661(listOf(tarjeta("t1")), poll = 30))
        advanceTimeBy(10_000); runCurrent()
        f.activa = true
        advanceTimeBy(VigilanteDePedidosEnLinea.VISTAZO_AL_VOLVER_MS); runCurrent()
        assertEquals("lee ya, con la cabecera: no espera los 600 s", listOf(true, false, true), folios.avisos)
        // Una vez por regreso: ya anunciada, perder y recuperar el foco no agrega lecturas antes de su compás de 30 s.
        f.activa = false; advanceTimeBy(5_000); runCurrent()
        f.activa = true; advanceTimeBy(5_000); runCurrent()
        assertEquals(listOf(true, false, true), folios.avisos)
        advanceTimeBy(20_000); runCurrent()
        assertEquals("el compás sigue con lo que contestó el SERVIDOR", listOf(true, false, true, true), folios.avisos)
        f.vigilante.onStop(owner)
    }

    /**
     * Ruling 78: en WINDOWS la señal de foco arranca en «no» (el arranque la publica antes de registrar el vigilante) hasta que la ventana
     * dice que es la activa: la primera lectura no se anuncia por una ventana que todavía no existe; en cuanto la ventana toma el foco, se lee
     * ya con la cabecera. Una ventana que se restaura de minimizada despierta igual.
     */
    @Test
    fun `R5 Family 1 before the window reports focus the first read carries no header, and taking focus or restoring reads with it`() = runTest {
        val folios = Folios(Result.success(listaD661(poll = 600)))
        val f = armarD661(folios)
        f.activa = false
        f.vigilante.onStart(owner); runCurrent()
        assertEquals("sin ventana activa todavía: sin cabecera", listOf(false), folios.avisos)
        f.activa = true
        advanceTimeBy(VigilanteDePedidosEnLinea.VISTAZO_AL_VOLVER_MS); runCurrent()
        assertEquals(listOf(false, true), folios.avisos)
        // Minimizada hasta su compás (600 s), y luego restaurada: también lee ya.
        f.seVe = false
        advanceTimeBy(600_000); runCurrent()
        assertEquals(listOf(false, true, false), folios.avisos)
        f.seVe = true
        advanceTimeBy(VigilanteDePedidosEnLinea.VISTAZO_AL_VOLVER_MS); runCurrent()
        assertEquals(listOf(false, true, false, true), folios.avisos)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `D662 p2 pausadaSola follows the store and survives a network failure`() = runTest {
        val f = armar(Result.success(inbox(autoPaused = true)))
        f.vigilante.onStart(owner)
        runCurrent()
        assertTrue(f.vigilante.pausadaSola.value)
        f.falso.respuesta = Result.failure(java.io.IOException("sin red"))
        advanceTimeBy(10_000); runCurrent()
        assertTrue("sin red se conserva lo último que dijo el servidor", f.vigilante.pausadaSola.value)
        f.falso.respuesta = Result.success(inbox().copy(autoPaused = null))
        advanceTimeBy(10_000); runCurrent()
        assertTrue("una respuesta sin el estado de la tienda tampoco la borra (Codex p2-r7 #3)", f.vigilante.pausadaSola.value)
        f.falso.respuesta = Result.success(inbox())
        advanceTimeBy(10_000); runCurrent()
        assertFalse("alguien reanudó", f.vigilante.pausadaSola.value)
        f.vigilante.onStop(owner)
    }

    @Test
    fun `D662 p2 banner says the store paused itself first, also with orders waiting`() {
        assertEquals("Pedidos en línea en pausa: 2 pedidos seguidos sin aceptar", textoDelBanner(emptyList(), pausadaSola = true))
        assertEquals(
            "Pedidos en línea en pausa: 2 pedidos seguidos sin aceptar · #W3 por aceptar",
            textoDelBanner(listOf("W2", "W3"), pausadaSola = true),
        )
        assertEquals("Nuevo pedido en línea #W3 (2 por aceptar)", textoDelBanner(listOf("W2", "W3")))
        assertEquals("", textoDelBanner(emptyList()))
    }
}
