package com.avoqado.pos.reservations.domain

import com.avoqado.pos.reservations.data.model.CreateReservationRequest
import com.avoqado.pos.reservations.data.model.ReservationChannel
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

enum class CreateStep { CUSTOMER, SERVICE, DATETIME, DETAILS, CONFIRM }

data class CreateReservationDraft(
    val step: CreateStep = CreateStep.CUSTOMER,
    val customerId: String? = null,
    val customerName: String? = null,    // display
    val guestName: String? = null,
    val guestPhone: String? = null,
    val guestEmail: String? = null,
    val isGuest: Boolean = false,
    val productId: String? = null,
    val productName: String? = null,
    val durationMinutes: Int = 60,
    val date: LocalDate = LocalDate.now(),
    val time: LocalTime = LocalTime.of(9, 0),
    val partySize: Int = 1,
    val tableId: String? = null,
    val tableNumber: String? = null,
    val assignedStaffId: String? = null,
    val assignedStaffName: String? = null,
    val specialRequests: String? = null,
    val internalNotes: String? = null,
    val channel: ReservationChannel = ReservationChannel.DASHBOARD,
) {
    val canContinueFromCustomer: Boolean
        get() = customerId != null || (isGuest && !guestName.isNullOrBlank())
    val canContinueFromService: Boolean
        get() = productId != null
    val canSubmit: Boolean
        get() = canContinueFromCustomer && canContinueFromService

    fun toRequest(zone: ZoneId): CreateReservationRequest {
        val startLocal = ZonedDateTime.of(date, time, zone)
        val endLocal = startLocal.plusMinutes(durationMinutes.toLong())
        val iso = DateTimeFormatter.ISO_INSTANT
        return CreateReservationRequest(
            customerId = customerId.takeUnless { isGuest },
            guestName = if (isGuest) guestName else null,
            guestPhone = if (isGuest) guestPhone else null,
            guestEmail = if (isGuest) guestEmail else null,
            partySize = partySize,
            startsAt = iso.format(startLocal.toInstant()),
            endsAt = iso.format(endLocal.toInstant()),
            productId = productId,
            tableId = tableId,
            assignedStaffId = assignedStaffId,
            channel = channel.name,
            specialRequests = specialRequests,
            internalNotes = internalNotes,
        )
    }
}
