package com.avoqado.pos.core.data.lan

import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_CONCEDIDA
import com.avoqado.pos.core.data.lan.LeaseProtocol.APERTURA_OCUPADA
import com.avoqado.pos.tables.data.TableSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Hub D3 (mesa declarada), la CAJA: un reconciliador serial por coordinador le manda al árbitro la declaración más nueva
 * (`estado`), valida cada respuesta contra el árbitro y la época capturados al mandarla, y resuelve las aperturas por id.
 *
 * Montaje: tiempo virtual (`backgroundScope`, `relojMs` del `testScheduler`); el árbitro es un [LeaseServer] REAL que ya terminó de
 * calentar; la caja A usa un [LeaseClient] de mockk que entrega cada `estado` a ese árbitro (con retenciones por `CompletableDeferred`);
 * las cajas B y C le hablan directo al mismo árbitro; la mesa de A sale de un [TableSession] real. p14c y p14d usan el
 * [LeaseClient] real contra un `ServerSocket` local, con reloj real.
 */
class ReconciliadorDeMesasTest {

    private val v = "V1"
    private val sesion = TableSession()
    private val vidas = ContadorDeVidas(leer = { 0L }, guardar = { true })
    private val yo = LanPeer("A", "10.0.0.1", 9000, presencia = true)
    private val peerX = LanPeer("X", "10.0.0.9", 9000, isWired = true, presencia = true)
    private val peerY = LanPeer("Y", "10.0.0.8", 9000, isWired = true, presencia = true)
    private val peerZ = LanPeer("Z", "10.0.0.7", 9000, isWired = true, presencia = true)
    private val discovery = FakeDiscovery(listOf(yo, peerX))
    private val nombres = mapOf("B" to "Beto", "C" to "Caro")
    private var seqOtras = 0L

    // MARK: - Montaje

    /** Un árbitro de verdad, ya caliente (Codex plan r1 #6). */
    private fun TestScope.arbitro(id: String) =
        LeaseServer(deviceId = id, monoMillis = { testScheduler.currentTime }).also { it.asumirArbitraje(emptySet()) }

    private fun TestScope.caja(v: ContadorDeVidas = vidas) =
        MesasDeEstaCaja({ sesion.current()?.tableId }, sesion.active.map { it?.tableId }, v) { testScheduler.currentTime }

    private fun TestScope.red(vararg arbitros: LeaseServer, ids: List<String> = listOf("X", "Y", "Z")) =
        Red(ids.zip(arbitros).toMap()) { testScheduler.currentTime }

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

    private fun cuenta(mesa: String) = TableSession.Active(
        tableId = mesa, tableNumber = mesa, areaName = null, orderId = "o-$mesa", orderNumber = "1",
        version = 1, totalCents = 0, mode = TableSession.Mode.ORDERING,
    )

    private fun entra(mesa: String) = sesion.start(cuenta(mesa), v)
    private fun sale() = assertTrue(sesion.estacionar(v, sesion.current()!!))

    /** Otra caja (B, C) declara en qué mesas está. */
    private fun LeaseServer.declara(caja: String, vararg mesas: String): LeaseResponse = respondTo(
        LeaseProtocol.encode(
            LeaseRequest(
                op = LeaseProtocol.OP_ESTADO, deviceId = caja, staffName = nombres.getValue(caja),
                gen = 1, seq = ++seqOtras, mesas = mesas.toList(),
            ),
        ),
    )

    /** Otra caja pide [mesa] como libre: el veredicto. Si se la conceden, la ocupa hasta su siguiente `estado`. */
    private fun LeaseServer.abre(caja: String, mesa: String): WireResultadoApertura = respondTo(
        LeaseProtocol.encode(
            LeaseRequest(
                op = LeaseProtocol.OP_ESTADO, deviceId = caja, staffName = nombres.getValue(caja),
                gen = 1, seq = ++seqOtras, aperturas = listOf(WireApertura("$caja-$seqOtras", mesa)),
            ),
        ),
    ).aperturas!!.single()

    // MARK: - Spec 1, 2, 3, 5, 6, 9

    @Test
    fun `p01c - A estaciona m13 y en el siguiente aviso B la abre`() = runTest {
        val x = arbitro("X")
        entra("m13")
        coordinador(red(x), caja()).start()
        runCurrent()
        assertEquals(APERTURA_OCUPADA, x.abre("B", "m13").resultado)

        sale()
        runCurrent()

        assertEquals(APERTURA_CONCEDIDA, x.abre("B", "m13").resultado)
    }

    @Test
    fun `p02c - cobrar la cuenta de A la libera en el siguiente aviso`() = runTest {
        val x = arbitro("X")
        entra("m13")
        coordinador(red(x), caja()).start()
        runCurrent()

        sesion.finalizar(v, setOf("o-m13"))
        runCurrent()

        assertEquals(APERTURA_CONCEDIDA, x.abre("C", "m13").resultado)
    }

    @Test
    fun `p02c - si B la cobra por su cuenta y A sigue dentro, m13 sigue ocupada para C hasta que A sale`() = runTest {
        val x = arbitro("X")
        entra("m13")
        coordinador(red(x), caja()).start()
        runCurrent()
        x.declara("B", "m13")
        x.declara("B") // B la cobró en su caja y salió

        advanceTimeBy(60_000) // los latidos de A la sostienen
        val r = x.abre("C", "m13")
        assertEquals(APERTURA_OCUPADA, r.resultado)
        assertEquals("Ana", r.quien)

        sale()
        runCurrent()
        assertEquals(APERTURA_CONCEDIDA, x.abre("C", "m13").resultado)
    }

    @Test
    fun `p03c - stop suelta la apertura concedida, la despedida no la lleva y el coordinador nuevo no la vuelve a declarar`() = runTest {
        val x = arbitro("X")
        val red = red(x)
        val mesas = caja()
        entra("m13")
        val viejo = coordinador(red, mesas)
        viejo.start()
        val apertura = mesas.abrirApertura("m14", "s1", "Ana", v)
        runCurrent()
        assertEquals(setOf("m13", "m14"), viejo.myTables.value)

        viejo.stop()
        runCurrent()

        assertEquals(LeaseOutcome.NoHub, mesas.esperar(apertura) { true })
        val despedida = red.recibidos.last()
        assertEquals("la despedida declara la sesión viva", listOf("m13"), despedida.mesas)
        assertTrue("la despedida no lleva la apertura", despedida.aperturas.orEmpty().isEmpty())
        assertEquals(APERTURA_CONCEDIDA, x.abre("C", "m14").resultado)

        coordinador(red, mesas).start()
        runCurrent()
        assertEquals(listOf("m13"), red.recibidos.last().mesas)
        assertTrue(red.recibidos.last().aperturas.orEmpty().isEmpty())
        assertEquals(APERTURA_OCUPADA, x.abre("B", "m13").resultado)
    }

    @Test
    fun `p05c - A entra a m13 ocupada y sale antes de que vuelva la respuesta - al final declara nada y nada queda huérfano`() = runTest {
        val x = arbitro("X")
        val red = red(x)
        x.declara("B", "m13")
        val a = coordinador(red, caja())
        a.start()
        runCurrent()

        red.retener = Red.Retener.VUELTA
        entra("m13")
        runCurrent()
        sale()
        runCurrent()
        red.retener = Red.Retener.NADA
        red.soltarTodo()
        runCurrent()

        assertEquals(emptyList<String>(), red.recibidos.last().mesas)
        assertTrue(a.myTables.value.isEmpty())
        assertTrue(red.maxEnVuelo <= 1)
        x.declara("B") // B sale
        assertEquals(APERTURA_CONCEDIDA, x.abre("C", "m13").resultado)
    }

    @Test
    fun `p06c - la respuesta del coordinador viejo no resuelve nada y su estado tardío es stale`() = runTest {
        val x = arbitro("X")
        val red = red(x)
        val mesas = caja()
        val viejo = coordinador(red, mesas)
        viejo.start()
        runCurrent()

        red.retener = Red.Retener.IDA // lo que ya salió por el cable llega aunque cancelen a quien lo mandó
        val vieja = mesas.abrirApertura("m14", "s1", "Ana", v)
        runCurrent()
        viejo.stop()
        runCurrent()
        val tardios = red.retenidos.toList()
        assertEquals("el estado viejo y la despedida", 2, tardios.size)

        red.retener = Red.Retener.NADA
        coordinador(red, mesas).start()
        val nueva = mesas.abrirApertura("m14", "s1", "Ana", v)
        runCurrent()
        assertEquals(LeaseOutcome.Granted, mesas.esperar(nueva) { true })

        tardios.forEach { red.soltar(it) }
        runCurrent()

        tardios.forEach { t -> assertEquals(LeaseProtocol.STATUS_STALE, red.respuestaA(t.pedido)?.status) }
        assertEquals(LeaseOutcome.NoHub, mesas.esperar(vieja) { true })
        assertEquals(LeaseOutcome.Granted, mesas.esperar(nueva) { true })
        assertEquals(APERTURA_OCUPADA, x.abre("C", "m14").resultado)
    }

    @Test
    fun `p09c - con m13 activa, la apertura de m14 que termina sin abrirse nunca deja de declarar m13`() = runTest {
        val x = arbitro("X")
        val red = red(x)
        val mesas = caja()
        x.declara("B", "m14")
        entra("m13")
        coordinador(red, mesas).start()
        runCurrent()

        val apertura = mesas.abrirApertura("m14", "s1", "Ana", v)
        assertEquals(LeaseOutcome.Taken("Beto"), mesas.esperar(apertura) { true })
        mesas.terminarApertura(apertura)
        runCurrent()
        advanceTimeBy(30_000)

        assertTrue(red.recibidos.size >= 3)
        assertTrue(red.recibidos.all { "m13" in it.mesas.orEmpty() })
        assertEquals(APERTURA_OCUPADA, x.abre("C", "m13").resultado)
    }

    // MARK: - Spec 11, 19, 24, 25, 28

    @Test
    fun `p11c - con el árbitro calentando, alEsperar suena, la espera sigue y al terminar el calentamiento da Granted`() = runTest {
        val x = arbitro("X").also { it.asumirArbitraje(setOf("B")) } // B nunca contesta: 12 s
        val mesas = caja()
        coordinador(red(x), mesas).start()
        val apertura = mesas.abrirApertura("m14", "s1", "Ana", v)
        var avisos = 0

        val r = mesas.esperar(apertura, alEsperar = { avisos++ }) { true }

        assertEquals(LeaseOutcome.Granted, r)
        assertEquals(1, avisos)
        assertEquals(12_000L, testScheduler.currentTime)
    }

    @Test
    fun `p19c - una respuesta de X que llega después de cambiar a Y se descarta`() = runTest {
        val x = arbitro("X")
        val y = arbitro("Y")
        val red = red(x, y)
        val mesas = caja()
        x.declara("B", "m14")
        coordinador(red, mesas).start()
        runCurrent()

        red.retener = Red.Retener.VUELTA
        val apertura = mesas.abrirApertura("m14", "s1", "Ana", v)
        runCurrent()
        assertEquals(APERTURA_OCUPADA, red.respuestaA(red.retenidos.single().pedido)?.aperturas?.single()?.resultado)
        discovery.peers.value = listOf(yo, peerY)
        runCurrent()
        red.retener = Red.Retener.NADA
        red.soltarTodo()
        runCurrent()

        assertEquals("lo de X no cuenta; Y la concede", LeaseOutcome.Granted, mesas.esperar(apertura) { true })
    }

    @Test
    fun `p19c - X a Y a X - la respuesta de la primera X es de otra época y se descarta`() = runTest {
        val x = arbitro("X")
        val y = arbitro("Y")
        val red = red(x, y)
        val mesas = caja()
        x.declara("B", "m14")
        coordinador(red, mesas).start()
        runCurrent()

        red.retener = Red.Retener.VUELTA
        val apertura = mesas.abrirApertura("m14", "s1", "Ana", v)
        runCurrent()
        x.declara("B") // B sale mientras la respuesta «ocupada» viaja
        discovery.peers.value = listOf(yo, peerY)
        runCurrent()
        discovery.peers.value = listOf(yo, peerX)
        runCurrent()
        red.retener = Red.Retener.NADA
        red.soltarTodo()
        runCurrent()

        assertEquals(LeaseOutcome.Granted, mesas.esperar(apertura) { true })
    }

    @Test
    fun `p24c - aunque el árbitro cambie dos veces de verdad, la apertura da NoHub a los 15 s desde el toque`() = runTest {
        val x = arbitro("X").also { it.asumirArbitraje(setOf("B")) }
        val y = arbitro("Y")
        val z = arbitro("Z")
        val red = red(x, y, z)
        val mesas = caja()
        coordinador(red, mesas).start()
        val apertura = mesas.abrirApertura("m14", "s1", "Ana", v)
        val espera = async { mesas.esperar(apertura) { true } }

        advanceTimeBy(5_000)
        y.asumirArbitraje(setOf("B"))
        discovery.peers.value = listOf(yo, peerY)
        runCurrent()
        advanceTimeBy(5_000)
        z.asumirArbitraje(setOf("B"))
        discovery.peers.value = listOf(yo, peerZ)
        runCurrent()

        assertEquals(LeaseOutcome.NoHub, espera.await())
        assertEquals(MesasDeEstaCaja.PLAZO_PEDIR_MESA_MS, testScheduler.currentTime)
        assertEquals(setOf("X", "Y", "Z"), red.arbitrosUsados)
    }

    @Test
    fun `p25c - sin número de vida, esperar da NoHub y no sale ningún mensaje`() = runTest {
        val red = red(arbitro("X"))
        val mesas = caja(ContadorDeVidas(leer = { 0L }, guardar = { false }))
        val c = coordinador(red, mesas)
        c.start()
        val apertura = mesas.abrirApertura("m14", "s1", "Ana", v)

        assertEquals(LeaseOutcome.NoHub, mesas.esperar(apertura) { true })
        assertTrue(testScheduler.currentTime < 1_000)
        c.stop()
        runCurrent()
        coVerify(exactly = 0) { red.client.estado(any(), any(), any()) }
    }

    @Test
    fun `p25c - stale en cada vuelta da NoHub sin bloquear`() = runTest {
        val x = arbitro("X")
        // Una vida de A más nueva que la de este proceso (p. ej. reloj atrasado tras reinstalar): todo lo de A es stale.
        x.respondTo(LeaseProtocol.encode(LeaseRequest(op = LeaseProtocol.OP_ESTADO, deviceId = "A", gen = 5, seq = 1)))
        val red = red(x)
        val mesas = caja()
        coordinador(red, mesas).start()
        val apertura = mesas.abrirApertura("m14", "s1", "Ana", v)

        assertEquals(LeaseOutcome.NoHub, mesas.esperar(apertura) { true })
        assertTrue(testScheduler.currentTime < 1_000)
        advanceTimeBy(20_000)
        val otra = mesas.abrirApertura("m15", "s1", "Ana", v)
        assertEquals(LeaseOutcome.NoHub, mesas.esperar(otra) { true })
        assertTrue(red.respuestas.all { it.second?.status == LeaseProtocol.STATUS_STALE })
    }

    @Test
    fun `p28c - con la apertura concedida y openTable tardando 5 min, la mesa sigue declarada hasta que el dueño la termina`() = runTest {
        val x = arbitro("X")
        val mesas = caja()
        val a = coordinador(red(x), mesas)
        a.start()
        val apertura = mesas.abrirApertura("m14", "s1", "Ana", v)
        assertEquals(LeaseOutcome.Granted, mesas.esperar(apertura) { true })

        repeat(5) {
            advanceTimeBy(59_000)
            assertEquals(APERTURA_OCUPADA, x.abre("C", "m14").resultado)
            assertEquals(setOf("m14"), a.myTables.value)
            advanceTimeBy(1_000)
        }

        mesas.terminarApertura(apertura)
        runCurrent()
        assertEquals(APERTURA_CONCEDIDA, x.abre("C", "m14").resultado)
    }

    // MARK: - Review Focus 4, rearme, cierre de sesión, null, solo

    @Test
    fun `p33 - entrar y salir 20 veces en un segundo - un estado en vuelo a la vez y el último declara nada`() = runTest {
        val x = arbitro("X")
        val red = red(x).also { it.latenciaMs = 30 }
        coordinador(red, caja()).start()
        runCurrent()

        repeat(20) {
            entra("m13")
            advanceTimeBy(25)
            sale()
            advanceTimeBy(25)
        }
        advanceTimeBy(1_000)

        assertEquals(1, red.maxEnVuelo)
        assertEquals(emptyList<String>(), red.recibidos.last().mesas)
        assertEquals(APERTURA_CONCEDIDA, x.abre("B", "m13").resultado)
    }

    private fun rearme(despedidaPrimero: Boolean) = runTest {
        val x = arbitro("X")
        val red = red(x)
        val mesas = caja()
        entra("m13")
        val viejo = coordinador(red, mesas)
        viejo.start()
        runCurrent()
        assertEquals(APERTURA_OCUPADA, x.abre("B", "m13").resultado)

        red.retener = Red.Retener.IDA
        viejo.stop()
        coordinador(red, mesas).start()
        runCurrent()
        val (despedida, primero) = red.retenidos.sortedBy { it.pedido.seq }.also { assertEquals(2, it.size) }
        assertEquals(listOf("m13"), despedida.pedido.mesas)
        assertEquals(listOf("m13"), primero.pedido.mesas)
        assertEquals(APERTURA_OCUPADA, x.abre("B", "m13").resultado)

        red.retener = Red.Retener.NADA
        for (vuelo in if (despedidaPrimero) listOf(despedida, primero) else listOf(primero, despedida)) {
            red.soltar(vuelo)
            runCurrent()
            assertEquals(APERTURA_OCUPADA, x.abre("B", "m13").resultado)
        }
        advanceTimeBy(45_000)
        assertEquals(APERTURA_OCUPADA, x.abre("B", "m13").resultado)
    }

    @Test
    fun `p_rearme - la despedida declara m13 y B la ve ocupada (llega la despedida primero)`() = rearme(despedidaPrimero = true)

    @Test
    fun `p_rearme - la despedida declara m13 y B la ve ocupada (llega primero el coordinador nuevo)`() = rearme(despedidaPrimero = false)

    @Test
    fun `p_logout - se borra la sesión y luego stop - la despedida declara nada y B abre m13 enseguida`() = runTest {
        val x = arbitro("X")
        val red = red(x)
        entra("m13")
        val a = coordinador(red, caja())
        a.start()
        runCurrent()
        assertEquals(APERTURA_OCUPADA, x.abre("B", "m13").resultado)

        sesion.clear() // el orden nuevo de onLogout: la sesión de mesa se borra ANTES de apagar el hub
        a.stop()
        runCurrent()

        assertEquals(emptyList<String>(), red.recibidos.last().mesas)
        assertEquals(APERTURA_CONCEDIDA, x.abre("B", "m13").resultado)
    }

    @Test
    fun `p_logout_con_apertura - la despedida no lleva la apertura concedida y B abre m14 enseguida`() = runTest {
        val x = arbitro("X")
        val red = red(x)
        val mesas = caja()
        val a = coordinador(red, mesas)
        a.start()
        val apertura = mesas.abrirApertura("m14", "s1", "Ana", v)
        assertEquals(LeaseOutcome.Granted, mesas.esperar(apertura) { true }) // openTable sigue retenido: el dueño no la termina
        assertEquals(APERTURA_OCUPADA, x.abre("B", "m14").resultado)

        sesion.clear()
        a.stop()
        runCurrent()

        assertTrue(red.recibidos.last().aperturas.orEmpty().isEmpty())
        assertEquals(emptyList<String>(), red.recibidos.last().mesas)
        assertEquals(APERTURA_CONCEDIDA, x.abre("B", "m14").resultado)
    }

    @Test
    fun `p_null - sin respuesta nunca, cada envío sale 1 s o más después del anterior y a los 15 s da NoHub`() = runTest {
        val red = red(arbitro("X")).also { it.fija = { null } } // conexión rechazada al instante
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()
        advanceTimeBy(5_000)

        val apertura = mesas.abrirApertura("m14", "s1", "Ana", v)
        assertEquals(LeaseOutcome.NoHub, mesas.esperar(apertura) { true })

        assertEquals(20_000L, testScheduler.currentTime)
        val envios = red.envios.filter { it >= 5_000 }
        assertEquals(5_000L, envios.first())
        assertTrue("hubo reintentos: $envios", envios.size >= 10)
        envios.zipWithNext().forEach { (antes, despues) -> assertTrue("$antes → $despues", despues - antes >= 1_000) }
    }

    @Test
    fun `p_null - un error sin arbitro da NoHub enseguida, sin reintento en 0 ms`() = runTest {
        val red = red(arbitro("X")).also { it.fija = { LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = "x") } }
        val mesas = caja()
        coordinador(red, mesas).start()
        runCurrent()
        advanceTimeBy(5_000)

        val apertura = mesas.abrirApertura("m14", "s1", "Ana", v)
        assertEquals(LeaseOutcome.NoHub, mesas.esperar(apertura) { true })
        assertEquals(5_000L, testScheduler.currentTime)
        advanceTimeBy(9_999)

        assertEquals(listOf(5_000L), red.envios.filter { it >= 5_000 })
    }

    @Test
    fun `p_solo - sin peers, esperar da NoHub en menos de 1 s`() = runTest {
        discovery.peers.value = emptyList()
        val mesas = caja()
        coordinador(red(arbitro("X")), mesas).start()
        val apertura = mesas.abrirApertura("m14", "s1", "Ana", v)

        assertEquals(LeaseOutcome.NoHub, mesas.esperar(apertura) { true })
        assertTrue(testScheduler.currentTime < 1_000)
    }

    // MARK: - Spec 14: el plazo TOTAL por mensaje, con el LeaseClient real (reloj real, fuera del hilo de la prueba)

    @Test
    fun `p14c - un árbitro que gotea byte por byte - estado da null en 3 s o menos y la vuelta siguiente sale, en IO`() = runBlocking {
        Goteo().use { goteo ->
            val arbitro = LanPeer("X", "127.0.0.1", goteo.puerto, isWired = true, presencia = true)
            val hilos = CopyOnWriteArrayList<Thread>()
            val llamadas = CopyOnWriteArrayList<Pair<Long, LeaseResponse?>>()
            val cliente = spyk(LeaseClient())
            coEvery { cliente.estado(any(), any(), any()) } coAnswers {
                hilos += Thread.currentThread()
                val t0 = ahora()
                val r = callOriginal()
                llamadas += (ahora() - t0) to r
                r
            }
            val mesas = MesasDeEstaCaja({ null }, flowOf(null), vidas) { ahora() }
            mesas.abrirApertura("m5", "s1", "Ana", v) // pendiente: tras un null la siguiente vuelta sale en 1 s
            val c = LanHubCoordinator(FakeDiscovery(listOf(yo, arbitro)), mesas, "A", v, { "Ana" }, cliente)

            c.start()
            try {
                val hasta = ahora() + 8_000
                while (goteo.conexiones.size < 2 && ahora() < hasta) delay(20)
            } finally {
                c.stop()
            }

            assertTrue("la segunda vuelta salió", goteo.conexiones.size >= 2)
            val primera = goteo.conexiones.first()
            val hasta = ahora() + 2_000
            while (primera.cerrada == 0L && ahora() < hasta) delay(10)
            assertNotEquals("el servidor vio el cierre", 0L, primera.cerrada)
            assertTrue("cerrada a los ${primera.cerrada - primera.aceptada} ms", primera.cerrada - primera.aceptada <= PLAZO_CON_MARGEN)
            val (duracion, respuesta) = llamadas.first()
            assertEquals(null, respuesta)
            assertTrue("estado tardó $duracion ms", duracion <= PLAZO_CON_MARGEN)
            val prueba = Thread.currentThread()
            assertTrue(hilos.all { it != prueba && it.name.startsWith("DefaultDispatcher") })
        }
    }

    @Test
    fun `p14d - cancelar a quien llama a media lectura igual cierra el socket a los 3 s y la llamada termina`() = runBlocking {
        Goteo().use { goteo ->
            val arbitro = LanPeer("X", "127.0.0.1", goteo.puerto)
            val pedido = LeaseRequest(op = LeaseProtocol.OP_ESTADO, deviceId = "A", gen = 1, seq = 1, mesas = listOf("m13"))
            val inicio = ahora()
            val llamada = CoroutineScope(Dispatchers.IO).launch { LeaseClient().estado(arbitro, pedido) }
            while (goteo.conexiones.isEmpty() && ahora() - inicio < 2_000) delay(10)
            delay(1_000) // ya está goteando

            llamada.cancel()
            withTimeoutOrNull(8_000) { llamada.join() }
            val fin = ahora()
            val hasta = ahora() + 8_000
            while (goteo.conexiones.single().cerrada == 0L && ahora() < hasta) delay(10)

            assertTrue("la llamada terminó a los ${fin - inicio} ms", fin - inicio <= PLAZO_CON_MARGEN)
            val c = goteo.conexiones.single()
            assertNotEquals("el servidor vio el cierre", 0L, c.cerrada)
            assertTrue("cerrada a los ${c.cerrada - inicio} ms", c.cerrada - inicio <= PLAZO_CON_MARGEN)
        }
    }

    private companion object {
        /** El plazo total de un mensaje al árbitro, más holgura para una máquina cargada. */
        const val PLAZO_CON_MARGEN = LeaseClient.PLAZO_HUB_MS + 700

        fun ahora() = System.nanoTime() / 1_000_000
    }
}

/**
 * La «red» entre la caja A y los árbitros: el mock de [LeaseClient] entrega cada `estado` al [LeaseServer] del árbitro al que se
 * mandó. Con [retener], IDA retiene el pedido antes de que llegue (y llega aunque cancelen a quien lo mandó: ya salió por el cable)
 * y VUELTA retiene la respuesta ya decidida. [fija] reemplaza al árbitro (null = conexión rechazada).
 */
private class Red(private val arbitros: Map<String, LeaseServer>, private val reloj: () -> Long) {
    enum class Retener { NADA, IDA, VUELTA }

    class Vuelo(val pedido: LeaseRequest) {
        val compuerta = CompletableDeferred<Unit>()
    }

    var retener = Retener.NADA
    var latenciaMs = 0L
    var fija: (() -> LeaseResponse?)? = null
    val retenidos = mutableListOf<Vuelo>()
    val recibidos = mutableListOf<LeaseRequest>()
    val respuestas = mutableListOf<Pair<LeaseRequest, LeaseResponse?>>()
    val envios = mutableListOf<Long>()
    val arbitrosUsados = mutableSetOf<String>()
    var enVuelo = 0
    var maxEnVuelo = 0

    val client = mockk<LeaseClient>().also { c ->
        coEvery { c.estado(any(), any(), any()) } coAnswers { pasar(firstArg(), secondArg()) }
    }

    fun respuestaA(pedido: LeaseRequest) = respuestas.lastOrNull { it.first === pedido }?.second

    fun soltar(vuelo: Vuelo) {
        retenidos.remove(vuelo)
        vuelo.compuerta.complete(Unit)
    }

    fun soltarTodo() = retenidos.toList().forEach(::soltar)

    private suspend fun pasar(arbitro: LanPeer, pedido: LeaseRequest): LeaseResponse? {
        envios += reloj()
        arbitrosUsados += arbitro.deviceId
        enVuelo++
        maxEnVuelo = maxOf(maxEnVuelo, enVuelo)
        try {
            val modo = retener
            return withContext(NonCancellable) {
                if (latenciaMs > 0) delay(latenciaMs)
                if (modo == Retener.IDA) esperarTurno(pedido)
                val r = fija.let { if (it != null) it() else arbitros.getValue(arbitro.deviceId).respondTo(LeaseProtocol.encode(pedido)) }
                recibidos += pedido
                respuestas += pedido to r
                if (modo == Retener.VUELTA) esperarTurno(pedido)
                r
            }
        } finally {
            enVuelo--
        }
    }

    private suspend fun esperarTurno(pedido: LeaseRequest) = Vuelo(pedido).also { retenidos += it }.compuerta.await()
}

/** Un «árbitro» que contesta un byte cada 500 ms y nunca termina la línea; anota cuándo acepta cada conexión y cuándo ve su cierre. */
private class Goteo : AutoCloseable {
    class Conexion(val aceptada: Long) {
        @Volatile var cerrada = 0L
    }

    private val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val puerto = ss.localPort
    val conexiones = CopyOnWriteArrayList<Conexion>()

    init {
        thread(isDaemon = true) {
            while (true) {
                val s = runCatching { ss.accept() }.getOrNull() ?: break
                val c = Conexion(System.nanoTime() / 1_000_000).also { conexiones += it }
                thread(isDaemon = true) {
                    s.use {
                        val entrada = BufferedInputStream(it.getInputStream())
                        LineaAcotada.leer(entrada) // la petición
                        val salida = it.getOutputStream()
                        thread(isDaemon = true) { runCatching { repeat(20) { salida.write('x'.code); salida.flush(); Thread.sleep(500) } } }
                        runCatching { entrada.read() } // -1 o excepción: el cliente cerró
                        c.cerrada = System.nanoTime() / 1_000_000
                    }
                }
            }
        }
    }

    override fun close() = ss.close()
}
