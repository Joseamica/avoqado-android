package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.filaAlReemplazar
import com.avoqado.pos.payment.data.CapturaDelCobro
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.payment.data.CashPaymentResult
import com.avoqado.pos.payment.data.OnlineTerminal
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.ColaDelCobro
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.OrderData
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.domain.CancelacionDeCobro
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CambioDePrecio
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * 🔴 D2 v7 (24-oct-2026, `docs/audit/2026-10-24-cobro-rechazado-sin-confirmar/promesa.md`): los cobros en efectivo dejan de
 * quedar «sin confirmar», al estilo Toast. El aparato NUNCA borra un apartado (`EN_VUELO`) por lo que contestó la red: lo borra
 * una ACCIÓN de la cajera sobre ESA venta («Cancelar», o volver a cobrarla con otra llave); lo que nadie resolvió se da por
 * cobrado y sube solo con su misma llave.
 *
 * ViewModel y `CashPaymentRepository` REALES sobre un disco en memoria que imita la SQL de producción (`borrarSiEnVuelo` no
 * borra; `descartarReservaRechazada` sólo un `EN_VUELO` con captura; `enVueloAPendiente` sólo un `EN_VUELO` con captura y sin
 * marca). La SQL de verdad, con Room, la fija `ApartadosDelEfectivoRoomTest` (escritorio, mismo DAO).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CobroRechazadoSinConfirmarTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    /** Lo que el aparato tendría en `pending_payments`, con las reglas de la SQL real. */
    private inner class Disco {
        val filas = linkedMapOf<String, PendingPaymentEntity>()
        var fallaAlGuardar = false
        var fallaAlBorrar = false
        var fallaAlEncolar = false
        /** El encolado sin red (`queueCashPayment`: la fila pasa a PENDING con su llave) truena. */
        var fallaAlGuardarPendiente = false
        /** Un borrado que se queda esperando (para cruzar otra venta en medio). */
        var compuertaDelBorrado: CompletableDeferred<Unit>? = null
        var borrados = 0

        private fun guardar(fila: PendingPaymentEntity) {
            if (fallaAlGuardar) throw IOException("disco lleno")
            if (fallaAlGuardarPendiente && fila.syncStatus == "PENDING") throw IOException("disco lleno al encolar")
            val vieja = filas[fila.id]
            if (vieja?.syncStatus == "SYNCED") return
            filas[fila.id] = filaAlReemplazar(vieja, fila)
        }

        private fun descartar(id: String): Int {
            if (fallaAlBorrar) throw IOException("disco lleno")
            val fila = filas[id] ?: return 0
            if (fila.syncStatus != "EN_VUELO" || fila.q15CaptureJson == null || fila.q15Reason != null) return 0
            if (fila.method != "CASH" || fila.tenderTypeId != null) return 0 // ronda final (A5): sólo efectivo puro
            filas.remove(id); borrados++; return 1
        }

        val dao = mockk<PendingPaymentDao>(relaxed = true).also { dao ->
            coEvery { dao.insert(any()) } coAnswers { guardar(firstArg()) }
            // La transacción real: o se borran los viejos Y se guarda el nuevo, o nada.
            coEvery { dao.reservarEnLugarDe(any(), any()) } coAnswers {
                if (fallaAlGuardar) throw IOException("disco lleno")
                secondArg<Collection<String>>().forEach { descartar(it) }
                guardar(firstArg())
            }
            coEvery { dao.borrarSiEnVuelo(any()) } returns 0 // `AND 0`: nunca borra
            coEvery { dao.descartarReservaRechazada(any()) } coAnswers { compuertaDelBorrado?.await(); descartar(firstArg()) }
            coEvery { dao.enVueloAPendiente(any()) } coAnswers {
                if (fallaAlEncolar) throw IOException("disco lleno")
                val fila = filas[firstArg()]
                if (fila == null || fila.syncStatus != "EN_VUELO" || fila.q15CaptureJson == null || fila.q15Reason != null) 0
                else { filas[fila.id] = fila.copy(syncStatus = "PENDING"); 1 }
            }
            coEvery { dao.findForVenue(any(), any()) } coAnswers { filas[secondArg<String>()]?.takeIf { it.venueId == firstArg<String>() } }
            coEvery { dao.actualizarApartadoEnVuelo(any(), any(), any()) } coAnswers {
                val f = filas[firstArg()]?.takeIf { it.syncStatus == "EN_VUELO" } ?: return@coAnswers 0
                filas[f.id] = f.copy(amountCents = secondArg(), orderId = thirdArg()); 1
            }
            coEvery { dao.enVueloDelVenue(any()) } coAnswers { filas.values.filter { it.venueId == firstArg<String>() && it.syncStatus == "EN_VUELO" } }
            every { dao.observarEstado(any()) } answers { flowOf(filas[firstArg<String>()]?.syncStatus) }
            every { dao.getPendingCount() } returns flowOf(0)
            every { dao.getFailedCount() } returns flowOf(0)
        }
    }

    private val disco = Disco()
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val caja = CashPaymentRepository(secureStorage, disco.dao)
    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val terminalPaymentService = mockk<TerminalPaymentService>(relaxed = true)
    private val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true)
    private val cashDrawerRepository = mockk<CashDrawerRepository>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)
    private val sincronizacion = mockk<PaymentSyncService>(relaxed = true)
    private lateinit var vm: PaymentFlowViewModel

    private fun nuevoVm(cola: PaymentSyncService = sincronizacion) = PaymentFlowViewModel(
        tableSession = com.avoqado.pos.tables.data.TableSession(),
        syncOutbox = mockk(relaxed = true),
        orderRepository = orderRepository,
        cashPaymentRepository = caja,
        tenderTypeRepository = mockk(relaxed = true),
        terminalPaymentService = terminalPaymentService,
        tpvSettingsRepository = tpvSettingsRepository,
        paymentSyncService = cola,
        cashDrawerRepository = cashDrawerRepository,
        kdsRepository = mockk(relaxed = true),
        kdsOrderBus = mockk(relaxed = true),
        printerService = mockk(relaxed = true),
        secureStorage = secureStorage,
        comandaDispatcher = mockk(relaxed = true),
        comandasPendientesStore = ComandasPendientesStore(AlmacenEnMemoria()),
        replayDeComandas = mockk(relaxed = true),
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
        areaTicketRepository = areaTicketRepository,
        cancelacionDeCobro = mockk(relaxed = true),
        savedStateHandle = androidx.lifecycle.SavedStateHandle(),
    )

    @Before
    fun setup() {
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = false)
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns
            TerminalListResult.Success(listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1", isOnline = true, hasSocket = true)))
        every { terminalPaymentService.intentoEnVuelo() } returns null
        every { terminalPaymentService.contextoDe(any()) } returns null
        every { terminalPaymentService.pendienteDeLaVenta(any()) } returns null
        coEvery { cashDrawerRepository.getOpenSession() } returns null
        coEvery { cashDrawerRepository.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) } returns null
        every { secureStorage.venueId } returns VENUE
        every { secureStorage.userId } returns "user-1"
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-1"
        every { areaTicketRepository.session.current() } returns null
        vm = nuevoVm()
    }

    // MARK: - Andamiaje

    private fun otroImporte(cents: Int = 10_000) = CartState(
        items = listOf(CartItem(id = "linea", type = CartItemType.CustomAmount, name = "Otro importe", unitPrice = cents)),
    )

    private val catalogo = "2026-10-17T15:00:00.000Z"
    private fun cafe(id: String) = CartItem(id = id, type = CartItemType.ProductItem("p1"), name = "Café", unitPrice = 5000, catalogAt = catalogo)
    private val dosCafes = CartState(items = listOf(cafe("e1"), cafe("e2")))

    /** `Result` es una clase inline y no puede ir en un `vararg`: va en una lista. */
    private fun fastResponde(r: List<Result<OrderRepository.CashPayResult>>) {
        coEvery { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returnsMany r
    }

    private fun pagado() = Result.success(OrderRepository.CashPayResult(paymentId = "pago-1", receiptAccessKey = null))
    private fun rechazo400() = Result.failure<OrderRepository.CashPayResult>(OrderRepository.ServerException(400, "Esta cuenta está cancelada, abre una nueva."))

    /** Una fila `EN_VUELO` sin dueño escrita directo en disco: con captura VIEJA (sin marca) o con la marca de la app nueva. */
    private fun sinDuenio(id: String, marcada: Boolean) = PendingPaymentEntity(
        id = id, venueId = VENUE, staffId = "staff-1", amountCents = 10_000, tipCents = 0, method = "CASH", paymentType = "FAST",
        syncStatus = "EN_VUELO",
        q15CaptureJson = ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(VENUE, id, amountCents = 10_000, tipCents = 0, method = "CASH",
            daPorCobradaAlAbandonar = true.takeIf { marcada })),
    ).also { disco.filas[id] = it }

    private fun marca(id: String) = ConciliacionDelCobro.leerCaptura(disco.filas[id]?.q15CaptureJson, VENUE, id)?.daPorCobradaAlAbandonar

    /** Las filas que siguen `EN_VUELO` (los apartados). */
    private fun enVuelo() = disco.filas.values.filter { it.syncStatus == "EN_VUELO" }.map { it.id }

    /** Un cobro rápido de $100 en efectivo, de punta a punta. */
    private fun kotlinx.coroutines.test.TestScope.cobrarEnEfectivo(cents: Int = 10_000) {
        vm.confirmCashCustom(cents)
        advanceUntilIdle()
    }

    // MARK: - 409 de precio: el apartado viejo se borra cuando la cajera vuelve a cobrar, no al 409

    @Test
    fun `P1 409 de precio - la fila vieja NO se borra al 409 y se borra al guardar el apartado del nuevo intento`() = runTest {
        val llaves = mutableListOf<String>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), capture(llaves), any(), any(), capturedVenueId = any()) } returnsMany listOf(
            Result.failure(OrderRepository.ServerException(409, "El precio cambió", listOf(CambioDePrecio("e1", "p1", "Café", catalogo, 5000, 6000)))),
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 110.0))),
        )
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any()) } returns pagado()

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10_000)
        advanceUntilIdle()

        val vieja = llaves.single()
        assertEquals(PaymentFlowState.SelectingPaymentMethod(11_000), vm.state.value)
        assertEquals("el 409 no es una decisión de la cajera: el apartado sigue", listOf(vieja), enVuelo())
        assertTrue("y sigue siendo del flujo: no aparece «sin confirmar»", caja.cobrosSinConfirmar().isEmpty())
        assertEquals("ni se da por cobrado mientras la venta vive", 0, caja.darPorCobradasLasAbandonadas())

        vm.processCashPayment(11_000)
        advanceUntilIdle()

        assertNotEquals("el reintento estrena llave", vieja, llaves[1])
        assertNull("al guardar el apartado nuevo, el viejo se borra", disco.filas[vieja])
        assertEquals("sólo queda el del intento que cobró", listOf(llaves[1]), disco.filas.keys.toList())
        assertTrue(vm.state.value is PaymentFlowState.Success)
    }

    @Test
    fun `P1 409 de precio y Cancelar - borra la fila vieja antes de cerrar`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.failure(OrderRepository.ServerException(409, "El precio cambió", listOf(CambioDePrecio("e1", "p1", "Café", catalogo, 5000, 6000))))

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10_000)
        advanceUntilIdle()
        assertEquals(1, enVuelo().size)

        // La X / Esc de la selección de método (PaymentFlowScreen): el anfitrión sólo cierra si esto dice que sí.
        assertTrue(vm.descartarApartadosDeEstaVenta())
        assertTrue("Cancelar la borró", disco.filas.isEmpty())
    }

    // MARK: - Error de la API: sigue del flujo; «Cancelar» la borra; el reintento con la misma llave no borra

    @Test
    fun `P1 error de la API - la fila sigue EN_VUELO y del flujo, y Cancelar la borra`() = runTest {
        fastResponde(listOf(rechazo400()))
        vm.startPaymentFlow(otroImporte())
        cobrarEnEfectivo()

        assertTrue(vm.state.value is PaymentFlowState.Error)
        val llave = enVuelo().single()
        assertTrue("es del flujo vivo: no es «sin confirmar»", caja.cobrosSinConfirmar().isEmpty())
        assertEquals(0, caja.darPorCobradasLasAbandonadas())
        coVerify(exactly = 0) { disco.dao.descartarReservaRechazada(any()) }

        vm.cancelarVenta()
        advanceUntilIdle()

        assertNull(disco.filas[llave])
        assertTrue("la pantalla se cierra", vm.debeSalir.value)
    }

    @Test
    fun `P1 reintento con la misma llave reemplaza su propia fila y no borra nada`() = runTest {
        fastResponde(listOf(rechazo400(), pagado()))
        vm.startPaymentFlow(otroImporte())
        cobrarEnEfectivo()
        val llave = enVuelo().single()

        vm.retry()
        advanceUntilIdle()

        assertTrue(vm.state.value is PaymentFlowState.Success)
        assertEquals("la misma fila, con la misma llave", listOf(llave), disco.filas.keys.toList())
        assertEquals("nada se borró", 0, disco.borrados)
        coVerify(exactly = 0) { disco.dao.reservarEnLugarDe(any(), any()) }
    }

    @Test
    fun `P1 reintento con la misma llave cuyo guardado falla - la fila anterior sigue del flujo`() = runTest {
        fastResponde(listOf(rechazo400()))
        vm.startPaymentFlow(otroImporte())
        cobrarEnEfectivo()
        val llave = enVuelo().single()

        disco.fallaAlGuardar = true
        vm.retry()
        advanceUntilIdle()

        assertTrue((vm.state.value as PaymentFlowState.Error).message.contains("No se pudo guardar el cobro"))
        assertEquals(listOf(llave), enVuelo())
        assertTrue("sigue del flujo: ni «sin confirmar» ni se da por cobrada", caja.cobrosSinConfirmar().isEmpty())
        assertEquals(0, caja.darPorCobradasLasAbandonadas())
    }

    // MARK: - Con el POST de efectivo en vuelo no hay «Cancelar»

    @Test
    fun `P1 Cancelar con el POST del efectivo en vuelo se ignora - ni borra ni cancela`() = runTest {
        val respuesta = CompletableDeferred<Result<OrderRepository.CashPayResult>>()
        coEvery { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } coAnswers { respuesta.await() }
        vm.startPaymentFlow(otroImporte())
        vm.confirmCashCustom(10_000)
        advanceUntilIdle()

        val enProceso = vm.state.value as PaymentFlowState.Processing
        assertTrue("la pantalla no ofrece «Cancelar»", enProceso.efectivoEnVuelo)
        vm.cancelarVenta()
        assertFalse(vm.descartarApartadosDeEstaVenta())
        advanceUntilIdle()
        assertEquals(enProceso, vm.state.value)
        assertFalse(vm.debeSalir.value)
        assertEquals(1, enVuelo().size)

        respuesta.complete(pagado())
        advanceUntilIdle()
        assertTrue(vm.state.value is PaymentFlowState.Success)
    }

    // MARK: - 2xx con el guardado local fallido = cobrado

    @Test
    fun `P1 2xx con el guardado local fallido - pasa a PENDING con su llave y la pantalla dice cobrado encolado`() = runTest {
        fastResponde(listOf(Result.failure<OrderRepository.CashPayResult>(OrderRepository.CobroRegistradoSinGuardar(IllegalStateException("disco")))))
        vm.startPaymentFlow(otroImporte())
        cobrarEnEfectivo()

        val llave = disco.filas.keys.single()
        val exito = vm.state.value as PaymentFlowState.Success
        assertTrue(exito.isQueued)
        assertEquals(ColaDelCobro.Pagos(llave), exito.colaDelCobro)
        assertEquals("PENDING", disco.filas[llave]?.syncStatus)
        assertTrue("«Cancelar» ya no tiene nada que borrar", vm.descartarApartadosDeEstaVenta())
        assertEquals("PENDING", disco.filas[llave]?.syncStatus)
    }

    @Test
    fun `P1 2xx con guardado y encolado fallidos - queda EN_VUELO sin duenio y se da por cobrada, nunca se borra`() = runTest {
        fastResponde(listOf(Result.failure<OrderRepository.CashPayResult>(OrderRepository.CobroRegistradoSinGuardar(IllegalStateException("disco")))))
        disco.fallaAlEncolar = true
        vm.startPaymentFlow(otroImporte())
        cobrarEnEfectivo()

        val llave = enVuelo().single()
        assertTrue((vm.state.value as PaymentFlowState.Success).isQueued)
        vm.cancelarVenta()
        advanceUntilIdle()
        assertEquals("ni «Cancelar» la borra", listOf(llave), enVuelo())

        disco.fallaAlEncolar = false
        assertEquals("la cola la da por cobrada con su misma llave", 1, caja.darPorCobradasLasAbandonadas())
        assertEquals("PENDING", disco.filas[llave]?.syncStatus)
    }

    // MARK: - El borrado de «Cancelar» se espera; si falla, no se cierra

    @Test
    fun `P1 borrado fallido al cancelar - dice No se pudo cancelar, no se cierra y la fila sigue del flujo`() = runTest {
        fastResponde(listOf(rechazo400()))
        vm.startPaymentFlow(otroImporte())
        cobrarEnEfectivo()
        val llave = enVuelo().single()
        val error = vm.state.value

        disco.fallaAlBorrar = true
        vm.cancelarVenta()
        advanceUntilIdle()

        assertEquals("No se pudo cancelar el cobro. Intenta de nuevo.", vm.cancelFailure.value)
        assertEquals(CancelacionDeCobro.NO_SE_PUDO_CANCELAR_EL_COBRO, vm.cancelFailure.value)
        assertFalse("no se cierra", vm.debeSalir.value)
        assertEquals(error, vm.state.value)
        assertEquals(listOf(llave), enVuelo())
        assertTrue("sigue del flujo", caja.cobrosSinConfirmar().isEmpty())
        assertFalse("la X de la selección tampoco cierra", vm.descartarApartadosDeEstaVenta())

        disco.fallaAlBorrar = false
        vm.cancelarVenta()
        advanceUntilIdle()
        assertNull(disco.filas[llave])
        assertTrue(vm.debeSalir.value)
    }

    // MARK: - Sin decisión de la cajera nunca se borra: se suelta y se da por cobrado

    @Test
    fun `P1 onCleared no borra - la fila queda sin duenio y se da por cobrada con su llave`() = runTest {
        fastResponde(listOf(rechazo400()))
        vm.startPaymentFlow(otroImporte())
        cobrarEnEfectivo()
        val llave = enVuelo().single()

        androidx.lifecycle.ViewModelStore().apply { put("cobro", vm) }.clear()

        assertEquals(listOf(llave), enVuelo())
        assertEquals(1, caja.darPorCobradasLasAbandonadas())
        assertEquals("PENDING", disco.filas[llave]?.syncStatus)
        assertEquals(0, disco.borrados)
    }

    @Test
    fun `P1 empezar otra venta en el mismo ViewModel no borra - la anterior se suelta, se da por cobrada y se avisa a la cola`() = runTest {
        fastResponde(listOf(rechazo400()))
        vm.startPaymentFlow(otroImporte(), apertura = "cobro-1")
        cobrarEnEfectivo()
        val llave = enVuelo().single()

        // Recomponer la MISMA apertura (girar la tablet) no es otra venta: sigue del flujo.
        vm.startPaymentFlow(otroImporte(), apertura = "cobro-1")
        assertEquals(0, caja.darPorCobradasLasAbandonadas())

        // Otra apertura (otra venta, o la pantalla recreada sin su apertura): se suelta.
        vm.startPaymentFlow(otroImporte(5_000), apertura = "cobro-2")

        assertEquals(listOf(llave), enVuelo())
        verify { sincronizacion.syncNow() }
        assertEquals(1, caja.darPorCobradasLasAbandonadas())
        assertEquals("PENDING", disco.filas[llave]?.syncStatus)
        assertEquals(0, disco.borrados)
    }

    @Test
    fun `P1 empezar otra venta con CARRITO (orden y pay) tambien suelta la anterior y se da por cobrada`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 100.0)))
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any()) } returns rechazo400()
        vm.startPaymentFlow(dosCafes, apertura = "carrito-1")
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10_000)
        advanceUntilIdle()
        assertTrue(vm.state.value is PaymentFlowState.Error)
        val llave = enVuelo().single()
        assertEquals("del flujo vivo", 0, caja.darPorCobradasLasAbandonadas())

        vm.startPaymentFlow(dosCafes, apertura = "carrito-2")

        assertEquals(1, caja.darPorCobradasLasAbandonadas())
        assertEquals("PENDING", disco.filas[llave]?.syncStatus)
        assertEquals(0, disco.borrados)
    }

    // MARK: - El barrido sólo toma lo que apartó la app nueva (decisión del coordinador, 24-oct)

    @Test
    fun `P1 el barrido no toca una fila sin duenio con captura VIEJA y si la que trae la marca`() = runTest {
        sinDuenio("de-la-app-de-antes", marcada = false)
        sinDuenio("de-la-app-nueva", marcada = true)

        assertEquals(1, caja.darPorCobradasLasAbandonadas())

        assertEquals("se queda «sin confirmar», como hoy", "EN_VUELO", disco.filas["de-la-app-de-antes"]?.syncStatus)
        assertEquals("pasa a la cola con su MISMA llave", "PENDING", disco.filas["de-la-app-nueva"]?.syncStatus)
        assertEquals(listOf("de-la-app-de-antes"), caja.cobrosSinConfirmar().map { it.id })
    }

    @Test
    fun `P1 la reserva marca su captura y un reintento con la misma llave conserva la marca`() = runTest {
        fastResponde(listOf(rechazo400(), rechazo400()))
        vm.startPaymentFlow(otroImporte())
        cobrarEnEfectivo()
        val llave = enVuelo().single()
        assertEquals(true, marca(llave))

        vm.retry()
        advanceUntilIdle()
        assertEquals(listOf(llave), enVuelo())
        assertEquals("la captura de disco (y su marca) manda en el reemplazo", true, marca(llave))
    }

    @Test
    fun `P1 si pasar la fila a la cola falla, se queda EN_VUELO y la siguiente vuelta la sube`() = runTest {
        sinDuenio("abandonada", marcada = true)
        disco.fallaAlEncolar = true
        assertEquals(0, caja.darPorCobradasLasAbandonadas())
        assertEquals("EN_VUELO", disco.filas["abandonada"]?.syncStatus)

        disco.fallaAlEncolar = false
        assertEquals(1, caja.darPorCobradasLasAbandonadas())
        assertEquals("PENDING", disco.filas["abandonada"]?.syncStatus)
    }

    /** Una cola real (sin red) sobre el mismo disco; [vuelta] = lo que tarda su cajón (para dejar una vuelta corriendo). */
    private fun kotlinx.coroutines.test.TestScope.colaReal(vuelta: (suspend () -> Boolean)? = null): PaymentSyncService {
        val red = mockk<com.avoqado.pos.core.util.ConnectivityMonitor>(relaxed = true)
        every { red.isConnected } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        every { red.isServerReachable } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        val cajon = mockk<CashDrawerRepository>(relaxed = true)
        coEvery { cajon.sincronizarCajonPrimero() } coAnswers { vuelta?.invoke() ?: true }
        every { cajon.cierreQueEspera() } returns null
        every { cajon.cajasQueEsperanSuApertura() } returns emptySet()
        return PaymentSyncService(disco.dao, secureStorage, mockk(relaxed = true), red, cajon, mockk(relaxed = true), caja)
            .also { it.usarContextoDeSincronizacion(StandardTestDispatcher(testScheduler)) }
    }

    // MARK: - Ronda final (revisión final + Codex r8)

    /**
     * Rediseño r9: con apartado de efectivo puro la venta en línea NO la anota el ViewModel; la anota la salida de `EN_VUELO` del 2xx,
     * con el id de su llave (`SalidaDelApartadoRoomTest`, `CashDrawerVentaDadaPorCobradaTest`). Antes la anotaba aquí (ronda final B).
     */
    @Test
    fun `P1 la venta en efectivo en linea con apartado no la anota el ViewModel`() = runTest {
        fastResponde(listOf(pagado()))
        vm.startPaymentFlow(otroImporte())
        cobrarEnEfectivo()
        assertEquals(1, disco.filas.size)
        coVerify(exactly = 0) { cashDrawerRepository.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) }
    }

    /** A1 (Codex r8 #1): un intento VIEJO que estaba suspendido no borra los apartados de la venta nueva. */
    @Test
    fun `P1 un intento viejo suspendido no borra el apartado de la venta nueva`() = runTest {
        val cajaDeA = CompletableDeferred<Unit>()
        var lecturas = 0
        coEvery { cashDrawerRepository.getOpenSession() } coAnswers { if (++lecturas == 1) cajaDeA.await(); null }
        fastResponde(listOf(rechazo400(), rechazo400()))
        vm.startPaymentFlow(otroImporte(10_000), apertura = "venta-A")
        vm.confirmCashCustom(10_000)
        advanceUntilIdle() // A espera la lectura de su caja

        vm.startPaymentFlow(otroImporte(5_000), apertura = "venta-B")
        vm.confirmCashCustom(5_000)
        advanceUntilIdle()
        val b = enVuelo().single()

        cajaDeA.complete(Unit) // A sigue: aparta su efectivo
        advanceUntilIdle()

        assertEquals("EN_VUELO", disco.filas[b]?.syncStatus)
        assertEquals("nadie borró nada", 0, disco.borrados)
        assertTrue("B sigue del flujo", caja.cobrosSinConfirmar().none { it.id == b })
        // Lo de A se soltó (su venta ya no está en pantalla) y se pidió el barrido en el acto.
        verify { sincronizacion.darPorCobradasLasAbandonadasYa() }
    }

    /** A2 (Codex r8 #6): sin red y con el encolado que truena, la cajera ve el error del efectivo y «Cancelar» lo borra. */
    @Test
    fun `P1 sin red y el encolado truena - error del efectivo, la fila sigue del flujo y Cancelar la borra`() = runTest {
        fastResponde(listOf(Result.failure(IOException("timeout"))))
        disco.fallaAlGuardarPendiente = true
        vm.startPaymentFlow(otroImporte())
        cobrarEnEfectivo()

        assertTrue("no se queda Procesando ni truena", vm.state.value is PaymentFlowState.Error)
        val llave = enVuelo().single()
        assertTrue("sigue del flujo", caja.cobrosSinConfirmar().isEmpty())

        vm.cancelarVenta()
        advanceUntilIdle()
        assertNull(disco.filas[llave])
        assertTrue(vm.debeSalir.value)
    }

    /** A3 (Codex r8 #5): el error tardío de CREAR LA ORDEN de la venta A no cierra la venta B. */
    @Test
    fun `P1 un error tardio al crear la orden de la venta anterior no cierra la venta nueva`() = runTest {
        val ordenDeA = CompletableDeferred<Result<CreateOrderResponse>>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } coAnswers { ordenDeA.await() }
        fastResponde(listOf(rechazo400()))
        vm.startPaymentFlow(dosCafes, apertura = "venta-A")
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10_000)
        advanceUntilIdle()
        vm.startPaymentFlow(otroImporte(5_000), apertura = "venta-B")
        vm.confirmCashCustom(5_000)
        advanceUntilIdle()
        val error = vm.state.value
        assertTrue(error is PaymentFlowState.Error)

        ordenDeA.complete(Result.failure(OrderRepository.ServerException(400, "Producto inválido")))
        advanceUntilIdle()

        assertEquals(error, vm.state.value)
        assertFalse("la venta B no se cierra", vm.debeSalir.value)
    }

    /** A3: el éxito tardío de crear la orden de A no le pone esa orden ni una cancelación a la venta B. */
    @Test
    fun `P1 un exito tardio al crear la orden de la venta anterior no toca la venta nueva`() = runTest {
        val ordenDeA = CompletableDeferred<Result<CreateOrderResponse>>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } coAnswers { ordenDeA.await() }
        fastResponde(listOf(rechazo400()))
        vm.startPaymentFlow(dosCafes, apertura = "venta-A")
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10_000)
        advanceUntilIdle()
        vm.startPaymentFlow(otroImporte(5_000), apertura = "venta-B")
        vm.confirmCashCustom(5_000)
        advanceUntilIdle()
        val error = vm.state.value

        ordenDeA.complete(Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-A", total = 100.0))))
        advanceUntilIdle()

        assertEquals("ni éxito ni cancelación sobre B", error, vm.state.value)
        assertNull("la orden de A no es de B", vm.buildCompletion().orderId)
        coVerify(exactly = 0) { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any()) }
    }

    /** A5 (revisión I-B, espejo de iOS `testYaPagoDeOtraFormaSigueComoHoy`): «Ya pagó de otra forma» rechazado sigue como hoy. */
    @Test
    fun `P1 Ya pago de otra forma rechazado se suelta, Cancelar no lo borra y el barrido no lo toma`() = runTest {
        fastResponde(listOf(rechazo400()))
        vm.startPaymentFlow(otroImporte())
        vm.confirmManualChoice(com.avoqado.pos.payment.domain.ManualPaymentChoice.Fixed(com.avoqado.pos.payment.domain.ManualPaymentMethod.TRANSFER))
        advanceUntilIdle()
        assertTrue(vm.state.value is PaymentFlowState.Error)
        val llave = enVuelo().single()

        assertEquals("se suelta: queda «sin confirmar», como hoy", listOf(llave), caja.cobrosSinConfirmar().map { it.id })
        vm.cancelarVenta()
        advanceUntilIdle()
        assertEquals("«Cancelar» no lo borra", listOf(llave), enVuelo())
        assertEquals("el barrido no lo toma", 0, caja.darPorCobradasLasAbandonadas())
    }

    /** M-1 (espejo de iOS): tras «No se pudo cancelar», «Reintentar» reintenta la CANCELACIÓN, nunca el cobro. */
    @Test
    fun `P1 Reintentar tras No se pudo cancelar reintenta la cancelacion y no vuelve a cobrar`() = runTest {
        fastResponde(listOf(rechazo400(), pagado()))
        vm.startPaymentFlow(otroImporte())
        cobrarEnEfectivo()
        val llave = enVuelo().single()
        disco.fallaAlBorrar = true
        vm.cancelarVenta()
        advanceUntilIdle()
        assertEquals(CancelacionDeCobro.NO_SE_PUDO_CANCELAR_EL_COBRO, vm.cancelFailure.value)

        vm.retry()
        advanceUntilIdle()
        coVerify(exactly = 1) { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) }
        assertFalse(vm.debeSalir.value)

        disco.fallaAlBorrar = false
        vm.retry()
        advanceUntilIdle()
        coVerify(exactly = 1) { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) }
        assertNull(disco.filas[llave])
        assertTrue("ahora sí se cancela", vm.debeSalir.value)
    }

    /** C: I3 no re-entra si mientras borraba empezó otra venta. */
    @Test
    fun `P1 si otra venta empieza mientras se borra el efectivo, la tarjeta de la anterior no sale`() = runTest {
        fastResponde(listOf(rechazo400()))
        vm.startPaymentFlow(otroImporte(), apertura = "venta-1")
        cobrarEnEfectivo()
        vm.selectPaymentMethod(PaymentMethod.CARD)
        val compuerta = CompletableDeferred<Unit>()
        disco.compuertaDelBorrado = compuerta
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        vm.startPaymentFlow(otroImporte(5_000), apertura = "venta-2")
        vm.selectPaymentMethod(PaymentMethod.CARD) // la venta 2 también va a tarjeta, pero todavía no elige terminal
        val antes = vm.state.value
        compuerta.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 0) { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) }
        assertEquals("el cobro de la venta 1 no se reanuda sobre la venta 2", antes, vm.state.value)
    }

    /** C: `onCleared` con sólo borrados pendientes (`porDescartar`) también pide el barrido en el acto. */
    @Test
    fun `P1 onCleared con solo borrados pendientes pide el barrido en el acto`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 100.0)))
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any()) } returnsMany
            listOf(rechazo400(), pagado())
        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10_000) // el /pay rechaza: queda su apartado
        advanceUntilIdle()
        assertEquals(1, enVuelo().size)
        // Se cobra por «Confirmar» (sin apartado propio) y el borrado del apartado viejo no se puede escribir: queda en porDescartar.
        disco.fallaAlBorrar = true
        vm.confirmPayment()
        advanceUntilIdle()
        assertTrue(vm.state.value is PaymentFlowState.Success)
        io.mockk.clearMocks(sincronizacion, answers = false)

        androidx.lifecycle.ViewModelStore().apply { put("cobro", vm) }.clear()

        verify { sincronizacion.darPorCobradasLasAbandonadasYa() }
    }

    // MARK: - Ronda 1 (revisión del 24-oct)

    /** M4: soltar (otra venta, pantalla recreada, `onCleared`) da por cobrado EN EL ACTO, aunque ya haya una vuelta corriendo. */
    @Test
    fun `P1 al soltar, lo soltado se da por cobrado en el acto aunque haya una vuelta de la cola corriendo`() = runTest {
        val vuelta = CompletableDeferred<Boolean>()
        val cola = colaReal { vuelta.await() }
        val conCola = nuevoVm(cola)
        try {
            cola.syncNow()
            runCurrent() // la vuelta se queda esperando a su cajón
            fastResponde(listOf(rechazo400()))
            conCola.startPaymentFlow(otroImporte(), apertura = "venta-1")
            conCola.confirmCashCustom(10_000)
            runCurrent()
            val llave = enVuelo().single()

            conCola.startPaymentFlow(otroImporte(), apertura = "venta-2")
            runCurrent()

            assertEquals("no espera a la siguiente vuelta", "PENDING", disco.filas[llave]?.syncStatus)
        } finally {
            vuelta.complete(true)
            runCurrent()
            cola.stop()
        }
    }

    /** I2: un `Success` tardío de la venta ANTERIOR no pinta pagada la venta nueva ni le quita su apartado. */
    @Test
    fun `P1 un Success tardio de la venta anterior no pinta pagada la venta nueva ni suelta su apartado`() = runTest {
        val venta1 = CompletableDeferred<Result<OrderRepository.CashPayResult>>()
        var llamadas = 0
        coEvery { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } coAnswers {
            if (++llamadas == 1) venta1.await() else rechazo400()
        }
        vm.startPaymentFlow(otroImporte(10_000), apertura = "venta-1")
        vm.confirmCashCustom(10_000)
        advanceUntilIdle()
        assertTrue(vm.state.value is PaymentFlowState.Processing)

        vm.startPaymentFlow(otroImporte(5_000), apertura = "venta-2")
        vm.confirmCashCustom(5_000)
        advanceUntilIdle()
        val error = vm.state.value
        assertTrue(error is PaymentFlowState.Error)
        val b = disco.filas.values.single { it.amountCents == 5_000 }.id

        venta1.complete(pagado())
        advanceUntilIdle()

        assertEquals("la venta 2 no se pinta pagada", error, vm.state.value)
        assertEquals("EN_VUELO", disco.filas[b]?.syncStatus)
        assertTrue("B sigue del flujo", caja.cobrosSinConfirmar().none { it.id == b })
        caja.darPorCobradasLasAbandonadas()
        assertEquals("ni el barrido la toca", "EN_VUELO", disco.filas[b]?.syncStatus)
        // Rediseño r9: el cajón de la venta 1 lo registra su salida de `EN_VUELO` (aquí el repositorio de órdenes es de mentira), no el VM.
        coVerify(exactly = 0) { cashDrawerRepository.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) }
    }

    /** Ronda 1b: lo mismo por el `/pay` legado de «Confirmar» (rama de efectivo de `processPaymentMethod`). */
    @Test
    fun `P1 un Success tardio del pay legado de la venta anterior no pinta pagada la venta nueva ni suelta su apartado`() = runTest {
        val venta1 = CompletableDeferred<Result<OrderRepository.CashPayResult>>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 100.0)))
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any()) } coAnswers { venta1.await() }
        fastResponde(listOf(rechazo400()))
        vm.startPaymentFlow(dosCafes, apertura = "venta-1")
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.confirmPayment()
        advanceUntilIdle()
        assertTrue(vm.state.value is PaymentFlowState.Processing)

        vm.startPaymentFlow(otroImporte(5_000), apertura = "venta-2")
        vm.confirmCashCustom(5_000)
        advanceUntilIdle()
        val error = vm.state.value
        assertTrue(error is PaymentFlowState.Error)
        val b = enVuelo().single()

        venta1.complete(pagado())
        advanceUntilIdle()

        assertEquals("la venta 2 no se pinta pagada", error, vm.state.value)
        assertEquals("EN_VUELO", disco.filas[b]?.syncStatus)
        assertTrue("B sigue del flujo", caja.cobrosSinConfirmar().none { it.id == b })
        assertEquals("ni el barrido la toca", 0, caja.darPorCobradasLasAbandonadas())
        coVerify { cashDrawerRepository.addCashSale(10_000, "order-1", any(), any(), any(), llaveDelCobro = any()) } // el cajón de la venta 1 sí se registra
    }

    /** I3: mandar ESTA venta a la tarjeta es volver a cobrarla: antes de la terminal se borra el apartado del efectivo. */
    @Test
    fun `P1 mandar la venta a la tarjeta borra antes el apartado del efectivo y Salir ya no da nada por cobrado`() = runTest {
        fastResponde(listOf(rechazo400()))
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) } returns
            TerminalPaymentResult.Undetermined("No se sabe si se cobró", "req-1")
        vm.startPaymentFlow(otroImporte())
        cobrarEnEfectivo()
        val llave = enVuelo().single()

        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        coVerifyOrder {
            disco.dao.descartarReservaRechazada(llave)
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any())
        }
        assertNull(disco.filas[llave])
        vm.salirDejandoPendiente()
        vm.startPaymentFlow(otroImporte(), apertura = "siguiente")
        assertEquals("la venta va por tarjeta: el efectivo no se da por cobrado", 0, caja.darPorCobradasLasAbandonadas())
    }

    @Test
    fun `P1 si no se puede borrar el apartado, la tarjeta no sale y la pantalla lo dice`() = runTest {
        fastResponde(listOf(rechazo400()))
        vm.startPaymentFlow(otroImporte())
        cobrarEnEfectivo()
        val llave = enVuelo().single()

        vm.selectPaymentMethod(PaymentMethod.CARD)
        disco.fallaAlBorrar = true
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        coVerify(exactly = 0) { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(CancelacionDeCobro.NO_SE_PUDO_CANCELAR_EL_COBRO, vm.cancelFailure.value)
        assertEquals(listOf(llave), enVuelo())
        assertTrue("sigue del flujo", caja.cobrosSinConfirmar().isEmpty())
    }

    /** M1: el `/pay` legado de «Confirmar» con efectivo tampoco ofrece «Cancelar» mientras viaja. */
    @Test
    fun `P1 el efectivo por Confirmar (pay legado) marca el POST en vuelo`() = runTest {
        val pago = CompletableDeferred<Result<OrderRepository.CashPayResult>>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 100.0)))
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any()) } coAnswers { pago.await() }
        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.confirmPayment()
        advanceUntilIdle()

        assertTrue((vm.state.value as PaymentFlowState.Processing).efectivoEnVuelo)
        pago.complete(pagado())
        advanceUntilIdle()
        assertTrue(vm.state.value is PaymentFlowState.Success)
    }

    /** M1: el cierre en $0 de una tarjeta (premio o cortesía) es un `/pay` en efectivo: sin «Cancelar» mientras viaja. */
    @Test
    fun `P1 el cierre en cero de una tarjeta marca el POST de efectivo en vuelo`() = runTest {
        val pago = CompletableDeferred<Result<OrderRepository.CashPayResult>>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 0.0)))
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any()) } coAnswers { pago.await() }
        val cortesia = Discount(id = "cortesia_1", name = "Cortesía", value = 100.0, type = "PERCENTAGE", scope = "ORDER")
        vm.startPaymentFlow(dosCafes.copy(orderDiscount = cortesia))
        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        coVerify(exactly = 0) { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue((vm.state.value as PaymentFlowState.Processing).efectivoEnVuelo)
        pago.complete(pagado())
        advanceUntilIdle()
    }

    // MARK: - La cola da por cobradas las abandonadas al arrancar y en cada vuelta

    @Test
    fun `P1 PaymentSyncService da por cobradas las abandonadas al arrancar y en cada vuelta`() = runTest {
        sinDuenio("al-arrancar", marcada = true)
        val red = mockk<com.avoqado.pos.core.util.ConnectivityMonitor>(relaxed = true)
        every { red.isConnected } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        every { red.isServerReachable } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        val cajon = mockk<CashDrawerRepository>(relaxed = true)
        coEvery { cajon.sincronizarCajonPrimero() } returns true
        every { cajon.cierreQueEspera() } returns null
        every { cajon.cajasQueEsperanSuApertura() } returns emptySet()
        val cola = PaymentSyncService(disco.dao, secureStorage, mockk(relaxed = true), red, cajon, mockk(relaxed = true), caja)
        cola.usarContextoDeSincronizacion(StandardTestDispatcher(testScheduler))
        try {
            cola.start()
            runCurrent()
            assertEquals("al arrancar", "PENDING", disco.filas["al-arrancar"]?.syncStatus)

            sinDuenio("en-la-vuelta", marcada = true)
            sinDuenio("de-la-app-de-antes", marcada = false)
            cola.syncNow()
            runCurrent()
            assertEquals("en la siguiente vuelta", "PENDING", disco.filas["en-la-vuelta"]?.syncStatus)
            assertEquals("la vieja, como hoy", "EN_VUELO", disco.filas["de-la-app-de-antes"]?.syncStatus)
        } finally {
            cola.stop()
        }
    }

    // MARK: - La venta se cobró por otro camino: el apartado viejo sobra

    @Test
    fun `P1 efectivo rechazado y luego cobrado con tarjeta - el apartado del efectivo se borra, no se da por cobrado`() = runTest {
        fastResponde(listOf(rechazo400()))
        coEvery { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) } returns
            TerminalPaymentResult.Success(paymentId = "pago-tarjeta", requestId = "req-1")
        vm.startPaymentFlow(otroImporte())
        cobrarEnEfectivo()
        val llave = enVuelo().single()

        vm.selectPaymentMethod(PaymentMethod.CARD)
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals(PaymentMethod.CARD, (vm.state.value as PaymentFlowState.Success).method)
        assertNull("la venta ya se cobró con tarjeta: el efectivo no puede subir también", disco.filas[llave])

        vm.startPaymentFlow(otroImporte(), apertura = "siguiente")
        assertEquals(0, caja.darPorCobradasLasAbandonadas())
    }

    // MARK: - Las salidas de la pantalla pasan por la cancelación esperada

    /**
     * Guarda de CÓDIGO FUENTE (misma técnica que `CobroAnteriorResueltoGuardTest`: el módulo no tiene `compose.ui.test`).
     * ⚠️ Alcance: caza la reversión —volver a pasar el `onCancel` del anfitrión directo a la selección de método (`:198`), o
     * volver a ofrecer «Cancelar» con el efectivo en vuelo—; no prueba que el botón se VEA. Eso queda para la QA en aparato.
     */
    @Test
    fun `P1 la X de la seleccion pasa por el borrado esperado y Procesando no ofrece Cancelar con el efectivo en vuelo`() {
        val codigo = leerFuente("app/src/main/java/com/avoqado/pos/payment/presentation/PaymentFlowScreen.kt")
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("""//[^\n]*"""), " ")
            .replace(Regex("""\s+"""), "")
        assertFalse("una pantalla del cobro cierra sin borrar los apartados", codigo.contains("onCancel=onCancel,"))
        assertTrue(codigo.contains("if(viewModel.descartarApartadosDeEstaVenta())salirSinCobrar()"))
        assertTrue(codigo.contains("valsalirSinCobrar:()->Unit={viewModel.alSalirSinCobrar();onCancel()}"))
        assertTrue(codigo.contains("onCancel={viewModel.cancelarVenta()}.takeIf{(currentStateas?PaymentFlowState.Processing)?.efectivoEnVuelo!=true}"))
    }

    private fun leerFuente(rutaRelativa: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            File(dir, rutaRelativa).takeIf { it.isFile }?.let { return it.readText() }
            File(dir, rutaRelativa.removePrefix("app/")).takeIf { it.isFile }?.let { return it.readText() }
            dir = dir.parentFile
        }
        throw AssertionError("No se encontró $rutaRelativa desde ${System.getProperty("user.dir")}")
    }

    private companion object { const val VENUE = "venue-1" }
}
