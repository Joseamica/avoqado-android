package com.avoqado.pos.navigation

import app.cash.turbine.test
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.reservations.domain.VenueMode
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MainTabHostViewModelTest {

    private fun storageWith(
        reservationsEnabled: Boolean,
        venueMode: String?,
        planTier: String? = null,
        planExempt: Boolean = false,
        relaxed: Boolean = false,
    ): SecureStorage {
        val storage: SecureStorage = if (relaxed) mockk(relaxed = true) else mockk()
        every { storage.reservationsEnabled } returns reservationsEnabled
        every { storage.venueMode } returns venueMode
        every { storage.planTier } returns planTier
        every { storage.planExempt } returns planExempt
        return storage
    }

    @Test
    fun `tabs without reservations enabled = standard set`() = runTest {
        val storage = storageWith(reservationsEnabled = false, venueMode = null)

        val vm = MainTabHostViewModel(storage, PlanManager(storage))

        vm.tabs.test {
            val tabs = awaitItem()
            assertEquals(false, tabs.contains(MainTab.CALENDAR))
            assertEquals(true, tabs.contains(MainTab.CHECKOUT))
            assertEquals(true, tabs.contains(MainTab.MORE))
        }
    }

    @Test
    fun `tabs with reservations enabled and reservations mode = calendar first, inventory kept`() = runTest {
        // v2.3.2 changed computeTabs: CALENDAR is prepended and INVENTORY stays (it used
        // to be replaced). The old expectation never ran since (the suite didn't compile).
        val storage = storageWith(
            reservationsEnabled = true,
            venueMode = VenueMode.RESERVATIONS.storageValue,
        )

        val vm = MainTabHostViewModel(storage, PlanManager(storage))

        vm.tabs.test {
            val tabs = awaitItem()
            assertEquals(MainTab.CALENDAR, tabs.first())
            assertEquals(true, tabs.contains(MainTab.INVENTORY))
            assertEquals(true, tabs.contains(MainTab.MORE))
        }
    }

    @Test
    fun `tabs with reservations enabled but standard mode = standard set unchanged`() = runTest {
        val storage = storageWith(
            reservationsEnabled = true,
            venueMode = VenueMode.STANDARD.storageValue,
        )

        val vm = MainTabHostViewModel(storage, PlanManager(storage))

        vm.tabs.test {
            val tabs = awaitItem()
            assertEquals(false, tabs.contains(MainTab.CALENDAR))
            assertEquals(true, tabs.contains(MainTab.INVENTORY))
        }
    }

    @Test
    fun `setMode emits new tab list immediately`() = runTest {
        val storage = storageWith(
            reservationsEnabled = true,
            venueMode = VenueMode.STANDARD.storageValue,
            relaxed = true,
        )

        val vm = MainTabHostViewModel(storage, PlanManager(storage))

        vm.tabs.test {
            awaitItem() // initial standard
            vm.setMode(VenueMode.RESERVATIONS)
            val updated = awaitItem()
            assertEquals(MainTab.CALENDAR, updated.first())
        }
    }

    // MARK: - Plan gating (RESERVATIONS, Pro)

    @Test
    fun `stale local toggle cannot expose calendar tab on FREE plan`() = runTest {
        // The local reservationsEnabled toggle is true (user flipped it before
        // tiers existed) but the venue's plan is FREE → no Calendar tab.
        val storage = storageWith(
            reservationsEnabled = true,
            venueMode = VenueMode.RESERVATIONS.storageValue,
            planTier = "FREE",
        )

        val vm = MainTabHostViewModel(storage, PlanManager(storage))

        vm.tabs.test {
            val tabs = awaitItem()
            assertEquals(false, tabs.contains(MainTab.CALENDAR))
            assertEquals(true, tabs.contains(MainTab.INVENTORY))
        }
    }

    @Test
    fun `PRO plan keeps calendar tab when reservations enabled`() = runTest {
        val storage = storageWith(
            reservationsEnabled = true,
            venueMode = VenueMode.RESERVATIONS.storageValue,
            planTier = "PRO",
        )

        val vm = MainTabHostViewModel(storage, PlanManager(storage))

        vm.tabs.test {
            val tabs = awaitItem()
            assertEquals(MainTab.CALENDAR, tabs.first())
        }
    }

    @Test
    fun `exempt FREE venue keeps calendar tab (grandfathered)`() = runTest {
        val storage = storageWith(
            reservationsEnabled = true,
            venueMode = VenueMode.RESERVATIONS.storageValue,
            planTier = "FREE",
            planExempt = true,
        )

        val vm = MainTabHostViewModel(storage, PlanManager(storage))

        vm.tabs.test {
            val tabs = awaitItem()
            assertEquals(MainTab.CALENDAR, tabs.first())
        }
    }

    @Test
    fun `absent plan fails open - calendar tab stays (old server)`() = runTest {
        val storage = storageWith(
            reservationsEnabled = true,
            venueMode = VenueMode.RESERVATIONS.storageValue,
            planTier = null,
        )

        val vm = MainTabHostViewModel(storage, PlanManager(storage))

        vm.tabs.test {
            val tabs = awaitItem()
            assertEquals(MainTab.CALENDAR, tabs.first())
        }
    }
}
