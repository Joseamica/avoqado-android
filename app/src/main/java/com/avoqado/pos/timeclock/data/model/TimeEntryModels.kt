package com.avoqado.pos.timeclock.data.model

import kotlinx.serialization.Serializable

@Serializable
data class StaffIdentifyRequest(val pin: String)

@Serializable
data class StaffIdentifyResponse(
    val success: Boolean = true,
    val data: StaffData? = null,
    val staff: IdentifiedStaffPayload? = null,
    val currentEntry: CurrentEntryPayload? = null,
    val message: String? = null,
)

@Serializable
data class IdentifiedStaffPayload(
    val id: String,
    val firstName: String,
    val lastName: String,
    val role: String? = null,
)

@Serializable
data class CurrentEntryPayload(
    val id: String,
    val status: String,
    val isOnBreak: Boolean = false,
    // El server ya lo manda desde siempre; Android lo ignoraba y por eso, a
    // diferencia de iOS, no podía decir desde qué hora lleva trabajando.
    val clockInTime: String? = null,
)

@Serializable
data class StaffData(
    val id: String,
    val name: String,
    val role: String? = null,
    val clockedIn: Boolean = false,
    val onBreak: Boolean = false,
    /** ISO del inicio del turno activo. null cuando no ha marcado entrada. */
    val clockInTime: String? = null,
)

@Serializable
data class TimeEntry(
    val id: String,
    val staffId: String,
    val staffName: String? = null,
    val clockInTime: String? = null,
    val clockOutTime: String? = null,
    val breakStartTime: String? = null,
    val breakEndTime: String? = null,
)

enum class TimeClockAction {
    CLOCK_IN,
    CLOCK_OUT,
    BREAK_START,
    BREAK_END,
}

fun StaffIdentifyResponse.toStaffDataOrNull(): StaffData? {
    data?.let { return it }

    val staffPayload = staff ?: return null
    val currentEntryPayload = currentEntry
    val fullName = "${staffPayload.firstName} ${staffPayload.lastName}".trim()

    return StaffData(
        id = staffPayload.id,
        name = fullName.ifBlank { staffPayload.firstName },
        role = staffPayload.role,
        clockedIn = currentEntryPayload != null,
        onBreak = currentEntryPayload?.isOnBreak == true || currentEntryPayload?.status == "ON_BREAK",
        clockInTime = currentEntryPayload?.clockInTime,
    )
}
