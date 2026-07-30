package com.avoqado.pos.scale

import com.avoqado.pos.areatickets.data.AreaTicketException
import com.avoqado.pos.areatickets.data.ScaleIntegrationSettings
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScaleSettingsRepository @Inject constructor(
    private val api: ApiService,
    private val secureStorage: SecureStorage,
) {
    suspend fun settings(): ScaleIntegrationSettings {
        val venueId = secureStorage.venueId
            ?: throw AreaTicketException(
                code = "VENUE_REQUIRED",
                message = "Selecciona un local antes de conectar una báscula.",
                retryable = false,
            )
        val envelope = api.getScaleSettings(venueId)
        if (envelope.success && envelope.data != null) return envelope.data
        val failure = envelope.error
        throw AreaTicketException(
            code = failure?.code ?: "SCALE_SETTINGS_REQUEST_FAILED",
            message = failure?.message ?: "No se pudo consultar la configuración de la báscula.",
            retryable = failure?.retryable == true,
        )
    }
}
