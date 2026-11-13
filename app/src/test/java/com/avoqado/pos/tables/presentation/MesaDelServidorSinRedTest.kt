package com.avoqado.pos.tables.presentation

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.network.PuertaDeLaFoto
import com.avoqado.pos.core.data.sync.FilaDeLaOrden
import com.avoqado.pos.core.data.sync.FotosDeMesas
import com.avoqado.pos.core.data.sync.SyncAck
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.CobrosLocalesDeLaCuenta
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.EfectivoDelPedido
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tables.data.AddItemsRequest
import com.avoqado.pos.tables.data.AddOrderItemRequest
import com.avoqado.pos.tables.data.AppliedOrderDiscount
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.OrderDetailItem
import com.avoqado.pos.tables.data.OrderDetailPayment
import com.avoqado.pos.tables.data.PreBillBalance
import com.avoqado.pos.tables.data.OrderDetailResponse
import com.avoqado.pos.tables.data.TableRoundDraftStore
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.tables.data.UpdatedOrder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * D5 · B2b (piloto `MESAS_SIN_RED`): una mesa que nació CON internet se cobra en efectivo SIN internet en la tablet que la tomó, con
 * su foto limpia + sus rondas posteriores − su efectivo posterior. VM, sesión, borrador y fotos REALES; la red es un servidor de
 * verdad (MockWebServer) detrás del interceptor REAL de la foto; la cola es una lista con su secuencia, como la lee `SyncOutbox`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MesaDelServidorSinRedTest {
    @get:Rule val main = MainDispatcherRule()
    private val venue = "v-b2b"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val root = java.nio.file.Files.createTempDirectory("b2b").toFile()
    private val dirFotos = java.io.File(root, "fotos")
    // La MISMA carpeta que usa la cola real (`filesDir/table-round-drafts`): marcar resuelta una ronda toca este borrador (D29).
    private val drafts = TableRoundDraftStore(java.io.File(root, "table-round-drafts"))
    private val session = TableSession()
    private val outbox = mockk<SyncOutbox>(relaxed = true)
    private val acks = MutableSharedFlow<SyncAck>(extraBufferCapacity = 4)
    private val storage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns venue }
    private val plan = mockk<PlanManager>()
    private val cobros = mockk<CobrosLocalesDeLaCuenta>()
    private var piloto = true
    private var cobroEnOtraCola = false
    /** D45: el efectivo de la orden en la cola de COBROS de esta tablet (la regla de la bandeja); null = no se pudo leer. */
    private val efectivoDeCobros = mockk<CashPaymentRepository>()
    private var efectivoGuardado: EfectivoDelPedido? = EfectivoDelPedido.NINGUNO
    /** D45: las filas de la orden en esa cola, por su llave y si ya subieron (la firma). */
    private var cobrosGuardados: List<com.avoqado.pos.payment.domain.DineroDeLaOrden> = emptyList()
    private fun abono(subido: Boolean) = listOf(com.avoqado.pos.payment.domain.DineroDeLaOrden("e:abono-50", 5000, esTarjeta = false, confirmado = subido))

    // MARK: - El servidor (la cuenta que conoce) y la red

    private val server = MockWebServer()
    private var conInternet = true
    private var cuenta = OrderDetail("order-F", status = "PENDING", total = 0.0, version = 3)
    private var antesDelGet: (() -> Unit)? = null

    // MARK: - La cola de esta tablet: filas con su secuencia

    private val cola = mutableListOf<FilaDeLaOrden>()
    private var seq = 0L
    private fun estado(id: String, nuevo: String?) {
        val i = cola.indexOfFirst { it.id == id }
        if (i >= 0) { if (nuevo == null) cola.removeAt(i) else cola[i] = cola[i].copy(estado = nuevo) }
    }

    /** Un «proceso» de la app: su almacén de fotos, su cliente HTTP con la puerta y su pantalla de la mesa. */
    private lateinit var fotos: FotosDeMesas
    private lateinit var client: OkHttpClient

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                // Sin internet: la petición no se aplica y nunca vuelve respuesta. (DISCONNECT_AT_START no corta una conexión ya
                // abierta: MockWebServer contestaba 200 vacío y la ronda «se enviaba» sin internet.)
                if (!conInternet) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                val ruta = request.path.orEmpty()
                return when {
                    request.method == "GET" && ruta.endsWith("/orders/order-F") ->
                        MockResponse().setBody(json.encodeToString(OrderDetailResponse.serializer(), OrderDetailResponse(order = cuenta)))
                    request.method == "POST" && ruta.endsWith("/orders/order-F/items") -> {
                        val ronda = json.decodeFromString(AddItemsRequest.serializer(), request.body.readUtf8())
                        val nuevos = ronda.items.mapIndexed { i, it -> OrderDetailItem("i${cuenta.items.size + i}", quantity = it.quantity,
                            total = (it.customUnitPriceCents ?: 0) * it.quantity / 100.0, externalId = it.externalId) }
                        cuenta = cuenta.copy(items = cuenta.items + nuevos, total = cuenta.total + nuevos.sumOf { it.total },
                            version = (cuenta.version ?: 0) + 1)
                        MockResponse().setResponseCode(201).setBody("""{"success":true,"data":{"id":"order-F","version":${cuenta.version}}}""")
                    }
                    else -> MockResponse().setBody("{}")
                }
            }
        }
        server.start()
    }

    @After fun limpiar() { server.shutdown(); root.walkTopDown().forEach { it.setWritable(true) }; root.deleteRecursively() }

    /** Un error de E/S que impide BORRAR la foto (su carpeta de sucursal no deja quitar archivos). */
    private inline fun <T> sinPoderBorrar(bloque: () -> T): T {
        val carpetas = dirFotos.listFiles { f -> f.isDirectory }.orEmpty()
        carpetas.forEach { assertTrue(it.setWritable(false)) }
        try { return bloque() } finally { carpetas.forEach { it.setWritable(true) } }
    }

    private fun url(ruta: String) = server.url("/api/v1/mobile/venues/$venue/$ruta")

    /** Otra pantalla (el plano, el cobro…) escribe por el MISMO cliente principal. */
    private fun escribir(ruta: String, cuerpo: String) =
        runCatching { client.newCall(Request.Builder().url(url(ruta)).post(cuerpo.toRequestBody("application/json".toMediaType())).build()).execute().close() }

    private fun vm(): TableOrderViewModel {
        fotos = FotosDeMesas(dirFotos, { piloto })
        client = OkHttpClient.Builder().addInterceptor(PuertaDeLaFoto(fotos)).build()
        val repository = mockk<TableServiceRepository>(relaxed = true)
        every { repository.tables } returns MutableStateFlow(emptyList())
        every { repository.ownership } returns MutableStateFlow(TableServiceRepository.TableOwnership())
        coEvery { repository.getOrderDetail(any(), any()) } answers {
            antesDelGet?.invoke()
            runCatching {
                client.newCall(Request.Builder().url(url("orders/${arg<String>(1)}")).build()).execute().use { r ->
                    check(r.isSuccessful) { "HTTP ${r.code}" }
                    json.decodeFromString(OrderDetailResponse.serializer(), r.body!!.string()).order!!
                }
            }
        }
        coEvery { repository.addRound(any(), any(), any(), any()) } answers {
            runCatching {
                val cuerpo = json.encodeToString(AddItemsRequest.serializer(), AddItemsRequest(arg<List<AddOrderItemRequest>>(2), arg(3)))
                client.newCall(Request.Builder().url(url("orders/${arg<String>(1)}/items")).post(cuerpo.toRequestBody("application/json".toMediaType()))
                    .build()).execute().use { r ->
                    check(r.isSuccessful) { "HTTP ${r.code}" }
                    UpdatedOrder("order-F", cuenta.version ?: 1)
                }
            }
        }
        every { outbox.acks } returns acks
        every { outbox.pendingCount } returns MutableStateFlow(0)
        every { outbox.observarEstado(any()) } answers { flowOf(cola.firstOrNull { it.id == firstArg<String>() }?.estado) }
        coEvery { outbox.enqueue(any(), any(), any(), any(), any(), any()) } answers {
            arg<String>(3).also { id -> if (cola.none { it.id == id }) cola += FilaDeLaOrden(id, arg(1), if (arg(4)) "HELD" else "PENDING", null, ++seq) }
        }
        coEvery { outbox.soltar(any(), any()) } answers { estado(arg(1), "PENDING") }
        coEvery { outbox.descartar(any(), any()) } answers { estado(arg(1), null) }
        coEvery { outbox.secuenciaActual() } answers { seq }
        coEvery { outbox.filasDeLaOrdenDesde(any(), any(), any()) } answers {
            cola.filter { it.seq > arg<Long>(2) || it.estado in setOf("PENDING", "HELD", "REJECTED") }
        }
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } answers {
            val vivas = cola.filter { it.estado != "ACKED" }
            TrabajoSinResolver(vivas.size, vivas.count { it.estado == "REJECTED" }, vivas.map { it.tipo }.toSet(), vivas.sumOf { it.importeCents ?: 0 })
        }
        every { plan.concedidaPorAvoqado(TableOrderViewModel.CODIGO_PILOTO_SIN_RED) } answers { piloto }
        coEvery { cobros.hayPendiente(any(), any(), any(), any()) } answers { cobroEnOtraCola }
        coEvery { efectivoDeCobros.efectivoDelPedido(any()) } answers { efectivoGuardado ?: throw IllegalStateException("disco") }
        coEvery { efectivoDeCobros.cobrosDelPedido(any(), any()) } answers { cobrosGuardados }
        val config = mockk<PrintConfigRepository>(relaxed = true)
        every { config.getCurrentConfig() } returns PrintConfig()
        val connectivity = mockk<ConnectivityMonitor>(relaxed = true)
        every { connectivity.isConnected } answers { MutableStateFlow(conInternet) }
        return TableOrderViewModel(repository, session, config, mockk(relaxed = true), mockk<PrinterService>(relaxed = true), storage,
            outbox, mockk(relaxed = true), connectivity, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), draftStore = drafts,
            planManager = plan, fotos = fotos, cobrosLocales = cobros, efectivoEnColaDeCobros = efectivoDeCobros)
    }

    /** El mesero abre la mesa M1 (del servidor) desde el plano. */
    private fun abrir() = session.abrirDesdePiso(venue, TableSession.Active("table-F", "1", null, "order-F", "F-1", 3, 0, TableSession.Mode.ORDERING))

    /** Matar la app y volver a abrir la mesa: sólo sobrevive lo de disco (foto, borrador y cola). */
    private fun reiniciar(antes: TableOrderViewModel): TableOrderViewModel {
        antes.viewModelScope.cancel()
        session.clear(); abrir()
        return vm()
    }

    private fun TestScope.ronda(subject: TableOrderViewModel, cents: Int) { subject.addCustomAmount("Ronda", cents); subject.sendRound(); advanceUntilIdle() }
    private fun TestScope.leer(subject: TableOrderViewModel) { subject.loadCheck(); advanceUntilIdle() }
    private fun fotoEnDisco() = FotosDeMesas(dirFotos).foto(venue, "order-F")
    private fun cobrado(cents: Int) { cola += FilaDeLaOrden("cobro-${seq + 1}", "PAY_CASH", "PENDING", cents, ++seq) }

    /** M1 abierta con internet + Coca $25 mandada con internet: la tablet la tomó y la vio completa ⇒ foto de $25. */
    private fun TestScope.mesaConFoto(): TableOrderViewModel {
        abrir()
        val subject = vm()
        leer(subject)
        ronda(subject, 2500)
        assertEquals(2500, (cuenta.total * 100).toInt())
        assertNotNull("la foto limpia quedó en disco", fotoEnDisco())
        return subject
    }

    // MARK: - 27b: el recorrido de la promesa

    @Test fun `27b ronda sin internet sobre la foto, Pagar 45, matar la app y reabrir dice cobrada aqui`() = runTest {
        var subject = mesaConFoto()
        conInternet = false
        ronda(subject, 2000) // el envío REAL falla sin respuesta: la ronda queda en la cola y la foto se conserva
        assertNotNull("la ronda sin respuesta no borra la foto", fotoEnDisco())
        assertEquals("PENDING", cola.single { it.tipo == "ADD_ITEMS" }.estado)
        assertTrue(subject.saldoDeMesaConocido)
        assertEquals("«Pagar $45»", 4500, subject.saldoDeMesa.porPagarCents)

        assertTrue(subject.preparePagar())
        val pagando = session.current()!!
        assertEquals(TableSession.Mode.PAYING, pagando.mode)
        assertEquals(4500, pagando.totalCents)
        assertTrue("el efectivo irá por la cola de mesas con su orderId (B1)", pagando.cobroSinRed)

        cobrado(4500) // el flujo de pago lo guarda en la cola ANTES de «Cobrado» (B0/B1)
        subject = reiniciar(subject)
        leer(subject)
        assertTrue("«Cobrada aquí, falta subirla»", subject.cuentaCobradaAqui)
        assertFalse(subject.preparePagar())
        assertEquals(CUENTA_COBRADA_AQUI, subject.blockedNotice.value)
        assertFalse(session.current()!!.cobroSinRed)
        subject.viewModelScope.cancel()
    }

    // MARK: - D45: un abono en efectivo que espera en la cola de COBROS (el SERVIDOR todavía no lo cuenta)

    /** Mesa del servidor de $100 abierta y leída CON internet; piloto apagado (como en el ensayo general). */
    private fun TestScope.mesaDeCien(): TableOrderViewModel {
        piloto = false
        abrir()
        val subject = vm()
        leer(subject)
        ronda(subject, 10000)
        leer(subject)
        assertEquals(10000, (cuenta.total * 100).toInt())
        return subject
    }

    @Test fun `P1 D45 un abono en la cola de cobros no deja cobrar el total y al subir se cobra el resto`() = runTest {
        val subject = mesaDeCien()
        // $50 en efectivo cuyo POST no llegó: espera en `pending_payments`. La cuenta se relee CON internet antes de que suba.
        efectivoGuardado = EfectivoDelPedido.EN_COLA
        cobrosGuardados = abono(subido = false)
        leer(subject)
        assertEquals("el servidor todavía no lo cuenta", 10000, subject.saldoDeMesa.porPagarCents)

        assertFalse("no se cobran $100 a quien ya abonó $50", subject.preparePagar())
        assertEquals(MESA_COBRO_SIN_SINCRONIZAR, subject.blockedNotice.value)
        assertEquals(TableSession.Mode.ORDERING, session.current()!!.mode)
        advanceUntilIdle() // el toque volvió a leer la cuenta: con el abono todavía en la cola

        // Sube el abono: el SERVIDOR ya lo cuenta, pero la cuenta en pantalla es la que se leyó con el abono en la cola.
        efectivoGuardado = EfectivoDelPedido.NINGUNO
        cobrosGuardados = abono(subido = true)
        cuenta = cuenta.copy(payments = listOf(OrderDetailPayment("pay-50", 50.0, 0.0, "COMPLETED")),
            preBillBalance = PreBillBalance(10000, 5000, 5000))
        assertFalse("esa lectura no se usa para cobrar: se vuelve a leer", subject.preparePagar())
        assertEquals(CUENTA_SE_ACTUALIZA, subject.blockedNotice.value)
        advanceUntilIdle()

        assertTrue(subject.preparePagar())
        assertEquals("el resto", 5000, session.current()!!.totalCents)
        subject.viewModelScope.cancel()
    }

    /**
     * Codex r1 #1: la lectura empieza con la cola vacía, el abono nace y SUBE mientras el SERVIDOR contesta la cuenta vieja ($100). Al tocar
     * «Pagar» ya no hay nada en la cola, pero esa lectura no lo trae: no se cobra con ella, se vuelve a leer.
     */
    @Test fun `P1 D45 un abono que nace y sube durante la lectura no deja cobrar con la cuenta vieja`() = runTest {
        val subject = mesaDeCien()
        antesDelGet = {
            antesDelGet = null
            cobrosGuardados = abono(subido = true) // el GET contesta $100: el SERVIDOR registra el abono justo después
        }
        leer(subject)
        cuenta = cuenta.copy(payments = listOf(OrderDetailPayment("pay-50", 50.0, 0.0, "COMPLETED")),
            preBillBalance = PreBillBalance(10000, 5000, 5000))
        assertEquals("la lectura se quedó con $100", 10000, subject.saldoDeMesa.porPagarCents)

        assertFalse(subject.preparePagar())
        assertEquals(CUENTA_SE_ACTUALIZA, subject.blockedNotice.value)
        advanceUntilIdle()
        assertTrue(subject.preparePagar())
        assertEquals(5000, session.current()!!.totalCents)
        subject.viewModelScope.cancel()
    }

    @Test fun `D45 un cobro por revisar o la cola sin leer no dejan cobrar la mesa`() = runTest {
        val subject = mesaDeCien()
        efectivoGuardado = EfectivoDelPedido.POR_REVISAR
        assertFalse(subject.preparePagar())
        assertEquals(MESA_COBRO_POR_REVISAR, subject.blockedNotice.value)

        efectivoGuardado = null // la lectura de la cola truena: falla CERRADO
        assertFalse(subject.preparePagar())
        assertEquals(MESA_COBROS_SIN_LEER, subject.blockedNotice.value)
        subject.viewModelScope.cancel()
    }

    @Test fun `D45 sin nada en la cola de cobros la mesa se cobra al primer toque`() = runTest {
        val subject = mesaDeCien()
        assertTrue(subject.preparePagar())
        assertEquals(10000, session.current()!!.totalCents)
        subject.viewModelScope.cancel()
    }

    @Test fun `abono sin internet y luego el resto`() = runTest {
        val subject = mesaConFoto()
        conInternet = false
        leer(subject)
        cobrado(1000)
        assertTrue(subject.preparePagar())
        assertEquals("$25 − $10 abonados", 1500, session.current()!!.totalCents)
        subject.viewModelScope.cancel()
    }

    // MARK: - B3 (recorrido en aparato, 12-nov)

    @Test fun `B3 C1 tras cobrar y liberar la mesa, matar y reabrir dice Ya pagada con el mismo aviso que el toque`() = runTest {
        var subject = mesaConFoto()
        conInternet = false
        ronda(subject, 2000)
        assertTrue(subject.preparePagar())
        cobrado(4500)
        // Al terminar el cobro se libera la mesa. Sin internet va a la cola (② la deja pasar: FotosEnLaColaTest); en línea, por ①.
        conInternet = true; escribir("tables/table-F/clear", "{}"); conInternet = false
        assertNotNull("liberar la mesa no borra la foto (el SERVIDOR rechaza liberar una cuenta que debe)", fotoEnDisco())
        subject = reiniciar(subject)
        leer(subject)
        assertEquals("la etiqueta al ABRIR", CUENTA_COBRADA_AQUI, subject.motivoCobradaAqui)
        assertFalse(subject.preparePagar())
        assertEquals("y el toque dicen lo mismo", CUENTA_COBRADA_AQUI, subject.blockedNotice.value)
        subject.viewModelScope.cancel()
    }

    @Test fun `B3 C1 sin foto y con su efectivo en la cola, al abrir ya dice lo que dira el toque`() = runTest {
        var subject = mesaConFoto()
        conInternet = false
        ronda(subject, 2000)
        assertTrue(subject.preparePagar())
        cobrado(4500)
        conInternet = true; escribir("orders/order-F/discounts", """{"discountId":"d-1"}"""); conInternet = false
        assertNull("una escritura de la cuenta sí la borra", fotoEnDisco())
        subject = reiniciar(subject)
        leer(subject)
        assertEquals(COBRO_AQUI_SIN_SUBIR, subject.motivoCobradaAqui)
        assertTrue(subject.cuentaCobradaAqui)
        assertFalse(subject.preparePagar())
        assertEquals(COBRO_AQUI_SIN_SUBIR, subject.blockedNotice.value)
        subject.viewModelScope.cancel()
    }

    @Test fun `B3 la cuenta se cerro con la pantalla abierta, atras regresa al plano y Pagar y agregar lo dicen, de los dos tipos`() = runTest {
        for (provisional in listOf(false, true)) {
            session.clear()
            if (provisional) session.abrirDesdePiso(venue, TableSession.Active("table-P", "2", null, "local-P", "P-1", 1, 0,
                TableSession.Mode.ORDERING, isProvisional = true, localOrderId = "local-P"))
            else abrir()
            val subject = vm()
            val ids = session.current()!!.idsDeLaOrden
            // El acuse de su efectivo dejó la orden en $0 (`TableSyncCoordinator` → `finalizar`), con la pantalla abierta.
            session.finalizar(venue, ids)
            assertNull(session.current())
            assertFalse("$provisional: Pagar no se calla", subject.preparePagar())
            assertEquals(CUENTA_CERRADA, subject.blockedNotice.value)
            subject.dismissBlockedNotice()
            subject.addCustomAmount("Agua", 2000)
            assertTrue("$provisional: no acepta productos", subject.pending.value.isEmpty())
            assertEquals(CUENTA_CERRADA, subject.blockedNotice.value)
            assertTrue("$provisional: atrás regresa al plano", subject.exitToFloor())
            subject.viewModelScope.cancel()
        }
    }

    @Test fun `B3 C7 una cuenta en 0 sin internet dice su razon al tocar Pagar`() = runTest {
        val subject = mesaConFoto()
        // Cortesía de toda la cuenta con internet: ① borra la foto y la relectura toma la nueva, de $0.
        escribir("orders/order-F/comp", """{"reason":"Cortesía"}""")
        cuenta = cuenta.copy(items = cuenta.items.map { it.copy(total = 0.0) }, total = 0.0, version = (cuenta.version ?: 0) + 1)
        leer(subject)
        assertEquals("la foto nueva es de \$0", 0, ((fotoEnDisco()?.detalle?.total ?: -1.0) * 100).toInt())
        conInternet = false
        leer(subject) // la pantalla vuelve a pedir la cuenta y no llega
        val pasa = subject.preparePagar()
        assertEquals("aviso", CUENTA_EN_CERO_SIN_RED, subject.blockedNotice.value)
        assertFalse("no abre el cobro", pasa)
        assertEquals(TableSession.Mode.ORDERING, session.current()!!.mode)
        subject.viewModelScope.cancel()
    }

    // MARK: - Cuándo NO se toma la foto

    @Test fun `la D3 que no le mando ninguna ronda no la cobra sin internet`() = runTest {
        cuenta = cuenta.copy(items = listOf(OrderDetailItem("i0", total = 25.0, externalId = "sync:otra:0")), total = 25.0)
        abrir()
        val subject = vm()
        leer(subject)
        assertNull("sin ronda propia no hay foto", fotoEnDisco())
        conInternet = false
        leer(subject)
        assertFalse(subject.preparePagar())
        assertEquals(TOMADA_EN_OTRA_TABLET, subject.blockedNotice.value)
        subject.viewModelScope.cancel()
    }

    @Test fun `no se toma foto con algo pendiente de la orden en otra cola`() = runTest {
        abrir()
        val subject = vm()
        cobroEnOtraCola = true
        ronda(subject, 2500)
        assertNull(fotoEnDisco())
        cobroEnOtraCola = false
        leer(subject)
        assertNotNull("en cuanto no hay nada pendiente, la siguiente lectura sí", fotoEnDisco())
        subject.viewModelScope.cancel()
    }

    @Test fun `no se toma foto si otra pantalla escribe sobre la mesa entre pedir y recibir`() = runTest {
        abrir()
        val subject = vm()
        antesDelGet = { escribir("orders/order-F/items/i0/comp", """{"reason":"cortesía desde el plano"}""") }
        ronda(subject, 2500)
        assertNull(fotoEnDisco())
        antesDelGet = null
        leer(subject)
        assertNotNull(fotoEnDisco())
        subject.viewModelScope.cancel()
    }

    @Test fun `no se toma foto con el cobro abierto`() = runTest {
        val subject = mesaConFoto()
        escribir("orders/order-F/discounts", """{"discountId":"d-1"}""") // cualquier escritura sobre la mesa la borra
        assertNull(fotoEnDisco())
        session.start(session.current()!!.copy(mode = TableSession.Mode.PAYING))
        leer(subject)
        assertNull("con el cobro abierto no se fotografía", fotoEnDisco())
        subject.viewModelScope.cancel()
    }

    @Test fun `una lectura que ya no es simple borra la foto y sin internet dice por que`() = runTest {
        val subject = mesaConFoto()
        cuenta = cuenta.copy(discounts = listOf(AppliedOrderDiscount("d1", "10 %", 2.5)), discountAmount = 2.5, total = 22.5)
        leer(subject)
        assertNull(fotoEnDisco())
        conInternet = false
        leer(subject)
        assertFalse(subject.preparePagar())
        assertTrue(subject.blockedNotice.value!!.contains("descuento de cuenta"))
        subject.viewModelScope.cancel()
    }

    @Test fun `caso 10 una cortesia desde el plano con internet borra la foto y sin internet queda en gris`() = runTest {
        val subject = mesaConFoto()
        escribir("orders/order-F/items/i0/comp", """{"reason":"Se cayó"}""")
        assertNull(fotoEnDisco())
        conInternet = false
        leer(subject)
        assertFalse(subject.preparePagar())
        assertEquals(SIN_CUENTA_COMPLETA, subject.blockedNotice.value)
        assertFalse(session.current()!!.cobroSinRed)
        subject.viewModelScope.cancel()
    }

    // MARK: - R5-1: una ronda rechazada y descartada no suma, también DESPUÉS de re-encolarse

    @Test fun `R5-1 ronda rechazada y descartada por el gerente nunca suma sobre la foto y ya no vuelve a la cola (D29)`() = runTest {
        var subject = mesaConFoto()
        conInternet = false
        ronda(subject, 2000)
        val llave = cola.single { it.tipo == "ADD_ITEMS" }.id
        // Vuelve el internet, la cola sube la ronda y el SERVIDOR la rechaza (producto agotado): la cola REAL guarda el rechazo.
        val real = rechazar(llave)
        assertNull("el rechazo mató la foto", fotoEnDisco())
        // El gerente la marca resuelta: la fila se va y, ANTES, su ronda sale del borrador (D29; antes se quedaba: A4).
        real.dismissRejected(venue, llave)
        estado(llave, null)

        conInternet = false
        subject = reiniciar(subject)
        leer(subject)
        assertTrue("D29: el borrador no la vuelve a encolar", cola.none { it.id == llave })
        assertTrue(subject.queued.value.isEmpty())
        assertFalse("nunca $45 sobre la foto vieja", subject.preparePagar())
        assertEquals(SIN_CUENTA_COMPLETA, subject.blockedNotice.value)
        assertEquals(TableSession.Mode.ORDERING, session.current()!!.mode)

        // Con internet la tablet vuelve a ver la cuenta ($25, sin la Corona) y, sin internet, se cobra lo que tiene el servidor.
        conInternet = true
        leer(subject)
        conInternet = false
        leer(subject)
        assertTrue(subject.preparePagar())
        assertEquals("lo que tiene el servidor, sin la ronda rechazada", 2500, session.current()!!.totalCents)
        assertTrue(cola.none { it.id == llave })
        subject.viewModelScope.cancel()
    }

    @Test fun `D29 marcada resuelta con la mesa abierta en pantalla - guardar el borrador desde memoria no la revive`() = runTest {
        var subject = mesaConFoto()
        conInternet = false
        ronda(subject, 2000)
        val llave = cola.single { it.tipo == "ADD_ITEMS" }.id
        val real = rechazar(llave)
        // La pantalla de la mesa sigue abierta (con la ronda en memoria) mientras el gerente la marca resuelta.
        real.dismissRejected(venue, llave)
        estado(llave, null)
        // Vuelve el internet y la pantalla abierta lee la cuenta: guarda el borrador ENTERO con las rondas que tiene en memoria.
        conInternet = true
        leer(subject)
        assertFalse("la ronda descartada no vuelve al disco", llave in drafts.load(venue, "order-F").rounds)

        conInternet = false
        subject = reiniciar(subject)
        leer(subject)
        assertTrue("no vuelve a la cola", cola.none { it.id == llave })
        assertTrue(subject.preparePagar())
        assertEquals(2500, session.current()!!.totalCents)
        subject.viewModelScope.cancel()
    }

    @Test fun `D29 con el piloto apagado la ronda rechazada y resuelta tampoco vuelve a la cola`() = runTest {
        piloto = false
        abrir()
        var subject = vm()
        leer(subject)
        ronda(subject, 2500)
        conInternet = false
        ronda(subject, 2000)
        val llave = cola.single { it.tipo == "ADD_ITEMS" }.id
        rechazar(llave).dismissRejected(venue, llave)
        estado(llave, null)

        subject = reiniciar(subject)
        leer(subject)
        assertTrue("no vuelve a la cola", cola.none { it.id == llave })
        assertTrue("ni a «por sincronizar»", subject.queued.value.isEmpty())
        subject.viewModelScope.cancel()
    }

    @Test fun `D29 Codex r1 marcada resuelta mientras la mesa se esta reabriendo - la recuperacion no la vuelve a encolar`() = runTest {
        var subject = mesaConFoto()
        conInternet = false
        ronda(subject, 2000)
        val llave = cola.single { it.tipo == "ADD_ITEMS" }.id
        rechazar(llave)
        subject = reiniciar(subject)
        // `restoreDraft` ya leyó el borrador (con la ronda) y, justo antes de preguntar el estado de su fila, el gerente la marca
        // resuelta: el borrador la pierde (lo mismo que hace `dismissRejected`, aquí sin cambiar de hilo para que la prueba espere
        // de verdad) y la fila se va, así que la pregunta contesta «no hay fila».
        var descartada = false
        every { outbox.observarEstado(llave) } answers {
            kotlinx.coroutines.flow.flow { drafts.descartarRonda(venue, "order-F", llave); estado(llave, null); descartada = true; emit(null) }
        }
        leer(subject)
        assertTrue("el cruce sí ocurrió", descartada)
        assertTrue("no vuelve a la cola", cola.none { it.id == llave })
        assertFalse(llave in drafts.load(venue, "order-F").rounds)
        subject.viewModelScope.cancel()
    }

    /** El SERVIDOR rechaza la ronda (producto agotado) y la cola REAL guarda el rechazo; devuelve esa cola. */
    private suspend fun rechazar(llave: String): SyncOutbox {
        val dao = mockk<SyncIntentDao>(relaxed = true)
        val fila = SyncIntentEntity(llave, venue, "staff", 2, "ADD_ITEMS", """{"orderId":"order-F","items":[{"externalId":"sync:$llave:0"}]}""")
        coEvery { dao.findForVenue(venue, llave) } returns fila.copy(status = "REJECTED")
        val real = colaReal(dao)
        real.conservarAck(SyncAck(llave, "REJECTED", "BUSINESS_RULE", "Producto agotado"), fila, venue, "d1", "d1")
        estado(llave, "REJECTED")
        return real
    }

    @Test fun `Codex b2b-r1 #1 si el disco falla al guardar el rechazo, la mesa queda en gris y nunca cobra la ronda rechazada`() = runTest {
        val subject = mesaConFoto()
        conInternet = false
        ronda(subject, 2000)
        val llave = cola.single { it.tipo == "ADD_ITEMS" }.id
        val dao = mockk<SyncIntentDao>(relaxed = true)
        val fila = SyncIntentEntity(llave, venue, "staff", 2, "ADD_ITEMS", """{"orderId":"order-F","items":[]}""")
        sinPoderBorrar { colaReal(dao).conservarAck(SyncAck(llave, "REJECTED", "BUSINESS_RULE", "Producto agotado"), fila, venue, "d1", "d1") }
        coVerify(exactly = 1) { dao.resolve(llave, "REJECTED", any(), any(), any()) }
        estado(llave, "REJECTED")
        assertNotNull("la foto no se pudo borrar", fotoEnDisco())
        assertFalse("nunca $45 con la ronda rechazada", subject.preparePagar())
        assertEquals(CAMBIOS_SIN_SINCRONIZAR, subject.blockedNotice.value)
        subject.viewModelScope.cancel()
    }

    @Test fun `Codex b2b-r2 #1 una lectura con descuento que no pudo borrar la foto deja gris, tambien tras reiniciar`() = runTest {
        var subject = mesaConFoto()
        cuenta = cuenta.copy(discounts = listOf(AppliedOrderDiscount("d1", "10 %", 2.5)), discountAmount = 2.5, total = 22.5,
            version = (cuenta.version ?: 0) + 1)
        sinPoderBorrar { leer(subject) }
        assertNotNull("el archivo sobrevivió (error de E/S)", fotoEnDisco())
        conInternet = false
        leer(subject)
        assertFalse(subject.preparePagar())
        assertTrue(subject.blockedNotice.value!!.contains("descuento de cuenta"))
        // Tras reiniciar, la foto vuelve a cargarse viva del archivo, pero no es la última cuenta leída: tampoco se usa.
        subject = reiniciar(subject)
        leer(subject)
        assertFalse(subject.preparePagar())
        assertTrue(subject.blockedNotice.value!!.contains("descuento de cuenta"))
        subject.viewModelScope.cancel()
    }

    @Test fun `Codex b2b-r2 #2 apagar, descartar un rechazo, prender y reiniciar sin red deja gris`() = runTest {
        var subject = mesaConFoto()
        conInternet = false
        ronda(subject, 2000)
        val llave = cola.single { it.tipo == "ADD_ITEMS" }.id
        val dao = mockk<SyncIntentDao>(relaxed = true)
        val fila = SyncIntentEntity(llave, venue, "staff", 2, "ADD_ITEMS", """{"orderId":"order-F","items":[]}""")
        coEvery { dao.findForVenue(venue, llave) } returns fila.copy(status = "REJECTED")
        val real = colaReal(dao)
        // El rechazo llega y el disco no deja borrar la foto: queda muerta en memoria, el archivo sobrevive.
        sinPoderBorrar { real.conservarAck(SyncAck(llave, "REJECTED", "BUSINESS_RULE", "Producto agotado"), fila, venue, "d1", "d1") }
        estado(llave, "REJECTED")
        assertNotNull(fotoEnDisco())
        // Avoqado apaga el piloto: al leer el plan, la tablet borra las fotos de ESA sucursal.
        piloto = false
        fotos.alLeerElPlan(venue)
        assertNull("la purga borró el archivo", fotoEnDisco())
        // El gerente la marca resuelta (apagado nunca frena) y luego se vuelve a prender y se reinicia sin red.
        real.dismissRejected(venue, llave)
        estado(llave, null)
        piloto = true
        subject = reiniciar(subject)
        leer(subject)
        assertTrue("D29: la ronda descartada ya no vuelve a la cola", cola.none { it.id == llave })
        assertFalse("nunca $45: no hay foto", subject.preparePagar())
        assertEquals(SIN_CUENTA_COMPLETA, subject.blockedNotice.value)
        subject.viewModelScope.cancel()
    }

    @Test fun `F1 la ronda vuelve con 2xx y no puede borrar su foto - queda muerta y la mesa no cobra con ella`() = runTest {
        val subject = mesaConFoto()
        sinPoderBorrar { ronda(subject, 2000) } // con internet: el servidor la aplica
        assertTrue("la ronda no quedó pendiente", cola.none { it.tipo == "ADD_ITEMS" })
        conInternet = false
        leer(subject)
        assertFalse(subject.preparePagar())
        assertEquals(SIN_CUENTA_COMPLETA, subject.blockedNotice.value)
        subject.viewModelScope.cancel()
    }

    @Test fun `Codex b2b-r3 F2 el piloto se apaga a media lectura - no nace la foto`() = runTest {
        val subject = mesaConFoto()
        escribir("orders/order-F/discounts", """{"discountId":"d"}""") // la foto se va; la próxima lectura la tomaría de nuevo
        assertNull(fotoEnDisco())
        // Avoqado apaga el piloto mientras la tablet está leyendo la cuenta (la lectura empezó con el piloto prendido).
        antesDelGet = { piloto = false; fotos.alLeerElPlan(venue); antesDelGet = null }
        leer(subject)
        assertNull("no nace la foto", fotoEnDisco())
        subject.viewModelScope.cancel()
    }

    @Test fun `Codex b2b-r3 F2 el piloto se apaga entre calcular el saldo y abrir el cobro - camino de hoy, sin cobroSinRed`() = runTest {
        val subject = mesaConFoto()
        conInternet = false
        ronda(subject, 2000)
        // La lectura de la cola (suspende) coincide con guardar el plan apagado.
        coEvery { outbox.filasDeLaOrdenDesde(any(), any(), any()) } answers {
            piloto = false
            cola.filter { it.seq > arg<Long>(2) || it.estado in setOf("PENDING", "HELD", "REJECTED") }
        }
        assertFalse(subject.preparePagar())
        assertEquals("el texto de hoy", "Esta mesa tiene cambios sin sincronizar: cóbrala cuando se sincronicen.", subject.blockedNotice.value)
        assertEquals(TableSession.Mode.ORDERING, session.current()!!.mode)
        assertFalse(session.current()!!.cobroSinRed)
        subject.viewModelScope.cancel()
    }

    @Test fun `Codex b2b-r3 F2 apagar con un saldo sin red ya en pantalla - vuelve lo de hoy`() = runTest {
        var subject = mesaConFoto()
        conInternet = false
        ronda(subject, 2000)
        assertTrue(subject.saldoDeMesaConocido); assertEquals(4500, subject.saldoDeMesa.porPagarCents)
        piloto = false
        assertFalse("«Pagar $45» ya no se dice con el piloto apagado", subject.saldoDeMesaConocido)
        // Y «Ya pagada» tampoco: cobrada aquí sin internet, reabierta, y luego se apaga.
        piloto = true
        cobrado(4500)
        subject = reiniciar(subject)
        leer(subject)
        assertTrue(subject.cuentaCobradaAqui)
        piloto = false
        assertFalse(subject.cuentaCobradaAqui)
        subject.viewModelScope.cancel()
    }

    private fun colaReal(dao: SyncIntentDao): SyncOutbox {
        val connectivity = mockk<ConnectivityMonitor>()
        every { connectivity.isConnected } returns MutableStateFlow(false)
        every { connectivity.isServerReachable } returns MutableStateFlow(false)
        val prefs = mockk<SharedPreferences>(relaxed = true)
        every { prefs.getString(any(), any()) } returns "d1"
        val context = mockk<Context>(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { context.filesDir } returns root
        return SyncOutbox(dao, mockk(relaxed = true), connectivity, storage, mockk(relaxed = true), context, mockk(relaxed = true), fotos = fotos)
    }

    // MARK: - El interruptor apagado: todo como hoy

    @Test fun `con el interruptor apagado no hay foto, ni puerta activa, ni bandera, ni textos nuevos`() = runTest {
        piloto = false
        abrir()
        val subject = vm()
        leer(subject)
        ronda(subject, 2500)
        assertFalse("ni la carpeta de fotos existe: la puerta no tocó el disco", dirFotos.exists())
        conInternet = false
        ronda(subject, 2000)
        assertFalse(subject.saldoDeMesaConocido && subject.saldoDeMesa.porPagarCents == 4500)
        assertFalse(subject.preparePagar())
        assertEquals("el texto de hoy", "Esta mesa tiene cambios sin sincronizar: cóbrala cuando se sincronicen.", subject.blockedNotice.value)
        assertEquals(TableSession.Mode.ORDERING, session.current()!!.mode)
        assertFalse(session.current()!!.cobroSinRed)
        assertFalse(subject.cuentaCobradaAqui)
        assertFalse(dirFotos.exists())
        subject.viewModelScope.cancel()
    }
}
