package com.avoqado.pos.payment

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.customers.data.IntentoDeCobro
import com.avoqado.pos.customers.data.PendingGrantQueue
import com.avoqado.pos.payment.data.*
import com.avoqado.pos.payment.data.model.*
import com.avoqado.pos.payment.domain.ManualPaymentChoice
import com.avoqado.pos.payment.domain.ManualPaymentMethod
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * D4 (5-nov): sin intentos anotados no salió ningún cobro. Esto es lo que sostiene esa frase: en TODOS los caminos del cobro,
 * la identidad del intento se escribe antes de tocar el dinero, y si el disco no la acepta, el cobro no sale.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaquetesEnElCobroTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val cash = mockk<CashPaymentRepository>(relaxed = true)
    private val order = mockk<OrderRepository>(relaxed = true)
    private val terminal = mockk<TerminalPaymentService>(relaxed = true)
    private val cola = mockk<PendingGrantQueue>(relaxed = true)
    private val almacen = ViewModelStore()
    private val cart = CartState(
        items = listOf(CartItem(type = CartItemType.CreditPack("pack-1"), name = "10 clases", unitPrice = 50000)),
    )

    private fun vm(): PaymentFlowViewModel {
        every { terminal.intentoEnVuelo() } returns null
        every { terminal.pendienteDeLaVenta(any()) } returns null
        every { terminal.pendientesDeOtrasVentas(any()) } returns emptyList()
        coEvery { terminal.fetchOnlineTerminals(any()) } returns TerminalListResult.Success(emptyList())
        val settings = mockk<TpvSettingsRepository>()
        every { settings.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = false)
        val area = mockk<AreaTicketRepository>(relaxed = true)
        every { area.session.current() } returns null
        val storage = mockk<SecureStorage>(relaxed = true).also {
            every { it.venueId } returns "c123456789012345678901234"
            every { it.userId } returns "c223456789012345678901234"
        }
        every { cash.processCashPayment(any(), any()) } returns CashPaymentResult.Success(changeCents = 0)
        every { cola.anotarIntento(any(), any(), any()) } returns true
        return PaymentFlowViewModel(
            tableSession = com.avoqado.pos.tables.data.TableSession(),
            syncOutbox = mockk(relaxed = true),
            orderRepository = order,
            cashPaymentRepository = cash,
            tenderTypeRepository = mockk(relaxed = true),
            terminalPaymentService = terminal,
            tpvSettingsRepository = settings,
            paymentSyncService = mockk(relaxed = true),
            cashDrawerRepository = mockk(relaxed = true),
            kdsRepository = mockk(relaxed = true),
            kdsOrderBus = mockk(relaxed = true),
            printerService = mockk(relaxed = true),
            secureStorage = storage,
            comandaDispatcher = mockk(relaxed = true),
            comandasPendientesStore = ComandasPendientesStore(AlmacenEnMemoria()),
            replayDeComandas = mockk(relaxed = true),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = area,
            cancelacionDeCobro = mockk(relaxed = true),
            savedStateHandle = SavedStateHandle(),
            pendingGrantQueue = cola,
        ).also { almacen.put(java.util.UUID.randomUUID().toString(), it) }
    }

    @After fun limpiar() = almacen.clear()

    private fun anotados(): List<IntentoDeCobro> {
        val capturados = mutableListOf<IntentoDeCobro>()
        verify(atLeast = 0) { cola.anotarIntento(cart.identidadDeVenta, capture(capturados), true) }
        return capturados
    }

    @Test
    fun `efectivo anota su llave antes de cobrar`() = runTest {
        val m = vm()
        m.startPaymentFlow(cart)
        m.selectPaymentMethod(PaymentMethod.CASH)

        m.confirmCashPreset(50000)
        runCurrent()

        val intento = anotados().single()
        assertEquals(IntentoDeCobro.Tipo.EFECTIVO, intento.tipo)
        assertTrue(intento.id.isNotBlank())
    }

    @Test
    fun `ya pago de otra forma se anota aparte - by-key no lo prueba`() = runTest {
        val m = vm()
        m.startPaymentFlow(cart)
        m.selectPaymentMethod(PaymentMethod.CASH)

        m.confirmManualChoice(ManualPaymentChoice.Fixed(ManualPaymentMethod.TRANSFER))
        runCurrent()

        assertEquals(IntentoDeCobro.Tipo.OTRA_FORMA, anotados().single().tipo)
    }

    @Test
    fun `si el disco no acepta el intento el efectivo no empieza`() = runTest {
        val m = vm()
        every { cola.anotarIntento(any(), any(), any()) } returns false
        m.startPaymentFlow(cart)
        m.selectPaymentMethod(PaymentMethod.CASH)

        m.confirmCashPreset(50000)
        runCurrent()

        assertTrue(m.state.value is PaymentFlowState.Error)
        verify(exactly = 0) { cash.processCashPayment(any(), any()) }
    }

    @Test
    fun `tarjeta deja anotar su requestId antes de enviar`() = runTest {
        val m = vm()
        var anotador: ((String) -> Boolean)? = null
        every { terminal.antesDelProximoEnvio = any() } answers { anotador = firstArg() }
        coEvery { terminal.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            assertNotNull("El anotador tiene que estar puesto ANTES de enviar", anotador)
            anotador!!("req-1")
            TerminalPaymentResult.Error("Rechazo de prueba", requestId = "req-1")
        }
        m.startPaymentFlow(cart)
        m.selectPaymentMethod(PaymentMethod.CARD)

        m.selectTerminalAndPay("terminal")
        runCurrent()

        assertTrue(IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1") in anotados())
        verify(exactly = 0) { cola.quitarIntento(any(), any()) }
    }

    @Test
    fun `el intento que el servidor rechazo sin crearlo se quita`() = runTest {
        val m = vm()
        coEvery { terminal.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) } returns
            TerminalPaymentResult.Error("Terminal ocupada", requestId = "req-1", noSeCreo = true)
        m.startPaymentFlow(cart)
        m.selectPaymentMethod(PaymentMethod.CARD)

        m.selectTerminalAndPay("terminal")
        runCurrent()

        verify(exactly = 1) { cola.quitarIntento(cart.identidadDeVenta, IntentoDeCobro(IntentoDeCobro.Tipo.TARJETA, "req-1")) }
        coVerify(exactly = 1) { terminal.sendPaymentToTerminal(any(), any(), any(), any(), any(), any(), any()) }
    }
}
