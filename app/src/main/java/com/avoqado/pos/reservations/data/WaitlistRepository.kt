package com.avoqado.pos.reservations.data

import com.avoqado.pos.reservations.data.model.AddToWaitlistRequest
import com.avoqado.pos.reservations.data.model.PromoteWaitlistRequest
import com.avoqado.pos.reservations.data.model.WaitlistEntry
import com.avoqado.pos.reservations.data.model.WaitlistStatus
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WaitlistRepository @Inject constructor(
    private val api: WaitlistApi,
) {
    suspend fun fetchEntries(status: WaitlistStatus? = null): Result<List<WaitlistEntry>> =
        api.list(status)

    suspend fun addEntry(request: AddToWaitlistRequest): Result<WaitlistEntry> =
        api.add(request)

    suspend fun removeEntry(entryId: String): Result<Unit> =
        api.remove(entryId)

    suspend fun promoteEntry(entryId: String, reservationId: String): Result<WaitlistEntry> =
        api.promote(entryId, PromoteWaitlistRequest(reservationId))
}
