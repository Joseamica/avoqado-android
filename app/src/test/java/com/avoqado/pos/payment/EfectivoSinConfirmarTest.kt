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
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * El cobro en efectivo se guarda ANTES de tocar la red, y la pantalla dice la verdad cuando se sincroniza.
 *
 * 🔴 Defecto 1 (30-sep-2026, prueba de Windows + Codex): la fila de la cola nacía en el `onFailure` del
 * POST (hasta 15 s). Si la app moría ahí, el cobro no quedaba en ningún lado.
 *
 * 🔴 Defecto 2 (30-sep-2026, D3 de Testarudo, encargo del founder): tras una venta sin red la pantalla
 * decía «Se sincronizará cuando haya conexión» y SEGUÍA diciéndolo después de que la cola la subió. El
 * cajero cree que no subió y la vuelve a cobrar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EfectivoSinConfirmarTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val cashPaymentRepository = mockk<CashPaymentRepository>(relaxed = true)
    private val terminalPaymentService = mockk<TerminalPaymentService>(relaxed = true)
    private val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true)
    private val cashDrawerRepository = mockk<CashDrawerRepository>(relaxed = true)
    private val kdsRepository = mockk<KDSRepository>(relaxed = true)
    private val kdsOrderBus = mockk<KDSOrderBus>(relaxed = true)
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)
    private val estadoEnCola = MutableStateFlow<String?>("PENDING")

    private lateinit var viewModel: PaymentFlowViewModel

    private fun otroImporte(cents: Int = 10_000) = CartState(
        items = listOf(CartItem(id = "linea", type = CartItemType.CustomAmount, name = "Otro importe", unitPrice = cents)),
    )

    @Before
    fun setup() {
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = false)
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns TerminalListResult.Success(emptyList())
        every { cashPaymentRepository.processCashPayment(any(), any()) } returns CashPaymentResult.Success(changeCents = 0)
        coEvery { cashPaymentRepository.queueCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            arg<String?>(8) ?: "sin-llave"
        }
        every { cashPaymentRepository.observarEstado(any()) } returns estadoEnCola
        coEvery { cashDrawerRepository.addCashSale(any(), any()) } returns null
        coEvery { kdsRepository.createOrder(any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { kdsOrderBus.publish(any()) } returns Unit
        every { secureStorage.userId } returns "user-456"
        every { secureStorage.venueId } returns "venue-1"
        every { areaTicketRepository.session.current() } returns null

        viewModel = PaymentFlowViewModel(
            orderRepository = orderRepository,
            cashPaymentRepository = cashPaymentRepository,
            tenderTypeRepository = mockk(relaxed = true),
            terminalPaymentService = terminalPaymentService,
            tpvSettingsRepository = tpvSettingsRepository,
            paymentSyncService = mockk<PaymentSyncService>(relaxed = true),
            cashDrawerRepository = cashDrawerRepository,
            kdsRepository = kdsRepository,
            kdsOrderBus = kdsOrderBus,
            printerService = printerService,
            secureStorage = secureStorage,
            comandasPendientesStore = ComandasPendientesStore(AlmacenEnMemoria()),
            replayDeComandas = mockk(relaxed = true),
            comandaDispatcher = ComandaDispatcher(
                mockk<PrintConfigRepository>(relaxed = true),
                ReintentoDeComanda(mockk<ComandaPrinter>(relaxed = true), reporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)),
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

    private fun llaveReservada(): String {
        val llave = slot<String>()
        coVerify { cashPaymentRepository.reservarCobro(any(), any(), any(), any(), any(), any(), any(), any(), capture(llave), any(), any()) }
        return llave.captured
    }

    // MARK: - Defecto 1: se guarda ANTES de tocar la red

    @Test
    fun `P1 el cobro rapido se reserva ANTES de mandarse al servidor`() = runTest {
        coEvery { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(OrderRepository.CashPayResult(paymentId = "pago-1", receiptAccessKey = null))

        viewModel.startPaymentFlow(otroImporte())
        viewModel.confirmCashCustom(10_000)
        advanceUntilIdle()

        coVerifyOrder {
            cashPaymentRepository.reservarCobro(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
            orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `P1 si el servidor lo registra la reserva se suelta con la misma llave del cobro`() = runTest {
        val llaveDelPost = slot<String>()
        coEvery { orderRepository.recordFastCashPayment(any(), any(), any(), any(), capture(llaveDelPost), any(), any(), any()) } returns
            Result.success(OrderRepository.CashPayResult(paymentId = "pago-1", receiptAccessKey = null))

        viewModel.startPaymentFlow(otroImporte())
        viewModel.confirmCashCustom(10_000)
        advanceUntilIdle()

        assertEquals(llaveDelPost.captured, llaveReservada())
        coVerify { cashPaymentRepository.soltarReserva(llaveDelPost.captured) }
        assertEquals(SincronizacionDelCobro.NINGUNA, viewModel.sincronizacionDelCobro.value)
    }

    @Test
    fun `P1 sin red se encola con la MISMA llave de la reserva (la reemplaza) y se suelta despues`() = runTest {
        coEvery { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(IOException("timeout"))

        viewModel.startPaymentFlow(otroImporte())
        viewModel.confirmCashCustom(10_000)
        advanceUntilIdle()

        val llave = llaveReservada()
        coVerifyOrder {
            cashPaymentRepository.queueCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), llave, any(), any())
            cashPaymentRepository.soltarReserva(llave)
        }
        val exito = viewModel.state.value as PaymentFlowState.Success
        assertTrue(exito.isQueued)
        assertEquals(ColaDelCobro.Pagos(llave), exito.colaDelCobro)
    }

    @Test
    fun `P1 un rechazo del servidor suelta la reserva y no encola nada`() = runTest {
        coEvery { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(OrderRepository.ServerException(400, "monto inválido"))

        viewModel.startPaymentFlow(otroImporte())
        viewModel.confirmCashCustom(10_000)
        advanceUntilIdle()

        val llave = llaveReservada()
        coVerify { cashPaymentRepository.soltarReserva(llave) }
        coVerify(exactly = 0) { cashPaymentRepository.queueCashPayment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(viewModel.state.value is PaymentFlowState.Error)
    }

    // MARK: - Defecto 2: el aviso cambia cuando la cola sube la venta

    @Test
    fun `P1 el aviso pasa de pendiente a sincronizada cuando la cola sube la venta`() = runTest {
        coEvery { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(IOException("sin red"))

        viewModel.startPaymentFlow(otroImporte())
        viewModel.confirmCashCustom(10_000)
        advanceUntilIdle()
        assertEquals(SincronizacionDelCobro.PENDIENTE, viewModel.sincronizacionDelCobro.value)

        estadoEnCola.value = "SYNCING"
        advanceUntilIdle()
        assertEquals(SincronizacionDelCobro.PENDIENTE, viewModel.sincronizacionDelCobro.value)

        estadoEnCola.value = "SYNCED"
        advanceUntilIdle()
        assertEquals(SincronizacionDelCobro.SINCRONIZADA, viewModel.sincronizacionDelCobro.value)
    }

    @Test
    fun `P1 si el servidor rechaza la venta encolada la pantalla lo dice, no se esconde`() = runTest {
        coEvery { orderRepository.recordFastCashPayment(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            Result.failure(IOException("sin red"))

        viewModel.startPaymentFlow(otroImporte())
        viewModel.confirmCashCustom(10_000)
        advanceUntilIdle()
        estadoEnCola.value = "FAILED"
        advanceUntilIdle()

        assertEquals(SincronizacionDelCobro.RECHAZADA, viewModel.sincronizacionDelCobro.value)
    }

    @Test
    fun `la fila limpiada por la cola cuenta como sincronizada`() {
        assertEquals(SincronizacionDelCobro.SINCRONIZADA, SincronizacionDelCobro.dePago(null))
        assertEquals(SincronizacionDelCobro.PENDIENTE, SincronizacionDelCobro.dePago("EN_VUELO"))
        assertEquals(SincronizacionDelCobro.SINCRONIZADA, SincronizacionDelCobro.deIntent("ACKED"))
        assertEquals(SincronizacionDelCobro.PENDIENTE, SincronizacionDelCobro.deIntent("HELD"))
        assertEquals(SincronizacionDelCobro.RECHAZADA, SincronizacionDelCobro.deIntent("REJECTED"))
    }
}
