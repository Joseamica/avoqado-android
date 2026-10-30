package com.avoqado.pos.reservations.presentation.detail

import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.domain.ReservationAction
import com.avoqado.pos.reservations.domain.ReservationStateMachine
import com.avoqado.pos.reservations.domain.ReservationsCapability
import com.avoqado.pos.reservations.domain.UndoNoShowTexts

data class ReservationDetailUiState(
    val isLoading: Boolean = true,
    val reservation: Reservation? = null,
    val capability: ReservationsCapability = ReservationsCapability(false, false, false, false),
    val pendingAction: ReservationAction? = null,
    val error: String? = null,
    // Aviso de "se guardó, se enviará al reconectar". Separado de `error` a
    // propósito: pintarlo en rojo haría que el mesero reintente y duplique.
    val queuedMessage: String? = null,
    val justCompletedAction: ReservationAction? = null,
    /** D4: «Marcar resuelto» del pendiente en camino (un solo POST). */
    val resolvingPending: Boolean = false,
    /** Palomita que no sale de un [ReservationAction] («Pendiente resuelto»). */
    val notice: String? = null,
    /** «Marcar resuelto» que no salió: sin red en ámbar, un rechazo con su texto humanizado (nunca crudo). */
    val resolveNotice: com.avoqado.pos.reservations.presentation.undo.UndoNoShowFlow.Notice? = null,
) {
    /** D4: «Sí llegó» sólo sobre un «No se presentó» y con permiso de editar reservaciones. */
    val canUndoNoShow: Boolean get() = reservation?.status == ReservationStatus.NO_SHOW && capability.canUpdate

    /** La franja ámbar: lo que la corrección dejó pendiente y nadie ha marcado resuelto. */
    val openPendingTexts: List<String>
        get() = reservation?.noShowCorrection?.takeIf { it.isOpen }?.pending?.map(UndoNoShowTexts::pending).orEmpty()

    fun isAllowed(action: ReservationAction): Boolean {
        val r = reservation ?: return false
        if (!ReservationStateMachine.canExecute(r.status, action)) return false
        return when (action) {
            ReservationAction.CANCEL -> capability.canCancel
            ReservationAction.RESCHEDULE -> capability.canUpdate
            else -> capability.canUpdate
        }
    }
}
