package com.avoqado.pos.navigation

import app.cash.turbine.test
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.domain.VenueMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MainTabHostViewModelTest {

    @Test
    fun `tabs without reservations enabled = standard set`() = runTest {
        val storage: SecureStorage = mockk()
        every { storage.reservationsEnabled } returns false
        every { storage.venueMode } returns null

        val vm = MainTabHostViewModel(storage)

        vm.tabs.test {
            val tabs = awaitItem()
            assertEquals(false, tabs.contains(MainTab.CALENDAR))
            assertEquals(true, tabs.contains(MainTab.CHECKOUT))
            assertEquals(true, tabs.contains(MainTab.MORE))
        }
    }

    @Test
    fun `tabs with reservations enabled and reservations mode = calendar replaces inventory`() = runTest {
        val storage: SecureStorage = mockk()
        every { storage.reservationsEnabled } returns true
        every { storage.venueMode } returns VenueMode.RESERVATIONS.storageValue

        val vm = MainTabHostViewModel(storage)

        vm.tabs.test {
            val tabs = awaitItem()
            assertEquals(MainTab.CALENDAR, tabs.first())
            assertEquals(false, tabs.contains(MainTab.INVENTORY))
            assertEquals(true, tabs.contains(MainTab.MORE))
        }
    }

    @Test
    fun `tabs with reservations enabled but standard mode = standard set unchanged`() = runTest {
        val storage: SecureStorage = mockk()
        every { storage.reservationsEnabled } returns true
        every { storage.venueMode } returns VenueMode.STANDARD.storageValue

        val vm = MainTabHostViewModel(storage)

        vm.tabs.test {
            val tabs = awaitItem()
            assertEquals(false, tabs.contains(MainTab.CALENDAR))
            assertEquals(true, tabs.contains(MainTab.INVENTORY))
        }
    }

    @Test
    fun `setMode emits new tab list immediately`() = runTest {
        val storage: SecureStorage = mockk(relaxed = true)
        every { storage.reservationsEnabled } returns true
        every { storage.venueMode } returns VenueMode.STANDARD.storageValue

        val vm = MainTabHostViewModel(storage)

        vm.tabs.test {
            awaitItem() // initial standard
            vm.setMode(VenueMode.RESERVATIONS)
            val updated = awaitItem()
            assertEquals(MainTab.CALENDAR, updated.first())
        }
    }
}
