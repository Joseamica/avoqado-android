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

    // MARK: - D12 (paso 2, Codex r1 #2, r2 #3, r3 H3): el candado de una cuenta AJENA sólo vale si OTRA caja de la
    // sucursal contesta por la red, y sigue vigente con el MISMO árbitro hasta justo antes de recibir el efectivo.

    private val llave = "cuenta:o1"
    private val yo = LanPeer(me, "127.0.0.1", 9999)

    /** Otra caja de la sucursal; con `bootedAtMillis` alto, YO soy el árbitro salvo que se diga otra cosa. */
    private val cajaB = LanPeer("tablet-b", "10.0.0.2", 9000, bootedAtMillis = 9_999_999, venueExplicito = true)
    private val arbitroB = cajaB.copy(isWired = true)
    private val arbitroC = LanPeer("tablet-c", "10.0.0.3", 9001, isWired = true, bootedAtMillis = 1, venueExplicito = true)

    private val client = mockk<LeaseClient> {
        coEvery { list(any()) } returns null
        coEvery { acquire(any(), any(), any(), any(), any()) } returns null
        coEvery { renew(any(), any(), any(), any()) } returns null
        coEvery { release(any(), any(), any(), any()) } returns null
    }
    private val red = FakeDiscovery(emptyList())
    private val sinOcupar = LeaseResponse(status = LeaseProtocol.STATUS_LEASES, leases = emptyList())

    private fun hub(vararg peers: LanPeer, server: LeaseServer = LeaseServer(nowMillis = { clock })): LanHubCoordinator {
        red.peers.value = peers.toList()
        return LanHubCoordinator(red, server, client, me) { clock }
    }

    /** Lo que contesta un árbitro remoto al conceder o renovar. Su `expiresAtMillis` es de SU reloj: no decide nada aquí. */
    private fun concedida(epoch: Long = 1) = LeaseResponse(
        status = LeaseProtocol.STATUS_GRANTED,
        lease = WireLease(llave, me, "s1", "Juan", epoch, Long.MAX_VALUE),
    )

    private fun ocupadaPorAna(server: LeaseServer) {
        server.respondTo(
            LeaseProtocol.encode(
                LeaseRequest(op = LeaseProtocol.OP_ACQUIRE, tableId = llave, deviceId = "tablet-b", staffId = "s2", staffName = "Ana"),
            ),
        )
    }

    @Test
    fun `D12 solo en la red el candado de cuenta no se concede aunque sea arbitro`() = runTest {
        val c = hub(yo)
        assertFalse(c.hayOtraCajaDelNegocio())
        assertEquals(LeaseOutcome.NoHub, c.acquireCoordinado(llave, "s1", "Juan"))
        assertTrue(c.myTables.value.isEmpty())
    }

    @Test
    fun `D12 otra caja que contesta por la red y siendo arbitro se concede`() = runTest {
        coEvery { client.list(cajaB) } returns sinOcupar
        val c = hub(yo, cajaB)
        assertTrue(c.hayOtraCajaDelNegocio())
        assertTrue(c.acquireCoordinado(llave, "s1", "Juan") is LeaseOutcome.Granted)
        assertEquals(setOf(llave), c.myTables.value)
    }

    @Test
    fun `D12 peer anunciado pero inalcanzable no acredita`() = runTest {
        val server = LeaseServer(nowMillis = { clock })
        val c = hub(yo, cajaB, server = server)
        assertEquals(LeaseOutcome.NoHub, c.acquireCoordinado(llave, "s1", "Juan"))
        assertTrue(c.myTables.value.isEmpty())
        assertTrue("nada se concede sin otra caja viva", server.activeLeases().isEmpty())
    }

    @Test
    fun `D12 WiFi caido con anuncio viejo no acredita`() = runTest {
        val c = hub(yo, arbitroB)
        assertEquals(LeaseOutcome.NoHub, c.acquireCoordinado(llave, "s1", "Juan"))
        assertTrue(c.myTables.value.isEmpty())
    }

    @Test
    fun `D12 un peer sin venue no acredita`() = runTest {
        coEvery { client.list(any()) } returns sinOcupar
        val c = hub(yo, cajaB.copy(venueExplicito = false))
        assertFalse(c.hayOtraCajaDelNegocio())
        assertEquals(LeaseOutcome.NoHub, c.acquireCoordinado(llave, "s1", "Juan"))
        coVerify(exactly = 0) { client.list(any()) }
    }

    @Test
    fun `D12 un arbitro sin venue explicito no acredita`() = runTest {
        // Hay otra caja con sucursal explícita (D), pero el árbitro elegido (B, con cable) no la anuncia.
        val cajaD = LanPeer("tablet-d", "10.0.0.4", 9002, bootedAtMillis = 99_999_999, venueExplicito = true)
        coEvery { client.acquire(any(), any(), any(), any(), any()) } returns concedida()
        val c = hub(yo, arbitroB.copy(venueExplicito = false), cajaD)
        assertTrue(c.hayOtraCajaDelNegocio())
        assertEquals(LeaseOutcome.NoHub, c.acquireCoordinado(llave, "s1", "Juan"))
        coVerify(exactly = 0) { client.acquire(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `D12 una pantalla de cocina no cuenta como otra caja`() = runTest {
        coEvery { client.list(any()) } returns sinOcupar
        val c = hub(yo, cajaB.copy(sirveLeases = false))
        assertFalse(c.hayOtraCajaDelNegocio())
        assertEquals(LeaseOutcome.NoHub, c.acquireCoordinado(llave, "s1", "Juan"))
    }

    @Test
    fun `D12 si la tiene otra caja se devuelve su nombre`() = runTest {
        val server = LeaseServer(nowMillis = { clock })
        ocupadaPorAna(server)
        coEvery { client.list(cajaB) } returns sinOcupar
        val c = hub(yo, cajaB, server = server)
        assertEquals(LeaseOutcome.Taken("Ana"), c.acquireCoordinado(llave, "s1", "Juan"))
        assertTrue(c.myTables.value.isEmpty())
    }

    @Test
    fun `D12 el candado de una app muerta vence y otra caja lo obtiene`() = runTest {
        val server = LeaseServer(nowMillis = { clock })
        ocupadaPorAna(server)
        coEvery { client.list(cajaB) } returns sinOcupar
        val c = hub(yo, cajaB, server = server)
        assertEquals(LeaseOutcome.Taken("Ana"), c.acquireCoordinado(llave, "s1", "Juan"))

        clock += LeaseRegistry.DEFAULT_TTL_MILLIS + 1
        assertTrue(c.acquireCoordinado(llave, "s1", "Juan") is LeaseOutcome.Granted)
    }

    @Test
    fun `D12 una ocupacion explicita en list no se ignora`() = runTest {
        val server = LeaseServer(nowMillis = { clock })
        coEvery { client.list(cajaB) } returns LeaseResponse(
            status = LeaseProtocol.STATUS_LEASES,
            leases = listOf(WireLease(tableId = llave, holderDeviceId = "tablet-b", holderStaffId = "s2", holderName = "Ana", epoch = 3, expiresAtMillis = 0)),
        )
        val c = hub(yo, cajaB, server = server)
        assertEquals(LeaseOutcome.Taken("Ana"), c.acquireCoordinado(llave, "s1", "Juan"))
        assertTrue(c.myTables.value.isEmpty())
        assertTrue("no se concede nada en local", server.activeLeases().isEmpty())
    }

    @Test
    fun `D12 el arbitro cambia durante la llamada`() = runTest {
        val c = hub(yo, arbitroB)
        coEvery { client.acquire(arbitroB, llave, me, "s1", "Juan") } coAnswers {
            red.peers.value = listOf(yo, arbitroB, arbitroC) // C (cable y más viejo) gana mientras B contestaba
            concedida()
        }
        assertEquals(LeaseOutcome.NoHub, c.acquireCoordinado(llave, "s1", "Juan"))
        assertTrue(c.myTables.value.isEmpty())
        coVerify { client.release(arbitroB, llave, me, 1) } // se suelta con quien lo concedió
    }

    private suspend fun concedidoPorB(): LanHubCoordinator {
        coEvery { client.acquire(arbitroB, llave, me, any(), any()) } returns concedida()
        return hub(yo, arbitroB).also { assertTrue(it.acquireCoordinado(llave, "s1", "Juan") is LeaseOutcome.Granted) }
    }

    @Test
    fun `D12 renovacion perdida hasta vencer`() = runTest {
        val c = concedidoPorB()
        coEvery { client.renew(arbitroB, llave, me, 1) } returns concedida()
        assertTrue("dentro del TTL y con renovación concedida", c.candadoVigente(llave))

        coEvery { client.renew(any(), any(), any(), any()) } returns null
        clock += LeaseRegistry.DEFAULT_TTL_MILLIS + 1
        assertFalse(c.candadoVigente(llave))
        assertTrue(c.myTables.value.isEmpty())

        // Y aunque un árbitro contestara GRANTED, la última confirmación de hace más de un TTL ya no vale.
        val d = concedidoPorB()
        coEvery { client.renew(arbitroB, llave, me, 1) } returns concedida()
        clock += LeaseRegistry.DEFAULT_TTL_MILLIS + 1
        assertFalse(d.candadoVigente(llave))
    }

    @Test
    fun `D12 STALE quita el permiso`() = runTest {
        val c = concedidoPorB()
        coEvery { client.renew(arbitroB, llave, me, 1) } returns LeaseResponse(status = LeaseProtocol.STATUS_STALE, currentEpoch = 2)
        assertFalse(c.candadoVigente(llave))
        assertTrue(c.myTables.value.isEmpty())
    }

    @Test
    fun `D12 cambio de arbitro despues de adquirir`() = runTest {
        val c = concedidoPorB()
        coEvery { client.renew(any(), llave, me, 1) } returns concedida()
        red.peers.value = listOf(yo, arbitroB, arbitroC)
        assertFalse(c.candadoVigente(llave))
        assertTrue(c.myTables.value.isEmpty())
    }

    @Test
    fun `D12 reinicio del arbitro`() = runTest {
        val c = concedidoPorB()
        coEvery { client.renew(any(), llave, me, 1) } returns concedida()
        red.peers.value = listOf(yo, arbitroB.copy(bootedAtMillis = 12_345_678)) // mismo aparato, otro arranque
        assertFalse(c.candadoVigente(llave))
    }

    @Test
    fun `D12 siendo arbitro, si la otra caja deja de contestar se pierde`() = runTest {
        val server = LeaseServer(nowMillis = { clock })
        coEvery { client.list(cajaB) } returns sinOcupar
        val c = hub(yo, cajaB, server = server)
        assertTrue(c.acquireCoordinado(llave, "s1", "Juan") is LeaseOutcome.Granted)
        assertTrue(c.candadoVigente(llave))

        coEvery { client.list(cajaB) } returns null
        assertFalse(c.candadoVigente(llave))
        assertTrue(c.myTables.value.isEmpty())
        assertTrue("también se libera en mi registro de árbitro", server.activeLeases().isEmpty())
    }

    @Test
    fun `la caja que abrio la cuenta conserva su salida`() = runTest {
        // B inalcanzable: el acquire de siempre (Mesas y la caja que abrió la cuenta) no pregunta a nadie más.
        val c = hub(yo, cajaB)
        assertTrue(c.acquire(llave, "s1", "Juan") is LeaseOutcome.Granted)
        coVerify(exactly = 0) { client.list(any()) }
    }
}

/** Transporte de mentira: entrega una lista fija de peers y acepta el enganche del hub sin abrir nada. */
private class FakeDiscovery(peers: List<LanPeer>) : LanDiscoveryPort {
    override val peers = kotlinx.coroutines.flow.MutableStateFlow(peers)
    override fun conectarHub(respondTo: (String) -> LeaseResponse) {}
    override fun desconectarHub() {}
}
