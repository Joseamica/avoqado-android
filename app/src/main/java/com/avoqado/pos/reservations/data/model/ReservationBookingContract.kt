package com.avoqado.pos.reservations.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ReservationAvailabilitySlot(
    val startsAt: String,
    val endsAt: String? = null,
    val available: Boolean = true,
    val reason: String? = null,
) {
    val isFull: Boolean get() = !available && reason == "FULL"
}

@Serializable
data class ReservationSettingsContract(
    val scheduling: ReservationSchedulingContract = ReservationSchedulingContract(),
    val publicBooking: ReservationPublicBookingContract = ReservationPublicBookingContract(),
) {
    val isStaffAware: Boolean
        get() = scheduling.capacityMode == "per_staff" || publicBooking.showStaffPicker == true
}

@Serializable
data class ReservationSchedulingContract(
    val capacityMode: String? = null,
)

@Serializable
data class ReservationPublicBookingContract(
    val showStaffPicker: Boolean? = null,
)

@Serializable
data class ProductStaffContract(
    val productId: String,
    val staffVenueIds: List<String> = emptyList(),
    val staff: List<ProductStaffMemberContract> = emptyList(),
    val explicit: Boolean = false,
)

@Serializable
data class ProductStaffMemberContract(
    val staffVenueId: String,
    val staffId: String,
)
