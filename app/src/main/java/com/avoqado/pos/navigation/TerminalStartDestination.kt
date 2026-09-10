package com.avoqado.pos.navigation

import com.avoqado.pos.settings.domain.PosMode
import com.avoqado.pos.tpvsettings.data.TerminalNavigationSettings

data class MainNavigationState(
    val startTab: MainTab,
    val contentKey: String,
)

internal data class MainGraphAccess(
    val canAccessPOS: Boolean,
    val canAccessInventory: Boolean,
    val canAccessTransactions: Boolean,
)

internal data class MainGraphSnapshot(
    val visibleTabs: List<MainTab>,
    val startTab: MainTab,
    val registeredTabs: Set<MainTab>,
)

/**
 * Captures the permissions used to build one main navigation graph.
 *
 * `visibleTabs` and RoleManager can briefly describe two different instants
 * while an invalid session is being cleared. Filtering and choosing the start
 * destination from this one snapshot prevents Compose Navigation from receiving
 * a start route that the same graph did not register.
 */
internal fun resolveMainGraphSnapshot(
    visibleTabs: List<MainTab>,
    initialTab: MainTab,
    access: MainGraphAccess,
): MainGraphSnapshot {
    val registeredTabs = buildSet {
        if (access.canAccessPOS) {
            add(MainTab.CHECKOUT)
            add(MainTab.TABLES)
        }
        if (access.canAccessInventory) add(MainTab.INVENTORY)
        if (access.canAccessTransactions) add(MainTab.TRANSACTIONS)
        add(MainTab.NOTIFICATIONS)
        add(MainTab.MORE)
        add(MainTab.CALENDAR)
    }
    val permittedVisibleTabs = visibleTabs.filter { it in registeredTabs }
        .ifEmpty { listOf(MainTab.NOTIFICATIONS, MainTab.MORE) }
    val startTab = initialTab.takeIf { it in permittedVisibleTabs }
        ?: permittedVisibleTabs.first()
    return MainGraphSnapshot(
        visibleTabs = permittedVisibleTabs,
        startTab = startTab,
        registeredTabs = registeredTabs,
    )
}

/**
 * Chooses the first surface for the current physical terminal.
 *
 * STANDARD_POS preserves the venue mode's existing first tab. An
 * AREA_OPERATIONS terminal opens the surface where its assigned job starts:
 * issue/checkout use Cobrar; a delivery-only station uses Más, which currently
 * hosts Entregas por área.
 */
internal fun resolveTerminalStartTab(
    visibleTabs: List<MainTab>,
    terminal: TerminalNavigationSettings,
): MainTab {
    val fallback = visibleTabs.firstOrNull() ?: MainTab.NOTIFICATIONS
    if (terminal.defaultWorkspace != TerminalNavigationSettings.AREA_OPERATIONS) {
        return fallback
    }

    return when {
        (terminal.canIssueAreaTickets || terminal.canCheckoutAreaTickets) &&
            MainTab.CHECKOUT in visibleTabs -> MainTab.CHECKOUT
        terminal.canDeliverAreaTickets && MainTab.MORE in visibleTabs -> MainTab.MORE
        else -> fallback
    }
}

internal fun mainContentKey(
    venueId: String?,
    posMode: PosMode,
    terminal: TerminalNavigationSettings,
    contextVersion: Int,
): String = buildString {
    append(venueId.orEmpty())
    append(':')
    append(posMode.key)
    append(':')
    append(terminal.terminalId.orEmpty())
    append(':')
    append(terminal.defaultWorkspace)
    append(':')
    append(if (terminal.canIssueAreaTickets) '1' else '0')
    append(if (terminal.canCheckoutAreaTickets) '1' else '0')
    append(if (terminal.canDeliverAreaTickets) '1' else '0')
    append(':')
    append(contextVersion)
}
