package com.avoqado.pos.reservations.domain

import com.avoqado.pos.reservations.data.model.ReservationStatus.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationStateMachineTest {

    @Test
    fun `confirm allowed only from PENDING`() {
        assertTrue(ReservationStateMachine.canExecute(PENDING, ReservationAction.CONFIRM))
        assertFalse(ReservationStateMachine.canExecute(CONFIRMED, ReservationAction.CONFIRM))
        assertFalse(ReservationStateMachine.canExecute(CANCELLED, ReservationAction.CONFIRM))
    }

    @Test
    fun `check-in allowed from PENDING or CONFIRMED`() {
        assertTrue(ReservationStateMachine.canExecute(PENDING, ReservationAction.CHECK_IN))
        assertTrue(ReservationStateMachine.canExecute(CONFIRMED, ReservationAction.CHECK_IN))
        assertFalse(ReservationStateMachine.canExecute(CHECKED_IN, ReservationAction.CHECK_IN))
        assertFalse(ReservationStateMachine.canExecute(COMPLETED, ReservationAction.CHECK_IN))
    }

    @Test
    fun `complete allowed only from CHECKED_IN`() {
        assertTrue(ReservationStateMachine.canExecute(CHECKED_IN, ReservationAction.COMPLETE))
        assertFalse(ReservationStateMachine.canExecute(CONFIRMED, ReservationAction.COMPLETE))
    }

    @Test
    fun `no-show allowed from PENDING or CONFIRMED`() {
        assertTrue(ReservationStateMachine.canExecute(PENDING, ReservationAction.NO_SHOW))
        assertTrue(ReservationStateMachine.canExecute(CONFIRMED, ReservationAction.NO_SHOW))
        assertFalse(ReservationStateMachine.canExecute(CHECKED_IN, ReservationAction.NO_SHOW))
    }

    @Test
    fun `cancel allowed from any active status`() {
        assertTrue(ReservationStateMachine.canExecute(PENDING, ReservationAction.CANCEL))
        assertTrue(ReservationStateMachine.canExecute(CONFIRMED, ReservationAction.CANCEL))
        assertTrue(ReservationStateMachine.canExecute(CHECKED_IN, ReservationAction.CANCEL))
        assertFalse(ReservationStateMachine.canExecute(COMPLETED, ReservationAction.CANCEL))
    }

    @Test
    fun `reschedule allowed from active non-checked-in`() {
        assertTrue(ReservationStateMachine.canExecute(PENDING, ReservationAction.RESCHEDULE))
        assertTrue(ReservationStateMachine.canExecute(CONFIRMED, ReservationAction.RESCHEDULE))
        assertFalse(ReservationStateMachine.canExecute(CHECKED_IN, ReservationAction.RESCHEDULE))
        assertFalse(ReservationStateMachine.canExecute(CANCELLED, ReservationAction.RESCHEDULE))
    }

    @Test
    fun `predicted next status follows happy path`() {
        assertEquals(CONFIRMED, ReservationStateMachine.predictedNextStatus(PENDING, ReservationAction.CONFIRM))
        assertEquals(CHECKED_IN, ReservationStateMachine.predictedNextStatus(CONFIRMED, ReservationAction.CHECK_IN))
        assertEquals(COMPLETED, ReservationStateMachine.predictedNextStatus(CHECKED_IN, ReservationAction.COMPLETE))
        assertEquals(CANCELLED, ReservationStateMachine.predictedNextStatus(PENDING, ReservationAction.CANCEL))
        assertEquals(NO_SHOW, ReservationStateMachine.predictedNextStatus(CONFIRMED, ReservationAction.NO_SHOW))
    }
}
