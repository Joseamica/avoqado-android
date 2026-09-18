package com.avoqado.pos.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.tpvsettings.data.AjusteGuardado
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Llaves de `TpvSettings` que este aparato puede ofrecer. Espejo EXACTO de los nombres del server. */
object AjustesDelCobro {
    const val CALIFICACION = "showReviewScreen"
    const val PROPINA = "showTipScreen"
    const val PORCENTAJES = "tipSuggestions"
}

@HiltViewModel
class SetupWizardViewModel @Inject constructor(
    private val tpvSettingsRepository: TpvSettingsRepository,
) : ViewModel() {

    val settings = tpvSettingsRepository.settings

    /**
     * Qué ajustes admite ESTE aparato, según el servidor. Si viene vacía (server viejo, o un
     * aparato que no es un POS) no se ofrece nada: mostrar un interruptor que el servidor va a
     * rechazar es peor que no mostrarlo.
     */
    val configurables: StateFlow<List<String>> = tpvSettingsRepository.terminalNavigation.let { nav ->
        MutableStateFlow(nav.value.configurableSettings).also { flow ->
            viewModelScope.launch { nav.collect { flow.value = it.configurableSettings } }
        }
    }

    private val _isSavingTipTaxSetting = MutableStateFlow(false)
    val isSavingTipTaxSetting: StateFlow<Boolean> = _isSavingTipTaxSetting.asStateFlow()

    private val _guardandoCobro = MutableStateFlow(false)
    val guardandoCobro: StateFlow<Boolean> = _guardandoCobro.asStateFlow()

    /** Último problema al guardar, ya en lenguaje del cajero. `null` = nada que avisar. */
    private val _avisoCobro = MutableStateFlow<String?>(null)
    val avisoCobro: StateFlow<String?> = _avisoCobro.asStateFlow()

    fun updateIncludeTaxInTipBase(enabled: Boolean) {
        viewModelScope.launch {
            _isSavingTipTaxSetting.value = true
            try {
                tpvSettingsRepository.setIncludeTaxInTipBase(enabled)
            } finally {
                _isSavingTipTaxSetting.value = false
            }
        }
    }

    fun mostrarCalificacion(activada: Boolean) = guardarCobro(showReviewScreen = activada)

    fun mostrarPropina(activada: Boolean) = guardarCobro(showTipScreen = activada)

    /**
     * Cambia los porcentajes sugeridos (founder, 2026-09-18). Se manda la lista COMPLETA, no un
     * «agrega este»: el servidor valida el conjunto entero (1 a 100, sin repetidos, máximo 6) y así
     * dos cajeros tocando a la vez no acaban con una lista a medias.
     */
    fun cambiarPorcentajes(porcentajes: List<Int>) = guardarCobro(tipSuggestions = porcentajes)

    /**
     * 🔴 El interruptor NO se mueve hasta que el servidor confirma, y si algo falla se DICE.
     * Este ajuste vive en la ficha del aparato en el servidor (decisión del founder: así sobrevive
     * a reinstalar la app y el dashboard ve lo mismo), así que es online-only a propósito.
     */
    private fun guardarCobro(
        showReviewScreen: Boolean? = null,
        showTipScreen: Boolean? = null,
        tipSuggestions: List<Int>? = null,
    ) {
        viewModelScope.launch {
            _guardandoCobro.value = true
            _avisoCobro.value = null
            try {
                _avisoCobro.value = when (val resultado = tpvSettingsRepository.guardarPantallasDelCobro(showReviewScreen, showTipScreen, tipSuggestions)) {
                    AjusteGuardado.Ok -> null
                    AjusteGuardado.SinConexion -> "Sin conexión: no se guardó. Vuelve a intentarlo cuando haya internet."
                    AjusteGuardado.SinPermiso -> "No tienes permiso para cambiar esto. Pídeselo al dueño del negocio."
                    AjusteGuardado.SinFicha -> "Este aparato aún no termina de sincronizar. Intenta en un momento."
                    is AjusteGuardado.Rechazado -> "No se pudo guardar (error ${resultado.codigo}). Intenta de nuevo."
                }
            } finally {
                _guardandoCobro.value = false
            }
        }
    }

    fun descartarAviso() {
        _avisoCobro.value = null
    }
}
