package com.avoqado.pos.onlineorders

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.domain.AccionResultado
import com.avoqado.pos.onlineorders.domain.ConteosPorPagar
import com.avoqado.pos.onlineorders.domain.CursoresPorPagar
import com.avoqado.pos.onlineorders.domain.Inbox
import com.avoqado.pos.onlineorders.domain.ListaPorPagar
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.domain.SaldoDelPedido
import com.avoqado.pos.onlineorders.presentation.OnlineOrdersViewModel
import com.avoqado.pos.onlineorders.presentation.SeccionesBandeja
import com.avoqado.pos.onlineorders.presentation.TextosPedidosEnLinea
import com.avoqado.pos.onlineorders.presentation.TipoDeDialogo
import com.avoqado.pos.onlineorders.presentation.carritoDelCobro
import com.avoqado.pos.onlineorders.presentation.conLoCobradoEnElAparato
import com.avoqado.pos.onlineorders.presentation.horaDelNegocio
import com.avoqado.pos.onlineorders.presentation.muestraCancelarFolio
import com.avoqado.pos.onlineorders.presentation.pedidoDelDialogo
import com.avoqado.pos.onlineorders.presentation.textoDeCobro
import com.avoqado.pos.onlineorders.presentation.textoDePago
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.domain.OrigenDelCobro
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tienda híbrida (A3): «Cobrar» y «Cancelar folio» de un folio de «Por pagar». Lo que decide la bandeja ANTES de abrir el cobro
 * (releer el folio con red, el saldo del servidor, sin red sólo efectivo, un efectivo en la cola bloquea) y lo que enseña la lista
 * después (lo cobrado sin red pasa en el aparato a «Por entregar»). Lo que pasa DENTRO del cobro cuando el folio vence con la
 * pantalla abierta (el 400 de cuenta cancelada borra la reserva) vive en `CobroRechazadoSinConfirmarTest`, que tiene el disco.
 *
 * La cola de efectivo es la REAL (`CashPaymentRepository` sobre un DAO falso), como en `CobroDePedidoEnLineaTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CobroDeFolioTest {

    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<OnlineOrdersRepository>()
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val dao = mockk<PendingPaymentDao>(relaxed = true)
    private val cola = CashPaymentRepository(secureStorage, dao)

    /** Las filas de `pending_payments` del negocio, tal como las devolvería Room. */
    private var filas: List<PendingPaymentEntity> = emptyList()
    private var respuesta: Result<ListaPorPagar> = Result.failure(IOException("sin red"))
    private var guardada: ListaPorPagar? = null

    /** Una red que no contesta («conectado sin datos»): la bandeja y «Por pagar» se quedan esperando mientras exista. */
    private var compuerta: CompletableDeferred<Unit>? = null

    /** El folio vence a las 18:30 (UTC); el reloj del aparato marca las 18:10. */
    private var reloj = Instant.parse("2026-11-06T18:10:00Z").toEpochMilli()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { secureStorage.venueId } returns "venue-1"
        coEvery { dao.forVenue("venue-1") } answers { filas }
        coEvery { repo.inbox(any(), any(), any()) } coAnswers {
            compuerta?.await()
            Result.success(Inbox(Instant.parse("2026-11-06T18:10:00Z"), 0, 0, 0, emptyList(), storeOff = false, truncated = false, nextCursor = null))
        }
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun folio(
        id: String = "f1", numero: String = "W7K3P", nombre: String? = "Ana", total: Long = 13400, falta: Long = 13400,
        pago: String = "PENDING", vence: String = "2026-11-06T18:30:00Z",
    ) = OnlineOrder(
        orderId = id, onlineOrderId = "oo-$id", orderNumber = numero, status = OnlineOrderStatus.PENDING_ACCEPTANCE,
        paymentMode = "PAY_AT_PICKUP", paymentStatus = pago, customerName = nombre, customerPhone = null, note = null,
        createdAt = Instant.parse("2026-11-06T18:00:00Z"), acceptDeadlineAt = Instant.parse(vence), estimatedReadyAt = null,
        totalCents = total, remainingCents = falta, needsPrint = false, items = emptyList(), requiresAcceptance = false, channel = "QR",
    )

    private fun lista(porPagar: List<OnlineOrder> = emptyList(), porEntregar: List<OnlineOrder> = emptyList()) =
        ListaPorPagar(true, true, 30, ConteosPorPagar(porPagar.size, porEntregar.size), porPagar, porEntregar, CursoresPorPagar(null, null))

    private fun fila(orderId: String, estado: String, cents: Int = 13400) = PendingPaymentEntity(
        id = "fila-$orderId-$estado", venueId = "venue-1", staffId = "staff-1", amountCents = cents, tipCents = 0,
        method = "CASH", paymentType = "ORDER", orderId = orderId, syncStatus = estado,
    )

    private fun armar() = OnlineOrdersViewModel(
        repositorio = repo,
        permisoActualizar = { true },
        permisoCancelar = { true },
        tienePlan = { true },
        hayPedidosAbiertos = { true },
        ventanaALaVista = MutableStateFlow(true),
        efectivoDelPedido = { cola.efectivoDelPedido(it) },
        efectivoYaSubido = { cola.huboEfectivoSubido(it) },
        leerPorPagar = { _, _, _ -> compuerta?.await(); respuesta },
        foliosGuardados = { guardada },
        ahoraMs = { reloj },
        efectivoEnCola = { cola.efectivoEnColaPorPedido() },
    )

    // MARK: - Con red se relee el folio antes de abrir el cobro

    @Test
    fun `con red relee el folio antes de cobrar - vencido no abre el cobro y dice cobralo como venta normal`() = runTest(dispatcher) {
        // El folio venció (la cuenta se canceló) mientras la lista lo seguía enseñando: el reloj ya pasó de las 18:30.
        reloj = Instant.parse("2026-11-06T18:31:00Z").toEpochMilli()
        coEvery { repo.saldoDelPedido("f1") } returns Result.success(SaldoDelPedido(0L, estadoDeLaCuenta = "CANCELLED"))
        val vm = armar()

        vm.cobrarFolio(folio())
        advanceUntilIdle()

        assertNull("no empieza ningún cobro", vm.cobroAbierto.value)
        assertEquals("Este pedido ya venció: cóbralo como venta normal", vm.aviso.value)
        coVerify(atLeast = 1) { repo.inbox("all", any(), any()) } // se relee la lista para que el folio salga
    }

    @Test
    fun `un folio cancelado por otra caja antes de vencer dice que se cancelo, no que vencio`() = runTest(dispatcher) {
        coEvery { repo.saldoDelPedido("f1") } returns Result.success(SaldoDelPedido(0L, estadoDeLaCuenta = "CANCELLED"))
        val vm = armar()

        vm.cobrarFolio(folio())
        advanceUntilIdle()

        assertNull(vm.cobroAbierto.value)
        assertEquals("Este folio ya se canceló: cóbralo como venta normal", vm.aviso.value)
    }

    @Test
    fun `un folio que otra caja ya cobro no abre otro cobro y dice a que hora`() = runTest(dispatcher) {
        val pagadoA = Instant.parse("2026-11-06T18:21:00Z")
        coEvery { repo.saldoDelPedido("f1") } returns Result.success(SaldoDelPedido(0L, estadoDeLaCuenta = "CONFIRMED", pagadoA = pagadoA))
        val vm = armar()

        vm.cobrarFolio(folio())
        advanceUntilIdle()

        assertNull("dos cajas sobre el mismo folio: la segunda no cobra otra vez", vm.cobroAbierto.value)
        assertEquals(
            "Este folio ya se pagó. Lo cobró otra caja a las ${horaDelNegocio(pagadoA)}. Ya está en «Por entregar».",
            vm.aviso.value,
        )
        coVerify(atLeast = 1) { repo.inbox("all", any(), any()) }
    }

    @Test
    fun `un folio pagado sin hora conocida lo dice sin hora`() {
        assertEquals("Este folio ya se pagó. Lo cobró otra caja. Ya está en «Por entregar».", TextosPedidosEnLinea.folioYaPagado(null))
    }

    @Test
    fun `con red abre PEDIDO_EN_LINEA con el saldo del servidor y no con el total`() = runTest(dispatcher) {
        // Ya pagó $100 de $185 (la lista dice «Falta $85»), y otra caja abonó otros $35 que la lista aún no ve: el servidor dice $50.
        val conAbono = folio(total = 18500, falta = 8500, pago = "PARTIAL")
        coEvery { repo.saldoDelPedido("f1") } returns Result.success(SaldoDelPedido(5000L, estadoDeLaCuenta = "PENDING"))
        val vm = armar()

        vm.cobrarFolio(conAbono)
        advanceUntilIdle()

        val cobro = vm.cobroAbierto.value!!
        assertEquals(OrigenDelCobro.PEDIDO_EN_LINEA, cobro.origen)
        assertTrue("con red se ofrece la terminal ligada", cobro.origen.permiteTarjeta)
        assertEquals("se cobra lo que dice el servidor, nunca el total", 5000, cobro.saldoCents)
        assertTrue(cobro.esFolio)
        // m1: el texto del 400 dentro del cobro se decide AL FALLAR: pasada la hora, venció; antes, otra caja lo canceló.
        assertEquals(TextosPedidosEnLinea.FOLIO_VENCIDO, cobro.textoSiLaCuentaSeCancelo(Instant.parse("2026-11-06T18:31:00Z")))
        assertEquals(TextosPedidosEnLinea.FOLIO_CANCELADO, cobro.textoSiLaCuentaSeCancelo(Instant.parse("2026-11-06T18:20:00Z")))
        val carrito = carritoDelCobro(cobro)
        assertEquals(5000, carrito.totalCents)
        assertEquals("vocabulario único: «folio», nunca «Pedido #»", "Folio W7K3P", carrito.items.single().name)
        assertNull(vm.aviso.value)
    }

    @Test
    fun `sin red abre PEDIDO_EN_LINEA_SIN_SALDO_ACTUALIZADO solo efectivo con lo que falta segun la lista`() = runTest(dispatcher) {
        val conAbono = folio(total = 18500, falta = 8500, pago = "PARTIAL")
        coEvery { repo.saldoDelPedido("f1") } returns Result.failure(IOException("sin red"))
        // R104: sin red, el saldo sale de la copia guardada (la que el aparato sí tiene en disco).
        guardada = lista(porPagar = listOf(conAbono))
        val vm = armar()

        vm.cobrarFolio(conAbono)
        advanceUntilIdle()

        val cobro = vm.cobroAbierto.value!!
        assertEquals(OrigenDelCobro.PEDIDO_EN_LINEA_SIN_SALDO_ACTUALIZADO, cobro.origen)
        assertFalse("la tarjeta sale en gris con su razón (SOLO_EFECTIVO_SIN_SALDO)", cobro.origen.permiteTarjeta)
        assertEquals("lo que falta, nunca el total", 8500, cobro.saldoCents)
        assertNull("sin red es un estado normal: no hay aviso", vm.aviso.value)
    }

    @Test
    fun `sin red un folio que ya paso su hora se cobra igual en efectivo`() = runTest(dispatcher) {
        // Decisión 16: lo capturado sin red entra por la cola (`isOfflineReplay`) y el SERVIDOR lo deja en «Por entregar».
        reloj = Instant.parse("2026-11-06T18:45:00Z").toEpochMilli()
        coEvery { repo.saldoDelPedido("f1") } returns Result.failure(IOException("sin red"))
        guardada = lista(porPagar = listOf(folio()))
        val vm = armar()

        vm.cobrarFolio(folio())
        advanceUntilIdle()

        assertEquals(OrigenDelCobro.PEDIDO_EN_LINEA_SIN_SALDO_ACTUALIZADO, vm.cobroAbierto.value?.origen)
        assertEquals(13400, vm.cobroAbierto.value?.saldoCents)
    }

    @Test
    fun `con un efectivo EN_COLA del mismo folio no deja cobrar otra vez`() = runTest(dispatcher) {
        filas = listOf(fila("f1", "PENDING"))
        coEvery { repo.saldoDelPedido(any()) } returns Result.success(SaldoDelPedido(13400L, estadoDeLaCuenta = "PENDING"))
        val vm = armar()

        vm.cobrarFolio(folio())
        advanceUntilIdle()

        assertNull(vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.COBRO_EN_EFECTIVO_GUARDADO, vm.aviso.value)
        coVerify(exactly = 0) { repo.saldoDelPedido(any()) }
    }

    // MARK: - «Cancelar folio»

    @Test
    fun `Cancelar folio llama POST cancel y con ORDER_HAS_PAYMENTS muestra su texto`() = runTest(dispatcher) {
        val texto = "El pedido ya tiene un cobro, o uno en curso en la terminal. Espera a que termine; si ya se cobró, usa el reembolso de la cuenta."
        coEvery { repo.cancel("f1") } returns AccionResultado.Error(texto)
        val vm = armar()

        vm.cancelar("f1")
        advanceUntilIdle()

        coVerify(exactly = 1) { repo.cancel("f1") }
        assertEquals(texto, vm.aviso.value)
    }

    @Test
    fun `Cancelar folio solo existe sin dinero, con permiso y con red`() {
        val sinDinero = folio()
        val conAbono = folio(total = 18500, falta = 8500, pago = "PARTIAL")
        assertTrue(muestraCancelarFolio(sinDinero, puedeCancelar = true, sinConexion = false))
        assertFalse("un folio con dinero no lo tiene", muestraCancelarFolio(conAbono, puedeCancelar = true, sinConexion = false))
        assertFalse("sin orders:cancel-unpaid", muestraCancelarFolio(sinDinero, puedeCancelar = false, sinConexion = false))
        assertFalse("sin red vuelve con la conexión", muestraCancelarFolio(sinDinero, puedeCancelar = true, sinConexion = true))

        // El diálogo vive fuera de la lista: sólo vale mientras el folio siga por pagar y sin dinero.
        assertEquals(sinDinero, pedidoDelDialogo("f1", TipoDeDialogo.CANCELAR_FOLIO, SeccionesBandeja(), listOf(sinDinero)))
        assertNull(pedidoDelDialogo("f1", TipoDeDialogo.CANCELAR_FOLIO, SeccionesBandeja(), listOf(conAbono)))
        assertNull("ya no está en «Por pagar»", pedidoDelDialogo("f1", TipoDeDialogo.CANCELAR_FOLIO, SeccionesBandeja(), emptyList()))
    }

    @Test
    fun `Cancelar folio siempre pregunta con el folio y el nombre`() {
        assertEquals("¿Cancelar el folio W7K3P?", TextosPedidosEnLinea.cancelarFolioTitulo("W7K3P"))
        assertEquals(
            "Ana lo verá cancelado en su celular. Úsalo para un pedido falso o si ya no lo quiere.",
            TextosPedidosEnLinea.cancelarFolioCuerpo("Ana"),
        )
        assertEquals(
            "Lo verá cancelado en su celular. Úsalo para un pedido falso o si ya no lo quiere.",
            TextosPedidosEnLinea.cancelarFolioCuerpo(" "),
        )
        assertEquals("No", TextosPedidosEnLinea.NO)
        assertEquals("Sí, cancelar", TextosPedidosEnLinea.SI_CANCELAR)
    }

    // MARK: - Sin red: lo cobrado en el aparato pasa a «Por entregar»

    @Test
    fun `el boton dice Cobrar en efectivo sin red`() {
        assertEquals("Cobrar", textoDeCobro(sinConexion = false))
        assertEquals("Cobrar en efectivo", textoDeCobro(sinConexion = true))
    }

    @Test
    fun `lo cobrado sin red pasa en el aparato a Por entregar para que nadie lo cobre dos veces`() {
        val ana = folio("f1", "W7K3P")
        val pau = folio("f2", "W9D5F", "Pau", total = 9500, falta = 9500)
        val leo = folio("e1", "W8M2C", "Leo", total = 6900, falta = 0, pago = "PAID")
        val antes = lista(porPagar = listOf(ana, pau), porEntregar = listOf(leo))

        val vista = conLoCobradoEnElAparato(antes, setOf("f2"))

        assertEquals(listOf("f1"), vista.porPagar.map { it.orderId })
        assertEquals("lo recién cobrado va primero", listOf("f2", "e1"), vista.porEntregar.map { it.orderId })
        assertEquals(1, vista.counts.toPay)
        assertEquals(2, vista.counts.toDeliver)
        assertEquals("nada cambia sin efectivo en la cola", antes, conLoCobradoEnElAparato(antes, emptySet()))
        assertEquals("ni con uno de otro pedido", antes, conLoCobradoEnElAparato(antes, setOf("otro")))
    }

    @Test
    fun `la bandeja sabe que folios tienen efectivo en la cola aunque la lectura falle`() = runTest(dispatcher) {
        // Arranque en frío sin red: la lista guardada es de antes de cobrar; el efectivo ya está en la cola del aparato.
        guardada = lista(porPagar = listOf(folio("f1"), folio("f2", "W9D5F", "Pau"), folio("f3", "WQ2RS", "Sofía")))
        filas = listOf(fila("f2", "PENDING"), fila("f3", "EN_VUELO"))
        respuesta = Result.failure(IOException("sin red"))
        val vm = armar()

        vm.descubrirPorPagar()

        // «Por revisar» (EN_VUELO) no se mueve: no se sube solo y su «Cobrar» dice que lo resuelve un gerente.
        assertEquals(mapOf("f2" to 13400L), vm.cobradosEnElAparato.value)
    }

    @Test
    fun `al subir el efectivo el folio deja de contarse como cobrado en el aparato`() = runTest(dispatcher) {
        respuesta = Result.success(lista(porPagar = listOf(folio("f1"))))
        filas = listOf(fila("f1", "PENDING"))
        val vm = armar()
        vm.refrescar()
        assertEquals(mapOf("f1" to 13400L), vm.cobradosEnElAparato.value)

        filas = listOf(fila("f1", "SYNCED"))
        respuesta = Result.success(lista(porEntregar = listOf(folio("f1", pago = "PAID", falta = 0))))
        vm.refrescar()
        assertEquals(emptyMap<String, Long>(), vm.cobradosEnElAparato.value)
    }

    @Test
    fun `Pagado en efectivo dice lo que guardo la cola, no lo que falta segun la lista`() = runTest(dispatcher) {
        // m4: la lista vieja decía $134; el cobro guardado en la cola fue de $90 (otra caja abonó antes y la lista no lo sabía).
        respuesta = Result.success(lista(porPagar = listOf(folio("f1"))))
        filas = listOf(fila("f1", "PENDING", cents = 9000), fila("otro", "PENDING"))
        val vm = armar()
        vm.refrescar()
        assertEquals("sólo los folios de «Por pagar»", mapOf("f1" to 9000L), vm.cobradosEnElAparato.value)
    }

    // MARK: - I1: lo cobrado sin red se mueve ANTES de tocar la red

    @Test
    fun `al cerrar el cobro lo cobrado sin red pasa a Por entregar sin esperar a la red`() = runTest(dispatcher) {
        respuesta = Result.success(lista(porPagar = listOf(folio("f1"))))
        guardada = respuesta.getOrThrow() // R104: la página 1 queda como copia (así lo hace el repositorio); sin red se cobra de ella
        val vm = armar()
        vm.refrescar()
        coEvery { repo.saldoDelPedido("f1") } returns Result.failure(IOException("sin red"))
        vm.cobrarFolio(folio())
        advanceUntilIdle()
        assertTrue(vm.cobroAbierto.value != null)

        // El cobro guardó el efectivo en la cola; la red está «conectada sin datos»: nada contesta.
        filas = listOf(fila("f1", "PENDING"))
        compuerta = CompletableDeferred()
        vm.cobroTerminado()
        runCurrent()

        assertEquals("ya está en «Por entregar» aunque la bandeja no haya contestado", mapOf("f1" to 13400L), vm.cobradosEnElAparato.value)
        compuerta!!.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `al abrir con lo guardado lo cobrado sin red ya sale en Por entregar antes de la red`() = runTest(dispatcher) {
        guardada = lista(porPagar = listOf(folio("f1"), folio("f2", "W9D5F", "Pau")))
        filas = listOf(fila("f2", "PENDING"))
        compuerta = CompletableDeferred()
        val vm = armar()

        val abriendo = launch { vm.descubrirPorPagar() }
        runCurrent()

        assertEquals(mapOf("f2" to 13400L), vm.cobradosEnElAparato.value)
        compuerta!!.complete(Unit)
        abriendo.join()
    }

    @Test
    fun `si este aparato cobro el folio no dice que lo cobro otra caja`() = runTest(dispatcher) {
        // m7: la fila de este aparato ya subió (SYNCED) y la lista sigue vieja.
        val pagadoA = Instant.parse("2026-11-06T18:21:00Z")
        filas = listOf(fila("f1", "SYNCED"))
        coEvery { repo.saldoDelPedido("f1") } returns Result.success(SaldoDelPedido(0L, estadoDeLaCuenta = "CONFIRMED", pagadoA = pagadoA))
        val vm = armar()

        vm.cobrarFolio(folio())
        advanceUntilIdle()

        assertNull(vm.cobroAbierto.value)
        assertEquals(
            "Este folio ya se pagó. Se cobró en esta caja a las ${horaDelNegocio(pagadoA)}. Ya está en «Por entregar».",
            vm.aviso.value,
        )
    }

    // MARK: - R67: «Pagado a las HH:MM»

    @Test
    fun `Por entregar dice a que hora se pago si el servidor lo manda`() {
        val pagadoA = Instant.parse("2026-11-06T18:21:00Z")
        assertEquals("Pagado a las ${horaDelNegocio(pagadoA)}", textoDePago(folio().copy(paidAt = pagadoA)))
        assertNull("sin `paidAt` (SERVIDOR viejo), como hoy", textoDePago(folio()))
    }
}

/** Las rutas que usa A3 contra un servidor HTTP de verdad (MockWebServer). */
class CobroDeFolioRepositorioTest {

    private val server = MockWebServer()
    private val secureStorage = mockk<SecureStorage>()
    private val cache = mockk<PayloadCache>(relaxed = true)
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

    @Test
    fun `Cancelar folio con dinero devuelve el texto de ORDER_HAS_PAYMENTS`() = runTest {
        val texto = "El pedido ya tiene un cobro, o uno en curso en la terminal. Espera a que termine; si ya se cobró, usa el reembolso de la cuenta."
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"success":false,"code":"ORDER_HAS_PAYMENTS","error":"$texto"}"""))

        assertEquals(AccionResultado.Error(texto), repo.cancel("f1"))
        val pedido = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", pedido.method)
        assertEquals("/api/v1/mobile/venues/v1/online-orders/f1/cancel", pedido.path)
    }

    @Test
    fun `releer el folio trae el estado de la cuenta y la hora del ultimo cobro`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"success":true,"order":{"id":"f1","status":"CONFIRMED","paymentStatus":"PAID",
            "preBillBalance":{"orderTotalCents":13400,"totalPaidCents":13400,"remainingBalanceCents":0,"tipAmountCents":0},
            "payments":[{"id":"p1","amount":50,"status":"COMPLETED","createdAt":"2026-11-06T18:05:00.000Z"},
            {"id":"p2","amount":84,"status":"COMPLETED","createdAt":"2026-11-06T18:21:00.000Z"},
            {"id":"p3","amount":-84,"status":"COMPLETED","createdAt":"2026-11-06T18:40:00.000Z"},
            {"id":"p4","amount":84,"status":"FAILED","createdAt":"2026-11-06T18:50:00.000Z"}]}}""",
        ))
        val leido = repo.saldoDelPedido("f1").getOrThrow()
        assertEquals(0L, leido.saldoCents)
        assertEquals("CONFIRMED", leido.estadoDeLaCuenta)
        assertEquals("el último cobro que entró (sin reembolsos ni fallidos)", Instant.parse("2026-11-06T18:21:00Z"), leido.pagadoA)

        // Una cuenta cancelada sin cobros: sin hora de pago.
        server.enqueue(MockResponse().setBody(
            """{"success":true,"order":{"id":"f1","status":"CANCELLED","preBillBalance":{"orderTotalCents":13400,"totalPaidCents":0,"remainingBalanceCents":0,"tipAmountCents":0},"payments":[]}}""",
        ))
        val cancelada = repo.saldoDelPedido("f1").getOrThrow()
        assertEquals("CANCELLED", cancelada.estadoDeLaCuenta)
        assertNull(cancelada.pagadoA)
    }

    @Test
    fun `R67 paidAt es opcional en cada folio de to-pay`() = runTest {
        fun folio(id: String, extra: String) =
            """{"orderId":"$id","onlineOrderId":"oo-$id","orderNumber":"W8M2C","status":"ACCEPTED","paymentMode":"PAY_AT_PICKUP",
            "customerName":"Leo","customerPhone":null,"note":null,"createdAt":"2026-11-06T18:00:00.000Z","acceptDeadlineAt":null,
            "estimatedReadyAt":null,"total":69,"remaining":0,"paymentStatus":"PAID","requiresAcceptance":false,"needsPrint":false,"items":[]$extra}"""
        server.enqueue(MockResponse().setBody(
            """{"success":true,"data":{"showTile":true,"pollSeconds":30,"counts":{"toPay":0,"toDeliver":3},"toPay":[],
            "toDeliver":[${folio("e1", ",\"paidAt\":\"2026-11-06T18:21:00.000Z\"")},${folio("e2", ",\"paidAt\":null")},${folio("e3", ",\"paidAt\":\"no es fecha\"")}],
            "nextCursors":{"toPay":null,"toDeliver":null}}}""",
        ))
        val porEntregar = repo.porPagar(conLatido = false).getOrThrow().porEntregar
        assertEquals(Instant.parse("2026-11-06T18:21:00Z"), porEntregar.single { it.orderId == "e1" }.paidAt)
        assertNull(porEntregar.single { it.orderId == "e2" }.paidAt)
        assertNull("una fecha rota no tumba el folio", porEntregar.single { it.orderId == "e3" }.paidAt)
    }
}
