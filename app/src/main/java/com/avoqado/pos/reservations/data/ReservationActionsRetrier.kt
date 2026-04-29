package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.model.CancelReservationRequest
import com.avoqado.pos.reservations.data.model.RescheduleRequest
import com.avoqado.pos.reservations.domain.ReservationAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReservationActionsRetrier @Inject constructor(
    private val pendingDao: PendingReservationActionDao,
    private val api: ReservationApi,
    private val connectivity: ConnectivityMonitor,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null
    private val maxAttempts = 5

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch {
            connectivity.isOnlineFlow.collectLatest { online ->
                if (online) drain()
            }
        }
    }

    internal suspend fun drain() {
        val pending = pendingDao.all()
        for (entry in pending) {
            if (entry.attemptCount >= maxAttempts) {
                pendingDao.delete(entry.rowId)
                continue
            }
            val action = runCatching { ReservationAction.valueOf(entry.action) }.getOrNull()
            if (action == null) {
                pendingDao.delete(entry.rowId)
                continue
            }
            val result: Result<Unit> = when (action) {
                ReservationAction.CONFIRM -> api.confirm(entry.reservationId).map { Unit }
                ReservationAction.CHECK_IN -> api.checkIn(entry.reservationId).map { Unit }
                ReservationAction.COMPLETE -> api.complete(entry.reservationId).map { Unit }
                ReservationAction.NO_SHOW -> api.noShow(entry.reservationId).map { Unit }
                ReservationAction.CANCEL -> {
                    val req = entry.payloadJson
                        ?.let { json.decodeFromString(CancelReservationRequest.serializer(), it) }
                        ?: CancelReservationRequest()
                    api.cancel(entry.reservationId, req)
                }
                ReservationAction.RESCHEDULE -> {
                    val req = json.decodeFromString(
                        RescheduleRequest.serializer(),
                        entry.payloadJson ?: error("Missing reschedule payload for ${entry.rowId}"),
                    )
                    api.reschedule(entry.reservationId, req).map { Unit }
                }
            }
            if (result.isSuccess) pendingDao.delete(entry.rowId)
            else pendingDao.incrementAttempt(entry.rowId)
        }
    }
}
