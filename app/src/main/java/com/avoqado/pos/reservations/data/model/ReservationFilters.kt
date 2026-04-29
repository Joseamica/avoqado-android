package com.avoqado.pos.reservations.data.model

data class ReservationFilters(
    val page: Int = 1,
    val pageSize: Int = 50,
    val statuses: List<ReservationStatus> = emptyList(),
    val dateFrom: String? = null,    // YYYY-MM-DD venue-local
    val dateTo: String? = null,
    val channel: ReservationChannel? = null,
    val search: String? = null,
    val tableId: String? = null,
    val staffId: String? = null,
    val productId: String? = null,
) {
    fun toQueryString(): String {
        val parts = mutableListOf<String>()
        parts += "page=$page"
        parts += "pageSize=$pageSize"
        if (statuses.isNotEmpty()) parts += "status=${statuses.joinToString(",") { it.name }}"
        dateFrom?.let { parts += "dateFrom=$it" }
        dateTo?.let { parts += "dateTo=$it" }
        channel?.let { parts += "channel=${it.name}" }
        search?.let { if (it.isNotBlank()) parts += "search=${java.net.URLEncoder.encode(it, "UTF-8")}" }
        tableId?.let { parts += "tableId=$it" }
        staffId?.let { parts += "staffId=$it" }
        productId?.let { parts += "productId=$it" }
        return parts.joinToString("&")
    }
}
