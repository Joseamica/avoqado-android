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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            syncOutbox = mockk(relaxed = true),
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
}
