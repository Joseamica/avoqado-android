package com.avoqado.pos.core.domain

import com.avoqado.pos.core.data.local.SecureStorage
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RoleManagerTest {

    private val secureStorage = mockk<SecureStorage>()
    private val roleManager = RoleManager(secureStorage)

    /**
     * 🔴 LA CACHE VACÍA ES LA RUTA DE RESPALDO, NO LA DE PRODUCCIÓN.
     *
     * Con la lista efectiva vacía —una sesión anterior al campo, o un server que
     * no la manda— los gates espejados caen al respaldo por rol. Es una rama
     * real y hay que probarla, pero **no es lo que hace el aparato el 99% del
     * tiempo**: en producción el login llena `venuePermissions` con la salida de
     * `getEffectiveRolePermissions()`.
     *
     * Por eso todo test que arranque desde aquí lleva "(respaldo por rol)" en el
     * nombre y tiene su GEMELO midiendo con `PermisosRealesDelServer`. Un test
     * que alimenta un estado que producción no produce confirma la premisa falsa
     * en vez de refutarla — fue exactamente lo que pasó con inventario: dos
     * tests en verde afirmando que el cajero y el mesero no lo veían mientras el
     * aparato se los abría.
     */
    @Before
    fun cachePermisosVacia() {
        every { secureStorage.venuePermissions } returns emptyList()
    }

    // MARK: - Role defaults

    @Test
    fun `null role defaults to VIEWER`() {
        every { secureStorage.userRole } returns null
        assertEquals("VIEWER", roleManager.role)
    }

    @Test
    fun `role is uppercased`() {
        every { secureStorage.userRole } returns "waiter"
        assertEquals("WAITER", roleManager.role)
    }

    // MARK: - VIEWER (lowest): no access to POS, inventory, transactions

    @Test
    fun `VIEWER cannot access POS`() {
        every { secureStorage.userRole } returns "VIEWER"
        assertFalse(roleManager.canAccessPOS)
    }

    @Test
    fun `VIEWER cannot access inventory`() {
        every { secureStorage.userRole } returns "VIEWER"
        assertFalse(roleManager.canAccessInventory)
    }

    @Test
    fun `VIEWER cannot access transactions`() {
        every { secureStorage.userRole } returns "VIEWER"
        assertFalse(roleManager.canAccessTransactions)
    }

    @Test
    fun `VIEWER cannot create products`() {
        every { secureStorage.userRole } returns "VIEWER"
        assertFalse(roleManager.canCreateProducts)
    }

    @Test
    fun `VIEWER cannot manage customers`() {
        every { secureStorage.userRole } returns "VIEWER"
        assertFalse(roleManager.canManageCustomers)
    }

    @Test
    fun `VIEWER cannot view customers (respaldo por rol)`() {
        every { secureStorage.userRole } returns "VIEWER"
        assertFalse(roleManager.canViewCustomers)
    }

    // MARK: - HOST: same as VIEWER

    @Test
    fun `HOST cannot access POS`() {
        every { secureStorage.userRole } returns "HOST"
        assertFalse(roleManager.canAccessPOS)
    }

    @Test
    fun `HOST cannot access inventory`() {
        every { secureStorage.userRole } returns "HOST"
        assertFalse(roleManager.canAccessInventory)
    }

    // MARK: - KITCHEN: same as VIEWER

    @Test
    fun `KITCHEN cannot access POS`() {
        every { secureStorage.userRole } returns "KITCHEN"
        assertFalse(roleManager.canAccessPOS)
    }

    // MARK: - WAITER: can access POS and view customers, nothing else

    @Test
    fun `WAITER can access POS`() {
        every { secureStorage.userRole } returns "WAITER"
        assertTrue(roleManager.canAccessPOS)
    }

    @Test
    fun `WAITER cannot access inventory`() {
        every { secureStorage.userRole } returns "WAITER"
        assertFalse(roleManager.canAccessInventory)
    }

    @Test
    fun `WAITER cannot access transactions`() {
        every { secureStorage.userRole } returns "WAITER"
        assertFalse(roleManager.canAccessTransactions)
    }

    @Test
    fun `WAITER cannot create products`() {
        every { secureStorage.userRole } returns "WAITER"
        assertFalse(roleManager.canCreateProducts)
    }

    @Test
    fun `WAITER cannot manage customers`() {
        every { secureStorage.userRole } returns "WAITER"
        assertFalse(roleManager.canManageCustomers)
    }

    @Test
    fun `WAITER can view customers (respaldo por rol)`() {
        every { secureStorage.userRole } returns "WAITER"
        assertTrue(roleManager.canViewCustomers)
    }

    // MARK: - Effective venue permissions

    @Test
    fun `credit packs stay hidden when effective permission is absent`() {
        every { secureStorage.venuePermissions } returns listOf(
            "menu:read",
            "area-tickets:issue",
        )

        assertFalse(roleManager.canReadCreditPacks)
    }

    @Test
    fun `credit packs are available when exact effective permission is present`() {
        every { secureStorage.venuePermissions } returns listOf(
            "menu:read",
            "creditPacks:read",
        )

        assertTrue(roleManager.canReadCreditPacks)
    }

    @Test
    fun `venue permission matcher accepts resource and global wildcards`() {
        every { secureStorage.venuePermissions } returns listOf("creditPacks:*")
        assertTrue(roleManager.hasVenuePermission("creditPacks:read"))

        every { secureStorage.venuePermissions } returns listOf("*:*")
        assertTrue(roleManager.hasVenuePermission("area-tickets:deliver"))
    }

    // MARK: - CASHIER: POS + transactions + view customers

    @Test
    fun `CASHIER can access POS`() {
        every { secureStorage.userRole } returns "CASHIER"
        assertTrue(roleManager.canAccessPOS)
    }

    @Test
    fun `CASHIER cannot access inventory`() {
        every { secureStorage.userRole } returns "CASHIER"
        assertFalse(roleManager.canAccessInventory)
    }

    @Test
    fun `CASHIER can access transactions`() {
        every { secureStorage.userRole } returns "CASHIER"
        assertTrue(roleManager.canAccessTransactions)
    }

    @Test
    fun `CASHIER cannot create products`() {
        every { secureStorage.userRole } returns "CASHIER"
        assertFalse(roleManager.canCreateProducts)
    }

    @Test
    fun `CASHIER can view customers (respaldo por rol)`() {
        every { secureStorage.userRole } returns "CASHIER"
        assertTrue(roleManager.canViewCustomers)
    }

    // MARK: - MANAGER: full access

    @Test
    fun `MANAGER can access POS`() {
        every { secureStorage.userRole } returns "MANAGER"
        assertTrue(roleManager.canAccessPOS)
    }

    @Test
    fun `MANAGER can access inventory`() {
        every { secureStorage.userRole } returns "MANAGER"
        assertTrue(roleManager.canAccessInventory)
    }

    @Test
    fun `MANAGER can access transactions`() {
        every { secureStorage.userRole } returns "MANAGER"
        assertTrue(roleManager.canAccessTransactions)
    }

    @Test
    fun `MANAGER can create products`() {
        every { secureStorage.userRole } returns "MANAGER"
        assertTrue(roleManager.canCreateProducts)
    }

    @Test
    fun `MANAGER can manage customers`() {
        every { secureStorage.userRole } returns "MANAGER"
        assertTrue(roleManager.canManageCustomers)
    }

    @Test
    fun `MANAGER can view customers (respaldo por rol)`() {
        every { secureStorage.userRole } returns "MANAGER"
        assertTrue(roleManager.canViewCustomers)
    }

    // MARK: - ADMIN, OWNER, SUPERADMIN: full access (spot checks)

    @Test
    fun `ADMIN has full access (respaldo por rol)`() {
        every { secureStorage.userRole } returns "ADMIN"
        assertTrue(roleManager.canAccessPOS)
        assertTrue(roleManager.canAccessInventory)
        assertTrue(roleManager.canAccessTransactions)
        assertTrue(roleManager.canCreateProducts)
        assertTrue(roleManager.canManageCustomers)
        assertTrue(roleManager.canViewCustomers)
    }

    @Test
    fun `OWNER has full access (respaldo por rol)`() {
        every { secureStorage.userRole } returns "OWNER"
        assertTrue(roleManager.canAccessPOS)
        assertTrue(roleManager.canAccessInventory)
        assertTrue(roleManager.canAccessTransactions)
        assertTrue(roleManager.canCreateProducts)
        assertTrue(roleManager.canManageCustomers)
        assertTrue(roleManager.canViewCustomers)
    }

    @Test
    fun `SUPERADMIN has full access (respaldo por rol)`() {
        every { secureStorage.userRole } returns "SUPERADMIN"
        assertTrue(roleManager.canAccessPOS)
        assertTrue(roleManager.canAccessInventory)
        assertTrue(roleManager.canAccessTransactions)
        assertTrue(roleManager.canCreateProducts)
        assertTrue(roleManager.canManageCustomers)
        assertTrue(roleManager.canViewCustomers)
    }

    // MARK: - Los GEMELOS: los mismos gates con la lista que el server manda
    //
    // 🔴 Todo lo de arriba corre con la cache VACÍA (respaldo por rol). Aquí se
    // mide con `PermisosRealesDelServer`, que es la salida literal de
    // `getEffectiveRolePermissions()` — el mismo payload que el login guarda en
    // `SecureStorage.venuePermissions`. Donde el respaldo y el permiso NO
    // coinciden, manda esta tabla: es lo que hace el aparato.

    @Test
    fun `ver clientes con la lista REAL del server, rol por rol`() {
        // El server le da `customers:read` a VIEWER y HOST, que el respaldo por
        // rol (FLOOR) les niega. No cambia nada visible —`canViewCustomers` no
        // tiene ni un consumidor en la app— pero el test no puede afirmar lo
        // contrario de lo que devuelve el aparato.
        every { secureStorage.userRole } returns "VIEWER"
        every { secureStorage.venuePermissions } returns PermisosRealesDelServer.VIEWER
        assertTrue("el server SÍ le da customers:read al VIEWER", roleManager.canViewCustomers)

        every { secureStorage.userRole } returns "HOST"
        every { secureStorage.venuePermissions } returns PermisosRealesDelServer.HOST
        assertTrue("y al HOST también", roleManager.canViewCustomers)

        every { secureStorage.userRole } returns "KITCHEN"
        every { secureStorage.venuePermissions } returns PermisosRealesDelServer.KITCHEN
        assertFalse("la cocina es la única del piso que NO lo trae", roleManager.canViewCustomers)

        every { secureStorage.userRole } returns "WAITER"
        every { secureStorage.venuePermissions } returns PermisosRealesDelServer.WAITER
        assertTrue(roleManager.canViewCustomers)

        every { secureStorage.userRole } returns "CASHIER"
        every { secureStorage.venuePermissions } returns PermisosRealesDelServer.CASHIER
        assertTrue(roleManager.canViewCustomers)

        every { secureStorage.userRole } returns "SUPERADMIN"
        every { secureStorage.venuePermissions } returns PermisosRealesDelServer.SUPERADMIN
        assertTrue("el comodín `*:*` concede todo", roleManager.canViewCustomers)
    }

    /**
     * 🔴 El gemelo que faltaba y que dejó pasar el defecto: con la lista REAL,
     * `orders:update` arrastra `inventory:read` y el cajero, el mesero y la
     * cocina lo reciben. La pestaña de Inventario NO puede encenderse por ahí —
     * a quién se le abre el módulo es decisión de producto, no la traducción de
     * un permiso que el POS usa para consultar existencias al tomar una orden.
     * El detalle, con la barra de navegación completa, en
     * `navigation/InventarioTabPermisoTest`.
     */
    @Test
    fun `inventario NO se enciende con la lista REAL del piso`() {
        every { secureStorage.userRole } returns "CASHIER"
        every { secureStorage.venuePermissions } returns PermisosRealesDelServer.CASHIER
        assertTrue("premisa: el server sí manda inventory:read", PermisosRealesDelServer.CASHIER.contains("inventory:read"))
        assertFalse(roleManager.canAccessInventory)

        every { secureStorage.userRole } returns "WAITER"
        every { secureStorage.venuePermissions } returns PermisosRealesDelServer.WAITER
        assertFalse(roleManager.canAccessInventory)

        every { secureStorage.userRole } returns "KITCHEN"
        every { secureStorage.venuePermissions } returns PermisosRealesDelServer.KITCHEN
        assertFalse(roleManager.canAccessInventory)

        every { secureStorage.userRole } returns "MANAGER"
        every { secureStorage.venuePermissions } returns PermisosRealesDelServer.CASHIER
        assertTrue("y el gerente lo sigue viendo", roleManager.canAccessInventory)
    }

    // MARK: - Candado del PIN de autorización de gerente
    //
    // Esconder un botón parece limpio, pero deja al piso sin salida: sin botón
    // no hay 403, y sin 403 no hay a quién pedirle autorización.

    @Test
    fun `con permiso, la accion se ve normal`() {
        every { secureStorage.userRole } returns "MANAGER"
        assertEquals(ActionVisibility.ALLOWED, roleManager.visibilityOf(allowed = true, overrideEnabled = false))
        assertEquals(ActionVisibility.ALLOWED, roleManager.visibilityOf(allowed = true, overrideEnabled = true))
    }

    @Test
    fun `sin permiso y con el switch APAGADO, se esconde como hoy`() {
        every { secureStorage.userRole } returns "WAITER"
        assertEquals(ActionVisibility.HIDDEN, roleManager.visibilityOf(allowed = false, overrideEnabled = false))
    }

    @Test
    fun `sin permiso y con el switch PRENDIDO, se ve con candado`() {
        every { secureStorage.userRole } returns "WAITER"
        assertEquals(ActionVisibility.LOCKED, roleManager.visibilityOf(allowed = false, overrideEnabled = true))
    }

    @Test
    fun `el reembolso sigue permitido para MANAGER y prohibido para WAITER (respaldo por rol)`() {
        every { secureStorage.userRole } returns "MANAGER"
        assertTrue(roleManager.canIssueRefund)
        every { secureStorage.userRole } returns "WAITER"
        assertFalse(roleManager.canIssueRefund)
    }

    // MARK: - El candado NO puede mentir: se decide por PERMISO, no por rol
    //
    // 🔴 Medido en la D3 el 2026-08-17: a un CAJERO se le pintaba el candado en
    // "Emitir reembolso", llenaba importe y motivo, tocaba Reembolsar… y el
    // reembolso se ejecutaba SIN pedir ningún PIN (quedó un `Payment` de
    // CASH -50.00). El server SÍ le da `payments:refund` al CASHIER; la app
    // decidía por una lista de roles. Un gate por ROL no es un espejo del
    // permiso: es una segunda fuente de verdad que se desincroniza sola.

    /**
     * 🔴 La lista EFECTIVA completa, no una selección a mano.
     *
     * Antes estas dos eran listas de 9 entradas escritas a mano y tituladas "los
     * permisos REALES": omitían las que el server AGREGA al expandir
     * dependencias, entre ellas `inventory:read`. Con ese fixture, el gate de
     * inventario daba en el test lo contrario de lo que da en el aparato. Un
     * fixture recortado a mano no prueba el sistema: prueba la idea que quien lo
     * escribió tenía del sistema.
     */
    private val permisosDeCajero = PermisosRealesDelServer.CASHIER

    /** Los permisos REALES del WAITER: NO trae `payments:refund`. */
    private val permisosDeMesero = PermisosRealesDelServer.WAITER

    @Test
    fun `el CAJERO puede reembolsar porque el server le da payments refund`() {
        every { secureStorage.userRole } returns "CASHIER"
        every { secureStorage.venuePermissions } returns permisosDeCajero

        assertTrue(roleManager.canIssueRefund)
    }

    @Test
    fun `el MESERO no puede reembolsar porque el server no le da el permiso`() {
        every { secureStorage.userRole } returns "WAITER"
        every { secureStorage.venuePermissions } returns permisosDeMesero

        assertFalse(roleManager.canIssueRefund)
    }

    /**
     * Un Permission Set puede darle el permiso a CUALQUIER rol. El rol deja de
     * importar: manda la lista efectiva que el server calculó para este venue.
     */
    @Test
    fun `un rol cualquiera con payments refund concedido a mano puede reembolsar`() {
        every { secureStorage.userRole } returns "WAITER"
        every { secureStorage.venuePermissions } returns permisosDeMesero + "payments:refund"

        assertTrue(roleManager.canIssueRefund)
    }

    /**
     * Y al revés: si el server le quitó el permiso a un gerente, la app tiene
     * que pintar el candado. Antes le prometía una autorización que el server
     * no iba a respetar.
     */
    @Test
    fun `un MANAGER sin el permiso efectivo ve el candado`() {
        every { secureStorage.userRole } returns "MANAGER"
        every { secureStorage.venuePermissions } returns listOf("menu:read", "orders:read")

        assertFalse(roleManager.canIssueRefund)
    }

    @Test
    fun `los comodines del server tambien conceden el reembolso`() {
        every { secureStorage.userRole } returns "CASHIER"

        every { secureStorage.venuePermissions } returns listOf("payments:*")
        assertTrue(roleManager.canIssueRefund)

        every { secureStorage.venuePermissions } returns listOf("*:*")
        assertTrue(roleManager.canIssueRefund)
    }

    /**
     * Con la lista VACÍA no se sabe nada —sesión vieja, o un server que no la
     * manda— y se cae al respaldo por rol. Negar a ciegas escondería el
     * reembolso hasta al dueño.
     */
    @Test
    fun `con la cache de permisos vacia el reembolso cae al respaldo por rol`() {
        every { secureStorage.venuePermissions } returns emptyList()

        every { secureStorage.userRole } returns "OWNER"
        assertTrue(roleManager.canIssueRefund)

        every { secureStorage.userRole } returns "CASHIER"
        assertFalse(roleManager.canIssueRefund)
    }

    /**
     * Cómo se PINTA el botón, ahora que el permiso manda. Con el switch del
     * local apagado sigue desapareciendo igual que hoy: adelantar el PIN no
     * cambia a quién se le ofrece.
     */
    @Test
    fun `como se pinta Emitir reembolso segun permiso y switch`() {
        every { secureStorage.userRole } returns "CASHIER"

        // Cajero CON el permiso: normal, sin candado, con o sin switch.
        every { secureStorage.venuePermissions } returns permisosDeCajero
        assertEquals(
            ActionVisibility.ALLOWED,
            roleManager.visibilityOf(roleManager.canIssueRefund, overrideEnabled = false),
        )
        assertEquals(
            ActionVisibility.ALLOWED,
            roleManager.visibilityOf(roleManager.canIssueRefund, overrideEnabled = true),
        )

        // Mesero SIN el permiso: candado sólo si el local prendió el PIN.
        every { secureStorage.userRole } returns "WAITER"
        every { secureStorage.venuePermissions } returns permisosDeMesero
        assertEquals(
            ActionVisibility.HIDDEN,
            roleManager.visibilityOf(roleManager.canIssueRefund, overrideEnabled = false),
        )
        assertEquals(
            ActionVisibility.LOCKED,
            roleManager.visibilityOf(roleManager.canIssueRefund, overrideEnabled = true),
        )
    }

    // MARK: - Los hermanos que también se espejan por permiso

    /**
     * 🔴 Inventario NO se espeja — y este test existe para que no vuelva a
     * espejarse de paso.
     *
     * Se espejó de `inventory:read` el 2026-08-17 y se revirtió el mismo día:
     * ese permiso viaja por DEPENDENCIA IMPLÍCITA (`orders:update` lo arrastra),
     * así que espejarlo encendía la pestaña INVENTARIO de la barra —más dos
     * rutas del NavGraph y la única puerta a Traslados— para COCINA, MESERO y
     * CAJERO. Es una decisión de producto, no una traducción.
     */
    @Test
    fun `inventario NO se espeja de inventory read, decide el rol`() {
        every { secureStorage.userRole } returns "CASHIER"

        every { secureStorage.venuePermissions } returns listOf("inventory:read")
        assertFalse("conceder el permiso no puede abrirle el módulo al cajero", roleManager.canAccessInventory)

        every { secureStorage.userRole } returns "MANAGER"
        every { secureStorage.venuePermissions } returns listOf("menu:read")
        assertTrue("y quitárselo no puede cerrárselo al gerente", roleManager.canAccessInventory)
    }

    @Test
    fun `reportes se espeja de reports read`() {
        every { secureStorage.userRole } returns "MANAGER"

        every { secureStorage.venuePermissions } returns listOf("reports:read")
        assertTrue(roleManager.canAccessReports)

        every { secureStorage.venuePermissions } returns listOf("analytics:read")
        assertFalse(roleManager.canAccessReports)
    }

    @Test
    fun `ver clientes se espeja de customers read`() {
        every { secureStorage.userRole } returns "HOST"

        every { secureStorage.venuePermissions } returns listOf("customers:read")
        assertTrue(roleManager.canViewCustomers)

        every { secureStorage.venuePermissions } returns listOf("menu:read")
        assertFalse(roleManager.canViewCustomers)
    }

    @Test
    fun `los traslados se espejan de inventory-transfers`() {
        every { secureStorage.userRole } returns "CASHIER"

        every { secureStorage.venuePermissions } returns listOf("inventory-transfers:read")
        assertTrue(roleManager.canViewInventoryTransfers)
        assertFalse(roleManager.canDecideInventoryTransfers)

        every { secureStorage.venuePermissions } returns listOf("inventory-transfers:*")
        assertTrue(roleManager.canViewInventoryTransfers)
        assertTrue(roleManager.canDecideInventoryTransfers)
    }

    // MARK: - Unknown role treated as no access

    @Test
    fun `unknown role has no access`() {
        every { secureStorage.userRole } returns "INTERN"
        assertFalse(roleManager.canAccessPOS)
        assertFalse(roleManager.canAccessInventory)
        assertFalse(roleManager.canAccessTransactions)
        assertFalse(roleManager.canCreateProducts)
        assertFalse(roleManager.canManageCustomers)
        assertFalse(roleManager.canViewCustomers)
    }
}
