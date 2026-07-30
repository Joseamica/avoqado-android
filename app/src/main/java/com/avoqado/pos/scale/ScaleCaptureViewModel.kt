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
    private var usageContext: ScaleUsageContext? = null
    private var connectionJob: Job? = null

    fun start(
        settings: ScaleIntegrationSettings?,
        context: ScaleUsageContext = ScaleUsageContext.AREA_TICKET_LINE,
    ) {
        val configured = settings?.configuredProfileFor(context)
        if (configured == null) {
            stop()
            return
        }
        if (profile?.id == configured.id &&
            usageContext == context &&
            state.value !is ScaleConnectionState.Problem &&
            state.value !is ScaleConnectionState.NotConfigured
        ) {
            return
        }
        profile = configured
        usageContext = context
        connect(configured, context)
    }

    fun retry() {
        val configured = profile ?: return
        val context = usageContext ?: return
        connect(configured, context)
    }

    fun stop() {
        connectionJob?.cancel()
        connectionJob = null
        profile = null
        usageContext = null
        scaleManager.disconnect()
    }

    private fun connect(
        profile: ScaleProfile,
        context: ScaleUsageContext,
    ) {
        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            scaleManager.connect(profile, context)
        }
    }

    override fun onCleared() {
        scaleManager.disconnect()
        super.onCleared()
    }
}
