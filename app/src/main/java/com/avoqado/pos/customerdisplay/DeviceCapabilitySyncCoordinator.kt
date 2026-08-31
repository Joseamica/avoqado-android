package com.avoqado.pos.customerdisplay

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

internal data class DeviceSession(val venueId: String)

internal fun interface DeviceSessionProvider {
    fun currentSession(): DeviceSession?
}

internal fun interface CoordinatorClock {
    fun nowMillis(): Long
}

internal fun interface PollJitter {
    fun nextDelayMillis(baseMs: Long, maximumMs: Long): Long
}

/** Límite pequeño que permite probar el scheduler sin HTTP real. */
internal interface DeviceCapabilityRemote {
    suspend fun reportCapabilities(
        venueId: String,
        snapshot: DisplayCapabilitySnapshot,
    ): DisplayModeRemoteOutcome<Unit>

    suspend fun fetchDisplayModeRequest(
        venueId: String,
    ): DisplayModeRemoteOutcome<DisplayModeRequestBinding>
}

internal fun interface DisplayModeProcessorClock {
    fun now(): Instant
}

internal interface DisplayModeAckRemote {
    suspend fun acknowledge(
        venueId: String,
        terminalId: String,
        requestId: String,
        confirmedInverted: Boolean,
        acknowledgement: DisplayModeAcknowledgement,
    ): DisplayModeRemoteOutcome<Unit>
}

private class RepositoryDisplayModeAckRemote(
    private val repository: DisplayModeRemoteRepository,
) : DisplayModeAckRemote {
    override suspend fun acknowledge(
        venueId: String,
        terminalId: String,
        requestId: String,
        confirmedInverted: Boolean,
        acknowledgement: DisplayModeAcknowledgement,
    ): DisplayModeRemoteOutcome<Unit> = repository.acknowledgeDisplayMode(
        venueId = venueId,
        terminalId = terminalId,
        requestId = requestId,
        customerDisplayInverted = confirmedInverted,
        acknowledgement = acknowledgement,
    )
}

/** Single durable control path: journal → exact apply → observed ACK → clear. */
@Singleton
open class DisplayModeRequestProcessor internal constructor(
    private val ackRemote: DisplayModeAckRemote?,
    private val journal: DisplayModeRequestJournal?,
    private val prefs: DisplayModePreferenceStore?,
    private val physicalApplier: DisplayModePhysicalApplier?,
    private val capabilities: StateFlow<DisplayCapabilitySnapshot?>?,
    private val deviceIdProvider: CanonicalDeviceIdProvider?,
    private val clock: DisplayModeProcessorClock?,
    private val authorityGate: DisplayModeAuthorityGate?,
) {
    /** Test-only subclass seam retained for Task 13 scheduler tests. */
    constructor() : this(null, null, null, null, null, null, null, null)

    @Inject
    constructor(
        repository: DisplayModeRemoteRepository,
        journal: DisplayModeRequestStore,
        prefs: DisplayModePrefs,
        physicalApplier: CustomerDisplayManager,
        customerDisplayState: CustomerDisplayState,
        syncOutboxProvider: Provider<SyncOutbox>,
        authorityGate: DisplayModeAuthorityGate,
    ) : this(
        ackRemote = RepositoryDisplayModeAckRemote(repository),
        journal = journal,
        prefs = prefs,
        physicalApplier = physicalApplier,
        capabilities = customerDisplayState.capabilities,
        deviceIdProvider = CanonicalDeviceIdProvider { syncOutboxProvider.get().deviceId },
        clock = DisplayModeProcessorClock { Instant.now() },
        authorityGate = authorityGate,
    )

    open suspend fun process(
        venueId: String,
        outcome: DisplayModeRemoteOutcome<DisplayModeRequestBinding>,
    ) {
        val ackRemote = ackRemote ?: return
        val journal = journal ?: return
        val prefs = prefs ?: return
        val physicalApplier = physicalApplier ?: return
        val capabilities = capabilities ?: return
        val authorityGate = authorityGate ?: return
        val deviceId = runCatching { deviceIdProvider?.currentDeviceId() }
            .getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: return
        val now = clock?.now() ?: return

        var replaceNeverStartedRequestId: String? = null
        when (val state = journal.readState(venueId, deviceId)) {
            DisplayModeJournalState.Unreadable -> return
            is DisplayModeJournalState.Ready -> {
                if (state.entry.ackPending) {
                    deliverAck(state.entry, ackRemote, journal, prefs)
                    return
                }
                if (state.entry.applyStartedAt != null) {
                    resumeEntry(state.entry, now, ackRemote, journal, prefs, physicalApplier, capabilities)
                    return
                }

                // A durable entry that never crossed applyStartedAt is only an
                // intent candidate. It is not authority to apply after the
                // server has withdrawn it or returned a different current B.
                when (outcome) {
                    is DisplayModeRemoteOutcome.Success -> {
                        val current = outcome.data.request
                        if (
                            current.requestId == state.entry.requestId &&
                            current.status == RemoteDisplayModeRequest.STATUS_PENDING &&
                            now.isBefore(current.expiresAt)
                        ) {
                            resumeEntry(
                                state.entry,
                                now,
                                ackRemote,
                                journal,
                                prefs,
                                physicalApplier,
                                capabilities,
                            )
                            return
                        }
                        replaceNeverStartedRequestId = state.entry.requestId
                        // Continue: current B replaces A atomically under the
                        // same authority gate used by settings legacy effects.
                    }

                    is DisplayModeRemoteOutcome.NoRequest -> {
                        authorityGate.withAuthority {
                            journal.clear(venueId, deviceId, state.entry.requestId)
                        }
                        return
                    }

                    else -> return // Network/session uncertainty preserves A.
                }
            }

            DisplayModeJournalState.Empty -> Unit
        }

        val binding = (outcome as? DisplayModeRemoteOutcome.Success)?.data ?: return
        val request = binding.request
        if (request.status != RemoteDisplayModeRequest.STATUS_PENDING || !now.isBefore(request.expiresAt)) {
            replaceNeverStartedRequestId?.let { requestId ->
                authorityGate.withAuthority { journal.clear(venueId, deviceId, requestId) }
            }
            return
        }
        val capability = capabilities.value ?: return
        val entry = authorityGate.withAuthority {
            replaceNeverStartedRequestId?.let { requestId ->
                journal.clear(venueId, deviceId, requestId)
            }
            if (journal.readState(venueId, deviceId) !is DisplayModeJournalState.Empty) {
                return@withAuthority null
            }
            JournalEntry(
                venueId = venueId,
                deviceId = deviceId,
                terminalId = binding.terminalId,
                requestId = request.requestId,
                desiredInverted = request.desiredInverted,
                appliedLocally = false,
                ackPending = false,
                localGenerationAtJournal = prefs.localGeneration,
                requestExpiresAt = request.expiresAt,
                journaledAt = now,
            ).also(journal::save)
        } ?: return
        if (!capability.present || !capability.invertible) {
            val confirmed = physicalApplier.observeConfirmedMode() ?: return
            prepareAndDeliverAck(
                entry = entry,
                outcome = DisplayModeAckOutcome.REJECTED,
                resultCode = DisplayModeAckResultCode.DISPLAY_NOT_INVERTIBLE,
                confirmedInverted = confirmed,
                confirmedGeneration = null,
                now = now,
                ackRemote = ackRemote,
                journal = journal,
                prefs = prefs,
            )
            return
        }
        resumeEntry(entry, now, ackRemote, journal, prefs, physicalApplier, capabilities)
    }

    private suspend fun resumeEntry(
        original: JournalEntry,
        now: Instant,
        ackRemote: DisplayModeAckRemote,
        journal: DisplayModeRequestJournal,
        prefs: DisplayModePreferenceStore,
        physicalApplier: DisplayModePhysicalApplier,
        capabilities: StateFlow<DisplayCapabilitySnapshot?>,
    ) {
        if (original.applyStartedAt == null && !now.isBefore(original.requestExpiresAt)) {
            journal.clear(original.venueId, original.deviceId, original.requestId)
            return
        }
        val capability = capabilities.value ?: return
        if (!capability.present || !capability.invertible) {
            val confirmed = physicalApplier.observeConfirmedMode() ?: return
            prepareAndDeliverAck(
                original,
                DisplayModeAckOutcome.REJECTED,
                DisplayModeAckResultCode.DISPLAY_NOT_INVERTIBLE,
                confirmed,
                null,
                now,
                ackRemote,
                journal,
                prefs,
            )
            return
        }

        val started = if (original.applyStartedAt == null) {
            original.copy(applyStartedAt = now).also(journal::save)
        } else {
            original
        }
        when (val preferenceResult = prefs.applyRemoteIntent(started.desiredInverted, started.localGenerationAtJournal)) {
            is RemoteDisplayModeApplyResult.LocalOverride -> {
                persistStableLocalOverrideAndDeliver(
                    started,
                    now,
                    ackRemote,
                    journal,
                    prefs,
                    physicalApplier,
                )
            }

            is RemoteDisplayModeApplyResult.Applied -> {
                val physicalResult = physicalApplier.applyAndConfirm(started.desiredInverted)
                when (physicalResult) {
                    is PhysicalDisplayModeResult.Confirmed -> persistPhysicalResultOrLocalOverride(
                        entry = started,
                        outcome = DisplayModeAckOutcome.APPLIED,
                        resultCode = null,
                        confirmedInverted = physicalResult.inverted,
                        now = now,
                        ackRemote = ackRemote,
                        journal = journal,
                        prefs = prefs,
                        physicalApplier = physicalApplier,
                    )

                    is PhysicalDisplayModeResult.Rejected -> persistPhysicalResultOrLocalOverride(
                        entry = started,
                        outcome = DisplayModeAckOutcome.REJECTED,
                        resultCode = physicalResult.resultCode,
                        confirmedInverted = physicalResult.confirmedInverted,
                        now = now,
                        ackRemote = ackRemote,
                        journal = journal,
                        prefs = prefs,
                        physicalApplier = physicalApplier,
                    )

                    PhysicalDisplayModeResult.Pending -> Unit
                }
            }
        }
    }

    private suspend fun persistPhysicalResultOrLocalOverride(
        entry: JournalEntry,
        outcome: DisplayModeAckOutcome,
        resultCode: DisplayModeAckResultCode?,
        confirmedInverted: Boolean,
        now: Instant,
        ackRemote: DisplayModeAckRemote,
        journal: DisplayModeRequestJournal,
        prefs: DisplayModePreferenceStore,
        physicalApplier: DisplayModePhysicalApplier,
    ) {
        var pending: JournalEntry? = null
        val expected = DisplayModePreferenceSnapshot(
            inverted = entry.desiredInverted,
            generation = entry.localGenerationAtJournal,
        )
        val persisted = prefs.persistIfUnchanged(expected) {
            pending = buildPendingAck(
                entry,
                outcome,
                resultCode,
                confirmedInverted,
                confirmedGeneration = null,
                now,
            ).also(journal::save)
        }
        if (!persisted) {
            persistStableLocalOverrideAndDeliver(
                entry,
                now,
                ackRemote,
                journal,
                prefs,
                physicalApplier,
            )
            return
        }
        deliverAck(requireNotNull(pending), ackRemote, journal, prefs)
    }

    /** Re-observa fuera del monitor y sólo guarda si esa preferencia sigue estable. */
    private suspend fun persistStableLocalOverrideAndDeliver(
        entry: JournalEntry,
        now: Instant,
        ackRemote: DisplayModeAckRemote,
        journal: DisplayModeRequestJournal,
        prefs: DisplayModePreferenceStore,
        physicalApplier: DisplayModePhysicalApplier,
    ) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val snapshot = prefs.snapshot()
            val confirmed = physicalApplier.observeConfirmedMode() ?: return
            var pending: JournalEntry? = null
            val persisted = prefs.persistIfUnchanged(snapshot) {
                pending = buildPendingAck(
                    entry,
                    DisplayModeAckOutcome.REJECTED,
                    DisplayModeAckResultCode.LOCAL_OVERRIDE,
                    confirmed,
                    confirmedGeneration = snapshot.generation,
                    now,
                ).also(journal::save)
            }
            if (persisted) {
                deliverAck(requireNotNull(pending), ackRemote, journal, prefs)
                return
            }
        }
    }

    private suspend fun prepareAndDeliverAck(
        entry: JournalEntry,
        outcome: DisplayModeAckOutcome,
        resultCode: DisplayModeAckResultCode?,
        confirmedInverted: Boolean,
        confirmedGeneration: Long?,
        now: Instant,
        ackRemote: DisplayModeAckRemote,
        journal: DisplayModeRequestJournal,
        prefs: DisplayModePreferenceStore,
    ) {
        val pending = buildPendingAck(
            entry,
            outcome,
            resultCode,
            confirmedInverted,
            confirmedGeneration,
            now,
        )
        journal.save(pending)
        deliverAck(pending, ackRemote, journal, prefs)
    }

    private fun buildPendingAck(
        entry: JournalEntry,
        outcome: DisplayModeAckOutcome,
        resultCode: DisplayModeAckResultCode?,
        confirmedInverted: Boolean,
        confirmedGeneration: Long?,
        now: Instant,
    ): JournalEntry = entry.copy(
            appliedLocally = outcome == DisplayModeAckOutcome.APPLIED,
            ackPending = true,
            appliedAt = if (outcome == DisplayModeAckOutcome.APPLIED) now else entry.appliedAt,
            ackPreparedAt = now,
            ackOutcome = outcome,
            ackResultCode = resultCode,
            confirmedInverted = confirmedInverted,
            confirmedLocalGeneration = confirmedGeneration,
        )

    private suspend fun deliverAck(
        entry: JournalEntry,
        ackRemote: DisplayModeAckRemote,
        journal: DisplayModeRequestJournal,
        prefs: DisplayModePreferenceStore,
    ) {
        val confirmed = entry.confirmedInverted ?: return
        val acknowledgement = when (entry.ackOutcome) {
            DisplayModeAckOutcome.APPLIED -> DisplayModeAcknowledgement.Applied
            DisplayModeAckOutcome.REJECTED -> {
                val code = entry.ackResultCode ?: return
                DisplayModeAcknowledgement.Rejected(code)
            }

            null -> return
        }
        val result = ackRemote.acknowledge(
            venueId = entry.venueId,
            terminalId = entry.terminalId,
            requestId = entry.requestId,
            confirmedInverted = confirmed,
            acknowledgement = acknowledgement,
        )
        val delivered = result is DisplayModeRemoteOutcome.Success ||
            result is DisplayModeRemoteOutcome.Rejected &&
            result.status == 409 && result.code == DEVICE_REQUEST_SUPERSEDED
        if (!delivered) return

        if (
            entry.ackResultCode == DisplayModeAckResultCode.LOCAL_OVERRIDE &&
            entry.confirmedLocalGeneration != null
        ) {
            prefs.markSynced(entry.confirmedLocalGeneration, confirmed)
        }
        journal.clear(entry.venueId, entry.deviceId, entry.requestId)
    }

    private companion object {
        const val DEVICE_REQUEST_SUPERSEDED = "DEVICE_REQUEST_SUPERSEDED"
    }
}

private class RepositoryDeviceCapabilityRemote(
    private val repository: DisplayModeRemoteRepository,
) : DeviceCapabilityRemote {
    override suspend fun reportCapabilities(
        venueId: String,
        snapshot: DisplayCapabilitySnapshot,
    ): DisplayModeRemoteOutcome<Unit> = repository.reportCapabilities(venueId, snapshot)

    override suspend fun fetchDisplayModeRequest(
        venueId: String,
    ): DisplayModeRemoteOutcome<DisplayModeRequestBinding> =
        repository.fetchDisplayModeRequest(venueId)
}

private class SecureStorageDeviceSessionProvider(
    private val secureStorage: SecureStorage,
) : DeviceSessionProvider {
    override fun currentSession(): DeviceSession? {
        if (!secureStorage.isLoggedIn) return null
        val venueId = secureStorage.venueId?.takeIf(String::isNotBlank) ?: return null
        return DeviceSession(venueId)
    }
}

/**
 * Un solo loop de proceso para anunciar capacidades y consultar intenciones.
 *
 * Los eventos sólo conflan un wakeup; nunca crean loops paralelos. La corrida
 * de red vive en un único child cancelable para que background, logout, cambio
 * de venue o hardware nuevo puedan estacionarla sin bloquear al caller.
 */
@Singleton
class DeviceCapabilitySyncCoordinator internal constructor(
    private val remote: DeviceCapabilityRemote,
    private val processor: DisplayModeRequestProcessor,
    private val snapshots: StateFlow<DisplayCapabilitySnapshot?>,
    private val sessionProvider: DeviceSessionProvider,
    private val connected: StateFlow<Boolean>,
    private val serverReachable: StateFlow<Boolean>,
    private val scope: CoroutineScope,
    private val clock: CoordinatorClock,
    private val jitter: PollJitter,
) : DefaultLifecycleObserver {

    @Inject
    constructor(
        repository: DisplayModeRemoteRepository,
        processor: DisplayModeRequestProcessor,
        customerDisplayState: CustomerDisplayState,
        secureStorage: SecureStorage,
        connectivityMonitor: ConnectivityMonitor,
    ) : this(
        remote = RepositoryDeviceCapabilityRemote(repository),
        processor = processor,
        snapshots = customerDisplayState.capabilities,
        sessionProvider = SecureStorageDeviceSessionProvider(secureStorage),
        connected = connectivityMonitor.isConnected,
        serverReachable = connectivityMonitor.isServerReachable,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        clock = CoordinatorClock { System.nanoTime() / 1_000_000L },
        jitter = PollJitter { baseMs, maximumMs ->
            Random.Default.nextLong(from = baseMs, until = maximumMs + 1L)
        },
    )

    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val activeTickLock = Any()
    private var activeTick: Job? = null

    @Volatile
    private var foreground = false

    /** Un 401 estaciona ESA sesión hasta que AppState anuncie un cambio. */
    private val invalidSessionVenue = AtomicReference<String?>(null)

    /** Sólo el loop escribe esto; no necesita otra fuente observable. */
    private var lastReported: ReportedCapabilities? = null

    init {
        // También se consume el valor inicial. Un StateFlow puede cambiar entre
        // construir el singleton y arrancar esta coroutine; hacer drop(1)
        // descartaría precisamente esa observación/recuperación más reciente.
        // Los wakes iniciales son inofensivos porque el canal es conflated.
        val snapshotAtConstruction = snapshots.value
        scope.launch {
            var previous = snapshotAtConstruction
            snapshots.collect { current ->
                if (current != previous) signalWakeup()
                previous = current
            }
        }
        // Tanto pérdida como recuperación cancelan el tick vigente. En pérdida
        // evita la siguiente llamada; en recuperación evita esperar el backoff.
        val onlineAtConstruction = connected.value && serverReachable.value
        scope.launch {
            var previous = onlineAtConstruction
            combine(connected, serverReachable) { network, server -> network && server }
                .distinctUntilChanged()
                .collect { current ->
                    if (current != previous) signalWakeup()
                    previous = current
                }
        }
        scope.launch { schedulerLoop() }
    }

    override fun onStart(owner: LifecycleOwner) {
        foreground = true
        signalWakeup()
    }

    override fun onStop(owner: LifecycleOwner) {
        foreground = false
        signalWakeup()
    }

    /**
     * Fire-and-forget: login, logout exitoso, venue switch e invalidación sólo
     * despiertan. Nunca esperan red en el hilo que maneja navegación/sesión.
     */
    fun onSessionChanged() {
        invalidSessionVenue.set(null)
        signalWakeup()
    }

    private fun signalWakeup() {
        synchronized(activeTickLock) { activeTick?.cancel() }
        wakeups.trySend(Unit)
    }

    private suspend fun schedulerLoop() {
        wakeups.receive()
        var retryAttempt = 0
        while (currentCoroutineContext().isActive) {
            val result = runOneTick()
            val waitMs = when (result) {
                TickResult.Complete -> {
                    retryAttempt = 0
                    minOf(foregroundPollDelayMs(), capabilityReportDelayMs())
                }

                TickResult.Ineligible,
                TickResult.Interrupted,
                -> {
                    retryAttempt = 0
                    MAX_FOREGROUND_WAIT_MS
                }

                TickResult.Retry -> offlineBackoffMs(retryAttempt++)
            }

            val wasWoken = select {
                wakeups.onReceive { true }
                onTimeout(waitMs) { false }
            }
            if (wasWoken) retryAttempt = 0
        }
    }

    private suspend fun runOneTick(): TickResult {
        val deferred: Deferred<TickResult> = scope.async(start = CoroutineStart.LAZY) {
            executeTick()
        }
        synchronized(activeTickLock) { activeTick = deferred }
        deferred.start()
        return try {
            deferred.await()
        } catch (cancellation: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancellation
            TickResult.Interrupted
        } finally {
            synchronized(activeTickLock) {
                if (activeTick === deferred) activeTick = null
            }
        }
    }

    private suspend fun executeTick(): TickResult {
        if (!foreground) return TickResult.Ineligible
        val session = sessionProvider.currentSession() ?: return TickResult.Ineligible
        val snapshot = snapshots.value ?: return TickResult.Ineligible
        if (invalidSessionVenue.get() == session.venueId) return TickResult.Ineligible
        if (!connected.value || !serverReachable.value) return TickResult.Retry

        var shouldRetrySoon = false
        // GET/processor va PRIMERO. Un PUT fallido puede hacer que el
        // ConnectivityInterceptor cambie serverReachable=false y despierte el
        // loop cancelando este tick. Si el PUT fuera primero, esa cancelación
        // podría impedir leer/aplicar una intención ya creada.
        val pollOutcome = remote.fetchDisplayModeRequest(session.venueId)
        currentCoroutineContext().ensureActive()
        when (pollOutcome) {
            is DisplayModeRemoteOutcome.Success,
            is DisplayModeRemoteOutcome.NoRequest,
            -> {
                try {
                    processor.process(session.venueId, pollOutcome)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    shouldRetrySoon = true
                }
            }

            DisplayModeRemoteOutcome.SessionInvalid -> {
                parkInvalidSession(session)
                return TickResult.Ineligible
            }

            is DisplayModeRemoteOutcome.Rejected,
            is DisplayModeRemoteOutcome.Retryable,
            -> shouldRetrySoon = true
        }

        // El reporte sigue siendo independiente y retryable, pero nunca está
        // delante del camino de control GET → processor.
        currentCoroutineContext().ensureActive()
        if (capabilityReportIsDue(session, snapshot)) {
            when (remote.reportCapabilities(session.venueId, snapshot)) {
                is DisplayModeRemoteOutcome.Success -> {
                    currentCoroutineContext().ensureActive()
                    lastReported = ReportedCapabilities(
                        venueId = session.venueId,
                        snapshot = snapshot,
                        atMs = clock.nowMillis(),
                    )
                }

                DisplayModeRemoteOutcome.SessionInvalid -> {
                    parkInvalidSession(session)
                    return TickResult.Ineligible
                }

                is DisplayModeRemoteOutcome.NoRequest,
                is DisplayModeRemoteOutcome.Rejected,
                is DisplayModeRemoteOutcome.Retryable,
                -> shouldRetrySoon = true
            }
        }

        return if (shouldRetrySoon) TickResult.Retry else TickResult.Complete
    }

    private fun capabilityReportIsDue(
        session: DeviceSession,
        snapshot: DisplayCapabilitySnapshot,
    ): Boolean {
        val previous = lastReported ?: return true
        val now = clock.nowMillis()
        return previous.venueId != session.venueId ||
            previous.snapshot != snapshot ||
            now < previous.atMs ||
            now - previous.atMs >= CAPABILITY_REPORT_INTERVAL_MS
    }

    private fun parkInvalidSession(session: DeviceSession) {
        // Una respuesta tardía de un venue anterior no estaciona el nuevo.
        if (sessionProvider.currentSession() == session) {
            invalidSessionVenue.set(session.venueId)
        }
    }

    private fun foregroundPollDelayMs(): Long =
        jitter.nextDelayMillis(BASE_FOREGROUND_WAIT_MS, MAX_FOREGROUND_WAIT_MS)
            .coerceIn(BASE_FOREGROUND_WAIT_MS, MAX_FOREGROUND_WAIT_MS)

    /** No deja que el jitter empuje el refresh periódico más allá de 24 h. */
    private fun capabilityReportDelayMs(): Long {
        val previous = lastReported ?: return 0L
        val session = sessionProvider.currentSession() ?: return MAX_FOREGROUND_WAIT_MS
        val snapshot = snapshots.value ?: return MAX_FOREGROUND_WAIT_MS
        if (previous.venueId != session.venueId || previous.snapshot != snapshot) return 0L
        val elapsed = clock.nowMillis() - previous.atMs
        if (elapsed < 0L) return 0L
        return (CAPABILITY_REPORT_INTERVAL_MS - elapsed).coerceAtLeast(0L)
    }

    private data class ReportedCapabilities(
        val venueId: String,
        val snapshot: DisplayCapabilitySnapshot,
        val atMs: Long,
    )

    private enum class TickResult { Complete, Retry, Ineligible, Interrupted }

    companion object {
        internal const val CAPABILITY_REPORT_INTERVAL_MS = 24L * 60L * 60L * 1_000L
        private const val BASE_FOREGROUND_WAIT_MS = 15_000L
        private const val MAX_FOREGROUND_WAIT_MS = 20_000L
        private const val INITIAL_BACKOFF_MS = 1_000L

        internal fun offlineBackoffMs(attempt: Int): Long {
            val shift = attempt.coerceIn(0, 20)
            val candidate = INITIAL_BACKOFF_MS * (1L shl shift)
            return candidate.coerceAtMost(MAX_FOREGROUND_WAIT_MS)
        }
    }
}
