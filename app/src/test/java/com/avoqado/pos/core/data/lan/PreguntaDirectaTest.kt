package com.avoqado.pos.core.data.lan

import com.avoqado.pos.tables.data.TableSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hub D6 bandas (hz:033d44ca:D6, ensayo del 16-nov, fila «A1 · sin internet, N86 + D3»). Con el WiFi que aísla la banda de 5 GHz la D3
 * y la SAMSUNG no se ven; las dos ven a la N86. Cada caja elige árbitro con SUS peers: la N86 le declaraba a la SAMSUNG y la D3 se
 * preguntaba a sí misma, así que abrió la mesa del mesero sin aviso. Ahora cada apertura también le pregunta `en_mesa` DIRECTO a cada
 * caja que alcanza. Tiempo virtual.
 */
class PreguntaDirectaTest {

    private val me = "d3-caja"
    /** La D3 arbitra para sí misma (cableada gana): es el caso medido. */
    private val yo = LanPeer(me, "127.0.0.1", 9999, isWired = true, presencia = true, venueExplicito = true)
    private val n86 = LanPeer("n86-mesero", "192.168.100.152", 33513, bootedAtMillis = 5, presencia = true, venueExplicito = true)
    private val vidas = ContadorDeVidas(leer = { 0L }, guardar = { true })

    private fun TestScope.caja(mesaActual: () -> String? = { null }) = MesasDeEstaCaja(mesaActual, flowOf(null), vidas) { testScheduler.currentTime }

    private fun TestScope.coordinador(peers: List<LanPeer>, mesas: MesasDeEstaCaja, client: LeaseClient, discovery: FakeDiscovery = FakeDiscovery(peers)) =
        LanHubCoordinator(
            discovery = discovery,
            mesas = mesas,
            deviceId = me,
            venueId = "V1",
            nombre = { "Cashier" },
            client = client,
            server = LeaseServer(deviceId = me, monoMillis = { testScheduler.currentTime }),
            relojMs = { testScheduler.currentTime },
            scope = backgroundScope,
        )

    private fun estoy(mesa: String, quien: String) =
        LeaseResponse(status = LeaseProtocol.STATUS_OK, holder = WireLease(mesa, "n86-mesero", "", quien, 0, 0))

    @Test
    fun `P1 EL CASO - el árbitro (yo) la concede pero la N86 dice que está dentro - ocupada con su nombre`() = runTest {
        val client = mockk<LeaseClient> { coEvery { enMesa(n86, any()) } returns estoy("M2", "Waiter Venue 1") }
        val mesas = caja()
        coordinador(listOf(yo, n86), mesas, client).start()
        runCurrent()

        val a = mesas.abrirApertura("M2", "s1", "Cashier", "V1")

        assertEquals(LeaseOutcome.Taken("Waiter Venue 1"), mesas.esperar(a) { true })
        coVerify { client.enMesa(n86, match { it.op == LeaseProtocol.OP_EN_MESA && it.tableId == "M2" && it.venueId == "V1" && it.deviceId == me }) }
    }

    @Test
    fun `nadie dice estar - se concede como siempre`() = runTest {
        val client = mockk<LeaseClient> { coEvery { enMesa(any(), any()) } returns LeaseResponse(status = LeaseProtocol.STATUS_OK) }
        val mesas = caja()
        coordinador(listOf(yo, n86), mesas, client).start()
        runCurrent()
        assertEquals(LeaseOutcome.Granted, mesas.esperar(mesas.abrirApertura("M2", "s1", "Cashier", "V1")) { true })
    }

    @Test
    fun `degradar nunca bloquear - la caja preguntada no contesta, contesta error o es app vieja - se concede`() = runTest {
        for (respuesta in listOf(
            null,
            LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = "Operación desconocida: en_mesa"),
            LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = LeaseProtocol.ERROR_VERSION_MISMATCH),
        )) {
            val client = mockk<LeaseClient> { coEvery { enMesa(any(), any()) } returns respuesta }
            val mesas = caja()
            coordinador(listOf(yo, n86), mesas, client).start()
            runCurrent()
            assertEquals("respuesta $respuesta", LeaseOutcome.Granted, mesas.esperar(mesas.abrirApertura("M2", "s1", "Cashier", "V1")) { true })
        }
        // Y si el cliente truena (p. ej. sin red local), tampoco.
        val truena = mockk<LeaseClient> { coEvery { enMesa(any(), any()) } throws java.io.IOException("sin ruta") }
        val mesas = caja()
        coordinador(listOf(yo, n86), mesas, truena).start()
        runCurrent()
        assertEquals(LeaseOutcome.Granted, mesas.esperar(mesas.abrirApertura("M2", "s1", "Cashier", "V1")) { true })
    }

    @Test
    fun `un «estoy» de OTRA mesa no cuenta`() = runTest {
        val client = mockk<LeaseClient> { coEvery { enMesa(any(), any()) } returns estoy("M9", "Waiter Venue 1") }
        val mesas = caja()
        coordinador(listOf(yo, n86), mesas, client).start()
        runCurrent()
        assertEquals(LeaseOutcome.Granted, mesas.esperar(mesas.abrirApertura("M2", "s1", "Cashier", "V1")) { true })
    }

    @Test
    fun `sólo se le pregunta a cajas de esta sucursal que hablan presencia y coordinan mesas`() = runTest {
        val client = mockk<LeaseClient> { coEvery { enMesa(any(), any()) } returns estoy("M2", "X") }
        val sinPm = LanPeer("vieja", "10.0.0.5", 1, presencia = false, venueExplicito = true)
        val cocina = LanPeer("kds", "10.0.0.6", 1, sirveLeases = false, presencia = true, venueExplicito = true)
        val sinVenue = LanPeer("sin-venue", "10.0.0.7", 1, presencia = true, venueExplicito = false)
        val mesas = caja()
        coordinador(listOf(yo, sinPm, cocina, sinVenue), mesas, client).start()
        runCurrent()
        assertEquals(LeaseOutcome.Granted, mesas.esperar(mesas.abrirApertura("M2", "s1", "Cashier", "V1")) { true })
        coVerify(exactly = 0) { client.enMesa(any(), any()) }
    }

    @Test
    fun `la concesión del árbitro espera a la pregunta directa, y una «sí» que llega después gana`() = runTest {
        val directa = CompletableDeferred<LeaseResponse?>()
        val client = mockk<LeaseClient> { coEvery { enMesa(any(), any()) } coAnswers { directa.await() } }
        val mesas = caja()
        coordinador(listOf(yo, n86), mesas, client).start()
        runCurrent()

        val a = mesas.abrirApertura("M2", "s1", "Cashier", "V1")
        val veredicto = async { mesas.esperar(a) { true } }
        // El árbitro (yo) calienta 12 s esperando a oír a la N86 y luego concede; la N86 todavía no contesta la pregunta directa.
        testScheduler.advanceTimeBy(13_000); runCurrent()
        assertFalse("el árbitro ya concedió, pero falta la N86", veredicto.isCompleted)

        directa.complete(estoy("M2", "Waiter Venue 1"))
        runCurrent()
        assertEquals(LeaseOutcome.Taken("Waiter Venue 1"), veredicto.await())
    }

    @Test
    fun `una «ocupada» del árbitro no espera a la pregunta directa`() = runTest {
        val directa = CompletableDeferred<LeaseResponse?>()
        val client = mockk<LeaseClient> { coEvery { enMesa(any(), any()) } coAnswers { directa.await() } }
        val mesas = caja()
        val server = LeaseServer(deviceId = me, monoMillis = { testScheduler.currentTime })
        LanHubCoordinator(FakeDiscovery(listOf(yo, n86)), mesas, me, "V1", { "Cashier" }, client, server,
            relojMs = { testScheduler.currentTime }, scope = backgroundScope).start()
        runCurrent()
        // Alberto (otra caja) le declara a este árbitro que está en la M2.
        server.respondTo(LeaseProtocol.encode(LeaseRequest(op = LeaseProtocol.OP_ESTADO, deviceId = "alberto", staffName = "Alberto", gen = 1, seq = 1, mesas = listOf("M2"))))

        assertEquals(LeaseOutcome.Taken("Alberto"), mesas.esperar(mesas.abrirApertura("M2", "s1", "Cashier", "V1")) { true })
        directa.complete(null)
    }

    @Test
    fun `sin coordinador la apertura no espera ninguna pregunta directa`() = runTest {
        val mesas = caja()
        val a = mesas.abrirApertura("M2", "s1", "Cashier", "V1")
        mesas.resolver(a.id, LeaseOutcome.Granted)
        assertEquals(LeaseOutcome.Granted, mesas.esperar(a) { true })
    }

    @Test
    fun `al apagarse el coordinador las aperturas nuevas ya no esperan pregunta`() = runTest {
        val mesas = caja()
        val c = coordinador(listOf(yo, n86), mesas, mockk(relaxed = true))
        c.start()
        runCurrent()
        c.stop()
        val a = mesas.abrirApertura("M2", "s1", "Cashier", "V1")
        mesas.resolver(a.id, LeaseOutcome.NoHub)
        assertEquals(LeaseOutcome.NoHub, mesas.esperar(a) { true })
    }

    // MARK: - Lo que contesta esta caja

    private fun TestScope.contesta(mesas: MesasDeEstaCaja): (String) -> LeaseResponse {
        val discovery = FakeDiscovery(listOf(yo))
        coordinador(listOf(yo), mesas, mockk(relaxed = true), discovery).start()
        runCurrent()
        return discovery.hub!!
    }

    private fun pregunta(mesa: String, venue: String = "V1") =
        LeaseProtocol.encode(LeaseRequest(op = LeaseProtocol.OP_EN_MESA, tableId = mesa, deviceId = "otra", venueId = venue))

    @Test
    fun `contesta «estoy» de su mesa actual con el nombre de quien la opera`() = runTest {
        val sesion = TableSession()
        val mesas = MesasDeEstaCaja({ sesion.current()?.tableId }, sesion.active.map { it?.tableId }, vidas) { testScheduler.currentTime }
        val hub = contesta(mesas)
        sesion.start(TableSession.Active(tableId = "M2", tableNumber = "2", areaName = null, orderId = "o", orderNumber = "1", version = 1, totalCents = 0, mode = TableSession.Mode.ORDERING), "V1")

        val r = hub(pregunta("M2"))
        assertEquals(LeaseProtocol.STATUS_OK, r.status)
        assertEquals("M2", r.holder?.tableId)
        assertEquals("Cashier", r.holder?.holderName)
        assertEquals(me, r.holder?.holderDeviceId)
        assertNull("de otra mesa no", hub(pregunta("M3")).holder)
    }

    @Test
    fun `contesta «estoy» de una apertura concedida o en isla, no de una pendiente ni de una ocupada`() = runTest {
        val mesas = caja()
        val hub = contesta(mesas)
        mesas.consultaDirecta = false // para resolver a mano sin la pregunta de este mismo coordinador
        val pendiente = mesas.abrirApertura("M4", "s", "Cashier", "V1")
        assertNull(hub(pregunta("M4")).holder)
        mesas.resolver(pendiente.id, LeaseOutcome.Granted)
        assertEquals("M4", hub(pregunta("M4")).holder?.tableId)

        val isla = mesas.abrirApertura("M5", "s", "Cashier", "V1")
        mesas.resolver(isla.id, LeaseOutcome.NoHub)
        assertEquals("M5", hub(pregunta("M5")).holder?.tableId)

        val ocupada = mesas.abrirApertura("M6", "s", "Cashier", "V1")
        mesas.resolver(ocupada.id, LeaseOutcome.Taken("Otro"))
        assertNull(hub(pregunta("M6")).holder)

        mesas.terminarApertura(pendiente)
        assertNull("terminada (sin sesión) ya no", hub(pregunta("M4")).holder)
    }

    @Test
    fun `otra sucursal, mesa vacía u otra versión - error, y las ops de siempre siguen yendo al árbitro`() = runTest {
        val mesas = caja { "M2" }
        val hub = contesta(mesas)
        assertEquals(LeaseProtocol.STATUS_ERROR, hub(pregunta("M2", venue = "V2")).status)
        assertEquals(LeaseProtocol.STATUS_ERROR, hub(pregunta("")).status)
        assertEquals(
            LeaseProtocol.ERROR_VERSION_MISMATCH,
            hub(LeaseProtocol.encode(LeaseRequest(version = 2, op = LeaseProtocol.OP_EN_MESA, tableId = "M2", venueId = "V1"))).message,
        )
        val lista = hub(LeaseProtocol.encode(LeaseRequest(op = LeaseProtocol.OP_LIST)))
        assertEquals(LeaseProtocol.STATUS_LEASES, lista.status)
    }

    // MARK: - Para la línea del plano

    @Test
    fun `las cajas que le declaran a este árbitro, sin él mismo`() = runTest {
        val mesas = caja()
        val discovery = FakeDiscovery(listOf(yo))
        val c = coordinador(listOf(yo), mesas, mockk(relaxed = true), discovery)
        c.start()
        runCurrent()
        assertTrue(c.cajasQueLeDeclaran().isEmpty())
        discovery.hub!!(LeaseProtocol.encode(LeaseRequest(op = LeaseProtocol.OP_ESTADO, deviceId = "n86-mesero", staffName = "W", gen = 1, seq = 1)))
        assertEquals(setOf("n86-mesero"), c.cajasQueLeDeclaran())
        testScheduler.advanceTimeBy(LeaseRegistry.DEFAULT_TTL_MILLIS + 1)
        assertTrue("a los 30 s sin avisar ya no cuenta", c.cajasQueLeDeclaran().isEmpty())
    }
}
