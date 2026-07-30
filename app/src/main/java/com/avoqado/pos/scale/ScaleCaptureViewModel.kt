package com.avoqado.pos.scale

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.areatickets.data.ScaleIntegrationSettings
import com.avoqado.pos.areatickets.data.ScaleProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class ScaleCaptureViewModel @Inject constructor(
    private val scaleManager: UsbSerialScaleManager,
) : ViewModel() {
    val state: StateFlow<ScaleConnectionState> = scaleManager.state

    private var profile: ScaleProfile? = null
    private var connectionJob: Job? = null

    fun start(settings: ScaleIntegrationSettings?) {
        val configured = settings?.profile?.takeIf {
            settings.entitled &&
                settings.enabled &&
                it.active &&
                it.transport == "ANDROID_USB_SERIAL" &&
                "AREA_TICKET_LINE" in it.allowedContexts
        }
        if (configured == null) {
            stop()
            return
        }
        if (profile?.id == configured.id &&
            state.value !is ScaleConnectionState.Problem &&
            state.value !is ScaleConnectionState.NotConfigured
        ) {
            return
        }
        profile = configured
        connect(configured)
    }

    fun retry() {
        profile?.let(::connect)
    }

    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        profile = null
        scaleManager.disconnect()
    }

    private fun connect(profile: ScaleProfile) {
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            scaleManager.connect(profile)
        }
    }

    override fun onCleared() {
        scaleManager.disconnect()
        super.onCleared()
    }
}
