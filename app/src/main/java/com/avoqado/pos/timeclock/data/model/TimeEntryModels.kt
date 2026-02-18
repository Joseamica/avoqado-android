package com.avoqado.pos.timeclock.data.model

import kotlinx.serialization.Serializable

@Serializable
data class StaffIdentifyRequest(val pin: String)

@Serializable
data class StaffIdentifyResponse(
    val success: Boolean = true,
    val data: StaffData? = null,
    val message: String? = null,
)

@Serializable
data class StaffData(
    val id: String,
    val name: String,
    val role: String? = null,
    val clockedIn: Boolean = false,
    val onBreak: Boolean = false,
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
