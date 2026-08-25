package com.avoqado.pos.reservations.presentation.coach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.reservations.data.CoachClassApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class MyClassNowUiState(
    val loading: Boolean = true,
    val myClass: CoachClassApi.MyClass? = null,
    /** Se consultó y no hay clase ahora. Distinto de "todavía no carga". */
    val noClass: Boolean = false,
    val error: String? = null,
)

/**
 * "Mi clase ahora" — Fase 8.
 *
 * Se refresca solo cada 20 segundos mientras la pantalla esté abierta: quien da la clase
 * la deja puesta en la tablet y ve llegar a la gente sin tocar nada. Un pull-to-refresh
 * obligaría a interrumpir la clase para saber si ya llegó alguien.
 */
@HiltViewModel
class MyClassNowViewModel @Inject constructor(
    private val api: CoachClassApi,
    private val secureStorage: SecureStorage,
) : ViewModel() {

    /**
     * 🔴 La zona del NEGOCIO, nunca la del aparato. Una tablet con la zona mal puesta —o
     * un instructor que viaja— pintaría la clase de las 7 a otra hora, y la regla del
     * repo lo prohíbe explícitamente.
     */
    val zone: java.time.ZoneId
        get() = runCatching { java.time.ZoneId.of(secureStorage.venueTimezone ?: "America/Mexico_City") }
            .getOrDefault(java.time.ZoneId.of("America/Mexico_City"))

    private val _state = MutableStateFlow(MyClassNowUiState())
    val state: StateFlow<MyClassNowUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                load(silent = _state.value.myClass != null)
                delay(REFRESH_MS)
            }
        }
    }

    fun refresh() = viewModelScope.launch { load(silent = false) }

    /**
     * @param silent el refresco automático NO pinta el spinner encima de una lista que ya
     * se está leyendo: parpadear cada 20 s de cara a la clase es peor que esperar.
     */
    private suspend fun load(silent: Boolean) {
        if (!silent) _state.update { it.copy(loading = true, error = null) }
        api.myClassNow().fold(
            onSuccess = { c ->
                _state.update { it.copy(loading = false, myClass = c, noClass = c == null, error = null) }
            },
            onFailure = { e ->
                // Un fallo del refresco automático no borra lo que ya se ve: la lista de
                // hace 20 segundos sigue siendo útil, y un error a media clase no lo es.
                _state.update {
                    if (silent) it else it.copy(loading = false, error = e.message ?: "No se pudo cargar")
                }
            },
        )
    }

    private companion object {
        const val REFRESH_MS = 20_000L
    }
}
