package com.avoqado.pos.cashdrawer.data

import kotlinx.serialization.Serializable

/**
 * "Cierre del día" (Square's end-of-day): the day's sales by tender plus the
 * blockers a manager must clear before closing. Read-only aggregate served by
 * GET /mobile/venues/:venueId/end-of-day — the server composes it from orders,
 * payments, cash drawer sessions and time entries, scoped to the VENUE-LOCAL day.
 */
@Serializable
data class EndOfDaySummary(
    val from: String = "",
    val to: String = "",
    val sales: EndOfDaySales = EndOfDaySales(),
    val openChecks: OpenChecks = OpenChecks(),
    val openDrawers: List<OpenDrawer> = emptyList(),
    val clockedInStaff: List<ClockedInStaff> = emptyList(),
    val readyToClose: Boolean = false,
)

@Serializable
data class EndOfDaySales(
    val totalCents: Int = 0,
    val tipsCents: Int = 0,
    val transactionCount: Int = 0,
    val averageTicketCents: Int = 0,
    val tenders: List<TenderTotal> = emptyList(),
)

@Serializable
data class TenderTotal(val method: String = "", val totalCents: Int = 0)

@Serializable
data class OpenChecks(val count: Int = 0, val totalCents: Int = 0)

@Serializable
data class OpenDrawer(
    val id: String = "",
    val openedByName: String = "",
    val openedAt: String = "",
    val startingAmountCents: Int = 0,
)

@Serializable
data class ClockedInStaff(
    val id: String = "",
    val name: String = "",
    val clockInTime: String = "",
    val status: String = "",
)

@Serializable
data class EndOfDayResponse(val success: Boolean = true, val data: EndOfDaySummary? = null)
