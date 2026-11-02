package com.avoqado.pos.pos.presentation.product

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * «Menú del aparato»: lo que los ajustes vigentes del aparato dicen que se ve en la cuadrícula. Las pantallas lo leen en cada
 * pintada, así un refresco (cada 5 min) cambia la pantalla abierta. `null` = todo.
 */
@HiltViewModel
class FiltroDelAparatoViewModel @Inject constructor(
    tpvSettingsRepository: TpvSettingsRepository,
) : ViewModel() {
    private val nav = tpvSettingsRepository.terminalNavigation
    private fun <T> flujo(f: (com.avoqado.pos.tpvsettings.data.TerminalNavigationSettings) -> T): StateFlow<T> =
        nav.map(f).distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, f(nav.value))

    val visibleCategoryIds: StateFlow<List<String>?> = flujo { it.visibleCategoryIds }
    val generacion: StateFlow<Long> = tpvSettingsRepository.generacionDelFiltro
    val visibleMenuIds: StateFlow<List<String>?> = flujo { it.visibleMenuIds }

    /** Cambia al cambiar de negocio (otro aparato): la pantalla apaga su «Todo ▸». */
    val terminalId: StateFlow<String?> = flujo { it.terminalId }
}
