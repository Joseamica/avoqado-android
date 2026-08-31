package com.avoqado.pos.tpvsettings.data

import com.avoqado.pos.core.data.local.PreferencesDataStore
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.customerdisplay.DisplayModePrefs
import com.avoqado.pos.customerdisplay.DisplayModeAuthorityGate
import com.avoqado.pos.customerdisplay.CanonicalDeviceIdProvider
import com.avoqado.pos.customerdisplay.DisplayModeJournalState
import com.avoqado.pos.customerdisplay.DisplayModeRequestJournal
import com.avoqado.pos.customerdisplay.JournalEntry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

class TpvSettingsRepositoryOfflineCacheTest {

    @Test
    fun `cold offline start restores last known terminal workspace and venue settings`() = runTest {
        val stored = mutableMapOf<String, String>()
        val preferences = preferencesBackedBy(stored)
        val storage = authenticatedStorage()

        val onlineRepository = TpvSettingsRepository(
            secureStorage = storage,
            client = clientReturning(areaOperationsResponse()),
            preferencesDataStore = preferences,
            displayModePrefs = fakeDisplayModePrefs(),
            displayModeJournal = emptyJournal(),
            deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
            displayModeAuthorityGate = DisplayModeAuthorityGate(),
        )
        onlineRepository.refreshSettingsForVenue(VENUE_ID)

        assertEquals(TerminalNavigationSettings.AREA_OPERATIONS, onlineRepository.terminalNavigation.value.defaultWorkspace)
        assertEquals(false, onlineRepository.settings.value.showReviewScreen)

        val coldOfflineRepository = TpvSettingsRepository(
            secureStorage = storage,
            client = clientThrowing(IOException("offline")),
            preferencesDataStore = preferences,
            displayModePrefs = fakeDisplayModePrefs(),
            displayModeJournal = emptyJournal(),
            deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
            displayModeAuthorityGate = DisplayModeAuthorityGate(),
        )
        coldOfflineRepository.refreshSettingsForVenue(VENUE_ID)

        assertEquals(TerminalNavigationSettings.AREA_OPERATIONS, coldOfflineRepository.terminalNavigation.value.defaultWorkspace)
        assertEquals(true, coldOfflineRepository.terminalNavigation.value.canIssueAreaTickets)
        assertEquals("area-creamery", coldOfflineRepository.terminalNavigation.value.fulfillmentAreaId)
        assertEquals(false, coldOfflineRepository.settings.value.showReviewScreen)
    }

    @Test
    fun `terminal cache is scoped by venue and never leaks into another venue`() = runTest {
        val stored = mutableMapOf<String, String>()
        val preferences = preferencesBackedBy(stored)
        val storage = authenticatedStorage()

        TpvSettingsRepository(
            secureStorage = storage,
            client = clientReturning(areaOperationsResponse()),
            preferencesDataStore = preferences,
            displayModePrefs = fakeDisplayModePrefs(),
            displayModeJournal = emptyJournal(),
            deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
            displayModeAuthorityGate = DisplayModeAuthorityGate(),
        ).refreshSettingsForVenue(VENUE_ID)

        val otherVenueRepository = TpvSettingsRepository(
            secureStorage = storage,
            client = clientThrowing(IOException("offline")),
            preferencesDataStore = preferences,
            displayModePrefs = fakeDisplayModePrefs(),
            displayModeJournal = emptyJournal(),
            deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
            displayModeAuthorityGate = DisplayModeAuthorityGate(),
        )
        otherVenueRepository.refreshSettingsForVenue("venue-b")

        assertEquals(TerminalNavigationSettings.DEFAULT, otherVenueRepository.terminalNavigation.value)
        assertEquals(TpvSettings.DEFAULT, otherVenueRepository.settings.value)
    }

    @Test
    fun `authoritative server denial does not preserve cached terminal capabilities`() = runTest {
        val stored = mutableMapOf<String, String>()
        val preferences = preferencesBackedBy(stored)
        val storage = authenticatedStorage()

        TpvSettingsRepository(
            secureStorage = storage,
            client = clientReturning(areaOperationsResponse()),
            preferencesDataStore = preferences,
            displayModePrefs = fakeDisplayModePrefs(),
            displayModeJournal = emptyJournal(),
            deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
            displayModeAuthorityGate = DisplayModeAuthorityGate(),
        ).refreshSettingsForVenue(VENUE_ID)

        val deniedRepository = TpvSettingsRepository(
            secureStorage = storage,
            client = clientReturning(body = "{\"error\":\"Forbidden\"}", statusCode = 403),
            preferencesDataStore = preferences,
            displayModePrefs = fakeDisplayModePrefs(),
            displayModeJournal = emptyJournal(),
            deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
            displayModeAuthorityGate = DisplayModeAuthorityGate(),
        )
        deniedRepository.refreshSettingsForVenue(VENUE_ID)

        assertEquals(TerminalNavigationSettings.DEFAULT, deniedRepository.terminalNavigation.value)

        val coldAfterDenial = TpvSettingsRepository(
            secureStorage = storage,
            client = clientThrowing(IOException("offline")),
            preferencesDataStore = preferences,
            displayModePrefs = fakeDisplayModePrefs(),
            displayModeJournal = emptyJournal(),
            deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
            displayModeAuthorityGate = DisplayModeAuthorityGate(),
        )
        coldAfterDenial.refreshSettingsForVenue(VENUE_ID)
        assertEquals(TerminalNavigationSettings.DEFAULT, coldAfterDenial.terminalNavigation.value)
    }

    private fun preferencesBackedBy(stored: MutableMap<String, String>): PreferencesDataStore =
        mockk<PreferencesDataStore>(relaxed = true).also { preferences ->
            every { preferences.getString(any()) } answers { flowOf(stored[firstArg()]) }
            every { preferences.getBooleanOrNull(any()) } returns flowOf(null)
            coEvery { preferences.setString(any(), any()) } answers {
                stored[firstArg()] = secondArg()
            }
            coEvery { preferences.removeString(any()) } answers {
                stored.remove(firstArg())
            }
        }

    private fun authenticatedStorage(): SecureStorage = mockk<SecureStorage>(relaxed = true) {
        every { accessToken } returns "access-token"
    }

    /**
     * Sin cambios pendientes: estos tests cubren el cache de settings/terminal,
     * no la sincronización del modo de pantallas — que ese `dirty` esté en
     * `false` evita que la reconciliación intente empujar nada aquí.
     */
    private fun fakeDisplayModePrefs(): DisplayModePrefs = mockk<DisplayModePrefs>(relaxed = true) {
        every { inverted } returns MutableStateFlow(false)
        every { dirty } returns MutableStateFlow(false)
        every { generation } returns MutableStateFlow(0L)
    }

    private fun emptyJournal(): DisplayModeRequestJournal = object : DisplayModeRequestJournal {
        override fun readState(venueId: String, deviceId: String) = DisplayModeJournalState.Empty
        override fun save(entry: JournalEntry) = Unit
        override fun clear(venueId: String, deviceId: String, requestId: String) = Unit
    }

    private fun clientReturning(body: String, statusCode: Int = 200): OkHttpClient {
        val call = mockk<Call>()
        every { call.execute() } returns Response.Builder()
            .request(Request.Builder().url("https://example.test/settings").build())
            .protocol(Protocol.HTTP_1_1)
            .code(statusCode)
            .message(if (statusCode in 200..299) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
        return mockk {
            every { newCall(any()) } returns call
        }
    }

    private fun clientThrowing(error: IOException): OkHttpClient {
        val call = mockk<Call>()
        every { call.execute() } throws error
        return mockk {
            every { newCall(any()) } returns call
        }
    }

    private fun areaOperationsResponse(): String =
        """
        {
          "success": true,
          "data": {
            "settings": { "showReviewScreen": false },
            "deviceTerminal": {
              "id": "terminal-creamery",
              "defaultWorkspace": "AREA_OPERATIONS",
              "canIssueAreaTickets": true,
              "canCheckoutAreaTickets": false,
              "canDeliverAreaTickets": true,
              "fulfillmentAreaId": "area-creamery"
            }
          }
        }
        """.trimIndent()

    private companion object {
        const val VENUE_ID = "venue-a"
    }
}
