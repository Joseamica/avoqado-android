package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.model.CancelReservationRequest
import com.avoqado.pos.reservations.data.model.CreateReservationRequest
import com.avoqado.pos.reservations.data.model.ProductStaffContract
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationFilters
import com.avoqado.pos.reservations.data.model.ReservationListResponse
import com.avoqado.pos.reservations.data.model.ReservationSettingsContract
import com.avoqado.pos.reservations.data.model.RescheduleRequest
import com.avoqado.pos.reservations.data.model.UpdateReservationRequest
import com.avoqado.pos.reservations.domain.ReservationAction
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class ReservationTimeSlot(
    val time: java.time.LocalTime,
    val available: Boolean,
    val reason: String? = null,
) {
    val isFull: Boolean get() = !available && reason == "FULL"
}

@Singleton
class ReservationRepository @Inject constructor(
    private val api: ReservationApi,
    private val pendingDao: PendingReservationActionDao,
    private val connectivity: ConnectivityMonitor,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val _lastList = MutableStateFlow<ReservationListResponse?>(null)
    val lastList: StateFlow<ReservationListResponse?> = _lastList.asStateFlow()

    /**
     * Emits after every successful mutation (create, update, runAction). Consumers (calendar /
     * list view models) collect this to refetch their data so the UI stays consistent with
     * server state without each screen polling on resume. `extraBufferCapacity = 1` +
     * `DROP_OLDEST` makes emits lossless from the producer side even when no one is collecting.
     */
    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    /**
     * Lets external callers (e.g. [ReservationActionsRetrier] after an offline-queue drain)
     * signal that server state changed without going through a mutation method here.
     */
    fun notifyChanged() {
        _changes.tryEmit(Unit)
    }

    val pendingActionsCount: Flow<Int> = pendingDao.pendingCount()

    suspend fun fetchList(filters: ReservationFilters): Result<ReservationListResponse> {
        val r = api.list(filters)
        r.getOrNull()?.let { _lastList.value = it }
        return r
    }

    suspend fun fetchCalendar(dateFrom: String, dateTo: String): Result<List<Reservation>> =
        api.calendar(dateFrom, dateTo)

    suspend fun fetchOne(id: String): Result<Reservation> = api.get(id)

    suspend fun runAction(
        reservationId: String,
        action: ReservationAction,
        payload: ActionPayload? = null,
    ): Result<Reservation?> {
        if (!connectivity.isOnline()) {
            pendingDao.enqueue(
                PendingReservationActionEntity(
                    reservationId = reservationId,
                    action = action.name,
                    payloadJson = payload?.toJson(json),
                ),
            )
            return Result.failure(OfflineEnqueuedException(action))
        }
        val result = when (action) {
            ReservationAction.CONFIRM -> api.confirm(reservationId).map { it as Reservation? }
            ReservationAction.CHECK_IN -> api.checkIn(reservationId).map { it as Reservation? }
            ReservationAction.COMPLETE -> api.complete(reservationId).map { it as Reservation? }
            ReservationAction.NO_SHOW -> api.noShow(reservationId).map { it as Reservation? }
            ReservationAction.CANCEL -> api.cancel(
                reservationId,
                (payload as? ActionPayload.Cancel)?.toRequest() ?: CancelReservationRequest(),
            ).map { null }
            ReservationAction.RESCHEDULE -> api.reschedule(
                reservationId,
                (payload as ActionPayload.Reschedule).toRequest(),
            ).map { it as Reservation? }
            ReservationAction.CREATE -> error("CREATE is not a runAction transition; call createReservation()")
            ReservationAction.UPDATE -> error("UPDATE is not a runAction transition; call updateReservation()")
        }
        if (result.isSuccess) _changes.tryEmit(Unit)
        return result
    }

    /** Horas reservables del día en la zona del venue (server = fuente de verdad). */
    suspend fun availableSlots(
        date: java.time.LocalDate,
        durationMin: Int?,
        zone: java.time.ZoneId,
        productId: String? = null,
        staffId: String? = null,
        includeFull: Boolean = false,
        windowSemantics: String? = null,
    ): Result<List<ReservationTimeSlot>> =
        api.availability(date.toString(), durationMin, productId, staffId, includeFull, windowSemantics).map { slots ->
            slots.mapNotNull { slot ->
                runCatching {
                    ReservationTimeSlot(
                        time = java.time.Instant.parse(slot.startsAt).atZone(zone).toLocalTime(),
                        available = slot.available,
                        reason = slot.reason,
                    )
                }.getOrNull()
            }
        }

    suspend fun reservationSettings(): Result<ReservationSettingsContract> = api.settings()

    suspend fun productStaff(productId: String): Result<ProductStaffContract> = api.productStaff(productId)

    suspend fun createReservation(request: CreateReservationRequest): Result<Reservation?> {
        if (!connectivity.isOnline()) {
            pendingDao.enqueue(
                PendingReservationActionEntity(
                    reservationId = "PENDING_NEW",
                    action = ReservationAction.CREATE.name,
                    payloadJson = ActionPayload.Create(request).toJson(json),
                ),
            )
            return Result.failure(OfflineEnqueuedException(ReservationAction.CREATE))
        }
        val result = api.create(request).map { it as Reservation? }
        if (result.isSuccess) _changes.tryEmit(Unit)
        return result
    }

    suspend fun updateReservation(id: String, request: UpdateReservationRequest): Result<Reservation?> {
        if (!connectivity.isOnline()) {
            pendingDao.enqueue(
                PendingReservationActionEntity(
                    reservationId = id,
                    action = ReservationAction.UPDATE.name,
                    payloadJson = ActionPayload.Update(request).toJson(json),
                ),
            )
            return Result.failure(OfflineEnqueuedException(ReservationAction.UPDATE))
        }
        val result = api.update(id, request).map { it as Reservation? }
        if (result.isSuccess) _changes.tryEmit(Unit)
        return result
    }

    sealed interface ActionPayload {
        fun toJson(json: Json): String

        data class Cancel(val reason: String?) : ActionPayload {
            fun toRequest() = CancelReservationRequest(reason)
            override fun toJson(json: Json): String =
                json.encodeToString(CancelReservationRequest.serializer(), CancelReservationRequest(reason))
        }

        data class Reschedule(
            val startsAt: String,
            val endsAt: String,
            val notificationChannel: com.avoqado.pos.reservations.data.model.RescheduleNotificationChannel? = null,
            val customMessage: String? = null,
            val allowOverCapacity: Boolean? = null,
        ) : ActionPayload {
            fun toRequest() = RescheduleRequest(
                startsAt,
                endsAt,
                notificationChannel,
                customMessage,
                allowOverCapacity,
            )
            override fun toJson(json: Json): String =
                json.encodeToString(RescheduleRequest.serializer(), toRequest())
        }

        data class Create(val request: CreateReservationRequest) : ActionPayload {
            override fun toJson(json: Json): String =
                json.encodeToString(CreateReservationRequest.serializer(), request)
        }

        data class Update(val request: UpdateReservationRequest) : ActionPayload {
            override fun toJson(json: Json): String =
                json.encodeToString(UpdateReservationRequest.serializer(), request)
        }
    }

    class OfflineEnqueuedException(val action: ReservationAction) :
        Exception("Action ${action.name} enqueued for retry")
}
