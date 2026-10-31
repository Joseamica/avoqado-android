package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.EstadoDeParte
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.estadoDeParte
import com.avoqado.pos.pos.data.ActiveCartState
import com.avoqado.pos.pos.data.ClassCheckoutSeed
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import com.avoqado.pos.pos.data.model.SavedCart
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

import com.avoqado.pos.pos.presentation.cart.CartViewModel
import com.avoqado.pos.pos.presentation.cart.PARTE_SIN_RED_VENTA_PAGADA
import org.json.JSONObject
/** GET -> real result SQL -> real observarParte -> unchanged cart observer. No synthetic Subida emission. */
@OptIn(ExperimentalCoroutinesApi::class)
class Q15RecoveredCartTest {
    // SQL publica desde IO; el observer del carrito debe terminar en el scheduler antes de leer su transición PAID.
    @get:Rule val mainDispatcherRule = MainDispatcherRule(StandardTestDispatcher())
    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
    private val savedCartsRepository = mockk<SavedCartsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val staffRepository = mockk<StaffRepository>(relaxed = true)

    @Before
    fun setup() {
        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { savedCartsRepository.savedCarts } returns MutableStateFlow<List<SavedCart>>(emptyList())
        every { savedCartsRepository.saveCart(any()) } returns true
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        every { secureStorage.venueId } returns "venue-1"
        coEvery { staffRepository.getActiveStaff() } returns Result.success(emptyList())
    }

    private fun createViewModel(cashPaymentRepository: CashPaymentRepository, monitor: ConnectivityMonitor? = null): CartViewModel = CartViewModel(
        productsRepository = productsRepository,
        discountsRepository = discountsRepository,
        savedCartsRepository = savedCartsRepository,
        authRepository = authRepository,
        secureStorage = secureStorage,
        activeCartState = mockk<ActiveCartState>(relaxed = true),
        orderRepository = mockk<OrderRepository>(relaxed = true),
        staffRepository = staffRepository,
        classCheckoutSeed = mockk<ClassCheckoutSeed>(relaxed = true).also { every { it.consume() } returns null },
        validateReferralUseCase = mockk(relaxed = true),
        captureReferralUseCase = mockk(relaxed = true),
        planManager = PlanManager(secureStorage),
        tableSession = com.avoqado.pos.tables.data.TableSession(),
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
        areaTicketRepository = mockk(relaxed = true),
        walletScanRepository = mockk(relaxed = true),
        cashPaymentRepository = cashPaymentRepository,
        connectivityMonitor = monitor,
    )


    @Test fun paid_order_after_another_payment_closes_cart_even_when_current_snapshot_has_positive_balance() = runTest {
        for(saldo in listOf(0,9500)) Q15RecoveryByKeyTest.Harness().use { h ->
            h.save(Q15RecoveryByKeyTest.source())
            h.response=JSONObject(Q15RecoveryByKeyTest.proof()).apply { getJSONObject("data")
                .put("remainingBalanceCents",saldo).put("orderPaymentStatus","PAID")
                .put("orderTotalCents",80000).put("totalPaidCents",90000) }.toString()
            val vm=createViewModel(CashPaymentRepository(h.f.storage,h.f.dao))
            vm.clearCart(); vm.addCustomAmount("Saldo pendiente",6000); vm.esperarParteEnCola(Q15RecoveryByKeyTest.KEY)
            runCurrent(); assertTrue(vm.detenerCobroPorParteEnEspera())
            val before=vm.cartState.value
            h.first(); runCurrent()
            assertTrue("saldo=$saldo source=${h.read()} part=${CashPaymentRepository(h.f.storage,h.f.dao).observarParte(Q15RecoveryByKeyTest.KEY).first()} before=$before after=${vm.cartState.value} aviso=${vm.avisoDeVales.value} espera=${vm.mensajeDeParteEnEspera()}",vm.cartState.value.items.isEmpty())
            assertEquals(PARTE_SIN_RED_VENTA_PAGADA,vm.avisoDeVales.value)
            assertNull(vm.resolvePendingSplitOrderForCharge()); h.exactGetOnly()
        }
    }

    @Test fun different_current_balance_blocks_extra_payment_and_keeps_cart_unchanged() = runTest {
        Q15RecoveryByKeyTest.Harness().use { h ->
            h.save(Q15RecoveryByKeyTest.source())
            h.response=JSONObject(Q15RecoveryByKeyTest.proof()).apply { getJSONObject("data")
                .put("remainingBalanceCents",9500).put("orderPaymentStatus","PARTIAL") }.toString()
            val vm=createViewModel(CashPaymentRepository(h.f.storage,h.f.dao))
            vm.clearCart(); vm.addCustomAmount("Saldo pendiente",6000); vm.esperarParteEnCola(Q15RecoveryByKeyTest.KEY)
            runCurrent(); val before=vm.cartState.value
            h.first(); runCurrent()
            assertEquals(before,vm.cartState.value); assertTrue(vm.detenerCobroPorParteEnEspera())
            assertNull(vm.resolvePendingSplitOrderForCharge()); h.exactGetOnly()
        }
    }
}
