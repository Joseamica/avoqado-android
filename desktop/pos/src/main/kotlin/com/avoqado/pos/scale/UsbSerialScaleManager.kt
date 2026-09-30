package com.avoqado.pos.scale

import android.util.Log
import com.avoqado.pos.areatickets.data.ScaleProfile
import com.avoqado.pos.pos.data.model.NormalizedScaleReading
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Copiado tal cual del original: lo pintan WeightCapturePanel y StockCountingView. */
sealed interface ScaleConnectionState {
    data object NotConfigured : ScaleConnectionState
    data class Connecting(val profileName: String) : ScaleConnectionState
    data class Ready(val profileName: String) : ScaleConnectionState
    data class Unstable(
        val profileName: String,
        val reading: NormalizedScaleReading,
    ) : ScaleConnectionState
    data class Stable(
        val profileName: String,
        val reading: NormalizedScaleReading,
    ) : ScaleConnectionState
    data class Problem(
        val profileName: String,
        val message: String,
    ) : ScaleConnectionState
}

/**
 * Reemplazo de escritorio: la báscula va por usb-serial-for-android, que no existe en la PC. Conectar deja el estado
 * en `Problem` con el motivo, que la pantalla de peso muestra; el peso se puede teclear a mano como con cualquier
 * báscula caída.
 */
@Singleton
class UsbSerialScaleManager @Inject constructor() {
    private val _state = MutableStateFlow<ScaleConnectionState>(ScaleConnectionState.NotConfigured)
    val state: StateFlow<ScaleConnectionState> = _state.asStateFlow()

    suspend fun connect(
        profile: ScaleProfile,
        usageContext: ScaleUsageContext = ScaleUsageContext.AREA_TICKET_LINE,
    ) {
        Log.w("Escritorio", "No disponible en Windows todavía: báscula USB (${profile.name})")
        _state.value = ScaleConnectionState.Problem(profile.name, "La báscula USB no está disponible en Windows todavía.")
    }

    fun disconnect(updateState: Boolean = true) {
        if (updateState) _state.value = ScaleConnectionState.NotConfigured
    }
}
