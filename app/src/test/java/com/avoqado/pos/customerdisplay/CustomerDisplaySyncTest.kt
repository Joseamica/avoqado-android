package com.avoqado.pos.customerdisplay

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.spyk
import io.mockk.verify
import java.io.File
import java.io.IOException
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
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
    // Sin decir otra cosa, el acuse no llega (503): se conserva y las pruebas de la Task 2.4 ven su pendiente intacto.
    @Volatile private var ackResponse: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(503) }
    private val acuses = Collections.synchronizedList(mutableListOf<CustomerDisplayAckBody>())
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
                } else if (request.path!!.endsWith("/customer-display/ack")) {
                    acuses += Json.decodeFromString<CustomerDisplayAckBody>(request.body.readUtf8())
                    ackResponse(request)
                } else MockResponse().setResponseCode(404)
        }
        server.start()
        every { images.cachedFile(any()) } answers { disco[url(firstArg())] }
        every { images.revalidationDue(any()) } answers {
            val ref = firstArg<LogoRef>()
            ref is LogoRef.Venue && versionValidada[ref.url].let { it == null || it.first != ref.version || reloj - it.second >= DIA_MS }
        }
        coEvery { images.ensure(any(), any()) } coAnswers { bajar(firstArg()) }
        every { images.prune(any()) } just Runs
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
    fun `volver a la ficha anterior limpia el aviso de esquema`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        fichaResponse = { ficha(HUELLA_B, schema = 2) }
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_B))

        // El server regresa a A (la ficha que se conservó): no hay nada que bajar, pero el aviso de B ya no es verdad.
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        assertEquals(2, fichaRequests.get())
        val guardado = store().load()
        val manifiesto = guardado.manifests.getValue(LLAVE)
        assertNull(manifiesto.unsupportedFingerprint)
        assertEquals(HUELLA_A, manifiesto.ficha!!.fingerprint)
        assertEquals(HUELLA_A, manifiesto.pendingAck!!.fingerprint)
        assertFalse(manifiesto.pendingAck!!.unsupportedSchema)
        assertEquals(3L, manifiesto.pendingAck!!.seq)
        assertEquals(4L, guardado.nextSeq)
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

        val acuse = sync().buildAck(manifiesto, HUELLA_A, unsupported = false, images = Triple(1, 0, 0), installationId = "inst-1")

        assertEquals("2026-10-18T12:00:00Z", acuse.storedAt)
        assertEquals("2026-10-18T12:05:00Z", acuse.shownAt)
        assertEquals("presentation", acuse.surface)
        assertEquals(1, acuse.imagesReady)
        assertEquals("inst-1", acuse.installationId)
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
        val poll = async(Dispatchers.IO) { sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_B)) }
        esperar { store().load().manifests[LLAVE]?.ficha?.fingerprint == HUELLA_B }

        soltar.countDown()
        poll.await()
        Thread.sleep(300)

        assertEquals(HUELLA_B, otroEstado.design.value!!.ficha.fingerprint)
    }

    @Test
    fun `desligar otra sucursal no borra el diseno de la actual`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        store().update { it.copy(manifests = it.manifests + ("test|otra|t9" to DesignManifest(storedAt = "x"))) }

        sync.onDeviceUnbound("otra")
        Thread.sleep(100)

        assertEquals(HUELLA_A, state.design.value!!.ficha.fingerprint)
        assertEquals(setOf(LLAVE), store().load().manifests.keys)
    }

    @Test
    fun `un poll viejo no cancela la descarga de la sesion nueva`() = runBlocking {
        // venue-1 ya tiene una ficha con logo que no está en disco.
        coEvery { images.ensure(any(), any()) } returns ImageStatus.PENDING
        fichaResponse = { ficha(HUELLA_A, logo = LOGO_ASSET_JSON) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        coVerify(timeout = 5_000, exactly = 1) { images.ensure(any(), any()) }
        Thread.sleep(150)
        val retenida = CompletableDeferred<Unit>()
        val cancelada = AtomicBoolean(false)
        coEvery { images.ensure(LogoRef.Asset(OTRO_LOGO_URL, SHA_OTRO), any()) } coAnswers {
            try {
                retenida.await()
            } catch (e: CancellationException) {
                cancelada.set(true)
                throw e
            }
            bajar(firstArg())
        }
        val soltarViejo = CountDownLatch(1)
        fichaResponse = { pedido ->
            if (pedido.path!!.contains("/venue-2/")) ficha(HUELLA_C, logo = OTRO_LOGO_JSON)
            else { soltarViejo.await(5, TimeUnit.SECONDS); ficha(HUELLA_B, logo = LOGO_ASSET_JSON) }
        }
        // Un poll de venue-1 se queda esperando su ficha...
        val viejo = async(Dispatchers.IO) { sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_B)) }
        esperar { fichaRequests.get() == 2 }
        // ...cambia la sesión y el poll de venue-2 lanza la descarga de SU logo.
        sesion = "venue-2"
        sync.onSessionChanged()
        sync.afterPoll("venue-2", TERMINAL, senales(HUELLA_C))
        coVerify(timeout = 5_000, exactly = 1) { images.ensure(LogoRef.Asset(OTRO_LOGO_URL, SHA_OTRO), any()) }

        soltarViejo.countDown()
        viejo.await()
        Thread.sleep(200)

        assertFalse(cancelada.get())
        retenida.complete(Unit)
        esperar { state.design.value?.logoFile != null }
        assertEquals(HUELLA_C, state.design.value!!.ficha.fingerprint)
    }

    @Test
    fun `un poll sin cambios no reescribe state json`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        val archivo = File(carpeta.root, "customer-display/state.json")
        assertTrue(archivo.setLastModified(1_000_000L))

        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        assertEquals(1_000_000L, archivo.lastModified())
    }

    @Test
    fun `ficha schema 1 con huella invalida no se pide en cada poll`() = runBlocking {
        fichaResponse = { ficha("no-es-una-huella") }
        val sync = sync()

        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        assertEquals(1, fichaRequests.get())
        val manifiesto = store().load().manifests.getValue(LLAVE)
        assertEquals(HUELLA_A, manifiesto.unsupportedFingerprint)
        assertNull(state.design.value)
    }

    // ── el acuse: tabla de respuestas ──

    @Test
    fun `aceptado vigente, sin current, STALE_SEQ, 404 y otro 4xx cierran el acuse`() {
        assertEquals(AckAction.Close, ackActionFor(respuesta(accepted = true, current = true), HUELLA_A))
        assertEquals(AckAction.Close, ackActionFor(respuesta(accepted = true), HUELLA_A))
        assertEquals(AckAction.Close, ackActionFor(respuesta(accepted = false, reason = "STALE_SEQ"), HUELLA_A))
        assertEquals(AckAction.Close, ackActionFor(DisplayModeRemoteOutcome.Rejected(404, "DEVICE_NOT_FOUND", null), HUELLA_A))
        assertEquals(AckAction.Close, ackActionFor(DisplayModeRemoteOutcome.Rejected(400, "VALIDATION", null), HUELLA_A))
        assertEquals(AckAction.Close, ackActionFor(DisplayModeRemoteOutcome.Rejected(409, null, null), null))
    }

    @Test
    fun `aceptado no vigente con la huella del manifiesto se acusa otra vez`() {
        assertEquals(
            AckAction.CloseAndAckAgain(HUELLA_A),
            ackActionFor(respuesta(accepted = true, current = false, currentFingerprint = HUELLA_A), HUELLA_A),
        )
    }

    @Test
    fun `aceptado no vigente con otra huella pide la ficha`() {
        assertEquals(AckAction.CloseAndFetch, ackActionFor(respuesta(accepted = true, current = false, currentFingerprint = HUELLA_B), HUELLA_A))
        assertEquals(AckAction.CloseAndFetch, ackActionFor(respuesta(accepted = true, current = false, currentFingerprint = HUELLA_B), null))
        assertEquals(AckAction.CloseAndFetch, ackActionFor(respuesta(accepted = true, current = false), null))
    }

    @Test
    fun `OTHER_INSTALLATION cierra y pide reportar`() {
        assertEquals(AckAction.CloseAndReport, ackActionFor(respuesta(accepted = false, reason = "OTHER_INSTALLATION"), HUELLA_A))
    }

    @Test
    fun `sin red, 408, 429, 5xx, ACK_BUSY y sesion invalida conservan el acuse`() {
        listOf(null, 408, 429, 500, 503).forEach { status ->
            assertEquals(AckAction.Keep, ackActionFor(DisplayModeRemoteOutcome.Retryable(status), HUELLA_A))
        }
        assertEquals(AckAction.Keep, ackActionFor(DisplayModeRemoteOutcome.SessionInvalid, HUELLA_A))
    }

    // ── el acuse: mandar y obedecer ──

    @Test
    fun `acuse pendiente sobrevive a reiniciar y se manda con el mismo seq`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        sync().afterPoll(VENUE, TERMINAL, senales(HUELLA_A))   // sin red para el acuse (503)
        assertEquals(1L, store().load().manifests.getValue(LLAVE).pendingAck!!.seq)

        ackResponse = { aceptado(current = true) }
        sync(estado = CustomerDisplayState()).afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        assertEquals(listOf(1L, 1L), acuses.map { it.seq })
        assertEquals(acuses[0], acuses[1])
        val guardado = store().load()
        assertNull(guardado.manifests.getValue(LLAVE).pendingAck)
        assertEquals(2L, guardado.nextSeq)
    }

    @Test
    fun `cerrar no borra un acuse mas nuevo`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        ackResponse = {
            // Mientras el acuse 1 viaja, se encola el 2 (p. ej. el logo terminó de bajar).
            store().update { s ->
                val m = s.manifests.getValue(LLAVE)
                s.copy(nextSeq = s.nextSeq + 1, manifests = s.manifests + (LLAVE to m.copy(pendingAck = m.pendingAck!!.copy(seq = s.nextSeq, imagesReady = 1))))
            }
            aceptado(current = true)
        }
        val sync = sync()

        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        assertEquals(listOf(1L), acuses.map { it.seq })
        assertEquals(2L, store().load().manifests.getValue(LLAVE).pendingAck!!.seq)
    }

    @Test
    fun `Keep conserva el pendiente para el siguiente poll`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        val pendiente = store().load().manifests.getValue(LLAVE).pendingAck!!

        assertEquals(1, acuses.size)
        assertEquals(pendiente, store().load().manifests.getValue(LLAVE).pendingAck)

        ackResponse = { aceptado(current = true) }
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        assertEquals(listOf(pendiente, pendiente), acuses.toList())
        assertNull(store().load().manifests.getValue(LLAVE).pendingAck)
    }

    @Test
    fun `aceptado no vigente con la misma huella encola un acuse nuevo`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        ackResponse = { aceptado(current = false, currentFingerprint = HUELLA_A) }
        val sync = sync()

        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        val guardado = store().load()
        val acuse = guardado.manifests.getValue(LLAVE).pendingAck!!
        assertEquals(HUELLA_A, acuse.fingerprint)
        assertEquals(2L, acuse.seq)
        assertEquals(3L, guardado.nextSeq)
    }

    @Test
    fun `aceptado no vigente con otra huella baja la ficha en el siguiente poll`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        ackResponse = { aceptado(current = false, currentFingerprint = HUELLA_C) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        assertNull(store().load().manifests.getValue(LLAVE).pendingAck)
        assertEquals(1, fichaRequests.get())

        // El acuse refresca la caché del server; `refetch` sólo cubre una carrera: un poll que aún anuncia A con C vigente.
        fichaResponse = { ficha(HUELLA_C) }
        ackResponse = { aceptado(current = true) }
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        assertEquals(2, fichaRequests.get())
        assertEquals(HUELLA_C, store().load().manifests.getValue(LLAVE).ficha!!.fingerprint)
        assertEquals(HUELLA_C, state.design.value!!.ficha.fingerprint)
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_C))
        assertEquals(2, fichaRequests.get())
    }

    @Test
    fun `refetch con esquema 2 no acusa la ficha conservada como no soportada`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        ackResponse = { aceptado(current = false, currentFingerprint = HUELLA_C) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        // Carrera: el poll aún anuncia A, pero lo que baja es C con un esquema que esta versión no entiende.
        fichaResponse = { ficha(HUELLA_C, schema = 2) }
        ackResponse = { MockResponse().setResponseCode(503) }
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        val manifiesto = store().load().manifests.getValue(LLAVE)
        assertEquals(HUELLA_A, manifiesto.ficha!!.fingerprint)
        assertNotEquals(HUELLA_A, manifiesto.unsupportedFingerprint)
        assertEquals(HUELLA_C, manifiesto.unsupportedFingerprint)
        assertEquals(HUELLA_C, manifiesto.pendingAck!!.fingerprint)
        assertTrue(manifiesto.pendingAck!!.unsupportedSchema)
    }

    @Test
    fun `cambio de sesion mientras viaja el acuse no lo cierra`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        ackResponse = {
            sesion = "venue-2"
            aceptado(current = true)
        }

        sync().afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        assertEquals(listOf(1L), acuses.map { it.seq })
        assertEquals(1L, store().load().manifests.getValue(LLAVE).pendingAck!!.seq)
    }

    @Test
    fun `OTHER_INSTALLATION cierra el acuse y marca el reporte pendiente`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        ackResponse = { MockResponse().setBody("{\"data\":{\"accepted\":false,\"reason\":\"OTHER_INSTALLATION\"}}") }

        sync().afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        val guardado = store().load()
        assertNull(guardado.manifests.getValue(LLAVE).pendingAck)
        assertTrue(guardado.otherInstallationPending)
    }

    // ── el reporte de capacidades (Task 2.6) ──

    @Test
    fun `sin capacidad anunciada no hay diseno en el reporte y con ella va la version 1 e instalacion`() {
        val sync = sync()

        assertNull(sync.designReport(null))
        val instalacion = store().load().installationId
        assertEquals(DesignReport(1, instalacion), sync.designReport(1))
        assertEquals(DesignReport(1, instalacion), sync.designReport(3))
    }

    @Test
    fun `una instalacion que solo existe en memoria no se reporta`() {
        // El archivo no se puede leer (IOException pasajera): load() sigue con una instalación inventada en memoria.
        val noSeLee = File(carpeta.root, "no-se-lee").also { File(it, "state.json").mkdirs() }
        assertNull(sync(almacen = CustomerDisplayDesignStore(noSeLee)).designReport(1))
        // El disco no deja guardar la instalación nueva: la siguiente lectura nacería otra.
        val noSeGuarda = File(carpeta.root, "no-se-guarda").also { it.writeText("") }
        assertNull(sync(almacen = CustomerDisplayDesignStore(noSeGuarda)).designReport(1))
    }

    @Test
    fun `OTHER_INSTALLATION reporta a lo mas una vez por hora`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        ackResponse = { MockResponse().setBody("{\"data\":{\"accepted\":false,\"reason\":\"OTHER_INSTALLATION\"}}") }
        val sync = sync()
        assertFalse(sync.otherInstallationReportDue())
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        assertTrue(sync.otherInstallationReportDue())

        sync.onCapabilitiesReported(VENUE)

        val guardado = store().load()
        assertFalse(guardado.otherInstallationPending)
        assertEquals(T0, guardado.otherInstallationReportAtMs)
        assertFalse(sync.otherInstallationReportDue())
        // El acuse nuevo vuelve a chocar con la otra instalación: dentro de la hora no se reporta otra vez.
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        assertTrue(store().load().otherInstallationPending)
        reloj = T0 + HORA_MS - 1
        assertFalse(sync.otherInstallationReportDue())
        reloj = T0 + HORA_MS
        assertTrue(sync.otherInstallationReportDue())
    }

    @Test
    fun `reloj hacia atras no bloquea el reporte por otra instalacion`() {
        val sync = sync()
        store().update { it.copy(otherInstallationPending = true, otherInstallationReportAtMs = T0) }

        reloj = T0 + 1
        assertFalse(sync.otherInstallationReportDue())
        reloj = T0 - 1
        assertTrue(sync.otherInstallationReportDue())
    }

    @Test
    fun `tras reportar manda un acuse nuevo con otro seq`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        ackResponse = { aceptado(current = true) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        assertNull(store().load().manifests.getValue(LLAVE).pendingAck)

        sync.onCapabilitiesReported(VENUE)

        val guardado = store().load()
        val acuse = guardado.manifests.getValue(LLAVE).pendingAck!!
        assertEquals(2L, acuse.seq)
        assertEquals(HUELLA_A, acuse.fingerprint)
        assertEquals(guardado.installationId, acuse.installationId)
        assertFalse(acuse.unsupportedSchema)
        // Sin otra instalación de por medio, no se guarda la hora.
        assertNull(guardado.otherInstallationReportAtMs)
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        assertEquals(listOf(1L, 2L), acuses.map { it.seq })
        assertNull(store().load().manifests.getValue(LLAVE).pendingAck)
    }

    @Test
    fun `el acuse tras reportar conserva shownAt y surface`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        ackResponse = { aceptado(current = true) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        store().update { s ->
            s.copy(manifests = s.manifests + (LLAVE to s.manifests.getValue(LLAVE).copy(shownAt = MOSTRADO, surface = "presentation")))
        }

        sync.onCapabilitiesReported(VENUE)

        val acuse = store().load().manifests.getValue(LLAVE).pendingAck!!
        assertEquals("2026-10-18T12:00:00Z", acuse.storedAt)
        assertEquals(MOSTRADO, acuse.shownAt)
        assertEquals("presentation", acuse.surface)
    }

    @Test
    fun `reportar no tapa el acuse unsupportedSchema`() = runBlocking {
        fichaResponse = { ficha(HUELLA_A) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        fichaResponse = { ficha(HUELLA_B, schema = 2) }
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_B))

        sync.onCapabilitiesReported(VENUE)

        val guardado = store().load()
        val acuse = guardado.manifests.getValue(LLAVE).pendingAck!!
        assertEquals(HUELLA_B, acuse.fingerprint)
        assertTrue(acuse.unsupportedSchema)
        assertEquals(2L, acuse.seq)
        assertEquals(3L, guardado.nextSeq)
    }

    @Test
    fun `tras reportar vuelve a acusar el esquema no soportado ya entregado`() = runBlocking {
        // El server borra el acuse al adoptar una instalación nueva (reinstalar): «actualiza la app» se tiene que repetir.
        fichaResponse = { ficha(HUELLA_A) }
        ackResponse = { aceptado(current = true) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        fichaResponse = { ficha(HUELLA_B, schema = 2) }
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_B))
        assertNull(store().load().manifests.getValue(LLAVE).pendingAck)   // el «unsupportedSchema» ya se entregó y se cerró

        sync.onCapabilitiesReported(VENUE)

        val acuse = store().load().manifests.getValue(LLAVE).pendingAck!!
        assertEquals(3L, acuse.seq)
        assertEquals(HUELLA_B, acuse.fingerprint)
        assertTrue(acuse.unsupportedSchema)
    }

    @Test
    fun `una descarga vieja no re-ofrece la ficha anterior`() = runBlocking {
        val retenida = CompletableDeferred<Unit>()
        coEvery { images.ensure(any(), any()) } coAnswers { retenida.await(); bajar(firstArg()) }
        val armada = AtomicBoolean(false)
        val leido = CountDownLatch(1)
        val soltar = CountDownLatch(1)
        every { images.cachedFile(any()) } answers {
            val enDisco = disco[url(firstArg())]
            if (armada.getAndSet(false)) {
                leido.countDown()
                soltar.await(5, TimeUnit.SECONDS)
            }
            enDisco
        }
        fichaResponse = { ficha(HUELLA_A, logo = LOGO_ASSET_JSON) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        // La descarga termina y su trabajo, con el manifiesto A ya leído, se queda leyendo el logo (disco lento)...
        armada.set(true)
        retenida.complete(Unit)
        assertTrue(leido.await(5, TimeUnit.SECONDS))
        // ...mientras un poll guarda y ofrece B con el MISMO logo (ya en disco: no cancela esa descarga).
        fichaResponse = { ficha(HUELLA_B, logo = LOGO_ASSET_JSON) }
        val poll = async(Dispatchers.IO) { sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_B)) }
        esperar { store().load().manifests[LLAVE]?.ficha?.fingerprint == HUELLA_B }

        soltar.countDown()
        poll.await()
        Thread.sleep(300)

        assertEquals(HUELLA_B, state.design.value!!.ficha.fingerprint)
        assertEquals(disco[LOGO_URL], state.design.value!!.logoFile)
    }

    @Test
    fun `si falla guardar el acuse del logo el siguiente poll lo encola`() = runBlocking {
        val retenida = CompletableDeferred<Unit>()
        coEvery { images.ensure(any(), any()) } coAnswers { retenida.await(); bajar(firstArg()) }
        val falla = AtomicBoolean(false)
        val espia = spyk(store())
        every { espia.update(any()) } answers {
            if (falla.getAndSet(false)) throw IOException("disco lleno un momento") else callOriginal()
        }
        fichaResponse = { ficha(HUELLA_A, logo = LOGO_ASSET_JSON) }
        val sync = sync(almacen = espia)
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        assertEquals(1, store().load().manifests.getValue(LLAVE).pendingAck!!.imagesPending)
        falla.set(true)   // la escritura del acuse «listo» del trabajo de descarga falla una vez

        retenida.complete(Unit)
        esperar { !falla.get() }
        Thread.sleep(200)
        assertEquals(1, store().load().manifests.getValue(LLAVE).pendingAck!!.imagesPending)

        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        val acuse = store().load().manifests.getValue(LLAVE).pendingAck!!
        assertEquals(1, acuse.imagesReady)
        assertEquals(0, acuse.imagesPending)
        assertEquals(HUELLA_A, acuse.fingerprint)
    }

    @Test
    fun `tras una descarga lista se poda el cache con los logos vigentes`() = runBlocking {
        val otro = LogoRef.Asset(OTRO_LOGO_URL, SHA_OTRO)
        store().update {
            it.copy(
                manifests = it.manifests + (
                    "test|otra|t9" to DesignManifest(
                        ficha = FichaDto(schemaVersion = 1, fingerprint = HUELLA_C, logo = LogoDto("asset", OTRO_LOGO_URL, SHA_OTRO)),
                        imageOrigins = listOf("https://storage.googleapis.com"),
                        storedAt = "x",
                    )
                ),
            )
        }
        fichaResponse = { ficha(HUELLA_A, logo = LOGO_ASSET_JSON) }

        sync().afterPoll(VENUE, TERMINAL, senales(HUELLA_A))

        val propio = LogoRef.Asset(LOGO_URL, LOGO_ASSET_JSON.substringAfter("sha256\":\"").substringBefore("\""))
        verify(timeout = 5_000) { images.prune(match { it.toSet() == setOf(propio, otro) }) }
    }

    @Test
    fun `un poll que publica sin logo despues de que termina la descarga no borra el logo`() = runBlocking {
        val retenida = CompletableDeferred<Unit>()
        coEvery { images.ensure(any(), any()) } coAnswers { retenida.await(); bajar(firstArg()) }
        val armada = AtomicBoolean(false)
        val leido = CountDownLatch(1)
        val soltar = CountDownLatch(1)
        every { images.cachedFile(any()) } answers {
            val enDisco = disco[url(firstArg())]
            if (armada.getAndSet(false)) {
                leido.countDown()
                soltar.await(5, TimeUnit.SECONDS)
            }
            enDisco
        }
        fichaResponse = { ficha(HUELLA_A, logo = LOGO_ASSET_JSON) }
        val sync = sync()
        sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        // Un poll arma su diseño cuando el logo todavía no está (y se queda ahí antes de publicarlo)...
        armada.set(true)
        val poll = async(Dispatchers.IO) { sync.afterPoll(VENUE, TERMINAL, senales(HUELLA_A)) }
        assertTrue(leido.await(5, TimeUnit.SECONDS))
        // ...la descarga termina y deja el logo en disco...
        retenida.complete(Unit)
        esperar { disco[LOGO_URL] != null }
        Thread.sleep(300)

        // ...y el poll publica DESPUÉS lo que había armado sin logo.
        soltar.countDown()
        poll.await()

        esperar { state.design.value?.logoFile == disco[LOGO_URL] }
        Thread.sleep(300)
        assertEquals(disco[LOGO_URL], state.design.value!!.logoFile)
    }

    @Test
    fun `si no se pudo leer el almacen no se poda`() = runBlocking {
        val retenida = CompletableDeferred<Unit>()
        coEvery { images.ensure(any(), any()) } coAnswers { retenida.await(); bajar(firstArg()) }
        fichaResponse = { ficha(HUELLA_A, logo = LOGO_ASSET_JSON) }
        sync().afterPoll(VENUE, TERMINAL, senales(HUELLA_A))
        // Una lectura pasajera fallida justo al podar: state.json se vuelve ilegible (una carpeta en su lugar).
        val real = File(carpeta.root, "customer-display/state.json")
        val aparte = File(carpeta.root, "aparte.json")
        assertTrue(real.renameTo(aparte))
        assertTrue(real.mkdir())

        retenida.complete(Unit)
        coVerify(timeout = 5_000, exactly = 1) { images.ensure(any(), any()) }
        Thread.sleep(300)

        verify(exactly = 0) { images.prune(any()) }
    }

    // ── ayudas ──

    private fun respuesta(accepted: Boolean, current: Boolean? = null, currentFingerprint: String? = null, reason: String? = null) =
        DisplayModeRemoteOutcome.Success(CustomerDisplayAckResponse(accepted, current, currentFingerprint, reason))

    private fun aceptado(current: Boolean, currentFingerprint: String? = null) = MockResponse().setBody(
        "{\"data\":{\"accepted\":true,\"current\":$current" +
            (currentFingerprint?.let { ",\"currentFingerprint\":\"$it\"" } ?: "") + "}}",
    )

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
        val HORA_MS = TimeUnit.HOURS.toMillis(1)
        const val MOSTRADO = "2026-10-18T12:05:00Z"
    }
}
