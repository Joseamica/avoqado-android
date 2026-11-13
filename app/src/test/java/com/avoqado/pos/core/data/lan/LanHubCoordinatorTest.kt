package com.avoqado.pos.core.data.lan

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coordinador del hub: cómo se traduce lo que dice el árbitro a lo que ve el mesero.
 *
 * La regla que se protege aquí: DEGRADAR, NUNCA BLOQUEAR. El hub sirve para PREVENIR conflictos, no para autorizar ventas —
 * pase lo que pase con la red o con el árbitro, el mesero tiene que poder abrir la mesa.
 *
 * Hub D3: ya no hay `acquire` ni renovación; la caja declara su estado y cada apertura se resuelve por id. Los escenarios del
 * reconciliador viven en `ReconciliadorDeMesasTest`; aquí quedan, reescritos, los casos de siempre. Tiempo virtual.
 */
class LanHubCoordinatorTest {

    private val me = "tablet-juan"
    private val yo = LanPeer(me, "127.0.0.1", 9999, presencia = true)
    private val arbitroB = LanPeer("tablet-b", "10.0.0.2", 9000, isWired = true, bootedAtMillis = 1, presencia = true)
    private val vidas = ContadorDeVidas(leer = { 0L }, guardar = { true })

    private fun TestScope.caja() = MesasDeEstaCaja({ null }, flowOf(null), vidas) { testScheduler.currentTime }

    private fun TestScope.coordinador(
        peers: List<LanPeer>,
        mesas: MesasDeEstaCaja,
        client: LeaseClient,
        server: LeaseServer? = null,
    ) = LanHubCoordinator(
        discovery = FakeDiscovery(peers),
        mesas = mesas,
        deviceId = me,
        venueId = "V1",
        nombre = { "Juan" },
        client = client,
        server = server,
        relojMs = { testScheduler.currentTime },
        scope = backgroundScope,
    )

    @Test
    fun `un árbitro que contesta VERSION_MISMATCH NO bloquea la venta - degrada a isla enseguida`() = runTest {
        val client = mockk<LeaseClient> {
            coEvery { estado(any(), any(), any()) } returns
                LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = LeaseProtocol.ERROR_VERSION_MISMATCH)
        }
        val mesas = caja()
        coordinador(listOf(yo, arbitroB), mesas, client).start()
        val apertura = mesas.abrirApertura("mesa-5", "s1", "Juan", "V1")

        assertEquals(LeaseOutcome.NoHub, mesas.esperar(apertura) { true })
        assertEquals("sin esperar el plazo", 0L, testScheduler.currentTime)
    }

    @Test
    fun `un árbitro que no contesta da isla a los 15 s, nunca bloquea`() = runTest {
        val client = mockk<LeaseClient> { coEvery { estado(any(), any(), any()) } returns null }
        val mesas = caja()
        coordinador(listOf(yo, arbitroB), mesas, client).start()
        val apertura = mesas.abrirApertura("mesa-5", "s1", "Juan", "V1")

        assertEquals(LeaseOutcome.NoHub, mesas.esperar(apertura) { true })
        assertEquals(MesasDeEstaCaja.PLAZO_PEDIR_MESA_MS, testScheduler.currentTime)
    }

    @Test
    fun `siendo el árbitro, la mesa se concede en local sin salir a la red`() = runTest {
        val client = mockk<LeaseClient>()
        val mesas = caja()
        val c = coordinador(listOf(yo), mesas, client, LeaseServer(deviceId = me, monoMillis = { testScheduler.currentTime }))
        c.start()
        runCurrent()

        val apertura = mesas.abrirApertura("mesa-5", "s1", "Juan", "V1")

        assertEquals(LeaseOutcome.Granted, mesas.esperar(apertura) { true })
        assertEquals(setOf("mesa-5"), c.myTables.value)
        assertTrue(c.isArbiter.value)
        coVerify(exactly = 0) { client.estado(any(), any(), any()) }
    }

    @Test
    fun `EL CASO - siendo el árbitro, si otro mesero la tiene se devuelve su nombre y no se anota como mía`() = runTest {
        val server = LeaseServer(deviceId = me, monoMillis = { testScheduler.currentTime })
        val mesas = caja()
        val c = coordinador(listOf(yo), mesas, mockk(), server)
        c.start()
        runCurrent()
        // Alberto (otra caja) declara que está en la mesa 5.
        server.respondTo(
            LeaseProtocol.encode(
                LeaseRequest(
                    op = LeaseProtocol.OP_ESTADO, deviceId = "tablet-alberto", staffName = "Alberto",
                    gen = 1, seq = 1, mesas = listOf("mesa-5"),
                ),
            ),
        )

        val apertura = mesas.abrirApertura("mesa-5", "s1", "Juan", "V1")

        assertEquals(LeaseOutcome.Taken("Alberto"), mesas.esperar(apertura) { true })
        assertTrue("la UI no debe creer que la gané", c.myTables.value.isEmpty())
    }
}

/** Transporte de mentira: entrega una lista de peers que la prueba cambia, y acepta el enganche del hub sin abrir nada. */
internal class FakeDiscovery(peers: List<LanPeer>) : LanDiscoveryPort {
    override val peers = MutableStateFlow(peers)
    override var cambiosDeArbitro = 0L
        private set
    override fun conectarHub(respondTo: (String) -> LeaseResponse) {}
    override fun desconectarHub() {}

    /** Como `TransporteLan.publicarPeers` (Codex código r2 H2): cuenta el cambio de elección ANTES de publicar. */
    fun publicar(lista: List<LanPeer>) {
        if (ArbiterElection.cambiaElArbitro(peers.value, lista)) cambiosDeArbitro++
        peers.value = lista
    }
}
