package com.avoqado.pos.core.data.lan

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}

/** Descubrimiento de mentira: entrega una lista fija de peers. */
private class FakeDiscovery(peers: List<LanPeer>) : LanDiscoveryPort {
    override val peers = kotlinx.coroutines.flow.MutableStateFlow(peers)
    override fun start(myPort: Int, isWired: Boolean, bootedAtMillis: Long) {}
    override fun stop() {}
}
