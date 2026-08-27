package com.avoqado.pos.pos

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.payment.data.OrderRepository
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
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Agotado AVISA, nunca bloquea (Square-parity, decisión founder+Claude
 * 2026-08-12): el registro del sistema puede estar desfasado y el producto sí
 * existir en el anaquel. Antes `addProduct` hacía `return` en silencio con
 * `isOutOfStock` — el cajero tocaba el tile y no pasaba NADA: ni item en el
 * carrito ni explicación. El stock queda en negativo como señal de descuadre.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CartViewModelOutOfStockTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
    private val savedCartsRepository = mockk<SavedCartsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val staffRepository = mockk<StaffRepository>(relaxed = true)

    private val agotado = Product(
        id = "prod-corona",
        name = "Cerveza Corona",
        trackInventory = true,
        availableQuantity = 0,
    )

    private val sinSeguimiento = Product(
        id = "prod-servicio",
        name = "Corte de pelo",
        trackInventory = null,
        availableQuantity = null,
    )

    @Before
    fun setup() {
        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { savedCartsRepository.savedCarts } returns MutableStateFlow<List<SavedCart>>(emptyList())
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        every { secureStorage.venueId } returns "venue-1"
        // El init llama fetchStaff(): un mock relaxed devuelve Object y truena
        // el cast a Result<List<StaffMember>> — hay que dárselo explícito.
        io.mockk.coEvery { staffRepository.getActiveStaff() } returns Result.success(emptyList())
    }

    private fun createViewModel(): CartViewModel = CartViewModel(
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
    )

    @Test
    fun `un producto agotado SI se agrega al carrito`() = runTest {
        val viewModel = createViewModel()

        viewModel.addProduct(agotado)

        assertEquals(1, viewModel.cartState.value.items.size)
        assertEquals("Cerveza Corona", viewModel.cartState.value.items.first().name)
    }

    @Test
    fun `agregar un agotado dispara el aviso ambar, y consumirlo lo limpia`() = runTest {
        val viewModel = createViewModel()

        viewModel.addProduct(agotado)

        assertNotNull(viewModel.stockWarning.value)
        assertTrue(viewModel.stockWarning.value!!.contains("Cerveza Corona"))

        viewModel.consumeStockWarning()
        assertNull(viewModel.stockWarning.value)
    }

    // ── Regresión ────────────────────────────────────────────────────────────
    @Test
    fun `un producto sin seguimiento de inventario no avisa nada`() = runTest {
        val viewModel = createViewModel()

        viewModel.addProduct(sinSeguimiento)

        assertEquals(1, viewModel.cartState.value.items.size)
        assertNull(viewModel.stockWarning.value)
    }
}
