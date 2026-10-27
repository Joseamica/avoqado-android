package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketCheckout
import com.avoqado.pos.areatickets.data.AreaTicketCheckoutOrder
import com.avoqado.pos.areatickets.data.AreaTicketCheckoutTotals
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.filaAlReemplazar
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.payment.data.CancelacionDeCobroCoordinator
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.IntencionDeCancelarCobro
import com.avoqado.pos.payment.data.OnlineTerminal
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.OrderData
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * 🔴 D2, rediseño r9 — familia B (Codex r9 #5, r10 #3) y los ajustes de la re-revisión (N1, N2): después de CUALQUIER suspensión, el
 * flujo de una venta no escribe en la venta que ya muestra la pantalla si no es la suya. Lo que de verdad ocurrió se conserva
 * (servidor, cola, cajón, cancelación durable). Y con apartado de efectivo puro el ViewModel ya no anota el cajón: lo hace la
 * salida de `EN_VUELO` (`SalidaDelApartado`).
 *
 * ViewModel y `CashPaymentRepository` REALES sobre un disco en memoria (como `CobroRechazadoSinConfirmarTest`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GuardaDeLaVentaTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private inner class Disco {
        val filas = linkedMapOf<String, PendingPaymentEntity>()
        var fallaAlGuardar = false
        var fallaAlBorrar = false
        /** Un guardado (de la reserva o del encolado) que se queda esperando, para cruzar otra venta en medio. */
        var compuertaDelGuardado: CompletableDeferred<Unit>? = null
        var compuertaSoloDelEncolado = false
        /** Un borrado de apartado que se queda esperando (Codex r14 B1). */
        var compuertaDelBorrado: CompletableDeferred<Unit>? = null

        private suspend fun guardar(fila: PendingPaymentEntity) {
            if (!compuertaSoloDelEncolado || fila.syncStatus == "PENDING") compuertaDelGuardado?.await()
            if (fallaAlGuardar) throw IOException("disco lleno")
            val vieja = filas[fila.id]
            if (vieja?.syncStatus == "SYNCED") return
            filas[fila.id] = filaAlReemplazar(vieja, fila)
        }

        val dao = mockk<PendingPaymentDao>(relaxed = true).also { dao ->
            coEvery { dao.insert(any()) } coAnswers { guardar(firstArg()) }
            coEvery { dao.reservarEnLugarDe(any(), any()) } coAnswers { guardar(firstArg()) }
            coEvery { dao.borrarSiEnVuelo(any()) } returns 0
            coEvery { dao.descartarReservaRechazada(any()) } coAnswers {
                compuertaDelBorrado?.await()
                if (fallaAlBorrar) throw IOException("disco lleno")
                val fila = filas[firstArg()]
                if (fila?.syncStatus == "EN_VUELO" && fila.method == "CASH" && fila.tenderTypeId == null) { filas.remove(fila.id); 1 } else 0
            }
            coEvery { dao.enVueloAPendiente(any()) } coAnswers {
                val fila = filas[firstArg()]
                if (fila?.syncStatus == "EN_VUELO" && fila.q15Reason == null) { filas[fila.id] = fila.copy(syncStatus = "PENDING"); 1 } else 0
            }
            coEvery { dao.findForVenue(any(), any()) } coAnswers { filas[secondArg<String>()]?.takeIf { it.venueId == firstArg<String>() } }
            coEvery { dao.findByIdQ15(any()) } coAnswers { filas[firstArg<String>()] }
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
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val comandaDispatcher = mockk<ComandaDispatcher>(relaxed = true)
    private val cancelacionDeCobro = mockk<CancelacionDeCobroCoordinator>(relaxed = true)
    private lateinit var vm: PaymentFlowViewModel

    private fun nuevoVm(cola: PaymentSyncService = mockk(relaxed = true), vales: AreaTicketRepository = areaTicketRepository) = PaymentFlowViewModel(
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
        printerService = printerService,
        secureStorage = secureStorage,
        comandaDispatcher = comandaDispatcher,
        comandasPendientesStore = ComandasPendientesStore(AlmacenEnMemoria()),
        replayDeComandas = mockk(relaxed = true),
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
        areaTicketRepository = vales,
        cancelacionDeCobro = cancelacionDeCobro,
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
        every { cancelacionDeCobro.registrar(any()) } returns true
        vm = nuevoVm()
    }

    private fun otroImporte(cents: Int = 10_000) = CartState(
        items = listOf(CartItem(id = "linea", type = CartItemType.CustomAmount, name = "Otro importe", unitPrice = cents)),
    )

    private val catalogo = "2026-10-17T15:00:00.000Z"
    private fun cafe(id: String) = CartItem(id = id, type = CartItemType.ProductItem("p1"), name = "Café", unitPrice = 5000, catalogAt = catalogo)
    private val dosCafes = CartState(items = listOf(cafe("e1"), cafe("e2")))

    private fun pagado() = Result.success(OrderRepository.CashPayResult(paymentId = "pago-1", receiptAccessKey = null))
    private fun ordenCreada(id: String, folio: String? = null) =
        Result.success(CreateOrderResponse(success = true, data = OrderData(id = id, orderNumber = folio, total = 100.0)))

    private fun campo(nombre: String): Any? =
        PaymentFlowViewModel::class.java.getDeclaredField(nombre).apply { isAccessible = true }.get(vm)

    // MARK: - Con apartado, el ViewModel ya no anota el cajón

    @Test
    fun `con apartado de efectivo puro el ViewModel no anota el cajon - lo hace la salida de EN_VUELO`() = runTest {
        coEvery { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns pagado()
        vm.startPaymentFlow(otroImporte())
        vm.confirmCashCustom(10_000)
        advanceUntilIdle()

        assertTrue(vm.state.value is PaymentFlowState.Success)
        coVerify(exactly = 0) { cashDrawerRepository.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) }
    }

    // MARK: - Codex r9 #5: el éxito de A tras encolar, con B ya empezada

    @Test
    fun `si otra venta empieza mientras se encola sin red el exito de la vieja no se pinta en ella`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.failure(java.net.UnknownHostException("sin red"))
        val compuerta = CompletableDeferred<Unit>()
        disco.compuertaDelGuardado = compuerta
        disco.compuertaSoloDelEncolado = true

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10_000)
        advanceUntilIdle()
        assertTrue("A espera su encolado", vm.state.value is PaymentFlowState.Processing)

        vm.startPaymentFlow(otroImporte(5_000)) // B
        val deB = vm.state.value
        compuerta.complete(Unit)
        advanceUntilIdle()

        assertEquals("el cobro de A sí quedó en la cola", listOf("PENDING"), disco.filas.values.map { it.syncStatus })
        assertFalse("B no recibe el éxito de A", vm.state.value is PaymentFlowState.Success)
        assertEquals(deB, vm.state.value)
    }

    // MARK: - Codex r9 #5: vales

    /**
     * Corrección del agregado 4: los vales se cobran con el billete ya en la mano. Si otra venta empieza mientras se materializan, la orden
     * de los vales no se escribe en ella, pero el cobro de A SÍ sale, con su contexto (lo durable se conserva, R5).
     */
    @Test
    fun `vales - si otra venta empieza mientras se materializa, la orden no se escribe en ella y el efectivo de la vieja se cobra`() = runTest {
        every { areaTicketRepository.session.current() } returns sesionConVale("sesion", "a1")
        val materializada = CompletableDeferred<AreaTicketCheckout>()
        coEvery { areaTicketRepository.materialize(any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers { materializada.await() }

        vm.startPaymentFlow(carritoDeVales("a1"))
        vm.confirmCashCustom(10_000)
        advanceUntilIdle()
        vm.startPaymentFlow(otroImporte(5_000)) // B
        val deB = vm.state.value
        materializada.complete(
            AreaTicketCheckout(
                id = "sesion", status = "OPEN", version = 1, expiresAt = "x", createdAt = "x",
                tickets = sesionConVale("sesion", "a1").tickets,
                order = AreaTicketCheckoutOrder(id = "orden-vales-A", orderNumber = "77", paymentStatus = "PENDING", status = "PENDING", total = "100.00"),
                totals = AreaTicketCheckoutTotals("100.00", "0.00", "100.00"),
            ),
        )
        advanceUntilIdle()

        assertNull("la orden de A no es la de B", campo("createdOrderId"))
        assertEquals(deB, vm.state.value)
        coVerify(exactly = 1) { orderRepository.recordCashPayment("orden-vales-A", any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any()) }
    }

    // MARK: - La venta B no lleva datos de A (folio de la comanda)

    @Test
    fun `el folio de la comanda de la venta nueva no lleva la llave de la vieja`() = runTest {
        val compuerta = CompletableDeferred<Unit>()
        disco.compuertaDelGuardado = compuerta // la reserva de A se queda escribiéndose
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns ordenCreada("orden-A")

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10_000)
        advanceUntilIdle()
        vm.startPaymentFlow(otroImporte(5_000)) // B
        compuerta.complete(Unit)
        advanceUntilIdle()

        assertNull("B no hereda el externalId de A", campo("externalIdDeLaVenta"))
    }

    // MARK: - Codex r10 #3: la impresión de A sigue con SU contexto

    @Test
    fun `la comanda de la venta vieja sale con su orden aunque mientras imprimia empezara otra`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns ordenCreada("orden-A")
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any()) } returns pagado()
        val ticket = CompletableDeferred<Unit>()
        coEvery { printerService.autoPrintReceipt(any()) } coAnswers { ticket.await() }
        val ordenDeLaComanda = slot<String?>()
        coEvery {
            comandaDispatcher.dispatch(any(), any(), any(), any(), any(), any(), any(), captureNullable(ordenDeLaComanda), any(), any(), any(), any(), any(), any(), any())
        } returns null

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10_000)
        advanceUntilIdle()
        assertTrue(vm.state.value is PaymentFlowState.Success)
        vm.startPaymentFlow(otroImporte(5_000)) // B, con el ticket de A todavía imprimiéndose
        ticket.complete(Unit)
        advanceUntilIdle()

        assertEquals("la comanda de A sale con la orden de A", "orden-A", ordenDeLaComanda.captured)
    }

    // MARK: - N1: la orden de A que nace con B en pantalla se cancela por la vía durable, sin tocar B

    @Test
    fun `la orden de la venta vieja que nace con otra en pantalla se cancela por la via durable sin tocar la nueva`() = runTest {
        val creada = CompletableDeferred<Result<CreateOrderResponse>>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } coAnswers { creada.await() }

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()
        vm.startPaymentFlow(otroImporte(5_000)) // B
        val deB = vm.state.value
        creada.complete(ordenCreada("orden-A", folio = "17"))
        advanceUntilIdle()

        val intencion = slot<IntencionDeCancelarCobro>()
        verify { cancelacionDeCobro.registrar(capture(intencion)) }
        assertEquals("orden-A", intencion.captured.orderId)
        assertTrue(intencion.captured.borrarOrden)
        assertEquals("con SU importe", 10_000, intencion.captured.montoCents)
        assertEquals("y SU número", "17", intencion.captured.orderNumber)
        verify { cancelacionDeCobro.procesarAhora(intencion.captured.id) }
        assertEquals("B no se toca", deB, vm.state.value)
        coVerify(exactly = 0) { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `con apartado de efectivo la orden de la venta vieja no se cancela - el barrido la paga`() = runTest {
        val creada = CompletableDeferred<Result<CreateOrderResponse>>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } coAnswers { creada.await() }

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10_000)
        advanceUntilIdle()
        vm.startPaymentFlow(otroImporte(5_000)) // B
        creada.complete(ordenCreada("orden-A"))
        advanceUntilIdle()

        verify(exactly = 0) { cancelacionDeCobro.registrar(any()) }
        assertEquals("el apartado de A queda para el barrido", listOf("EN_VUELO"), disco.filas.values.map { it.syncStatus })
    }

    // MARK: - N2: un cobro nuevo deja atrás el «Cancelar» que falló

    @Test
    fun `tras No se pudo cancelar, un cobro nuevo cuyo guardado falla se reintenta como cobro y no cancela`() = runTest {
        coEvery { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returnsMany listOf(
            Result.failure(OrderRepository.ServerException(400, "Esta cuenta está cancelada, abre una nueva.")),
            pagado(),
        )
        vm.startPaymentFlow(otroImporte())
        vm.confirmCashCustom(10_000)
        advanceUntilIdle()
        disco.fallaAlBorrar = true
        vm.cancelarVenta() // «No se pudo cancelar el cobro»
        advanceUntilIdle()
        disco.fallaAlBorrar = false

        disco.fallaAlGuardar = true
        vm.confirmCashCustom(10_000) // la cajera vuelve a cobrar; su guardado falla
        advanceUntilIdle()
        assertTrue(vm.state.value is PaymentFlowState.Error)
        disco.fallaAlGuardar = false

        vm.retry()
        advanceUntilIdle()

        assertTrue("«Reintentar» de ESE error reintenta el cobro", vm.state.value is PaymentFlowState.Success)
        assertFalse("no canceló la venta", vm.debeSalir.value)
        assertEquals(1, disco.filas.size)
    }

    // MARK: - Agregados de la ronda corta (re-revisión del rediseño)

    /** Ruling N1: con un apartado «Ya pagó de otra forma» la orden de la venta vieja tampoco se cancela (el dinero pudo entrar). */
    @Test
    fun `con un apartado manual la orden de la venta vieja no se cancela`() = runTest {
        val creada = CompletableDeferred<Result<CreateOrderResponse>>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } coAnswers { creada.await() }

        vm.startPaymentFlow(dosCafes)
        vm.confirmManualChoice(com.avoqado.pos.payment.domain.ManualPaymentChoice.Fixed(com.avoqado.pos.payment.domain.ManualPaymentMethod.CARD_EXTERNAL))
        advanceUntilIdle()
        assertEquals("el apartado manual quedó en disco", listOf("CARD_EXTERNAL"), disco.filas.values.map { it.method })
        vm.startPaymentFlow(otroImporte(5_000)) // B
        creada.complete(ordenCreada("orden-A"))
        advanceUntilIdle()

        verify(exactly = 0) { cancelacionDeCobro.registrar(any()) }
    }

    /** Agregado 4: con TARJETA (todavía no hubo dinero), si otra venta empezó mientras se leía la caja, NO se crea la orden de A. */
    @Test
    fun `confirmar con tarjeta - si otra venta empezo mientras se leia la caja no se crea la orden`() = runTest {
        val caja = CompletableDeferred<Unit>()
        coEvery { cashDrawerRepository.getOpenSession() } coAnswers { caja.await(); null }

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        vm.selectTerminalAndPay("t1")
        advanceUntilIdle()
        vm.startPaymentFlow(otroImporte(5_000)) // B
        val deB = vm.state.value
        caja.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 0) { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) }
        verify(exactly = 0) { cancelacionDeCobro.registrar(any()) }
        assertEquals(deB, vm.state.value)
    }

    /**
     * Corrección del agregado 4 (efectivo en mano, sin apartado: venta con premio). Si otra venta empezó mientras se leía la caja, la venta
     * de A NO se abandona: su orden y su cobro salen con su contexto, y en B no se escribe nada.
     */
    @Test
    fun `efectivo sin apartado (con premio) - si otra venta empezo mientras se leia la caja el cobro de la vieja sale igual`() = runTest {
        val caja = CompletableDeferred<Unit>()
        coEvery { cashDrawerRepository.getOpenSession() } coAnswers { caja.await(); null }
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns ordenCreada("orden-A")
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any()) } returns pagado()
        val conPremio = CartState(
            items = listOf(cafe("e1")),
            pendingStampReward = com.avoqado.pos.loyalty.data.PremioPorAplicar(id = "rw1", etiqueta = "\$10 de premio", tipo = "FIXED_AMOUNT", valor = 10.0),
        )

        vm.startPaymentFlow(conPremio)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(5_000)
        advanceUntilIdle()
        vm.startPaymentFlow(otroImporte(5_000)) // B
        val deB = vm.state.value
        caja.complete(Unit)
        advanceUntilIdle()

        assertTrue("con premio no hay apartado", disco.filas.isEmpty())
        coVerify(exactly = 1) { orderRepository.recordCashPayment("orden-A", any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any()) }
        verify(exactly = 0) { cancelacionDeCobro.registrar(any()) }
        assertNull(campo("createdOrderId"))
        assertEquals(deB, vm.state.value)
    }

    /**
     * Corrección del agregado 4: «Confirmar» legado en EFECTIVO, con el billete en la mano. B empieza mientras se lee la caja: el cobro de A
     * llega al servidor con su orden y su contexto, y la pantalla de B no cambia.
     */
    @Test
    fun `confirmar en efectivo - si otra venta empezo mientras se leia la caja el cobro de la vieja llega al servidor`() = runTest {
        val caja = CompletableDeferred<Unit>()
        coEvery { cashDrawerRepository.getOpenSession() } coAnswers { caja.await(); null }
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns ordenCreada("orden-A")
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any()) } returns pagado()

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.confirmPayment()
        advanceUntilIdle()
        vm.startPaymentFlow(otroImporte(5_000)) // B
        val deB = vm.state.value
        caja.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 1) { orderRepository.recordCashPayment("orden-A", 10_000, any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any()) }
        coVerify(exactly = 1) { cashDrawerRepository.addCashSale(10_000, "orden-A", any(), any(), any(), llaveDelCobro = any()) }
        verify(exactly = 0) { cancelacionDeCobro.registrar(any()) }
        assertNull("la orden de A no es la de B", campo("createdOrderId"))
        assertEquals("B no cambia", deB, vm.state.value)
    }

    /**
     * Agregado 7 («Confirmar» legado en efectivo): falla crear la orden de A sin red con B ya en pantalla. Lo durable se conserva: el
     * efectivo de A se encola (y su cajón, sin apartado, lo anota el ViewModel). En B no se escribe nada.
     */
    @Test
    fun `confirmar en efectivo - si falla crear la orden con otra venta en pantalla el efectivo de la vieja se encola`() = runTest {
        val creada = CompletableDeferred<Result<CreateOrderResponse>>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } coAnswers { creada.await() }

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.confirmPayment()
        advanceUntilIdle()
        vm.startPaymentFlow(otroImporte(5_000)) // B
        val deB = vm.state.value
        creada.complete(Result.failure(java.net.UnknownHostException("sin red")))
        advanceUntilIdle()

        assertEquals("el cobro de A quedó en la cola", listOf("PENDING"), disco.filas.values.map { it.syncStatus })
        assertEquals(10_000, disco.filas.values.single().let { it.amountCents + it.tipCents })
        coVerify(exactly = 1) { cashDrawerRepository.addCashSale(10_000, any(), any(), any(), any(), llaveDelCobro = any()) }
        assertEquals("B no cambia", deB, vm.state.value)
    }

    /** Agregado 8: N2 también en `confirmPayment` — su error, tras un «Cancelar» que falló, se reintenta como cobro, no como cancelación. */
    @Test
    fun `tras No se pudo cancelar, Confirmar deja atras la cancelacion fallida`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returnsMany listOf(
            Result.failure(OrderRepository.ServerException(400, "rechazo")),
            Result.failure(OrderRepository.ServerException(400, "rechazo")),
            ordenCreada("orden-3"),
        )
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any()) } returns pagado()
        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10_000)
        advanceUntilIdle()
        disco.fallaAlBorrar = true
        vm.cancelarVenta() // «No se pudo cancelar el cobro»
        advanceUntilIdle()
        disco.fallaAlBorrar = false

        vm.confirmPayment()
        advanceUntilIdle()
        assertTrue(vm.state.value is PaymentFlowState.Error)
        vm.retry()
        advanceUntilIdle()

        assertFalse("no canceló la venta", vm.debeSalir.value)
        assertTrue("«Reintentar» volvió a cobrar", vm.state.value is PaymentFlowState.Success)
    }

    /**
     * Codex r11 #4: el `catch` exterior de «Confirmar». A no pudo crear su orden sin red y, al encolar su efectivo, la escritura de la
     * cola truena. B ya empezó: su pantalla no cambia (el error de A no se pinta sobre B).
     */
    @Test
    fun `confirmar - si la escritura de la cola de la vieja truena con otra venta en pantalla, la nueva no cambia`() = runTest {
        val creada = CompletableDeferred<Result<CreateOrderResponse>>()
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } coAnswers { creada.await() }

        vm.startPaymentFlow(dosCafes)
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.confirmPayment()
        advanceUntilIdle()
        vm.startPaymentFlow(otroImporte(5_000)) // B
        val deB = vm.state.value
        disco.fallaAlGuardar = true // encolar el efectivo de A truena
        creada.complete(Result.failure(java.net.UnknownHostException("sin red")))
        advanceUntilIdle()

        assertEquals("B no recibe el error de A", deB, vm.state.value)
    }

    // MARK: - Codex r14 A5: el respaldo del 2xx comprobado conserva el importe definitivo en la pantalla y el cambio

    @Test
    fun `el respaldo del 2xx comprobado de una orden muestra el importe y el cambio que confirmo el servidor`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns ordenCreada("orden-A")
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any()) } returns
            Result.failure(OrderRepository.CobroRegistradoSinGuardar(IllegalStateException("disco"), importeCobradoCents = 8_000))

        vm.startPaymentFlow(dosCafes) // $100
        vm.selectPaymentMethod(PaymentMethod.CASH)
        vm.processCashPayment(10_000) // recibe $100; otra caja ya cobró $20 de la orden
        advanceUntilIdle()

        val exito = vm.state.value as PaymentFlowState.Success
        assertTrue(exito.isQueued)
        assertEquals("lo que el servidor registró", 8_000, exito.totalAmount)
        assertEquals("el cambio sale de lo registrado", 2_000, exito.changeAmount)
    }

    // MARK: - Codex r14 B5: las acciones manuales después del cobro publican su resultado sólo en SU venta

    /** A cobró en efectivo y la pantalla muestra su éxito (recibo, pago). */
    private suspend fun kotlinx.coroutines.test.TestScope.ventaCobrada() {
        coEvery { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns pagado()
        vm.startPaymentFlow(otroImporte())
        vm.confirmCashCustom(10_000)
        advanceUntilIdle()
        assertTrue(vm.state.value is PaymentFlowState.Success)
    }

    @Test
    fun `reimprimir - el resultado de la reimpresion de la vieja no se pinta en la nueva`() = runTest {
        ventaCobrada()
        val impresion = CompletableDeferred<PrinterService.PrintOutcome>()
        coEvery { printerService.manualPrintReceipt(any()) } coAnswers { impresion.await() }

        vm.reprintReceipt()
        advanceUntilIdle()
        vm.startPaymentFlow(otroImporte(5_000)) // B
        impresion.complete(PrinterService.PrintOutcome.Printed(1))
        advanceUntilIdle()

        coVerify(exactly = 1) { printerService.manualPrintReceipt(any()) }
        assertNull("B no dice «Recibo impreso» por el de A", vm.printResult.value)
        assertFalse(vm.printSending.value)
    }

    @Test
    fun `WhatsApp - el resultado del envio de la vieja no se pinta en la nueva`() = runTest {
        ventaCobrada()
        val envio = CompletableDeferred<Result<Unit>>()
        coEvery { orderRepository.sendReceiptWhatsApp(any(), any(), any()) } coAnswers { envio.await() }

        vm.sendReceiptWhatsApp("5512345678")
        advanceUntilIdle()
        vm.startPaymentFlow(otroImporte(5_000)) // B
        envio.complete(Result.success(Unit))
        advanceUntilIdle()

        assertNull(vm.whatsAppResult.value)
    }

    @Test
    fun `correo - el resultado del envio de la vieja no se pinta en la nueva`() = runTest {
        ventaCobrada()
        val envio = CompletableDeferred<Result<Unit>>()
        coEvery { orderRepository.sendReceiptEmail(any(), any(), any()) } coAnswers { envio.await() }

        vm.sendReceiptEmail("cliente@example.invalid")
        advanceUntilIdle()
        vm.startPaymentFlow(otroImporte(5_000)) // B
        envio.complete(Result.failure(java.net.UnknownHostException("sin red")))
        advanceUntilIdle()

        assertNull("B no dice «Sin conexión» por el de A", vm.emailResult.value)
    }

    // MARK: - Codex r14 B1: la continuación de «Cancelar» (y del borrado pendiente) después del disco

    private fun rechazo400() = Result.failure<OrderRepository.CashPayResult>(OrderRepository.ServerException(400, "Esta cuenta está cancelada, abre una nueva."))

    /** A cobró en efectivo, el servidor lo rechazó: queda su apartado, del flujo, y la pantalla de error. */
    private suspend fun kotlinx.coroutines.test.TestScope.ventaConApartadoRechazado(): String {
        coEvery { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns rechazo400()
        vm.startPaymentFlow(otroImporte())
        vm.confirmCashCustom(10_000)
        advanceUntilIdle()
        assertTrue(vm.state.value is PaymentFlowState.Error)
        return disco.filas.keys.single()
    }

    @Test
    fun `cancelar - si otra venta empieza mientras se borra el apartado de la vieja, la nueva no se cancela y el apartado si se borra`() = runTest {
        ventaConApartadoRechazado()
        val borrado = CompletableDeferred<Unit>()
        disco.compuertaDelBorrado = borrado

        vm.cancelarVenta()
        advanceUntilIdle()
        vm.startPaymentFlow(otroImporte(5_000)) // B
        val deB = vm.state.value
        borrado.complete(Unit)
        advanceUntilIdle()

        assertTrue("el borrado de A terminó", disco.filas.isEmpty())
        assertFalse("B no se cierra", vm.debeSalir.value)
        assertEquals("B no cambia", deB, vm.state.value)
    }

    @Test
    fun `cancelar - si el borrado de la vieja falla con otra venta en pantalla, la nueva no recibe el aviso y el apartado se suelta`() = runTest {
        val llave = ventaConApartadoRechazado()
        val borrado = CompletableDeferred<Unit>()
        disco.compuertaDelBorrado = borrado
        disco.fallaAlBorrar = true

        vm.cancelarVenta()
        advanceUntilIdle()
        vm.startPaymentFlow(otroImporte(5_000)) // B
        val deB = vm.state.value
        borrado.complete(Unit)
        advanceUntilIdle()

        assertNull("B no recibe «No se pudo cancelar»", vm.cancelFailure.value)
        assertFalse("su «Reintentar» no cancela nada", campo("cancelacionFallida") as Boolean)
        assertEquals(deB, vm.state.value)
        assertEquals("nadie decide ya por A: se da por cobrado, como al empezar otra venta", 1, caja.darPorCobradasLasAbandonadas())
        assertEquals("PENDING", disco.filas[llave]?.syncStatus)
    }

    /**
     * Codex r15 #1 (regresión de r14): la pantalla se destruye (`onCleared`) mientras dura un borrado que va a fallar. Ese apartado ya
     * no tiene dueño: al terminar el borrado se suelta y el barrido lo toma en el acto. Antes quedaba EN_VUELO, reservado por un
     * ViewModel muerto, sin barrido, hasta reiniciar (y podía retener la barrera del cierre).
     */
    @Test
    fun `cancelar - si la pantalla se destruye mientras falla el borrado, el apartado se suelta y el barrido lo toma`() = runTest {
        val cola = mockk<PaymentSyncService>(relaxed = true)
        vm = nuevoVm(cola)
        val llave = ventaConApartadoRechazado()
        val borrado = CompletableDeferred<Unit>()
        disco.compuertaDelBorrado = borrado
        disco.fallaAlBorrar = true

        vm.cancelarVenta()
        advanceUntilIdle()
        androidx.lifecycle.ViewModelStore().apply { put("cobro", vm) }.clear()
        borrado.complete(Unit)
        advanceUntilIdle()

        verify(exactly = 1) { cola.darPorCobradasLasAbandonadasYa() }
        assertEquals("ya no es de un flujo vivo: se da por cobrado", 1, caja.darPorCobradasLasAbandonadas())
        assertEquals("PENDING", disco.filas[llave]?.syncStatus)
    }

    @Test
    fun `el borrado pendiente que termina con otra venta en pantalla no arranca el cobro de la vieja en ella`() = runTest {
        val llave = ventaConApartadoRechazado()
        @Suppress("UNCHECKED_CAST") (campo("porDescartar") as MutableSet<String>) += llave // un borrado ya decidido que el disco no escribió
        val borrado = CompletableDeferred<Unit>()
        disco.compuertaDelBorrado = borrado

        vm.processCashPayment(10_000) // primero el borrado pendiente; al terminar, el cobro que pidió A
        advanceUntilIdle()
        vm.startPaymentFlow(otroImporte(5_000)) // B
        val deB = vm.state.value
        borrado.complete(Unit)
        advanceUntilIdle()

        assertTrue("el borrado pendiente sí terminó", (campo("porDescartar") as Set<*>).isEmpty())
        assertEquals("B no cambia", deB, vm.state.value)
        coVerify(exactly = 1) { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) }
    }

    // MARK: - Codex r14 B2: el cobro de vales materializa SU checkout, capturado antes de esperar

    private val api = mockk<com.avoqado.pos.core.data.network.ApiService>()
    private val sesionDeVales = com.avoqado.pos.areatickets.data.AreaTicketSession(secureStorage)
    private val valesReales = AreaTicketRepository(api = api, secureStorage = secureStorage, session = sesionDeVales, payloadCache = mockk(relaxed = true))

    private fun sesionConVale(id: String, vale: String) = AreaTicketCheckout(
        id = id, status = "OPEN", version = 1, expiresAt = "2999-01-01T00:00:00Z", createdAt = "2026-10-25T10:00:00Z",
        tickets = listOf(
            com.avoqado.pos.areatickets.data.AreaTicket(
                id = vale, code = "V-$vale", status = "OPEN", fulfillmentArea = com.avoqado.pos.areatickets.data.AreaTicketArea("area-1", "Barra", "PICKUP"),
                subtotal = "100.00", total = "100.00", issuedAt = "2026-10-25T10:00:00Z",
                lines = listOf(com.avoqado.pos.areatickets.data.AreaTicketLine(id = "l-$vale", clientLineId = "c-$vale", productNameSnapshot = "Café", quantity = "1", unitPrice = "100.00", total = "100.00")),
            ),
        ),
        totals = AreaTicketCheckoutTotals("100.00", "0.00", "100.00"),
    )

    private fun carritoDeVales(vale: String) = CartState(
        items = listOf(CartItem(id = "area-ticket:$vale:l-$vale", type = CartItemType.CustomAmount, name = "Café", unitPrice = 10_000,
            areaTicketId = vale, areaTicketLineId = "l-$vale", locked = true)),
    )

    /** A espera la lectura de la caja con SU sesión de vales; mientras, A termina para la pantalla y B abre otra sesión. */
    private suspend fun kotlinx.coroutines.test.TestScope.valesDeAConBEnMedio(cobrar: () -> Unit) {
        coEvery { api.materializeAreaTicketCheckout(VENUE, any(), any()) } answers {
            val id = secondArg<String>()
            com.avoqado.pos.areatickets.data.AreaTicketEnvelope(success = true, data = com.avoqado.pos.areatickets.data.CheckoutData(
                sesionConVale(id, if (id == "sesion-A") "a1" else "b1").copy(
                    order = AreaTicketCheckoutOrder(id = "orden-de-$id", orderNumber = "1", paymentStatus = "PENDING", status = "PENDING", total = "100.00"),
                ),
            ))
        }
        coEvery { api.getAreaTicketCheckout(VENUE, any()) } answers {
            com.avoqado.pos.areatickets.data.AreaTicketEnvelope(success = true, data = com.avoqado.pos.areatickets.data.CheckoutData(sesionConVale(secondArg(), "b1")))
        }
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any()) } returns pagado()
        val caja = CompletableDeferred<com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity?>()
        coEvery { cashDrawerRepository.getOpenSession() } coAnswers { caja.await() }
        vm = nuevoVm(vales = valesReales)
        sesionDeVales.update(VENUE, sesionConVale("sesion-A", "a1"))

        vm.startPaymentFlow(carritoDeVales("a1"))
        vm.selectPaymentMethod(PaymentMethod.CASH)
        cobrar()
        advanceUntilIdle()
        sesionDeVales.clear()
        sesionDeVales.update(VENUE, sesionConVale("sesion-B", "b1"))
        vm.startPaymentFlow(carritoDeVales("b1")) // B
        caja.complete(null)
        advanceUntilIdle()
    }

    @Test
    fun `vales - el efectivo de la vieja materializa y cobra SU checkout aunque mientras se leia la caja la sesion ya sea otra`() = runTest {
        valesDeAConBEnMedio { vm.processCashPayment(10_000) }

        coVerify(exactly = 1) { api.materializeAreaTicketCheckout(VENUE, "sesion-A", any()) }
        coVerify(exactly = 0) { api.materializeAreaTicketCheckout(VENUE, "sesion-B", any()) }
        coVerify(exactly = 1) { orderRepository.recordCashPayment("orden-de-sesion-A", any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any(), importeAceptadoCents = any()) }
        assertEquals("la sesión de B no se toca", "sesion-B", sesionDeVales.current()?.id)
    }

    @Test
    fun `vales - Confirmar en efectivo de la vieja materializa SU checkout aunque la sesion ya sea otra`() = runTest {
        valesDeAConBEnMedio { vm.confirmPayment() }

        coVerify(exactly = 1) { api.materializeAreaTicketCheckout(VENUE, "sesion-A", any()) }
        coVerify(exactly = 0) { api.materializeAreaTicketCheckout(VENUE, "sesion-B", any()) }
        assertEquals("sesion-B", sesionDeVales.current()?.id)
    }

    private companion object { const val VENUE = "venue-guarda" }
}
