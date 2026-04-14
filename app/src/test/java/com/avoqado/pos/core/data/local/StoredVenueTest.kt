package com.avoqado.pos.core.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class StoredVenueTest {

    private fun venueWithRole(role: String?) = StoredVenue(
        id = "1",
        name = "Test Venue",
        role = role,
    )

    @Test
    fun `SUPERADMIN displays as Super Admin`() {
        assertEquals("Super Admin", venueWithRole("SUPERADMIN").displayRole)
    }

    @Test
    fun `OWNER displays as Propietario`() {
        assertEquals("Propietario", venueWithRole("OWNER").displayRole)
    }

    @Test
    fun `ADMIN displays as Administrador`() {
        assertEquals("Administrador", venueWithRole("ADMIN").displayRole)
    }

    @Test
    fun `MANAGER displays as Gerente`() {
        assertEquals("Gerente", venueWithRole("MANAGER").displayRole)
    }

    @Test
    fun `CASHIER displays as Cajero`() {
        assertEquals("Cajero", venueWithRole("CASHIER").displayRole)
    }

    @Test
    fun `WAITER displays as Mesero`() {
        assertEquals("Mesero", venueWithRole("WAITER").displayRole)
    }

    @Test
    fun `KITCHEN displays as Cocina`() {
        assertEquals("Cocina", venueWithRole("KITCHEN").displayRole)
    }

    @Test
    fun `HOST displays as Anfitrion`() {
        assertEquals("Anfitrion", venueWithRole("HOST").displayRole)
    }

    @Test
    fun `VIEWER displays as Observador`() {
        assertEquals("Observador", venueWithRole("VIEWER").displayRole)
    }

    @Test
    fun `STAFF displays as Staff`() {
        assertEquals("Staff", venueWithRole("STAFF").displayRole)
    }

    @Test
    fun `null role defaults to Staff`() {
        assertEquals("Staff", venueWithRole(null).displayRole)
    }

    @Test
    fun `lowercase role is matched correctly`() {
        assertEquals("Gerente", venueWithRole("manager").displayRole)
    }

    @Test
    fun `unknown role displays raw value`() {
        assertEquals("INTERN", venueWithRole("INTERN").displayRole)
    }
}
