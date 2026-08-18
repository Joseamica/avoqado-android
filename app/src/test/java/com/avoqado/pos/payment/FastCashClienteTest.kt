package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.kds.domain.KDSOrderBus
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CashPaymentResult
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * El CLIENTE de la venta en el cobro rápido ("Otro importe").
 *
 * 🔴 El defecto que cierra este archivo: el cajero elegía "Juan Pérez" en el carrito,
 * cobraba $100 sin productos, y la orden `FAST-*` nacía SIN cliente. Verificado en un
 * POS Android real. Se perdían historial de compra, CFDI y atribución — y nadie se
 * enteraba, porque el ticket salía bien.
 *
 * Vive aparte de `PaymentFlowViewModelTest` sólo por tamaño: ese archivo ya pasa de
 * 1000 líneas y cubre el flujo de pago entero. Aquí se agrupa lo del CLIENTE.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FastCashClienteTest {

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

    private lateinit var viewModel: PaymentFlowViewModel

    /** Venta rápida: SOLO un importe, sin productos. Es la que va por `/fast`. */
    private fun otroImporte(cents: Int = 10_000) = CartState(
        items = listOf(
            CartItem(
                id = "linea-importe",
                type = CartItemType.CustomAmount,
                name = "Otro importe",
                unitPrice = cents,
            ),
        ),
    )

    @Before
    fun setup() {
        every {
            tpvSettingsRepository.getCurrentSettings()
        } returns TpvSettings(showReviewScreen = false, showTipScreen = false)

        coEvery {
            terminalPaymentService.fetchOnlineTerminals(any())
        } returns TerminalListResult.Success(emptyList())

        every {
            cashPaymentRepository.processCashPayment(any(), any())
        } returns CashPaymentResult.Success(changeCents = 0)

        coEvery {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "pago-1", receiptAccessKey = null))

        coEvery { cashDrawerRepository.addCashSale(any(), any()) } returns null
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
            comandaDispatcher = ComandaDispatcher(printConfigRepository, comandaPrinter, printerService),
            tableSession = com.avoqado.pos.tables.data.TableSession(),
            syncOutbox = mockk(relaxed = true),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = areaTicketRepository,
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
        )
    }

    @Test
    fun `el cobro rapido manda el cliente que el cajero eligio en el carrito`() = runTest {
        viewModel.startPaymentFlow(otroImporte(), customerId = "cmcustomer123", customerName = "Juan Pérez")
        viewModel.confirmCashCustom(10_000)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            orderRepository.recordFastCashPayment(
                amount = 10_000,
                staffId = "user-456",
                tip = 0,
                splitType = any(),
                idempotencyKey = any(),
                manualMethod = null,
                tenderType = null,
                customerId = "cmcustomer123",
            )
        }
        assertTrue(viewModel.state.value is PaymentFlowState.Success)
    }

    @Test
    fun `una venta anonima sigue viajando sin cliente`() = runTest {
        viewModel.startPaymentFlow(otroImporte())
        viewModel.confirmCashCustom(10_000)
        advanceUntilIdle()

        coVerify(exactly = 1) {
            orderRepository.recordFastCashPayment(
                amount = 10_000,
                staffId = "user-456",
                tip = 0,
                splitType = any(),
                idempotencyKey = any(),
                manualMethod = null,
                tenderType = null,
                customerId = null,
            )
        }
    }

    /**
     * El server no pudo vincular al cliente (id de otro negocio). El cobro SÍ quedó
     * registrado: se AVISA, y jamás se vuelve a cobrar.
     */
    @Test
    fun `un aviso del server se pinta y NO dispara otro cobro`() = runTest {
        coEvery {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(
            OrderRepository.CashPayResult(
                paymentId = "pago-1",
                receiptAccessKey = null,
                customerLinkWarning = "El cliente seleccionado no existe en este negocio. La venta se registró sin cliente.",
            ),
        )

        viewModel.startPaymentFlow(otroImporte(), customerId = "cmdeotrovenue", customerName = "Juan Pérez")
        viewModel.confirmCashCustom(10_000)
        advanceUntilIdle()

        val estado = viewModel.state.value
        assertTrue("el cobro fue exitoso, no un Error", estado is PaymentFlowState.Success)
        assertEquals(
            "El cliente seleccionado no existe en este negocio. La venta se registró sin cliente.",
            (estado as PaymentFlowState.Success).customerLinkWarning,
        )
        // 🔴 UNA sola vez: un aviso NUNCA es motivo para volver a cobrar.
        coVerify(exactly = 1) {
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any())
        }
        // La pantalla no puede seguir enseñando un cliente que la venta no tiene.
        assertNull(viewModel.attachedCustomerName.value)
    }

    @Test
    fun `sin aviso el recibo conserva el cliente puesto`() = runTest {
        viewModel.startPaymentFlow(otroImporte(), customerId = "cmcustomer123", customerName = "Juan Pérez")
        viewModel.confirmCashCustom(10_000)
        advanceUntilIdle()

        assertEquals("Juan Pérez", viewModel.attachedCustomerName.value)
        assertNull((viewModel.state.value as PaymentFlowState.Success).customerLinkWarning)
    }
}
