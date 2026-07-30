package com.avoqado.pos.navigation

import com.avoqado.pos.settings.domain.PosMode
import com.avoqado.pos.tpvsettings.data.TerminalNavigationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}
