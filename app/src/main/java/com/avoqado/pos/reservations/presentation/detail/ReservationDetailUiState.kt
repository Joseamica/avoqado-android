package com.avoqado.pos.reservations.presentation.detail

import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.domain.ReservationAction
import com.avoqado.pos.reservations.domain.ReservationStateMachine
import com.avoqado.pos.reservations.domain.ReservationsCapability

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
) {
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
