package com.avoqado.pos.core.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `roleDisplayName` es la ÚNICA traducción de rol a español de la app. Lo que
 * más importa aquí no son los aciertos, es el null: el bloque de identidad de
 * "Más" existe para contestar "¿con qué rol entré?", así que devolver un rol
 * inventado cuando no consta sería exactamente el bug que la pantalla arregla.
 */
class RoleDisplayNameTest {

    @Test
    fun `traduce los roles conocidos al español`() {
        assertEquals("Super Admin", roleDisplayName("SUPERADMIN"))
        assertEquals("Propietario", roleDisplayName("OWNER"))
        assertEquals("Administrador", roleDisplayName("ADMIN"))
        assertEquals("Gerente", roleDisplayName("MANAGER"))
        assertEquals("Cajero", roleDisplayName("CASHIER"))
        assertEquals("Mesero", roleDisplayName("WAITER"))
        assertEquals("Cocina", roleDisplayName("KITCHEN"))
        assertEquals("Anfitrion", roleDisplayName("HOST"))
        assertEquals("Observador", roleDisplayName("VIEWER"))
        assertEquals("Staff", roleDisplayName("STAFF"))
    }

    @Test
    fun `no distingue mayusculas ni espacios sobrantes`() {
        assertEquals("Cajero", roleDisplayName("cashier"))
        assertEquals("Gerente", roleDisplayName("  MANAGER  "))
    }

    @Test
    fun `un rol nulo NO se traduce a un rol inventado`() {
        assertNull(roleDisplayName(null))
    }

    @Test
    fun `un rol vacio o en blanco tampoco se traduce`() {
        assertNull(roleDisplayName(""))
        assertNull(roleDisplayName("   "))
    }

    @Test
    fun `un rol nuevo del server se muestra tal cual en vez de desaparecer`() {
        assertEquals("INTERN", roleDisplayName("INTERN"))
    }

    @Test
    fun `el switcher de sucursal conserva su Staff historico`() {
        // StoredVenue.displayRole delega en roleDisplayName pero NO puede
        // devolver null: VenueSwitcherSheet pinta ese texto sin fallback.
        val sinRol = StoredVenue(id = "1", name = "Test Venue", role = null)
        assertEquals("Staff", sinRol.displayRole)
    }
}
