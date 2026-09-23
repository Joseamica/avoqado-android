package com.avoqado.pos.inventory.waste.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.inventory.waste.data.BloqueoDeMermaPorPlan
import com.avoqado.pos.inventory.waste.data.CatalogoDeMerma
import com.avoqado.pos.inventory.waste.data.WasteCatalogEntity
import com.avoqado.pos.inventory.waste.data.WasteCatalogItem
import com.avoqado.pos.inventory.waste.data.WasteSyncCoordinator
import com.avoqado.pos.inventory.waste.data.buscarEnCatalogo
import com.avoqado.pos.inventory.waste.domain.TOPE_DE_NOTA
import com.avoqado.pos.inventory.waste.domain.TextosMerma
import com.avoqado.pos.inventory.waste.domain.WasteReason
import com.avoqado.pos.inventory.waste.domain.antiguedadDelCatalogo
import com.avoqado.pos.inventory.waste.domain.normalizarCantidad
import com.avoqado.pos.inventory.waste.domain.recortarNota
import com.avoqado.pos.inventory.waste.domain.textoDeConfirmacion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/** Lo que se le dice al cajero al terminar: título y, si hace falta, por qué todavía no subió. */
data class AvisoDeMerma(val titulo: String, val detalle: String? = null)

/** La pantalla de captura, entera: buscador → cantidad → motivo → nota → confirmación. */
data class EstadoDeCaptura(
    val busqueda: String = "",
    val resultados: List<WasteCatalogEntity> = emptyList(),
    /** «Catálogo de hace N h» — sólo cuando lo que se ve puede no estar al día. */
    val antiguedadDelCatalogo: String? = null,
    val articulo: WasteCatalogEntity? = null,
    val cantidad: String = "",
    val motivo: WasteReason? = null,
    val nota: String = "",
    val sinRed: Boolean = false,
    val bloqueadaPorPlan: Boolean = false,
    /** El texto de la confirmación; no nulo ⇒ el diálogo está abierto. */
    val confirmacion: String? = null,
    val enviando: Boolean = false,
    val aviso: AvisoDeMerma? = null,
    val error: String? = null,
) {
    /**
     * Sin artículo, sin una cantidad que el servidor acepte o sin motivo, no se puede. «Otro»
     * exige nota — y la exige AQUÍ, no con un 422 permanente sobre una fila ya escrita.
     * 🔴 El bloqueo de plan NO entra: es informativo, y apagar el botón trabaría la petición que
     * podría levantarlo (spec §5).
     */
    val puedeConfirmar: Boolean
        get() = articulo != null &&
            normalizarCantidad(cantidad) != null &&
            motivo != null &&
            (motivo?.exigeNota != true || recortarNota(nota).isNotEmpty()) &&
            !enviando
}

/**
 * La entrada «Registrar merma» en «Más», decidida por PERMISO (spec §5).
 *
 * Sin permiso se ve APAGADA y dice a quién pedírselo — nunca desaparece en silencio. Con el
 * plan bloqueado se ve ENCENDIDA con el plan que la incluye: el bloqueo informa, no traba.
 */
data class EntradaDeMerma(val habilitada: Boolean, val subtitulo: String?, val insignia: String?)

fun entradaDeMerma(puedeRegistrar: Boolean, bloqueadaPorPlan: Boolean): EntradaDeMerma = when {
    !puedeRegistrar -> EntradaDeMerma(habilitada = false, subtitulo = TextosMerma.SIN_PERMISO, insignia = null)
    bloqueadaPorPlan -> EntradaDeMerma(habilitada = true, subtitulo = TextosMerma.INCLUIDO_EN_PLAN, insignia = TextosMerma.PLAN)
    else -> EntradaDeMerma(habilitada = true, subtitulo = null, insignia = null)
}

/**
 * La captura de una merma. Espejo exacto de `LogWasteViewModel.swift` de avoqado-ios.
 *
 * Buscar funciona SIN red (el catálogo vive en disco); registrar también (la fila se escribe
 * antes de tocar la red y la sube el motor). Nada de lo que se ve aquí es una existencia: el
 * catálogo del servidor ni siquiera las manda, así que no hay nada que mover de forma optimista.
 */
@HiltViewModel
class LogWasteViewModel @Inject constructor(
    private val catalogo: CatalogoDeMerma,
    private val motor: WasteSyncCoordinator,
    private val bloqueo: BloqueoDeMermaPorPlan,
    private val secureStorage: SecureStorage,
    private val red: ConnectivityMonitor,
) : ViewModel() {

    /** Inyectable para las pruebas: la antigüedad del catálogo se mide contra este reloj. */
    internal var reloj: () -> Long = System::currentTimeMillis

    private val _estado = MutableStateFlow(EstadoDeCaptura())
    val estado: StateFlow<EstadoDeCaptura> = _estado.asStateFlow()

    private var enMemoria: List<WasteCatalogEntity> = emptyList()

    /** El candado del doble toque: el segundo toque llega con el candado puesto y no hace nada. */
    private val registrando = AtomicBoolean(false)

    init {
        viewModelScope.launch {
            combine(red.isConnected, red.isServerReachable) { hayRed, servidor -> hayRed && servidor }
                .collect { conRed -> _estado.update { it.copy(sinRed = !conRed) } }
        }
        viewModelScope.launch {
            bloqueo.venues.collect { venues ->
                _estado.update { it.copy(bloqueadaPorPlan = secureStorage.venueId?.let { v -> v in venues } == true) }
            }
        }
    }

    /**
     * Al abrir: primero lo que hay en disco (sirve sin red); con red, le pregunta al servidor
     * por el plan y baja el catálogo. También es el «Actualizar» del aviso de plan: la petición
     * que puede levantarlo está siempre disponible.
     */
    fun alAbrir(): Job = viewModelScope.launch {
        val venueId = secureStorage.venueId ?: return@launch
        cargar(venueId, recienBajado = false)
        if (!hayRed()) return@launch
        bloqueo.revalidar(venueId)
        if (!bloqueo.estaBloqueado(venueId) && catalogo.refrescarCatalogo(venueId, reloj())) {
            cargar(venueId, recienBajado = true)
        }
    }

    fun buscar(texto: String) = _estado.update {
        it.copy(busqueda = texto, resultados = buscarEnCatalogo(enMemoria, texto, LIMITE))
    }

    fun elegirArticulo(articulo: WasteCatalogEntity) = _estado.update { it.copy(articulo = articulo, error = null) }

    fun quitarArticulo() = _estado.update { it.copy(articulo = null) }

    fun escribirCantidad(texto: String) = _estado.update { it.copy(cantidad = texto) }

    fun elegirMotivo(motivo: WasteReason) = _estado.update { it.copy(motivo = motivo) }

    /** La tecla que pasaría del tope del servidor no entra: nunca se corta la nota en silencio. */
    fun escribirNota(texto: String) = _estado.update { if (texto.length > TOPE_DE_NOTA) it else it.copy(nota = texto) }

    /** 🔴 Spec §5: confirmación SIEMPRE, con el cuánto y el de qué. */
    fun pedirConfirmacion() = _estado.update { e ->
        val articulo = e.articulo
        val cantidad = normalizarCantidad(e.cantidad)
        if (!e.puedeConfirmar || articulo == null || cantidad == null) {
            e
        } else {
            e.copy(confirmacion = textoDeConfirmacion(cantidad, articulo.unit, articulo.name))
        }
    }

    fun cancelarConfirmacion() = _estado.update { it.copy(confirmacion = null) }

    fun avisoVisto() = _estado.update { it.copy(aviso = null) }

    /** Un doble toque en «Confirmar» registra UNA merma, no dos. */
    fun confirmar(): Job = viewModelScope.launch {
        if (!registrando.compareAndSet(false, true)) return@launch
        try {
            registrarLoCapturado()
        } finally {
            registrando.set(false)
        }
    }

    private suspend fun registrarLoCapturado() {
        val e = _estado.value
        val articulo = e.articulo ?: return
        val motivo = e.motivo ?: return
        val venueId = secureStorage.venueId
        val staffId = secureStorage.userId
        if (venueId.isNullOrBlank() || staffId.isNullOrBlank()) {
            _estado.update { it.copy(confirmacion = null, error = TextosMerma.SIN_SESION) }
            return
        }
        _estado.update { it.copy(confirmacion = null, enviando = true, error = null) }
        motor.registrar(
            venueId = venueId,
            staffId = staffId,
            item = WasteCatalogItem(articulo.itemType, articulo.itemId, articulo.name, articulo.sku, articulo.unit),
            cantidadTecleada = e.cantidad,
            motivo = motivo,
            nota = e.nota,
        ).fold(
            onSuccess = {
                // La fila ya está en disco. Lo que cambia es cuándo va a subir, y eso se DICE:
                // sin red o sin plan no es un error, pero tampoco es «registrada» todavía.
                val aviso = when {
                    bloqueo.estaBloqueado(venueId) -> AvisoDeMerma(TextosMerma.GUARDADA, TextosMerma.SE_SUBIRA_SIN_PLAN)
                    !hayRed() -> AvisoDeMerma(TextosMerma.GUARDADA, TextosMerma.SE_SUBIRA_SIN_RED)
                    else -> AvisoDeMerma(TextosMerma.REGISTRADA)
                }
                // Lista para la siguiente: al final del día se registran varias seguidas.
                _estado.update {
                    it.copy(
                        busqueda = "",
                        resultados = buscarEnCatalogo(enMemoria, "", LIMITE),
                        articulo = null,
                        cantidad = "",
                        motivo = null,
                        nota = "",
                        enviando = false,
                        aviso = aviso,
                    )
                }
            },
            onFailure = { fallo -> _estado.update { it.copy(enviando = false, error = fallo.message) } },
        )
    }

    private suspend fun cargar(venueId: String, recienBajado: Boolean) {
        enMemoria = catalogo.catalogo(venueId)
        val actualizadoEn = catalogo.catalogoActualizadoEn(venueId)
        _estado.update {
            it.copy(
                resultados = buscarEnCatalogo(enMemoria, it.busqueda, LIMITE),
                antiguedadDelCatalogo = when {
                    recienBajado -> null
                    actualizadoEn == null -> TextosMerma.SIN_CATALOGO
                    else -> antiguedadDelCatalogo(reloj(), actualizadoEn)
                },
            )
        }
    }

    private fun hayRed(): Boolean = red.isConnected.value && red.isServerReachable.value

    private companion object {
        /** Lo que cabe en una pantalla; el resto se alcanza escribiendo. */
        const val LIMITE = 50
    }
}
