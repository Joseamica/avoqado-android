package com.avoqado.pos.navigation

import com.avoqado.pos.settings.domain.PosMode
import com.avoqado.pos.tpvsettings.data.TerminalNavigationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalStartDestinationTest {

    private val restaurantTabs = listOf(
        MainTab.TABLES,
        MainTab.CHECKOUT,
        MainTab.INVENTORY,
        MainTab.TRANSACTIONS,
        MainTab.NOTIFICATIONS,
        MainTab.MORE,
    )

    @Test
    fun `standard restaurant terminal still starts on tables`() {
        assertEquals(
            MainTab.TABLES,
            resolveTerminalStartTab(
                restaurantTabs,
                TerminalNavigationSettings.DEFAULT,
            ),
        )
    }

    @Test
    fun `area issuing terminal starts on checkout instead of tables`() {
        val terminal = TerminalNavigationSettings(
            terminalId = "cremeria",
            defaultWorkspace = TerminalNavigationSettings.AREA_OPERATIONS,
            canIssueAreaTickets = true,
            canDeliverAreaTickets = true,
            fulfillmentAreaId = "area-cremeria",
        )

        assertEquals(MainTab.CHECKOUT, resolveTerminalStartTab(restaurantTabs, terminal))
    }

    @Test
    fun `area checkout terminal starts on checkout instead of tables`() {
        val terminal = TerminalNavigationSettings(
            terminalId = "caja",
            defaultWorkspace = TerminalNavigationSettings.AREA_OPERATIONS,
            canCheckoutAreaTickets = true,
        )

        assertEquals(MainTab.CHECKOUT, resolveTerminalStartTab(restaurantTabs, terminal))
    }

    @Test
    fun `delivery-only area terminal starts where deliveries are available`() {
        val terminal = TerminalNavigationSettings(
            terminalId = "entregas",
            defaultWorkspace = TerminalNavigationSettings.AREA_OPERATIONS,
            canDeliverAreaTickets = true,
            fulfillmentAreaId = "area-panaderia",
        )

        assertEquals(MainTab.MORE, resolveTerminalStartTab(restaurantTabs, terminal))
    }

    @Test
    fun `area workspace without an effective capability falls back safely`() {
        val terminal = TerminalNavigationSettings(
            terminalId = "misconfigured",
            defaultWorkspace = TerminalNavigationSettings.AREA_OPERATIONS,
        )

        assertEquals(MainTab.TABLES, resolveTerminalStartTab(restaurantTabs, terminal))
    }

    @Test
    fun `content key changes across venue mode and terminal workspace`() {
        val standard = TerminalNavigationSettings.DEFAULT
        val area = TerminalNavigationSettings(
            terminalId = "cremeria",
            defaultWorkspace = TerminalNavigationSettings.AREA_OPERATIONS,
            canIssueAreaTickets = true,
        )

        val initial = mainContentKey("venue-a", PosMode.RESTAURANT, standard, contextVersion = 0)
        assertNotEquals(initial, mainContentKey("venue-b", PosMode.RESTAURANT, standard, contextVersion = 0))
        assertNotEquals(initial, mainContentKey("venue-a", PosMode.RETAIL, standard, contextVersion = 0))
        assertNotEquals(initial, mainContentKey("venue-a", PosMode.RESTAURANT, area, contextVersion = 0))
        assertNotEquals(initial, mainContentKey("venue-a", PosMode.RESTAURANT, standard, contextVersion = 1))
    }

    @Test
    fun `P1 clearSession no deja checkout viejo como inicio fuera del grafo compartido`() {
        val tabsAntesDel401 = listOf(
            MainTab.CHECKOUT,
            MainTab.INVENTORY,
            MainTab.TRANSACTIONS,
            MainTab.NOTIFICATIONS,
            MainTab.MORE,
        )
        val permisosDespuesDeClearSession = MainGraphAccess(
            canAccessPOS = false,
            canAccessInventory = false,
            canAccessTransactions = false,
        )

        val snapshot = resolveMainGraphSnapshot(
            visibleTabs = tabsAntesDel401,
            initialTab = MainTab.CHECKOUT,
            access = permisosDespuesDeClearSession,
        )

        assertFalse("no puede registrar Checkout sin permiso", MainTab.CHECKOUT in snapshot.registeredTabs)
        assertTrue("necesita un startDestination registrado", snapshot.startTab in snapshot.registeredTabs)
        assertEquals(listOf(MainTab.NOTIFICATIONS, MainTab.MORE), snapshot.visibleTabs)
    }

    @Test
    fun `si initialTab desaparece el inicio cae en una ruta registrada`() {
        val snapshot = resolveMainGraphSnapshot(
            visibleTabs = listOf(MainTab.INVENTORY, MainTab.NOTIFICATIONS, MainTab.MORE),
            initialTab = MainTab.CHECKOUT,
            access = MainGraphAccess(
                canAccessPOS = false,
                canAccessInventory = true,
                canAccessTransactions = false,
            ),
        )

        assertEquals(MainTab.INVENTORY, snapshot.startTab)
        assertTrue(snapshot.startTab in snapshot.registeredTabs)
    }

    @Test
    fun `sin tabs emitidas conserva una salida registrada`() {
        val snapshot = resolveMainGraphSnapshot(
            visibleTabs = emptyList(),
            initialTab = MainTab.CHECKOUT,
            access = MainGraphAccess(false, false, false),
        )

        assertEquals(listOf(MainTab.NOTIFICATIONS, MainTab.MORE), snapshot.visibleTabs)
        assertEquals(MainTab.NOTIFICATIONS, snapshot.startTab)
        assertTrue(snapshot.startTab in snapshot.registeredTabs)
    }

    @Test
    fun `POS conserva destinos internos aunque no aparezcan en la barra`() {
        val snapshot = resolveMainGraphSnapshot(
            visibleTabs = listOf(MainTab.CHECKOUT, MainTab.NOTIFICATIONS, MainTab.MORE),
            initialTab = MainTab.CHECKOUT,
            access = MainGraphAccess(
                canAccessPOS = true,
                canAccessInventory = false,
                canAccessTransactions = false,
            ),
        )

        assertTrue("Mesas y table-order dependen del mismo acceso POS", MainTab.TABLES in snapshot.registeredTabs)
        assertTrue("Calendario conserva links desde Más y notificaciones", MainTab.CALENDAR in snapshot.registeredTabs)
        assertEquals(MainTab.CHECKOUT, snapshot.startTab)
    }
}
