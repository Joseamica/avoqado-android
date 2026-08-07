package com.avoqado.pos.areatickets.data

import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class AreaTicketRepositoryOfflineSettingsTest {

    @Test
    fun `cold offline start restores last known area ticket settings`() = runTest {
        var cachedJson: String? = null
        val cache = mockk<PayloadCache>()
        coEvery {
            cache.save(PayloadCache.TYPE_AREA_TICKET_SETTINGS, VENUE_ID, any())
        } answers {
            cachedJson = thirdArg()
        }
        coEvery {
            cache.load(PayloadCache.TYPE_AREA_TICKET_SETTINGS, VENUE_ID)
        } answers {
            cachedJson?.let { PayloadCache.Cached(it, System.currentTimeMillis()) }
        }

        val storage = storage()
        val onlineApi = mockk<ApiService>()
        coEvery { onlineApi.getAreaTicketSettings(VENUE_ID) } returns
            AreaTicketEnvelope(success = true, data = creamerySettings())

        val onlineRepository = repository(onlineApi, storage, cache)
        assertEquals(true, onlineRepository.settings().terminal.canIssueAreaTickets)

        val offlineApi = mockk<ApiService>()
        coEvery { offlineApi.getAreaTicketSettings(VENUE_ID) } throws IOException("offline")
        val coldOfflineRepository = repository(offlineApi, storage, cache)

        val restored = coldOfflineRepository.settings()
        assertEquals(true, restored.areaTickets.enabled)
        assertEquals("AREA_OPERATIONS", restored.terminal.defaultWorkspace)
        assertEquals(true, restored.terminal.canIssueAreaTickets)
        assertEquals("area-creamery", restored.terminal.fulfillmentArea?.id)
    }

    private fun repository(
        api: ApiService,
        storage: SecureStorage,
        cache: PayloadCache,
    ) = AreaTicketRepository(
        api = api,
        secureStorage = storage,
        session = AreaTicketSession(storage),
        payloadCache = cache,
    )

    private fun storage(): SecureStorage = mockk(relaxed = true) {
        every { venueId } returns VENUE_ID
    }

    private fun creamerySettings() = AreaTicketSettingsData(
        venueId = VENUE_ID,
        areaTickets = AreaTicketModuleSettings(
            entitled = true,
            enabled = true,
            deliveryVerificationMode = "PAPER_OR_SCAN",
        ),
        terminal = AreaTicketTerminalCapabilities(
            id = "terminal-creamery",
            name = "Samsung",
            fulfillmentArea = AreaTicketArea(
                id = "area-creamery",
                name = "QA Cremería",
                fulfillmentMode = "HOLD_UNTIL_PAID",
            ),
            canIssueAreaTickets = true,
            canDeliverAreaTickets = true,
            defaultWorkspace = "AREA_OPERATIONS",
        ),
        scaleIntegration = ScaleIntegrationSettings(
            entitled = true,
            enabled = true,
            manualFallbackAllowed = true,
        ),
    )

    private companion object {
        const val VENUE_ID = "venue-a"
    }
}
