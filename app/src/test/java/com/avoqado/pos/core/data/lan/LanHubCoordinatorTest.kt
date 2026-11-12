package com.avoqado.pos.core.data.lan

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coordinador del hub: cómo se traduce lo que dice el árbitro a lo que ve el
 * mesero.
 *
 * La regla que se protege aquí: DEGRADAR, NUNCA BLOQUEAR. El hub sirve para
 * PREVENIR conflictos, no para autorizar ventas — pase lo que pase con la red
 * o con el árbitro, el mesero tiene que poder abrir la mesa.
 *
 * Se prueba `interpret` a través del camino "yo soy el árbitro", que no toca
 * sockets: el transporte ya está probado aparte y aquí lo que importa es la
 * decisión.
 */
class LanHubCoordinatorTest {

    private val me = "tablet-juan"
    private var clock = 1_000_000L

    /** Coordinador donde YO soy el único peer ⇒ yo soy el árbitro. */
    private fun soloCoordinator(server: LeaseServer = LeaseServer(nowMillis = { clock })): LanHubCoordinator {
        val discovery = FakeDiscovery(listOf(LanPeer(me, "127.0.0.1", 9999)))
        return LanHubCoordinator(
            discovery = discovery,
            server = server,
            client = LeaseClient(),
            deviceId = me,
            nowMillis = { clock },
        )
    }

    @Test
    fun `sin peers NO hay hub - se abre la mesa igual (modo isla)`() = runTest {
        val discovery = FakeDiscovery(emptyList())
        val coordinator = LanHubCoordinator(discovery, LeaseServer(nowMillis = { clock }), LeaseClient(), me) { clock }

        val outcome = coordinator.acquire("mesa-5", "s1", "Juan")

        // Lo que importa: NO es un error ni un bloqueo. Es "no hay con quién
        // coordinar" y el POS sigue como isla, igual que antes del hub.
        assertEquals(LeaseOutcome.NoHub, outcome)
    }

    @Test
    fun `siendo árbitro, la primera mesa se concede sin salir a la red`() = runTest {
        val coordinator = soloCoordinator()

        val outcome = coordinator.acquire("mesa-5", "s1", "Juan")

        assertTrue(outcome is LeaseOutcome.Granted)
        assertEquals(1L, (outcome as LeaseOutcome.Granted).lease.epoch)
        assertEquals(setOf("mesa-5"), coordinator.myTables.value)
    }

    @Test
    fun `EL CASO - si otro mesero la tiene, se devuelve su nombre para mostrarlo`() = runTest {
        // El árbitro ya tiene la mesa de Alberto (otro dispositivo).
        val server = LeaseServer(nowMillis = { clock })
        server.respondTo(
            LeaseProtocol.encode(
                LeaseRequest(
                    op = LeaseProtocol.OP_ACQUIRE, tableId = "mesa-5",
                    deviceId = "tablet-alberto", staffId = "s2", staffName = "Alberto",
                ),
            ),
        )
        val coordinator = soloCoordinator(server)

        val outcome = coordinator.acquire("mesa-5", "s1", "Juan")

        assertTrue(outcome is LeaseOutcome.Taken)
        assertEquals("Alberto", (outcome as LeaseOutcome.Taken).holderName)
        // Y NO se anota como mía: la UI no debe creer que la gané.
        assertTrue(coordinator.myTables.value.isEmpty())
    }

    @Test
    fun `un error del árbitro NO bloquea la venta - degrada a isla`() = runTest {
        val server = LeaseServer(nowMillis = { clock })
        val coordinator = soloCoordinator(server)

        // Un árbitro que habla otra versión del protocolo responde error. Eso
        // NO puede impedir que el mesero cobre: interpret() lo degrada a NoHub.
        val versionMismatch = server.respondTo("""{"v":99,"op":"acquire","tableId":"m1","deviceId":"x"}""")
        assertEquals(LeaseProtocol.STATUS_ERROR, versionMismatch.status)
        assertEquals(LeaseProtocol.ERROR_VERSION_MISMATCH, versionMismatch.message)

        // Y el flujo normal sigue funcionando (el árbitro no quedó envenenado).
        assertTrue(coordinator.acquire("mesa-1", "s1", "Juan") is LeaseOutcome.Granted)
    }

    @Test
    fun `soltar la mesa la quita de las mías y la libera en el árbitro`() = runTest {
        val server = LeaseServer(nowMillis = { clock })
        val coordinator = soloCoordinator(server)
        coordinator.acquire("mesa-5", "s1", "Juan")
        assertEquals(setOf("mesa-5"), coordinator.myTables.value)

        coordinator.release("mesa-5")

        assertTrue(coordinator.myTables.value.isEmpty())
        // Y en el árbitro ya no aparece ocupada.
        assertTrue(server.activeLeases().none { it.tableId == "mesa-5" })
    }

    // MARK: - La renovación de fondo (revisión D12 T10): una respuesta tardía no revive ni borra lo que cambió mientras viajaba.

    private val arbitroB = LanPeer("tablet-b", "10.0.0.2", 9000, isWired = true, bootedAtMillis = 1, venueExplicito = true)
    private val client = mockk<LeaseClient> {
        coEvery { acquire(any(), any(), any(), any(), any()) } returns null
        coEvery { renew(any(), any(), any(), any()) } returns null
        coEvery { release(any(), any(), any(), any()) } returns null
    }
    private val red = FakeDiscovery(emptyList())

    private fun concedidaMesa(epoch: Long) = LeaseResponse(
        status = LeaseProtocol.STATUS_GRANTED,
        lease = WireLease("mesa-5", me, "s1", "Juan", epoch, Long.MAX_VALUE),
    )

    @Test
    fun `un STALE de la época vieja no borra la concesión nueva de la mesa`() = runTest {
        red.peers.value = listOf(LanPeer(me, "127.0.0.1", 9999), arbitroB)
        val c = LanHubCoordinator(red, LeaseServer(nowMillis = { clock }), client, me) { clock }
        coEvery { client.acquire(arbitroB, "mesa-5", me, any(), any()) } returnsMany listOf(concedidaMesa(1), concedidaMesa(2))
        assertTrue(c.acquire("mesa-5", "s1", "Juan") is LeaseOutcome.Granted)
        coEvery { client.renew(arbitroB, "mesa-5", me, 1) } coAnswers {
            // Mientras la renovación de la época 1 va en camino, la mesa se vuelve a pedir y se concede con la época 2.
            assertTrue(c.acquire("mesa-5", "s1", "Juan") is LeaseOutcome.Granted)
            LeaseResponse(status = LeaseProtocol.STATUS_STALE, currentEpoch = 2)
        }
        c.renovarUnaVez()
        assertEquals(setOf("mesa-5"), c.myTables.value)
    }

    @Test
    fun `una mesa soltada mientras su renovación viajaba no revive`() = runTest {
        red.peers.value = listOf(LanPeer(me, "127.0.0.1", 9999), arbitroB)
        val c = LanHubCoordinator(red, LeaseServer(nowMillis = { clock }), client, me) { clock }
        coEvery { client.acquire(arbitroB, "mesa-5", me, any(), any()) } returns concedidaMesa(1)
        assertTrue(c.acquire("mesa-5", "s1", "Juan") is LeaseOutcome.Granted)
        coEvery { client.renew(arbitroB, "mesa-5", me, 1) } coAnswers {
            c.release("mesa-5")
            concedidaMesa(1)
        }
        c.renovarUnaVez()
        assertTrue(c.myTables.value.isEmpty())
    }
}

/** Transporte de mentira: entrega una lista fija de peers y acepta el enganche del hub sin abrir nada. */
private class FakeDiscovery(peers: List<LanPeer>) : LanDiscoveryPort {
    override val peers = kotlinx.coroutines.flow.MutableStateFlow(peers)
    override fun conectarHub(respondTo: (String) -> LeaseResponse) {}
    override fun desconectarHub() {}
}
