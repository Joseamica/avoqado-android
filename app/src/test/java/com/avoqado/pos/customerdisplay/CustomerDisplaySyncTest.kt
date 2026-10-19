package com.avoqado.pos.customerdisplay

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import java.io.File
import java.time.Instant
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * MockWebServer + `DisplayModeRemoteRepository` real + almacén en carpeta temporal + `CustomerDisplayState` real.
 *
 * El caché de imágenes va con MockK: en las pruebas JVM de `app` `BitmapFactory` es un stub (`isReturnDefaultValues`) y el
 * caché real no puede decodificar. Sus reglas (hash, `If-None-Match`, 24 h, versión) las prueba
 * `CustomerDisplayImageCacheTest` en escritorio; aquí se prueba lo que el sync hace con lo que el caché responde.
 *
 * `MainDispatcherRule`: todo `offerDesign`/`clearDesign` pasa por `Dispatchers.Main.immediate`; sin la regla, Main no existe en
 * la JVM y la prueba truena. La última prueba cambia Main por un hilo propio y comprueba que ahí corren.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomerDisplaySyncTest {

    @get:Rule val mainRule = MainDispatcherRule()
    @get:Rule val carpeta = TemporaryFolder()

    private lateinit var server: MockWebServer
    private val fichaRequests = AtomicInteger()
    @Volatile private var fichaResponse: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(500) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var sesion: String? = VENUE
    @Volatile private var reloj = T0
    private val state = CustomerDisplayState()

    // El caché de mentiras: lo que hay en disco por URL (como el real: el legado guarda una copia por URL) y la versión validada.
    private val images = mockk<CustomerDisplayImageCache>()
    private val disco = Collections.synchronizedMap(HashMap<String, File>())
    private val versionValidada = Collections.synchronizedMap(HashMap<String, Pair<String, Long>>())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (request.path!!.endsWith("/customer-display")) {
                    fichaRequests.incrementAndGet()
                    fichaResponse(request)
                } else MockResponse().setResponseCode(404)
        }
        server.start()
        every { images.cachedFile(any()) } answers { disco[url(firstArg())] }
        every { images.revalidationDue(any()) } answers {
            val ref = firstArg<LogoRef>()
            ref is LogoRef.Venue && versionValidada[ref.url].let { it == null || it.first != ref.version || reloj - it.second >= DIA_MS }
        }
        coEvery { images.ensure(any(), any()) } coAnswers { bajar(firstArg()) }
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    // ── huella → ficha → manifiesto → diseño ──

    @Test
    fun `huella nueva baja la ficha la guarda y la ofrece`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        val sync = sync()

        val reintenta = sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        assertFalse(reintenta)
        assertEquals(1, fichaRequests.get())
        val guardado = store().load()
        val manifiesto = guardado.manifests.getValue(LLAVE)
        assertEquals(HUELLA_A, manifiesto.ficha!!.fingerprint)
        assertEquals("2026-10-18T12:00:00Z", manifiesto.storedAt)
        assertNull(manifiesto.shownAt)
        assertEquals(HUELLA_A, manifiesto.pendingAck!!.fingerprint)
        assertEquals(1L, manifiesto.pendingAck!!.seq)
        assertEquals(guardado.installationId, manifiesto.pendingAck!!.installationId)
        assertFalse(manifiesto.pendingAck!!.unsupportedSchema)
        assertEquals(2L, guardado.nextSeq)
        assertEquals(TERMINAL, guardado.lastTerminalByVenue["test|$VENUE"])
        assertEquals(HUELLA_A, state.design.value!!.ficha.fingerprint)
        assertTrue(state.design.value!!.ficha.dark)
    }

    @Test
    fun `huella igual no pide nada`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        assertEquals(1, fichaRequests.get())
        assertEquals(2L, store().load().nextSeq)
    }

    @Test
    fun `huella omitida no borra ni pide nada`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        val antes = store().load()

        val reintenta = sync.afterPoll(VENUE, TERMINAL, PollSignals())

        assertFalse(reintenta)
        assertEquals(1, fichaRequests.get())
        assertEquals(antes, store().load())
        assertEquals(HUELLA_A, state.design.value!!.ficha.fingerprint)
    }

    @Test
    fun `404 deja la fabrica sin acuse`() = runBlocking {
        fichaResponse = { MockResponse().setResponseCode(404).setBody("{\"code\":\"DEVICE_NOT_FOUND\"}") }
        val sync = sync()

        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        assertNull(state.design.value)
        val guardado = store().load()
        assertTrue(guardado.manifests.isEmpty())
        assertEquals(1L, guardado.nextSeq)
    }

    @Test
    fun `aparato desligado borra el diseno guardado de esa sucursal`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        // Otra sucursal del mismo aparato no se toca.
        store().update { it.copy(manifests = it.manifests + ("test|otra|t9" to DesignManifest(storedAt = "x"))) }

        sync.onDeviceUnbound(VENUE)

        assertNull(state.design.value)
        val guardado = store().load()
        assertEquals(setOf("test|otra|t9"), guardado.manifests.keys)
        assertNull(guardado.lastTerminalByVenue["test|$VENUE"])
        // Una instancia nueva SIN red sigue en fábrica.
        val otroEstado = CustomerDisplayState()
        sync(estado = otroEstado)
        Thread.sleep(200)
        assertNull(otroEstado.design.value)
    }

    // ── el logo ──

    @Test
    fun `el poll no espera la descarga del logo`() = runBlocking {
        val retenida = CompletableDeferred<Unit>()
        coEvery { images.ensure(any(), any()) } coAnswers { retenida.await(); bajar(firstArg()) }
        fichaResponse = { ficha(HUELLA_A, logo = LOGO_ASSET_JSON) }
        val sync = sync()

        withTimeout(5_000) { sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A)) }

        assertEquals(HUELLA_A, state.design.value!!.ficha.fingerprint)
        assertNull(state.design.value!!.logoFile)
        assertEquals(1, store().load().manifests.getValue(LLAVE).pendingAck!!.imagesPending)

        retenida.complete(Unit)

        esperar { state.design.value?.logoFile == disco[LOGO_URL] }
        esperar { store().load().manifests.getValue(LLAVE).pendingAck!!.imagesReady == 1 }
        val acuse = store().load().manifests.getValue(LLAVE).pendingAck!!
        assertEquals(2L, acuse.seq)
        assertEquals(0, acuse.imagesPending)
        assertEquals(HUELLA_A, acuse.fingerprint)
    }

    @Test
    fun `dos polls con la imagen pendiente hacen una sola descarga`() = runBlocking {
        val retenida = CompletableDeferred<Unit>()
        coEvery { images.ensure(any(), any()) } coAnswers { retenida.await(); bajar(firstArg()) }
        fichaResponse = { ficha(HUELLA_A, logo = LOGO_ASSET_JSON) }
        val sync = sync()

        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        retenida.complete(Unit)

        esperar { store().load().manifests.getValue(LLAVE).pendingAck!!.imagesReady == 1 }
        coVerify(exactly = 1) { images.ensure(any(), any()) }
        assertEquals(disco[LOGO_URL], state.design.value!!.logoFile)
    }

    @Test
    fun `logo en vuelo y luego 404 se queda en fabrica`() = runBlocking {
        val retenida = CompletableDeferred<Unit>()
        val bajando = CountDownLatch(1)
        // Como OkHttp bloqueante: la descarga NO se entera de la cancelación y termina igual.
        coEvery { images.ensure(any(), any()) } coAnswers {
            bajando.countDown()
            withContext(NonCancellable) { retenida.await() }
            bajar(firstArg())
        }
        fichaResponse = { ficha(HUELLA_A, logo = LOGO_ASSET_JSON) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        assertTrue(bajando.await(5, TimeUnit.SECONDS))

        sync.onDeviceUnbound(VENUE)
        retenida.complete(Unit)
        Thread.sleep(300)

        assertNull(state.design.value)
        assertTrue(store().load().manifests.isEmpty())
    }

    @Test
    fun `logo legado con la misma url y version nueva se revalida`() = runBlocking {
        val copia = carpeta.newFile("copia-v1.img")
        disco[LEGADO_URL] = copia
        versionValidada[LEGADO_URL] = "v1" to T0
        fichaResponse = { ficha(HUELLA_A, logo = legadoJson("v1")) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        coVerify(exactly = 0) { images.ensure(any(), any()) }

        val retenida = CompletableDeferred<Unit>()
        coEvery { images.ensure(any(), any()) } coAnswers { retenida.await(); bajar(firstArg()) }
        fichaResponse = { ficha(HUELLA_B, logo = legadoJson("v2")) }
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_B))

        // La copia vigente sigue visible mientras se revalida.
        assertEquals(HUELLA_B, state.design.value!!.ficha.fingerprint)
        assertEquals(copia, state.design.value!!.logoFile)
        retenida.complete(Unit)
        esperar { state.design.value!!.logoFile != copia }
        coVerify(exactly = 1) { images.ensure(LogoRef.Venue(LEGADO_URL, "v2"), any()) }
        assertEquals(disco[LEGADO_URL], state.design.value!!.logoFile)
    }

    @Test
    fun `a las 24 h se revalida aunque no cambie la version`() = runBlocking {
        disco[LEGADO_URL] = carpeta.newFile("copia.img")
        versionValidada[LEGADO_URL] = "v1" to T0
        fichaResponse = { ficha(HUELLA_A, logo = legadoJson("v1")) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        reloj = T0 + DIA_MS - 1
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        coVerify(exactly = 0) { images.ensure(any(), any()) }

        reloj = T0 + DIA_MS
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        coVerify(timeout = 5_000, exactly = 1) { images.ensure(LogoRef.Venue(LEGADO_URL, "v1"), any()) }
        assertEquals(1, fichaRequests.get())
    }

    @Test
    fun `un logo que sigue pendiente no encola un acuse en cada poll`() = runBlocking {
        coEvery { images.ensure(any(), any()) } returns ImageStatus.PENDING
        fichaResponse = { ficha(HUELLA_A, logo = LOGO_ASSET_JSON) }
        val sync = sync()

        repeat(3) { vuelta ->
            sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
            coVerify(timeout = 5_000, exactly = vuelta + 1) { images.ensure(any(), any()) }
            Thread.sleep(150)   // que el trabajo termine (publicar y decidir el acuse) antes del siguiente poll
        }

        assertEquals(2L, store().load().nextSeq)
        assertEquals(1, store().load().manifests.getValue(LLAVE).pendingAck!!.imagesPending)
    }

    // ── esquema, errores y sesión ──

    @Test
    fun `schema desconocido conserva el diseno y acusa unsupportedSchema`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        fichaResponse = { ficha(HUELLA_B, schema = 2) }

        val reintenta = sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_B))

        assertFalse(reintenta)
        val manifiesto = store().load().manifests.getValue(LLAVE)
        assertEquals(HUELLA_A, manifiesto.ficha!!.fingerprint)
        assertEquals(HUELLA_B, manifiesto.unsupportedFingerprint)
        assertEquals(HUELLA_B, manifiesto.pendingAck!!.fingerprint)
        assertTrue(manifiesto.pendingAck!!.unsupportedSchema)
        assertEquals(2L, manifiesto.pendingAck!!.seq)
        assertEquals(HUELLA_A, state.design.value!!.ficha.fingerprint)
    }

    @Test
    fun `ficha con otra forma y schema 2 se acusa unsupportedSchema, no se reintenta para siempre`() = runBlocking {
        fichaResponse = {
            MockResponse().setBody(
                "{\"data\":{\"terminalId\":\"$TERMINAL\",\"imageOrigins\":[]," +
                    "\"ficha\":{\"schemaVersion\":2,\"fingerprint\":\"$HUELLA_B\",\"theme\":{\"mode\":\"DARK\"},\"logo\":[1,2]}}}",
            )
        }
        val sync = sync()

        assertFalse(sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_B)))
        assertFalse(sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_B)))

        assertEquals(1, fichaRequests.get())
        val manifiesto = store().load().manifests.getValue(LLAVE)
        assertNull(manifiesto.ficha)
        assertTrue(manifiesto.pendingAck!!.unsupportedSchema)
        assertEquals(HUELLA_B, manifiesto.pendingAck!!.fingerprint)
        assertNull(state.design.value)
    }

    @Test
    fun `500 conserva todo y pide reintento`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        val antes = store().load()
        fichaResponse = { MockResponse().setResponseCode(500) }

        val reintenta = sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_B))

        assertTrue(reintenta)
        assertEquals(antes, store().load())
        assertEquals(HUELLA_A, state.design.value!!.ficha.fingerprint)
    }

    @Test
    fun `una ficha que llega tras cambiar de sesion no se publica`() = runBlocking {
        val soltar = CountDownLatch(1)
        fichaResponse = { soltar.await(5, TimeUnit.SECONDS); ficha(HUELLA_A) }
        val sync = sync()

        val poll = async(Dispatchers.IO) { sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A)) }
        esperar { fichaRequests.get() == 1 }
        sesion = "venue-2"
        sync.onSessionChanged()
        soltar.countDown()
        poll.await()

        assertNull(store().load().manifests[LLAVE])
        assertNull(state.design.value)
    }

    @Test
    fun `arranque sin red publica lo ultimo guardado`() = runBlocking {
        disco[LOGO_URL] = carpeta.newFile("logo.img")
        fichaResponse = { ficha(HUELLA_A, logo = LOGO_ASSET_JSON) }
        sync().afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        fichaResponse = { MockResponse().setResponseCode(503) }   // la instancia nueva ni siquiera hace poll
        val otroEstado = CustomerDisplayState()

        sync(estado = otroEstado)

        esperar { otroEstado.design.value != null }
        assertEquals(HUELLA_A, otroEstado.design.value!!.ficha.fingerprint)
        assertEquals(disco[LOGO_URL], otroEstado.design.value!!.logoFile)
    }

    @Test
    fun `logout retira el diseno al instante`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        state.showCart(CartState(items = listOf(CartItem(id = "p1", type = CartItemType.ProductItem("cafe"), name = "Café", unitPrice = 5000))))
        assertTrue(state.content.value !is CustomerContent.Idle)
        assertEquals(HUELLA_A, state.design.value!!.ficha.fingerprint)

        sesion = null
        sync.onSessionChanged()

        assertNull(state.design.value)
    }

    @Test
    fun `el acuse copia storedAt shownAt y surface del manifiesto`() {
        val manifiesto = DesignManifest(storedAt = "2026-10-18T12:00:00Z", shownAt = "2026-10-18T12:05:00Z", surface = "presentation")

        val acuse = sync().buildAck(manifiesto, HUELLA_A, unsupported = false, images = Triple(1, 0, 0))

        assertEquals("2026-10-18T12:00:00Z", acuse.storedAt)
        assertEquals("2026-10-18T12:05:00Z", acuse.shownAt)
        assertEquals("presentation", acuse.surface)
        assertEquals(1, acuse.imagesReady)
        assertEquals(store().load().installationId, acuse.installationId)
    }

    @Test
    fun `ofrecer y retirar el diseno siempre corre en Main`() = runBlocking {
        val principal = Executors.newSingleThreadExecutor { Thread(it, "main-de-prueba") }
        Dispatchers.setMain(principal.asCoroutineDispatcher())   // la regla lo regresa al terminar
        try {
            val hilos = Collections.synchronizedList(mutableListOf<String>())
            val espia = spyk(CustomerDisplayState())
            every { espia.offerDesign(any()) } answers { hilos += "offer@" + hilo(); callOriginal() }
            every { espia.clearDesign() } answers { hilos += "clear@" + hilo(); callOriginal() }
            fichaResponse = { ficha(HUELLA_A) }
            val sync = sync(estado = espia)

            sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
            sync.onDeviceUnbound(VENUE)

            esperar { hilos.size >= 3 }
            assertEquals(listOf("clear@main-de-prueba", "offer@main-de-prueba", "clear@main-de-prueba"), hilos.toList())
        } finally {
            principal.shutdownNow()
        }
    }

    @Test
    fun `logo revalidado con 200 en la misma ruta se vuelve a ofrecer`() = runBlocking {
        // Como el caché real: la copia del legado vive en UNA ruta por URL y un 200 la reescribe en el mismo lugar.
        val misma = carpeta.newFile("legado.img").apply { writeBytes(byteArrayOf(1)); setLastModified(T0) }
        disco[LEGADO_URL] = misma
        versionValidada[LEGADO_URL] = "v1" to T0
        val retenida = CompletableDeferred<Unit>()
        coEvery { images.ensure(any(), any()) } coAnswers {
            retenida.await()
            val ref = firstArg<LogoRef.Venue>()
            misma.writeBytes(byteArrayOf(2))
            misma.setLastModified(T0 + 60_000)
            versionValidada[ref.url] = ref.version to reloj
            ImageStatus.READY
        }
        fichaResponse = { ficha(HUELLA_A, logo = legadoJson("v2")) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        val antes = state.design.value!!
        assertEquals(misma, antes.logoFile)

        retenida.complete(Unit)

        esperar { state.design.value !== antes }
        assertNotEquals(antes, state.design.value)
        assertEquals(misma, state.design.value!!.logoFile)
    }

    @Test
    fun `revalidar el logo no tapa el acuse unsupportedSchema`() = runBlocking {
        disco[LEGADO_URL] = carpeta.newFile("copia.img")
        versionValidada[LEGADO_URL] = "v1" to T0
        fichaResponse = { ficha(HUELLA_A, logo = legadoJson("v1")) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        fichaResponse = { ficha(HUELLA_B, schema = 2) }
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_B))

        reloj = T0 + DIA_MS   // toca revalidar el logo de la ficha que se conserva
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_B))

        coVerify(timeout = 5_000, exactly = 1) { images.ensure(any(), any()) }
        Thread.sleep(300)
        val acuse = store().load().manifests.getValue(LLAVE).pendingAck!!
        assertEquals(HUELLA_B, acuse.fingerprint)
        assertTrue(acuse.unsupportedSchema)
    }

    @Test
    fun `lo guardado del arranque no tapa la ficha del primer poll`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        sync().afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        // El arranque lee A del disco y se queda retenido (disco lento) mientras el primer poll trae B.
        val leido = CountDownLatch(1)
        val soltar = CountDownLatch(1)
        val primera = AtomicBoolean(true)
        val espia = spyk(store())
        every { espia.load() } answers {
            val leidoDelDisco = callOriginal()
            if (primera.getAndSet(false)) {
                leido.countDown()
                soltar.await(5, TimeUnit.SECONDS)
            }
            leidoDelDisco
        }
        val otroEstado = CustomerDisplayState()
        val sync = sync(estado = otroEstado, almacen = espia)
        assertTrue(leido.await(5, TimeUnit.SECONDS))
        fichaResponse = { ficha(HUELLA_B) }
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_B))
        assertEquals(HUELLA_B, otroEstado.design.value!!.ficha.fingerprint)

        soltar.countDown()
        Thread.sleep(300)

        assertEquals(HUELLA_B, otroEstado.design.value!!.ficha.fingerprint)
    }

    // ── ayudas ──

    private fun store() = CustomerDisplayDesignStore(File(carpeta.root, "customer-display"))

    private fun sync(estado: CustomerDisplayState = state, almacen: CustomerDisplayDesignStore = store()) = CustomerDisplaySync(
        remote = DisplayModeRemoteRepository(OkHttpClient()) { server.url("/api/v1").toString().removeSuffix("/") },
        store = almacen,
        images = images,
        state = estado,
        kioskEnabled = MutableStateFlow(false),
        sessionVenue = { sesion },
        environment = { "test" },
        wallClock = { reloj },
        scope = scope,
    )

    private fun senales(huella: String) = PollSignals(fingerprints = PollFingerprints(customerDisplay = huella))

    private fun ficha(huella: String, schema: Int = 1, logo: String = "null") = MockResponse().setBody(
        "{\"data\":{\"terminalId\":\"$TERMINAL\",\"imageOrigins\":[\"https://storage.googleapis.com\"]," +
            "\"ficha\":{\"schemaVersion\":$schema,\"fingerprint\":\"$huella\",\"theme\":\"DARK\",\"logo\":$logo}}}",
    )

    private fun legadoJson(version: String) = "{\"source\":\"venue\",\"url\":\"$LEGADO_URL\",\"version\":\"$version\"}"

    /** Lo que haría el caché real al terminar bien: deja un archivo (nuevo) en disco y anota la versión validada. */
    private fun bajar(ref: LogoRef): ImageStatus {
        disco[url(ref)] = carpeta.newFile()
        if (ref is LogoRef.Venue) versionValidada[ref.url] = ref.version to reloj
        return ImageStatus.READY
    }

    /** Sin el sufijo « @coroutine#N» que agrega el modo de depuración de corrutinas. */
    private fun hilo() = Thread.currentThread().name.substringBefore(" @")

    private fun url(ref: LogoRef) = when (ref) {
        is LogoRef.Asset -> ref.url
        is LogoRef.Venue -> ref.url
    }

    private fun esperar(condicion: () -> Boolean) {
        val limite = System.currentTimeMillis() + 5_000
        while (!condicion()) {
            if (System.currentTimeMillis() > limite) fail("no se cumplió a tiempo")
            Thread.sleep(10)
        }
    }

    private companion object {
        const val VENUE = "venue-1"
        const val TERMINAL = "t1"
        const val LLAVE = "test|venue-1|t1"
        const val HUELLA_A = "0123456789abcdef0123456789abcdef"
        const val HUELLA_B = "fedcba9876543210fedcba9876543210"
        const val LOGO_URL = "https://storage.googleapis.com/b/logo.png"
        const val LEGADO_URL = "https://storage.googleapis.com/b/legado.png"
        const val OTRO_LOGO_URL = "https://storage.googleapis.com/b/otro.png"
        const val SHA_OTRO = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        const val OTRO_LOGO_JSON = "{\"source\":\"asset\",\"url\":\"$OTRO_LOGO_URL\",\"sha256\":\"$SHA_OTRO\"}"
        const val HUELLA_C = "00112233445566778899aabbccddeeff"
        const val LOGO_ASSET_JSON =
            "{\"source\":\"asset\",\"url\":\"$LOGO_URL\",\"sha256\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\"}"
        val T0 = Instant.parse("2026-10-18T12:00:00Z").toEpochMilli()
        val DIA_MS = TimeUnit.HOURS.toMillis(24)
    }
}
