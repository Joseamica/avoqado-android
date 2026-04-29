package com.avoqado.pos.reservations.presentation.onboarding

import app.cash.turbine.test
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.data.ReservationApi
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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

    @Before fun setup() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `successful activate persists flag and emits success`() = runTest {
        val api: ReservationApi = mockk()
        val storage: SecureStorage = mockk(relaxed = true)
        coEvery { api.enableForVenue() } returns Result.success(Unit)

        val vm = ActivateReservationsViewModel(api, storage)
        vm.activate()

        vm.state.test {
            val s = awaitItem()
            assertTrue(s.didSucceed)
            assertEquals(false, s.isActivating)
            cancelAndIgnoreRemainingEvents()
        }
        verify { storage.reservationsEnabled = true }
    }

    @Test
    fun `failure surfaces error message`() = runTest {
        val api: ReservationApi = mockk()
        val storage: SecureStorage = mockk(relaxed = true)
        coEvery { api.enableForVenue() } returns Result.failure(RuntimeException("HTTP 500: Server error"))

        val vm = ActivateReservationsViewModel(api, storage)
        vm.activate()

        vm.state.test {
            val s = awaitItem()
            assertEquals(false, s.didSucceed)
            assertTrue(s.error!!.contains("Server error") || s.error!!.contains("HTTP 500"))
            cancelAndIgnoreRemainingEvents()
        }
    }
}
