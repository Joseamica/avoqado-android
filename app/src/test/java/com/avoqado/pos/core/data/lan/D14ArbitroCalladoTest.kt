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
import org.junit.Test

/**
 * D14 (hz:033d44ca:D14, recorrido del 16-nov, paso 7): la caja árbitro se queda sin WiFi y la otra todavía la tiene en su lista. Abrir
 * una mesa tardaba ~15 s con «Abriendo...»: cada `estado` al árbitro se quedaba 3 s sin respuesta, el reconciliador reintentaba y nada
 * resolvía la apertura hasta el plazo de 15 s. Ahora, cuando el mismo árbitro (misma época, misma elección) lleva 6 s sin contestar
 * nada de lo que se le mandó, las aperturas que viajaron se van a isla (lo mismo que pasaba a los 15 s). Un silencio corto se reintenta
 * y manda lo que diga el árbitro; una «ocupada» directa de la caja que está en la mesa sigue ganando.
 *
 * Montaje de `ReconciliadorDeMesasTest` ([Red], tiempo virtual). El árbitro «sin WiFi» tarda el plazo entero de un mensaje
 * ([LeaseClient.PLAZO_HUB_MS]) y no contesta, como en el log de la D3; la pregunta directa a él falla a los 1.5 s.
 */
class D14ArbitroCalladoTest {

    private val v = "V1"
    private val sesion = TableSession()
    private val vidas = ContadorDeVidas(leer = { 0L }, guardar = { true })
    private val yo = LanPeer("A", "10.0.0.1", 9000, presencia = true, venueExplicito = true)
    private val peerX = LanPeer("X", "10.0.0.9", 9000, isWired = true, presencia = true, venueExplicito = true)
    private val peerY = LanPeer("Y", "10.0.0.8", 9000, isWired = true, presencia = true, venueExplicito = true)
    private val discovery = FakeDiscovery(listOf(yo, peerX))
    private val nombres = mapOf("B" to "Beto")

    private fun TestScope.arbitro(id: String) =
        LeaseServer(deviceId = id, monoMillis = { testScheduler.currentTime }).also { it.asumirArbitraje(emptySet()) }

    private fun TestScope.red(vararg arbitros: LeaseServer) =
        Red(listOf("X", "Y").zip(arbitros).toMap()) { testScheduler.currentTime }.also { r ->
            // La pregunta directa al árbitro sin WiFi: 1.5 s y nada (la de la N86 del recorrido no está en este montaje).
            coEvery { r.client.enMesa(any(), any()) } coAnswers { delay(LeaseClient.PLAZO_EN_MESA_MS); null }
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

    /** Otra caja (B) le declara a [this] que está en [mesas]. */
    private var seqB = 0L
    private fun LeaseServer.declara(caja: String, vararg mesas: String): LeaseResponse = respondTo(
        LeaseProtocol.encode(
            LeaseRequest(op = LeaseProtocol.OP_ESTADO, deviceId = caja, staffName = nombres.getValue(caja), gen = 1, seq = ++seqB, mesas = mesas.toList()),
        ),
    )

    /** El árbitro se queda sin WiFi: cada `estado` se lleva el plazo entero y vuelve sin respuesta. */
    private fun Red.sinWifi() {
        latenciaMs = LeaseClient.PLAZO_HUB_MS
        fija = { null }
    }

    @Test
    fun `P1 EL CASO - el latido ya había fallado y el estado del toque falla otra vez - isla a los 3 s, no a los 15`() = runTest {
        val red = red(arbitro("X"))
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent() // el primer estado: X contesta

        red.sinWifi()
        advanceTimeBy(14_000) // el latido de los 10 s sale y a los 13 s vuelve sin respuesta (12:07:06 en la D3)

        val toque = testScheduler.currentTime
        val a = mesas.abrirApertura("M2", "s1", "Ana", v)
        assertEquals(LeaseOutcome.NoHub, mesas.esperar(a) { true })
        assertEquals("abrió al segundo estado sin respuesta", 3_000L, testScheduler.currentTime - toque)
    }

    @Test
    fun `P1 el árbitro se cae justo en el toque - isla al segundo estado sin respuesta, unos 7 s`() = runTest {
        val red = red(arbitro("X"))
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()
        advanceTimeBy(2_000)

        red.sinWifi()
        val toque = testScheduler.currentTime
        val a = mesas.abrirApertura("M2", "s1", "Ana", v)
        assertEquals(LeaseOutcome.NoHub, mesas.esperar(a) { true })
        // 3 s sin respuesta, 1 s de pausa, otros 3 s sin respuesta.
        assertEquals(7_000L, testScheduler.currentTime - toque)
    }

    @Test
    fun `P1 el árbitro vivo que no contesta UNA vez y luego dice ocupada - no se abre`() = runTest {
        val x = arbitro("X")
        x.declara("B", "M2")
        val red = red(x)
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()

        red.latenciaMs = LeaseClient.PLAZO_HUB_MS
        red.fija = { red.fija = null; null } // un bache: sólo el siguiente estado se pierde
        val a = mesas.abrirApertura("M2", "s1", "Ana", v)
        assertEquals(LeaseOutcome.Taken("Beto"), mesas.esperar(a) { true })
    }

    @Test
    fun `P1 una respuesta entre medias borra el silencio - latido perdido, latido bueno y un bache al abrir - manda el árbitro`() = runTest {
        val x = arbitro("X")
        x.declara("B", "M2")
        val red = red(x)
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()

        red.fija = { red.fija = null; null }
        advanceTimeBy(10_001) // el latido de los 10 s se pierde
        x.declara("B", "M2") // B sigue en la M2 (su presencia vence a los 30 s)
        advanceTimeBy(10_000) // el de los 20 s llega bien: la cuenta vuelve a cero

        red.fija = { red.fija = null; null } // y el estado del toque se pierde una vez
        val a = mesas.abrirApertura("M2", "s1", "Ana", v)
        assertEquals(LeaseOutcome.Taken("Beto"), mesas.esperar(a) { true })
    }

    @Test
    fun `P1 cambiar de árbitro empieza el silencio de cero - X callado 7 s y una falta de Y no mandan a isla`() = runTest {
        val x = arbitro("X")
        val y = arbitro("Y")
        y.declara("B", "M2")
        val red = red(x, y)
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()

        var n = 0
        red.fija = { if (++n >= 2) red.fija = null; null } // se pierden el latido de X y el primer estado que le llegue a Y
        advanceTimeBy(17_001) // X calla desde el latido de los 10 s: 7 s

        val a = mesas.abrirApertura("M2", "s1", "Ana", v)
        discovery.publicar(listOf(yo, peerY)) // en el mismo instante el elegido pasa a ser Y
        assertEquals(LeaseOutcome.Taken("Beto"), mesas.esperar(a) { true })
    }

    @Test
    fun `P1 Codex r1 1 - dos rechazos al instante no son 6 s de silencio - el árbitro que vuelve a los 2 s dice ocupada y no se abre`() = runTest {
        val x = arbitro("X")
        x.declara("B", "M2")
        val red = red(x)
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()

        var n = 0
        red.fija = { if (++n >= 2) red.fija = null; null } // dos conexiones rechazadas al instante, 1 s entre ellas
        val a = mesas.abrirApertura("M2", "s1", "Ana", v)
        assertEquals(LeaseOutcome.Taken("Beto"), mesas.esperar(a) { true })
    }

    @Test
    fun `P1 Codex r1 2 - X a Y a X sin que el colector lo vea - el silencio de antes no cuenta`() = runTest {
        val red = red(arbitro("X"), arbitro("Y"))
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()

        red.sinWifi()
        advanceTimeBy(14_000) // X calla desde el latido de los 10 s
        discovery.publicar(listOf(yo, peerY))
        discovery.publicar(listOf(yo, peerX)) // sin runCurrent: el colector sólo ve el final, igual al de antes (no sube la época)

        val toque = testScheduler.currentTime
        val a = mesas.abrirApertura("M2", "s1", "Ana", v)
        assertEquals(LeaseOutcome.NoHub, mesas.esperar(a) { true })
        assertEquals("el silencio empieza de cero en la elección nueva", 7_000L, testScheduler.currentTime - toque)
    }

    @Test
    fun `P1 con el árbitro callado, la caja que está en la mesa contesta directo y no se abre`() = runTest {
        val peerB = LanPeer("B", "10.0.0.5", 9000, presencia = true, venueExplicito = true)
        discovery.publicar(listOf(yo, peerX, peerB))
        val red = red(arbitro("X"))
        coEvery { red.client.enMesa(peerB, any()) } coAnswers {
            delay(300)
            LeaseResponse(status = LeaseProtocol.STATUS_OK, holder = WireLease("M2", "B", "", "Beto", 0, 0))
        }
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()

        red.sinWifi()
        advanceTimeBy(14_000)
        val a = mesas.abrirApertura("M2", "s1", "Ana", v)
        assertEquals(LeaseOutcome.Taken("Beto"), mesas.esperar(a) { true })
    }

    @Test
    fun `límite declarado - el árbitro que calla 6 s y vuelve antes de los 15 s ya no decide esa apertura`() = runTest {
        val x = arbitro("X")
        x.declara("B", "M2")
        val red = red(x)
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()

        red.sinWifi()
        val a = mesas.abrirApertura("M2", "s1", "Ana", v)
        advanceTimeBy(7_001) // 3 s sin respuesta, 1 s, otros 3 s: 6 s de silencio, isla
        red.latenciaMs = 0
        red.fija = null // X vuelve a los 7 s y sí tenía a B en la M2
        val desde = red.respuestas.size
        advanceTimeBy(10_000) // el latido le vuelve a mandar la apertura y X contesta «ocupada»
        assertEquals(
            LeaseProtocol.APERTURA_OCUPADA,
            red.respuestas.drop(desde).firstNotNullOf { it.second?.aperturas?.singleOrNull { r -> r.id == a.id } }.resultado,
        )

        // La primera resolución gana: sin la pregunta directa a B (no la alcanza), esta caja abre. Dos cuentas que el SERVIDOR pone
        // aparte al volver la red; hoy pasa igual si X vuelve a los 16 s.
        assertEquals(LeaseOutcome.NoHub, mesas.esperar(a) { true })
    }

    @Test
    fun `una apertura nacida durante el silencio no sale por él - se le pregunta al árbitro`() = runTest {
        val x = arbitro("X")
        x.declara("B", "M5")
        val red = red(x)
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()

        red.sinWifi()
        advanceTimeBy(14_000) // un latido sin respuesta
        val primera = mesas.abrirApertura("M2", "s1", "Ana", v)
        advanceTimeBy(2_000) // su estado sigue en vuelo
        val segunda = mesas.abrirApertura("M5", "s1", "Ana", v) // nace mientras tanto: el árbitro no la ha visto
        advanceTimeBy(1_001) // vuelve el segundo sin respuesta (7 s de silencio): sólo la primera viajaba en él
        red.latenciaMs = 0
        red.fija = null // el árbitro vuelve

        assertEquals(LeaseOutcome.NoHub, mesas.esperar(primera) { true })
        assertEquals("la M5 la decide el árbitro", LeaseOutcome.Taken("Beto"), mesas.esperar(segunda) { true })
    }
}
