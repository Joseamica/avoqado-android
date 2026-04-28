package com.avoqado.pos

import android.app.Application
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.VenueTimeZone
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class AvoqadoApp : Application() {
    @Inject lateinit var secureStorage: SecureStorage

    override fun onCreate() {
        super.onCreate()
        // Hydrate the global venue timezone holder so DTO-level formatters
        // (TransactionModel.timeDisplay etc.) work from the very first compose.
        VenueTimeZone.set(secureStorage.venueTimezone)
    }
}
