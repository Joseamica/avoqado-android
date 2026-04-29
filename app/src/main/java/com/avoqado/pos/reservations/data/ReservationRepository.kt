package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.model.CancelReservationRequest
import com.avoqado.pos.reservations.data.model.Reservation
import com.avoqado.pos.reservations.data.model.ReservationFilters
import com.avoqado.pos.reservations.data.model.ReservationListResponse
import com.avoqado.pos.reservations.data.model.RescheduleRequest
import com.avoqado.pos.reservations.domain.ReservationAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReservationRepository @Inject constructor(
    private val api: ReservationApi,
    private val pendingDao: PendingReservationActionDao,
    private val connectivity: ConnectivityMonitor,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val _lastList = MutableStateFlow<ReservationListResponse?>(null)
    val lastList: StateFlow<ReservationListResponse?> = _lastList.asStateFlow()

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
        return when (action) {
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
        }
    }

    sealed interface ActionPayload {
        fun toJson(json: Json): String

        data class Cancel(val reason: String?) : ActionPayload {
            fun toRequest() = CancelReservationRequest(reason)
            override fun toJson(json: Json): String =
                json.encodeToString(CancelReservationRequest.serializer(), CancelReservationRequest(reason))
        }

        data class Reschedule(val startsAt: String, val endsAt: String) : ActionPayload {
            fun toRequest() = RescheduleRequest(startsAt, endsAt)
            override fun toJson(json: Json): String =
                json.encodeToString(RescheduleRequest.serializer(), RescheduleRequest(startsAt, endsAt))
        }
    }

    class OfflineEnqueuedException(val action: ReservationAction) :
        Exception("Action ${action.name} enqueued for retry")
}
