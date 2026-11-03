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
import com.avoqado.pos.payment.data.OnlineTerminal
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.PaymentFlowState
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.data.ReporteDeComandas
import com.avoqado.pos.printing.routing.PrintConfig
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
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * D15 (28-oct): una venta rápida NUEVA (importe sin productos) que un descuento deja en $0 es sólo efectivo, con su razón:
 * no hay nada que mandarle a la terminal (el SERVIDOR rechaza un cobro de terminal ≤ 0). Con productos (un premio que deja
 * la cuenta en $0) la tarjeta sigue: ya cierra como efectivo de $0 en `processPaymentMethod`. Espejo de iOS
 * (`VentaRapidaEnCeroTests`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VentaRapidaEnCeroTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val unPeso = CartItem(id = "l-importe", type = CartItemType.CustomAmount, name = "Otro importe", unitPrice = 100)
    private val cafe = CartItem(id = "l-cafe", type = CartItemType.ProductItem("p-cafe"), name = "Café", unitPrice = 100)
    private val cienPorCiento = com.avoqado.pos.pos.data.model.Discount(id = "d100", name = "Cortesía", value = 100.0)

    @Test
    fun `un peso con cien por ciento es sólo efectivo y la tarjeta no arranca`() = runTest {
        val carrito = CartState(items = listOf(unPeso), orderDiscount = cienPorCiento)
        assertEquals(0, carrito.totalCents)

        paymentViewModel.startPaymentFlow(carrito)
        assertTrue(paymentViewModel.ventaRapidaEnCero())
        assertEquals(PaymentFlowState.SelectingPaymentMethod(0), paymentViewModel.state.value)

        paymentViewModel.selectPaymentMethod(PaymentMethod.CARD)
        advanceUntilIdle()
        assertEquals("la tarjeta no aplica con \$0", PaymentFlowState.SelectingPaymentMethod(0), paymentViewModel.state.value)
        coVerify(exactly = 0) { terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any()) }
        assertEquals("Venta en \$0: no hay nada que cobrar con tarjeta.", PaymentFlowViewModel.TEXTO_VENTA_EN_CERO)
    }

    @Test
    fun `en cero el efectivo pide cero`() = runTest {
        paymentViewModel.startPaymentFlow(CartState(items = listOf(unPeso), orderDiscount = cienPorCiento))
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        assertEquals(PaymentFlowState.CollectingCashAmount(0), paymentViewModel.state.value)
    }

    @Test
    fun `con saldo o con productos la tarjeta sigue`() = runTest {
        paymentViewModel.startPaymentFlow(CartState(items = listOf(unPeso)))
        assertFalse("hay \$1.00 que cobrar", paymentViewModel.ventaRapidaEnCero())

        paymentViewModel.startPaymentFlow(CartState(items = listOf(cafe), orderDiscount = cienPorCiento))
        assertFalse("con productos manda el camino del premio en \$0", paymentViewModel.ventaRapidaEnCero())
    }

    @Test
    fun `un carrito vacío no es venta en cero`() = runTest {
        paymentViewModel.startPaymentFlow(CartState())
        assertFalse(paymentViewModel.ventaRapidaEnCero())
    }

    // MARK: - Andamiaje

    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val cashPaymentRepository = mockk<CashPaymentRepository>(relaxed = true)
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

    private lateinit var paymentViewModel: PaymentFlowViewModel

    /** Cierra el cobro como la pantalla al salir: sin esto sus corrutinas siguen vivas y estorban a las pruebas que vienen después (Q15). */
    @After
    fun cerrarElCobro() {
        androidx.lifecycle.ViewModelStore().apply { put("cobro", paymentViewModel) }.clear()
    }

    @Before
    fun setup() {
        every { tpvSettingsRepository.getCurrentSettings() } returns
            TpvSettings(showReviewScreen = false, showTipScreen = false)
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns
            TerminalListResult.Success(
                listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1", isOnline = true, hasSocket = true)),
            )
        every { cashPaymentRepository.processCashPayment(any(), any()) } returns
            CashPaymentResult.Success(changeCents = 0)
        coEvery { cashDrawerRepository.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) } returns null
        coEvery { kdsRepository.createOrder(any(), any(), any(), any()) } returns Result.success(Unit)
        every { secureStorage.venueName } returns "Avoqado Test"
        every { secureStorage.userId } returns "user-1"
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-99"
        every { areaTicketRepository.session.current() } returns null
        coEvery { printConfigRepository.refresh(any()) } returns Unit
        every { printConfigRepository.getCurrentConfig() } returns PrintConfig()
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 0, printed = 0, skippedNoPrinter = 0, lastError = null)

        paymentViewModel = PaymentFlowViewModel(
            orderRepository = orderRepository,
            cashPaymentRepository = cashPaymentRepository,
            tenderTypeRepository = mockk(relaxed = true),
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
            tableSession = com.avoqado.pos.tables.data.TableSession(),
            syncOutbox = mockk(relaxed = true),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = areaTicketRepository,
            cancelacionDeCobro = mockk(relaxed = true),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
        )
    }
}
