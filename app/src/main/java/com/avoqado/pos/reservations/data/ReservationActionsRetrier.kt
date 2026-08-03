package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.model.CancelReservationRequest
import com.avoqado.pos.reservations.data.model.CreateReservationRequest
import com.avoqado.pos.reservations.data.model.RescheduleRequest
import com.avoqado.pos.reservations.data.model.UpdateReservationRequest
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
    private val reservationRepository: ReservationRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var job: Job? = null
    // Misma constante que la cuarentena: si se separan, una acción agotada
    // dejaría de listarse o se listaría antes de tiempo.
    private val maxAttempts = ReservationRepository.MAX_ATTEMPTS

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
        var anySuccess = false
        for (entry in pending) {
            if (entry.attemptCount >= maxAttempts) {
                // Agotó los reintentos: se DEJA en la tabla, no se borra.
                //
                // Antes se borraba aquí y la acción del mesero desaparecía sin que
                // nadie se enterara — la mesa quedaba sin confirmar, o un cliente
                // sin cancelar, y no había dónde mirarlo. Conservarla es lo que
                // permite listarla en la cuarentena, igual que los cobros
                // rechazados: visible hasta que un gerente la resuelva a mano.
                android.util.Log.e(
                    "📅",
                    "Acción de reserva EN CUARENTENA tras $maxAttempts intentos: " +
                        "${entry.action} sobre ${entry.reservationId} — nunca llegó al server",
                )
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
                ReservationAction.CREATE -> {
                    val req = json.decodeFromString(
                        CreateReservationRequest.serializer(),
                        entry.payloadJson ?: error("Missing create payload for ${entry.rowId}"),
                    )
                    api.create(req).map { Unit }
                }
                ReservationAction.UPDATE -> {
                    val req = json.decodeFromString(
                        UpdateReservationRequest.serializer(),
                        entry.payloadJson ?: error("Missing update payload for ${entry.rowId}"),
                    )
                    api.update(entry.reservationId, req).map { Unit }
                }
            }
            if (result.isSuccess) {
                pendingDao.delete(entry.rowId)
                anySuccess = true
            } else {
                // El motivo se guarda para que la cuarentena pueda enseñarlo. Sin
                // esto sólo quedaba una guía genérica por tipo de acción, y el
                // "requiere al menos 60 minutos de anticipación" que devuelve el
                // server se perdía en un log que nadie lee.
                pendingDao.incrementAttempt(entry.rowId, result.exceptionOrNull()?.message)
            }
        }
        // Coalesce into a single change signal per drain — mirrors iOS's
        // ReservationActionsRetrier (post once per drain with >=1 success; an
        // all-failure drain posts nothing) so stale screens refresh exactly once.
        if (anySuccess) reservationRepository.notifyChanged()
    }
}
