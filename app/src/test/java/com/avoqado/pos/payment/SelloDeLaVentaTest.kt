package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.kds.domain.KDSOrderBus
import com.avoqado.pos.loyalty.data.SaleStampOutcome
import com.avoqado.pos.loyalty.domain.RenglonDelSello
import com.avoqado.pos.loyalty.domain.TextosDelSello
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CashPaymentResult
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.ColaDelCobro
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.SincronizacionDelCobro
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.data.ReporteDeComandas
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Sellos tardíos en «Cobro exitoso» (Testarudo, 14-oct): 3 de 5 ventas con cliente perdían su sello porque el barista
 * agrega al cliente DESPUÉS de cobrar. El servidor ahora sella al ligar; esta pantalla dice qué pasó.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SelloDeLaVentaTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val cashPaymentRepository = mockk<CashPaymentRepository>(relaxed = true)
    private val tenderTypeRepository = mockk<com.avoqado.pos.payment.data.TenderTypeRepository>(relaxed = true)
    private val terminalPaymentService = mockk<TerminalPaymentService>(relaxed = true)
    private val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true)
    private val paymentSyncService = mockk<PaymentSyncService>(relaxed = true)
    private val cashDrawerRepository = mockk<CashDrawerRepository>(relaxed = true)
    private val kdsRepository = mockk<KDSRepository>(relaxed = true)
    private val kdsOrderBus = mockk<KDSOrderBus>(relaxed = true)
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val printConfigRepository = mockk<PrintConfigRepository>(relaxed = true)
    private val comandaPrinter = mockk<ComandaPrinter>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)
    private val tableSession = TableSession()
    private val syncOutbox = mockk<com.avoqado.pos.core.data.sync.SyncOutbox>(relaxed = true)

    private lateinit var viewModel: PaymentFlowViewModel

    private fun otroImporte(cents: Int = 10_000) = CartState(
        items = listOf(
            CartItem(id = "linea-importe", type = CartItemType.CustomAmount, name = "Otro importe", unitPrice = cents),
        ),
    )

    private fun sello(stamped: Boolean = false, reason: String? = null, earned: Int = 0, reward: Boolean = false) =
        SaleStampOutcome(
            stampsEnabled = true,
            stamped = stamped,
            reason = reason,
            stampsEarned = earned,
            stampsRequired = 7,
            rewardReady = reward,
        )

    /** Cobro rápido en efectivo de $100; el servidor lo registra como `pago-1`. */
    private fun cobrar(customerId: String? = null, customerName: String? = null) {
        viewModel.startPaymentFlow(otroImporte(), customerId = customerId, customerName = customerName)
        viewModel.confirmCashCustom(10_000)
    }

    @Before
    fun setup() {
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = false)
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns TerminalListResult.Success(emptyList())
        every { cashPaymentRepository.processCashPayment(any(), any()) } returns CashPaymentResult.Success(changeCents = 0)
        coEvery {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "pago-1", receiptAccessKey = null))
        coEvery { cashDrawerRepository.addCashSale(any(), any(), any(), any(), any()) } returns null
        coEvery { kdsRepository.createOrder(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { kdsOrderBus.publish(any()) } returns Unit
        every { secureStorage.userId } returns "user-456"
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.venueName } returns "Avoqado Test"
        every { areaTicketRepository.session.current() } returns null

        viewModel = PaymentFlowViewModel(
            orderRepository = orderRepository,
            cashPaymentRepository = cashPaymentRepository,
            tenderTypeRepository = tenderTypeRepository,
            terminalPaymentService = terminalPaymentService,
            tpvSettingsRepository = tpvSettingsRepository,
            paymentSyncService = paymentSyncService,
            cashDrawerRepository = cashDrawerRepository,
            kdsRepository = kdsRepository,
            kdsOrderBus = kdsOrderBus,
            printerService = printerService,
            secureStorage = secureStorage,
            comandasPendientesStore = ComandasPendientesStore(AlmacenEnMemoria()),
            replayDeComandas = mockk(relaxed = true),
            comandaDispatcher = ComandaDispatcher(
                printConfigRepository,
                ReintentoDeComanda(comandaPrinter, reporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)),
                printerService,
            ),
            tableSession = tableSession,
            syncOutbox = syncOutbox,
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = areaTicketRepository,
            cancelacionDeCobro = mockk(relaxed = true),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
        )
    }

    // MARK: - A4: el cliente se agrega DESPUÉS de cobrar

    @Test
    fun `P1 agregar el cliente despues de cobrar pinta el sello que dijo el servidor`() = runTest {
        coEvery { orderRepository.attachCustomerToPayment("pago-1", "cm-dany") } returns Result.success(sello(stamped = true, earned = 3))
        cobrar()
        advanceUntilIdle()

        viewModel.attachCustomerToCurrentPayment("cm-dany", "Dany")
        advanceUntilIdle()

        assertEquals("Cliente agregado: Dany", viewModel.customerAttachResult.value)
        assertEquals(RenglonDelSello("✓ +1 sello · 3 de 7", positivo = true), viewModel.renglonDelSello.value)
    }

    @Test
    fun `un motivo del servidor llega al renglon como informacion, no como exito`() = runTest {
        coEvery { orderRepository.attachCustomerToPayment(any(), any()) } returns Result.success(sello(reason = "DAILY_LIMIT"))
        cobrar()
        advanceUntilIdle()

        viewModel.attachCustomerToCurrentPayment("cm-dany", "Dany")
        advanceUntilIdle()

        assertEquals(RenglonDelSello("Ya recibió su sello de hoy", positivo = false), viewModel.renglonDelSello.value)
    }

    @Test
    fun `P1 un servidor viejo sin loyalty agrega al cliente y no pinta renglon`() = runTest {
        coEvery { orderRepository.attachCustomerToPayment(any(), any()) } returns Result.success(null)
        cobrar()
        advanceUntilIdle()

        viewModel.attachCustomerToCurrentPayment("cm-dany", "Dany")
        advanceUntilIdle()

        assertEquals("Cliente agregado: Dany", viewModel.customerAttachResult.value)
        assertEquals("Dany", viewModel.attachedCustomerName.value)
        assertNull(viewModel.renglonDelSello.value)
    }

    @Test
    fun `P1 sin red al agregar el cliente se dice en llano y con el plazo de 24 h`() = runTest {
        coEvery { orderRepository.attachCustomerToPayment(any(), any()) } returns
            Result.failure(java.net.UnknownHostException("Unable to resolve host \"api.avoqado.io\""))
        cobrar()
        advanceUntilIdle()

        viewModel.attachCustomerToCurrentPayment("cm-dany", "Dany")
        advanceUntilIdle()

        assertEquals(TextosDelSello.SIN_RED_AL_AGREGAR_CLIENTE, viewModel.customerAttachResult.value)
        assertNull("sin liga, la pantalla no puede enseñar al cliente puesto", viewModel.attachedCustomerName.value)
        assertNull(viewModel.renglonDelSello.value)
    }

    @Test
    fun `un rechazo del servidor conserva su mensaje`() = runTest {
        coEvery { orderRepository.attachCustomerToPayment(any(), any()) } returns
            Result.failure(OrderRepository.ServerException(404, "Pago no encontrado"))
        cobrar()
        advanceUntilIdle()

        viewModel.attachCustomerToCurrentPayment("cm-dany", "Dany")
        advanceUntilIdle()

        assertEquals("Pago no encontrado", viewModel.customerAttachResult.value)
    }

    @Test
    fun `P1 un doble toque en el cliente manda una sola liga`() = runTest {
        coEvery { orderRepository.attachCustomerToPayment(any(), any()) } coAnswers {
            delay(500)
            Result.success(null)
        }
        cobrar()
        advanceUntilIdle()

        viewModel.attachCustomerToCurrentPayment("cm-dany", "Dany")
        viewModel.attachCustomerToCurrentPayment("cm-dany", "Dany")
        advanceUntilIdle()

        coVerify(exactly = 1) { orderRepository.attachCustomerToPayment(any(), any()) }
    }

    // MARK: - Auditoría A #4: la respuesta TARDÍA de la liga anterior no toca la venta nueva
    // Las respuestas se sueltan a mano (CompletableDeferred): nada depende de cuánto tarde por dentro el cobro en efectivo.

    @Test
    fun `P1 una respuesta tardia de la liga anterior no pone al cliente en la venta nueva`() = runTest {
        val respuestaDeAna = CompletableDeferred<Result<SaleStampOutcome?>>()
        coEvery { orderRepository.attachCustomerToPayment("pago-1", "cm-ana") } coAnswers { respuestaDeAna.await() }
        cobrar()
        advanceUntilIdle()

        viewModel.attachCustomerToCurrentPayment("cm-ana", "Ana")
        advanceUntilIdle() // la liga de Ana queda en vuelo
        viewModel.startPaymentFlow(otroImporte()) // «Venta nueva», sin cliente
        advanceUntilIdle()
        respuestaDeAna.complete(Result.success(sello(stamped = true, earned = 3))) // llega tarde
        advanceUntilIdle()

        assertNull(viewModel.attachedCustomerName.value)
        assertNull(viewModel.customerAttachResult.value)
        assertNull(viewModel.renglonDelSello.value)
        assertEquals(false, viewModel.customerAttachSending.value)

        // 🔴 Y el cobro de la venta nueva sale SIN Ana.
        viewModel.confirmCashCustom(10_000)
        advanceUntilIdle()
        coVerify(exactly = 0) {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), "cm-ana", capturedVenueId = any())
        }
        coVerify(exactly = 2) {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), null, capturedVenueId = any())
        }
    }

    @Test
    fun `un error tardio de la liga anterior tampoco pinta nada ni apaga el enviando de la venta nueva`() = runTest {
        val respuestaDeAna = CompletableDeferred<Result<SaleStampOutcome?>>()
        val respuestaDeBeto = CompletableDeferred<Result<SaleStampOutcome?>>()
        coEvery { orderRepository.attachCustomerToPayment(any(), "cm-ana") } coAnswers { respuestaDeAna.await() }
        coEvery { orderRepository.attachCustomerToPayment(any(), "cm-beto") } coAnswers { respuestaDeBeto.await() }
        cobrar()
        advanceUntilIdle()
        viewModel.attachCustomerToCurrentPayment("cm-ana", "Ana")
        advanceUntilIdle()

        viewModel.startPaymentFlow(otroImporte()) // venta nueva…
        viewModel.confirmCashCustom(10_000) // …cobrada…
        advanceUntilIdle()
        viewModel.attachCustomerToCurrentPayment("cm-beto", "Beto") // …y su PROPIA liga en vuelo
        advanceUntilIdle()

        respuestaDeAna.complete(Result.failure(java.net.UnknownHostException("sin red"))) // llega el error de Ana
        advanceUntilIdle()
        assertNull("el error de la venta anterior no se pinta aquí", viewModel.customerAttachResult.value)
        assertEquals("la liga de Beto sigue en vuelo: el doble toque sigue bloqueado", true, viewModel.customerAttachSending.value)

        respuestaDeBeto.complete(Result.success(null))
        advanceUntilIdle()
        assertEquals("Cliente agregado: Beto", viewModel.customerAttachResult.value)
        assertEquals(false, viewModel.customerAttachSending.value)
    }

    @Test
    fun `una venta nueva arranca sin el renglon de la anterior`() = runTest {
        coEvery { orderRepository.attachCustomerToPayment(any(), any()) } returns Result.success(sello(stamped = true, earned = 3))
        cobrar()
        advanceUntilIdle()
        viewModel.attachCustomerToCurrentPayment("cm-dany", "Dany")
        advanceUntilIdle()

        viewModel.startPaymentFlow(otroImporte())

        assertNull(viewModel.renglonDelSello.value)
    }

    // MARK: - A5: el cliente venía del carrito

    @Test
    fun `P1 con el cliente del carrito al cobrar se consulta el sello y se pinta`() = runTest {
        coEvery { orderRepository.consultarSelloDeLaVenta("pago-1") } returns sello(stamped = true, earned = 3)

        cobrar(customerId = "cm-dany", customerName = "Dany")
        advanceUntilIdle()

        assertTrue(viewModel.state.value is PaymentFlowState.Success)
        assertEquals(RenglonDelSello("✓ +1 sello · 3 de 7", positivo = true), viewModel.renglonDelSello.value)
        coVerify(exactly = 1) { orderRepository.consultarSelloDeLaVenta("pago-1") }
    }

    @Test
    fun `P1 si la lealtad sigue procesandose reintenta una sola vez a los 2 s`() = runTest {
        val momentos = mutableListOf<Long>()
        coEvery { orderRepository.consultarSelloDeLaVenta("pago-1") } coAnswers {
            momentos += testScheduler.currentTime
            if (momentos.size == 1) sello(reason = "PENDING") else sello(stamped = true, earned = 0, reward = true)
        }

        cobrar(customerId = "cm-dany", customerName = "Dany")
        advanceUntilIdle()

        assertEquals(2, momentos.size)
        assertEquals(2_000L, momentos[1] - momentos[0])
        assertEquals(
            RenglonDelSello("🎁 ¡Completó su tarjeta! Tiene un premio por cobrar", positivo = true),
            viewModel.renglonDelSello.value,
        )
    }

    @Test
    fun `si tras el reintento sigue pendiente no pinta nada ni vuelve a preguntar`() = runTest {
        coEvery { orderRepository.consultarSelloDeLaVenta("pago-1") } returns sello(reason = "PENDING")

        cobrar(customerId = "cm-dany", customerName = "Dany")
        advanceUntilIdle()

        coVerify(exactly = 2) { orderRepository.consultarSelloDeLaVenta("pago-1") }
        assertNull(viewModel.renglonDelSello.value)
    }

    @Test
    fun `P1 si la consulta falla la venta sigue en exito y no se pinta nada`() = runTest {
        coEvery { orderRepository.consultarSelloDeLaVenta(any()) } returns null

        cobrar(customerId = "cm-dany", customerName = "Dany")
        advanceUntilIdle()

        assertTrue(viewModel.state.value is PaymentFlowState.Success)
        assertNull(viewModel.renglonDelSello.value)
    }

    @Test
    fun `una venta sin cliente no pregunta por el sello`() = runTest {
        cobrar()
        advanceUntilIdle()

        coVerify(exactly = 0) { orderRepository.consultarSelloDeLaVenta(any()) }
    }

    @Test
    fun `la liga despues de cobrar no dispara ademas la consulta del sello`() = runTest {
        coEvery { orderRepository.attachCustomerToPayment(any(), any()) } returns Result.success(sello(stamped = true, earned = 3))
        cobrar()
        advanceUntilIdle()

        viewModel.attachCustomerToCurrentPayment("cm-dany", "Dany")
        advanceUntilIdle()

        coVerify(exactly = 0) { orderRepository.consultarSelloDeLaVenta(any()) }
        assertEquals(RenglonDelSello("✓ +1 sello · 3 de 7", positivo = true), viewModel.renglonDelSello.value)
    }

    @Test
    fun `P1 la consulta de una venta anterior no pinta sobre la venta nueva`() = runTest {
        coEvery { orderRepository.consultarSelloDeLaVenta("pago-1") } coAnswers {
            delay(5_000)
            sello(stamped = true, earned = 3)
        }
        cobrar(customerId = "cm-dany", customerName = "Dany")
        advanceTimeBy(1_000)
        assertTrue("premisa: la venta 1 ya está en «Cobro exitoso»", viewModel.state.value is PaymentFlowState.Success)

        viewModel.startPaymentFlow(otroImporte())
        advanceUntilIdle()

        assertNull(viewModel.renglonDelSello.value)
    }

    @Test
    fun `P1 venta en efectivo encolada sin red con cliente dice que el sello se suma al sincronizar`() = runTest {
        every { orderRepository.programaDeSellosActivo("venue-1") } returns true
        coEvery {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.failure(java.net.UnknownHostException("sin red"))

        cobrar(customerId = "cm-dany", customerName = "Dany")
        advanceUntilIdle()

        val exito = viewModel.state.value as PaymentFlowState.Success
        assertTrue(exito.isQueued)
        assertEquals(RenglonDelSello.SE_SUMA_AL_SINCRONIZAR, viewModel.renglonDelSello.value)
        coVerify(exactly = 0) { orderRepository.consultarSelloDeLaVenta(any()) }
    }

    @Test
    fun `encolada en un negocio del que no se sabe si tiene sellos no promete nada`() = runTest {
        every { orderRepository.programaDeSellosActivo(any()) } returns null
        coEvery {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.failure(java.net.UnknownHostException("sin red"))

        cobrar(customerId = "cm-dany", customerName = "Dany")
        advanceUntilIdle()

        assertTrue((viewModel.state.value as PaymentFlowState.Success).isQueued)
        assertNull(viewModel.renglonDelSello.value)
    }

    // MARK: - A5: casos donde el sello NO se puede prometer (la pantalla no puede mentir)

    @Test
    fun `P1 una mesa abierta sin red no promete el sello porque su cola no lleva al cliente`() = runTest {
        // El intent PAY_CASH de una mesa provisional (y su OPEN_TABLE) no llevan `customerId`: al sincronizar, la venta
        // llega sin cliente y nunca suma sello. Prometerlo sería mentir.
        every { orderRepository.programaDeSellosActivo(any()) } returns true
        tableSession.start(
            TableSession.Active("mesa-5", "5", null, "local-5", "LOCAL-5", 0, 10_000, TableSession.Mode.PAYING, isProvisional = true),
        )

        cobrar(customerId = "cm-dany", customerName = "Dany")
        advanceUntilIdle()

        val exito = viewModel.state.value as PaymentFlowState.Success
        assertTrue("premisa: la mesa provisional se encola en el outbox", exito.colaDelCobro is ColaDelCobro.Outbox)
        assertNull(viewModel.renglonDelSello.value)
        coVerify(exactly = 0) { orderRepository.consultarSelloDeLaVenta(any()) }
    }

    @Test
    fun `una mesa cuyo cobro se encola tampoco promete porque la orden de la mesa puede no tener al cliente`() = runTest {
        every { orderRepository.programaDeSellosActivo(any()) } returns true
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.failure(java.net.UnknownHostException("sin red"))
        tableSession.start(
            TableSession.Active("mesa-5", "5", null, "orden-mesa-5", "M-5", 0, 10_000, TableSession.Mode.PAYING),
        )

        cobrar(customerId = "cm-dany", customerName = "Dany")
        advanceUntilIdle()

        val exito = viewModel.state.value as PaymentFlowState.Success
        assertTrue("premisa: se encoló en la cola de pagos", exito.colaDelCobro is ColaDelCobro.Pagos)
        assertNull(viewModel.renglonDelSello.value)
    }

    @Test
    fun `P1 la parte 1 de una cuenta dividida no pregunta por el sello`() = runTest {
        // Contrato §3: con saldo pendiente el servidor respondería NOT_PAID («no está pagada completa») a media división.
        viewModel.setSplitConfig("CUSTOMAMOUNT", customAmountCents = 4_000)
        viewModel.startPaymentFlow(otroImporte(), customerId = "cm-dany", customerName = "Dany")
        viewModel.confirmCashCustom(4_000)
        advanceUntilIdle()

        assertTrue(viewModel.state.value is PaymentFlowState.Success)
        assertTrue("premisa: queda saldo", viewModel.buildCompletion().remainingBalanceCents > 0)
        coVerify(exactly = 0) { orderRepository.consultarSelloDeLaVenta(any()) }
        assertNull(viewModel.renglonDelSello.value)
    }

    @Test
    fun `la parte 1 de una cuenta dividida encolada sin red tampoco promete el sello`() = runTest {
        every { orderRepository.programaDeSellosActivo(any()) } returns true
        coEvery {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.failure(java.net.UnknownHostException("sin red"))
        viewModel.setSplitConfig("CUSTOMAMOUNT", customAmountCents = 4_000)
        viewModel.startPaymentFlow(otroImporte(), customerId = "cm-dany", customerName = "Dany")
        viewModel.confirmCashCustom(4_000)
        advanceUntilIdle()

        assertTrue((viewModel.state.value as PaymentFlowState.Success).isQueued)
        assertNull("el sello lo trae la parte que salda la cuenta", viewModel.renglonDelSello.value)
    }

    // MARK: - Info-7 (QA en la D3, 15-oct): al sincronizar, la promesa se cambia por el sello que dio el servidor
    // Antes: con «Venta sincronizada» arriba, el renglón seguía diciendo «El sello se suma al sincronizar».

    /** El estado de la fila «fila-1» de la cola; la prueba lo mueve a mano, como la cola al subir la venta. */
    private val estadoDeLaFila = MutableStateFlow<String?>("PENDING")

    private fun pagoEnLinea() {
        coEvery {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "pago-1", receiptAccessKey = null))
    }

    /** Cobro rápido de $100 con Dany SIN red: se encola en «fila-1» y promete el sello. */
    private fun cobrarSinRedConDany() = cobrarSinRed(customerId = "cm-dany", customerName = "Dany")

    /** Cobro rápido de $100 SIN red: se encola en «fila-1». */
    private fun cobrarSinRed(customerId: String?, customerName: String?) {
        every { orderRepository.programaDeSellosActivo("venue-1") } returns true
        coEvery {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.failure(java.net.UnknownHostException("sin red"))
        coEvery {
            cashPaymentRepository.queueCashPayment(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
            )
        } returns "fila-1"
        every { cashPaymentRepository.observarEstado("fila-1") } returns estadoDeLaFila
        cobrar(customerId = customerId, customerName = customerName)
    }

    @Test
    fun `P1 al sincronizar la venta encolada el renglon dice el sello que dio el servidor`() = runTest {
        coEvery { cashPaymentRepository.pagoDelServidor("fila-1") } returns "pago-servidor-9"
        coEvery { orderRepository.consultarSelloDeLaVenta("pago-servidor-9") } returns sello(stamped = true, earned = 2)
        cobrarSinRedConDany()
        advanceUntilIdle()
        assertEquals("premisa: sin red se promete", RenglonDelSello.SE_SUMA_AL_SINCRONIZAR, viewModel.renglonDelSello.value)
        coVerify(exactly = 0) { orderRepository.consultarSelloDeLaVenta(any()) }

        estadoDeLaFila.value = "SYNCED" // la cola subió la venta
        advanceUntilIdle()

        assertEquals("premisa: arriba ya dice Venta sincronizada", SincronizacionDelCobro.SINCRONIZADA, viewModel.sincronizacionDelCobro.value)
        assertEquals(RenglonDelSello("✓ +1 sello · 2 de 7", positivo = true), viewModel.renglonDelSello.value)
    }

    @Test
    fun `P1 al sincronizar, si la consulta del sello falla, se quita la promesa`() = runTest {
        coEvery { cashPaymentRepository.pagoDelServidor("fila-1") } returns "pago-servidor-9"
        coEvery { orderRepository.consultarSelloDeLaVenta(any()) } returns null
        cobrarSinRedConDany()
        advanceUntilIdle()

        estadoDeLaFila.value = "SYNCED"
        advanceUntilIdle()

        assertTrue((viewModel.state.value as PaymentFlowState.Success).isQueued)
        assertNull("una promesa vieja miente: sin respuesta, el renglón se va", viewModel.renglonDelSello.value)
    }

    @Test
    fun `si al sincronizar ya no se sabe el pago del servidor se quita la promesa sin preguntar`() = runTest {
        coEvery { cashPaymentRepository.pagoDelServidor("fila-1") } returns null
        cobrarSinRedConDany()
        advanceUntilIdle()

        estadoDeLaFila.value = null // la limpieza ya borró la fila: también se lee como «Venta sincronizada»
        advanceUntilIdle()

        assertNull(viewModel.renglonDelSello.value)
        coVerify(exactly = 0) { orderRepository.consultarSelloDeLaVenta(any()) }
    }

    @Test
    fun `al sincronizar, si la lealtad aun se procesa, reintenta una sola vez a los 2 s`() = runTest {
        val momentos = mutableListOf<Long>()
        coEvery { cashPaymentRepository.pagoDelServidor("fila-1") } returns "pago-servidor-9"
        coEvery { orderRepository.consultarSelloDeLaVenta("pago-servidor-9") } coAnswers {
            momentos += testScheduler.currentTime
            if (momentos.size == 1) sello(reason = "PENDING") else sello(stamped = true, earned = 2)
        }
        cobrarSinRedConDany()
        advanceUntilIdle()

        estadoDeLaFila.value = "SYNCED"
        advanceUntilIdle()

        assertEquals(2, momentos.size)
        assertEquals(2_000L, momentos[1] - momentos[0])
        assertEquals(RenglonDelSello("✓ +1 sello · 2 de 7", positivo = true), viewModel.renglonDelSello.value)
    }

    @Test
    fun `P1 si la venta encolada termina rechazada se quita la promesa del sello`() = runTest {
        cobrarSinRedConDany()
        advanceUntilIdle()
        assertEquals("premisa: sin red se promete", RenglonDelSello.SE_SUMA_AL_SINCRONIZAR, viewModel.renglonDelSello.value)

        estadoDeLaFila.value = "FAILED" // el servidor la rechazó: queda en «Pendientes»
        advanceUntilIdle()

        assertEquals("premisa: arriba dice rechazada", SincronizacionDelCobro.RECHAZADA, viewModel.sincronizacionDelCobro.value)
        assertNull("la venta no subió: prometer el sello miente", viewModel.renglonDelSello.value)
        coVerify(exactly = 0) { orderRepository.consultarSelloDeLaVenta(any()) }
    }

    @Test
    fun `P1 si se empezo otra venta antes de sincronizar, el sello de la anterior no se pinta en ella`() = runTest {
        coEvery { cashPaymentRepository.pagoDelServidor("fila-1") } returns "pago-servidor-9"
        coEvery { orderRepository.consultarSelloDeLaVenta("pago-servidor-9") } returns sello(stamped = true, earned = 2)
        cobrarSinRedConDany()
        advanceUntilIdle()

        pagoEnLinea()
        cobrar() // «Venta nueva», sin cliente y ya con red
        advanceUntilIdle()
        assertEquals("premisa: la venta nueva ya cobró en línea", false, (viewModel.state.value as PaymentFlowState.Success).isQueued)

        estadoDeLaFila.value = "SYNCED" // la venta ANTERIOR sube ahora
        advanceUntilIdle()

        assertNull(viewModel.renglonDelSello.value)
        coVerify(exactly = 0) { orderRepository.consultarSelloDeLaVenta(any()) }
    }

    @Test
    fun `P1 la consulta tardia de la venta encolada anterior no pisa el renglon de la venta nueva`() = runTest {
        val selloDeLaAnterior = CompletableDeferred<SaleStampOutcome?>()
        coEvery { cashPaymentRepository.pagoDelServidor("fila-1") } returns "pago-servidor-9"
        coEvery { orderRepository.consultarSelloDeLaVenta("pago-servidor-9") } coAnswers { selloDeLaAnterior.await() }
        coEvery { orderRepository.consultarSelloDeLaVenta("pago-1") } returns sello(stamped = true, earned = 5)
        cobrarSinRedConDany()
        advanceUntilIdle()
        estadoDeLaFila.value = "SYNCED"
        advanceUntilIdle() // la consulta de la venta anterior queda en vuelo

        pagoEnLinea()
        cobrar(customerId = "cm-beto", customerName = "Beto") // venta nueva, con red y con su propio cliente
        advanceUntilIdle()
        assertEquals(RenglonDelSello("✓ +1 sello · 5 de 7", positivo = true), viewModel.renglonDelSello.value)

        selloDeLaAnterior.complete(sello(stamped = true, earned = 2)) // la de Dany llega tarde
        advanceUntilIdle()

        assertEquals(RenglonDelSello("✓ +1 sello · 5 de 7", positivo = true), viewModel.renglonDelSello.value)
    }

    // MARK: - Agregar el cliente a una venta ENCOLADA (Codex r1 #1 de iOS, 18-oct)
    // Antes se ligaba con «el último pago de $X del mismo empleado en 15 min»: dos cafés de $100 seguidos y el cliente (y
    // su sello) caían en la OTRA venta. Ahora sólo con el id que el servidor le dio a ESTA fila. Espejo de iOS 0d2c422.

    @Test
    fun `P1 agregar el cliente a una venta encolada ya sincronizada liga SU pago, nunca el ultimo de 100`() = runTest {
        coEvery { cashPaymentRepository.pagoDelServidor("fila-1") } returns "pago-servidor-9"
        coEvery { orderRepository.attachCustomerToPayment("pago-servidor-9", "cm-dany") } returns
            Result.success(sello(stamped = true, earned = 3))
        cobrarSinRed(customerId = null, customerName = null)
        advanceUntilIdle()
        estadoDeLaFila.value = "SYNCED"
        advanceUntilIdle()

        viewModel.attachCustomerToCurrentPayment("cm-dany", "Dany")
        advanceUntilIdle()

        coVerify(exactly = 1) { orderRepository.attachCustomerToPayment("pago-servidor-9", "cm-dany") }
        coVerify(exactly = 0) { orderRepository.attachCustomerToLatestPayment(any(), any(), any(), any()) }
        assertEquals("Dany", viewModel.attachedCustomerName.value)
        assertEquals(RenglonDelSello("✓ +1 sello · 3 de 7", positivo = true), viewModel.renglonDelSello.value)
    }

    @Test
    fun `P1 una venta encolada que aun no sincroniza no llama al servidor y lo dice`() = runTest {
        coEvery { cashPaymentRepository.pagoDelServidor("fila-1") } returns null
        cobrarSinRed(customerId = null, customerName = null)
        advanceUntilIdle()

        viewModel.attachCustomerToCurrentPayment("cm-dany", "Dany")
        advanceUntilIdle()

        coVerify(exactly = 0) { orderRepository.attachCustomerToPayment(any(), any()) }
        coVerify(exactly = 0) { orderRepository.attachCustomerToLatestPayment(any(), any(), any(), any()) }
        assertEquals(
            "Esta venta aún no se sincroniza. Agrega el cliente cuando aparezca «Venta sincronizada».",
            viewModel.customerAttachResult.value,
        )
        assertEquals(TextosDelSello.VENTA_SIN_SINCRONIZAR, viewModel.customerAttachResult.value)
        assertFalse(viewModel.customerAttachSending.value)
        assertNull(viewModel.attachedCustomerName.value)
    }

    // MARK: - Agregar el cliente a una venta de MESA encolada sin red, o encolada sin fila (18-oct)
    // Antes caían en «el último pago de $X del mismo empleado en 15 min». Ahora: el id del intent PAY_CASH ACKED, o nada.

    /** Mesa abierta SIN red (sesión provisional): el cobro va como intent PAY_CASH «intent-1» al outbox. */
    private fun cobrarMesaSinRed() {
        coEvery { syncOutbox.enqueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns "intent-1"
        tableSession.start(
            TableSession.Active("mesa-5", "5", null, "local-5", "LOCAL-5", 0, 10_000, TableSession.Mode.PAYING, isProvisional = true),
        )
        cobrar()
    }

    @Test
    fun `P1 agregar el cliente a una mesa cobrada sin red ya sincronizada liga el pago de SU intent`() = runTest {
        coEvery { syncOutbox.pagoDelServidor("intent-1") } returns "pago-mesa-9"
        coEvery { orderRepository.attachCustomerToPayment("pago-mesa-9", "cm-dany") } returns
            Result.success(sello(stamped = true, earned = 3))
        cobrarMesaSinRed()
        advanceUntilIdle()
        assertEquals("premisa", ColaDelCobro.Outbox("intent-1"), (viewModel.state.value as PaymentFlowState.Success).colaDelCobro)

        viewModel.attachCustomerToCurrentPayment("cm-dany", "Dany")
        advanceUntilIdle()

        coVerify(exactly = 1) { orderRepository.attachCustomerToPayment("pago-mesa-9", "cm-dany") }
        coVerify(exactly = 0) { orderRepository.attachCustomerToLatestPayment(any(), any(), any(), any()) }
        assertEquals("Dany", viewModel.attachedCustomerName.value)
    }

    @Test
    fun `P1 una mesa cobrada sin red que aun no sincroniza no llama al servidor y lo dice`() = runTest {
        coEvery { syncOutbox.pagoDelServidor("intent-1") } returns null
        cobrarMesaSinRed()
        advanceUntilIdle()

        viewModel.attachCustomerToCurrentPayment("cm-dany", "Dany")
        advanceUntilIdle()

        coVerify(exactly = 0) { orderRepository.attachCustomerToPayment(any(), any()) }
        coVerify(exactly = 0) { orderRepository.attachCustomerToLatestPayment(any(), any(), any(), any()) }
        assertEquals(TextosDelSello.VENTA_SIN_SINCRONIZAR, viewModel.customerAttachResult.value)
        assertFalse(viewModel.customerAttachSending.value)
        assertNull(viewModel.attachedCustomerName.value)
    }

    @Test
    fun `P1 una venta encolada sin fila de cola no llama al servidor y lo dice`() = runTest {
        // Cobro rápido sin carrito (`cartState` nulo): se encola sin id de fila, así que no hay pago que ligar.
        coEvery {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.failure(java.net.UnknownHostException("sin red"))
        viewModel.confirmCashCustom(10_000)
        advanceUntilIdle()
        val exito = viewModel.state.value as PaymentFlowState.Success
        assertTrue("premisa: encolada", exito.isQueued)
        assertNull("premisa: sin fila", exito.colaDelCobro)

        viewModel.attachCustomerToCurrentPayment("cm-dany", "Dany")
        advanceUntilIdle()

        coVerify(exactly = 0) { orderRepository.attachCustomerToPayment(any(), any()) }
        coVerify(exactly = 0) { orderRepository.attachCustomerToLatestPayment(any(), any(), any(), any()) }
        assertEquals(TextosDelSello.VENTA_SIN_SINCRONIZAR, viewModel.customerAttachResult.value)
        assertFalse(viewModel.customerAttachSending.value)
    }
}
