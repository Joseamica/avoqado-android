package com.avoqado.pos.printing.data

import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrinterInfo
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.printing.routing.TicketPlan
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class AlmacenFalsoReplay : AlmacenDeTexto {
    private var texto: String? = null
    override fun leer(): String? = texto
    override fun escribir(texto: String): Boolean { this.texto = texto; return true }
    override fun borrar() { texto = null }
}

/**
 * El que hace que la comanda salga sola cuando prendes la impresora — lo que pidió el founder.
 *
 * 🔴 El reintento automático se rendía tras ~1 minuto. Si el cocinero prendía la impresora diez
 * minutos después, la comanda seguía guardada pero alguien tenía que tocar el botón.
 */
class ReplayDeComandasPendientesTest {

    private val plan = TicketPlan(
        stationId = "st_cocina",
        unrouted = false,
        lines = listOf(ConsolidatedLine("Taco", 2, emptyList(), null, listOf("oi_1"))),
    )
    private val config = PrintConfig(
        printers = listOf(PrinterInfo("pr_cocina", "Cocina", "NETWORK", "192.168.1.50:9100")),
        stations = listOf(StationInfo(id = "st_cocina", name = "Cocina", printerId = "pr_cocina", copies = 1)),
    )
    private val trabajo = TrabajoPendiente(
        planes = listOf(plan), copiasPendientes = emptyMap(), saltadas = emptyList(),
        config = config, orderNumber = "ORD-5", orderType = "En tienda", serverName = null,
        comboNames = emptyMap(), venueId = "venue-1", orderId = "order-5",
    )
    private val fallo = EstadoDeComanda.NoSalio(listOf("Cocina"), "sin papel", "ORD-5", trabajo)

    private fun conPendiente(): Pair<ComandasPendientesStore, AlmacenFalsoReplay> {
        val almacen = AlmacenFalsoReplay()
        val store = ComandasPendientesStore(almacen)
        store.guardar(fallo)
        return store to almacen
    }

    @Test
    fun `cuando por fin sale, el pendiente se borra del aparato`() = runTest {
        val (store, almacen) = conPendiente()
        val dispatcher = mockk<ComandaDispatcher>()
        coEvery { dispatcher.reintentar(any(), any()) } returns EstadoDeComanda.Salio

        val estado = ReplayDeComandasPendientes(store, dispatcher).intentarAhora()

        assertEquals(EstadoDeComanda.Salio, estado)
        assertNull("la comanda salió y siguió guardada: se reimprimiría sola otra vez", almacen.leer())
        assertNull(store.pendiente.value)
    }

    /**
     * 🔴 Si sigue sin salir, el pendiente NO se toca: el siguiente tic vuelve a intentarlo. Es
     * justo lo que hace que salga cuando prendan la impresora media hora después.
     */
    @Test
    fun `si sigue fallando, el pendiente se conserva para el siguiente intento`() = runTest {
        val (store, almacen) = conPendiente()
        val dispatcher = mockk<ComandaDispatcher>()
        coEvery { dispatcher.reintentar(any(), any()) } returns fallo

        ReplayDeComandasPendientes(store, dispatcher).intentarAhora()

        assertEquals("se perdió el pendiente tras un intento fallido", "ORD-5", store.pendiente.value?.orderNumber)
        assertEquals(true, almacen.leer() != null)
    }

    @Test
    fun `sin nada pendiente no molesta a la impresora`() = runTest {
        val store = ComandasPendientesStore(AlmacenFalsoReplay())
        var llamadas = 0
        val dispatcher = mockk<ComandaDispatcher>()
        coEvery { dispatcher.reintentar(any(), any()) } coAnswers { llamadas++; EstadoDeComanda.Salio }

        assertNull(ReplayDeComandasPendientes(store, dispatcher).intentarAhora())
        assertEquals(0, llamadas)
    }

    /**
     * 🔴 La invariante que impide el ticket duplicado: el reloj y el botón «Volver a imprimir»
     * comparten ejecutor. Si el reloj ya está adentro, el toque del cajero NO encola un segundo
     * envío — se omite. Sin esto, los dos pueden mandar la misma comanda a la vez y la cocina
     * recibe dos.
     */
    @Test
    fun `P1 con un intento en curso, el segundo NO manda otra vez`() = runTest {
        val (store, _) = conPendiente()
        val puerta = CompletableDeferred<Unit>()
        var envios = 0
        val dispatcher = mockk<ComandaDispatcher>()
        coEvery { dispatcher.reintentar(any(), any()) } coAnswers {
            envios++
            puerta.await()
            EstadoDeComanda.Salio
        }
        val replay = ReplayDeComandasPendientes(store, dispatcher)

        val enVuelo = backgroundScope.async { replay.intentarAhora() }
        // Deja que el primero entre y se quede esperando en la puerta.
        kotlinx.coroutines.yield()

        val segundo = replay.intentarAhora()

        assertNull("el segundo intento mandó la comanda otra vez", segundo)
        assertEquals("la cocina habría recibido dos", 1, envios)
        puerta.complete(Unit)
        enVuelo.await()
    }

    // MARK: - D11 (16-nov-2026): el reintento parcial guarda lo que sigue faltando

    private val planCocina = plan
    private val planBarra = TicketPlan(
        stationId = "st_barra",
        unrouted = false,
        lines = listOf(ConsolidatedLine("Limonada", 1, emptyList(), null, listOf("oi_2"))),
    )
    private val configDos = PrintConfig(
        printers = listOf(
            PrinterInfo("pr_cocina", "Cocina", "NETWORK", "192.168.1.50:9100"),
            PrinterInfo("pr_barra", "Barra", "NETWORK", "192.168.1.51:9100"),
        ),
        stations = listOf(
            StationInfo(id = "st_cocina", name = "Cocina", printerId = "pr_cocina", copies = 1),
            StationInfo(id = "st_barra", name = "Barra", printerId = "pr_barra", copies = 1),
        ),
    )
    private val trabajoDos = trabajo.copy(planes = listOf(planCocina, planBarra), config = configDos, orderNumber = "ORD-7", orderId = "order-7")
    private val falloDos = EstadoDeComanda.NoSalio(listOf("Cocina", "Barra"), "no responde", "ORD-7", trabajoDos)
    /** Lo que devuelve el reintento cuando Cocina SÍ salió y Barra volvió a fallar: sólo Barra. */
    private val soloBarra = EstadoDeComanda.NoSalio(
        listOf("Barra"), "no responde", "ORD-7", trabajoDos.copy(planes = listOf(planBarra)), algoSalio = true,
    )

    /**
     * P1 El caso de la tarjeta: Cocina y Barra fallan; al reintentar sale Cocina y Barra vuelve a fallar. El siguiente
     * intento (el reloj o «Volver a imprimir») tiene que mandar SÓLO Barra. Antes mandaba otra vez las dos y la cocina
     * recibía el mismo ticket dos veces.
     */
    @Test
    fun `P1 si sale Cocina y Barra vuelve a fallar, el siguiente intento manda sólo Barra`() = runTest {
        val almacen = AlmacenFalsoReplay()
        val store = ComandasPendientesStore(almacen)
        store.guardar(falloDos)
        val enviados = mutableListOf<List<String?>>()
        val dispatcher = mockk<ComandaDispatcher>()
        val visto = slot<TrabajoPendiente>()
        coEvery { dispatcher.reintentar(capture(visto), any()) } coAnswers {
            enviados += visto.captured.planes.map { it.stationId }
            soloBarra
        }
        val replay = ReplayDeComandasPendientes(store, dispatcher)

        replay.intentarAhora()
        replay.intentarAhora()

        assertEquals(listOf("st_cocina", "st_barra"), enviados[0])
        assertEquals("Cocina habría salido dos veces", listOf("st_barra"), enviados[1])
        assertEquals(listOf("Barra"), store.pendiente.value?.estaciones)
    }

    /** P1 Lo reducido también queda en DISCO: si la app muere y se abre otra vez, «Volver a imprimir» no repite Cocina. */
    @Test
    fun `P1 lo que sigue faltando sobrevive a reiniciar la app`() = runTest {
        val almacen = AlmacenFalsoReplay()
        val store = ComandasPendientesStore(almacen)
        store.guardar(falloDos)
        val dispatcher = mockk<ComandaDispatcher>()
        coEvery { dispatcher.reintentar(any(), any()) } returns soloBarra

        ReplayDeComandasPendientes(store, dispatcher).intentarAhora()

        val tras = ComandasPendientesStore(almacen).apply { cargar("venue-1") }
        assertEquals(listOf("st_barra"), tras.pendiente.value?.trabajo?.planes?.map { it.stationId })
    }

    /**
     * P1 La vigencia (8 h) cuenta desde el PRIMER fallo. Si cada reintento la renovara, el reloj —que insiste cada
     * minuto— la mantendría viva para siempre y una comanda de ayer saldría hoy.
     */
    @Test
    fun `P1 guardar lo reducido no renueva la vigencia de 8 horas`() = runTest {
        val almacen = AlmacenFalsoReplay()
        val store = ComandasPendientesStore(almacen)
        // Hace 7 h: todavía vigente, así que el reintento SÍ corre y guarda lo reducido (Codex D11 r2: con una fecha de
        // 1970 la comanda vencía antes de reintentar y la prueba pasaba sin probar nada).
        val t0 = System.currentTimeMillis() - 7L * 60 * 60 * 1000
        store.guardar(falloDos, ahora = t0)
        var llamadas = 0
        val dispatcher = mockk<ComandaDispatcher>()
        coEvery { dispatcher.reintentar(any(), any()) } coAnswers { llamadas++; soloBarra }

        ReplayDeComandasPendientes(store, dispatcher).intentarAhora()

        assertEquals("el reintento no corrió", 1, llamadas)
        assertEquals(listOf("st_barra"), store.leer("venue-1", ahora = t0 + 60_000)?.trabajo?.planes?.map { it.stationId })
        val ochoHorasYUnMinuto = 8L * 60 * 60 * 1000 + 60_000
        // Dos lecturas independientes: el disco (otra instancia, como tras reiniciar) y la memoria de ESTA instancia.
        assertNull("guardar lo reducido renovó la vigencia en disco", ComandasPendientesStore(almacen).leer("venue-1", ahora = t0 + ochoHorasYUnMinuto))
        assertNull("guardar lo reducido renovó la vigencia en memoria", store.vigente(ahora = t0 + ochoHorasYUnMinuto))
    }

    /**
     * Lo que faltaba salió, pero lo que queda ya no tiene impresora (no hay nada que reenviar): el aviso se sigue viendo,
     * sin botón, y ni el reloj ni un reinicio vuelven a mandar lo que ya salió.
     */
    @Test
    fun `sin nada reenviable el aviso se ve pero ya no se manda nada`() = runTest {
        val almacen = AlmacenFalsoReplay()
        val store = ComandasPendientesStore(almacen)
        store.guardar(falloDos)
        var llamadas = 0
        val dispatcher = mockk<ComandaDispatcher>()
        coEvery { dispatcher.reintentar(any(), any()) } coAnswers {
            llamadas++
            EstadoDeComanda.NoSalio(listOf("Barra"), ReintentoDeComanda.CAUSA_SIN_IMPRESORA, "ORD-7", trabajo = null, algoSalio = true)
        }
        val replay = ReplayDeComandasPendientes(store, dispatcher)

        replay.intentarAhora()
        assertNull("el reloj volvió a mandar lo que ya salió", replay.intentarAhora())

        assertEquals(1, llamadas)
        assertEquals(listOf("Barra"), store.pendiente.value?.estaciones)
        assertNull(store.pendiente.value?.trabajo)
        assertNull("tras reiniciar, «Volver a imprimir» repetiría lo que ya salió", almacen.leer())
    }

    /** La libreta es de UNA ranura: si mientras se imprimía entró el fallo de OTRA venta, ése no se pisa. */
    @Test
    fun `si otra venta ocupó la libreta durante el intento, no se pisa`() = runTest {
        val almacen = AlmacenFalsoReplay()
        val store = ComandasPendientesStore(almacen)
        store.guardar(falloDos)
        val dispatcher = mockk<ComandaDispatcher>()
        coEvery { dispatcher.reintentar(any(), any()) } coAnswers {
            store.guardar(fallo) // la venta ORD-5 falla mientras se reimprime la ORD-7
            soloBarra
        }

        ReplayDeComandasPendientes(store, dispatcher).intentarAhora()

        assertEquals("ORD-5", store.pendiente.value?.orderNumber)
        val enDisco = ComandasPendientesStore(almacen).apply { cargar("venue-1") }.pendiente.value
        assertNotNull(enDisco)
        assertTrue(enDisco!!.orderNumber == "ORD-5")
    }

    // MARK: - Codex D11 r1

    /**
     * P1 (Codex D11 r1 #1) El reintento comprueba «sigue siendo mi comanda» y escribe bajo el MISMO candado que `guardar`.
     * Aquí la escritura del reintento se detiene a la mitad mientras OTRO hilo (una ronda de mesa) guarda la venta ORD-5:
     * sin candado, el reintento terminaba encima y ORD-5 desaparecía de memoria y de disco.
     */
    @Test
    fun `P1 una ronda que guarda a media escritura del reintento no se pierde`() = runTest {
        val enEscritura = java.util.concurrent.CountDownLatch(1)
        val sigue = java.util.concurrent.CountDownLatch(1)
        val almacen = object : AlmacenDeTexto {
            @Volatile var texto: String? = null
            @Volatile var detener = false
            override fun leer(): String? = texto
            override fun escribir(texto: String): Boolean {
                if (detener && texto.contains("ORD-7")) {
                    detener = false
                    enEscritura.countDown()
                    sigue.await(5, java.util.concurrent.TimeUnit.SECONDS)
                }
                this.texto = texto
                return true
            }
            override fun borrar() { texto = null }
        }
        val store = ComandasPendientesStore(almacen)
        store.guardar(falloDos)
        almacen.detener = true

        val reintento = Thread { store.reemplazar(trabajoDos, soloBarra) }.apply { start() }
        enEscritura.await(5, java.util.concurrent.TimeUnit.SECONDS)
        val ronda = Thread { store.guardar(fallo) }.apply { start() }
        Thread.sleep(200) // sin candado, la ronda escribe aquí, ANTES de que el reintento termine
        sigue.countDown()
        reintento.join(5_000)
        ronda.join(5_000)

        assertEquals("la comanda de la ronda se perdió", "ORD-5", store.pendiente.value?.orderNumber)
        assertEquals("ORD-5", ComandasPendientesStore(almacen).apply { cargar("venue-1") }.pendiente.value?.orderNumber)
    }

    /**
     * P1 (Codex D11 r1 #2) Si la reimpresión de ORD-7 SALE pero mientras tanto falló ORD-5 y ocupó la libreta, el éxito
     * de ORD-7 no puede borrar el aviso ni el reintento de ORD-5.
     */
    @Test
    fun `P1 el éxito de una reimpresión no borra el aviso de otra venta`() = runTest {
        val almacen = AlmacenFalsoReplay()
        val store = ComandasPendientesStore(almacen)
        store.guardar(falloDos)
        val dispatcher = mockk<ComandaDispatcher>()
        coEvery { dispatcher.reintentar(any(), any()) } coAnswers {
            store.guardar(fallo)
            EstadoDeComanda.Salio
        }

        ReplayDeComandasPendientes(store, dispatcher).intentarAhora()

        assertEquals("ORD-5", store.pendiente.value?.orderNumber)
        assertNotNull("el aviso de ORD-5 se borró del aparato", almacen.leer())
    }

    /**
     * P1 (Codex D11 r1 #3) Con la app abierta más de 8 h, la comanda vence también para el reloj y el botón: no se imprime
     * una comanda de ayer porque la impresora volvió.
     */
    @Test
    fun `P1 con la app abierta más de 8 horas la comanda vencida ya no se manda`() = runTest {
        val store = ComandasPendientesStore(AlmacenFalsoReplay())
        val nueveHoras = 9L * 60 * 60 * 1000
        store.guardar(falloDos, ahora = System.currentTimeMillis() - nueveHoras)
        var llamadas = 0
        val dispatcher = mockk<ComandaDispatcher>()
        coEvery { dispatcher.reintentar(any(), any()) } coAnswers { llamadas++; EstadoDeComanda.Salio }

        assertNull(ReplayDeComandasPendientes(store, dispatcher).intentarAhora())

        assertEquals("se mandó una comanda de hace 9 h", 0, llamadas)
        assertNull(store.pendiente.value)
    }

    /** La vigencia que lee el reloj también viene del disco al abrir la app (cargar), no sólo de lo guardado en esta vida. */
    @Test
    fun `al abrir la app la vigencia de lo guardado sigue contando desde el primer fallo`() = runTest {
        val almacen = AlmacenFalsoReplay()
        val t0 = System.currentTimeMillis() - 7L * 60 * 60 * 1000
        ComandasPendientesStore(almacen).guardar(falloDos, ahora = t0)
        val store = ComandasPendientesStore(almacen).apply { cargar("venue-1") }

        assertNotNull("a las 7 h sigue vigente", store.vigente())
        assertNull("a las 8 h y un minuto ya venció", store.vigente(ahora = t0 + 8L * 60 * 60 * 1000 + 60_000))
    }

    // MARK: - D1 (hz:033d44ca:D1, 16-nov-2026): cuando vuelve la impresora salen TODAS, no sólo la última

    private fun venta(n: Int) = fallo.copy(orderNumber = "ORD-$n", trabajo = trabajo.copy(orderNumber = "ORD-$n", orderId = "order-$n"))

    /**
     * P1 El recorrido de la tarjeta: cocina apagada, tres rondas que no salen, se prende la impresora. Antes salía sólo la
     * tercera (la única que quedaba en la libreta). Ahora salen las tres, en el orden en que fallaron.
     */
    @Test
    fun `P1 al prender la impresora salen las tres comandas pendientes en orden`() = runTest {
        val almacen = AlmacenFalsoReplay()
        val store = ComandasPendientesStore(almacen).apply { cargar("venue-1") }
        store.guardar(venta(1))
        store.guardar(venta(2))
        store.guardar(venta(3))
        val salieron = mutableListOf<String>()
        val visto = slot<TrabajoPendiente>()
        val dispatcher = mockk<ComandaDispatcher>()
        coEvery { dispatcher.reintentar(capture(visto), any()) } coAnswers {
            salieron += visto.captured.orderNumber
            EstadoDeComanda.Salio
        }

        ReplayDeComandasPendientes(store, dispatcher).intentarAhora()

        assertEquals(listOf("ORD-1", "ORD-2", "ORD-3"), salieron)
        assertTrue(store.pendientes.value.isEmpty())
        assertNull(store.pendiente.value)
        assertNull("quedó basura en el aparato", almacen.leer())
    }

    /** Si una sigue sin salir (su estación sigue apagada), las demás salen igual y sólo ella se queda. */
    @Test
    fun `si una sigue fallando las demas salen y solo ella se queda`() = runTest {
        val store = ComandasPendientesStore(AlmacenFalsoReplay()).apply { cargar("venue-1") }
        store.guardar(venta(1))
        store.guardar(venta(2))
        store.guardar(venta(3))
        val visto = slot<TrabajoPendiente>()
        val dispatcher = mockk<ComandaDispatcher>()
        coEvery { dispatcher.reintentar(capture(visto), any()) } coAnswers {
            if (visto.captured.orderNumber == "ORD-2") venta(2) else EstadoDeComanda.Salio
        }

        ReplayDeComandasPendientes(store, dispatcher).intentarAhora()

        assertEquals(listOf("ORD-2"), store.pendientes.value.map { it.orderNumber })
    }

    /** Una que ya salió no se vuelve a mandar en el siguiente tic (la cocina no recibe dos). */
    @Test
    fun `P1 la que ya salio no se manda otra vez en el siguiente tic`() = runTest {
        val store = ComandasPendientesStore(AlmacenFalsoReplay()).apply { cargar("venue-1") }
        store.guardar(venta(1))
        store.guardar(venta(2))
        val salieron = mutableListOf<String>()
        val visto = slot<TrabajoPendiente>()
        val dispatcher = mockk<ComandaDispatcher>()
        coEvery { dispatcher.reintentar(capture(visto), any()) } coAnswers {
            salieron += visto.captured.orderNumber
            if (visto.captured.orderNumber == "ORD-2") venta(2) else EstadoDeComanda.Salio
        }
        val replay = ReplayDeComandasPendientes(store, dispatcher)

        replay.intentarAhora()
        replay.intentarAhora()

        assertEquals("ORD-1 salió dos veces", listOf("ORD-1", "ORD-2", "ORD-2"), salieron)
    }
}
