package com.avoqado.pos.inventory.waste.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.core.util.VenueDateTimeFormatter
import com.avoqado.pos.inventory.presentation.PriceLabelViewModel.AvisoImpresion
import com.avoqado.pos.inventory.data.model.StockItem
import com.avoqado.pos.inventory.waste.data.BloqueoDeMermaPorPlan
import com.avoqado.pos.inventory.waste.data.CatalogoDeMerma
import com.avoqado.pos.inventory.waste.data.WasteCatalogEntity
import com.avoqado.pos.inventory.waste.data.WasteCatalogItem
import com.avoqado.pos.inventory.waste.data.SubidaDeMerma
import com.avoqado.pos.inventory.waste.data.WasteSyncCoordinator
import com.avoqado.pos.inventory.waste.data.buscarEnCatalogo
import com.avoqado.pos.inventory.waste.domain.TOPE_DE_NOTA
import com.avoqado.pos.inventory.waste.domain.TextosMerma
import com.avoqado.pos.inventory.waste.domain.WasteReason
import com.avoqado.pos.inventory.waste.domain.antiguedadDelCatalogo
import com.avoqado.pos.inventory.waste.domain.normalizarCantidad
import com.avoqado.pos.inventory.waste.domain.recortarNota
import com.avoqado.pos.inventory.waste.domain.textoDeConfirmacion
import com.avoqado.pos.printing.data.ComprobanteDeMerma
import com.avoqado.pos.printing.data.MermaRegistrada
import com.avoqado.pos.printing.data.PrinterService
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
    /** La identidad del aviso: sólo lo cierra el que lo abrió (el temporizador de uno viejo no toca el nuevo). */
    val avisoId: Long = 0,
    /** Lo que el servidor rechazó: letrero fijo hasta que el cajero lo ve (`rechazosVistos`). */
    val rechazos: List<String> = emptyList(),
    val error: String? = null,
    /** La última merma de esta apertura: de ella sale el comprobante impreso (nunca automático). */
    val ultima: MermaRegistrada? = null,
    val imprimiendo: Boolean = false,
    val avisoDeImpresion: AvisoImpresion? = null,
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
/**
 * 🔴 Codex r2: dos capturas seguidas esperan su desenlace a la vez, y el aviso de la vieja podía llegar al
 * final y tapar el de la nueva. Sólo se publica el aviso de la ÚLTIMA captura. Los rechazos no pasan por
 * aquí: tienen su letrero fijo (`EstadoDeCaptura.rechazos`). Espejo de iOS.
 */
fun debePublicarseElAviso(turno: Long, ultimo: Long): Boolean = turno == ultimo

/** El artículo con el que se abre el formulario desde Inventario (su tipo y su id, como en el catálogo). */
data class ArticuloPreseleccionado(val itemType: String, val itemId: String)

/**
 * Desde Inventario: el tipo sale de la lista de la que vino el artículo (productos o insumos). Un producto de
 * receta no tiene existencia propia y no admite merma en esta versión (spec D2): no se ofrece el botón.
 */
fun preseleccionDesdeInventario(item: StockItem, esInsumo: Boolean): ArticuloPreseleccionado? = when {
    esInsumo -> ArticuloPreseleccionado("RAW_MATERIAL", item.id)
    !item.isCountable -> null
    else -> ArticuloPreseleccionado("PRODUCT", item.id)
}

/**
 * 🔴 Codex r3: lo que el servidor RECHAZÓ no es un aviso pasajero (y menos el verde de éxito): es un letrero
 * fijo que junta todas y sólo se va cuando el cajero lo ve. Así ni el éxito de la captura siguiente ni el
 * temporizador de un aviso viejo pueden taparlo. Uno o varios, en buen español. Espejo de iOS.
 */
fun textoDeRechazos(articulos: List<String>): String {
    val plantilla = if (articulos.size == 1) TextosMerma.NO_SE_PUDO_REGISTRAR else TextosMerma.NO_SE_PUDIERON_REGISTRAR
    return plantilla.replace("{articulos}", articulos.joinToString(", ") { "«$it»" })
}

@HiltViewModel
class LogWasteViewModel @Inject constructor(
    private val catalogo: CatalogoDeMerma,
    private val motor: WasteSyncCoordinator,
    private val bloqueo: BloqueoDeMermaPorPlan,
    private val secureStorage: SecureStorage,
    private val red: ConnectivityMonitor,
    private val impresora: PrinterService,
    private val horaDelNegocio: VenueDateTimeFormatter,
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
    /**
     * Cada vez que el formulario se ABRE (desde «Más», desde Inventario o desde la ficha de un artículo).
     *
     * 🔴 Codex r8: el ViewModel de Inventario vive con su pestaña, no con el formulario, así que abrirlo desde OTRO
     * artículo traía la captura abandonada del anterior — y registrarla mermaba el equivocado. Cada apertura empieza
     * limpia. El letrero de rechazos NO se toca: sólo se va cuando el cajero lo ve.
     */
    fun abrirFormulario(idApertura: Long, preseleccion: ArticuloPreseleccionado?): Job {
        // 🔴 Codex r9: volver a MOSTRAR la misma apertura (girar, regresar de otra pestaña) no la abre de nuevo.
        if (idApertura == aperturaMostrada) return alAbrir()
        aperturaMostrada = idApertura
        carga?.cancel()
        preseleccionPendiente = preseleccion
        _estado.update {
            it.copy(
                busqueda = "",
                resultados = buscarEnCatalogo(enMemoria, "", LIMITE),
                articulo = null,
                cantidad = "",
                motivo = null,
                nota = "",
                confirmacion = null,
                error = null,
                aviso = null,
                ultima = null,
                avisoDeImpresion = null,
            )
        }
        return alAbrir().also { carga = it }
    }

    /**
     * Al abrir: primero lo que hay en disco (sirve sin red); con red, le pregunta al servidor por el plan y baja el
     * catálogo. También es el «Actualizar» del aviso de plan: la petición que puede levantarlo está siempre disponible.
     */
    fun alAbrir(): Job = viewModelScope.launch {
        val venueId = secureStorage.venueId ?: return@launch
        cargar(venueId, recienBajado = false)
        preseleccionar()
        if (!hayRed()) return@launch
        bloqueo.revalidar(venueId)
        if (!bloqueo.estaBloqueado(venueId) && catalogo.refrescarCatalogo(venueId, reloj())) {
            cargar(venueId, recienBajado = true)
            preseleccionar()
        }
    }

    /** La carga de la apertura en curso: una apertura nueva cancela la de la anterior. */
    private var carga: Job? = null

    /** La apertura que está en pantalla (la da quien abre el formulario). */
    private var aperturaMostrada: Long? = null

    /**
     * El artículo de la ficha, mientras no se haya aplicado. Se aplica UNA vez (🔴 Codex r8: «Cambiar» mientras bajaba
     * el catálogo se deshacía) y se cancela en cuanto el cajero elige otro.
     */
    private var preseleccionPendiente: ArticuloPreseleccionado? = null

    private fun preseleccionar() {
        val pendiente = preseleccionPendiente ?: return
        val articulo = enMemoria.firstOrNull { it.itemType == pendiente.itemType && it.itemId == pendiente.itemId }
            ?: return
        preseleccionPendiente = null
        _estado.update { it.copy(articulo = articulo, error = null) }
    }

    fun buscar(texto: String) = _estado.update {
        it.copy(busqueda = texto, resultados = buscarEnCatalogo(enMemoria, texto, LIMITE))
    }

    fun elegirArticulo(articulo: WasteCatalogEntity) {
        preseleccionPendiente = null
        _estado.update { it.copy(articulo = articulo, error = null) }
    }

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

    fun avisoVisto(id: Long) = _estado.update { if (it.avisoId == id) it.copy(aviso = null) else it }

    fun rechazosVistos() = _estado.update { it.copy(rechazos = emptyList()) }

    /**
     * 🔴 Codex r5: este ViewModel vive con el menú «Más», no con el formulario, así que cerrar la pantalla no
     * cancela nada por sí solo. Al cerrarla se deja de seguir las capturas: un desenlace que llega con la
     * pantalla cerrada ya no se guarda para cuando se reabra (el cajero lo ve en «Mermas por subir»).
     */
    fun formularioCerrado() {
        apertura++
        val vivos = seguimientos.toList()
        seguimientos.clear()
        vivos.forEach { it.cancel() }
    }

    /** Los seguimientos vivos (`seguirHastaElDesenlace`); se cancelan al cerrar el formulario. */
    private val seguimientos = mutableListOf<Job>()

    /**
     * Cada cierre del formulario la cambia. Una espera que termina en OTRA apertura — cerrado, o cerrado y
     * reabierto (Codex r6: entre medio el cajero pudo descartar esa merma) — no publica ni sigue nada. Abrir no la
     * cambia: toda reapertura viene después de un cierre, y el «Actualizar» del aviso de plan (que llama a
     * `alAbrir`) no debe tragarse el aviso de la captura en curso.
     */
    private var apertura = 0L

    /**
     * Un doble toque en «Confirmar» registra UNA merma, no dos. El candado cubre sólo el registro: la
     * espera del desenlace corre fuera de él, con el formulario ya limpio para la siguiente.
     */
    /** El turno de cada captura: decide qué aviso se publica (`debePublicarseElAviso`). */
    private val turnos = java.util.concurrent.atomic.AtomicLong(0)

    fun confirmar(): Job = viewModelScope.launch {
        if (!registrando.compareAndSet(false, true)) return@launch
        val miApertura = apertura
        val registrada = try {
            registrarLoCapturado(miApertura)
        } finally {
            registrando.set(false)
        } ?: return@launch
        val turno = turnos.incrementAndGet()
        // La espera va FUERA de `update`: dentro, un cambio del formulario la volvería a correr.
        val (aviso, definitivo) = avisoTrasRegistrar(registrada)
        if (apertura != miApertura) return@launch
        when {
            aviso == null -> anotarRechazo(registrada.articulo)
            debePublicarseElAviso(turno, turnos.get()) -> _estado.update { it.copy(aviso = aviso, avisoId = turno) }
        }
        if (!definitivo) seguirHastaElDesenlace(registrada)
    }

    private fun anotarRechazo(articulo: String) =
        _estado.update { it.copy(rechazos = (it.rechazos + articulo).distinct()) }

    /**
     * 🔴 Codex r4: tras el aviso provisional («Se está subiendo», sin red, sin plan) la captura se sigue
     * hasta su desenlace mientras la pantalla esté abierta: un rechazo que llega después también va al
     * letrero. Aparte del `confirmar` para no retenerlo; se cancela solo al cerrar la pantalla.
     */
    private fun seguirHastaElDesenlace(r: Registrada) {
        val seguimiento = viewModelScope.launch {
            val final = runCatching { motor.esperarDesenlaceFinal(r.venueId, r.folio) }.getOrNull()
            if (final == SubidaDeMerma.EN_REVISION) anotarRechazo(r.articulo)
        }
        seguimientos.removeAll { it.isCompleted }
        seguimientos += seguimiento
    }

    /** Lo que se registró: la sucursal, el folio y el nombre del artículo (lo necesita el aviso de rechazo). */
    private data class Registrada(val venueId: String, val folio: String, val articulo: String)

    /**
     * 🔴 Codex r1: «¡Merma registrada!» es una afirmación sobre el SERVIDOR, no sobre el aparato. Sin red o
     * sin plan se dice al momento cuándo subirá; con red se espera (poco) a que el servidor conteste, y si la
     * rechaza se DICE dónde verla — nunca un «registrada» que después resulta falso.
     */
    /**
     * El aviso pasajero de esta captura (`null` si el servidor la rechazó: eso va al letrero fijo) y si
     * ya es el desenlace definitivo; si no lo es, la captura se sigue (`seguirHastaElDesenlace`).
     */
    private suspend fun avisoTrasRegistrar(r: Registrada): Pair<AvisoDeMerma?, Boolean> = when {
        bloqueo.estaBloqueado(r.venueId) -> AvisoDeMerma(TextosMerma.GUARDADA, TextosMerma.SE_SUBIRA_SIN_PLAN) to false
        !hayRed() -> AvisoDeMerma(TextosMerma.GUARDADA, TextosMerma.SE_SUBIRA_SIN_RED) to false
        else -> when (runCatching { motor.esperarSubida(r.folio) }.getOrDefault(SubidaDeMerma.EN_CAMINO)) {
            SubidaDeMerma.SUBIO -> AvisoDeMerma(TextosMerma.REGISTRADA) to true
            SubidaDeMerma.EN_REVISION -> null to true
            SubidaDeMerma.BLOQUEADA -> AvisoDeMerma(TextosMerma.GUARDADA, TextosMerma.SE_SUBIRA_SIN_PLAN) to false
            SubidaDeMerma.EN_CAMINO -> AvisoDeMerma(TextosMerma.GUARDADA, TextosMerma.SUBIENDO) to false
        }
    }

    /** Escribe la merma y deja el formulario listo. `null` si no se registró. */
    private suspend fun registrarLoCapturado(miApertura: Long): Registrada? {
        val e = _estado.value
        val articulo = e.articulo ?: return null
        val motivo = e.motivo ?: return null
        val venueId = secureStorage.venueId
        val staffId = secureStorage.userId
        if (venueId.isNullOrBlank() || staffId.isNullOrBlank()) {
            _estado.update { it.copy(confirmacion = null, error = TextosMerma.SIN_SESION) }
            return null
        }
        _estado.update { it.copy(confirmacion = null, enviando = true, error = null) }
        return motor.registrar(
            venueId = venueId,
            staffId = staffId,
            item = WasteCatalogItem(articulo.itemType, articulo.itemId, articulo.name, articulo.sku, articulo.unit),
            cantidadTecleada = e.cantidad,
            motivo = motivo,
            nota = e.nota,
        ).fold(
            onSuccess = { folio ->
                // 🔴 Codex (r3): si mientras se escribía el cajero cerró y abrió el formulario, esta merma ya no es
                // de la pantalla que se ve: no revive como «Última merma» ni borra lo elegido. Sigue en la cola.
                if (apertura != miApertura) {
                    _estado.update { it.copy(enviando = false) }
                    return@fold Registrada(venueId, folio, articulo.name)
                }
                // La fila ya está en disco. Lista para la siguiente: al final del día se registran varias
                // seguidas. El aviso llega después (`avisoTrasRegistrar`): cuándo sube se DICE.
                _estado.update {
                    it.copy(
                        busqueda = "",
                        resultados = buscarEnCatalogo(enMemoria, "", LIMITE),
                        articulo = null,
                        cantidad = "",
                        motivo = null,
                        nota = "",
                        enviando = false,
                        ultima = MermaRegistrada(
                            folio = folio,
                            articulo = articulo.name,
                            cantidad = normalizarCantidad(e.cantidad) ?: e.cantidad,
                            unit = articulo.unit,
                            motivo = motivo.etiqueta,
                            nota = recortarNota(e.nota).takeIf { n -> n.isNotEmpty() },
                            registro = listOfNotNull(secureStorage.userFirstName, secureStorage.userLastName)
                                .joinToString(" ").trim().takeIf { n -> n.isNotEmpty() },
                            creadaEn = reloj(),
                        ),
                    )
                }
                Registrada(venueId, folio, articulo.name)
            },
            onFailure = { fallo ->
                _estado.update { if (apertura == miApertura) it.copy(enviando = false, error = fallo.message) else it.copy(enviando = false) }
                null
            },
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

    /**
     * El comprobante de la última merma, para firmar. Lo pide el cajero (nunca automático). Pregunta a la
     * cola AHORA si ya subió: sin red dice «Pendiente de subir», y una que el servidor rechazó no se imprime.
     */
    fun imprimirComprobante(): Job? {
        val m = _estado.value.ultima ?: return null
        if (_estado.value.imprimiendo) return null
        _estado.update { it.copy(imprimiendo = true, avisoDeImpresion = null) }
        return viewModelScope.launch {
            val subida = motor.subida(m.folio)
            val aviso = if (subida == SubidaDeMerma.EN_REVISION) {
                AvisoImpresion.Error(TextosMerma.COMPROBANTE_NO_REGISTRADA)
            } else {
                val c = ComprobanteDeMerma.desde(
                    m,
                    negocio = secureStorage.venueDisplayName,
                    fecha = horaDelNegocio.formatDateTime(m.creadaEn),
                    pendiente = subida != SubidaDeMerma.SUBIO,
                )
                when (val r = impresora.printWasteReceipt(c)) {
                    is PrinterService.PrintOutcome.Printed -> AvisoImpresion.Hecho(TextosMerma.COMPROBANTE_IMPRESO, "Folio ${c.folio}")
                    PrinterService.PrintOutcome.NoPrinter -> AvisoImpresion.Error(TextosMerma.SIN_IMPRESORA)
                    PrinterService.PrintOutcome.OutOfPaper -> AvisoImpresion.Error(TextosMerma.SIN_PAPEL)
                    is PrinterService.PrintOutcome.Failed ->
                        AvisoImpresion.Error(TextosMerma.NO_SE_PUDO_IMPRIMIR.replace("{motivo}", r.reason))
                }
            }
            _estado.update { it.copy(imprimiendo = false, avisoDeImpresion = aviso) }
        }
    }

    fun avisoDeImpresionVisto() = _estado.update { it.copy(avisoDeImpresion = null) }

    private fun hayRed(): Boolean = red.isConnected.value && red.isServerReachable.value

    private companion object {
        /** Lo que cabe en una pantalla; el resto se alcanza escribiendo. */
        const val LIMITE = 50
    }
}
