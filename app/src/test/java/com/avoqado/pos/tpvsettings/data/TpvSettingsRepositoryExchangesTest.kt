package com.avoqado.pos.tpvsettings.data

import com.avoqado.pos.core.data.local.PreferencesDataStore
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.customerdisplay.CanonicalDeviceIdProvider
import com.avoqado.pos.customerdisplay.DisplayModeAuthorityGate
import com.avoqado.pos.customerdisplay.DisplayModeJournalState
import com.avoqado.pos.customerdisplay.DisplayModePrefs
import com.avoqado.pos.customerdisplay.DisplayModeRequestJournal
import com.avoqado.pos.customerdisplay.JournalEntry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class TpvSettingsRepositoryExchangesTest {
    @Test fun `exchangesEnabled defaults to false and keeps the last value when its component failed`() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository()
        assertFalse(repo.exchangesEnabled.value)
        fixture.body = """{"data":{"exchangesEnabled":true}}"""
        repo.refreshSettingsForVenue("venue-a")
        assertTrue(repo.exchangesEnabled.value)
        assertTrue(fixture.stored)
        fixture.body = """{"data":{"exchangesEnabled":false,"componentsStatus":{"promotionsPanel":"failed"}}}"""
        repo.refreshSettingsForVenue("venue-a")
        assertTrue(repo.exchangesEnabled.value)
        assertTrue(fixture.stored)
        fixture.body = """{"data":{"exchangesEnabled":false,"componentsStatus":{"promotionsPanel":"ok"}}}"""
        repo.refreshSettingsForVenue("venue-a")
        assertFalse(repo.exchangesEnabled.value)
        assertFalse(fixture.stored)
    }

    @Test fun `missing or null settings disable exchanges on a successful component read`() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository()
        for (missing in listOf("{}", """{"exchangesEnabled":null}""")) {
            fixture.body = """{"data":{"exchangesEnabled":true}}"""
            repo.refreshSettingsForVenue("venue-a")
            assertTrue(repo.exchangesEnabled.value)
            fixture.body = """{"data":$missing}"""
            repo.refreshSettingsForVenue("venue-a")
            assertFalse(repo.exchangesEnabled.value)
            assertFalse(fixture.stored)
        }
        val json = Json { ignoreUnknownKeys = true }
        assertNull(json.decodeFromString<VenueSettingsData>("{}").exchangesEnabled)
        assertNull(json.decodeFromString<VenueSettingsData>("""{"exchangesEnabled":null}""").exchangesEnabled)
    }

    @Test fun `cold offline start restores the stored switch and network failure preserves it`() = runTest {
        val fixture = Fixture()
        fixture.stored = true
        val repo = fixture.repository()
        fixture.failure = IOException("offline")
        repo.refreshSettingsForVenue("venue-a")
        assertTrue(repo.exchangesEnabled.value)
        assertTrue(fixture.stored)
    }

    @Test fun `clearCache clears both memory and storage and invalidates an in-flight response`() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository()
        fixture.body = """{"data":{"exchangesEnabled":true}}"""
        repo.refreshSettingsForVenue("venue-a")
        assertTrue(repo.exchangesEnabled.value)
        fixture.beforeResponse = { repo.clearCache() }
        repo.refreshSettingsForVenue("venue-a")
        assertFalse(repo.exchangesEnabled.value)
        assertFalse(fixture.stored)
        assertFalse(fixture.repository().exchangesEnabled.value)
    }

    @Test fun `a response from a previous venue cannot enable exchanges`() = runTest {
        val fixture = Fixture()
        val repo = fixture.repository()
        fixture.body = """{"data":{"exchangesEnabled":true}}"""
        fixture.beforeResponse = { fixture.venue = "venue-b" }
        repo.refreshSettingsForVenue("venue-a")
        assertFalse(repo.exchangesEnabled.value)
        assertFalse(fixture.stored)
    }

    private class Fixture {
        var venue = "venue-a"
        var stored = false
        var body = """{"data":{}}"""
        var failure: IOException? = null
        var beforeResponse: () -> Unit = {}
        private val storage = mockk<SecureStorage>(relaxed = true).also { s ->
            every { s.accessToken } returns "test-token"
            every { s.venueId } answers { venue }
            every { s.exchangesEnabled } answers { stored }
            every { s.exchangesEnabled = any() } answers { stored = firstArg() }
        }

        fun repository(): TpvSettingsRepository {
            val preferences = mockk<PreferencesDataStore>(relaxed = true) {
                every { getString(any()) } returns flowOf(null)
                every { getBooleanOrNull(any()) } returns flowOf(null)
            }
            val client = mockk<OkHttpClient> {
                every { newCall(any()) } answers {
                    val request = firstArg<Request>()
                    mockk<Call> {
                        every { execute() } answers {
                            beforeResponse()
                            failure?.let { throw it }
                            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                                .body(body.toResponseBody("application/json".toMediaType())).build()
                        }
                    }
                }
            }
            return TpvSettingsRepository(
                secureStorage = storage,
                client = client,
                preferencesDataStore = preferences,
                displayModePrefs = mockk<DisplayModePrefs>(relaxed = true) {
                    every { inverted } returns MutableStateFlow(false)
                    every { dirty } returns MutableStateFlow(false)
                    every { generation } returns MutableStateFlow(0L)
                },
                displayModeJournal = object : DisplayModeRequestJournal {
                    override fun readState(venueId: String, deviceId: String) = DisplayModeJournalState.Empty
                    override fun save(entry: JournalEntry) = Unit
                    override fun clear(venueId: String, deviceId: String, requestId: String) = Unit
                },
                deviceIdProvider = CanonicalDeviceIdProvider { "device-test" },
                displayModeAuthorityGate = DisplayModeAuthorityGate(),
            )
        }
    }
}
