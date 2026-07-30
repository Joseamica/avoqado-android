package com.avoqado.pos.pos.presentation.cart

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
import com.avoqado.pos.pos.data.StaffMember
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import com.avoqado.pos.pos.data.model.SavedCart
import com.avoqado.pos.referrals.domain.usecase.CaptureReferralUseCase
import com.avoqado.pos.referrals.domain.usecase.ValidateReferralUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Venta por peso en [CartViewModel]: D9 — cada pesada agrega una LÍNEA NUEVA (nunca se fusiona),
 * mientras que un producto normal sigue fusionando cantidad (regresión).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CartViewModelWeightTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
    private val savedCartsRepository = mockk<SavedCartsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val activeCartState = mockk<ActiveCartState>(relaxed = true)
    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val staffRepository = mockk<StaffRepository>(relaxed = true)
    private val classCheckoutSeed = mockk<ClassCheckoutSeed>(relaxed = true)
    private val validateReferralUseCase = mockk<ValidateReferralUseCase>(relaxed = true)
    private val captureReferralUseCase = mockk<CaptureReferralUseCase>(relaxed = true)

    private val venueSwitchedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Before
    fun setup() {
        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { savedCartsRepository.savedCarts } returns MutableStateFlow<List<SavedCart>>(emptyList())
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns venueSwitchedFlow
        every { classCheckoutSeed.consume() } returns null
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.userId } returns "user-1"
        every { secureStorage.userFirstName } returns "Jose"
        every { secureStorage.userLastName } returns "Tester"
        every { secureStorage.userEmail } returns "jose@example.com"
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-99"
        every { secureStorage.selectedStaffNameForCurrentVenue } returns "Jose Tester"
        coEvery { staffRepository.getActiveStaff() } returns Result.success(
            listOf(StaffMember(id = "staff-99", firstName = "Jose", lastName = "Tester")),
        )
    }

    private fun createViewModel(): CartViewModel = CartViewModel(
        productsRepository = productsRepository,
        discountsRepository = discountsRepository,
        savedCartsRepository = savedCartsRepository,
        authRepository = authRepository,
        secureStorage = secureStorage,
        activeCartState = activeCartState,
        orderRepository = orderRepository,
        staffRepository = staffRepository,
        classCheckoutSeed = classCheckoutSeed,
        validateReferralUseCase = validateReferralUseCase,
        captureReferralUseCase = captureReferralUseCase,
        planManager = PlanManager(secureStorage),
        tableSession = com.avoqado.pos.tables.data.TableSession(),
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
        areaTicketRepository = mockk(relaxed = true),
    )

    private val jamon = Product(
        id = "jamon",
        name = "Jamón serrano",
        priceValue = 420.0,
        soldByWeight = true,
        unit = "KILOGRAM",
    )

    private val burger = Product(
        id = "burger",
        name = "Hamburguesa",
        priceValue = 150.0,
    )

    @Test
    fun `two weighings of the same product create two separate lines (D9)`() {
        val vm = createViewModel()
        vm.addProductByWeight(jamon, 0.435)
        vm.addProductByWeight(jamon, 0.512)

        val items = vm.cartState.value.items
        assertEquals(2, items.size)
        assertEquals(0.435, items[0].weightKg!!, 0.0)
        assertEquals(0.512, items[1].weightKg!!, 0.0)
        // $182.70 + $215.04
        assertEquals(18270 + 21504, vm.cartState.value.subtotalCents)
    }

    @Test
    fun `weighted line carries price per kg as unitPrice and quantity 1`() {
        val vm = createViewModel()
        vm.addProductByWeight(jamon, 0.435)

        val item = vm.cartState.value.items.single()
        assertEquals(42000, item.unitPrice)
        assertEquals(1, item.quantity)
        assertEquals(18270, item.totalPrice)
    }

    @Test
    fun `zero weight is ignored (no line added)`() {
        val vm = createViewModel()
        vm.addProductByWeight(jamon, 0.0)
        assertEquals(0, vm.cartState.value.items.size)
    }

    @Test
    fun `normal product still merges into a single line with summed quantity (regression)`() {
        val vm = createViewModel()
        vm.addProduct(burger)
        vm.addProduct(burger)

        val items = vm.cartState.value.items
        assertEquals(1, items.size)
        assertEquals(2, items.single().quantity)
        assertNull(items.single().weightKg)
    }

    @Test
    fun `weighted and normal lines coexist without merging`() {
        val vm = createViewModel()
        vm.addProductByWeight(jamon, 0.435)
        vm.addProduct(burger)
        vm.addProductByWeight(jamon, 0.512)

        val items = vm.cartState.value.items
        assertEquals(3, items.size)
        assertNotNull(items[0].weightKg)
        assertNull(items[1].weightKg)
        assertNotNull(items[2].weightKg)
    }
}
