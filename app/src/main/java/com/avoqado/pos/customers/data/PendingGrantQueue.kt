package com.avoqado.pos.customers.data

import android.content.Context
import android.util.Log
import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.articles.data.model.CreditPack
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.core.util.aCentavos
import com.avoqado.pos.core.util.formatMoneyFromCents
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.domain.CardChargeDecision
import com.avoqado.pos.payment.domain.CardChargeOutcome
import com.avoqado.pos.payment.domain.ProbeDecision
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Un intento de cobro de la venta. Su identidad se escribe ANTES de que el cobro salga: es lo único que permite preguntar después. */
@Serializable
data class IntentoDeCobro(val tipo: Tipo, val id: String) {
    /** [id] es la llave del cobro en [EFECTIVO] y [OTRA_FORMA] (método manual o tipo del catálogo), y el `requestId` en [TARJETA]. */
    enum class Tipo { EFECTIVO, OTRA_FORMA, TARJETA }
}

/** Lo que dice la evidencia de un cobro. Nunca se concluye [NO] de una ignorancia. */
enum class Cobro { SI, NO, NO_SE_SABE }

/** Un SÍ basta (el dinero entró); sólo se borra si todos dicen NO. Sin intentos no salió ningún cobro: NO. */
internal fun decidirCobro(evidencias: List<Cobro>): Cobro = when {
    Cobro.SI in evidencias -> Cobro.SI
    Cobro.NO_SE_SABE in evidencias -> Cobro.NO_SE_SABE
    else -> Cobro.NO
}

/** Los desenlaces de mandar una entrega. Un booleano mezclaba «el servidor ya la tiene» con «la rechazó». */
enum class DesenlaceDeEntrega { ENTREGADA, SIN_RED, REINTENTAR, SESION_VENCIDA, RECHAZADA }

/** Por código, nunca por el texto. 401 corta la pasada sin gastar intento; 408 y 429 son 4xx de número y transitorios de significado. */
internal fun clasificarEntrega(code: Int?): DesenlaceDeEntrega = when {
    code == null -> DesenlaceDeEntrega.SIN_RED
    code in 200..299 -> DesenlaceDeEntrega.ENTREGADA
    code == 401 -> DesenlaceDeEntrega.SESION_VENCIDA
    code == 408 || code == 429 || code >= 500 -> DesenlaceDeEntrega.REINTENTAR
    else -> DesenlaceDeEntrega.RECHAZADA
}

/** Un paquete de la venta, una por pieza cobrada. [linea] es estable mientras la venta siga en el carrito. */
data class LineaDePaquete(val linea: String, val packId: String, val nombre: String?, val precioCents: Int?)

/**
 * Lo que se debe entregar. [id] es la llave de la entrega (`deliveryKey`): con ella el servidor nunca crea dos compras.
 * Las filas que dejó la app de antes no traen estado: ya estaban cobradas, y su `id` se vuelve su llave.
 */
@Serializable
data class PendingGrant(
    val id: String,
    val venueId: String,
    val packId: String,
    val customerId: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val estado: Estado = Estado.POR_ENTREGAR,
    /** La venta del carrito (`identidadDeVenta`). Null = ficha del cliente o fila de la app de antes. */
    val venta: String? = null,
    val linea: String? = null,
    val intentos: List<IntentoDeCobro> = emptyList(),
    val nombre: String? = null,
    val precioCents: Int? = null,
    val clienteNombre: String? = null,
    /** La foto de lo vendido, firmada por el servidor: se devuelve tal cual al entregar. Null = se entrega el paquete vigente. */
    val saleProof: String? = null,
    val motivo: String? = null,
    /** Desde cuándo, CON RED, no se sabe si se cobró. El tiempo sin red no cuenta. */
    val dudaDesde: Long? = null,
) {
    enum class Estado {
        /** Aún no consta el cobro. */
        POR_COBRAR,
        /** Consta el cobro: la cola la entrega sola. */
        POR_ENTREGAR,
        /** El servidor la rechazó de verdad: la ve una persona. Ya no se reintenta sola. */
        RECHAZADA,
        /** Media hora con red sin poder saber si se cobró: decide una persona. La evidencia se sigue consultando. */
        EN_DUDA,
    }

    val porRevisar: Boolean get() = estado == Estado.RECHAZADA || estado == Estado.EN_DUDA
}

// Cuarentena (los mismos textos para cuando le toque a iOS).
const val PAQUETE_EN_DUDA = "No se pudo confirmar si este cobro se hizo. Revisa la terminal o la caja."

fun tituloDePaquete(fila: PendingGrant): String = listOfNotNull(
    "Paquete",
    fila.nombre,
    fila.clienteNombre,
    fila.precioCents?.let(::formatMoneyFromCents),
).joinToString(" · ")

/**
 * 🔴 D4 (5-nov, promesa en `docs/audit/2026-11-05-paquete-pos/`): la obligación de entregar un paquete vendido.
 *
 * Es del dinero que ya entró o está por entrar, y se escribe con `commit()`:
 * - **Antes de abrir el cobro**, con su llave y lo vendido ([armar]). Si la app muere después, la fila sigue.
 * - **Antes de cada intento de cobro**, la identidad del intento ([anotarIntento]). Sin intentos no salió ningún cobro.
 * - **Al confirmarse el cobro**, pasa a por entregar ([marcarCobrada]).
 *
 * Lo que la app no llegó a marcar lo decide su evidencia ([drenar]): la fila del efectivo en este aparato, el servidor por la llave
 * del cobro y el estado durable de la tarjeta. Si se cobró se entrega, si no se cobró se borra, y si no se puede saber lo decide
 * una persona. Mientras la venta sigue en pantalla ([ventaViva]) un NO no borra nada: la cajera puede reintentar con otro método.
 *
 * Toda escritura relee el disco bajo [candado], sin red de por medio: lo borrado no revive.
 * Mandar dos veces es inofensivo: la llave hace que el servidor conteste la misma compra.
 */
@Singleton
class PendingGrantQueue @Inject constructor(
    @ApplicationContext context: Context,
    private val articlesRepository: ArticlesRepository,
    private val secureStorage: SecureStorage,
    private val pagos: PendingPaymentDao,
    private val terminal: TerminalPaymentService,
    private val connectivityMonitor: ConnectivityMonitor,
) {
    private val prefs = context.getSharedPreferences("pending_pack_grants", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val candado = Any()
    private val drenando = Mutex()

    /** Seam de pruebas. */
    @androidx.annotation.VisibleForTesting
    internal var io: CoroutineDispatcher = Dispatchers.IO

    @androidx.annotation.VisibleForTesting
    internal var reloj: () -> Long = System::currentTimeMillis

    /** La venta que está en pantalla. Sólo memoria: si el proceso muere, ninguna lo está. */
    @Volatile private var ventaViva: String? = null

    private val _filas = MutableStateFlow(leer())
    val filas: StateFlow<List<PendingGrant>> = _filas.asStateFlow()

    private val sucursal = MutableStateFlow(secureStorage.venueId)

    /** `AppState` la llama cada vez que cambia la sesión: los contadores y la cuarentena siguen a la sucursal. */
    fun alCambiarLaSesion() {
        sucursal.value = secureStorage.venueId
    }

    /** Las de esta sucursal que una persona tiene que ver. */
    val porRevisarEnLaSucursal: Flow<List<PendingGrant>> =
        combine(filas, sucursal) { fs, venue -> fs.filter { it.porRevisar && it.venueId == venue } }

    val porRevisar: Flow<Int> = porRevisarEnLaSucursal.map { it.size }

    /** Cobradas que esperan subir: suman en «por sincronizar». */
    val porEntregar: Flow<Int> =
        combine(filas, sucursal) { fs, venue -> fs.count { it.estado == PendingGrant.Estado.POR_ENTREGAR && it.venueId == venue } }

    // MARK: - El almacén

    private fun leer(): List<PendingGrant> {
        val raw = prefs.getString(LLAVE, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<PendingGrant>>(raw) }.getOrDefault(emptyList())
    }

    /** 🔴 `commit()` y no `apply()`: esto va antes de cobrar. Android cambia la memoria antes de saber si el disco aceptó: si no, se regresa. */
    private fun escribir(filas: List<PendingGrant>): Boolean {
        val anterior = prefs.getString(LLAVE, null)
        val ok = prefs.edit().putString(LLAVE, json.encodeToString(filas)).commit()
        if (!ok) prefs.edit().putString(LLAVE, anterior).commit()
        _filas.value = leer()
        return ok
    }

    private fun mutar(cambio: (List<PendingGrant>) -> List<PendingGrant>): Boolean = synchronized(candado) {
        val antes = leer()
        val despues = cambio(antes)
        despues == antes || escribir(despues)
    }

    private fun cambiar(id: String, cambio: (PendingGrant) -> PendingGrant) {
        mutar { fs -> fs.map { if (it.id == id) cambio(it) else it } }
    }

    private fun borrar(id: String): Boolean = mutar { fs -> fs.filterNot { it.id == id } }

    @androidx.annotation.VisibleForTesting
    internal fun borrarParaPruebas(id: String) { borrar(id) }

    // MARK: - Lo que escribe el cobro

    /**
     * Se abre el cobro de [venta]: su obligación queda en disco. false = el disco no aceptó y el cobro no debe abrirse.
     *
     * Volver a abrir la MISMA venta conserva la llave y los intentos de cada línea: dos cobros de una venta entregan un solo paquete.
     * Lo que nunca intentó cobrar (otra venta, o un paquete que ya se quitó del carrito) se borra: no hubo cobro. Lo que sí lo intentó
     * se queda y lo decide su evidencia. Sin [lineas] la venta no vende paquetes y nada queda en pantalla.
     */
    fun armar(venta: String?, lineas: List<LineaDePaquete>, customerId: String?, clienteNombre: String?): Boolean {
        val venue = secureStorage.venueId
        val catalogo = articlesRepository.creditPacks.value
        val hayQueArmar = venta != null && customerId != null && venue != null && lineas.isNotEmpty()
        val ok = mutar { fs ->
            val quedan = fs.filterNot { f ->
                f.estado == PendingGrant.Estado.POR_COBRAR && f.intentos.isEmpty() &&
                    (f.venta != venta || !hayQueArmar || lineas.none { it.linea == f.linea })
            }.toMutableList()
            if (hayQueArmar) {
                for (l in lineas) {
                    val foto = catalogo.firstOrNull { it.id == l.packId }?.saleProof
                    val i = quedan.indexOfFirst {
                        it.venta == venta && it.linea == l.linea &&
                            (it.estado == PendingGrant.Estado.POR_COBRAR || it.estado == PendingGrant.Estado.EN_DUDA)
                    }
                    if (i >= 0) {
                        quedan[i] = quedan[i].copy(
                            estado = PendingGrant.Estado.POR_COBRAR, packId = l.packId, customerId = customerId!!, clienteNombre = clienteNombre,
                            nombre = l.nombre, precioCents = l.precioCents, saleProof = foto, motivo = null, dudaDesde = null,
                        )
                    } else {
                        quedan += PendingGrant(
                            id = java.util.UUID.randomUUID().toString(),
                            venueId = venue!!,
                            packId = l.packId,
                            customerId = customerId!!,
                            createdAt = reloj(),
                            estado = PendingGrant.Estado.POR_COBRAR,
                            venta = venta,
                            linea = l.linea,
                            nombre = l.nombre,
                            precioCents = l.precioCents,
                            clienteNombre = clienteNombre,
                            saleProof = foto,
                        )
                    }
                }
            }
            quedan
        }
        if (ok) ventaViva = venta.takeIf { hayQueArmar }
        return ok
    }

    /** Sale un intento de cobro de [venta]. false = el disco no aceptó y el cobro no debe salir. Sin paquetes en esa venta no hace nada. */
    fun anotarIntento(venta: String, intento: IntentoDeCobro): Boolean = mutar { fs ->
        fs.map { f ->
            if (f.venta == venta && f.estado != PendingGrant.Estado.RECHAZADA && intento !in f.intentos) f.copy(intentos = f.intentos + intento) else f
        }
    }

    /** Consta que ese intento nunca nació (el servidor lo rechazó sin crearlo): no hay por qué preguntar por él; su 404 sería una duda falsa. */
    fun quitarIntento(venta: String, intento: IntentoDeCobro) {
        mutar { fs -> fs.map { if (it.venta == venta) it.copy(intentos = it.intentos - intento) else it } }
    }

    /** Consta el cobro de [venta]: lo suyo pasa a por entregar. */
    fun marcarCobrada(venta: String): Boolean {
        if (ventaViva == venta) ventaViva = null
        return mutar { fs ->
            fs.map { f ->
                if (f.venta == venta && (f.estado == PendingGrant.Estado.POR_COBRAR || f.estado == PendingGrant.Estado.EN_DUDA)) {
                    f.copy(estado = PendingGrant.Estado.POR_ENTREGAR, motivo = null)
                } else {
                    f
                }
            }
        }
    }

    /**
     * La ficha del cliente: la cajera ya cobró a mano («Cobra el pago antes de confirmar»), así que nace por entregar. Se escribe
     * antes de la red: si se pierde la respuesta, se reintenta con la misma llave. null = el disco no aceptó y no se vende.
     */
    fun guardarVentaDeFicha(pack: CreditPack, customerId: String, clienteNombre: String?): PendingGrant? {
        val venue = secureStorage.venueId ?: return null
        val fila = PendingGrant(
            id = java.util.UUID.randomUUID().toString(),
            venueId = venue,
            packId = pack.id,
            customerId = customerId,
            createdAt = reloj(),
            nombre = pack.name,
            precioCents = pack.price.aCentavos(),
            clienteNombre = clienteNombre,
            saleProof = pack.saleProof,
        )
        return fila.takeIf { mutar { fs -> fs + fila } }
    }

    // MARK: - La entrega

    /** Manda la fila tal como está en disco y deja escrito su desenlace. Rechazada = queda con su motivo, para una persona. */
    private suspend fun enviar(fila: PendingGrant, background: Boolean = true): DesenlaceDeEntrega {
        val r = articlesRepository.sellPackToCustomer(fila.packId, fila.customerId, background, fila.id, fila.saleProof)
        val desenlace = clasificarEntrega(r.code)
        when (desenlace) {
            DesenlaceDeEntrega.ENTREGADA -> {
                borrar(fila.id)
                Log.d(TAG, "Entregado ${fila.packId} → ${fila.customerId}")
            }
            DesenlaceDeEntrega.RECHAZADA -> cambiar(fila.id) {
                it.copy(estado = PendingGrant.Estado.RECHAZADA, attempts = it.attempts + 1, motivo = r.mensaje ?: "El servidor rechazó la entrega (${r.code})")
            }
            DesenlaceDeEntrega.SIN_RED, DesenlaceDeEntrega.REINTENTAR -> cambiar(fila.id) { it.copy(attempts = it.attempts + 1) }
            DesenlaceDeEntrega.SESION_VENCIDA -> Unit
        }
        return desenlace
    }

    /** La venta se confirmó con la cajera enfrente: se intenta ya y se le dice cómo quedó. */
    suspend fun entregarVenta(venta: String): List<DesenlaceDeEntrega> = withContext(io) {
        // La ruta sale de la sesión de ahora: lo de otra sucursal espera a su sucursal.
        filas.value.filter { it.venta == venta && it.estado == PendingGrant.Estado.POR_ENTREGAR && it.venueId == secureStorage.venueId }.map { enviar(it) }
    }

    /** La venta de la ficha: la pidió un toque, así que su 403 sí puede salir como modal. */
    suspend fun entregarDeFicha(id: String): DesenlaceDeEntrega? = withContext(io) {
        filas.value.firstOrNull { it.id == id }?.let { enviar(it, background = false) }
    }

    /** La ficha ya le enseñó el rechazo a quien lo tocó, y lo tiene enfrente: no hace falta que vuelva a salir en la cuarentena. */
    fun olvidarRechazoDeFicha(id: String): Boolean = borrar(id)

    /** Qué dijo el servidor de esa fila, tal como quedó. */
    fun motivoDe(id: String): String? = filas.value.firstOrNull { it.id == id }?.motivo

    /** «Reintentar» de la cuarentena: la misma llave. null = ya no está, es de otra sucursal o no está rechazada. */
    suspend fun reintentar(id: String): DesenlaceDeEntrega? = withContext(io) {
        val fila = filas.value.firstOrNull { it.id == id && it.estado == PendingGrant.Estado.RECHAZADA && it.venueId == secureStorage.venueId }
            ?: return@withContext null
        cambiar(id) { it.copy(estado = PendingGrant.Estado.POR_ENTREGAR, motivo = null) }
        enviar(fila)
    }

    /** «Sí se cobró» de la cuarentena. */
    suspend fun siSeCobro(id: String): DesenlaceDeEntrega? = withContext(io) {
        val fila = filas.value.firstOrNull { it.id == id && it.estado == PendingGrant.Estado.EN_DUDA && it.venueId == secureStorage.venueId }
            ?: return@withContext null
        cambiar(id) { it.copy(estado = PendingGrant.Estado.POR_ENTREGAR, motivo = null) }
        enviar(fila)
    }

    /** «Descartar» o «No se cobró» de la cuarentena, ya confirmado por una persona. Sólo lo que está por revisar, de esta sucursal. */
    fun descartar(id: String): Boolean {
        if (filas.value.none { it.id == id && it.porRevisar && it.venueId == secureStorage.venueId }) return false
        return borrar(id)
    }

    // MARK: - Cómo se sabe si el cobro sucedió

    private suspend fun evidencia(intento: IntentoDeCobro, venueId: String, conRed: Boolean): Cobro = when (intento.tipo) {
        IntentoDeCobro.Tipo.TARJETA -> {
            if (!conRed) {
                Cobro.NO_SE_SABE
            } else {
                val probe = terminal.consultarEstado(intento.id, venueId, enSegundoPlano = true).probe
                when (val d = CardChargeDecision.decide(probe, isFinalAttempt = true)) {
                    is ProbeDecision.Resolved -> when (d.outcome) {
                        is CardChargeOutcome.Charged -> Cobro.SI
                        is CardChargeOutcome.NotCharged -> Cobro.NO
                        is CardChargeOutcome.Undetermined -> Cobro.NO_SE_SABE
                    }
                    ProbeDecision.KeepPolling -> Cobro.NO_SE_SABE
                }
            }
        }
        IntentoDeCobro.Tipo.EFECTIVO, IntentoDeCobro.Tipo.OTRA_FORMA -> {
            val fila = pagos.findForVenue(venueId, intento.id)
            when {
                // Apartado sin resolver: lo da por cobrado el barrido de la cola o lo decide una persona en «Cobro interrumpido».
                fila?.syncStatus == PaymentSyncStatus.EN_VUELO.name -> Cobro.NO_SE_SABE
                // En la cola, subido o rechazado al subir: el billete ya está en el cajón.
                fila != null -> Cobro.SI
                !conRed -> Cobro.NO_SE_SABE
                else -> when (articlesRepository.cobroEnEfectivoPorLlave(venueId, intento.id)) {
                    true -> Cobro.SI
                    // Sin fila, sin cobro con esa llave y sin nadie que la reintente: no se cobró. `by-key` sólo prueba efectivo puro.
                    false -> if (intento.tipo == IntentoDeCobro.Tipo.EFECTIVO) Cobro.NO else Cobro.NO_SE_SABE
                    null -> Cobro.NO_SE_SABE
                }
            }
        }
    }

    private suspend fun resolverCobros(venue: String, conRed: Boolean) {
        val porResolver = filas.value.filter {
            it.venueId == venue && (it.estado == PendingGrant.Estado.POR_COBRAR || it.estado == PendingGrant.Estado.EN_DUDA)
        }
        for (foto in porResolver) {
            val viva = foto.venta != null && foto.venta == ventaViva
            if (viva && foto.intentos.isEmpty()) continue
            val cobro = decidirCobro(
                foto.intentos.map {
                    try {
                        evidencia(it, venue, conRed)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "No se pudo leer la evidencia del cobro ${it.id}: ${e.message}")
                        Cobro.NO_SE_SABE
                    }
                },
            )
            when (cobro) {
                Cobro.SI -> cambiar(foto.id) { it.copy(estado = PendingGrant.Estado.POR_ENTREGAR, motivo = null) }
                // Un intento nuevo pudo anotarse mientras se preguntaba: esa evidencia aún no se vio.
                Cobro.NO -> if (foto.venta == null || foto.venta != ventaViva) {
                    mutar { fs -> fs.filterNot { it.id == foto.id && it.intentos == foto.intentos } }
                }
                Cobro.NO_SE_SABE -> if (conRed && !viva) anotarDuda(foto.id)
            }
        }
    }

    private fun anotarDuda(id: String) {
        val ahora = reloj()
        cambiar(id) { f ->
            val desde = f.dudaDesde ?: ahora
            if (f.estado == PendingGrant.Estado.POR_COBRAR && ahora - desde > DUDA_MS) {
                f.copy(estado = PendingGrant.Estado.EN_DUDA, motivo = PAQUETE_EN_DUDA, dudaDesde = desde)
            } else {
                f.copy(dudaDesde = desde)
            }
        }
    }

    /**
     * Una pasada, sobre las filas de la sucursal de la sesión, viejas primero. Sin red se resuelve lo que se sabe en el aparato y no se
     * manda nada. Sin respuesta o con la sesión vencida, la pasada se corta.
     */
    suspend fun drenar() {
        if (!drenando.tryLock()) return
        try {
            withContext(io) {
                val venue = secureStorage.venueId ?: return@withContext
                val conRed = connectivityMonitor.isConnected.value && connectivityMonitor.isServerReachable.value
                resolverCobros(venue, conRed)
                if (!conRed) return@withContext
                for (foto in filas.value.filter { it.venueId == venue && it.estado == PendingGrant.Estado.POR_ENTREGAR }.sortedBy { it.createdAt }) {
                    if (secureStorage.venueId != venue) return@withContext
                    val d = enviar(foto)
                    if (d == DesenlaceDeEntrega.SIN_RED || d == DesenlaceDeEntrega.SESION_VENCIDA) return@withContext
                }
            }
        } finally {
            drenando.unlock()
        }
    }

    // MARK: - Disparadores (arranque, red de vuelta y un reloj: el apartado de efectivo se resuelve solo poco después de arrancar)

    private var trabajo: Job? = null

    @Synchronized
    fun start() {
        if (trabajo != null) return
        trabajo = CoroutineScope(SupervisorJob() + io).launch {
            launch { drenar() }
            launch {
                while (isActive) {
                    delay(CADA_MS)
                    if (_filas.value.isNotEmpty()) drenar()
                }
            }
            PaymentSyncService.alReconectar(
                combine(connectivityMonitor.isConnected, connectivityMonitor.isServerReachable) { red, servidor -> red && servidor },
            ) { drenar() }
        }
    }

    @Synchronized
    fun stop() {
        trabajo?.cancel()
        trabajo = null
    }

    companion object {
        private const val TAG = "🎟️"
        private const val LLAVE = "queue"
        private const val CADA_MS = 60_000L

        /** Media hora con red sin poder saber: ya no se espera en silencio. */
        internal const val DUDA_MS = 30L * 60 * 1000
    }
}
