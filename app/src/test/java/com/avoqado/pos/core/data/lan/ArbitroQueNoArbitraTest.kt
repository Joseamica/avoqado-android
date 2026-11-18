package com.avoqado.pos.core.data.lan

import com.avoqado.pos.tables.data.TableSession
import io.mockk.coEvery
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tarjeta de la nube na5a8c9b (encargo 0199df): cada caja elige árbitro con SUS peers. Con un WiFi que aísla una banda, la D3 elige a la
 * N86 y la N86 se elige otra (no asume el arbitraje): su [Presencias] contesta «esperar 1 s» a cada `estado` (`NO_ASUMIDO_MS`), cada
 * respuesta borraba el silencio de D14 y abrir una mesa esperaba los 15 s enteros («Revisando con las otras cajas…»). Ahora la respuesta
 * dice `arbitra: false` y cuenta como silencio: a los 3 s ([LeaseClient.PLAZO_HUB_MS]) la apertura sale en isla. El calentamiento de un
 * árbitro que SÍ asumió (12 s) no cambia, y una «ocupada» directa de la caja que está en la mesa sigue ganando.
 *
 * Montaje de [D14ArbitroCalladoTest] ([Red], tiempo virtual).
 */
class ArbitroQueNoArbitraTest {

    private val v = "V1"
    private val sesion = TableSession()
    private val vidas = ContadorDeVidas(leer = { 0L }, guardar = { true })
    private val yo = LanPeer("A", "10.0.0.1", 9000, presencia = true, venueExplicito = true)
    private val peerX = LanPeer("X", "10.0.0.9", 9000, isWired = true, presencia = true, venueExplicito = true)
    private val discovery = FakeDiscovery(listOf(yo, peerX))

    /** La N86 del ensayo: está en la lista y contesta al instante, pero eligió a otra caja (nunca asumió). */
    private fun TestScope.queNoArbitra(id: String) = LeaseServer(deviceId = id, monoMillis = { testScheduler.currentTime })

    private fun TestScope.red(x: LeaseServer, enMesa: suspend (LanPeer) -> LeaseResponse? = { delay(50); LeaseResponse(status = LeaseProtocol.STATUS_OK) }) =
        Red(mapOf("X" to x)) { testScheduler.currentTime }.also { r ->
            coEvery { r.client.enMesa(any(), any()) } coAnswers { enMesa(firstArg()) }
        }

    private fun TestScope.caja() =
        MesasDeEstaCaja({ sesion.current()?.tableId }, sesion.active.map { it?.tableId }, vidas) { testScheduler.currentTime }

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

    @Test
    fun `P1 EL CASO - la caja elegida no arbitra - la mesa abre en isla a los 3 s, no a los 15`() = runTest {
        val red = red(queNoArbitra("X"))
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()
        advanceTimeBy(4_000) // ya venía contestando «no arbitro» en los latidos

        val toque = testScheduler.currentTime
        val a = mesas.abrirApertura("M2", "s1", "Ana", v)
        assertEquals(LeaseOutcome.NoHub, mesas.esperar(a) { true })
        val tardo = testScheduler.currentTime - toque
        assertTrue("abrió a los $tardo ms (antes, 15 000)", tardo <= LeaseClient.PLAZO_HUB_MS + 1_000)
    }

    @Test
    fun `P1 recién elegida - si en 3 s asume el arbitraje manda su veredicto`() = runTest {
        val x = queNoArbitra("X")
        val red = red(x)
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()

        val a = mesas.abrirApertura("M2", "s1", "Ana", v)
        advanceTimeBy(1_500)
        x.asumirArbitraje(emptySet()) // a los 1.5 s se da cuenta de que es el árbitro
        // y otra caja (B) le declara la M2
        x.respondTo(LeaseProtocol.encode(LeaseRequest(op = LeaseProtocol.OP_ESTADO, deviceId = "B", staffName = "Beto", gen = 1, seq = 1, mesas = listOf("M2"))))
        assertEquals(LeaseOutcome.Taken("Beto"), mesas.esperar(a) { true })
    }

    @Test
    fun `P1 la caja que está en la mesa contesta directo y gana aunque el árbitro no arbitre`() = runTest {
        val peerB = LanPeer("B", "10.0.0.5", 9000, presencia = true, venueExplicito = true)
        discovery.publicar(listOf(yo, peerX, peerB))
        val red = red(queNoArbitra("X")) { p ->
            delay(300)
            if (p.deviceId == "B") LeaseResponse(status = LeaseProtocol.STATUS_OK, holder = WireLease("M2", "B", "", "Beto", 0, 0))
            else LeaseResponse(status = LeaseProtocol.STATUS_OK)
        }
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()
        advanceTimeBy(4_000)

        val a = mesas.abrirApertura("M2", "s1", "Ana", v)
        assertEquals(LeaseOutcome.Taken("Beto"), mesas.esperar(a) { true })
    }

    @Test
    fun `sin cambio - el árbitro que SÍ asumió y calienta sigue haciendo esperar hasta oír a las cajas o 12 s`() = runTest {
        val x = LeaseServer(deviceId = "X", monoMillis = { testScheduler.currentTime }).also { it.asumirArbitraje(setOf("Z")) }
        val red = red(x)
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()

        val toque = testScheduler.currentTime
        val a = mesas.abrirApertura("M2", "s1", "Ana", v)
        assertEquals(LeaseOutcome.Granted, mesas.esperar(a) { true })
        assertTrue("concedida al terminar el calentamiento, no en isla a los 3 s", testScheduler.currentTime - toque >= 11_000)
    }

    @Test
    fun `cable - sólo la caja que no arbitra manda arbitra false, la que arbitra no cambia ni un byte y lo viejo se lee igual`() = runTest {
        fun pedido() = LeaseProtocol.encode(LeaseRequest(op = LeaseProtocol.OP_ESTADO, deviceId = "A", staffName = "Ana", gen = 1, seq = 1,
            aperturas = listOf(WireApertura("a1", "M2"))))
        val noArbitra = queNoArbitra("X").respondTo(pedido())
        assertEquals(false, noArbitra.arbitra)
        assertTrue(LeaseProtocol.encode(noArbitra).contains("\"arbitra\":false"))

        val arbitra = LeaseServer(deviceId = "Y", monoMillis = { testScheduler.currentTime }).also { it.asumirArbitraje(emptySet()) }.respondTo(pedido())
        assertEquals(null, arbitra.arbitra)
        assertTrue("la respuesta del árbitro sale como antes", !LeaseProtocol.encode(arbitra).contains("arbitra\":"))

        // Una caja de antes (o iOS) no lo manda: se lee como null y la caja nueva espera como siempre.
        val vieja = LeaseProtocol.decodeResponse("""{"v":1,"status":"ok","arbitro":"X","aperturas":[{"id":"a1","resultado":"esperar","esperarMs":1000}]}""")
        assertEquals(null, vieja?.arbitra)
    }

    @Test
    fun `P1 Codex r1 2 - la caja que no arbitra pero tiene el permiso vivo de una caja vieja contesta ocupada`() = runTest {
        val x = queNoArbitra("X")
        // C, una app vieja (sin presencia), le pidió la M2 a X cuando la eligió como árbitro.
        x.respondTo(LeaseProtocol.encode(LeaseRequest(op = LeaseProtocol.OP_ACQUIRE, tableId = "M2", deviceId = "C", staffId = "sC", staffName = "Caro")))
        val red = red(x)
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()
        advanceTimeBy(4_000)

        val a = mesas.abrirApertura("M2", "s1", "Ana", v)
        assertEquals(LeaseOutcome.Taken("Caro"), mesas.esperar(a) { true })
    }

    @Test
    fun `P1 Codex r1 2 - con una caja de app vieja en la red se conserva la espera de hoy`() = runTest {
        val peerC = LanPeer("C", "10.0.0.7", 9000, presencia = false, venueExplicito = true)
        discovery.publicar(listOf(yo, peerX, peerC))
        val red = red(queNoArbitra("X"))
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()
        advanceTimeBy(4_000)

        val toque = testScheduler.currentTime
        val a = mesas.abrirApertura("M2", "s1", "Ana", v)
        assertEquals(LeaseOutcome.NoHub, mesas.esperar(a) { true })
        assertTrue("la caja vieja sólo la sabe un árbitro: no se corta a los 3 s", testScheduler.currentTime - toque >= 14_000)
    }

    @Test
    fun `P1 Codex r2 3 - negativas, aparece una caja vieja y se pierde un mensaje - no adelanta la isla`() = runTest {
        val x = queNoArbitra("X")
        val red = red(x)
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()
        advanceTimeBy(5_000) // X lleva 5 s diciendo «no arbitro» (latidos)

        discovery.publicar(listOf(yo, peerX, LanPeer("C", "10.0.0.7", 9000, presencia = false, venueExplicito = true)))
        red.latenciaMs = LeaseClient.PLAZO_HUB_MS
        red.fija = { red.latenciaMs = 0; red.fija = null; null } // se pierde UN mensaje
        val toque = testScheduler.currentTime
        val a = mesas.abrirApertura("M2", "s1", "Ana", v)
        assertEquals(LeaseOutcome.NoHub, mesas.esperar(a) { true })
        assertTrue("con la caja vieja, la espera de hoy (no la isla tras una pérdida)", testScheduler.currentTime - toque >= 14_000)
    }

    @Test
    fun `P1 Codex r3 1 - con negativas viejas, la apertura nueva todavía espera 3 s y una ocupada en ese lapso gana`() = runTest {
        val x = queNoArbitra("X")
        val red = red(x)
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()
        advanceTimeBy(5_000) // X lleva 5 s diciendo «no arbitro»

        val a = mesas.abrirApertura("M2", "s1", "Ana", v)
        advanceTimeBy(1_500)
        x.asumirArbitraje(emptySet()) // a los 1.5 s del toque X asume y B le declara la M2
        x.respondTo(LeaseProtocol.encode(LeaseRequest(op = LeaseProtocol.OP_ESTADO, deviceId = "B", staffName = "Beto", gen = 1, seq = 1, mesas = listOf("M2"))))
        assertEquals(LeaseOutcome.Taken("Beto"), mesas.esperar(a) { true })
    }
}
