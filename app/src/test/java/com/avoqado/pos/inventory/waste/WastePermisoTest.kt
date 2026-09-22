package com.avoqado.pos.inventory.waste

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `inventory:log-waste` — el permiso NUEVO y limitado que el dueño enciende.
 *
 * 🔴 SIN respaldo por rol, a propósito (spec §5). Es la lección que costó un
 * reembolso sin PIN en la D3 el 2026-08-17: un gate por ROL no es un espejo del
 * permiso, es una SEGUNDA fuente de verdad que se desincroniza sola. Aquí el
 * riesgo va en la otra dirección —negar de más— y eso es lo correcto para un
 * permiso que de fábrica tienen sólo tres roles.
 */
class WastePermisoTest {

    private val secureStorage = mockk<SecureStorage>()
    private val roleManager = RoleManager(secureStorage)

    private fun conPermisos(vararg permisos: String, rol: String = "WAITER"): RoleManager {
        every { secureStorage.venuePermissions } returns permisos.toList()
        every { secureStorage.userRole } returns rol
        return roleManager
    }

    /**
     * 🔴 Con la lista vacía el gate queda APAGADO, aunque quien mire sea el dueño.
     * Adivinar por rol prometería una autorización que el servidor no dio.
     */
    @Test
    fun `sin la lista de permisos el gate queda apagado, sin adivinar por rol`() {
        assertFalse(conPermisos(rol = "OWNER").canLogWaste)
        assertFalse(conPermisos(rol = "MANAGER").canLogWaste)
        assertFalse(conPermisos(rol = "SUPERADMIN").canLogWaste)
    }

    @Test
    fun `con el permiso exacto queda encendido`() {
        assertTrue(conPermisos("inventory:log-waste").canLogWaste)
    }

    /** Un comodín de recurso o global también lo concede, como en el servidor. */
    @Test
    fun `los comodines del servidor tambien lo conceden`() {
        assertTrue(conPermisos("inventory:*").canLogWaste)
        assertTrue(conPermisos("*:*").canLogWaste)
    }

    /**
     * 🔴 `inventory:adjust` NO da merma: son permisos distintos y el servidor los
     * separa a propósito (spec D1: «`inventory:adjust` no cambia»). Colgar la merma
     * de `adjust` se la daría a quien puede corregir existencias pero a quien el
     * dueño no quiso dejarle declarar pérdidas.
     */
    @Test
    fun `inventory adjust NO da merma, son permisos distintos`() {
        assertFalse(conPermisos("inventory:adjust", "inventory:read", rol = "MANAGER").canLogWaste)
    }

    /** Los tres roles de fábrica del servidor: mesero, cajero y gerente. */
    @Test
    fun `los tres roles de fabrica lo traen cuando el servidor se los manda`() {
        listOf("WAITER", "CASHIER", "MANAGER").forEach { rol ->
            assertTrue("$rol debería poder registrar merma", conPermisos("inventory:log-waste", rol = rol).canLogWaste)
        }
    }
}
