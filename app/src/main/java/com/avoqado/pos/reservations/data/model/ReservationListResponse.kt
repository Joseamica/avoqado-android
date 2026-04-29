package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ReservationListResponse(
    val data: List<Reservation>,
    val pagination: Pagination? = null,
) {
    @Serializable
    data class Pagination(
        val page: Int,
        val pageSize: Int,
        val total: Int,
        val totalPages: Int,
    )
}
