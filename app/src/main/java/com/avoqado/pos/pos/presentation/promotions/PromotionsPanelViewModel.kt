package com.avoqado.pos.pos.presentation.promotions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.pos.data.EstadoCatalogo
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
     * En qué situación está el catálogo: "todavía no sé", "sé que no hay" o "no
     * pude preguntar". Es lo que decide qué escribe el panel cuando no tiene ni
     * una tarjeta que pintar (ver `mensajeSinTarjetas`).
     *
     * 🔴 Se DERIVA del repositorio, no es una bandera de este ViewModel. Lo
     * intenté primero como latch local y estaba mal: el cambio de local llama
     * derecho al repositorio (`AuthRepository.switchVenue` → `clearCache()` +
     * `refresh(venue.id)`) sin tocar este ViewModel nunca, y su instancia
     * sobrevive al cambio de pestaña (`saveState`/`restoreState` del NavGraph).
     * O sea que el latch se quedaba en "ya cargué" del local ANTERIOR mientras el
     * catálogo del nuevo estaba vacío. Derivándolo, los dos caminos —primer
     * montaje y cambio de local— quedan cubiertos por construcción, sin depender
     * de quién llame a quién.
     */
    val estado: StateFlow<EstadoCatalogo> = repository.estado

    /**
     * Baja el catálogo del venue activo. Cache-first: si falla, se conserva lo
     * que ya había (el repositorio nunca borra por un fallo de red).
     *
     * El estado de carga lo mueve el repositorio — aquí no se toca nada.
     */
    fun refresh() {
        val venueId = secureStorage.venueId
        if (venueId == null) {
            repository.marcarSinVenue()
            return
        }
        viewModelScope.launch { repository.refresh(venueId) }
    }
}
