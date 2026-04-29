package com.avoqado.pos

import android.app.Application
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.VenueTimeZone
import com.avoqado.pos.reservations.data.ReservationActionsRetrier
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@HiltAndroidApp
class AvoqadoApp : Application() {
    @Inject lateinit var secureStorage: SecureStorage
    @Inject lateinit var reservationActionsRetrier: ReservationActionsRetrier

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Hydrate the global venue timezone holder so DTO-level formatters
        // (TransactionModel.timeDisplay etc.) work from the very first compose.
        VenueTimeZone.set(secureStorage.venueTimezone)
        reservationActionsRetrier.start(appScope)
    }
}
