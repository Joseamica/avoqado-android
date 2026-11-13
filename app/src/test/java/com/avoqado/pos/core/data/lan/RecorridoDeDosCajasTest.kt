package com.avoqado.pos.core.data.lan

import android.content.Context
import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_ESPERAR
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tables.data.TableSession
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val V = "V1"

/** Escenario 1 (reloj real): lo más que se acepta entre la flecha de A y la apertura de B. Amplio, y lejos del vencimiento de 30 s. */
private const val LIMITE_REAL_MS = 10_000L

/**
 * Hub D3 · el recorrido de la promesa (`docs/audit/2026-11-12-mesa-permiso-hub/promesa.md`) con TRES aparatos, para no mezclar el
 * vencimiento con el cambio de árbitro (Codex plan r2 R2-6): C arbitra y no se toca; A y B son cajas, cada una con su sesión de mesa,
 * lo que declara y su coordinador.
 *
 * - Escenario 1, con reloj REAL: C es un [TransporteLan] de verdad (socket por loopback, ruteo incluido) y A y B le hablan con el
 *   [LeaseClient] real. NSD no existe en la JVM: los peers van a mano.
 * - Escenarios 2 y 3, con tiempo virtual: el montaje de `ReconciliadorDeMesasTest` (árbitros [LeaseServer] reales y una [Red] por caja).
 */
class RecorridoDeDosCajasTest {

    private val pA = LanPeer("A", "10.0.0.1", 9000, presencia = true)
    private val pB = LanPeer("B", "10.0.0.2", 9000, presencia = true)
    private val pC = LanPeer("C", "10.0.0.3", 9000, isWired = true, presencia = true)
    private val pD = LanPeer("D", "10.0.0.4", 9000, isWired = true, presencia = true)

    /** Una caja: su sesión de mesa, lo que declara y su coordinador, ya arrancado en [scope]. */
    private class Caja(id: String, private val nombre: String, discovery: LanDiscoveryPort, client: LeaseClient, reloj: () -> Long, val scope: CoroutineScope) {
        private val sesion = TableSession()
        private val mesas = MesasDeEstaCaja({ sesion.current()?.tableId }, sesion.active.map { it?.tableId }, ContadorDeVidas(leer = { 0L }, guardar = { true }), reloj)

        init {
            LanHubCoordinator(discovery, mesas, id, V, { nombre }, client, relojMs = reloj, scope = scope).start()
        }

        /** Como la pantalla de mesas: ticket, espera y, si se la conceden, primero la sesión y después se termina el ticket. */
        suspend fun abre(mesa: String, alEsperar: () -> Unit = {}): LeaseOutcome {
            val ticket = mesas.abrirApertura(mesa, "s-$nombre", nombre, V)
            try {
                return mesas.esperar(ticket, alEsperar) { true }.also { if (it == LeaseOutcome.Granted) entra(mesa) }
            } finally {
                mesas.terminarApertura(ticket)
            }
        }

        fun entra(mesa: String) = sesion.start(
            TableSession.Active(
                tableId = mesa, tableNumber = mesa, areaName = null, orderId = "o-$mesa", orderNumber = "1",
                version = 1, totalCents = 0, mode = TableSession.Mode.ORDERING,
            ),
            V,
        )

        /** La flecha: la sesión se estaciona y la caja deja de declarar la mesa. */
        fun sale() = assertTrue(sesion.estacionar(V, sesion.current()!!))
    }

    // MARK: - Montaje con tiempo virtual

    private fun TestScope.arbitro(id: String) =
        LeaseServer(deviceId = id, monoMillis = { testScheduler.currentTime }).also { it.asumirArbitraje(emptySet()) }

    private fun TestScope.red(arbitros: Map<String, LeaseServer>) = Red(arbitros) { testScheduler.currentTime }

    /** Cada caja en su propio trabajo, hijo del `backgroundScope`: cancelarlo es matar la app sin despedida. */
    private fun TestScope.caja(id: String, nombre: String, discovery: LanDiscoveryPort, red: Red) = Caja(
        id, nombre, discovery, red.client, { testScheduler.currentTime },
        CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job])),
    )

    private fun LeaseServer.mesasListadas() =
        respondTo(LeaseProtocol.encode(LeaseRequest(op = LeaseProtocol.OP_LIST))).leases.orEmpty().map { it.tableId }

    // MARK: - Escenario 1: la promesa, con reloj real

    @Test
    fun `escenario 1 - A abre m13, B la ve de Ana y, cuando A sale con la flecha, B la abre mucho antes del vencimiento de 30 s`() = runBlocking {
        val c = LeaseServer(deviceId = "C").also { it.asumirArbitraje(emptySet()) }
        val printConfig = mockk<PrintConfigRepository> {
            every { config } returns MutableStateFlow(PrintConfig())
            every { getCurrentConfig() } returns PrintConfig()
        }
        val transporteC = TransporteLan(mockk<Context>(relaxed = true), mockk<SyncOutbox> { every { deviceId } returns "C" }, printConfig)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            transporteC.iniciar(V)
            transporteC.conectarHub(c::respondTo)
            // 10 s: esta Mac corre con la carga por encima de 100 (como en `TransporteLanLoopbackTest`).
            val puerto = withTimeoutOrNull(10_000) { while (transporteC.puerto <= 0) delay(25); transporteC.puerto }
            assertNotNull("el socket de C abrió", puerto)
            val discovery = FakeDiscovery(listOf(pA, pB, pC.copy(host = "127.0.0.1", port = puerto!!)))
            val reloj = { System.nanoTime() / 1_000_000 }
            val a = Caja("A", "Ana", discovery, LeaseClient(), reloj, scope)
            val b = Caja("B", "Beto", discovery, LeaseClient(), reloj, scope)

            assertEquals(LeaseOutcome.Granted, a.abre("m13"))
            assertEquals(LeaseOutcome.Taken("Ana"), b.abre("m13"))

            val flecha = reloj()
            a.sale()
            // B pide cuando la salida de A ya llegó a C (si pidiera antes, «ocupada» también sería correcto).
            withTimeoutOrNull(LIMITE_REAL_MS) { while ("m13" in c.mesasListadas()) delay(5) }
            assertEquals(LeaseOutcome.Granted, b.abre("m13"))
            val ms = reloj() - flecha
            // El «en seguida» de la promesa se mide en el aparato; aquí sólo se cuida que no caiga al vencimiento de 30 s (Codex código
            // r1 #5: con reloj real, un límite apretado falla en un CI cargado con el código correcto).
            assertTrue("B la abrió a los $ms ms de la flecha", ms < LIMITE_REAL_MS)
        } finally {
            scope.cancel()
            transporteC.detener()
        }
    }

    // MARK: - Escenario 2: A muere dentro

    @Test
    fun `escenario 2 - A muere dentro de m13 - a los 29 999 ms de su último aviso aceptado B la ve de Ana y a los 30 000 la abre`() = runTest {
        val c = arbitro("C")
        val discovery = FakeDiscovery(listOf(pA, pB, pC))
        val redA = red(mapOf("C" to c))
        val a = caja("A", "Ana", discovery, redA)
        val b = caja("B", "Beto", discovery, red(mapOf("C" to c)))

        a.entra("m13")
        runCurrent()
        assertEquals(LeaseOutcome.Taken("Ana"), b.abre("m13"))
        advanceTimeBy(25_000) // sus latidos la sostienen

        a.scope.cancel() // la app muere: sin despedida; C sigue igual
        runCurrent()
        val avisosDeA = redA.envios.size
        val ultimo = redA.envios.last()
        assertEquals("cada aviso de A tuvo respuesta", avisosDeA, redA.respuestas.size)
        assertEquals(LeaseProtocol.STATUS_OK, redA.respuestas.last().second?.status)
        assertEquals(listOf("m13"), redA.recibidos.last().mesas)

        advanceTimeBy(ultimo + 29_999 - testScheduler.currentTime)
        assertEquals(LeaseOutcome.Taken("Ana"), b.abre("m13"))
        assertEquals(ultimo + 29_999, testScheduler.currentTime)

        advanceTimeBy(1)
        assertEquals(LeaseOutcome.Granted, b.abre("m13"))
        assertEquals(ultimo + 30_000, testScheduler.currentTime)
        assertEquals("A no volvió a hablar", avisosDeA, redA.envios.size)
    }

    // MARK: - Escenario 3: el calentamiento del árbitro nuevo

    @Test
    fun `escenario 3 - el árbitro cambia a D - la apertura nueva de B espera a oír a A y B, la concede, y m13 sigue siendo de Ana`() = runTest {
        val c = arbitro("C")
        val d = LeaseServer(deviceId = "D", monoMillis = { testScheduler.currentTime })
        val arbitros = mapOf("C" to c, "D" to d)
        val discovery = FakeDiscovery(listOf(pA, pB, pC))
        val redA = red(arbitros)
        val redB = red(arbitros)
        val a = caja("A", "Ana", discovery, redA)
        val b = caja("B", "Beto", discovery, redB)
        a.entra("m13")
        runCurrent()
        assertEquals(LeaseOutcome.Taken("Ana"), b.abre("m13"))

        // El relevo: C se va y D asume esperando a A y a B (lo que haría su coordinador con las cajas `pm=1` que ve). El aviso de A a D
        // queda en camino: D todavía no sabe que A está en m13.
        advanceTimeBy(3_000)
        d.asumirArbitraje(setOf("A", "B"))
        redA.retener = Red.Retener.IDA
        discovery.peers.value = listOf(pA, pB, pD)
        runCurrent()
        val relevo = testScheduler.currentTime
        assertEquals("el aviso de A a D va en camino", listOf("m13"), redA.retenidos.single().pedido.mesas)

        var avisos = 0
        val m14 = async { b.abre("m14", alEsperar = { avisos++ }) }
        runCurrent()
        val veredicto = redB.respuestas.last().second?.aperturas?.single()
        assertEquals(APERTURA_ESPERAR, veredicto?.resultado)
        assertEquals("lo que falta del calentamiento", 12_000L, veredicto?.esperarMs)
        assertEquals("«Revisando con las otras cajas…»", 1, avisos)
        assertFalse(m14.isCompleted)

        redA.retener = Red.Retener.NADA
        redA.soltarTodo() // D oye a A en m13: ya oyó a las dos cajas y termina el calentamiento
        runCurrent()

        assertEquals(LeaseOutcome.Granted, m14.await())
        assertTrue("sin esperar los 12 s del calentamiento", testScheduler.currentTime - relevo < 12_000)
        assertEquals(LeaseOutcome.Taken("Ana"), b.abre("m13"))
    }
}
