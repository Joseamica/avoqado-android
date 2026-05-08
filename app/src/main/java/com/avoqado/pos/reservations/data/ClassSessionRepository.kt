package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.reservations.data.model.AddAttendeeRequest
import com.avoqado.pos.reservations.data.model.BulkCreateResult
import com.avoqado.pos.reservations.data.model.ClassSession
import com.avoqado.pos.reservations.data.model.ClassSessionAttendee
import com.avoqado.pos.reservations.data.model.CreateClassSessionBulkRequest
import com.avoqado.pos.reservations.data.model.CreateClassSessionRequest
import com.avoqado.pos.reservations.data.model.UpdateClassSessionRequest
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClassSessionRepository @Inject constructor(
    private val api: ClassSessionApi,
    private val connectivity: ConnectivityMonitor,
) {
    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    suspend fun fetchList(dateFrom: String, dateTo: String): Result<List<ClassSession>> =
        api.list(dateFrom = dateFrom, dateTo = dateTo)

    suspend fun fetchOne(sessionId: String): Result<ClassSession> = api.get(sessionId)

    suspend fun create(request: CreateClassSessionRequest): Result<ClassSession> =
        mutate { api.create(request) }

    suspend fun createBulk(request: CreateClassSessionBulkRequest): Result<BulkCreateResult> =
        mutate { api.createBulk(request) }

    suspend fun update(sessionId: String, request: UpdateClassSessionRequest): Result<ClassSession> =
        mutate { api.update(sessionId, request) }

    suspend fun cancel(sessionId: String): Result<ClassSession> =
        mutate { api.cancel(sessionId) }

    suspend fun addAttendee(sessionId: String, request: AddAttendeeRequest): Result<ClassSessionAttendee> =
        mutate { api.addAttendee(sessionId, request) }

    suspend fun removeAttendee(sessionId: String, reservationId: String): Result<Unit> =
        mutate { api.removeAttendee(sessionId, reservationId) }

    private suspend fun <T> mutate(block: suspend () -> Result<T>): Result<T> {
        if (!connectivity.isOnline()) {
            return Result.failure(OfflineRequiredException())
        }
        val result = block()
        if (result.isSuccess) _changes.tryEmit(Unit)
        return result
    }

    class OfflineRequiredException : Exception("Sin conexión, intenta de nuevo")
}
