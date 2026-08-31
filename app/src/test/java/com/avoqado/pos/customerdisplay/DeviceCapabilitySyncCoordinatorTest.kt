package com.avoqado.pos.customerdisplay

import androidx.lifecycle.LifecycleOwner
import io.mockk.mockk
import kotlin.math.ceil
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceCapabilitySyncCoordinatorTest {

    private val lifecycleOwner = mockk<LifecycleOwner>(relaxed = true)

    @Test
    fun `customer display state stays unknown until manager publishes one snapshot`() {
        val state = CustomerDisplayState()

        assertNull(state.capabilities.value)
        assertEquals(false, state.invertible.value)

        val observed = DisplayCapabilitySnapshot(present = true, invertible = true)
        state.updateCapabilities(observed)

        assertEquals(observed, state.capabilities.value)
        assertEquals(true, state.invertible.value)
    }

    @Test
    fun `foreground waits for a real hardware observation then ticks immediately`() = runTest {
        val fixture = fixture(snapshot = null)

        fixture.coordinator.onStart(lifecycleOwner)
        runCurrent()

        assertEquals(0, fixture.remote.fetchCalls)
        assertNull(fixture.snapshots.value)

        fixture.snapshots.value = DisplayCapabilitySnapshot(present = false, invertible = false)
        runCurrent()

        assertEquals(1, fixture.remote.reportCalls)
        assertEquals(1, fixture.remote.fetchCalls)
        assertEquals(
            listOf(DisplayCapabilitySnapshot(present = false, invertible = false)),
            fixture.remote.reportedSnapshots,
        )
    }

    @Test
    fun `foreground poll repeats after bounded configured jitter`() = runTest {
        val fixture = fixture(jitterMs = 15_000L)

        fixture.coordinator.onStart(lifecycleOwner)
        runCurrent()
        assertEquals(1, fixture.remote.fetchCalls)

        advanceTimeBy(14_999L)
        runCurrent()
        assertEquals(1, fixture.remote.fetchCalls)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, fixture.remote.fetchCalls)
    }

    @Test
    fun `configured jitter is clamped so worst foreground wait is twenty seconds`() = runTest {
        val fixture = fixture(jitterMs = Long.MAX_VALUE)

        fixture.coordinator.onStart(lifecycleOwner)
        runCurrent()
        advanceTimeBy(19_999L)
        runCurrent()
        assertEquals(1, fixture.remote.fetchCalls)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(2, fixture.remote.fetchCalls)
    }

    @Test
    fun `consumer entry p95 and worst stay at or below twenty seconds for one hundred offsets`() = runTest {
        val availability = (0 until 101).map { index ->
            index * 40_000L + ((index * 7_919L) % 20_000L)
        }
        val fixture = fixture(
            jitterMs = 20_000L,
            requestAvailabilityMs = availability,
        )

        fixture.coordinator.onStart(lifecycleOwner)
        advanceTimeBy(availability.last() + 40_000L)
        runCurrent()

        val latencies = fixture.processor.processedAtMs.zip(availability) { consumedAt, availableAt ->
            consumedAt - availableAt
        }.sorted()
        val p95Index = ceil(latencies.size * 0.95).toInt().coerceAtLeast(1) - 1

        assertEquals(availability.size, latencies.size)
        assertTrue("p95=${latencies[p95Index]}", latencies[p95Index] <= 20_000L)
        assertTrue("worst=${latencies.last()}", latencies.last() <= 20_000L)
    }

    @Test
    fun `login venue snapshot and network recovery each wake the single loop`() = runTest {
        val fixture = fixture(loggedIn = false, connected = false)

        fixture.coordinator.onStart(lifecycleOwner)
        runCurrent()
        assertEquals(0, fixture.remote.fetchCalls)

        fixture.session.loggedIn = true
        fixture.session.venueId = "venue-a"
        fixture.coordinator.onSessionChanged()
        runCurrent()
        assertEquals(0, fixture.remote.fetchCalls)

        fixture.connected.value = true
        runCurrent()
        assertEquals(listOf("venue-a"), fixture.remote.fetchedVenues)

        fixture.session.venueId = "venue-b"
        fixture.coordinator.onSessionChanged()
        runCurrent()
        assertEquals(listOf("venue-a", "venue-b"), fixture.remote.fetchedVenues)

        fixture.snapshots.value = DisplayCapabilitySnapshot(present = true, invertible = false)
        runCurrent()
        assertEquals(listOf("venue-a", "venue-b", "venue-b"), fixture.remote.fetchedVenues)
        assertEquals(3, fixture.remote.reportCalls)
    }

    @Test
    fun `successful no-request and request outcomes reach the processor immediately`() = runTest {
        val fixture = fixture(
            fetchOutcomes = ArrayDeque(
                listOf(
                    DisplayModeRemoteOutcome.NoRequest("terminal-a"),
                    successRequest("terminal-a", "request-a"),
                ),
            ),
            jitterMs = 15_000L,
        )

        fixture.coordinator.onStart(lifecycleOwner)
        runCurrent()
        advanceTimeBy(15_000L)
        runCurrent()

        assertEquals(2, fixture.processor.outcomes.size)
        assertTrue(fixture.processor.outcomes[0] is DisplayModeRemoteOutcome.NoRequest)
        assertTrue(fixture.processor.outcomes[1] is DisplayModeRemoteOutcome.Success)
    }

    @Test
    fun `capabilities re-report every twenty four hours and immediately when changed`() = runTest {
        // 17 s no divide 24 h: el coordinator debe recortar el último wait,
        // no reportar hasta 16 s tarde por esperar el siguiente poll.
        val fixture = fixture(jitterMs = 17_000L)

        fixture.coordinator.onStart(lifecycleOwner)
        runCurrent()
        assertEquals(1, fixture.remote.reportCalls)

        fixture.snapshots.value = DisplayCapabilitySnapshot(present = true, invertible = true)
        runCurrent()
        assertEquals(2, fixture.remote.reportCalls)

        advanceTimeBy(DeviceCapabilitySyncCoordinator.CAPABILITY_REPORT_INTERVAL_MS - 1L)
        runCurrent()
        assertEquals(2, fixture.remote.reportCalls)

        advanceTimeBy(1L)
        runCurrent()
        assertEquals(3, fixture.remote.reportCalls)
    }

    @Test
    fun `failed capability report remains due while GET can still deliver a request`() = runTest {
        val fixture = fixture(
            reportOutcomes = ArrayDeque(
                listOf(
                    DisplayModeRemoteOutcome.Retryable(),
                    DisplayModeRemoteOutcome.Success(Unit),
                ),
            ),
            fetchOutcomes = ArrayDeque(
                listOf(
                    successRequest("terminal-a", "request-a"),
                    DisplayModeRemoteOutcome.NoRequest("terminal-a"),
                ),
            ),
        )

        fixture.coordinator.onStart(lifecycleOwner)
        runCurrent()

        assertEquals(1, fixture.processor.outcomes.size)
        assertEquals(1, fixture.remote.reportCalls)
        assertEquals(1, fixture.remote.fetchCalls)

        advanceTimeBy(1_000L)
        runCurrent()

        assertEquals(2, fixture.remote.reportCalls)
        assertEquals(2, fixture.remote.fetchCalls)
    }

    @Test
    fun `capability PUT connectivity flip cannot cancel GET or processor delivery`() = runTest {
        val fixture = fixture(
            fetchOutcomes = ArrayDeque(
                listOf(successRequest("terminal-a", "request-a")),
            ),
            reportOutcomes = ArrayDeque(
                listOf(DisplayModeRemoteOutcome.Retryable(503)),
            ),
            reportSideEffect = { reachable -> reachable.value = false },
            yieldAfterReportSideEffect = true,
        )

        // Ensure the connectivity collector is subscribed before the PUT flips
        // reachability; this reproduces the production cancellation race.
        runCurrent()
        fixture.coordinator.onStart(lifecycleOwner)
        runCurrent()

        assertEquals(1, fixture.remote.fetchCalls)
        assertEquals(1, fixture.remote.reportCalls)
        assertEquals(1, fixture.processor.outcomes.size)
        assertTrue(fixture.processor.outcomes.single() is DisplayModeRemoteOutcome.Success)
    }

    @Test
    fun `snapshot changed before observer startup still wakes an immediate tick`() {
        val manual = manualFixture(snapshot = null, connected = true)
        manual.coordinator.onStart(lifecycleOwner)

        // Init queued snapshot observer, connectivity observer, scheduler. Run
        // only scheduler + its first null-snapshot tick, leaving observers cold.
        manual.dispatcher.runAt(2)
        manual.dispatcher.runAt(2)
        manual.dispatcher.runAt(2)
        assertEquals(0, manual.remote.fetchCalls)

        manual.snapshots.value = DisplayCapabilitySnapshot(present = true, invertible = false)
        manual.dispatcher.runAt(0)
        manual.dispatcher.runTailWhileKeepingFirst()

        assertEquals(1, manual.remote.fetchCalls)
        manual.scope.cancel()
    }

    @Test
    fun `connectivity recovered before observer startup still wakes an immediate tick`() {
        val manual = manualFixture(
            snapshot = DisplayCapabilitySnapshot(present = true, invertible = false),
            connected = false,
        )
        manual.coordinator.onStart(lifecycleOwner)

        // Run scheduler + its offline tick before either flow observer starts.
        manual.dispatcher.runAt(2)
        manual.dispatcher.runAt(2)
        manual.dispatcher.runAt(2)
        assertEquals(0, manual.remote.fetchCalls)

        manual.connected.value = true
        manual.dispatcher.runAt(1)
        manual.dispatcher.runTailWhileKeepingFirst()

        assertEquals(1, manual.remote.fetchCalls)
        manual.scope.cancel()
    }

    @Test
    fun `wakeups are conflated and never create concurrent network ticks`() = runTest {
        val fixture = fixture(networkDelayMs = 1_000L)

        fixture.coordinator.onStart(lifecycleOwner)
        runCurrent()
        assertEquals(1, fixture.remote.activeCalls)

        fixture.coordinator.onSessionChanged()
        fixture.coordinator.onSessionChanged()
        fixture.snapshots.value = DisplayCapabilitySnapshot(present = true, invertible = false)
        runCurrent()

        assertEquals(1, fixture.remote.maxActiveCalls)
        // La secuencia es deliberadamente serial: GET (1 s) y luego PUT (1 s).
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(1, fixture.remote.maxActiveCalls)
        assertEquals(1, fixture.remote.fetchCalls)
    }

    @Test
    fun `background and logout cancel active work and foreground can restart`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fixture = fixture(reportGate = gate)

        fixture.coordinator.onStart(lifecycleOwner)
        runCurrent()
        assertEquals(1, fixture.remote.activeCalls)

        fixture.coordinator.onStop(lifecycleOwner)
        runCurrent()
        assertEquals(0, fixture.remote.activeCalls)
        assertEquals(1, fixture.remote.fetchCalls)

        fixture.coordinator.onStart(lifecycleOwner)
        runCurrent()
        assertEquals(1, fixture.remote.activeCalls)

        fixture.session.loggedIn = false
        fixture.session.venueId = null
        fixture.coordinator.onSessionChanged()
        runCurrent()
        assertEquals(0, fixture.remote.activeCalls)
        assertEquals(2, fixture.remote.fetchCalls)
    }

    @Test
    fun `offline uses bounded exponential backoff without making calls`() = runTest {
        val fixture = fixture(connected = false)

        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 20_000L, 20_000L),
            (0..6).map(DeviceCapabilitySyncCoordinator::offlineBackoffMs),
        )

        fixture.coordinator.onStart(lifecycleOwner)
        advanceTimeBy(2 * 60_000L)
        runCurrent()

        assertEquals(0, fixture.remote.reportCalls)
        assertEquals(0, fixture.remote.fetchCalls)
    }

    @Test
    fun `transient poll failure retries and later reaches the processor`() = runTest {
        val fixture = fixture(
            fetchOutcomes = ArrayDeque(
                listOf(
                    DisplayModeRemoteOutcome.Retryable(503),
                    DisplayModeRemoteOutcome.NoRequest("terminal-a"),
                ),
            ),
        )

        fixture.coordinator.onStart(lifecycleOwner)
        runCurrent()
        assertEquals(0, fixture.processor.outcomes.size)

        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(1, fixture.processor.outcomes.size)
    }

    @Test
    fun `session-invalid parks polling without clearing local auth and wakes after session change`() = runTest {
        val fixture = fixture(
            fetchOutcomes = ArrayDeque(
                listOf(
                    DisplayModeRemoteOutcome.SessionInvalid,
                    DisplayModeRemoteOutcome.NoRequest("terminal-a"),
                ),
            ),
        )

        fixture.coordinator.onStart(lifecycleOwner)
        runCurrent()
        assertTrue(fixture.session.loggedIn)
        assertEquals("venue-a", fixture.session.venueId)

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, fixture.remote.fetchCalls)

        fixture.coordinator.onSessionChanged()
        runCurrent()
        assertEquals(2, fixture.remote.fetchCalls)
        assertEquals(1, fixture.processor.outcomes.size)
    }

    private fun TestScope.fixture(
        snapshot: DisplayCapabilitySnapshot? = DisplayCapabilitySnapshot(false, false),
        loggedIn: Boolean = true,
        connected: Boolean = true,
        reachable: Boolean = true,
        jitterMs: Long = 15_000L,
        networkDelayMs: Long = 0L,
        reportGate: CompletableDeferred<Unit>? = null,
        reportOutcomes: ArrayDeque<DisplayModeRemoteOutcome<Unit>> = ArrayDeque(),
        fetchOutcomes: ArrayDeque<DisplayModeRemoteOutcome<DisplayModeRequestBinding>> = ArrayDeque(),
        requestAvailabilityMs: List<Long> = emptyList(),
        reportSideEffect: ((MutableStateFlow<Boolean>) -> Unit)? = null,
        yieldAfterReportSideEffect: Boolean = false,
    ): Fixture {
        val snapshots = MutableStateFlow(snapshot)
        val connectedFlow = MutableStateFlow(connected)
        val reachableFlow = MutableStateFlow(reachable)
        val session = MutableSession(loggedIn = loggedIn, venueId = if (loggedIn) "venue-a" else null)
        val remote = FakeRemote(
            nowMs = { testScheduler.currentTime },
            networkDelayMs = networkDelayMs,
            reportGate = reportGate,
            reportOutcomes = reportOutcomes,
            fetchOutcomes = fetchOutcomes,
            requestAvailabilityMs = requestAvailabilityMs,
            onReport = { reportSideEffect?.invoke(reachableFlow) },
            yieldAfterReportSideEffect = yieldAfterReportSideEffect,
        )
        val processor = RecordingProcessor { testScheduler.currentTime }
        val coordinator = DeviceCapabilitySyncCoordinator(
            remote = remote,
            processor = processor,
            snapshots = snapshots,
            sessionProvider = session,
            connected = connectedFlow,
            serverReachable = reachableFlow,
            scope = backgroundScope,
            clock = CoordinatorClock { testScheduler.currentTime },
            jitter = PollJitter { _, _ -> jitterMs },
        )
        return Fixture(
            coordinator = coordinator,
            remote = remote,
            processor = processor,
            snapshots = snapshots,
            connected = connectedFlow,
            reachable = reachableFlow,
            session = session,
        )
    }

    private data class Fixture(
        val coordinator: DeviceCapabilitySyncCoordinator,
        val remote: FakeRemote,
        val processor: RecordingProcessor,
        val snapshots: MutableStateFlow<DisplayCapabilitySnapshot?>,
        val connected: MutableStateFlow<Boolean>,
        val reachable: MutableStateFlow<Boolean>,
        val session: MutableSession,
    )

    private class MutableSession(
        var loggedIn: Boolean,
        var venueId: String?,
    ) : DeviceSessionProvider {
        override fun currentSession(): DeviceSession? = if (loggedIn) {
            venueId?.takeIf(String::isNotBlank)?.let(::DeviceSession)
        } else {
            null
        }
    }

    private class FakeRemote(
        private val nowMs: () -> Long,
        private val networkDelayMs: Long,
        private val reportGate: CompletableDeferred<Unit>?,
        private val reportOutcomes: ArrayDeque<DisplayModeRemoteOutcome<Unit>>,
        private val fetchOutcomes: ArrayDeque<DisplayModeRemoteOutcome<DisplayModeRequestBinding>>,
        requestAvailabilityMs: List<Long>,
        private val onReport: (() -> Unit)? = null,
        private val yieldAfterReportSideEffect: Boolean = false,
    ) : DeviceCapabilityRemote {
        private val pendingAvailability = ArrayDeque(requestAvailabilityMs)
        var reportCalls = 0
        var fetchCalls = 0
        var activeCalls = 0
        var maxActiveCalls = 0
        val reportedSnapshots = mutableListOf<DisplayCapabilitySnapshot>()
        val fetchedVenues = mutableListOf<String>()

        override suspend fun reportCapabilities(
            venueId: String,
            snapshot: DisplayCapabilitySnapshot,
        ): DisplayModeRemoteOutcome<Unit> = networkCall {
            reportCalls += 1
            reportedSnapshots += snapshot
            reportGate?.await()
            onReport?.invoke()
            if (yieldAfterReportSideEffect) yield()
            reportOutcomes.removeFirstOrNull() ?: DisplayModeRemoteOutcome.Success(Unit)
        }

        override suspend fun fetchDisplayModeRequest(
            venueId: String,
        ): DisplayModeRemoteOutcome<DisplayModeRequestBinding> = networkCall {
            fetchCalls += 1
            fetchedVenues += venueId
            fetchOutcomes.removeFirstOrNull()
                ?: pendingAvailability.firstOrNull()?.takeIf { it <= nowMs() }?.let { availableAt ->
                    pendingAvailability.removeFirst()
                    successRequest("terminal-a", "request-$availableAt")
                }
                ?: DisplayModeRemoteOutcome.NoRequest("terminal-a")
        }

        private suspend fun <T> networkCall(block: suspend () -> T): T {
            activeCalls += 1
            maxActiveCalls = maxOf(maxActiveCalls, activeCalls)
            return try {
                if (networkDelayMs > 0L) delay(networkDelayMs)
                block()
            } finally {
                activeCalls -= 1
            }
        }
    }

    private class RecordingProcessor(
        private val nowMs: () -> Long,
    ) : DisplayModeRequestProcessor() {
        val outcomes = mutableListOf<DisplayModeRemoteOutcome<DisplayModeRequestBinding>>()
        val processedAtMs = mutableListOf<Long>()

        override suspend fun process(
            venueId: String,
            outcome: DisplayModeRemoteOutcome<DisplayModeRequestBinding>,
        ) {
            outcomes += outcome
            if (outcome is DisplayModeRemoteOutcome.Success) processedAtMs += nowMs()
        }
    }

    private fun manualFixture(
        snapshot: DisplayCapabilitySnapshot?,
        connected: Boolean,
    ): ManualFixture {
        val dispatcher = ManualDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val snapshots = MutableStateFlow(snapshot)
        val connectedFlow = MutableStateFlow(connected)
        val reachableFlow = MutableStateFlow(true)
        val remote = FakeRemote(
            nowMs = { 0L },
            networkDelayMs = 0L,
            reportGate = null,
            reportOutcomes = ArrayDeque(),
            fetchOutcomes = ArrayDeque(),
            requestAvailabilityMs = emptyList(),
        )
        val coordinator = DeviceCapabilitySyncCoordinator(
            remote = remote,
            processor = RecordingProcessor { 0L },
            snapshots = snapshots,
            sessionProvider = DeviceSessionProvider { DeviceSession("venue-a") },
            connected = connectedFlow,
            serverReachable = reachableFlow,
            scope = scope,
            clock = CoordinatorClock { 0L },
            jitter = PollJitter { baseMs, _ -> baseMs },
        )
        return ManualFixture(
            coordinator = coordinator,
            remote = remote,
            snapshots = snapshots,
            connected = connectedFlow,
            dispatcher = dispatcher,
            scope = scope,
        )
    }

    private data class ManualFixture(
        val coordinator: DeviceCapabilitySyncCoordinator,
        val remote: FakeRemote,
        val snapshots: MutableStateFlow<DisplayCapabilitySnapshot?>,
        val connected: MutableStateFlow<Boolean>,
        val dispatcher: ManualDispatcher,
        val scope: CoroutineScope,
    )

    private class ManualDispatcher : CoroutineDispatcher() {
        private val queued = mutableListOf<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(queued) { queued += block }
        }

        fun runAt(index: Int) {
            val next = synchronized(queued) {
                check(index in queued.indices) {
                    "No queued coroutine at $index; size=${queued.size}"
                }
                queued.removeAt(index)
            }
            next.run()
        }

        /** Runs wake/tick work while deliberately leaving observer 0 cold. */
        fun runTailWhileKeepingFirst(maxSteps: Int = 50) {
            repeat(maxSteps) {
                val lastIndex = synchronized(queued) { queued.lastIndex }
                if (lastIndex <= 0) return
                runAt(lastIndex)
            }
            error("Manual dispatcher did not quiesce within $maxSteps steps")
        }
    }

    private companion object {
        fun successRequest(
            terminalId: String,
            requestId: String,
        ): DisplayModeRemoteOutcome.Success<DisplayModeRequestBinding> = DisplayModeRemoteOutcome.Success(
            DisplayModeRequestBinding(
                terminalId = terminalId,
                request = RemoteDisplayModeRequest(
                    requestId = requestId,
                    desiredInverted = true,
                    status = RemoteDisplayModeRequest.STATUS_PENDING,
                    requestedAt = java.time.Instant.EPOCH,
                    expiresAt = java.time.Instant.EPOCH.plusSeconds(300),
                ),
            ),
        )
    }
}
