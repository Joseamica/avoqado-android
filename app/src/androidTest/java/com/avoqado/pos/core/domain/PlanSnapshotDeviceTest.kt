package com.avoqado.pos.core.domain

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.avoqado.pos.core.data.local.SecureStorage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run each method in a separate instrumentation process on the isolated .hybridqa APK.
 * seedPaidAccess → disable networking → recoverOfflineAfterProcessDeath → restore networking → applyServerChange.
 * No fixture may touch an installed production/dev session.
 */
@RunWith(AndroidJUnit4::class)
class PlanSnapshotDeviceTest {
    private fun storage(): SecureStorage {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.avoqado.pos.hybridqa") { "Requires isolated QA application ID" }
        return SecureStorage(context)
    }

    @Test fun seedPaidAccess() {
        val store = storage()
        store.venueId = "FULLTEST-hybrid-device"
        store.planTier = "PREMIUM"
        store.storePlanSnapshot("FULLTEST-hybrid-device", PlanSnapshot(
            tier = "FREE", accessSchemaVersion = 1,
            accessObservedAt = "2026-09-27T00:00:00Z", grantedFeatureCodes = listOf("CFDI"),
        ))
        assertTrue(PlanManager(store).hasFeature("CFDI"))
        assertFalse(PlanManager(store).hasFeature("INVENTORY_TRACKING"))
    }

    @Test fun recoverOfflineAfterProcessDeath() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        assertNull("Disable device networking for this check", network.activeNetwork)
        val store = storage()
        assertEquals("FULLTEST-hybrid-device", store.venueId)
        assertTrue(PlanManager(store).hasFeature("CFDI"))
        assertFalse(PlanManager(store).hasFeature("INVENTORY_TRACKING"))
        store.storePlanSnapshot("FULLTEST-hybrid-device", null)
        assertTrue(PlanManager(store).hasFeature("CFDI"))
    }

    @Test fun applyServerChange() {
        val store = storage()
        store.storePlanSnapshot("FULLTEST-hybrid-device", PlanSnapshot(
            tier = "FREE", accessSchemaVersion = 1,
            accessObservedAt = "2026-09-27T00:01:00Z", grantedFeatureCodes = listOf("INVENTORY_TRACKING"),
        ))
        assertFalse(PlanManager(store).hasFeature("CFDI"))
        assertTrue(PlanManager(store).hasFeature("INVENTORY_TRACKING"))
        val reopened = storage()
        assertFalse(PlanManager(reopened).hasFeature("CFDI"))
        assertTrue(PlanManager(reopened).hasFeature("INVENTORY_TRACKING"))
    }
}
