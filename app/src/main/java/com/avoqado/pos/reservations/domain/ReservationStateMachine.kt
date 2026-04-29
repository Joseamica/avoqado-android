package com.avoqado.pos.reservations.domain

import com.avoqado.pos.reservations.data.model.ReservationStatus
import com.avoqado.pos.reservations.data.model.ReservationStatus.*

enum class ReservationAction { CONFIRM, CHECK_IN, COMPLETE, NO_SHOW, CANCEL, RESCHEDULE }

object ReservationStateMachine {

    private val table: Map<ReservationAction, Set<ReservationStatus>> = mapOf(
        ReservationAction.CONFIRM to setOf(PENDING),
        ReservationAction.CHECK_IN to setOf(PENDING, CONFIRMED),
        ReservationAction.COMPLETE to setOf(CHECKED_IN),
        ReservationAction.NO_SHOW to setOf(PENDING, CONFIRMED),
        ReservationAction.CANCEL to setOf(PENDING, CONFIRMED, CHECKED_IN),
        ReservationAction.RESCHEDULE to setOf(PENDING, CONFIRMED),
    )

    fun canExecute(current: ReservationStatus, action: ReservationAction): Boolean =
        current in (table[action] ?: emptySet())

    fun predictedNextStatus(current: ReservationStatus, action: ReservationAction): ReservationStatus = when (action) {
        ReservationAction.CONFIRM -> CONFIRMED
        ReservationAction.CHECK_IN -> CHECKED_IN
        ReservationAction.COMPLETE -> COMPLETED
        ReservationAction.CANCEL -> CANCELLED
        ReservationAction.NO_SHOW -> NO_SHOW
        ReservationAction.RESCHEDULE -> current  // reschedule keeps status
    }
}
