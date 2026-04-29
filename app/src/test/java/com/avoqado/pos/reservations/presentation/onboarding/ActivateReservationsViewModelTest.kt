package com.avoqado.pos.reservations.presentation.onboarding

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.domain.VenueMode
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivateReservationsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `activate persists reservationsEnabled and switches to RESERVATIONS mode`() = runTest(dispatcher) {
        val storage: SecureStorage = mockk(relaxed = true)
        val vm = ActivateReservationsViewModel(storage)

        vm.activate()
        advanceUntilIdle()

        val s = vm.state.value
        assertTrue(s.didSucceed)
        assertEquals(false, s.isActivating)
        verify { storage.reservationsEnabled = true }
        verify { storage.venueMode = VenueMode.RESERVATIONS.storageValue }
    }

    @Test
    fun `activate ignored after success`() = runTest(dispatcher) {
        val storage: SecureStorage = mockk(relaxed = true)
        val vm = ActivateReservationsViewModel(storage)

        vm.activate(); advanceUntilIdle()
        vm.activate(); advanceUntilIdle()

        // setter only called once for each prop
        verify(exactly = 1) { storage.reservationsEnabled = true }
    }
}
