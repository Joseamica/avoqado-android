package com.avoqado.pos.payment.data

import android.content.Context
import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.core.util.formatMoneyFromCents
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.model.CambioDePrecio
import com.avoqado.pos.payment.data.model.OrderData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Qué pasó al mandar una cuenta de «Pagar después» ([OrderRepository.leerPagarDespues]). */
sealed interface DesenlacePagarDespues {
    /** 2xx con `order.id`: la cuenta EXISTE. [totalCents] null = no se pudo leer su importe. */
    data class Creada(val orderId: String, val totalCents: Int?, val orden: OrderData? = null,
        val motivoRevision: String? = null) : DesenlacePagarDespues
    /** No hubo respuesta (red caída, plazo vencido): la pasada se corta. */
    data object SinRespuesta : DesenlacePagarDespues
    /** Una respuesta que no dice nada de la cuenta (5xx, 408/429, intermediario, 2xx sin `order.id`): sigue pendiente. */
    data class Pendiente(val motivo: String) : DesenlacePagarDespues
    /** 401: la pasada se corta sin gastar intento. */
    data class SesionVencida(val mensaje: String) : DesenlacePagarDespues
    /** Un 4xx de la API: el servidor NO la creó. */
    data class Rechazada(val code: Int, val mensaje: String, val cambios: List<CambioDePrecio>) : DesenlacePagarDespues
}

/**
 * Una cuenta de «Pagar después» guardada en el aparato ANTES de mandarla. [id] es la llave de la VENTA (`pay-later:<uuid>`):
 * viaja como `externalId` en el intento en línea y en cada reenvío, así el servidor devuelve la misma cuenta y nunca hay dos.
 */
@Serializable
data class PayLaterRow(
    val id: String,
    val venueId: String,
    val staffId: String,
    val customerId: String,
    val customerName: String,
    /** Lo que vio el cliente. Un 2xx por otro importe queda en [Estado.REVISAR]. */
    val totalEsperadoCents: Int,
    /** El POST de REENVÍO: `isOfflineReplay` y `createdAtLocal` (la venta ya ocurrió, el servidor respeta su precio). */
    val cuerpo: String,
    /** La copia de «Carritos guardados» de la que salió: se retira cuando la cuenta va a existir (R3-1). */
    val carritoGuardadoId: String? = null,
    val creadaEn: Long,
    val estado: Estado = Estado.EN_VUELO,
    val intentos: Int = 0,
    val motivo: String? = null,
    val orderId: String? = null,
    /** [Estado.REVISAR]: lo que registró el servidor; null = no se pudo leer. */
    val totalRegistradoCents: Int? = null,
    /** [Estado.RECUPERAR]: la venta entera, como se guarda en «Carritos guardados» (con su llave). */
    val venta: com.avoqado.pos.pos.data.model.SavedCart? = null,
) {
    /**
     * - [RECUPERAR] (Codex ANDROID r1 A-4): el servidor NO la creó en línea y la venta no se pudo poner a salvo (carrito ocupado y
     *   «Carritos guardados» sin escribir). Nunca se manda: como venta sin red se saltaría el rechazo de precio. Lleva la venta
     *   ([venta]) y pasa sola a «Carritos guardados» en cuanto se pueda escribir.
     * - [RETIRO_PENDIENTE] (A-5): la cuenta ya existe pero su copia guardada no se pudo retirar. Local: no se manda ni se cuenta;
     *   se borra cuando el retiro se confirma. Mientras, esa copia no se restaura.
     */
    enum class Estado { EN_VUELO, PENDIENTE, RECHAZADA, REVISAR, RECUPERAR, RETIRO_PENDIENTE }

    val porSubir: Boolean get() = estado == Estado.EN_VUELO || estado == Estado.PENDIENTE

    /** Lo que una persona ve en la cuarentena. */
    val paraRevisar: Boolean get() = estado == Estado.RECHAZADA || estado == Estado.REVISAR || estado == Estado.RECUPERAR

    /** La cuenta existe o va a existir: su copia guardada no se puede vender otra vez. */
    val ligaSuCopia: Boolean get() = porSubir || estado == Estado.REVISAR || estado == Estado.RETIRO_PENDIENTE
}

// Cuarentena (mismos textos en iOS).
const val PAGAR_DESPUES_IMPORTE_DESCONOCIDO = "Se registró, pero no se pudo leer su importe"

fun tituloDePagarDespues(fila: PayLaterRow): String =
    "Pagar después · ${fila.customerName} · ${formatMoneyFromCents(fila.totalEsperadoCents)}"

fun detalleDePagarDespues(fila: PayLaterRow): String? = when (fila.estado) {
    PayLaterRow.Estado.REVISAR -> fila.motivo ?: fila.totalRegistradoCents
        ?.let { "Se registró por ${formatMoneyFromCents(it)}, no por ${formatMoneyFromCents(fila.totalEsperadoCents)}" }
        ?: PAGAR_DESPUES_IMPORTE_DESCONOCIDO
    else -> fila.motivo
}

/**
 * 🔴 «Pagar después» sin red (27-oct; promesa y diseño v4 en `docs/audit/2026-10-27-pagar-despues-sin-red/`).
 *
 * Fila PROPIA, a propósito: la del efectivo (`pending_payments`) suma su importe al cajón y su reenvío SALDA la cuenta; ésta
 * sólo la crea. Una llave por fila en `SharedPreferences` con `commit()`, y toda mutación bajo [candado]: lee lo de disco,
 * cambia ESTA fila por id y escribe, sin red de por medio (Codex r1 #6: leer la lista → red → reescribirla perdía filas). Lo
 * borrado no revive: un éxito gana a un fallo tardío.
 *
 * La marca [enCurso] es de MEMORIA (R7): la fila EN_VUELO de un intento vivo no se toca; la de un proceso que murió (o de un
 * intento cancelado) no tiene marca y el reenvío la toma — la llave hace seguro mandarla otra vez.
 */
@Singleton
class PayLaterQueue @Inject constructor(
    @ApplicationContext context: Context,
    private val orderRepository: OrderRepository,
    private val secureStorage: SecureStorage,
    private val savedCartsRepository: SavedCartsRepository,
    private val connectivityMonitor: ConnectivityMonitor,
) {
    private val prefs = context.getSharedPreferences("pagar_despues_pendientes", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val candado = Mutex()
    private val drenando = Mutex()
    private val enCurso = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Seam de pruebas: dónde se toca el disco. */
    @androidx.annotation.VisibleForTesting
    internal var io: CoroutineDispatcher = Dispatchers.IO

    private val _filas = MutableStateFlow<List<PayLaterRow>>(emptyList())
    val filas: StateFlow<List<PayLaterRow>> = _filas.asStateFlow()

    /** La sucursal de la sesión. La pone al día [alCambiarLaSesion] (Codex A-8: leerla sólo al cambiar una fila se quedaba vieja). */
    private val sucursal = MutableStateFlow(secureStorage.venueId)

    /** `AppState` la llama cada vez que cambia la sesión (entrar, salir, cambiar de sucursal). */
    fun alCambiarLaSesion() {
        sucursal.value = secureStorage.venueId
    }

    /** Las de la sucursal de la sesión que una persona tiene que ver (cuarentena). */
    val porRevisarEnLaSucursal: Flow<List<PayLaterRow>> =
        combine(filas, sucursal) { fs, venue -> fs.filter { it.paraRevisar && it.venueId == venue } }

    /** Las de la sucursal de la sesión que esperan subir: suman en «por sincronizar». */
    val porSubir: Flow<Int> = combine(filas, sucursal) { fs, venue -> fs.count { it.porSubir && it.venueId == venue } }

    val porRevisar: Flow<Int> = porRevisarEnLaSucursal.map { it.size }

    init {
        // R2-3/R3-1: al ARRANCAR, sólo local y antes de que nadie pueda restaurarlas, se retiran las copias guardadas de las
        // cuentas que existen o van a existir ([PayLaterRow.ligaSuCopia]; EN_VUELO = la app murió a media petición). Lo que no
        // se pueda retirar sigue ligado (no se restaura) y se reintenta en cada pasada ([mantenimientoLocal]).
        val vivas = leerTodas()
        vivas.filter { it.ligaSuCopia }.forEach { retirarCopia(it.carritoGuardadoId) }
        _filas.value = vivas
    }

    // MARK: - El almacén

    private fun llave(id: String) = "fila:$id"

    private fun leerTodas(): List<PayLaterRow> = prefs.all
        .filterKeys { it.startsWith("fila:") }
        .values.mapNotNull { raw -> (raw as? String)?.let { runCatching { json.decodeFromString<PayLaterRow>(it) }.getOrNull() } }
        .sortedBy { it.creadaEn }

    private fun cambiarClave(id: String, valor: String?): Boolean {
        val editor = prefs.edit()
        if (valor == null) editor.remove(llave(id)) else editor.putString(llave(id), valor)
        return editor.commit()
    }

    /**
     * 🔴 Codex A-2: Android cambia la MEMORIA de las preferencias antes de saber si el disco aceptó el `commit()`. Si no lo
     * aceptó, la memoria vuelve a lo que había: una fila que no está en disco no se publica ni se reenvía.
     */
    private fun escribir(id: String, valor: String?): Boolean {
        val anterior = prefs.getString(llave(id), null)
        if (cambiarClave(id, valor)) return true
        // ponytail: si tampoco esto se confirma, el disco sigue con lo de antes y la memoria ya quedó igual a él.
        cambiarClave(id, anterior)
        return false
    }

    private fun leerEnDisco(id: String): PayLaterRow? =
        prefs.getString(llave(id), null)?.let { runCatching { json.decodeFromString<PayLaterRow>(it) }.getOrNull() }

    private suspend fun <T> conCandado(bloque: () -> T): T = candado.withLock {
        withContext(io) { bloque().also { _filas.value = leerTodas() } }
    }

    suspend fun guardar(fila: PayLaterRow): Boolean = conCandado { escribir(fila.id, json.encodeToString(fila)) }

    /** Cambia la fila [id] tal como está AHORA en disco. false si ya no existe (nada revive). */
    suspend fun cambiar(id: String, cambio: (PayLaterRow) -> PayLaterRow): Boolean = conCandado {
        val actual = leerEnDisco(id)
        actual != null && escribir(id, json.encodeToString(cambio(actual)))
    }

    suspend fun borrar(id: String): Boolean = conCandado { escribir(id, null) }

    /** La fila [id] como está AHORA (Codex A-1: nunca una foto tomada antes de otra petición). */
    private suspend fun leer(id: String): PayLaterRow? = candado.withLock { withContext(io) { leerEnDisco(id) } }

    /** false si ya hay un envío vivo de esa cuenta en este proceso. */
    fun marcarEnCurso(id: String): Boolean = enCurso.add(id)

    fun soltar(id: String) {
        enCurso.remove(id)
    }

    // MARK: - El envío

    suspend fun mandar(venueId: String, cuerpo: String): DesenlacePagarDespues {
        val respuesta: OrderRepository.RespuestaCruda
        val desenlace: DesenlacePagarDespues
        try {
            respuesta = orderRepository.enviarPagarDespues(venueId, cuerpo)
            desenlace = OrderRepository.leerPagarDespues(respuesta.code, respuesta.contentType, respuesta.ngrokError, respuesta.body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Pagar después sin respuesta: ${e.message}")
            return DesenlacePagarDespues.SinRespuesta
        }
        // Una cuenta conocida sin prueba de cortesía no es un fallo de red ni un rechazo que diga que no existe.
        return if (desenlace is DesenlacePagarDespues.Creada && CortesiaPersistida.solicitada(cuerpo) &&
            !CortesiaPersistida.confirmada(cuerpo, respuesta.body)) {
            desenlace.copy(motivoRevision = "${CortesiaPersistida.AVISO} Folio: ${desenlace.orderId}.")
        } else desenlace
    }

    // MARK: - La copia guardada (A-5) y la venta por recuperar (A-4): sólo local, nunca se manda nada

    /** Retira la copia [copia] de «Carritos guardados». true si ya no está (o nunca hubo). */
    fun retirarCopia(copia: String?): Boolean =
        copia == null || savedCartsRepository.savedCarts.value.none { it.id == copia } || savedCartsRepository.deleteCart(copia)

    /**
     * La copia [copia] es de una cuenta viva: restaurarla (ni confirmando) la vendería otra vez. Una RECHAZADA también la liga
     * (su «Reintentar» puede crear la cuenta) pero no la retira: si una persona la descarta, la venta sigue en su copia.
     */
    fun copiaLigada(copia: String): Boolean = filas.value.any {
        it.carritoGuardadoId == copia &&
            // RECUPERAR también (Codex r2 #2): su venta se está trasladando a esa misma copia.
            (it.ligaSuCopia || it.estado == PayLaterRow.Estado.RECHAZADA || it.estado == PayLaterRow.Estado.RECUPERAR)
    }

    /** La cuenta [id] ya existe: la fila se borra sólo cuando su copia guardada quedó retirada; si no, espera ese retiro. */
    suspend fun cerrarAceptada(id: String) {
        val fila = leer(id) ?: return
        if (retirarCopia(fila.carritoGuardadoId)) borrar(id) else cambiar(id) { it.copy(estado = PayLaterRow.Estado.RETIRO_PENDIENTE) }
    }

    /**
     * La venta por recuperar va a «Carritos guardados» con su llave, SIEMPRE sobre el id estable de su captura: trasladarla
     * dos veces (el borrado de la fila falló, o la automática y la manual) deja UN solo carrito (Codex r2 #1). La fila se
     * conserva hasta confirmar el traslado: que falte la copia original no prueba que alguien ya la atendió (r2 #2).
     */
    private suspend fun aCarritosGuardados(fila: PayLaterRow): Boolean {
        val venta = fila.venta ?: return false
        return savedCartsRepository.saveCart(venta) && borrar(fila.id)
    }

    /**
     * «Pasar a carritos guardados» de la cuarentena. Sólo una por recuperar, de esta sucursal. La reclama con la misma marca
     * que la recuperación automática: null = la otra la está trasladando ahora.
     */
    suspend fun pasarACarritosGuardados(id: String): Boolean? {
        if (!marcarEnCurso(id)) return null
        return try {
            leer(id)?.takeIf { it.estado == PayLaterRow.Estado.RECUPERAR && it.venueId == secureStorage.venueId }
                ?.let { aCarritosGuardados(it) } == true
        } finally {
            soltar(id)
        }
    }

    /**
     * Al arrancar y en cada pasada, sin red y SIN mandar nada: retira las copias de las cuentas vivas, borra las filas cuyo
     * retiro ya se confirmó y pasa a «Carritos guardados» las ventas por recuperar. Nunca toca una fila en curso.
     */
    suspend fun mantenimientoLocal() {
        for (foto in filas.value) {
            if (!marcarEnCurso(foto.id)) continue
            try {
                val fila = leer(foto.id)
                when {
                    fila == null -> Unit
                    fila.estado == PayLaterRow.Estado.RETIRO_PENDIENTE -> if (retirarCopia(fila.carritoGuardadoId)) borrar(fila.id)
                    fila.ligaSuCopia -> retirarCopia(fila.carritoGuardadoId)
                    fila.estado == PayLaterRow.Estado.RECUPERAR && fila.venueId == secureStorage.venueId -> aCarritosGuardados(fila)
                }
            } finally {
                soltar(foto.id)
            }
        }
    }

    /** Manda una fila YA marcada en curso con su cuerpo de reenvío y deja escrito su desenlace. */
    private suspend fun procesar(fila: PayLaterRow): DesenlacePagarDespues {
        // Va a existir (o ya existe): su copia guardada no se puede volver a fiar. Cubre el intento en línea cancelado.
        retirarCopia(fila.carritoGuardadoId)
        val desenlace = mandar(fila.venueId, fila.cuerpo)
        when (desenlace) {
            is DesenlacePagarDespues.Creada ->
                if (desenlace.motivoRevision == null && desenlace.totalCents == fila.totalEsperadoCents) {
                    cerrarAceptada(fila.id)
                } else {
                    cambiar(fila.id) {
                        it.copy(estado = PayLaterRow.Estado.REVISAR, orderId = desenlace.orderId, totalRegistradoCents = desenlace.totalCents,
                            motivo = desenlace.motivoRevision,
                            intentos = it.intentos + 1)
                    }
                }
            DesenlacePagarDespues.SinRespuesta, is DesenlacePagarDespues.Pendiente -> cambiar(fila.id) {
                it.copy(estado = PayLaterRow.Estado.PENDIENTE, intentos = it.intentos + 1,
                    motivo = (desenlace as? DesenlacePagarDespues.Pendiente)?.motivo ?: "Sin conexión")
            }
            is DesenlacePagarDespues.SesionVencida -> Unit
            is DesenlacePagarDespues.Rechazada -> cambiar(fila.id) {
                it.copy(estado = PayLaterRow.Estado.RECHAZADA, intentos = it.intentos + 1, motivo = desenlace.mensaje)
            }
        }
        return desenlace
    }

    /**
     * Sube las filas de la sucursal de la sesión, viejas primero. Sin respuesta o con la sesión vencida, la pasada se corta;
     * una respuesta que no sirve (5xx) deja esa fila pendiente y sigue con la siguiente (un 500 fijo no detiene la cola).
     */
    suspend fun drenar() {
        if (!drenando.tryLock()) return
        try {
            mantenimientoLocal()
            val venue = secureStorage.venueId ?: return
            for (foto in filas.value.filter { it.porSubir && it.venueId == venue }) {
                if (secureStorage.venueId != venue) return
                if (!marcarEnCurso(foto.id)) continue
                val desenlace = try {
                    // Codex A-1: mientras se mandaba otra, un intento en línea pudo rechazarla y soltar su marca. Se relee
                    // con la marca ya puesta: si ya no está por subir (o es de otra sucursal), no se manda.
                    leer(foto.id)?.takeIf { it.porSubir && it.venueId == venue }?.let { procesar(it) }
                } finally {
                    soltar(foto.id)
                }
                if (desenlace == DesenlacePagarDespues.SinRespuesta || desenlace is DesenlacePagarDespues.SesionVencida) return
            }
        } finally {
            drenando.unlock()
        }
    }

    /**
     * «Reintentar» de la cuarentena: la misma llave. null si ya se está mandando, ya no existe, es de otra sucursal (A-8) o no
     * se puede mandar (REVISAR ya existe; RECUPERAR nunca se manda).
     */
    suspend fun reintentar(id: String): DesenlacePagarDespues? {
        if (!marcarEnCurso(id)) return null
        return try {
            leer(id)?.takeIf { (it.porSubir || it.estado == PayLaterRow.Estado.RECHAZADA) && it.venueId == secureStorage.venueId }
                ?.let { procesar(it) }
        } finally {
            soltar(id)
        }
    }

    /**
     * «Descartar» de la cuarentena (ya confirmado por una persona). Sólo lo que está por revisar, de esta sucursal. Una REVISAR
     * existe en el servidor: sale de la vista, pero su fila espera a que su copia guardada se retire (A-5).
     */
    suspend fun descartar(id: String): Boolean {
        if (!marcarEnCurso(id)) return false
        return try {
            val fila = leer(id)?.takeIf { it.paraRevisar && it.venueId == secureStorage.venueId } ?: return false
            if (fila.estado == PayLaterRow.Estado.REVISAR) {
                cerrarAceptada(id)
                true
            } else {
                borrar(id)
            }
        } finally {
            soltar(id)
        }
    }

    // MARK: - Disparadores (los mismos que la cola del efectivo: arranque, red de vuelta y cada 15 min)

    private var trabajo: Job? = null

    @Synchronized
    fun start() {
        if (trabajo != null) return
        trabajo = CoroutineScope(SupervisorJob() + io).launch {
            launch { drenar() }
            launch {
                while (isActive) {
                    delay(CADA_MS)
                    // Lo local (retiros y ventas por recuperar) corre con o sin red; lo que se manda, sólo con red.
                    if (connectivityMonitor.isConnected.value) drenar() else mantenimientoLocal()
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

    private companion object {
        const val TAG = "🧾 PagarDespues"
        const val CADA_MS = 15L * 60 * 1000
    }
}
