package com.avoqado.pos.onlineorders

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.domain.AccionResultado
import com.avoqado.pos.onlineorders.domain.ConteosPorPagar
import com.avoqado.pos.onlineorders.domain.CursoresPorPagar
import com.avoqado.pos.onlineorders.domain.Inbox
import com.avoqado.pos.onlineorders.domain.ListaPorPagar
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.presentation.OnlineOrdersViewModel
import com.avoqado.pos.onlineorders.presentation.TextosPedidosEnLinea
import com.avoqado.pos.onlineorders.presentation.filtrarFolios
import com.avoqado.pos.onlineorders.presentation.formatoPesos
import com.avoqado.pos.onlineorders.presentation.hayAbono
import com.avoqado.pos.onlineorders.presentation.numeroDeSeccion
import com.avoqado.pos.onlineorders.presentation.numeroDelMenu
import com.avoqado.pos.onlineorders.presentation.textoDeVencimiento
import com.avoqado.pos.onlineorders.presentation.yaPagadoCents
import io.mockk.coVerify
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** «Por pagar» / «Por entregar» (tienda híbrida, etapa 1) contra `GET online-orders/to-pay`, con MockWebServer. */
class PorPagarTest {

    private val server = MockWebServer()
    private val secureStorage = mockk<SecureStorage>()
    private val guardado = mutableMapOf<String, String>()
    private val cache = mockk<PayloadCache>().also { c ->
        coEvery { c.save(any(), any(), any()) } answers { guardado["${firstArg<String>()}|${secondArg<String>()}"] = thirdArg() }
        coEvery { c.load(any(), any()) } answers { guardado["${firstArg<String>()}|${secondArg<String>()}"]?.let { PayloadCache.Cached(it, 0L) } }
    }
    private lateinit var repo: OnlineOrdersRepository

    @Before
    fun arrancar() {
        server.start()
        System.setProperty("avoqado.test.baseUrl", server.url("/api/v1").toString().trimEnd('/'))
        every { secureStorage.venueId } returns "v1"
        every { secureStorage.accessToken } returns "tok"
        repo = OnlineOrdersRepository(secureStorage, OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).retryOnConnectionFailure(false).build(), cache)
    }

    @After
    fun apagar() {
        System.clearProperty("avoqado.test.baseUrl")
        runCatching { server.shutdown() }
    }

    private fun folio(id: String = "o1", number: String = "W7KQ2", status: String = "PENDING_ACCEPTANCE", total: String = "186.10",
                      remaining: String = "66.10", pago: String = "PARTIAL") =
        """{"orderId":"$id","onlineOrderId":"oo-$id","orderNumber":"$number","status":"$status","paymentMode":"PAY_AT_PICKUP",
        "customerName":"María","customerPhone":null,"note":null,"createdAt":"2026-11-06T18:00:00.000Z",
        "acceptDeadlineAt":"2026-11-06T18:30:00.000Z","estimatedReadyAt":null,"total":$total,"remaining":$remaining,"paymentStatus":"$pago",
        "cardStatus":null,"card":null,"rejectReason":null,"requiresAcceptance":false,"channel":"QR","needsPrint":false,
        "items":[{"productId":"p1","categoryId":"c1","name":"Flautas","quantity":2,"note":null,"modifiers":[{"name":"Salsa verde","quantity":1}]}]}"""

    private fun cuerpo(porPagar: String = folio(), porEntregar: String = "", cursorPagar: String? = null, cursorEntregar: String? = null) =
        """{"success":true,"data":{"serverTime":"2026-11-06T18:10:00.000Z","enabled":true,"requiresAcceptance":false,"showTile":true,
        "pollSeconds":30,"counts":{"toPay":2,"toDeliver":1},"toPay":[$porPagar],"toDeliver":[$porEntregar],
        "nextCursors":{"toPay":${cursorPagar?.let { "\"$it\"" } ?: "null"},"toDeliver":${cursorEntregar?.let { "\"$it\"" } ?: "null"}},
        "store":{"status":"OPEN","reason":null,"pausedUntil":null,"manualPause":false}}}"""

    @Test
    fun `porPagar parsea folios con saldo en centavos exactos`() = runTest {
        server.enqueue(MockResponse().setBody(cuerpo(porEntregar = folio("o2", "WAB23", "ACCEPTED", "50", "0", "PAID"))))
        val lista = repo.porPagar(conLatido = false).getOrThrow()
        val f = lista.porPagar.single()
        assertEquals("W7KQ2", f.orderNumber); assertEquals("o1", f.orderId); assertEquals("PARTIAL", f.paymentStatus)
        assertEquals(18610L, f.totalCents); assertEquals(6610L, f.remainingCents)
        assertEquals(false, f.requiresAcceptance); assertEquals("QR", f.channel)
        assertEquals("María", f.customerName); assertNotNull(f.acceptDeadlineAt); assertEquals("Flautas", f.items.single().name)
        val e = lista.porEntregar.single()
        assertEquals(5000L, e.totalCents); assertEquals(0L, e.remainingCents)
        assertEquals(2, lista.counts.toPay); assertEquals(1, lista.counts.toDeliver)
        assertTrue(lista.disponible); assertTrue(lista.showTile); assertEquals(30, lista.pollSeconds)
        assertNull(lista.nextCursors.toPay); assertNull(lista.nextCursors.toDeliver)
        assertNull(lista.guardadoHaceMin)
        assertEquals("/api/v1/mobile/venues/v1/online-orders/to-pay", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
    }

    @Test
    fun `porPagar no pierde un centavo con cantidades de punto flotante dificiles`() = runTest {
        server.enqueue(MockResponse().setBody(cuerpo(folio(total = "1.15", remaining = "0.29"))))
        val f = repo.porPagar(conLatido = false).getOrThrow().porPagar.single()
        assertEquals(115L, f.totalCents); assertEquals(29L, f.remainingCents)
    }

    @Test
    fun `403 deja la seccion no disponible sin error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"success":false,"error":"Forbidden"}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"success":false,"error":"Not found"}"""))
        for (i in 0..1) {
            val lista = repo.porPagar(conLatido = true).getOrThrow()
            assertFalse(lista.disponible); assertFalse(lista.showTile)
            assertTrue(lista.porPagar.isEmpty()); assertTrue(lista.porEntregar.isEmpty())
        }
    }

    @Test
    fun `con conLatido manda x-online-orders-to-pay 1 y sin el no`() = runTest {
        server.enqueue(MockResponse().setBody(cuerpo()))
        server.enqueue(MockResponse().setBody(cuerpo()))
        repo.porPagar(conLatido = true).getOrThrow()
        repo.porPagar(conLatido = false).getOrThrow()
        assertEquals("1", server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("x-online-orders-to-pay"))
        assertNull(server.takeRequest(1, TimeUnit.SECONDS)!!.getHeader("x-online-orders-to-pay"))
    }

    @Test
    fun `porPagar manda seccion y cursor y no pisa lo guardado con otra pagina`() = runTest {
        server.enqueue(MockResponse().setBody(cuerpo(folio("o1"), cursorPagar = "c1")))
        server.enqueue(MockResponse().setBody(cuerpo(folio("o7"))))
        val primera = repo.porPagar(conLatido = false).getOrThrow()
        assertEquals("c1", primera.nextCursors.toPay)
        repo.porPagar(conLatido = false, seccion = "toPay", cursor = primera.nextCursors.toPay).getOrThrow()
        server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("/api/v1/mobile/venues/v1/online-orders/to-pay?section=toPay&cursor=c1", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        assertEquals(listOf("o1"), repo.porPagarGuardado()!!.porPagar.map { it.orderId })
    }

    @Test
    fun `porPagar guarda la primera pagina y sin red se lee guardada con su edad`() = runTest {
        assertNull(repo.porPagarGuardado())
        server.enqueue(MockResponse().setBody(cuerpo()))
        repo.porPagar(conLatido = false).getOrThrow()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertTrue(repo.porPagar(conLatido = false).isFailure)
        val viejo = repo.porPagarGuardado()!!
        assertEquals(6610L, viejo.porPagar.single().remainingCents)
        assertNotNull(viejo.guardadoHaceMin)
    }

    @Test
    fun `un folio dañado se salta y el resto sobrevive`() = runTest {
        val roto = folio("o8").replace("\"orderId\":\"o8\",", "")
        server.enqueue(MockResponse().setBody(cuerpo(porPagar = folio("o1") + "," + roto)))
        assertEquals(listOf("o1"), repo.porPagar(conLatido = false).getOrThrow().porPagar.map { it.orderId })
    }
}

/**
 * Task A2: la pantalla «Pedidos en línea» con las secciones «Por pagar» y «Por entregar» (ViewModel y lógica pura, sin red).
 * Cobrar y cancelar un folio son de A3: aquí sólo la lista, «Listo»/«Entregado», el buscador, «Cargar más» y los números.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PorPagarBandejaTest {

    private val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()
    private val repo = mockk<OnlineOrdersRepository>()

    private data class Llamada(val conLatido: Boolean, val seccion: String?, val cursor: String?)

    private val llamadas = mutableListOf<Llamada>()
    private var respuesta: Result<ListaPorPagar> = Result.success(lista())
    private var respuestasPorCursor = mutableMapOf<String?, Result<ListaPorPagar>>()
    private var guardada: ListaPorPagar? = null
    private var puedeCobrar = true
    private var reloj = 1_000_000L

    @Before fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        coEvery { repo.inbox(any(), any(), any()) } returns Result.success(inbox())
    }

    @After fun tearDown() { kotlinx.coroutines.Dispatchers.resetMain() }

    private fun folio(
        id: String, numero: String = "W${id.uppercase()}AAA".take(5), nombre: String? = "Ana", status: OnlineOrderStatus = OnlineOrderStatus.PENDING_ACCEPTANCE,
        total: Long = 13400, falta: Long = 13400, pago: String = "PENDING", requiere: Boolean? = false,
        vence: String? = "2026-11-06T18:30:00Z",
    ) = OnlineOrder(
        orderId = id, onlineOrderId = "oo-$id", orderNumber = numero, status = status, paymentMode = "PAY_AT_PICKUP", paymentStatus = pago,
        customerName = nombre, customerPhone = null, note = null, createdAt = Instant.parse("2026-11-06T18:00:00Z"),
        acceptDeadlineAt = vence?.let { Instant.parse(it) }, estimatedReadyAt = null, totalCents = total, remainingCents = falta,
        needsPrint = false, items = emptyList(), requiresAcceptance = requiere, channel = "QR",
    )

    private fun lista(
        porPagar: List<OnlineOrder> = emptyList(), porEntregar: List<OnlineOrder> = emptyList(),
        toPay: Int = porPagar.size, toDeliver: Int = porEntregar.size,
        cursorPagar: String? = null, cursorEntregar: String? = null, disponible: Boolean = true, hace: Long? = null,
    ) = ListaPorPagar(disponible, true, 30, ConteosPorPagar(toPay, toDeliver), porPagar, porEntregar, CursoresPorPagar(cursorPagar, cursorEntregar), hace)

    private fun inbox(vararg pedidos: OnlineOrder) = Inbox(
        Instant.parse("2026-11-06T18:00:00Z"), 0, pedidos.size, 0, pedidos.toList(), storeOff = false, truncated = false, nextCursor = null,
    )

    private fun armar() = OnlineOrdersViewModel(
        repositorio = repo,
        permisoActualizar = { true },
        permisoCancelar = { true },
        tienePlan = { true },
        hayPedidosAbiertos = { true },
        ventanaALaVista = kotlinx.coroutines.flow.MutableStateFlow(true),
        permisoCobrar = { puedeCobrar },
        leerPorPagar = { latido, seccion, cursor ->
            llamadas += Llamada(latido, seccion, cursor)
            if (cursor != null) respuestasPorCursor[cursor] ?: Result.failure(IOException("sin red")) else respuesta
        },
        foliosGuardados = { guardada },
        ahoraMs = { reloj },
    )

    // MARK: - Por entregar y por pagar

    @Test
    fun `Por entregar lists paid folios and Entregado or Listo take them out`() = runTest(dispatcher) {
        val leo = folio("e1", "W8M2C", "Leo", OnlineOrderStatus.ACCEPTED, 6900, 0, "PAID")
        respuesta = Result.success(lista(porPagar = listOf(folio("p1")), porEntregar = listOf(leo)))
        coEvery { repo.delivered("e1") } returns AccionResultado.Hecho
        coEvery { repo.ready("e1") } returns AccionResultado.Hecho
        val vm = armar()
        vm.refrescar()
        assertEquals(listOf("e1"), vm.porPagar.value!!.porEntregar.map { it.orderId })

        respuesta = Result.success(lista(porPagar = listOf(folio("p1"))))
        vm.entregarFolio(leo)
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.delivered("e1") }
        assertTrue(vm.porPagar.value!!.porEntregar.isEmpty())
        assertEquals("W8M2C entregado a Leo", vm.aviso.value)

        vm.marcarListo("e1")
        advanceUntilIdle()
        coVerify(exactly = 1) { repo.ready("e1") }
    }

    @Test
    fun `the delivered notice drops the name when the folio has none`() {
        assertEquals("W8M2C entregado", TextosPedidosEnLinea.folioEntregado("W8M2C", null))
        assertEquals("W8M2C entregado", TextosPedidosEnLinea.folioEntregado("W8M2C", "  "))
        assertEquals("W8M2C entregado a Leo", TextosPedidosEnLinea.folioEntregado("W8M2C", "Leo"))
    }

    @Test
    fun `a folio does not repeat in En preparacion`() = runTest(dispatcher) {
        val folioPagado = folio("f1", status = OnlineOrderStatus.ACCEPTED, pago = "PAID", falta = 0, requiere = false)
        val delEquipo = folio("t1", status = OnlineOrderStatus.ACCEPTED, requiere = true)
        val viejo = folio("v1", status = OnlineOrderStatus.ACCEPTED, requiere = null)
        coEvery { repo.inbox(any(), any(), any()) } returns Result.success(inbox(folioPagado, delEquipo, viejo))
        val vm = armar()
        vm.refrescar()
        assertEquals(listOf("t1", "v1"), vm.secciones.value.enPreparacion.map { it.orderId })
    }

    @Test
    fun `cold start without network shows the saved folios`() = runTest(dispatcher) {
        guardada = lista(porPagar = listOf(folio("p1")), hace = 4)
        respuesta = Result.failure(IOException("sin red"))
        val vm = armar()
        vm.descubrirPorPagar()
        val mostrada = vm.porPagar.value!!
        assertEquals(listOf("p1"), mostrada.porPagar.map { it.orderId })
        assertEquals(4L, mostrada.guardadoHaceMin)
        assertTrue("la lista sale de lo guardado: aviso de sin conexión", vm.sinConexion.value)
        assertFalse(vm.errorFolios.value)
    }

    @Test
    fun `a failed read never wipes the list and says how old it is`() = runTest(dispatcher) {
        respuesta = Result.success(lista(porPagar = listOf(folio("p1"))))
        val vm = armar()
        vm.refrescar()
        assertNull(vm.porPagar.value!!.guardadoHaceMin)

        reloj += 3 * 60_000
        respuesta = Result.failure(IOException("sin red"))
        vm.refrescar()
        assertEquals(listOf("p1"), vm.porPagar.value!!.porPagar.map { it.orderId })
        assertEquals(3L, vm.porPagar.value!!.guardadoHaceMin)
        assertTrue(vm.sinConexion.value)

        respuesta = Result.failure(IllegalStateException("JSON roto"))
        vm.refrescar()
        assertEquals("una lectura rota tampoco borra", listOf("p1"), vm.porPagar.value!!.porPagar.map { it.orderId })
        assertTrue(vm.noSePudoLeer.value)
    }

    @Test
    fun `without any list a broken read shows the error with Reintentar and a good one clears it`() = runTest(dispatcher) {
        respuesta = Result.failure(IllegalStateException("500"))
        val vm = armar()
        vm.descubrirPorPagar()
        assertNull(vm.porPagar.value)
        assertTrue(vm.errorFolios.value)

        respuesta = Result.success(lista(porPagar = listOf(folio("p1"))))
        vm.reintentarFolios()
        advanceUntilIdle()
        assertFalse(vm.errorFolios.value)
        assertEquals(1, vm.porPagar.value!!.porPagar.size)
    }

    @Test
    fun `the server saying not available hides the section even with a saved list`() = runTest(dispatcher) {
        guardada = lista(porPagar = listOf(folio("p1")), hace = 2)
        respuesta = Result.success(lista(disponible = false))
        val vm = armar()
        vm.descubrirPorPagar()
        assertFalse(vm.porPagar.value!!.disponible)
        assertTrue(vm.porPagar.value!!.porPagar.isEmpty())
    }

    @Test
    fun `the offline notice is a single text`() {
        assertEquals(
            "Sin conexión: no llegan pedidos nuevos. El efectivo se guarda en el aparato y se sube solo. " +
                "«Cancelar», «Listo» y «Entregado» vuelven con la conexión.",
            TextosPedidosEnLinea.SIN_CONEXION_FOLIOS,
        )
        assertEquals("(de hace 4 min)", TextosPedidosEnLinea.deHaceMin(4))
        assertEquals("(de hace un momento)", TextosPedidosEnLinea.deHaceMin(0))
    }

    // MARK: - Cargar más

    @Test
    fun `with 101 por entregar Cargar mas brings the 101st with nextCursors toDeliver`() = runTest(dispatcher) {
        val cien = (1..100).map { folio("e$it", "W${"%04d".format(it).replace('0', 'A')}", status = OnlineOrderStatus.ACCEPTED, pago = "PAID", falta = 0) }
        respuesta = Result.success(lista(porEntregar = cien, toDeliver = 101, cursorEntregar = "c1"))
        respuestasPorCursor["c1"] = Result.success(lista(porEntregar = listOf(folio("e101", "WZZZZ", status = OnlineOrderStatus.ACCEPTED, pago = "PAID", falta = 0)), toDeliver = 101))
        val vm = armar()
        vm.refrescar()
        assertEquals(100, vm.porPagar.value!!.porEntregar.size)

        vm.cargarMasFolios(OnlineOrdersViewModel.SECCION_POR_ENTREGAR)
        advanceUntilIdle()

        assertTrue(llamadas.contains(Llamada(false, "toDeliver", "c1")))
        assertEquals(101, vm.porPagar.value!!.porEntregar.size)
        assertNull("ya no hay más", vm.porPagar.value!!.nextCursors.toDeliver)

        // La lectura del siguiente compás vuelve a recorrer la página extra: no se tira.
        vm.refrescar()
        assertEquals(101, vm.porPagar.value!!.porEntregar.size)
    }

    @Test
    fun `searching the 101st folio offers Cargar mas and finds it after loading`() = runTest(dispatcher) {
        val cien = (1..100).map { folio("e$it", "W${"%04d".format(it).replace('0', 'A')}", status = OnlineOrderStatus.ACCEPTED, pago = "PAID", falta = 0) }
        respuesta = Result.success(lista(porEntregar = cien, toDeliver = 101, cursorEntregar = "c1"))
        respuestasPorCursor["c1"] = Result.success(lista(porEntregar = listOf(folio("e101", "WZZZZ", nombre = "Zoe", status = OnlineOrderStatus.ACCEPTED, pago = "PAID", falta = 0)), toDeliver = 101))
        val vm = armar()
        vm.refrescar()
        val antes = vm.porPagar.value!!
        assertTrue("no está entre los cargados", filtrarFolios(antes.porEntregar, "zzzz").isEmpty())
        assertNotNull("pero «Cargar más» sigue disponible: la pantalla no puede decir «no encontramos»", antes.nextCursors.toDeliver)
        assertEquals("Sin resultados entre los 100 cargados", TextosPedidosEnLinea.sinResultadosEntre(100))

        vm.cargarMasFolios(OnlineOrdersViewModel.SECCION_POR_ENTREGAR)
        advanceUntilIdle()
        assertEquals(listOf("e101"), filtrarFolios(vm.porPagar.value!!.porEntregar, "zzzz").map { it.orderId })
    }

    @Test
    fun `an offline read is never also a read error and reading is announced until the first answer`() = runTest(dispatcher) {
        respuesta = Result.failure(IllegalStateException("500"))
        val vm = armar()
        assertTrue("antes de la primera lectura se anuncia", vm.leyendoFolios.value)
        vm.descubrirPorPagar()
        assertTrue(vm.errorFolios.value)
        assertFalse(vm.leyendoFolios.value)

        respuesta = Result.failure(IOException("sin red"))
        vm.reintentarFolios()
        advanceUntilIdle()
        assertFalse("sin red no es error de lectura", vm.errorFolios.value)
        assertTrue(vm.sinConexion.value)
    }

    // MARK: - Latido

    @Test
    fun `the heartbeat only goes with payments create and a visible window`() = runTest(dispatcher) {
        val vm = armar()
        puedeCobrar = true
        vm.refrescar(comoBandeja = true)
        puedeCobrar = false
        vm.refrescar(comoBandeja = true)
        puedeCobrar = true
        vm.refrescar(comoBandeja = false)
        assertEquals(listOf(true, false, false), llamadas.map { it.conLatido })
    }

    // MARK: - Números

    @Test
    fun `the menu number adds por aceptar and por pagar`() {
        assertEquals(5, numeroDelMenu(porAceptar = 2, porPagar = 3))
        assertEquals(0, numeroDelMenu(0, 0))
    }

    @Test
    fun `a number never contradicts the list it heads`() {
        // El servidor cuenta 3 pero un folio dañado se saltó: la lista tiene 2 y no hay más páginas ⇒ el número es 2.
        assertEquals(2, numeroDeSeccion(conteo = 3, mostrados = 2, hayMas = false))
        // Con más páginas por traer el número es el exacto del servidor.
        assertEquals(101, numeroDeSeccion(conteo = 101, mostrados = 100, hayMas = true))
        assertEquals(100, numeroDeSeccion(conteo = 99, mostrados = 100, hayMas = true))
    }

    // MARK: - Buscador

    @Test
    fun `search by folio ignores case the W and dashes and needs 3 characters`() {
        val a = folio("a", "W7KQ2", "Ana")
        val b = folio("b", "W9D5F", "Pau")
        val todos = listOf(a, b)
        assertEquals(listOf("a"), filtrarFolios(todos, "7kq").map { it.orderId })
        assertEquals(listOf("a"), filtrarFolios(todos, "W7KQ2").map { it.orderId })
        assertEquals(listOf("a"), filtrarFolios(todos, "w-7k-q").map { it.orderId })
        assertEquals("con menos de 3 caracteres no filtra", todos, filtrarFolios(todos, "7k"))
        assertEquals(todos, filtrarFolios(todos, "  "))
        assertTrue(filtrarFolios(todos, "zzz").isEmpty())
    }

    @Test
    fun `search by name ignores accents and case`() {
        val a = folio("a", "W7KQ2", "María José")
        val b = folio("b", "W9D5F", "Pau")
        assertEquals(listOf("a"), filtrarFolios(listOf(a, b), "MARIA").map { it.orderId })
        assertEquals(listOf("a"), filtrarFolios(listOf(a, b), "josé").map { it.orderId })
    }

    // MARK: - Vencimiento y dinero

    @Test
    fun `expiry says minutes online and a fixed hour offline and amber from 5 minutes`() {
        val ahora = Instant.parse("2026-11-06T18:00:00Z")
        val en3 = folio("a", vence = "2026-11-06T18:03:00Z")
        val en22 = folio("b", vence = "2026-11-06T18:22:00Z")
        assertEquals("Vence en 3 min", textoDeVencimiento(en3, ahora, sinConexion = false)!!.texto)
        assertTrue(textoDeVencimiento(en3, ahora, sinConexion = false)!!.pronto)
        assertEquals("Vence en 22 min", textoDeVencimiento(en22, ahora, sinConexion = false)!!.texto)
        assertFalse(textoDeVencimiento(en22, ahora, sinConexion = false)!!.pronto)
        assertTrue("justo 5 min ya es ámbar", textoDeVencimiento(folio("c", vence = "2026-11-06T18:05:00Z"), ahora, false)!!.pronto)
        // 4 min 20 s se dice 5 min (se redondea hacia arriba, nunca promete de más).
        assertEquals("Vence en 5 min", textoDeVencimiento(folio("d", vence = "2026-11-06T18:04:20Z"), ahora, false)!!.texto)
        assertTrue(textoDeVencimiento(en22, ahora, sinConexion = true)!!.texto.startsWith("Vence a las "))
        assertFalse(textoDeVencimiento(en22, ahora, sinConexion = true)!!.pronto)
        assertNull(textoDeVencimiento(folio("e", vence = null), ahora, false))
    }

    @Test
    fun `an expired folio says it expired and never counts down`() {
        val ahora = Instant.parse("2026-11-06T18:40:00Z")
        val v = textoDeVencimiento(folio("a", vence = "2026-11-06T18:30:00Z"), ahora, sinConexion = false)!!
        assertTrue(v.texto.startsWith("Venció a las "))
        assertFalse("nunca rojo ni ámbar", v.pronto)
    }

    @Test
    fun `partial payment says what was paid and what is missing`() {
        val abono = folio("a", total = 18500, falta = 8500, pago = "PARTIAL")
        assertTrue(hayAbono(abono))
        assertEquals("Ya pagó $100.00 de $185.00", TextosPedidosEnLinea.yaPago(formatoPesos(yaPagadoCents(abono)), formatoPesos(abono.totalCents)))
        assertEquals("Falta $85.00", TextosPedidosEnLinea.falta(formatoPesos(abono.remainingCents)))
        assertFalse(hayAbono(folio("b", total = 13400, falta = 13400)))
    }
}
