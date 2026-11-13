package com.avoqado.pos.core.data.lan

import com.avoqado.pos.core.data.lan.LeaseProtocol.OP_ACQUIRE
import com.avoqado.pos.core.data.lan.LeaseProtocol.OP_ESTADO
import com.avoqado.pos.core.data.lan.LeaseProtocol.OP_LIST
import com.avoqado.pos.core.data.lan.LeaseProtocol.OP_RELEASE
import com.avoqado.pos.core.data.lan.LeaseProtocol.OP_RENEW
import com.avoqado.pos.tables.data.TableSession
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hub D3 · modo compatible (spec §3.5): con un árbitro VIEJO (sin `pm=1`), el MISMO reconciliador serial traduce la declaración de
 * la caja a permisos viejos (`acquire`, `renew`, `release`), un mensaje a la vez, y revisa el árbitro y la época después de CADA
 * espera de red. Un `denied` sólo vale en su vuelta.
 *
 * Montaje: el de `ReconciliadorDeMesasTest` (tiempo virtual y la [Red] que entrega cada mensaje a un [LeaseServer] real). El árbitro
 * viejo es un [LeaseServer] con su reloj de pared también virtual (los permisos vencen con él) y su peer va con `presencia = false`;
 * B es una app vieja que le habla directo con sus `acquire`/`release`.
 */
class ModoCompatibleTest {

    private val v = "V1"
    private val sesion = TableSession()
    private val vidas = ContadorDeVidas(leer = { 0L }, guardar = { true })
    private val yo = LanPeer("A", "10.0.0.1", 9000, presencia = true)
    private val viejoX = LanPeer("X", "10.0.0.9", 9000, isWired = true)
    private val viejoY = LanPeer("Y", "10.0.0.8", 9000, isWired = true)
    private val nuevoY = LanPeer("Y", "10.0.0.8", 9000, isWired = true, presencia = true)
    private val discovery = FakeDiscovery(listOf(yo, viejoX))

    // MARK: - Montaje

    private fun TestScope.arbitro(id: String) =
        LeaseServer(nowMillis = { testScheduler.currentTime }, deviceId = id, monoMillis = { testScheduler.currentTime })
            .also { it.asumirArbitraje(emptySet()) }

    private fun TestScope.caja() =
        MesasDeEstaCaja({ sesion.current()?.tableId }, sesion.active.map { it?.tableId }, vidas) { testScheduler.currentTime }

    private fun TestScope.red(arbitros: Map<String, LeaseServer>) = Red(arbitros) { testScheduler.currentTime }

    private fun TestScope.coordinador(red: Red, mesas: MesasDeEstaCaja) = LanHubCoordinator(
        discovery = discovery,
        mesas = mesas,
        deviceId = "A",
        venueId = v,
        nombre = { "Ana" },
        client = red.client,
        relojMs = { testScheduler.currentTime },
        scope = backgroundScope,
    )

    private fun entra(mesa: String) = sesion.start(
        TableSession.Active(
            tableId = mesa, tableNumber = mesa, areaName = null, orderId = "o-$mesa", orderNumber = "1",
            version = 1, totalCents = 0, mode = TableSession.Mode.ORDERING,
        ),
        v,
    )

    private fun sale() = assertTrue(sesion.estacionar(v, sesion.current()!!))

    /** B (app vieja) toma [mesa] con su acquire de siempre; devuelve su época. */
    private fun LeaseServer.tomaB(mesa: String): Long = respondTo(
        LeaseProtocol.encode(LeaseRequest(op = OP_ACQUIRE, tableId = mesa, deviceId = "B", staffId = "sB", staffName = "Beto")),
    ).lease!!.epoch

    private fun LeaseServer.sueltaB(mesa: String, epoca: Long) =
        respondTo(LeaseProtocol.encode(LeaseRequest(op = OP_RELEASE, tableId = mesa, deviceId = "B", epoch = epoca)))

    private fun LeaseServer.lista() = respondTo(LeaseProtocol.encode(LeaseRequest(op = OP_LIST))).leases.orEmpty().map { it.tableId }

    private fun LeaseServer.deQuien() = activeLeases().map { it.tableId to it.holderDeviceId }

    private fun Red.ops(desde: Int = 0) = recibidos.drop(desde).map { it.op to it.tableId }

    // MARK: - Codex código r1 #3: con un árbitro viejo, revisar la época y aplicar también son UNA operación

    @Test
    fun `r1-3 compat - otro hilo cambia la época justo después de validar - nada de X se aplica después de la época nueva`() = runTest {
        val x = arbitro("X")
        x.tomaB("m6")
        val mesas = caja()
        val c = coordinador(red(mapOf("X" to x)), mesas) // sin start(): la vuelta se corre a mano
        val apertura = mesas.abrirApertura("m6", "s1", "Ana", v)
        val epocaNueva = EpocaDesdeOtroHilo(c, mesas, v)

        c.reconciliarUnaVez()

        assertEquals("nuevaEpoca() regresó con la apertura pendiente y luego X la resolvió", false, epocaNueva.terminar())
        assertEquals("X la resolvió antes de la época nueva", LeaseOutcome.Taken("Beto"), mesas.esperar(apertura) { false })
    }

    // MARK: - p12a (spec-12)

    @Test
    fun `p12a - A abre m5 y queda suya en el registro, al salir la suelta, y la m6 de una app vieja da Taken`() = runTest {
        val x = arbitro("X")
        val red = red(mapOf("X" to x))
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()

        val m5 = mesas.abrirApertura("m5", "s1", "Ana", v)
        assertEquals(LeaseOutcome.Granted, mesas.esperar(m5) { true })
        assertEquals(listOf("m5" to "A"), x.deQuien())
        assertEquals("el staffId de la apertura", "s1", x.activeLeases().single().holderStaffId)
        entra("m5") // el éxito: primero la sesión, después se termina el ticket
        mesas.terminarApertura(m5)
        runCurrent()
        advanceTimeBy(45_000) // sin renovar habría vencido a los 30 s
        assertEquals(listOf("m5" to "A"), x.deQuien())
        assertTrue("se renueva cada 10 s", red.ops().count { it == OP_RENEW to "m5" } >= 4)
        assertTrue("nunca la soltó mientras estuvo dentro", red.ops().none { it.first == OP_RELEASE })

        sale()
        runCurrent()
        assertEquals(OP_RELEASE to "m5", red.ops().last())
        assertTrue(x.activeLeases().isEmpty())

        x.tomaB("m6")
        val m6 = mesas.abrirApertura("m6", "s1", "Ana", v)
        assertEquals(LeaseOutcome.Taken("Beto"), mesas.esperar(m6) { true })
        mesas.terminarApertura(m6)
        assertEquals(1, red.maxEnVuelo)
    }

    // MARK: - p12b

    @Test
    fun `p12b - el árbitro se reinicia - el renew da stale y en la misma vuelta sale el acquire y la mesa vuelve a ser de A`() = runTest {
        val arbitros = mutableMapOf("X" to arbitro("X"))
        val red = red(arbitros)
        entra("m5")
        coordinador(red, caja()).start()
        runCurrent()
        assertEquals(listOf(OP_ACQUIRE to "m5"), red.ops())
        assertEquals("una mesa en la que entró va sin staffId", "", arbitros.getValue("X").activeLeases().single().holderStaffId)

        val reiniciado = arbitro("X").also { arbitros["X"] = it } // perdió todo lo que tenía en memoria
        advanceTimeBy(10_000)
        runCurrent()

        assertEquals(listOf(OP_RENEW to "m5", OP_ACQUIRE to "m5"), red.ops(desde = 1))
        assertEquals(LeaseProtocol.STATUS_STALE, red.respuestaA(red.recibidos[1])?.status)
        assertEquals("en la misma vuelta", listOf(10_000L, 10_000L), red.envios.drop(1))
        assertEquals(listOf("m5" to "A"), reiniciado.deQuien())

        advanceTimeBy(45_000)
        assertEquals("la renueva con su época nueva", listOf("m5" to "A"), reiniciado.deQuien())
        assertTrue(red.respuestas.drop(3).all { it.second?.status == LeaseProtocol.STATUS_GRANTED })
    }

    @Test
    fun `p12b - un acquire que el árbitro procesa cuando A ya lo dejó nunca se renueva y a los 30 s list ya no lo trae`() = runTest {
        val x = arbitro("X")
        val red = red(mapOf("X" to x))
        val mesas = caja()
        val viejo = coordinador(red, mesas)
        viejo.start()
        runCurrent()

        red.retener = Red.Retener.IDA
        val m7 = mesas.abrirApertura("m7", "s1", "Ana", v)
        runCurrent()
        val tarde = red.retenidos.single()
        assertEquals(OP_ACQUIRE to "m7", tarde.pedido.op to tarde.pedido.tableId)
        mesas.terminarApertura(m7) // A desiste y su hub se rearma
        viejo.stop()
        red.retener = Red.Retener.NADA
        coordinador(red, mesas).start()
        runCurrent()

        red.soltar(tarde) // el árbitro lo procesa ahora
        runCurrent()
        assertEquals(listOf("m7"), x.lista())

        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(x.lista().isEmpty())
        assertTrue("nadie lo renueva", red.ops().none { it == OP_RENEW to "m7" })
    }

    // MARK: - p12c

    @Test
    fun `p12c - el árbitro pasa de viejo a nuevo - la siguiente vuelta manda estado y no vuelve a mandar release`() = runTest {
        val x = arbitro("X")
        val y = arbitro("Y")
        val red = red(mapOf("X" to x, "Y" to y))
        entra("m5")
        coordinador(red, caja()).start()
        runCurrent()
        assertEquals(listOf("m5" to "A"), x.deQuien())

        val desde = red.recibidos.size
        discovery.peers.value = listOf(yo, nuevoY)
        runCurrent()
        sale()
        runCurrent()
        advanceTimeBy(30_000)

        val despues = red.recibidos.drop(desde)
        assertEquals(OP_ESTADO, despues.first().op)
        assertEquals(listOf("m5"), despues.first().mesas)
        assertTrue("sólo estado: ${despues.map { it.op }}", despues.all { it.op == OP_ESTADO })
        assertEquals(emptyList<String>(), despues.last().mesas)
    }

    // MARK: - p12d (Codex plan r1 #2)

    @Test
    fun `p12d - el acquire a X queda retenido, la elección cambia a Y y luego X contesta granted - no cuenta`() = runTest {
        val x = arbitro("X")
        val y = arbitro("Y").also { it.tomaB("m14") }
        val red = red(mapOf("X" to x, "Y" to y))
        val mesas = caja()
        val a = coordinador(red, mesas)
        a.start()
        runCurrent()

        red.retener = Red.Retener.VUELTA
        val m14 = mesas.abrirApertura("m14", "s1", "Ana", v)
        runCurrent()
        assertEquals(LeaseProtocol.STATUS_GRANTED, red.respuestaA(red.retenidos.single().pedido)?.status)
        discovery.peers.value = listOf(yo, viejoY)
        runCurrent()
        red.retener = Red.Retener.NADA
        red.soltarTodo()
        runCurrent()

        assertEquals("lo de X no cuenta; en Y la tiene Beto", LeaseOutcome.Taken("Beto"), mesas.esperar(m14) { true })
        assertTrue(a.myTables.value.isEmpty())
    }

    @Test
    fun `p12d - X a Y a X - la respuesta de la primera X es de otra época y se descarta`() = runTest {
        val x = arbitro("X")
        val epocaB = x.tomaB("m14")
        val red = red(mapOf("X" to x, "Y" to arbitro("Y")))
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()

        red.retener = Red.Retener.VUELTA
        val m14 = mesas.abrirApertura("m14", "s1", "Ana", v)
        runCurrent()
        assertEquals(LeaseProtocol.STATUS_DENIED, red.respuestaA(red.retenidos.single().pedido)?.status)
        x.sueltaB("m14", epocaB) // B sale mientras el «denied» viaja
        discovery.peers.value = listOf(yo, viejoY)
        runCurrent()
        discovery.peers.value = listOf(yo, viejoX)
        runCurrent()
        red.retener = Red.Retener.NADA
        red.soltarTodo()
        runCurrent()

        assertEquals(LeaseOutcome.Granted, mesas.esperar(m14) { true })
        assertEquals(listOf("m14" to "A"), x.deQuien())
    }

    @Test
    fun `p12d - una vuelta que ya ve a Y antes de que suba la época no usa los permisos de X`() = runTest {
        val x = arbitro("X")
        val y = arbitro("Y")
        val red = red(mapOf("X" to x, "Y" to y))
        val mesas = caja()
        entra("m5")
        coordinador(red, mesas).start()
        runCurrent()
        assertEquals(listOf("m5" to "A"), x.deQuien())

        // El acquire de m6 a X: justo entonces la elección cambia a Y y X no contesta. La vuelta devuelve 0 y la siguiente sale sin
        // pausa, ANTES de que el colector de peers suba la época: ya ve a Y con la época de X.
        red.fija = {
            discovery.peers.value = listOf(yo, viejoY)
            red.fija = null
            null
        }
        val desde = red.recibidos.size
        mesas.abrirApertura("m6", "s1", "Ana", v)
        runCurrent()

        assertEquals(
            "m5 se pide otra vez a Y: lo retenido era de X",
            listOf(OP_ACQUIRE to "m6", OP_ACQUIRE to "m5", OP_ACQUIRE to "m6"),
            red.ops(desde).take(3),
        )
        assertEquals(listOf("m5" to "A", "m6" to "A"), y.deQuien())
    }

    // MARK: - release sin respuesta y renew negado

    @Test
    fun `release sin respuesta - la mesa que ya no se desea sale de retenidas y no se vuelve a soltar`() = runTest {
        val x = arbitro("X")
        val red = red(mapOf("X" to x))
        entra("m5")
        coordinador(red, caja()).start()
        runCurrent()
        assertEquals(listOf("m5" to "A"), x.deQuien())

        red.fija = { null } // X deja de contestar justo cuando A sale
        sale()
        runCurrent()
        red.fija = null
        advanceTimeBy(45_000)

        assertEquals("un solo release", 1, red.ops().count { it == OP_RELEASE to "m5" })
        assertTrue("ni se renueva", red.ops().drop(2).none { it.second == "m5" })
        assertTrue("sin renovar, vence sola", x.activeLeases().isEmpty())
    }

    @Test
    fun `renew negado - la mesa sale de retenidas y la apertura de esa vuelta da Taken`() = runTest {
        val arbitros = mutableMapOf("X" to arbitro("X"))
        val red = red(arbitros)
        val mesas = caja()
        entra("m5")
        coordinador(red, mesas).start()
        runCurrent()
        // X se reinicia y B (app vieja) toma m5 antes de que A la renueve.
        arbitros["X"] = arbitro("X").also { it.tomaB("m5") }
        val desde = red.recibidos.size

        advanceTimeBy(10_000)
        val apertura = mesas.abrirApertura("m5", "s1", "Ana", v) // va en la misma vuelta que el renew
        runCurrent()
        assertEquals(LeaseProtocol.STATUS_DENIED, red.respuestaA(red.recibidos[desde])?.status)
        assertEquals(LeaseOutcome.Taken("Beto"), mesas.esperar(apertura) { true })

        advanceTimeBy(10_000)
        runCurrent()
        val ops = red.ops(desde)
        assertEquals(OP_RENEW to "m5", ops.first())
        assertEquals("salió de retenidas: nunca se vuelve a renovar", 1, ops.count { it == OP_RENEW to "m5" })
        assertEquals("a los 20 s se pide de nuevo (el denied no se recuerda)", OP_ACQUIRE to "m5", ops.last())
    }

    // MARK: - p12e (Codex plan r1 #3)

    @Test
    fun `p12e - un denied no se recuerda - tras soltarla B, la nueva apertura de m13 sin respuesta espera y da NoHub a los 15 s`() = runTest {
        val x = arbitro("X")
        val epocaB = x.tomaB("m13")
        val red = red(mapOf("X" to x))
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()

        val primera = mesas.abrirApertura("m13", "s1", "Ana", v)
        assertEquals(LeaseOutcome.Taken("Beto"), mesas.esperar(primera) { true })
        mesas.terminarApertura(primera)
        x.sueltaB("m13", epocaB)
        red.fija = { null } // el árbitro deja de contestar
        runCurrent()

        val toque = testScheduler.currentTime
        val segunda = mesas.abrirApertura("m13", "s1", "Ana", v)
        assertEquals(LeaseOutcome.NoHub, mesas.esperar(segunda) { true })
        assertEquals(toque + MesasDeEstaCaja.PLAZO_PEDIR_MESA_MS, testScheduler.currentTime)
        assertTrue("se volvió a pedir", red.envios.count { it >= toque } >= 10)
    }
}
