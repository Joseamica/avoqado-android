package com.avoqado.pos.tpvsettings.data

import com.avoqado.pos.core.data.local.PreferencesDataStore
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.customerdisplay.CanonicalDeviceIdProvider
import com.avoqado.pos.customerdisplay.DisplayModeJournalState
import com.avoqado.pos.customerdisplay.DisplayModeAckRemote
import com.avoqado.pos.customerdisplay.DisplayModeAuthorityGate
import com.avoqado.pos.customerdisplay.DisplayModePhysicalApplier
import com.avoqado.pos.customerdisplay.DisplayModePreferenceSnapshot
import com.avoqado.pos.customerdisplay.DisplayModePrefs
import com.avoqado.pos.customerdisplay.DisplayModeProcessorClock
import com.avoqado.pos.customerdisplay.DisplayModeRemoteOutcome
import com.avoqado.pos.customerdisplay.DisplayModeRequestBinding
import com.avoqado.pos.customerdisplay.DisplayModeRequestJournal
import com.avoqado.pos.customerdisplay.DisplayModeRequestProcessor
import com.avoqado.pos.customerdisplay.DisplayModeAcknowledgement
import com.avoqado.pos.customerdisplay.DisplayCapabilitySnapshot
import com.avoqado.pos.customerdisplay.JournalEntry
import com.avoqado.pos.customerdisplay.PhysicalDisplayModeResult
import com.avoqado.pos.customerdisplay.RemoteDisplayModeApplyResult
import com.avoqado.pos.customerdisplay.RemoteDisplayModeRequest
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TpvSettingsDisplayModeAuthorityTest {
    @Test
    fun `Ready and Unreadable journal skip every legacy display effect`() = runTest {
        listOf(
            DisplayModeJournalState.Ready(inFlightEntry()),
            DisplayModeJournalState.Unreadable,
        ).forEach { state ->
            val client = RecordingClient(settingsBody(serverInverted = true))
            val displayPrefs = displayPrefs(local = false, dirtyValue = true)
            repository(client, displayPrefs, journal(state)).refreshSettingsForVenue(VENUE_ID)

            assertEquals(listOf("GET"), client.methods)
            verify(exactly = 0) { displayPrefs.adoptFromServer(any()) }
            verify(exactly = 0) { displayPrefs.markSynced(any(), any()) }
        }
    }

    @Test
    fun `without journal dirty local pushes through legacy patch`() = runTest {
        val client = RecordingClient(settingsBody(serverInverted = false))
        val displayPrefs = displayPrefs(local = true, dirtyValue = true, generationValue = 7L)

        repository(client, displayPrefs, journal(DisplayModeJournalState.Empty))
            .refreshSettingsForVenue(VENUE_ID)

        assertEquals(listOf("GET", "PATCH"), client.methods)
        verify(exactly = 1) { displayPrefs.markSynced(7L, true) }
    }

    @Test
    fun `without journal clean local adopts differing legacy confirmation`() = runTest {
        val client = RecordingClient(settingsBody(serverInverted = true))
        val displayPrefs = displayPrefs(local = false, dirtyValue = false)

        repository(client, displayPrefs, journal(DisplayModeJournalState.Empty))
            .refreshSettingsForVenue(VENUE_ID)

        assertEquals(listOf("GET"), client.methods)
        verify(exactly = 1) { displayPrefs.adoptFromServer(true) }
    }

    @Test
    fun `settings empty check and legacy effect finish before processor may journal A`() = runTest {
        val events = mutableListOf<String>()
        val journal = PausingEmptyJournal(events)
        val gate = DisplayModeAuthorityGate()
        val displayPrefs = displayPrefs(local = false, dirtyValue = false).also { prefs ->
            every { prefs.adoptFromServer(true) } answers {
                events += "legacy-complete"
            }
            every { prefs.applyRemoteIntent(true, 0L) } returns RemoteDisplayModeApplyResult.Applied(true)
            every { prefs.snapshot() } returns DisplayModePreferenceSnapshot(true, 0L)
            every { prefs.persistIfUnchanged(any(), any()) } answers {
                secondArg<() -> Unit>().invoke()
                true
            }
        }
        val repository = repository(
            RecordingClient(settingsBody(serverInverted = true)),
            displayPrefs,
            journal,
            gate,
        )
        val processor = DisplayModeRequestProcessor(
            ackRemote = object : DisplayModeAckRemote {
                override suspend fun acknowledge(
                    venueId: String,
                    terminalId: String,
                    requestId: String,
                    confirmedInverted: Boolean,
                    acknowledgement: DisplayModeAcknowledgement,
                ) = DisplayModeRemoteOutcome.Success(Unit)
            },
            journal = journal,
            prefs = displayPrefs,
            physicalApplier = object : DisplayModePhysicalApplier {
                override suspend fun applyAndConfirm(desiredInverted: Boolean) =
                    PhysicalDisplayModeResult.Confirmed(desiredInverted)

                override suspend fun observeConfirmedMode(): Boolean = true
            },
            capabilities = MutableStateFlow(DisplayCapabilitySnapshot(true, true)),
            deviceIdProvider = CanonicalDeviceIdProvider { DEVICE_ID },
            clock = DisplayModeProcessorClock { Instant.parse("2026-08-31T12:00:00Z") },
            authorityGate = gate,
        )

        val settingsJob = async(Dispatchers.Default) {
            repository.refreshSettingsForVenue(VENUE_ID)
        }
        assertTrue(journal.settingsObservedEmpty.await(5, TimeUnit.SECONDS))
        val processorJob = async(Dispatchers.Default) {
            processor.process(
                VENUE_ID,
                DisplayModeRemoteOutcome.Success(
                    DisplayModeRequestBinding(
                        terminalId = "terminal-a",
                        request = RemoteDisplayModeRequest(
                            requestId = "request-a",
                            desiredInverted = true,
                            expiresAt = Instant.parse("2026-09-01T00:00:00Z"),
                        ),
                    ),
                ),
            )
        }
        assertTrue(journal.processorObservedEmpty.await(5, TimeUnit.SECONDS))
        assertFalse(journal.requestJournaled.await(200, TimeUnit.MILLISECONDS))

        journal.allowSettingsToContinue.countDown()
        settingsJob.await()
        processorJob.await()

        assertEquals(listOf("legacy-complete", "journal-created"), events.take(2))
    }

    private fun repository(
        recordingClient: RecordingClient,
        displayPrefs: DisplayModePrefs,
        journal: DisplayModeRequestJournal,
        authorityGate: DisplayModeAuthorityGate = DisplayModeAuthorityGate(),
    ) = TpvSettingsRepository(
        secureStorage = mockk<SecureStorage>(relaxed = true) {
            every { accessToken } returns "token"
        },
        client = recordingClient.client,
        preferencesDataStore = mockk<PreferencesDataStore>(relaxed = true) {
            every { getString(any()) } returns flowOf(null)
            every { getBooleanOrNull(any()) } returns flowOf(null)
        },
        displayModePrefs = displayPrefs,
        displayModeJournal = journal,
        deviceIdProvider = CanonicalDeviceIdProvider { DEVICE_ID },
        displayModeAuthorityGate = authorityGate,
    )

    private fun displayPrefs(
        local: Boolean,
        dirtyValue: Boolean,
        generationValue: Long = 0L,
    ): DisplayModePrefs = mockk<DisplayModePrefs>(relaxed = true) {
        every { inverted } returns MutableStateFlow(local)
        every { dirty } returns MutableStateFlow(dirtyValue)
        every { generation } returns MutableStateFlow(generationValue)
    }

    private fun journal(state: DisplayModeJournalState): DisplayModeRequestJournal =
        object : DisplayModeRequestJournal {
            override fun readState(venueId: String, deviceId: String) = state
            override fun save(entry: JournalEntry) = Unit
            override fun clear(venueId: String, deviceId: String, requestId: String) = Unit
        }

    private fun inFlightEntry() = JournalEntry(
        venueId = VENUE_ID,
        deviceId = DEVICE_ID,
        terminalId = "terminal-a",
        requestId = "request-a",
        desiredInverted = true,
        appliedLocally = false,
        ackPending = false,
        localGenerationAtJournal = 0,
        requestExpiresAt = Instant.parse("2026-09-01T00:00:00Z"),
        journaledAt = Instant.parse("2026-08-31T00:00:00Z"),
    )

    private class PausingEmptyJournal(
        private val events: MutableList<String>,
    ) : DisplayModeRequestJournal {
        private val reads = AtomicInteger()

        @Volatile
        private var entry: JournalEntry? = null

        val settingsObservedEmpty = CountDownLatch(1)
        val processorObservedEmpty = CountDownLatch(1)
        val allowSettingsToContinue = CountDownLatch(1)
        val requestJournaled = CountDownLatch(1)

        override fun readState(venueId: String, deviceId: String): DisplayModeJournalState {
            when (reads.incrementAndGet()) {
                1 -> {
                    settingsObservedEmpty.countDown()
                    check(allowSettingsToContinue.await(5, TimeUnit.SECONDS))
                }

                2 -> processorObservedEmpty.countDown()
            }
            return entry?.let(DisplayModeJournalState::Ready) ?: DisplayModeJournalState.Empty
        }

        override fun save(entry: JournalEntry) {
            this.entry = entry
            if (entry.applyStartedAt == null && !entry.ackPending) {
                events += "journal-created"
                requestJournaled.countDown()
            }
        }

        override fun clear(venueId: String, deviceId: String, requestId: String) {
            if (entry?.requestId == requestId) entry = null
        }
    }

    private fun settingsBody(serverInverted: Boolean) =
        """
        {"success":true,"data":{"settings":{},"deviceTerminal":{"id":"terminal-a","customerDisplayInverted":$serverInverted}}}
        """.trimIndent()

    private class RecordingClient(private val getBody: String) {
        val methods = mutableListOf<String>()
        val client: OkHttpClient = mockk {
            every { newCall(any()) } answers {
                val request = firstArg<Request>()
                val call = mockk<Call>()
                every { call.execute() } answers {
                    methods += request.method
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            (if (request.method == "GET") getBody else "{}")
                                .toResponseBody("application/json".toMediaType()),
                        )
                        .build()
                }
                call
            }
        }
    }

    private companion object {
        const val VENUE_ID = "venue-a"
        const val DEVICE_ID = "device-from-outbox"
    }
}
