package com.avoqado.pos.pos.presentation.promotions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.pos.data.PromotionsRepository
import com.avoqado.pos.pos.data.model.PromotionsPayload
import com.avoqado.pos.tpvsettings.data.PanelMode
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Lo que el panel de promociones necesita saber: el catálogo cacheado, si el
 * local pagó el plan, y si ESTE cajero puede aplicar.
 *
 * 🔴 No aplica nada ni cobra nada. Sólo lee. Quien mete la promoción al carrito
 * es la Task 6.
 *
 * Plan: .superpowers/sdd/2026-08-15-promociones-pos-cliente/task-4-brief.md
 */
@HiltViewModel
class PromotionsPanelViewModel @Inject constructor(
    private val repository: PromotionsRepository,
    private val secureStorage: SecureStorage,
    private val planManager: PlanManager,
    private val roleManager: RoleManager,
    tpvSettingsRepository: TpvSettingsRepository,
) : ViewModel() {

    /** Catálogo cache-first: sin red se sigue viendo el último bueno. */
    val promociones: StateFlow<PromotionsPayload> = repository.promotions

    /**
     * Dónde quiere el local el panel del CAJERO. Es preferencia de VENUE, se
     * elige en el dashboard web y llega dentro de los ajustes de la terminal.
     * El ancho real de la pantalla lo corrige después (`resolverModoPanel`).
     */
    val ajustePanelCajero: StateFlow<PanelMode> = tpvSettingsRepository.settings
        .map { it.promotions.panelCashier }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = tpvSettingsRepository.getCurrentSettings().promotions.panelCashier,
        )

    /**
     * PRO (`PROMOTIONS`). `PlanManager` ya falla ABIERTO cuando el plan es
     * desconocido o el server es viejo — un bug de gating jamás puede impedir
     * cobrar.
     */
    val planPermitido: Boolean
        get() = planManager.hasFeature("PROMOTIONS")

    /**
     * ¿Este cajero puede aplicar una promoción? El server exige
     * [PERMISO_APLICAR_PROMOCION] en los DOS caminos, así que sin esto el toque
     * terminaría en un 403 pelón.
     *
     * 🔴 FAIL-OPEN: una lista de permisos VACÍA significa "no sabemos" (sesión
     * vieja, login que no los trajo), no "no puede". Con lista vacía se permite
     * y el server sigue siendo el juez. Bloquear aquí por falta de datos sería
     * quitarle al local una venta que sí podía hacer.
     */
    val puedeAplicar: Boolean
        get() = secureStorage.venuePermissions.isEmpty() ||
            roleManager.hasVenuePermission(PERMISO_APLICAR_PROMOCION)

    /**
     * Baja el catálogo del venue activo. Cache-first: si falla, se conserva lo
     * que ya había (el repositorio nunca borra por un fallo de red).
     */
    fun refresh() {
        val venueId = secureStorage.venueId ?: return
        viewModelScope.launch { repository.refresh(venueId) }
    }
}
