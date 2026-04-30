package com.avoqado.pos.reservations.data

import com.avoqado.pos.reservations.data.model.AddToWaitlistRequest
import com.avoqado.pos.reservations.data.model.PromoteWaitlistRequest
import com.avoqado.pos.reservations.data.model.WaitlistEntry
import com.avoqado.pos.reservations.data.model.WaitlistStatus
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WaitlistRepository @Inject constructor(
    private val api: WaitlistApi,
) {
    /**
     * Emits after every successful mutation (add, remove, promote). The waitlist screen
     * collects this so a promote that happens during the create-reservation flow refreshes
     * the list and the entry leaves the "Esperando" filter without a manual reload.
     */
    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val changes: SharedFlow<Unit> = _changes.asSharedFlow()

    suspend fun fetchEntries(status: WaitlistStatus? = null): Result<List<WaitlistEntry>> =
        api.list(status)

    suspend fun addEntry(request: AddToWaitlistRequest): Result<WaitlistEntry> {
        val r = api.add(request)
        if (r.isSuccess) _changes.tryEmit(Unit)
        return r
    }

    suspend fun removeEntry(entryId: String): Result<Unit> {
        val r = api.remove(entryId)
        if (r.isSuccess) _changes.tryEmit(Unit)
        return r
    }

    suspend fun promoteEntry(entryId: String, reservationId: String): Result<WaitlistEntry> {
        val r = api.promote(entryId, PromoteWaitlistRequest(reservationId))
        if (r.isSuccess) _changes.tryEmit(Unit)
        return r
    }
}
