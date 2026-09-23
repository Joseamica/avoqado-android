package com.avoqado.pos.inventory.waste.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.VenueDateTimeFormatter
import com.avoqado.pos.inventory.waste.data.FolioDeHistorial
import com.avoqado.pos.inventory.waste.data.HistorialDeMerma
import com.avoqado.pos.inventory.waste.data.ResultadoDeHistorial
import com.avoqado.pos.inventory.waste.domain.TextosMerma
import com.avoqado.pos.inventory.waste.domain.etiquetaDeUnidad
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

/** Un renglón del historial, como lo lee el cajero. */
data class FilaDeHistorial(
    val id: String,
    val articulo: String,
    /** «2 L · Se echó a perder» */
    val detalle: String,
    val hora: String,
    /** «Registró: Ana» — sólo en la vista del negocio. */
    val autor: String?,
    /** «0.5 L sin existencia» — sólo si algo no pudo descontarse. */
    val sinExistencia: String?,
    val nota: String?,
)

fun filaDeHistorial(folio: FolioDeHistorial, todos: Boolean, hora: (String) -> String): FilaDeHistorial {
    val unidad = etiquetaDeUnidad(folio.unit)
    val faltante = folio.unrecorded.toBigDecimalOrNull()?.takeIf { it.compareTo(BigDecimal.ZERO) > 0 }
    return FilaDeHistorial(
        id = folio.id,
        articulo = folio.name,
        detalle = "${folio.declared} $unidad · ${folio.reasonLabel ?: folio.reasonCode}",
        hora = hora(folio.createdAt),
        autor = folio.reportedByName?.takeIf { todos }?.let { TextosMerma.REGISTRO_DE.replace("{nombre}", it) },
        sinExistencia = faltante?.let { TextosMerma.SIN_EXISTENCIA.replace("{cantidad}", "${folio.unrecorded} $unidad") },
        nota = folio.note,
    )
}

data class EstadoDeHistorial(
    /** Neutro hasta que el servidor diga el alcance: sin red no se presume «Mis mermas» (visto en la D3). */
    val titulo: String = TextosMerma.HISTORIAL_TITULO,
    val filas: List<FilaDeHistorial> = emptyList(),
    val total: Int = 0,
    val cargando: Boolean = false,
    /** Sin red, sin plan o fallo: en palabras del cajero. Lo ya cargado se conserva. */
    val aviso: String? = null,
    val cargado: Boolean = false,
) {
    val hayMas: Boolean get() = filas.size < total
}

/**
 * «Mis mermas» / «Mermas del negocio». El ALCANCE lo decide el servidor (spec 2026-09-23); el aparato
 * sólo pinta. Online-only a propósito. Espejo de `HistorialDeMermaViewModel.swift` de avoqado-ios.
 */
@HiltViewModel
class HistorialDeMermaViewModel @Inject constructor(
    private val fuente: HistorialDeMerma,
    private val secureStorage: SecureStorage,
    private val formato: VenueDateTimeFormatter,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoDeHistorial())
    val estado: StateFlow<EstadoDeHistorial> = _estado.asStateFlow()

    private var pagina = 0
    private var carga: Job? = null

    fun cargar(): Job {
        carga?.cancel()
        pagina = 0
        _estado.value = EstadoDeHistorial(cargando = true)
        return pedir(1).also { carga = it }
    }

    fun cargarMas(): Job {
        if (_estado.value.cargando) return carga ?: Job().also { it.complete() }
        return pedir(pagina + 1).also { carga = it }
    }

    private fun pedir(numero: Int): Job = viewModelScope.launch {
        _estado.update { it.copy(cargando = true, aviso = null) }
        val venueId = secureStorage.venueId
        val resultado = if (venueId == null) ResultadoDeHistorial.Fallo else fuente.historial(venueId, numero)
        _estado.update { actual ->
            when (resultado) {
                is ResultadoDeHistorial.Pagina -> {
                    pagina = resultado.pagina.page
                    val nuevas = resultado.pagina.folios.map { filaDeHistorial(it, resultado.pagina.todos, formato::formatShort) }
                    actual.copy(
                        titulo = if (resultado.pagina.todos) TextosMerma.MERMAS_DEL_NEGOCIO else TextosMerma.MIS_MERMAS,
                        filas = if (numero == 1) nuevas else (actual.filas + nuevas).distinctBy { it.id },
                        total = resultado.pagina.total,
                        cargando = false,
                        cargado = true,
                    )
                }
                ResultadoDeHistorial.SinRed -> actual.copy(cargando = false, aviso = TextosMerma.HISTORIAL_SIN_RED)
                ResultadoDeHistorial.SinPlan -> actual.copy(cargando = false, aviso = TextosMerma.HISTORIAL_SIN_PLAN)
                ResultadoDeHistorial.Fallo -> actual.copy(cargando = false, aviso = TextosMerma.HISTORIAL_FALLO)
            }
        }
    }
}
