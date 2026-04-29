package com.avoqado.pos.reservations.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReservationsCapabilityTest {

    @Test
    fun `staff with all reservation perms gets all capabilities`() {
        val cap = ReservationsCapability.fromPermissions(
            listOf("reservations:read", "reservations:create", "reservations:update", "reservations:cancel")
        )
        assertTrue(cap.canRead); assertTrue(cap.canCreate)
        assertTrue(cap.canUpdate); assertTrue(cap.canCancel)
    }

    @Test
    fun `staff with no perms gets none`() {
        val cap = ReservationsCapability.fromPermissions(emptyList())
        assertFalse(cap.canRead); assertFalse(cap.canCreate)
        assertFalse(cap.canUpdate); assertFalse(cap.canCancel)
    }

    @Test
    fun `wildcard reservations colon star grants all`() {
        val cap = ReservationsCapability.fromPermissions(listOf("reservations:*"))
        assertTrue(cap.canRead); assertTrue(cap.canCreate)
        assertTrue(cap.canUpdate); assertTrue(cap.canCancel)
    }

    @Test
    fun `superadmin star grants all`() {
        val cap = ReservationsCapability.fromPermissions(listOf("*"))
        assertTrue(cap.canRead); assertTrue(cap.canCreate)
        assertTrue(cap.canUpdate); assertTrue(cap.canCancel)
    }

    @Test
    fun `read-only staff can read but not mutate`() {
        val cap = ReservationsCapability.fromPermissions(listOf("reservations:read"))
        assertTrue(cap.canRead)
        assertFalse(cap.canCreate); assertFalse(cap.canUpdate); assertFalse(cap.canCancel)
    }
}
