package com.avoqado.pos.reservations.presentation.onboarding

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.reservations.domain.VenueMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivateReservationsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun storageWith(planTier: String?, planExempt: Boolean = false): SecureStorage {
        val storage: SecureStorage = mockk(relaxed = true)
        every { storage.planTier } returns planTier
        every { storage.planExempt } returns planExempt
        return storage
    }

    @Test
    fun `activate persists reservationsEnabled and switches to RESERVATIONS mode`() = runTest(dispatcher) {
        val storage = storageWith(planTier = null) // fail-open: plan unknown
        val posModeManager: com.avoqado.pos.settings.domain.PosModeManager = mockk(relaxed = true)
        val vm = ActivateReservationsViewModel(storage, PlanManager(storage), posModeManager)

        vm.activate()
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.didSucceed)
        assertEquals(false, s.isActivating)
        verify { storage.reservationsEnabled = true }
        // El modo vive en PosModeManager (selector único); el VenueMode legacy
        // ya no se escribe — escribir solo el legacy dejaba Calendario invisible.
        verify { posModeManager.switchMode(com.avoqado.pos.settings.domain.PosMode.RESERVATIONS) }
    }

    @Test
    fun `activate ignored after success`() = runTest(dispatcher) {
        val storage = storageWith(planTier = null)
        val vm = ActivateReservationsViewModel(storage, PlanManager(storage), mockk(relaxed = true))

        vm.activate(); advanceUntilIdle()
        vm.activate(); advanceUntilIdle()

        // setter only called once for each prop
        verify(exactly = 1) { storage.reservationsEnabled = true }
    }

    // MARK: - Plan gating (RESERVATIONS, Pro)

    @Test
    fun `activate refuses on FREE plan and never flips local toggle`() = runTest(dispatcher) {
        val storage = storageWith(planTier = "FREE")
        val vm = ActivateReservationsViewModel(storage, PlanManager(storage), mockk(relaxed = true))

        assertFalse(vm.hasReservationsFeature)

        vm.activate()
        advanceUntilIdle()

        val s = vm.state.value
        assertFalse(s.didSucceed)
        assertNotNull(s.error)
        verify(exactly = 0) { storage.reservationsEnabled = true }
        verify(exactly = 0) { storage.reservationsEnabled = true }
    }

    @Test
    fun `activate works on PRO plan`() = runTest(dispatcher) {
        val storage = storageWith(planTier = "PRO")
        val vm = ActivateReservationsViewModel(storage, PlanManager(storage), mockk(relaxed = true))

        assertTrue(vm.hasReservationsFeature)

        vm.activate()
        advanceUntilIdle()

        assertTrue(vm.state.value.didSucceed)
        verify { storage.reservationsEnabled = true }
    }

    @Test
    fun `activate works on exempt FREE venue (grandfathered)`() = runTest(dispatcher) {
        val storage = storageWith(planTier = "FREE", planExempt = true)
        val vm = ActivateReservationsViewModel(storage, PlanManager(storage), mockk(relaxed = true))

        assertTrue(vm.hasReservationsFeature)

        vm.activate()
        advanceUntilIdle()

        assertTrue(vm.state.value.didSucceed)
    }

    @Test
    fun `requiredTierLabel is Pro`() {
        val storage = storageWith(planTier = "FREE")
        val vm = ActivateReservationsViewModel(storage, PlanManager(storage), mockk(relaxed = true))
        assertEquals("Pro", vm.requiredTierLabel)
    }
}
