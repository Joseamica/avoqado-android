package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.Serializable

/**
 * D4 · «Sí llegó»: lo que el SERVIDOR revertiría de un «No asistió» (GET `…/{id}/no-show/undo`).
 * Los montos y el paquete llegaron después: son opcionales y, si faltan, la confirmación los dice sin cifra.
 */
@Serializable
data class UndoNoShowPlan(
    val creditToReconsume: Int = 0,
    val clearFee: Boolean = false,
    val restoreDeposit: Boolean = false,
    /** CLASS_UNPAID · CREDIT_NOT_RECONSUMED · FEE_CAPTURED · KIOSK_TOO_OLD · KIOSK_UNVERIFIED */
    val pending: List<String> = emptyList(),
    val feeAmount: Double? = null,
    val depositAmount: Double? = null,
    val packName: String? = null,
)

/** La corrección tal como la devuelve `POST …/no-show/undo`. */
@Serializable
data class NoShowCorrectionRef(
    val id: String,
    val pending: List<String> = emptyList(),
    val resolvedAt: String? = null,
)

/** `POST …/{id}/no-show/undo`. `ALREADY_UNDONE` = otra persona (u otro toque) ya la había corregido. */
@Serializable
data class UndoNoShowResult(
    val outcome: String,
    val reservation: Reservation,
    val plan: UndoNoShowPlan = UndoNoShowPlan(),
    val correction: NoShowCorrectionRef,
) {
    val alreadyUndone: Boolean get() = outcome == "ALREADY_UNDONE"
}

/** La corrección en el detalle de la reserva (`noShowCorrection`, null si nunca se corrigió). */
@Serializable
data class NoShowCorrection(
    val pending: List<String> = emptyList(),
    val resolvedAt: String? = null,
    /** KIOSK · DASHBOARD · POS_ANDROID · POS_IOS · MCP … */
    val source: String? = null,
    val createdAt: String? = null,
    /** Nombre corto de quien la corrigió; null cuando la hizo el kiosco. */
    val actorName: String? = null,
) {
    /** Una corrección limpia NO es pendiente: sólo la que dejó algo por resolver y nadie lo ha marcado. */
    val isOpen: Boolean get() = pending.isNotEmpty() && resolvedAt == null
}

/** La reserva de una falta vino de un pase (TotalPass/Wellhub): se corrige desde su app. */
@Serializable
data class AggregatorBookingLite(
    val id: String,
    val provider: String? = null,
)
