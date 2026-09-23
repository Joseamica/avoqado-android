package com.avoqado.pos.inventory.waste.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.inventory.waste.data.DesenlaceDeDescarte
import com.avoqado.pos.inventory.waste.data.EstadoMerma
import com.avoqado.pos.inventory.waste.data.PendingWasteDao
import com.avoqado.pos.inventory.waste.data.PendingWasteEntity
import com.avoqado.pos.inventory.waste.data.WasteSyncCoordinator
import com.avoqado.pos.inventory.waste.domain.TextosMerma
import com.avoqado.pos.inventory.waste.domain.WasteReason
import com.avoqado.pos.inventory.waste.domain.etiquetaDeUnidad
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Una merma de la cola, como la lee el cajero. */
data class FilaPorSubir(
    val folio: String,
    val articulo: String,
    val cantidad: String,
    val motivo: String,
    /** Por qué sigue aquí, en palabras del cajero. */
    val explicacion: String,
    val propia: Boolean,
    val sePuedeDescartar: Boolean,
)

/** Cómo se dice un desenlace: sin red no es un error (se dice en ámbar), y un fallo no es un éxito. */
enum class TonoDeAviso { EXITO, ADVERTENCIA, ERROR }

data class AvisoDeDescarte(val texto: String, val tono: TonoDeAviso)

/** Cada quien ve lo suyo; MANAGER+ ve lo de todos (spec §5). La MISMA regla para la lista y el contador. */
fun mermasVisibles(filas: List<PendingWasteEntity>, yo: String?, todas: Boolean): List<PendingWasteEntity> =
    filas.filter { todas || it.staffId == yo }

/** Lo que todavía espera algo. Lo cerrado (anulada, ya aplicada) se conserva, pero ya no está «por subir». */
fun porSubir(filas: List<PendingWasteEntity>): Int =
    filas.count { it.estado != EstadoMerma.VOIDED && it.estado != EstadoMerma.APPLIED }

/** Por qué una fila sigue en la cola. 🔴 Una fila en revisión dice QUÉ hacer: nunca un código crudo. */
fun explicacionDeFila(fila: PendingWasteEntity): String = when (fila.estado) {
    EstadoMerma.SENDING -> TextosMerma.SUBIENDO
    EstadoMerma.PLAN_BLOCKED -> TextosMerma.SE_SUBIRA_SIN_PLAN
    EstadoMerma.VOIDED -> TextosMerma.ESTADO_DESCARTADA
    EstadoMerma.APPLIED -> TextosMerma.ESTADO_APLICADA
    EstadoMerma.NEEDS_REVIEW -> when (fila.ultimoCodigo) {
        "UNIT_MISMATCH" -> TextosMerma.REVISION_UNIDAD
        "ITEM_NOT_FOUND" -> TextosMerma.REVISION_ARTICULO
        "IDEMPOTENCY_KEY_REUSED" -> TextosMerma.REVISION_FOLIO
        "QUANTITY_TOO_LARGE" -> TextosMerma.REVISION_CANTIDAD
        // El 403 de `checkPermission` no trae `code`: el motor guarda «HTTP 403».
        "HTTP 403" -> TextosMerma.REVISION_PERMISO
        else -> TextosMerma.REVISION_OTRA
    }
    else -> if (fila.intentos > 0) TextosMerma.SE_REINTENTARA else TextosMerma.EN_ESPERA
}

/** Lo que se le dice al cajero después de intentar descartar. */
fun avisoDeDescarte(desenlace: DesenlaceDeDescarte): AvisoDeDescarte = when (desenlace) {
    DesenlaceDeDescarte.ANULADA -> AvisoDeDescarte(TextosMerma.DESCARTADA, TonoDeAviso.EXITO)
    DesenlaceDeDescarte.YA_APLICADA -> AvisoDeDescarte(TextosMerma.YA_APLICADA, TonoDeAviso.ADVERTENCIA)
    DesenlaceDeDescarte.SIN_RED -> AvisoDeDescarte(TextosMerma.DESCARTAR_SIN_RED, TonoDeAviso.ADVERTENCIA)
    DesenlaceDeDescarte.EN_CAMINO -> AvisoDeDescarte(TextosMerma.DESCARTAR_EN_CAMINO, TonoDeAviso.ADVERTENCIA)
    DesenlaceDeDescarte.SIN_PERMISO -> AvisoDeDescarte(TextosMerma.DESCARTAR_SIN_PERMISO, TonoDeAviso.ERROR)
    DesenlaceDeDescarte.FALLO -> AvisoDeDescarte(TextosMerma.DESCARTAR_FALLO, TonoDeAviso.ERROR)
}

/**
 * «Mermas por subir»: lo que la cola todavía guarda en ESTE aparato para la sucursal activa.
 * Espejo de `PendingWasteViewModel.swift` de avoqado-ios.
 */
@HiltViewModel
class PendingWasteViewModel @Inject constructor(
    private val dao: PendingWasteDao,
    private val motor: WasteSyncCoordinator,
    private val secureStorage: SecureStorage,
    private val roleManager: RoleManager,
) : ViewModel() {

    private val _filas = MutableStateFlow<List<FilaPorSubir>>(emptyList())
    val filas: StateFlow<List<FilaPorSubir>> = _filas.asStateFlow()

    private val _aviso = MutableStateFlow<AvisoDeDescarte?>(null)
    val aviso: StateFlow<AvisoDeDescarte?> = _aviso.asStateFlow()

    fun cargar(): Job = viewModelScope.launch { _filas.value = leer() }

    /** 🔴 Descartar EXIGE red (spec §4.3): lo decide el motor, que le pregunta al servidor. */
    fun descartar(folio: String): Job = viewModelScope.launch {
        _aviso.value = avisoDeDescarte(motor.descartar(folio))
        _filas.value = leer()
    }

    fun avisoVisto() {
        _aviso.value = null
    }

    private suspend fun leer(): List<FilaPorSubir> {
        val venueId = secureStorage.venueId ?: return emptyList()
        val yo = secureStorage.userId
        return mermasVisibles(dao.delVenue(venueId).first(), yo, roleManager.veMermasDeTodos).map { fila ->
            FilaPorSubir(
                folio = fila.idempotencyKey,
                articulo = fila.itemName,
                cantidad = "${fila.quantity} ${etiquetaDeUnidad(fila.unit)}",
                motivo = WasteReason.porCodigo(fila.reasonCode)?.etiqueta ?: fila.reasonCode,
                explicacion = explicacionDeFila(fila),
                propia = fila.staffId == yo,
                // La que va en camino no se toca; lo cerrado ya no tiene nada que descartar.
                sePuedeDescartar = fila.estado != EstadoMerma.SENDING && porSubir(listOf(fila)) == 1,
            )
        }
    }
}
