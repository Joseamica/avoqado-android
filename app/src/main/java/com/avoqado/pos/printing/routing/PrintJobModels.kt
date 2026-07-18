package com.avoqado.pos.printing.routing

import kotlinx.serialization.Serializable

/**
 * PRINT_STATIONS — request DTOs for the client → server print-job replica + heartbeat.
 *
 * The gateway replicates its DURABLE local outbox to the server (visibility / audit /
 * alerts) — the server is NOT on the critical path. Dedupe is tenant-scoped by
 * (venueId, eventId, reason, seq) on the server; `id` is created by the gateway.
 */
@Serializable
data class PrintJobDto(
    /** Globally-unique id created by the gateway (not the server). */
    val id: String,
    /** Causal id of the mutation → the real dedupe key. */
    val eventId: String,
    val reason: String, // ORIGINAL | ADDITION | CANCEL | REPRINT
    val seq: Int = 1,
    val type: String, // KITCHEN_TICKET | RECEIPT | EXPO | TEST | CASH_CLOSE
    val status: String, // QUEUED | SENT | DONE | UNCERTAIN | OPERATOR_CONFIRMED | FAILED
    val stationId: String? = null,
    val printerId: String? = null,
    val gatewayTerminalId: String? = null,
    val originTerminalId: String? = null,
    val orderId: String? = null,
    val orderItemIds: List<String> = emptyList(),
    val attempts: Int = 0,
    val error: String? = null,
)

@Serializable
data class SyncPrintJobsRequest(
    /** This device's id — the server accepts the replica ONLY from the venue's designated gateway. */
    val terminalId: String,
    val jobs: List<PrintJobDto>,
)

@Serializable
data class PrinterStatusReport(
    val printerId: String,
    val status: String, // ONLINE | OFFLINE | PAPER_LOW | PAPER_OUT | ERROR
)

@Serializable
data class GatewayHeartbeatRequest(
    val terminalId: String,
    val address: String? = null,
    val printers: List<PrinterStatusReport> = emptyList(),
)
