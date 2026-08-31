package com.avoqado.pos.customerdisplay

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayModeRequestStateTest {

    private val now = Instant.parse("2026-08-30T18:00:00Z")
    private val supported = DisplayCapabilitySnapshot(present = true, invertible = true)

    @Test
    fun `request vencida se ignora sin empezar a aplicarla`() {
        val decision = decideRemoteIntent(
            request = request(expiresAt = now),
            capability = supported,
            journal = null,
            currentLocalGeneration = 4,
            now = now,
        )

        assertEquals(RemoteDisplayIntentDecision.IGNORE_EXPIRED, decision)
    }

    @Test
    fun `request ya resuelta se ignora como ack previo`() {
        val decision = decideRemoteIntent(
            request = request(status = "APPLIED", expiresAt = now.minusSeconds(1)),
            capability = supported,
            journal = null,
            currentLocalGeneration = 4,
            now = now,
        )

        assertEquals(RemoteDisplayIntentDecision.IGNORE_ALREADY_ACKED, decision)
    }

    @Test
    fun `aplicacion journalizada se acusa aun si el request ya vencio`() {
        val harness = preferencesHarness()
        val store = DisplayModeRequestStore(harness.prefs)
        val entry = journal(
            requestId = "request-a",
            appliedLocally = true,
            ackPending = true,
            requestExpiresAt = now.minusSeconds(1),
            appliedAt = now.minusSeconds(2),
            ackPreparedAt = now.minusSeconds(1),
            ackOutcome = DisplayModeAckOutcome.APPLIED,
            confirmedInverted = true,
        )
        store.save(entry)
        val reloaded = store.load(entry.venueId, entry.deviceId)
        assertEquals(entry, reloaded)

        val decision = decideRemoteIntent(
            request = request(requestId = "request-b", expiresAt = now.minusSeconds(1)),
            capability = supported,
            journal = reloaded,
            currentLocalGeneration = 4,
            now = now,
        )

        assertEquals(RemoteDisplayIntentDecision.ACK_JOURNALED_APPLY, decision)
    }

    @Test
    fun `request pendiente compatible se aplica por valor y se acusa`() {
        val decision = decideRemoteIntent(
            request = request(),
            capability = supported,
            journal = null,
            currentLocalGeneration = 4,
            now = now,
        )

        assertEquals(RemoteDisplayIntentDecision.APPLY_AND_ACK, decision)
    }

    @Test
    fun `display ausente o no invertible rechaza por capacidad`() {
        listOf(
            DisplayCapabilitySnapshot(present = false, invertible = false),
            DisplayCapabilitySnapshot(present = true, invertible = false),
        ).forEach { capability ->
            val decision = decideRemoteIntent(
                request = request(),
                capability = capability,
                journal = null,
                currentLocalGeneration = 4,
                now = now,
            )

            assertEquals(RemoteDisplayIntentDecision.REJECT_UNSUPPORTED, decision)
        }
    }

    @Test
    fun `cambio local posterior al journal gana y rechaza override`() {
        val decision = decideRemoteIntent(
            request = request(),
            capability = supported,
            journal = journal(localGenerationAtJournal = 4),
            currentLocalGeneration = 5,
            now = now,
        )

        assertEquals(RemoteDisplayIntentDecision.REJECT_LOCAL_OVERRIDE, decision)
    }

    @Test
    fun `cambio local anterior capturado por el journal queda superado por request nueva`() {
        val decision = decideRemoteIntent(
            request = request(),
            capability = supported,
            journal = journal(localGenerationAtJournal = 5),
            currentLocalGeneration = 5,
            now = now,
        )

        assertEquals(RemoteDisplayIntentDecision.APPLY_AND_ACK, decision)
    }

    @Test
    fun `store conserva payload de ack terminal y scope sin mezclar dispositivos`() {
        val harness = preferencesHarness()
        val store = DisplayModeRequestStore(harness.prefs)
        val venueADeviceA = journal(
            venueId = "venue-a",
            deviceId = "device-a",
            terminalId = "terminal-from-get",
            ackOutcome = DisplayModeAckOutcome.REJECTED,
            ackResultCode = DisplayModeAckResultCode.LOCAL_OVERRIDE,
            confirmedInverted = false,
            applyStartedAt = now.plusSeconds(1),
            confirmedLocalGeneration = 5,
            appliedLocally = true,
            ackPending = true,
            appliedAt = now.plusSeconds(2),
            ackPreparedAt = now.plusSeconds(3),
        )
        val venueADeviceB = journal(
            venueId = "venue-a",
            deviceId = "device-b",
            terminalId = "terminal-b",
            requestId = "request-b",
        )

        store.save(venueADeviceA)
        store.save(venueADeviceB)

        assertEquals(venueADeviceA, store.load("venue-a", "device-a"))
        assertEquals(venueADeviceB, store.load("venue-a", "device-b"))
        assertNull(store.load("venue-b", "device-a"))
        assertTrue(store.hasInFlight("venue-a", "device-a"))
        assertFalse(store.hasInFlight("venue-b", "device-a"))
    }

    @Test
    fun `clear por requestId nunca borra una request mas nueva del mismo scope`() {
        val harness = preferencesHarness()
        val store = DisplayModeRequestStore(harness.prefs)
        val entryA = journal(requestId = "request-a")
        val entryB = journal(requestId = "request-b")

        store.save(entryA)
        store.save(entryB)
        store.clear(entryA.venueId, entryA.deviceId, entryA.requestId)

        assertEquals(entryB, store.load(entryB.venueId, entryB.deviceId))

        store.clear(entryB.venueId, entryB.deviceId, entryB.requestId)
        assertNull(store.load(entryB.venueId, entryB.deviceId))
    }

    @Test
    fun `JSON corrupto o incompleto falla cerrado sin crashear`() {
        val corrupt = preferencesHarness(initialValue = "{not-json")
        assertNull(DisplayModeRequestStore(corrupt.prefs).load("venue-a", "device-a"))

        val incomplete = preferencesHarness(initialValue = "{\"venueId\":\"venue-a\"}")
        assertNull(DisplayModeRequestStore(incomplete.prefs).load("venue-a", "device-a"))
    }

    @Test
    fun `JSON ilegible o con enum futuro conserva barrera in flight`() {
        val corrupt = preferencesHarness(initialValue = "{not-json")
        val corruptStore = DisplayModeRequestStore(corrupt.prefs)
        assertNull(corruptStore.load("venue-a", "device-a"))
        assertTrue(corruptStore.hasInFlight("venue-a", "device-a"))

        val future = preferencesHarness()
        val futureStore = DisplayModeRequestStore(future.prefs)
        val entry = journal(
            ackPending = true,
            ackOutcome = DisplayModeAckOutcome.REJECTED,
            ackResultCode = DisplayModeAckResultCode.LOCAL_OVERRIDE,
            confirmedInverted = false,
            ackPreparedAt = now.plusSeconds(2),
        )
        futureStore.save(entry)
        val key = DisplayModeRequestStore.storageKey(entry.venueId, entry.deviceId)
        future.replacePersistedRaw(
            key,
            requireNotNull(future.rawInMemory(key)).replace("LOCAL_OVERRIDE", "FUTURE_RESULT_CODE"),
        )

        assertNull(futureStore.load(entry.venueId, entry.deviceId))
        assertTrue(futureStore.hasInFlight(entry.venueId, entry.deviceId))
    }

    @Test
    fun `save fallido conserva A y expone el fallo`() {
        val harness = preferencesHarness(commitResults = listOf(true, false, false))
        val store = DisplayModeRequestStore(harness.prefs)
        val entryA = journal(requestId = "request-a")
        val entryB = journal(requestId = "request-b")
        store.save(entryA)

        val error = runCatching { store.save(entryB) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(entryA, store.load(entryA.venueId, entryA.deviceId))
        val restartedStore = DisplayModeRequestStore(harness.restartAfterProcessDeath())
        assertEquals(entryA, restartedStore.load(entryA.venueId, entryA.deviceId))
    }

    @Test
    fun `clear fallido conserva A y expone el fallo`() {
        val harness = preferencesHarness(commitResults = listOf(true, false, false))
        val store = DisplayModeRequestStore(harness.prefs)
        val entryA = journal(requestId = "request-a")
        store.save(entryA)

        val error = runCatching {
            store.clear(entryA.venueId, entryA.deviceId, entryA.requestId)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(entryA, store.load(entryA.venueId, entryA.deviceId))
        assertTrue(store.hasInFlight(entryA.venueId, entryA.deviceId))
        val restartedStore = DisplayModeRequestStore(harness.restartAfterProcessDeath())
        assertEquals(entryA, restartedStore.load(entryA.venueId, entryA.deviceId))
        assertTrue(restartedStore.hasInFlight(entryA.venueId, entryA.deviceId))
    }

    private fun request(
        requestId: String = "request-a",
        status: String = "PENDING",
        expiresAt: Instant = now.plusSeconds(900),
    ) = RemoteDisplayModeRequest(
        requestId = requestId,
        desiredInverted = true,
        status = status,
        expiresAt = expiresAt,
    )

    private fun journal(
        venueId: String = "venue-a",
        deviceId: String = "device-a",
        terminalId: String = "terminal-a",
        requestId: String = "request-a",
        desiredInverted: Boolean = true,
        appliedLocally: Boolean = false,
        ackPending: Boolean = false,
        localGenerationAtJournal: Long = 4,
        requestExpiresAt: Instant = now.plusSeconds(900),
        journaledAt: Instant = now.plusSeconds(1),
        applyStartedAt: Instant? = null,
        appliedAt: Instant? = null,
        ackPreparedAt: Instant? = null,
        ackOutcome: DisplayModeAckOutcome? = null,
        ackResultCode: DisplayModeAckResultCode? = null,
        confirmedInverted: Boolean? = null,
        confirmedLocalGeneration: Long? = null,
    ) = DisplayModeJournalEntry(
        venueId = venueId,
        deviceId = deviceId,
        terminalId = terminalId,
        requestId = requestId,
        desiredInverted = desiredInverted,
        appliedLocally = appliedLocally,
        ackPending = ackPending,
        localGenerationAtJournal = localGenerationAtJournal,
        requestExpiresAt = requestExpiresAt,
        journaledAt = journaledAt,
        applyStartedAt = applyStartedAt,
        appliedAt = appliedAt,
        ackPreparedAt = ackPreparedAt,
        ackOutcome = ackOutcome,
        ackResultCode = ackResultCode,
        confirmedInverted = confirmedInverted,
        confirmedLocalGeneration = confirmedLocalGeneration,
    )

    private fun preferencesHarness(
        initialValue: String? = null,
        commitResults: List<Boolean> = emptyList(),
    ): PreferencesHarness = PreferencesHarness(initialValue, commitResults)

    /**
     * Android publica un Editor en memoria ANTES de intentar escribir a disco.
     * `commit=false` puede dejar ambos mundos distintos hasta que muera el proceso.
     */
    private class PreferencesHarness(
        initialValue: String?,
        commitResults: List<Boolean>,
    ) {
        private val diskValues = mutableMapOf<String, String?>()
        private var memoryValues = mutableMapOf<String, String?>()
        private val remainingCommitResults = commitResults.toMutableList()

        var prefs: SharedPreferences
            private set

        init {
            if (initialValue != null) {
                diskValues[DisplayModeRequestStore.storageKey("venue-a", "device-a")] = initialValue
            }
            memoryValues.putAll(diskValues)
            prefs = createPreferences()
        }

        fun rawInMemory(key: String): String? = memoryValues[key]

        fun replacePersistedRaw(key: String, raw: String) {
            memoryValues[key] = raw
            diskValues[key] = raw
        }

        fun restartAfterProcessDeath(): SharedPreferences {
            memoryValues = diskValues.toMutableMap()
            prefs = createPreferences()
            return prefs
        }

        private fun createPreferences(): SharedPreferences {
            val preferences = mockk<SharedPreferences>()
            every { preferences.getString(any(), any()) } answers {
                val key = firstArg<String>()
                if (memoryValues.containsKey(key)) memoryValues[key] else secondArg()
            }
            every { preferences.edit() } answers {
                val editor = mockk<SharedPreferences.Editor>()
                val pendingPuts = mutableMapOf<String, String?>()
                val pendingRemovals = mutableSetOf<String>()
                every { editor.putString(any(), any()) } answers {
                    val key = firstArg<String>()
                    pendingPuts[key] = secondArg<String?>()
                    pendingRemovals.remove(key)
                    editor
                }
                every { editor.remove(any()) } answers {
                    val key = firstArg<String>()
                    pendingRemovals += key
                    pendingPuts.remove(key)
                    editor
                }
                every { editor.commit() } answers {
                    pendingRemovals.forEach(memoryValues::remove)
                    pendingPuts.forEach { (key, value) -> memoryValues[key] = value }
                    val succeeds = if (remainingCommitResults.isEmpty()) true else remainingCommitResults.removeAt(0)
                    if (succeeds) {
                        diskValues.clear()
                        diskValues.putAll(memoryValues)
                    }
                    succeeds
                }
                editor
            }
            return preferences
        }
    }
}
