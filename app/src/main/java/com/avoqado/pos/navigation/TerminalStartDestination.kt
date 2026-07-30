package com.avoqado.pos.navigation

import com.avoqado.pos.settings.domain.PosMode
import com.avoqado.pos.tpvsettings.data.TerminalNavigationSettings

data class MainNavigationState(
    val startTab: MainTab,
    val contentKey: String,
)

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
