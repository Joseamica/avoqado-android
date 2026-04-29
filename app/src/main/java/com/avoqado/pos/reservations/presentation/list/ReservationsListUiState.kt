package com.avoqado.pos.reservations.presentation.list

import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.ReservationStatus

enum class ReservationListTab(val label: String, val statusFilter: List<ReservationStatus>) {
    HOY("Hoy", listOf(ReservationStatus.PENDING, ReservationStatus.CONFIRMED, ReservationStatus.CHECKED_IN)),
    PENDIENTES("Pendientes", listOf(ReservationStatus.PENDING)),
    CONFIRMADAS("Confirmadas", listOf(ReservationStatus.CONFIRMED)),
    NO_SHOW("No-show", listOf(ReservationStatus.NO_SHOW)),
    TODAS("Todas", emptyList()),
}

data class ReservationsListUiState(
    val tab: ReservationListTab = ReservationListTab.HOY,
    val isLoading: Boolean = false,
    val items: List<Reservation> = emptyList(),
    val error: String? = null,
    val search: String = "",
    val channelFilter: ReservationChannel? = null,
    val pendingTransitionIds: Set<String> = emptySet(),
)
