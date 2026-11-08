package com.avoqado.pos.onlineorders

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.onlineorders.data.FolioCobradoConTarjeta
import com.avoqado.pos.onlineorders.data.FoliosCobradosConTarjeta
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.domain.HuellaDeFolio
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.presentation.OnlineOrdersViewModel
import com.avoqado.pos.onlineorders.presentation.sinLosYaAtendidos
import com.avoqado.pos.onlineorders.presentation.yaEstaListo
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.net.InetAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R101 (Codex fase 3-4 r3): la copia guardada de «Por pagar» es lo que «Cobrar» usa sin red y lo que la pantalla enseña al reiniciar.
 * Con el repositorio REAL (MockWebServer) y la copia en un «disco» de memoria que imita `PayloadCache`:
 * - (1) una marca de tarjeta de folio sólo se suelta si ESA lectura quedó como copia guardada: ninguna copia vieja queda detrás;
 * - (2) lo escondido tras «Listo»/«Entregado»/«Cancelar folio» se guarda en la MISMA copia y vuelve al reiniciar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CopiaGuardadaDeFoliosTest {

    private val server = MockWebServer()
    private val secureStorage = mockk<SecureStorage>()
    private val disco = mutableMapOf<String, PayloadCache.Cached>()
    private var falloAlGuardar = false
    private val cache = mockk<PayloadCache>().also { c ->
        coEvery { c.save(any(), any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), System.currentTimeMillis()) }
        coEvery { c.saveDurable(any(), any(), any()) } answers {
            if (falloAlGuardar) throw IllegalStateException("disco lleno")
            disco["${firstArg<String>()}|${secondArg<String>()}"] = PayloadCache.Cached(thirdArg(), System.currentTimeMillis())
        }
        coEvery { c.load(any(), any()) } answers { disco["${firstArg<String>()}|${secondArg<String>()}"] }
    }
    private lateinit var repo: OnlineOrdersRepository
    private val marcas = FoliosCobradosConTarjeta.enMemoria()

    /** Lo que contesta `to-pay` ahora mismo. */
    private var toPay: MockResponse = MockResponse().setResponseCode(500)

    /** R105: lo que contesta una página extra (con `cursor=`) y el refresco de sesión; y si la próxima página 1 da 401. */
    private var extra: MockResponse = MockResponse().setResponseCode(500)
    private var refresco: MockResponse = MockResponse().setResponseCode(500)
    private var proxima401 = java.util.concurrent.atomic.AtomicBoolean(false)

    /** R102: respuestas de `to-pay` retenidas, en el orden en que llegan las peticiones: cada una sale cuando se abre su compuerta. */
    private val retenidas = ConcurrentLinkedQueue<Pair<CountDownLatch, MockResponse>>()
    private val llegadasToPay = AtomicInteger()

    private suspend fun esperarLlegadas(n: Int) = withTimeout(5_000) { while (llegadasToPay.get() < n) delay(10) }

    @Before fun arrancar() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.endsWith("/mobile/auth/refresh") -> refresco
                request.path!!.contains("/online-orders/to-pay") && request.path!!.contains("cursor=") -> extra
                request.path!!.contains("/online-orders/to-pay") && proxima401.getAndSet(false) -> {
                    llegadasToPay.incrementAndGet()
                    MockResponse().setResponseCode(401).setBody("""{"error":"Unauthorized","message":"Token has expired"}""")
                }
                request.path!!.contains("/online-orders/to-pay") -> {
                    val retenida = retenidas.poll()
                    llegadasToPay.incrementAndGet()
                    if (retenida == null) toPay else retenida.first.await(5, TimeUnit.SECONDS).let { retenida.second }
                }
                request.path!!.contains("/online-orders/inbox") -> MockResponse().setBody(
                    """{"success":true,"data":{"serverTime":"2026-11-06T18:10:00.000Z","counts":{"pending":0,"active":0,"done":0},"orders":[]}}""",
                )
                request.path!!.endsWith("/ready") || request.path!!.endsWith("/cancel") -> MockResponse().setBody("""{"success":true}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        System.setProperty("avoqado.test.baseUrl", server.url("/api/v1").toString().trimEnd('/'))
        every { secureStorage.venueId } returns "v1"
        every { secureStorage.accessToken } returns "tok"
        // Tras cancelar, OkHttp puede intentar la otra dirección de localhost, donde este servidor no escucha.
        repo = OnlineOrdersRepository(secureStorage, OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false)
            .dns(object : okhttp3.Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1"))
            }).build(), cache)
    }

    @After fun apagar() {
        Dispatchers.resetMain()
        System.clearProperty("avoqado.test.baseUrl")
        runCatching { server.shutdown() }
    }

    /** Una pantalla nueva sobre el mismo disco (así se ve un reinicio de la app). */
    private fun pantalla() = OnlineOrdersViewModel(
        repositorio = repo,
        permisoActualizar = { true },
        permisoCancelar = { true },
        tienePlan = { true },
        hayPedidosAbiertos = { true },
        ventanaALaVista = MutableStateFlow(true),
        leerPorPagar = { latido, seccion, cursor -> repo.porPagar(latido, seccion, cursor) },
        foliosGuardados = { repo.porPagarGuardado() },
        guardarOcultos = { repo.guardarOcultos(it) },
        foliosConTarjeta = marcas,
        venueActual = { "v1" },
    )

    private fun folio(id: String, status: String, remaining: String, pago: String) =
        """{"orderId":"$id","onlineOrderId":"oo-$id","orderNumber":"W7K3P","status":"$status","paymentMode":"PAY_AT_PICKUP",
        "customerName":"Ana","customerPhone":null,"note":null,"createdAt":"2026-11-06T18:00:00.000Z",
        "acceptDeadlineAt":"2099-11-06T18:30:00.000Z","estimatedReadyAt":null,"total":134,"remaining":$remaining,"paymentStatus":"$pago",
        "cardStatus":null,"card":null,"rejectReason":null,"requiresAcceptance":false,"channel":"QR","needsPrint":false,"items":[]}"""

    private fun cuerpo(porPagar: String = "", porEntregar: String = "") = MockResponse().setBody(
        """{"success":true,"data":{"serverTime":"2026-11-06T18:10:00.000Z","enabled":true,"requiresAcceptance":false,"showTile":true,
        "pollSeconds":30,"counts":{"toPay":${if (porPagar.isEmpty()) 0 else 1},"toDeliver":${if (porEntregar.isEmpty()) 0 else 1}},
        "toPay":[$porPagar],"toDeliver":[$porEntregar],"nextCursors":{"toPay":null,"toDeliver":null},"store":null}}""",
    )

    private fun cuerpoConCursor(porPagar: String, porEntregar: String, cursorEntregar: String) =
        """{"success":true,"data":{"serverTime":"2026-11-06T18:10:00.000Z","enabled":true,"requiresAcceptance":false,"showTile":true,
        "pollSeconds":30,"counts":{"toPay":${if (porPagar.isEmpty()) 0 else 1},"toDeliver":2},
        "toPay":[$porPagar],"toDeliver":[$porEntregar],"nextCursors":{"toPay":null,"toDeliver":"$cursorEntregar"},"store":null}}"""

    private suspend fun esperarQueTermine(vm: OnlineOrdersViewModel, orderId: String) =
        withTimeout(5_000) { vm.ocupados.first { orderId !in it } }

    // MARK: - (2) lo escondido sobrevive al reinicio

    @Test
    fun `P2 Listo confirmed, the re-read fails and a restart with a 500 brings no live Listo - a READY read shows it with Entregado`() = runBlocking {
        toPay = cuerpo(porEntregar = folio("f1", "ACCEPTED", "0", "PAID"))
        val antes = pantalla()
        antes.descubrirPorPagar()

        toPay = MockResponse().setResponseCode(500)
        antes.marcarListo("f1")
        esperarQueTermine(antes, "f1")
        assertTrue("en este proceso, escondido", sinLosYaAtendidos(antes.porPagar.value!!, antes.ocultos.value).porEntregar.isEmpty())

        // Reinicio: la lista guardada es la de antes de «Listo» y la lectura vuelve a dar 500.
        val tras = pantalla()
        tras.descubrirPorPagar()
        assertEquals("la copia guardada sigue trayendo el folio como estaba", OnlineOrderStatus.ACCEPTED, tras.porPagar.value!!.porEntregar.single().status)
        assertTrue("pero sin su «Listo» vivo", sinLosYaAtendidos(tras.porPagar.value!!, tras.ocultos.value).porEntregar.isEmpty())

        // El SERVIDOR lo trae READY: vuelve, con la etiqueta «Listo» y sólo «Entregado»; la huella se suelta, también en la copia.
        toPay = cuerpo(porEntregar = folio("f1", "READY", "0", "PAID"))
        tras.refrescar()
        val vuelve = sinLosYaAtendidos(tras.porPagar.value!!, tras.ocultos.value).porEntregar.single()
        assertEquals(OnlineOrderStatus.READY, vuelve.status)
        assertTrue(yaEstaListo(vuelve))
        assertTrue(tras.ocultos.value.isEmpty())
        assertTrue(repo.porPagarGuardado()!!.ocultos.isEmpty())
    }

    @Test
    fun `saving page 1 keeps the hides that still match and prunes the rest`() = runBlocking {
        toPay = cuerpo(porPagar = folio("p1", "PENDING_ACCEPTANCE", "134", "PENDING"), porEntregar = folio("f1", "ACCEPTED", "0", "PAID"))
        repo.porPagar(conLatido = false).getOrThrow()
        val ocultos = mapOf(
            "p1" to HuellaDeFolio("toPay", OnlineOrderStatus.PENDING_ACCEPTANCE, 13400),
            "f1" to HuellaDeFolio("toDeliver", OnlineOrderStatus.ACCEPTED, 0),
        )
        assertTrue(repo.guardarOcultos(ocultos))
        assertEquals(ocultos, repo.porPagarGuardado()!!.ocultos)

        // f1 cambió (READY): se suelta; p1 sigue igual: se queda.
        toPay = cuerpo(porPagar = folio("p1", "PENDING_ACCEPTANCE", "134", "PENDING"), porEntregar = folio("f1", "READY", "0", "PAID"))
        repo.porPagar(conLatido = false).getOrThrow()
        assertEquals(ocultos.filterKeys { it == "p1" }, repo.porPagarGuardado()!!.ocultos)

        // p1 ya no está en ninguna lista completa: se poda.
        toPay = cuerpo(porEntregar = folio("f1", "READY", "0", "PAID"))
        repo.porPagar(conLatido = false).getOrThrow()
        assertTrue(repo.porPagarGuardado()!!.ocultos.isEmpty())
    }

    // MARK: - (1) la marca se suelta en el mismo paso que la copia nueva

    @Test
    fun `P1 a complete to-pay read that counts the card payment releases the marker and the saved copy drops the old balance`() = runBlocking {
        toPay = cuerpo(porPagar = folio("f1", "PENDING_ACCEPTANCE", "134", "PENDING"))
        val vm = pantalla()
        vm.descubrirPorPagar()
        marcas.anotar(FolioCobradoConTarjeta("f1", 13400, 13400, enMs = 0, venueId = "v1", requestId = "req-1"))

        toPay = cuerpo(porEntregar = folio("f1", "ACCEPTED", "0", "PAID"))
        vm.refrescar()

        assertTrue("el SERVIDOR ya lo cuenta: se suelta", marcas.marcas().isEmpty())
        val copia = repo.porPagarGuardado()!!
        assertTrue("la copia guardada ya no dice «Por pagar $134»", copia.porPagar.none { it.orderId == "f1" })
        assertEquals(listOf("f1"), copia.porEntregar.map { it.orderId })
    }

    // MARK: - R102: la copia (y la lista en memoria) sólo avanza

    /**
     * R103 (Codex r5): el número de R102 mide cuándo SALIÓ la petición, no qué tan nueva es la foto del servidor: con dos lecturas a la
     * vez, el servidor puede atender la #2 antes que la #1. Por eso la página 1 de `to-pay` se lee ENTERA bajo un candado del
     * repositorio único: la lectura de la bandeja no sale hasta que la del vigilante ya se aplicó, y su foto es posterior.
     */
    @Test
    fun `P1 the tray and the watcher never overlap a to-pay read - the second request is not sent until the first response is applied`() = runBlocking {
        toPay = cuerpo(porPagar = folio("f1", "PENDING_ACCEPTANCE", "134", "PENDING"))
        val vm = pantalla()
        vm.descubrirPorPagar()
        marcas.anotar(FolioCobradoConTarjeta("f1", 13400, 13400, enMs = 0, venueId = "v1", requestId = "req-1"))

        // El vigilante lee antes del pago; el servidor tarda en contestarle.
        val compuerta = CountDownLatch(1)
        retenidas.add(compuerta to cuerpo(porPagar = folio("f1", "PENDING_ACCEPTANCE", "134", "PENDING")))
        val antes = llegadasToPay.get()
        val delVigilante = async(Dispatchers.IO) { repo.porPagar(conLatido = false) }
        esperarLlegadas(antes + 1)

        // La bandeja quiere leer (ya después del pago): espera su turno, no sale a la red.
        toPay = cuerpo(porEntregar = folio("f1", "ACCEPTED", "0", "PAID"))
        val deLaBandeja = async(Dispatchers.IO) { vm.refrescar() }
        delay(400)
        assertEquals("la segunda lectura no sale mientras la primera sigue en el aire", antes + 1, llegadasToPay.get())
        assertEquals("y nada se soltó todavía", 1, marcas.marcas().size)

        compuerta.countDown()
        assertTrue(delVigilante.await().getOrThrow().copiaGuardada)
        deLaBandeja.await()
        assertEquals(antes + 2, llegadasToPay.get())
        assertTrue("la lectura posterior al pago es la última: suelta la marca", marcas.marcas().isEmpty())
        assertTrue("y la copia dice pagado", repo.porPagarGuardado()!!.porPagar.none { it.orderId == "f1" })

        // Reinicio sin red: la lista guardada no ofrece «Cobrar» de ese folio.
        toPay = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
        val tras = pantalla()
        tras.descubrirPorPagar()
        assertTrue(tras.porPagar.value!!.porPagar.none { it.orderId == "f1" })
        assertEquals(listOf("f1"), tras.porPagar.value!!.porEntregar.map { it.orderId })
    }

    /** R103: una lectura que se abandona (cancelada) suelta el candado al instante y nunca se aplica. */
    @Test
    fun `a cancelled to-pay read releases the lock at once and is never applied`() = runBlocking {
        toPay = cuerpo(porPagar = folio("f1", "PENDING_ACCEPTANCE", "134", "PENDING"))
        repo.porPagar(conLatido = false).getOrThrow()

        val compuerta = CountDownLatch(1)
        retenidas.add(compuerta to cuerpo(porPagar = folio("f9", "PENDING_ACCEPTANCE", "99", "PENDING")))
        val antes = llegadasToPay.get()
        val abandonada = async(Dispatchers.IO) { repo.porPagar(conLatido = false) }
        esperarLlegadas(antes + 1)
        abandonada.cancel()

        // Antes de que venza el plazo de lectura (2 s): la siguiente lectura ya pasa.
        toPay = cuerpo(porEntregar = folio("f1", "ACCEPTED", "0", "PAID"))
        val siguiente = withTimeout(1_500) { repo.porPagar(conLatido = false).getOrThrow() }
        assertTrue(siguiente.copiaGuardada)
        compuerta.countDown()
        assertTrue(abandonada.isCancelled)
        val copia = repo.porPagarGuardado()!!
        assertTrue("la abandonada no se aplicó", copia.porPagar.none { it.orderId == "f9" })
        assertEquals(listOf("f1"), copia.porEntregar.map { it.orderId })
    }

    /**
     * R104 (Codex r6 #2, regresión de A9): una respuesta que llega a gotas no vence el plazo de LECTURA (cada gota llega a tiempo) y
     * retenía el candado sin fin. La página 1 lleva un plazo TOTAL: al vencer se cancela, suelta el candado y no se aplica.
     */
    @Test
    fun `a slow-drip to-pay response is cut by the total deadline and the tray reads`() = runBlocking {
        repo.plazoTotalDeFoliosMs = 1_000
        val gotas = cuerpo(porPagar = folio("f9", "PENDING_ACCEPTANCE", "99", "PENDING")).throttleBody(16, 250, TimeUnit.MILLISECONDS)
        retenidas.add(CountDownLatch(0) to gotas)
        val antes = llegadasToPay.get()
        val delVigilante = async(Dispatchers.IO) { repo.porPagar(conLatido = false) }
        esperarLlegadas(antes + 1)

        toPay = cuerpo(porEntregar = folio("f1", "ACCEPTED", "0", "PAID"))
        val deLaBandeja = withTimeout(4_000) { repo.porPagar(conLatido = false).getOrThrow() }
        assertTrue(deLaBandeja.copiaGuardada)
        assertTrue("la lectura a gotas venció y no se aplicó", delVigilante.await().isFailure)
        assertTrue(repo.porPagarGuardado()!!.porPagar.none { it.orderId == "f9" })
    }

    // MARK: - R105: todo lo que esta pantalla espera lleva plazo TOTAL

    /**
     * R105 (a) (Codex r7 #2): la página 1 recibe 401 y el autenticador renueva la sesión con su cliente propio; esa renovación llega a
     * gotas. Cancelar la llamada original no la corta, así que sin un plazo total propio la página 1 se quedaba con el candado.
     */
    @Test
    fun `a slow-drip session refresh after a 401 releases the page-1 lock within its deadline`() = runBlocking {
        val sesion = mockk<SecureStorage>(relaxed = true).also { every { it.refreshToken } returns "refresh-viejo" }
        val autenticador = com.avoqado.pos.core.data.network.TokenRefreshAuthenticator(sesion, com.avoqado.pos.core.data.network.RefrescoExclusivo())
        autenticador.refreshBaseUrl = server.url("/api/v1").toString().trimEnd('/')
        autenticador.plazoTotalDelRefrescoMs = 1_000
        autenticador.reportarFallo = {}
        val conSesion = OnlineOrdersRepository(
            secureStorage, OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).authenticator(autenticador).build(), cache,
        )
        refresco = MockResponse().setBody("""{"accessToken":"a","refreshToken":"r","padding":"${"x".repeat(400)}"}""").throttleBody(8, 250, TimeUnit.MILLISECONDS)
        proxima401.set(true)
        val antes = llegadasToPay.get()
        val conRenovacion = async(Dispatchers.IO) { conSesion.porPagar(conLatido = false) }
        esperarLlegadas(antes + 1)

        toPay = cuerpo(porEntregar = folio("f1", "ACCEPTED", "0", "PAID"))
        val luego = withTimeout(3_500) { conSesion.porPagar(conLatido = false).getOrThrow() }
        assertTrue("la página 1 siguiente sí pasó", luego.copiaGuardada)
        assertTrue("la renovación vencida falla como sin red, sin cerrar sesión", conRenovacion.await().isFailure)
        io.mockk.verify(exactly = 0) { sesion.clearSessionSiSigue(any()) }
    }

    @Test
    fun `the session refresh client has a total deadline by default`() {
        val autenticador = com.avoqado.pos.core.data.network.TokenRefreshAuthenticator(mockk(relaxed = true), com.avoqado.pos.core.data.network.RefrescoExclusivo())
        assertEquals(15_000L, autenticador.plazoTotalDelRefrescoMs)
    }

    /**
     * R105 (b) y (c) (Codex r7 #1): una página extra que llega a gotas no retrasa la página 1 (un folio nuevo por cobrar sale YA) y la
     * corta su propio plazo total; lo que ya se veía de las extras no se borra.
     */
    @Test
    fun `a slow-drip extra page does not delay page 1 and is cut by its deadline`() = runBlocking {
        repo.plazoTotalDeFoliosMs = 1_000
        toPay = MockResponse().setBody(cuerpoConCursor(porPagar = "", porEntregar = folio("e1", "ACCEPTED", "0", "PAID"), cursorEntregar = "d1"))
        extra = cuerpo(porEntregar = folio("e2", "ACCEPTED", "0", "PAID"))
        val vm = pantalla()
        vm.descubrirPorPagar()
        vm.cargarMasFolios(OnlineOrdersViewModel.SECCION_POR_ENTREGAR)
        withTimeout(5_000) { while (vm.porPagar.value!!.porEntregar.none { it.orderId == "e2" }) delay(20) }

        // La extra ahora gotea; la página 1 trae un folio NUEVO por cobrar.
        extra = cuerpo(porEntregar = folio("e2", "ACCEPTED", "0", "PAID")).throttleBody(8, 250, TimeUnit.MILLISECONDS)
        toPay = MockResponse().setBody(
            cuerpoConCursor(porPagar = folio("n1", "PENDING_ACCEPTANCE", "50", "PENDING"), porEntregar = folio("e1", "ACCEPTED", "0", "PAID"), cursorEntregar = "d1"),
        )
        withTimeout(800) { vm.refrescar() }
        assertTrue("el folio nuevo sale sin esperar a la extra", vm.porPagar.value!!.porPagar.any { it.orderId == "n1" })
        assertTrue("y lo de la extra no se borra mientras", vm.porPagar.value!!.porEntregar.any { it.orderId == "e2" })

        // El plazo total corta la extra: se dice, y la lista no se recorta.
        withTimeout(4_000) { while (!vm.sinConexion.value && !vm.noSePudoLeer.value) delay(20) }
        assertTrue(vm.porPagar.value!!.porEntregar.any { it.orderId == "e2" })
    }

    @Test
    fun `reads are applied in order - a later read still replaces an earlier one`() = runBlocking {
        val primera = CountDownLatch(1)
        retenidas.add(primera to cuerpo(porPagar = folio("f1", "PENDING_ACCEPTANCE", "134", "PENDING")))
        toPay = cuerpo(porEntregar = folio("f1", "ACCEPTED", "0", "PAID"))
        val a = async(Dispatchers.IO) { repo.porPagar(conLatido = false) }
        esperarLlegadas(1)
        val b = async(Dispatchers.IO) { repo.porPagar(conLatido = false) }
        delay(200)
        assertEquals("la segunda espera su turno", 1, llegadasToPay.get())

        primera.countDown()
        assertTrue(a.await().getOrThrow().copiaGuardada)
        val luego = b.await().getOrThrow()
        assertTrue(luego.copiaGuardada)
        assertFalse(luego.masViejaQueLaCopia)
        assertTrue(repo.porPagarGuardado()!!.porPagar.none { it.orderId == "f1" })
    }

    @Test
    fun `if the saved copy cannot be written the marker stays`() = runBlocking {
        toPay = cuerpo(porPagar = folio("f1", "PENDING_ACCEPTANCE", "134", "PENDING"))
        val vm = pantalla()
        vm.descubrirPorPagar()
        marcas.anotar(FolioCobradoConTarjeta("f1", 13400, 13400, enMs = 0, venueId = "v1", requestId = "req-1"))

        falloAlGuardar = true
        toPay = cuerpo(porEntregar = folio("f1", "ACCEPTED", "0", "PAID"))
        val leida = repo.porPagar(conLatido = false).getOrThrow()
        assertFalse("la lectura dice que su copia no quedó", leida.copiaGuardada)
        vm.refrescar()
        assertEquals("la copia vieja ($134) sigue en el disco: la marca se queda", 1, marcas.marcas().size)
        assertEquals(13400L, repo.porPagarGuardado()!!.porPagar.single().remainingCents)
    }
}
