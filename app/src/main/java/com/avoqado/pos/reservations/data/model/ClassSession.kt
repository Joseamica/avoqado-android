package com.avoqado.pos.reservations.data.model
import com.avoqado.pos.core.util.formatMoney

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class ClassSessionStatus {
    @SerialName("SCHEDULED") SCHEDULED,
    @SerialName("CANCELLED") CANCELLED,
    @SerialName("COMPLETED") COMPLETED,
}

@Serializable
data class ClassSession(
    val id: String,
    val venueId: String,
    val productId: String,
    val product: ClassSessionProduct? = null,
    val startsAt: String,
    val endsAt: String,
    val duration: Int,
    val capacity: Int,
    val assignedStaffId: String? = null,
    val assignedStaff: StaffLite? = null,
    val status: ClassSessionStatus = ClassSessionStatus.SCHEDULED,
    val internalNotes: String? = null,
    val createdById: String? = null,
    val createdBy: StaffLite? = null,
    val reservations: List<ClassSessionAttendee> = emptyList(),
    val enrolled: Int = 0,
    val available: Int = capacity - enrolled,
    val createdAt: String? = null,
    val updatedAt: String? = null,
) {
    val title: String get() = product?.name ?: "Clase"
    val isFull: Boolean get() = enrolled >= capacity
}

@Serializable
data class ClassSessionProduct(
    val id: String,
    val name: String,
    val price: JsonElement? = null,
    val duration: Int? = null,
    val maxParticipants: Int? = null,
) {
    val displayPrice: String?
        get() {
            val primitive = price?.jsonPrimitive ?: return null
            val value = primitive.doubleOrNull ?: primitive.content.toDoubleOrNull() ?: return null
            return formatMoney(value)
        }
}

@Serializable
data class ClassSessionAttendee(
    val id: String,
    val confirmationCode: String? = null,
    val status: ReservationStatus? = null,
    val partySize: Int = 1,
    val guestName: String? = null,
    val guestPhone: String? = null,
    val guestEmail: String? = null,
    val specialRequests: String? = null,
    val customer: CustomerLite? = null,
) {
    val displayName: String get() = customer?.fullName ?: guestName ?: "Invitado"
    val displayContact: String? get() = customer?.phone ?: guestPhone ?: guestEmail
}

@Serializable
data class CreateClassSessionRequest(
    val productId: String,
    val startsAt: String,
    val endsAt: String,
    val capacity: Int,
    val assignedStaffId: String? = null,
    val internalNotes: String? = null,
)

@Serializable
data class CreateClassSessionBulkRequest(
    val productId: String,
    val startDate: String,
    val startTime: String,
    val endTime: String,
    val weekdays: List<Int>,
    val endDate: String? = null,
    val occurrences: Int? = null,
    val capacity: Int,
    val assignedStaffId: String? = null,
    val internalNotes: String? = null,
)

@Serializable
data class UpdateClassSessionRequest(
    val capacity: Int? = null,
    val assignedStaffId: String? = null,
    val internalNotes: String? = null,
)

@Serializable
data class AddAttendeeRequest(
    val guestName: String,
    val guestPhone: String? = null,
    val guestEmail: String? = null,
    val partySize: Int = 1,
    val specialRequests: String? = null,
    val customerId: String? = null,
)

@Serializable
data class BulkCreateResult(
    val count: Int = 0,
    val skipped: Int = 0,
    val created: List<BulkCreatedSession> = emptyList(),
)

@Serializable
data class BulkCreatedSession(
    val id: String,
    val startsAt: String,
    val endsAt: String,
)
