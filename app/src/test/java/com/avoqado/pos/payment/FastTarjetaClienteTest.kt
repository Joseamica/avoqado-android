package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.kds.domain.KDSOrderBus
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.OnlineTerminal
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.PaymentMethod
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * El CLIENTE de la venta en el cobro rápido con **TARJETA**.
 *
 * 🔴 El defecto que cierra este archivo: el cajero elegía "Juan Pérez" en el carrito,
 * cobraba $100 con tarjeta sin productos, y la orden `FAST-*` nacía SIN cliente. El
 * cobro en EFECTIVO sí lo mandaba desde el fix anterior (`FastCashClienteTest`); el de
 * tarjeta no, porque el relay a la terminal no tenía dónde llevarlo. Se perdían
 * historial de compra, CFDI y atribución — y nadie se entera, porque el ticket sale
 * perfecto.
 *
 * Aquí se prueba EL CABLEADO del ViewModel (que el id congelado llega al relay). Que el
 * cuerpo del POST lleve —u omita— la llave se prueba contra HTTP real en
 * `TerminalPaymentServiceHttpTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FastTarjetaClienteTest {

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

    /** Venta rápida: SOLO un importe, sin productos. Es la que NO crea orden. */
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
        } returns TerminalListResult.Success(
            listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1", isOnline = true, hasSocket = true)),
        )

        // Un desenlace de ERROR corta el flujo justo después del relay: lo que se prueba
        // es CON QUÉ se llamó, no lo que pasa al cobrar bien (eso ya está cubierto).
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any())
        } returns TerminalPaymentResult.Error("Terminal offline")

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
    fun `el cobro con tarjeta manda al relay el cliente que el cajero eligio`() = runTest {
        viewModel.startPaymentFlow(otroImporte(), customerId = "cmcustomer123", customerName = "Juan Pérez")
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            terminalPaymentService.sendPaymentToTerminal(
                terminalId = "t1",
                amountCents = 10_000,
                tipCents = 0,
                rating = null,
                // Sin productos NO nace orden: por eso el cliente tiene que viajar aparte.
                orderId = null,
                processedByStaffId = "user-456",
                customerId = "cmcustomer123",
            )
        }
    }

    @Test
    fun `una venta anonima con tarjeta sigue viajando sin cliente`() = runTest {
        viewModel.startPaymentFlow(otroImporte())
        viewModel.selectPaymentMethod(PaymentMethod.CARD)
        viewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        coVerify(exactly = 1) {
            terminalPaymentService.sendPaymentToTerminal(
                terminalId = "t1",
                amountCents = 10_000,
                tipCents = 0,
                rating = null,
                orderId = null,
                processedByStaffId = "user-456",
                customerId = null,
            )
        }
    }
}
