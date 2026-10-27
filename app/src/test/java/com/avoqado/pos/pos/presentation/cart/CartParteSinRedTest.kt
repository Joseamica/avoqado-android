package com.avoqado.pos.pos.presentation.cart

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
 * D1 (26-oct, founder «B»): una venta de tienda dividida cuya parte 1 se cobró en efectivo y se guardó SIN RED. El resto
 * espera a que esa parte suba y se cobra contra la orden que le dé el servidor (una sola venta). Y dividir en tienda pide
 * conexión con Avoqado.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CartParteSinRedTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
    private val savedCartsRepository = mockk<SavedCartsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val staffRepository = mockk<StaffRepository>(relaxed = true)
    private val cashPaymentRepository = mockk<CashPaymentRepository>(relaxed = true)
    private val parte = MutableStateFlow<EstadoDeParte>(EstadoDeParte.Subiendo)

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
        every { cashPaymentRepository.observarParte("cola-1") } returns parte
    }

    private fun createViewModel(monitor: ConnectivityMonitor? = null): CartViewModel = CartViewModel(
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

    /** Deja el carrito como queda tras una parte 1 de $40 guardada sin red: «Saldo pendiente $60» esperando. */
    private fun CartViewModel.parteGuardadaSinRed(saldoCents: Int = 6000) {
        clearCart()
        addCustomAmount(name = "Saldo pendiente", amountCents = saldoCents)
        esperarParteEnCola("cola-1")
    }

    // ── Mientras la parte 1 no sube ────────────────────────────────────────────

    @Test
    fun `mientras la parte sin red no sube, el resto no se cobra y lo dice`() = runTest {
        val vm = createViewModel()
        vm.parteGuardadaSinRed()
        advanceUntilIdle()

        assertTrue(vm.detenerCobroPorParteEnEspera())
        assertEquals(PARTE_SIN_RED_SUBIENDO, vm.avisoDeVales.value)
        assertNull(vm.resolvePendingSplitOrderForCharge())
    }

    @Test
    fun `mientras espera es una venta a medias - el premio no se cambia`() = runTest {
        val vm = createViewModel()
        vm.parteGuardadaSinRed()
        advanceUntilIdle()

        assertTrue(vm.hayVentaAMedias())
        assertFalse(vm.puedeCambiarPremio())
    }

    @Test
    fun `una parte por revisar dice que la resuelve un gerente, no que espere`() = runTest {
        val vm = createViewModel()
        vm.parteGuardadaSinRed()
        parte.value = EstadoDeParte.PorRevisar
        advanceUntilIdle()

        assertTrue(vm.detenerCobroPorParteEnEspera())
        assertEquals(PARTE_SIN_RED_POR_REVISAR, vm.avisoDeVales.value)
    }

    // ── Cuando sube ────────────────────────────────────────────────────────────

    @Test
    fun `al subir, el resto se cobra contra la orden que le dio el servidor`() = runTest {
        val vm = createViewModel()
        vm.parteGuardadaSinRed()
        parte.value = EstadoDeParte.Subida(orderId = "orden-1", saldoCents = 6000)
        advanceUntilIdle()

        assertFalse(vm.detenerCobroPorParteEnEspera())
        assertEquals("orden-1", vm.resolvePendingSplitOrderForCharge())
        assertEquals(6000, vm.cartState.value.totalCents)
    }

    @Test
    fun `si el servidor dice otro saldo, nada se cobra ni se ajusta solo`() = runTest {
        val vm = createViewModel()
        vm.parteGuardadaSinRed(saldoCents = 6000)
        parte.value = EstadoDeParte.Subida(orderId = "orden-1", saldoCents = 16000)
        advanceUntilIdle()

        assertEquals(6000, vm.cartState.value.totalCents)
        assertTrue(vm.detenerCobroPorParteEnEspera())
        assertEquals(parteSinRedSaldoDistinto(6000, 16000), vm.avisoDeVales.value)
        assertNull(vm.resolvePendingSplitOrderForCharge())
    }

    @Test
    fun `un centavo de diferencia no detiene - el servidor lo perdona`() = runTest {
        val vm = createViewModel()
        vm.parteGuardadaSinRed(saldoCents = 6000)
        parte.value = EstadoDeParte.Subida(orderId = "orden-1", saldoCents = 6001)
        advanceUntilIdle()

        assertFalse(vm.detenerCobroPorParteEnEspera())
        assertEquals("orden-1", vm.resolvePendingSplitOrderForCharge())
    }

    @Test
    fun `si al subir la venta ya quedó pagada, termina y suelta al cliente`() = runTest {
        val vm = createViewModel()
        vm.parteGuardadaSinRed()
        vm.setSelectedCustomer("c-1", "Ana")
        parte.value = EstadoDeParte.Subida(orderId = "orden-1", saldoCents = 0)
        advanceUntilIdle()

        assertTrue(vm.cartState.value.isEmpty)
        assertNull(vm.selectedCustomer.value)
        assertFalse(vm.hayVentaAMedias())
        assertEquals(PARTE_SIN_RED_VENTA_PAGADA, vm.avisoDeVales.value)
    }

    @Test
    fun `PAID con un centavo perdonado tambien es venta pagada`() = runTest {
        val vm = createViewModel()
        vm.parteGuardadaSinRed()
        parte.value = EstadoDeParte.Subida(orderId = "orden-1", saldoCents = 1, pagada = true)
        advanceUntilIdle()

        assertTrue(vm.cartState.value.isEmpty)
        assertFalse(vm.hayVentaAMedias())
    }

    @Test
    fun `pagada con algo agregado mientras esperaba - lo agregado se queda en el carrito`() = runTest {
        val vm = createViewModel()
        vm.parteGuardadaSinRed(saldoCents = 6000)
        vm.addCustomAmount(name = "Otra cosa", amountCents = 2500)
        parte.value = EstadoDeParte.Subida(orderId = "orden-1", saldoCents = 0, pagada = true)
        advanceUntilIdle()

        assertEquals(8500, vm.cartState.value.totalCents)
        assertFalse(vm.hayVentaAMedias())
        assertEquals(PARTE_SIN_RED_PAGADA_CON_ALGO_MAS, vm.avisoDeVales.value)
    }

    @Test
    fun `tras subir, un descuento al saldo detiene el cobro (la foto es exacta)`() = runTest {
        val vm = createViewModel()
        vm.parteGuardadaSinRed()
        parte.value = EstadoDeParte.Subida(orderId = "orden-1", saldoCents = 6000)
        advanceUntilIdle()

        vm.applyOrderDiscount(Discount(id = "d-6", name = "Descuento", value = 6.0, type = "FIXED"))

        assertTrue(vm.detenerCobroDeVentaCambiada())
    }

    // ── Desenlaces sin orden ───────────────────────────────────────────────────

    @Test
    fun `una venta sin productos queda fuera - el resto es otro cobro rapido como antes`() = runTest {
        val vm = createViewModel()
        vm.parteGuardadaSinRed()
        parte.value = EstadoDeParte.SinOrden
        advanceUntilIdle()

        assertFalse(vm.hayVentaAMedias())
        assertFalse(vm.detenerCobroPorParteEnEspera())
        assertNull(vm.avisoDeVales.value)
    }

    @Test
    fun `si la parte ya no está en el aparato, se suelta con aviso`() = runTest {
        val vm = createViewModel()
        vm.parteGuardadaSinRed()
        parte.value = EstadoDeParte.NoEsta
        advanceUntilIdle()

        assertFalse(vm.hayVentaAMedias())
        assertEquals(PARTE_SIN_RED_YA_NO_ESTA, vm.avisoDeVales.value)
    }

    @Test
    fun `vaciar el carrito suelta la espera - la venta siguiente no la hereda`() = runTest {
        val vm = createViewModel()
        vm.parteGuardadaSinRed()
        advanceUntilIdle()

        vm.clearCart()
        parte.value = EstadoDeParte.Subida(orderId = "orden-1", saldoCents = 6000)
        advanceUntilIdle()

        assertNull(vm.resolvePendingSplitOrderForCharge())
        assertFalse(vm.detenerCobroPorParteEnEspera())
    }

    // ── Dividir pide conexión ──────────────────────────────────────────────────

    @Test
    fun `sin conexión con Avoqado, dividir no abre y lo dice`() = runTest {
        val monitor = mockk<ConnectivityMonitor>(relaxed = true)
        every { monitor.isConnected } returns MutableStateFlow(true)
        every { monitor.isServerReachable } returns MutableStateFlow(false)
        val vm = createViewModel(monitor)

        assertTrue(vm.detenerDividirSinConexion())
        assertEquals(DIVIDIR_SIN_CONEXION, vm.avisoDeVales.value)
    }

    @Test
    fun `con conexión, dividir se abre como siempre`() = runTest {
        val monitor = mockk<ConnectivityMonitor>(relaxed = true)
        every { monitor.isConnected } returns MutableStateFlow(true)
        every { monitor.isServerReachable } returns MutableStateFlow(true)
        val vm = createViewModel(monitor)

        assertFalse(vm.detenerDividirSinConexion())
        assertNull(vm.avisoDeVales.value)
    }

    // ── La lectura de la fila de la cola ───────────────────────────────────────

    private fun fila(
        status: PaymentSyncStatus,
        tipo: String = "ORDER",
        resultado: String? = null,
        q15Reason: String? = null,
    ) = PendingPaymentEntity(
        id = "cola-1", venueId = "venue-1", staffId = "s", amountCents = 4000, tipCents = 0, splitType = "CUSTOMAMOUNT",
        method = "CASH", paymentType = tipo, syncStatus = status.name, q15ResultJson = resultado, q15Reason = q15Reason,
    )

    @Test
    fun `la fila se lee por su estado`() {
        assertEquals(EstadoDeParte.NoEsta, estadoDeParte(null))
        assertEquals(EstadoDeParte.Subiendo, estadoDeParte(fila(PaymentSyncStatus.PENDING)))
        assertEquals(EstadoDeParte.Subiendo, estadoDeParte(fila(PaymentSyncStatus.SYNCING)))
        assertEquals(EstadoDeParte.PorRevisar, estadoDeParte(fila(PaymentSyncStatus.PENDING, q15Reason = "PRICE_PROOF_PENDING")))
        assertEquals(EstadoDeParte.PorRevisar, estadoDeParte(fila(PaymentSyncStatus.FAILED)))
        assertEquals(EstadoDeParte.PorRevisar, estadoDeParte(fila(PaymentSyncStatus.EN_VUELO)))
        assertEquals(EstadoDeParte.SinOrden, estadoDeParte(fila(PaymentSyncStatus.PENDING, tipo = "FAST")))
    }

    @Test
    fun `una fila subida da la orden y el saldo que dijo el servidor`() {
        val subida = fila(PaymentSyncStatus.SYNCED, resultado = """{"paymentId":"p-1","orderId":"orden-1","saldoCents":6000,"estadoDeLaOrden":"PARTIAL"}""")
        assertEquals(EstadoDeParte.Subida("orden-1", 6000, pagada = false), estadoDeParte(subida))
        val pagada = fila(PaymentSyncStatus.SYNCED, resultado = """{"paymentId":"p-1","orderId":"orden-1","saldoCents":1,"estadoDeLaOrden":"PAID"}""")
        assertEquals(EstadoDeParte.Subida("orden-1", 1, pagada = true), estadoDeParte(pagada))
        // Subió pero sin decir su orden: no se adivina, lo revisa una persona.
        assertEquals(EstadoDeParte.PorRevisar, estadoDeParte(fila(PaymentSyncStatus.SYNCED, resultado = """{"paymentId":"p-1"}""")))
    }
}
