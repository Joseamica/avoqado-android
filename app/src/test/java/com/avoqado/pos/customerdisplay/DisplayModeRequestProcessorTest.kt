package com.avoqado.pos.customerdisplay

import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayModeRequestProcessorTest {
    private val now = Instant.parse("2026-08-31T12:00:00Z")

    @Test
    fun `true to false never mounts presentation over cashier and waits for old activity stop`() {
        assertFalse(canMountCustomerWindow(customerDisplayId = 7, cashierDisplayId = 7))
        assertEquals(
            true,
            observePhysicalDisplayMode(
                defaultDisplayId = 0,
                normalCustomerDisplayId = 7,
                cashierDisplayId = 7,
                presentationDisplayId = null,
                presentationShowing = false,
                defaultCustomerActivityStarted = true,
            ),
        )
        assertNull(
            observePhysicalDisplayMode(
                defaultDisplayId = 0,
                normalCustomerDisplayId = 7,
                cashierDisplayId = 0,
                presentationDisplayId = 7,
                presentationShowing = true,
                defaultCustomerActivityStarted = true,
            ),
        )
        assertEquals(
            false,
            observePhysicalDisplayMode(
                defaultDisplayId = 0,
                normalCustomerDisplayId = 7,
                cashierDisplayId = 0,
                presentationDisplayId = 7,
                presentationShowing = true,
                defaultCustomerActivityStarted = false,
            ),
        )
    }

    @Test
    fun `false to true never mounts activity over cashier and waits for old presentation removal`() {
        assertFalse(canMountCustomerWindow(customerDisplayId = 0, cashierDisplayId = 0))
        assertEquals(
            false,
            observePhysicalDisplayMode(
                defaultDisplayId = 0,
                normalCustomerDisplayId = 7,
                cashierDisplayId = 0,
                presentationDisplayId = 7,
                presentationShowing = true,
                defaultCustomerActivityStarted = false,
            ),
        )
        assertNull(
            observePhysicalDisplayMode(
                defaultDisplayId = 0,
                normalCustomerDisplayId = 7,
                cashierDisplayId = 7,
                presentationDisplayId = 7,
                presentationShowing = true,
                defaultCustomerActivityStarted = true,
            ),
        )
        assertEquals(
            true,
            observePhysicalDisplayMode(
                defaultDisplayId = 0,
                normalCustomerDisplayId = 7,
                cashierDisplayId = 7,
                presentationDisplayId = null,
                presentationShowing = false,
                defaultCustomerActivityStarted = true,
            ),
        )
    }

    @Test
    fun `failed relocation leaves no customer window over cashier and cannot confirm desired mode`() {
        assertFalse(canMountCustomerWindow(customerDisplayId = 7, cashierDisplayId = 7))
        assertNull(
            observePhysicalDisplayMode(
                defaultDisplayId = 0,
                normalCustomerDisplayId = 7,
                cashierDisplayId = 7,
                presentationDisplayId = null,
                presentationShowing = false,
                defaultCustomerActivityStarted = false,
            ),
        )
        assertEquals(
            true,
            observeCashierPhysicalMode(
                defaultDisplayId = 0,
                normalCustomerDisplayId = 7,
                cashierDisplayId = 7,
            ),
        )
    }

    @Test
    fun `journals before exact physical apply and persists ack before HTTP`() = runTest {
        val fixture = fixture()

        fixture.processor.process("venue-a", requestOutcome("request-a", desired = true))

        assertEquals(
            listOf("save:new:request-a", "save:started:request-a", "physical:true", "save:ack:request-a", "http:request-a"),
            fixture.events,
        )
        assertEquals(listOf(true), fixture.physical.requestedValues)
        assertNull(fixture.journal.entry)
    }

    @Test
    fun `pending physical relocation emits no optimistic ack and repeated request does not relocate twice`() = runTest {
        val fixture = fixture(
            physicalResults = ArrayDeque(
                listOf(
                    PhysicalDisplayModeResult.Pending,
                    PhysicalDisplayModeResult.Confirmed(true),
                ),
            ),
        )

        fixture.processor.process("venue-a", requestOutcome("request-a", desired = true))
        assertEquals(0, fixture.ack.calls.size)
        assertNotNull(fixture.journal.entry?.applyStartedAt)
        assertFalse(fixture.journal.entry?.ackPending == true)

        fixture.processor.process("venue-a", requestOutcome("request-a", desired = true))

        assertEquals(1, fixture.physical.relocationCount)
        assertEquals(1, fixture.ack.calls.size)
    }

    @Test
    fun `A pending ack drains before fetched B is applied`() = runTest {
        val fixture = fixture(initialJournal = ackEntry("request-a", confirmed = true))

        fixture.processor.process("venue-a", requestOutcome("request-b", desired = false))

        assertEquals(listOf("http:request-a"), fixture.events)
        assertEquals(emptyList<Boolean>(), fixture.physical.requestedValues)

        fixture.processor.process("venue-a", requestOutcome("request-b", desired = false))
        assertEquals(listOf(false), fixture.physical.requestedValues)
    }

    @Test
    fun `delivered ack A cannot clear concurrently persisted B`() = runTest {
        val entryB = newEntry("request-b", desired = false)
        val fixture = fixture(initialJournal = ackEntry("request-a", confirmed = true))
        fixture.ack.onCall = { fixture.journal.save(entryB) }

        fixture.processor.process("venue-a", noRequestOutcome())

        assertEquals(entryB, fixture.journal.entry)
    }

    @Test
    fun `expired never started journal is cleared without preference or physical effect`() = runTest {
        val expired = newEntry("request-a", desired = true).copy(requestExpiresAt = now)
        val fixture = fixture(initialJournal = expired)

        fixture.processor.process("venue-a", noRequestOutcome())

        assertNull(fixture.journal.entry)
        assertEquals(0, fixture.prefs.remoteApplyCalls)
        assertEquals(0, fixture.physical.relocationCount)
        assertEquals(0, fixture.ack.calls.size)
    }

    @Test
    fun `unexpired never started journal is cleared when server says no request`() = runTest {
        val fixture = fixture(initialJournal = newEntry("request-a", desired = true))

        fixture.processor.process("venue-a", noRequestOutcome())

        assertNull(fixture.journal.entry)
        assertEquals(0, fixture.prefs.remoteApplyCalls)
        assertEquals(0, fixture.physical.relocationCount)
        assertEquals(0, fixture.ack.calls.size)
    }

    @Test
    fun `current B supersedes never started A and B is applied in the same delivery`() = runTest {
        val fixture = fixture(initialJournal = newEntry("request-a", desired = true))

        fixture.processor.process("venue-a", requestOutcome("request-b", desired = false))

        assertEquals(listOf(false), fixture.physical.requestedValues)
        assertEquals(listOf("request-b"), fixture.ack.calls.map(AckCall::requestId))
        assertNull(fixture.journal.entry)
    }

    @Test
    fun `unsupported current hardware persists rejected ack with physical value`() = runTest {
        val fixture = fixture(capability = DisplayCapabilitySnapshot(present = true, invertible = false))
        fixture.physical.current = false

        fixture.processor.process("venue-a", requestOutcome("request-a", desired = true))

        val call = fixture.ack.calls.single()
        assertEquals(DisplayModeAcknowledgement.Rejected(DisplayModeAckResultCode.DISPLAY_NOT_INVERTIBLE), call.ack)
        assertFalse(call.confirmed)
        assertEquals(0, fixture.physical.relocationCount)
        assertTrue(fixture.events.indexOf("save:ack:request-a") < fixture.events.indexOf("http:request-a"))
    }

    @Test
    fun `older dirty local value is superseded by newer remote request`() = runTest {
        val fixture = fixture()
        fixture.prefs.localSet(false)
        val generationBeforeRequest = fixture.prefs.localGeneration

        fixture.processor.process("venue-a", requestOutcome("request-a", desired = true))

        assertTrue(fixture.prefs.invertedValue)
        assertFalse(fixture.prefs.dirtyValue)
        assertEquals(generationBeforeRequest, fixture.prefs.localGeneration)
    }

    @Test
    fun `local change after journal wins and dirty clears only for matching delivered ack generation`() = runTest {
        val fixture = fixture()
        fixture.journal.afterFirstSave = { fixture.prefs.localSet(false) }

        fixture.processor.process("venue-a", requestOutcome("request-a", desired = true))

        assertEquals(
            DisplayModeAcknowledgement.Rejected(DisplayModeAckResultCode.LOCAL_OVERRIDE),
            fixture.ack.calls.single().ack,
        )
        assertFalse(fixture.ack.calls.single().confirmed)
        assertFalse(fixture.prefs.dirtyValue)

        val second = fixture(capability = DisplayCapabilitySnapshot(true, true))
        second.journal.afterFirstSave = { second.prefs.localSet(false) }
        second.ack.onCall = { second.prefs.localSet(true) }
        second.processor.process("venue-a", requestOutcome("request-b", desired = false))
        assertTrue(second.prefs.dirtyValue)
    }

    @Test
    fun `local mutations before ack persistence retry until stable local override`() = runTest {
        val fixture = fixture()
        fixture.prefs.beforeStablePersistences += {
            fixture.prefs.localSet(false)
            fixture.physical.current = false
        }
        fixture.prefs.beforeStablePersistences += {
            fixture.prefs.localSet(true)
            fixture.physical.current = true
        }

        fixture.processor.process("venue-a", requestOutcome("request-a", desired = true))

        val call = fixture.ack.calls.single()
        assertEquals(
            DisplayModeAcknowledgement.Rejected(DisplayModeAckResultCode.LOCAL_OVERRIDE),
            call.ack,
        )
        assertTrue(call.confirmed)
        assertFalse(fixture.journal.entry?.ackOutcome == DisplayModeAckOutcome.APPLIED)
    }

    @Test
    fun `cancellation after journal resumes and cancellation after physical effect does not duplicate relocation`() = runTest {
        val fixture = fixture()
        fixture.journal.cancelOnSaveNumber = 2
        val first = runCatching {
            fixture.processor.process("venue-a", requestOutcome("request-a", desired = true))
        }.exceptionOrNull()
        assertTrue(first is CancellationException)
        assertNull(fixture.journal.entry?.applyStartedAt)
        assertEquals(0, fixture.physical.relocationCount)

        fixture.journal.cancelOnSaveNumber = null
        fixture.physical.cancelAfterEffect = true
        val second = runCatching {
            fixture.processor.process("venue-a", requestOutcome("request-a", desired = true))
        }.exceptionOrNull()
        assertTrue(second is CancellationException)
        assertEquals(1, fixture.physical.relocationCount)
        assertNotNull(fixture.journal.entry?.applyStartedAt)

        fixture.physical.cancelAfterEffect = false
        fixture.processor.process("venue-a", requestOutcome("request-a", desired = true))
        assertEquals(1, fixture.physical.relocationCount)
        assertEquals(1, fixture.ack.calls.size)
    }

    @Test
    fun `cancellation after ack persistence resumes by sending the durable ack only`() = runTest {
        val fixture = fixture()
        fixture.journal.cancelAfterSaveNumber = 3

        val failure = runCatching {
            fixture.processor.process("venue-a", requestOutcome("request-a", desired = true))
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(fixture.journal.entry?.ackPending == true)
        assertEquals(1, fixture.physical.relocationCount)
        assertEquals(0, fixture.ack.calls.size)

        fixture.journal.cancelAfterSaveNumber = null
        fixture.processor.process("venue-a", noRequestOutcome())

        assertEquals(1, fixture.physical.relocationCount)
        assertEquals(1, fixture.ack.calls.size)
        assertNull(fixture.journal.entry)
    }

    @Test
    fun `retryable and session invalid ack preserve durable state while superseded 409 delivers it`() = runTest {
        val entry = ackEntry("request-a", confirmed = true)
        val fixture = fixture(
            initialJournal = entry,
            ackOutcomes = ArrayDeque(
                listOf(
                    DisplayModeRemoteOutcome.Retryable(503),
                    DisplayModeRemoteOutcome.SessionInvalid,
                    DisplayModeRemoteOutcome.Rejected(409, "DEVICE_REQUEST_SUPERSEDED", "old"),
                ),
            ),
        )

        fixture.processor.process("venue-a", noRequestOutcome())
        assertEquals(entry, fixture.journal.entry)
        fixture.processor.process("venue-a", noRequestOutcome())
        assertEquals(entry, fixture.journal.entry)
        fixture.processor.process("venue-a", noRequestOutcome())
        assertNull(fixture.journal.entry)
    }

    private fun fixture(
        initialJournal: JournalEntry? = null,
        capability: DisplayCapabilitySnapshot = DisplayCapabilitySnapshot(true, true),
        physicalResults: ArrayDeque<PhysicalDisplayModeResult> = ArrayDeque(),
        ackOutcomes: ArrayDeque<DisplayModeRemoteOutcome<Unit>> = ArrayDeque(),
    ): Fixture {
        val events = mutableListOf<String>()
        val journal = FakeJournal(initialJournal, events)
        val prefs = FakePrefs()
        val physical = FakePhysicalApplier(physicalResults, events)
        val ack = FakeAckRemote(ackOutcomes, events)
        val processor = DisplayModeRequestProcessor(
            ackRemote = ack,
            journal = journal,
            prefs = prefs,
            physicalApplier = physical,
            capabilities = MutableStateFlow(capability),
            deviceIdProvider = CanonicalDeviceIdProvider { "device-a" },
            clock = DisplayModeProcessorClock { now },
            authorityGate = DisplayModeAuthorityGate(),
        )
        return Fixture(processor, journal, prefs, physical, ack, events)
    }

    private fun requestOutcome(requestId: String, desired: Boolean) = DisplayModeRemoteOutcome.Success(
        DisplayModeRequestBinding(
            terminalId = "terminal-a",
            request = RemoteDisplayModeRequest(
                requestId = requestId,
                desiredInverted = desired,
                requestedAt = now.minusSeconds(1),
                expiresAt = now.plusSeconds(900),
            ),
        ),
    )

    private fun noRequestOutcome(): DisplayModeRemoteOutcome<DisplayModeRequestBinding> =
        DisplayModeRemoteOutcome.NoRequest("terminal-a")

    private fun newEntry(requestId: String, desired: Boolean) = JournalEntry(
        venueId = "venue-a",
        deviceId = "device-a",
        terminalId = "terminal-a",
        requestId = requestId,
        desiredInverted = desired,
        appliedLocally = false,
        ackPending = false,
        localGenerationAtJournal = 0,
        requestExpiresAt = now.plusSeconds(900),
        journaledAt = now,
    )

    private fun ackEntry(requestId: String, confirmed: Boolean) =
        newEntry(requestId, confirmed).copy(
            applyStartedAt = now,
            appliedLocally = true,
            ackPending = true,
            appliedAt = now,
            ackPreparedAt = now,
            ackOutcome = DisplayModeAckOutcome.APPLIED,
            confirmedInverted = confirmed,
        )

    private data class Fixture(
        val processor: DisplayModeRequestProcessor,
        val journal: FakeJournal,
        val prefs: FakePrefs,
        val physical: FakePhysicalApplier,
        val ack: FakeAckRemote,
        val events: MutableList<String>,
    )

    private class FakeJournal(
        initial: JournalEntry?,
        private val events: MutableList<String>,
    ) : DisplayModeRequestJournal {
        var entry: JournalEntry? = initial
        var afterFirstSave: (() -> Unit)? = null
        var cancelOnSaveNumber: Int? = null
        var cancelAfterSaveNumber: Int? = null
        private var saves = 0

        override fun readState(venueId: String, deviceId: String): DisplayModeJournalState =
            entry?.let(DisplayModeJournalState::Ready) ?: DisplayModeJournalState.Empty

        override fun save(entry: JournalEntry) {
            saves += 1
            if (cancelOnSaveNumber == saves) throw CancellationException("window-$saves")
            this.entry = entry
            val phase = when {
                entry.ackPending -> "ack"
                entry.applyStartedAt != null -> "started"
                else -> "new"
            }
            events += "save:$phase:${entry.requestId}"
            if (saves == 1) afterFirstSave?.invoke()
            if (cancelAfterSaveNumber == saves) throw CancellationException("after-window-$saves")
        }

        override fun clear(venueId: String, deviceId: String, requestId: String) {
            if (entry?.requestId == requestId) entry = null
        }
    }

    private class FakePrefs : DisplayModePreferenceStore {
        override var invertedValue = false
        override var dirtyValue = false
        override var localGeneration = 0L
        var remoteApplyCalls = 0
        val beforeStablePersistences = ArrayDeque<() -> Unit>()

        fun localSet(value: Boolean) {
            invertedValue = value
            dirtyValue = true
            localGeneration += 1
        }

        override fun applyRemoteIntent(value: Boolean, localGenerationAtJournal: Long): RemoteDisplayModeApplyResult {
            remoteApplyCalls += 1
            if (localGeneration > localGenerationAtJournal) {
                return RemoteDisplayModeApplyResult.LocalOverride(invertedValue, localGeneration)
            }
            invertedValue = value
            dirtyValue = false
            return RemoteDisplayModeApplyResult.Applied(value)
        }

        override fun markSynced(expectedGeneration: Long, expectedValue: Boolean): Boolean {
            if (localGeneration != expectedGeneration || invertedValue != expectedValue) return false
            dirtyValue = false
            return true
        }

        override fun snapshot(): DisplayModePreferenceSnapshot =
            DisplayModePreferenceSnapshot(invertedValue, localGeneration)

        override fun persistIfUnchanged(
            expected: DisplayModePreferenceSnapshot,
            persist: () -> Unit,
        ): Boolean {
            beforeStablePersistences.removeFirstOrNull()?.invoke()
            if (snapshot() != expected) return false
            persist()
            return true
        }
    }

    private class FakePhysicalApplier(
        private val results: ArrayDeque<PhysicalDisplayModeResult>,
        private val events: MutableList<String>,
    ) : DisplayModePhysicalApplier {
        var current: Boolean? = false
        var relocationCount = 0
        var cancelAfterEffect = false
        val requestedValues = mutableListOf<Boolean>()

        override suspend fun applyAndConfirm(desiredInverted: Boolean): PhysicalDisplayModeResult {
            requestedValues += desiredInverted
            if (current == desiredInverted) return PhysicalDisplayModeResult.Confirmed(desiredInverted)
            relocationCount += 1
            events += "physical:$desiredInverted"
            current = desiredInverted
            if (cancelAfterEffect) throw CancellationException("after-effect")
            return results.removeFirstOrNull() ?: PhysicalDisplayModeResult.Confirmed(desiredInverted)
        }

        override suspend fun observeConfirmedMode(): Boolean? = current
    }

    private class FakeAckRemote(
        private val outcomes: ArrayDeque<DisplayModeRemoteOutcome<Unit>>,
        private val events: MutableList<String>,
    ) : DisplayModeAckRemote {
        val calls = mutableListOf<AckCall>()
        var onCall: (() -> Unit)? = null

        override suspend fun acknowledge(
            venueId: String,
            terminalId: String,
            requestId: String,
            confirmedInverted: Boolean,
            acknowledgement: DisplayModeAcknowledgement,
        ): DisplayModeRemoteOutcome<Unit> {
            events += "http:$requestId"
            calls += AckCall(requestId, confirmedInverted, acknowledgement)
            onCall?.invoke()
            return outcomes.removeFirstOrNull() ?: DisplayModeRemoteOutcome.Success(Unit)
        }
    }

    private data class AckCall(
        val requestId: String,
        val confirmed: Boolean,
        val ack: DisplayModeAcknowledgement,
    )
}
