package com.avoqado.pos.referrals

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
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import com.avoqado.pos.pos.data.model.SavedCart
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import com.avoqado.pos.referrals.domain.model.ValidationResult
import com.avoqado.pos.referrals.domain.repository.ReferralValidationException
import com.avoqado.pos.referrals.domain.usecase.CaptureReferralUseCase
import com.avoqado.pos.referrals.domain.usecase.ValidateReferralUseCase
import com.avoqado.pos.referrals.presentation.ReferralCaptureUiState
import io.mockk.coEvery
import io.mockk.coVerify
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
 * Unit tests for the Plan 5B referrals capture wiring on [CartViewModel].
 *
 * Covers the V1 surface:
 * - happy path (Valid response → state + discount applied)
 * - each rejection [ValidationResult.Reason]
 * - no-customer guard (validate is a no-op)
 * - network failure surfaces UNKNOWN
 * - clearReferral wipes the discount when source matches, leaves manual
 *   discounts alone
 * - editing the code after a result resets state to Idle
 * - captureReferralOnPayment no-ops when state isn't Valid
 * - captureReferralOnPayment surfaces ReferralValidationException as Invalid
 *
 * Note on the forceOverride affordance: v1 keeps the UI button disabled, so
 * there's no ViewModel surface yet. Those tests will land with v2.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CartViewModelReferralTest {

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
        // Mock CartViewModel dependencies so the constructor doesn't crash.
        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { savedCartsRepository.savedCarts } returns MutableStateFlow<List<SavedCart>>(emptyList())
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns venueSwitchedFlow
        every { classCheckoutSeed.consume() } returns null

        // SecureStorage defaults
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.userId } returns "user-1"
        every { secureStorage.userFirstName } returns "Jose"
        every { secureStorage.userLastName } returns "Tester"
        every { secureStorage.userEmail } returns "jose@example.com"
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-99"
        every { secureStorage.selectedStaffNameForCurrentVenue } returns "Jose Tester"

        // StaffRepository.getActiveStaff is called from init
        coEvery { staffRepository.getActiveStaff() } returns Result.success(
            listOf(
                StaffMember(
                    id = "staff-99",
                    firstName = "Jose",
                    lastName = "Tester",
                ),
            ),
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
        // Relaxed SecureStorage mock → planTier null → fail-open (allowed).
        planManager = PlanManager(secureStorage),
        tableSession = com.avoqado.pos.tables.data.TableSession(),
    )

    private suspend fun selectCustomer(viewModel: CartViewModel, id: String = "cust-7") {
        viewModel.setSelectedCustomer(id)
    }

    // MARK: - Happy path

    @Test
    fun `validate Valid response sets state to Valid and applies referral discount`() = runTest {
        coEvery {
            validateReferralUseCase(any(), any(), any())
        } returns Result.success(
            ValidationResult.Valid(
                referrerName = "Ana López",
                discountPercent = 10,
                referrerCustomerId = "cust-ana",
            ),
        )

        val viewModel = createViewModel()
        selectCustomer(viewModel)
        viewModel.onReferralCodeChange("ANA-2026")

        viewModel.validateReferralCode()
        advanceUntilIdle()

        val state = viewModel.referralValidation.value
        assertTrue("expected Valid, got $state", state is ReferralCaptureUiState.Valid)
        state as ReferralCaptureUiState.Valid
        assertEquals("Ana López", state.referrerName)
        assertEquals(10, state.discountPercent)

        // Discount applied to the cart with the right source tag.
        val discount = viewModel.cartState.value.orderDiscount
        assertEquals("REFERRAL_NEW_CUSTOMER", discount?.source)
        assertEquals(10.0, discount?.value ?: -1.0, 0.0)
        assertEquals("PERCENTAGE", discount?.type)
    }

    // MARK: - Each rejection reason

    @Test
    fun `PROGRAM_INACTIVE rejection flips to Invalid and does not apply discount`() = runTest {
        verifyRejectionReason(ValidationResult.Reason.PROGRAM_INACTIVE)
    }

    @Test
    fun `CODE_NOT_FOUND rejection flips to Invalid and does not apply discount`() = runTest {
        verifyRejectionReason(ValidationResult.Reason.CODE_NOT_FOUND)
    }

    @Test
    fun `SELF_REFERRAL rejection flips to Invalid and does not apply discount`() = runTest {
        verifyRejectionReason(ValidationResult.Reason.SELF_REFERRAL)
    }

    @Test
    fun `EXISTING_CUSTOMER rejection flips to Invalid and does not apply discount`() = runTest {
        verifyRejectionReason(ValidationResult.Reason.EXISTING_CUSTOMER)
    }

    private fun verifyRejectionReason(reason: ValidationResult.Reason) = runTest {
        coEvery {
            validateReferralUseCase(any(), any(), any())
        } returns Result.success(ValidationResult.Invalid(reason))

        val viewModel = createViewModel()
        selectCustomer(viewModel)
        viewModel.onReferralCodeChange("REJECT-ME")

        viewModel.validateReferralCode()
        advanceUntilIdle()

        val state = viewModel.referralValidation.value
        assertTrue("expected Invalid, got $state", state is ReferralCaptureUiState.Invalid)
        assertEquals(reason, (state as ReferralCaptureUiState.Invalid).reason)
        assertNull(viewModel.cartState.value.orderDiscount)
    }

    // MARK: - No-customer guard

    @Test
    fun `validate is a no-op when no customer is selected`() = runTest {
        val viewModel = createViewModel()
        viewModel.onReferralCodeChange("ANA-2026")
        // intentionally no setSelectedCustomer

        viewModel.validateReferralCode()
        advanceUntilIdle()

        assertEquals(ReferralCaptureUiState.Idle, viewModel.referralValidation.value)
        coVerify(exactly = 0) { validateReferralUseCase(any(), any(), any()) }
    }

    // MARK: - Network failure → UNKNOWN

    @Test
    fun `validate transport failure surfaces UNKNOWN reason and clears discount`() = runTest {
        coEvery {
            validateReferralUseCase(any(), any(), any())
        } returns Result.failure(RuntimeException("boom"))

        val viewModel = createViewModel()
        selectCustomer(viewModel)
        viewModel.onReferralCodeChange("ANY")

        viewModel.validateReferralCode()
        advanceUntilIdle()

        val state = viewModel.referralValidation.value
        assertTrue(state is ReferralCaptureUiState.Invalid)
        assertEquals(
            ValidationResult.Reason.UNKNOWN,
            (state as ReferralCaptureUiState.Invalid).reason,
        )
        assertNull(viewModel.cartState.value.orderDiscount)
    }

    // MARK: - clearReferral semantics

    @Test
    fun `clearReferral wipes the cart discount when source is the referral tag`() = runTest {
        coEvery {
            validateReferralUseCase(any(), any(), any())
        } returns Result.success(
            ValidationResult.Valid(
                referrerName = "Ana",
                discountPercent = 15,
                referrerCustomerId = "cust-ana",
            ),
        )

        val viewModel = createViewModel()
        selectCustomer(viewModel)
        viewModel.onReferralCodeChange("ANA-2026")
        viewModel.validateReferralCode()
        advanceUntilIdle()
        assertEquals("REFERRAL_NEW_CUSTOMER", viewModel.cartState.value.orderDiscount?.source)

        viewModel.clearReferral()

        assertEquals(ReferralCaptureUiState.Idle, viewModel.referralValidation.value)
        assertEquals("", viewModel.referralCode.value)
        assertNull(viewModel.cartState.value.orderDiscount)
    }

    @Test
    fun `clearReferral does NOT wipe a manual order discount`() = runTest {
        val viewModel = createViewModel()
        // Cashier applies a manual ORDER discount (no source tag).
        val manual = Discount(
            id = "manual-1",
            name = "Promo casa",
            value = 5.0,
            type = "PERCENTAGE",
            scope = "ORDER",
        )
        viewModel.applyOrderDiscount(manual)
        assertEquals(manual, viewModel.cartState.value.orderDiscount)

        viewModel.clearReferral()

        // Untouched — the discount didn't come from a referral.
        assertEquals(manual, viewModel.cartState.value.orderDiscount)
    }

    // MARK: - Editing code after a result

    @Test
    fun `editing code after a Valid result resets state to Idle`() = runTest {
        coEvery {
            validateReferralUseCase(any(), any(), any())
        } returns Result.success(
            ValidationResult.Valid(
                referrerName = "Ana",
                discountPercent = 10,
                referrerCustomerId = "cust-ana",
            ),
        )

        val viewModel = createViewModel()
        selectCustomer(viewModel)
        viewModel.onReferralCodeChange("ANA-2026")
        viewModel.validateReferralCode()
        advanceUntilIdle()
        assertTrue(viewModel.referralValidation.value is ReferralCaptureUiState.Valid)

        viewModel.onReferralCodeChange("ANA-2027")

        assertEquals(ReferralCaptureUiState.Idle, viewModel.referralValidation.value)
    }

    // MARK: - Customer switch invalidates

    @Test
    fun `switching customer clears cached referral`() = runTest {
        coEvery {
            validateReferralUseCase(any(), any(), any())
        } returns Result.success(
            ValidationResult.Valid(
                referrerName = "Ana",
                discountPercent = 10,
                referrerCustomerId = "cust-ana",
            ),
        )

        val viewModel = createViewModel()
        viewModel.setSelectedCustomer("cust-7")
        viewModel.onReferralCodeChange("ANA-2026")
        viewModel.validateReferralCode()
        advanceUntilIdle()
        assertTrue(viewModel.referralValidation.value is ReferralCaptureUiState.Valid)

        viewModel.setSelectedCustomer("cust-other")

        assertEquals(ReferralCaptureUiState.Idle, viewModel.referralValidation.value)
        assertEquals("", viewModel.referralCode.value)
        assertNull(viewModel.cartState.value.orderDiscount)
    }

    // MARK: - captureReferralOnPayment

    @Test
    fun `captureReferralOnPayment is a no-op when state is not Valid`() = runTest {
        val viewModel = createViewModel()
        selectCustomer(viewModel)
        // referralValidation stays Idle

        val ok = viewModel.captureReferralOnPayment(orderId = "order-1")

        assertTrue(ok)
        coVerify(exactly = 0) {
            captureReferralUseCase(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `captureReferralOnPayment calls use case when state is Valid`() = runTest {
        coEvery {
            validateReferralUseCase(any(), any(), any())
        } returns Result.success(
            ValidationResult.Valid(
                referrerName = "Ana",
                discountPercent = 10,
                referrerCustomerId = "cust-ana",
            ),
        )
        coEvery {
            captureReferralUseCase(any(), any(), any(), any(), any())
        } returns Result.success("ref-id-123")

        val viewModel = createViewModel()
        selectCustomer(viewModel, id = "cust-7")
        viewModel.onReferralCodeChange("ANA-2026")
        viewModel.validateReferralCode()
        advanceUntilIdle()

        val ok = viewModel.captureReferralOnPayment(orderId = "order-1")

        assertTrue(ok)
        coVerify {
            captureReferralUseCase(
                venueId = "venue-1",
                referralCode = "ANA-2026",
                newCustomerId = "cust-7",
                capturedByStaffVenueId = any(), // staff-99 (selectedStaffId) or userId fallback
                intendedOrderId = "order-1",
            )
        }
    }

    @Test
    fun `captureReferralOnPayment surfaces ReferralValidationException as Invalid state`() = runTest {
        coEvery {
            validateReferralUseCase(any(), any(), any())
        } returns Result.success(
            ValidationResult.Valid(
                referrerName = "Ana",
                discountPercent = 10,
                referrerCustomerId = "cust-ana",
            ),
        )
        coEvery {
            captureReferralUseCase(any(), any(), any(), any(), any())
        } returns Result.failure(
            ReferralValidationException(ValidationResult.Reason.EXISTING_CUSTOMER),
        )

        val viewModel = createViewModel()
        selectCustomer(viewModel)
        viewModel.onReferralCodeChange("ANA-2026")
        viewModel.validateReferralCode()
        advanceUntilIdle()

        val ok = viewModel.captureReferralOnPayment(orderId = "order-1")

        assertFalse(ok)
        val state = viewModel.referralValidation.value
        assertTrue(state is ReferralCaptureUiState.Invalid)
        assertEquals(
            ValidationResult.Reason.EXISTING_CUSTOMER,
            (state as ReferralCaptureUiState.Invalid).reason,
        )
        assertNull(viewModel.cartState.value.orderDiscount)
    }

    @Test
    fun `clearCart wipes referral state too`() = runTest {
        coEvery {
            validateReferralUseCase(any(), any(), any())
        } returns Result.success(
            ValidationResult.Valid(
                referrerName = "Ana",
                discountPercent = 10,
                referrerCustomerId = "cust-ana",
            ),
        )

        val viewModel = createViewModel()
        selectCustomer(viewModel)
        viewModel.onReferralCodeChange("ANA-2026")
        viewModel.validateReferralCode()
        advanceUntilIdle()
        viewModel.addCustomAmount("test", 1000)

        viewModel.clearCart()

        assertEquals("", viewModel.referralCode.value)
        assertEquals(ReferralCaptureUiState.Idle, viewModel.referralValidation.value)
        assertTrue(viewModel.cartState.value.items.isEmpty())
        assertNull(viewModel.cartState.value.orderDiscount)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun productOf(id: String): CartItem = CartItem(
        type = CartItemType.ProductItem(id),
        name = "Item",
        unitPrice = 100,
    )

    // MARK: - Plan gating (REFERRAL_PROGRAM, Pro)

    @Test
    fun `referralPlanAllowed is false on FREE plan`() = runTest {
        every { secureStorage.planTier } returns "FREE"
        every { secureStorage.planExempt } returns false
        val viewModel = createViewModel()
        assertFalse(viewModel.referralPlanAllowed)
    }

    @Test
    fun `referralPlanAllowed is true on PRO plan`() = runTest {
        every { secureStorage.planTier } returns "PRO"
        every { secureStorage.planExempt } returns false
        val viewModel = createViewModel()
        assertTrue(viewModel.referralPlanAllowed)
    }

    @Test
    fun `referralPlanAllowed fails open when plan absent`() = runTest {
        every { secureStorage.planTier } returns null
        every { secureStorage.planExempt } returns false
        val viewModel = createViewModel()
        assertTrue(viewModel.referralPlanAllowed)
    }
}
