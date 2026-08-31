package com.avoqado.pos.customerdisplay

import java.time.Instant

/** Solicitud server-owned. El valor deseado se aplica como set, nunca como toggle. */
data class RemoteDisplayModeRequest(
    val requestId: String,
    val desiredInverted: Boolean,
    val status: String = STATUS_PENDING,
    val expiresAt: Instant,
    val requestedAt: Instant? = null,
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
    }
}

/** Los únicos resultados de la decisión pura; el coordinador ejecuta el efecto. */
enum class RemoteDisplayIntentDecision {
    IGNORE_EXPIRED,
    IGNORE_ALREADY_ACKED,
    ACK_JOURNALED_APPLY,
    APPLY_AND_ACK,
    REJECT_UNSUPPORTED,
    REJECT_LOCAL_OVERRIDE,
}

/** Payload de ACK que debe sobrevivir a pérdida de red o muerte del proceso. */
enum class DisplayModeAckOutcome {
    APPLIED,
    REJECTED,
}

enum class DisplayModeAckResultCode {
    DISPLAY_NOT_PRESENT,
    DISPLAY_NOT_INVERTIBLE,
    APPLY_FAILED,
    LOCAL_OVERRIDE,
    DEVICE_RETIRED,
}

/**
 * Estado durable de una sola solicitud por scope.
 *
 * [terminalId] viene junto con el request en el GET. Nunca se reconstruye desde
 * settings porque hacerlo podría acusar sobre otra fila después de un rebinding.
 */
data class JournalEntry(
    val venueId: String,
    val deviceId: String,
    val terminalId: String,
    val requestId: String,
    val desiredInverted: Boolean,
    val appliedLocally: Boolean,
    val ackPending: Boolean,
    val localGenerationAtJournal: Long,
    val requestExpiresAt: Instant,
    val journaledAt: Instant,
    /** Durable boundary written immediately before the first preference/effect call. */
    val applyStartedAt: Instant? = null,
    val appliedAt: Instant? = null,
    val ackPreparedAt: Instant? = null,
    val ackOutcome: DisplayModeAckOutcome? = null,
    val ackResultCode: DisplayModeAckResultCode? = null,
    val confirmedInverted: Boolean? = null,
    /** Generation that produced [confirmedInverted], for generation-safe dirty clearing. */
    val confirmedLocalGeneration: Long? = null,
)

/** The canonical identity already persisted by SyncOutbox; never derive it from terminalId. */
internal fun interface CanonicalDeviceIdProvider {
    fun currentDeviceId(): String
}

/** Alias descriptivo para call sites que prefieran evitar el nombre genérico. */
typealias DisplayModeJournalEntry = JournalEntry

sealed interface DisplayModeJournalState {
    data object Empty : DisplayModeJournalState

    data class Ready(val entry: JournalEntry) : DisplayModeJournalState

    /** Hay bytes persistidos, pero no es seguro interpretarlos ni descartarlos. */
    data object Unreadable : DisplayModeJournalState
}

interface DisplayModeRequestJournal {
    fun readState(venueId: String, deviceId: String): DisplayModeJournalState

    fun load(venueId: String, deviceId: String): JournalEntry? =
        (readState(venueId, deviceId) as? DisplayModeJournalState.Ready)?.entry

    fun save(entry: JournalEntry)

    fun clear(venueId: String, deviceId: String, requestId: String)

    fun hasInFlight(venueId: String, deviceId: String): Boolean =
        readState(venueId, deviceId) !is DisplayModeJournalState.Empty
}

/**
 * Decide sin tocar Android, storage o red.
 *
 * Un ACK durable gana incluso después del TTL: la aplicación ocurrió a tiempo
 * y el servidor necesita conocer el estado físico (`ACK_AFTER_EXPIRY`). Para
 * requests aún no aplicadas, el vencimiento sí es una barrera estricta.
 */
internal fun decideRemoteIntent(
    request: RemoteDisplayModeRequest,
    capability: DisplayCapabilitySnapshot,
    journal: JournalEntry?,
    currentLocalGeneration: Long,
    now: Instant,
): RemoteDisplayIntentDecision {
    if (journal?.ackPending == true) {
        return RemoteDisplayIntentDecision.ACK_JOURNALED_APPLY
    }
    if (request.status != RemoteDisplayModeRequest.STATUS_PENDING) {
        return RemoteDisplayIntentDecision.IGNORE_ALREADY_ACKED
    }
    if (!now.isBefore(request.expiresAt)) {
        return RemoteDisplayIntentDecision.IGNORE_EXPIRED
    }
    if (!capability.present || !capability.invertible) {
        return RemoteDisplayIntentDecision.REJECT_UNSUPPORTED
    }
    if (
        journal?.requestId == request.requestId &&
        currentLocalGeneration > journal.localGenerationAtJournal
    ) {
        return RemoteDisplayIntentDecision.REJECT_LOCAL_OVERRIDE
    }
    return RemoteDisplayIntentDecision.APPLY_AND_ACK
}
