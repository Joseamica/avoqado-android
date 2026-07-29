package com.avoqado.pos.reservations.domain

import com.avoqado.pos.reservations.data.model.CreateReservationRequest
import com.avoqado.pos.reservations.data.model.ReservationChannel
import com.avoqado.pos.reservations.data.model.UpdateReservationRequest
import com.avoqado.pos.core.util.VenueTimeZone
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

data class CreateReservationDraft(
    val customerId: String? = null,
    val customerName: String? = null,    // display
    val guestName: String? = null,
    val guestPhone: String? = null,
    val guestEmail: String? = null,
    val isGuest: Boolean = false,
    val productId: String? = null,
    val productName: String? = null,
    val productType: String? = null,
    val durationMinutes: Int = 60,
    val date: LocalDate = LocalDate.now(VenueTimeZone.zoneId()),
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
    val canSubmit: Boolean
        get() = missingToSubmit == null

    /**
     * Qué le falta a la cita para poder crearse, en palabras.
     *
     * El botón "Crear" se apagaba sin decir por qué: quien abre la pantalla ve dos
     * renglones que dicen "Seleccionar cliente" y "Seleccionar servicio" y un botón
     * muerto arriba, sin nada que los conecte. Con un cliente enfrente esperando
     * mesa, adivinar no es una opción.
     */
    val missingToSubmit: String?
        get() {
            val hasCustomer = customerId != null || (isGuest && !guestName.isNullOrBlank())
            val hasProduct = productId != null
            return when {
                !hasCustomer && !hasProduct -> "Elige el cliente y el servicio para crear la cita"
                !hasCustomer -> "Elige el cliente para crear la cita"
                !hasProduct -> "Elige el servicio para crear la cita"
                else -> null
            }
        }

    fun toRequest(
        zone: ZoneId,
        useBaseWindow: Boolean = false,
        allowOverCapacity: Boolean = false,
    ): CreateReservationRequest {
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
            duration = durationMinutes,
            productId = productId,
            productIds = if (useBaseWindow) productId?.let(::listOf) else null,
            windowSemantics = if (useBaseWindow) "base" else null,
            allowOverCapacity = true.takeIf { allowOverCapacity },
            tableId = tableId,
            assignedStaffId = assignedStaffId,
            channel = channel.name,
            specialRequests = specialRequests,
            internalNotes = internalNotes,
        )
    }

    // Note: UpdateReservationRequest does not include date/time fields — those go through
    // the dedicated reschedule endpoint.
    fun toUpdateRequest(): UpdateReservationRequest =
        UpdateReservationRequest(
            customerId = customerId.takeUnless { isGuest },
            guestName = if (isGuest) guestName else null,
            guestPhone = if (isGuest) guestPhone else null,
            guestEmail = if (isGuest) guestEmail else null,
            partySize = partySize,
            productId = productId,
            tableId = tableId,
            assignedStaffId = assignedStaffId,
            specialRequests = specialRequests,
            internalNotes = internalNotes,
        )
}
