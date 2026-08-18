package com.avoqado.pos.navigation

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PermisosRealesDelServer
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.settings.domain.PosMode
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 🔴 QUIÉN VE LA PESTAÑA DE INVENTARIO — CON LOS PERMISOS REALES DEL SERVER.
 *
 * `canAccessInventory` gobierna TRES cosas: la pestaña INVENTARIO de la barra de
 * abajo (`MainTabsPolicy`), las dos rutas `MainTab.INVENTORY` del `NavGraph`
 * (tablet y teléfono), y —porque `InterVenueTransfersView` vive dentro de
 * `InventoryScreen`— la única puerta a Traslados entre sucursales.
 *
 * 🔴 POR QUÉ NO SE ESPEJA DE `inventory:read`: el server EXPANDE dependencias
 * antes de mandar la lista efectiva, y `orders:create` / `orders:update`
 * arrastran `inventory:read` (`avoqado-server/src/lib/permissions.ts`). O sea que
 * la COCINA, el MESERO y el CAJERO lo reciben aunque nadie se los concedió: ese
 * permiso está ahí para que el POS pueda CONSULTAR existencias mientras se toma
 * una orden, no para abrirles el módulo de inventario.
 *
 * Espejarlo enciende una pestaña entera de navegación para tres roles. Puede que
 * sea lo correcto, pero es una DECISIÓN DE PRODUCTO —y del founder—, no una
 * traducción mecánica de un permiso. Mientras no se tome, la barra se queda como
 * estaba: MANAGER para arriba.
 *
 * Estos tests miden con el payload REAL del server, no con la cache vacía: el
 * respaldo por rol da la respuesta correcta por la razón equivocada y esconde la
 * regresión.
 */
class InventarioTabPermisoTest {

    private val secureStorage = mockk<SecureStorage>()
    private val roleManager = RoleManager(secureStorage)

    private fun conRolReal(rol: String, permisos: List<String>) {
        every { secureStorage.userRole } returns rol
        every { secureStorage.venuePermissions } returns permisos
    }

    private fun barra() = MainTabsPolicy.visibleTabs(
        reservationsEnabled = false,
        planAllowsReservations = false,
        posMode = PosMode.RETAIL,
        canAccessPOS = roleManager.canAccessPOS,
        canAccessInventory = roleManager.canAccessInventory,
        canAccessTransactions = roleManager.canAccessTransactions,
    )

    // MARK: - El permiso implícito NO enciende la pestaña

    @Test
    fun `el CAJERO recibe inventory read del server y aun asi no ve la pestana`() {
        conRolReal("CASHIER", PermisosRealesDelServer.CASHIER)

        assertTrue(
            "premisa del test: el server SÍ le manda inventory:read (por orders:update)",
            PermisosRealesDelServer.CASHIER.contains("inventory:read"),
        )
        assertFalse("la barra del cajero no puede ganar Inventario", barra().contains(MainTab.INVENTORY))
        assertFalse(roleManager.canAccessInventory)
    }

    @Test
    fun `el MESERO recibe inventory read del server y aun asi no ve la pestana`() {
        conRolReal("WAITER", PermisosRealesDelServer.WAITER)

        assertTrue(PermisosRealesDelServer.WAITER.contains("inventory:read"))
        assertFalse(barra().contains(MainTab.INVENTORY))
    }

    @Test
    fun `la COCINA recibe inventory read del server y aun asi no ve la pestana`() {
        conRolReal("KITCHEN", PermisosRealesDelServer.KITCHEN)

        assertTrue(PermisosRealesDelServer.KITCHEN.contains("inventory:read"))
        assertFalse(barra().contains(MainTab.INVENTORY))
    }

    // MARK: - Quien sí la ve, la sigue viendo

    @Test
    fun `el GERENTE ve la pestana de Inventario`() {
        // El MANAGER del server trae `inventory:read` explícito; aquí basta el rol.
        conRolReal("MANAGER", PermisosRealesDelServer.CASHIER)

        assertTrue(barra().contains(MainTab.INVENTORY))
    }

    @Test
    fun `el VIEWER no ve la pestana ni con la lista poblada`() {
        conRolReal("VIEWER", PermisosRealesDelServer.VIEWER)

        assertFalse(PermisosRealesDelServer.VIEWER.contains("inventory:read"))
        assertFalse(barra().contains(MainTab.INVENTORY))
    }
}
