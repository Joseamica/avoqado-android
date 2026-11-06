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
enum class Cobro {
    SI,
    NO,
    NO_SE_SABE,
    /**
     * El servidor dice que no se cobró, pero lo puede corregir si el banco aprueba tarde: la ventana sin respuesta, la declaración de la
     * cajera o el rechazo del banco (`CODIGOS_DE_LIBERACION_REVERSIBLE` del SERVIDOR). Nunca borra sola: fuera del cobro en pantalla
     * lo decide una persona, y se sigue preguntando por si llega el cobro tardío.
     */
    NO_REVERSIBLE,
}

/** Un SÍ basta (el dinero entró); sólo se borra si todos dicen NO definitivo. Sin intentos no salió ningún cobro: NO. */
internal fun decidirCobro(evidencias: List<Cobro>): Cobro = when {
    Cobro.SI in evidencias -> Cobro.SI
    Cobro.NO_SE_SABE in evidencias -> Cobro.NO_SE_SABE
    Cobro.NO_REVERSIBLE in evidencias -> Cobro.NO_REVERSIBLE
    else -> Cobro.NO
}

/** Lo que el SERVIDOR libera «sin cobro» y todavía puede convertir en cobro (`terminal-payment.service.ts`). */
private val LIBERACIONES_REVERSIBLES = setOf("NO_EVIDENCE_AFTER_WINDOW", "OPERATOR_RECONCILED_NO_CHARGE", "BANK_DECLINED")
private val EVIDENCIAS_REVERSIBLES = setOf("NO_EVIDENCE_AFTER_WINDOW", "OPERATOR_RECONCILED", "PROCESSOR_DECLINED")

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

/**
 * Un paquete de la venta, una por pieza cobrada. [linea] es estable mientras la venta siga en el carrito. [saleProof] es la foto que
 * tomó la línea al entrar al carrito; sin ella se usa la del catálogo de ahora.
 */
data class LineaDePaquete(val linea: String, val packId: String, val nombre: String?, val precioCents: Int?, val saleProof: String? = null)

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

    /**
     * D17: se cobró en una orden del carrito, cuyo cobro suma al «Total gastado» al saldarse: la entrega no lo vuelve a sumar. Por la
     * [linea], que nace en el carrito y nunca se borra: [venta] se suelta si el paquete o el cliente cambian tras intentar cobrar
     * (Codex D12-D17 r1 #2). La ficha no tiene ninguna de las dos (cobra a mano, sin orden). Una fila de la app de antes de D4
     * tampoco: no se sabe de dónde vino y suma, como antes.
     */
    val cobradaEnOrden: Boolean get() = linea != null || venta != null

    /** Cobrado, por revisar o con un cobro intentado: no se puede soltar sin su sucursal (ver `bloqueantes`). */
    val esperaASuSucursal: Boolean get() = estado != Estado.POR_COBRAR || intentos.isNotEmpty()
}

// Cuarentena (los mismos textos para cuando le toque a iOS).
const val PAQUETE_EN_DUDA = "No se pudo confirmar si este cobro se hizo. Revisa la terminal o la caja."
const val PAQUETE_DE_OTRA_SUCURSAL = "Es de otra sucursal: entra a esa sucursal para que se entregue o se revise."
const val PAQUETE_TARJETA_NO_PASO =
    "La tarjeta no pasó, pero el banco todavía puede aprobarla tarde. Si el cliente no pagó de otra forma, descártalo; si llega el cobro, se entrega solo."

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

    /**
     * Las que una persona tiene que ver: las de esta sucursal por revisar y, de las DEMÁS sucursales, lo cobrado o intentado que sólo se
     * entrega con su sucursal activa. Sin esto, una sesión que se cerró sola y volvió a entrar en otra sucursal las escondía.
     */
    val porRevisarEnLaSucursal: Flow<List<PendingGrant>> =
        combine(filas, sucursal) { fs, venue -> fs.filter { if (it.venueId == venue) it.porRevisar else it.esperaASuSucursal } }

    val porRevisar: Flow<Int> = porRevisarEnLaSucursal.map { it.size }

    /** Cobradas que esperan subir: suman en «por sincronizar». */
    val porEntregar: Flow<Int> =
        combine(filas, sucursal) { fs, venue -> fs.count { it.estado == PendingGrant.Estado.POR_ENTREGAR && it.venueId == venue } }

    /**
     * Lo de [venueId] que se quedaría varado si la sesión cambia de sucursal: cobrado por entregar, por revisar, o un cobro intentado
     * cuya evidencia aún no llega. Cierra el paso igual que las demás colas (`AppState.onLogout`, `MoreMenuViewModel.switchVenue`).
     */
    fun bloqueantes(venueId: String?): Int = filas.value.count { it.venueId == venueId && it.esperaASuSucursal }

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

    /**
     * Cambia la fila [id] sólo si, AL ESCRIBIR, [si] se cumple. Devuelve la fila escrita, o null si ya no estaba así (otra mano la cambió
     * mientras tanto) o el disco no la aceptó.
     */
    private fun cambiarSi(id: String, si: (PendingGrant) -> Boolean, cambio: (PendingGrant) -> PendingGrant): PendingGrant? =
        synchronized(candado) {
            val actual = leer().firstOrNull { it.id == id }?.takeIf(si) ?: return@synchronized null
            val nueva = cambio(actual)
            nueva.takeIf { mutar { fs -> fs.map { f -> if (f.id == id) nueva else f } } }
        }

    /** La venta en pantalla, leída bajo el candado: toda decisión que dependa de ella se toma en el mismo candado en que se escribe. */
    private fun estaViva(f: PendingGrant): Boolean = f.venta != null && f.venta == ventaViva

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
        // 🔴 Codex r5: la venta queda en pantalla en el MISMO candado en que su fila se publica. Si se marcara después, un barrido que la
        // leyera en medio la vería sin venta viva ni intentos y la borraría antes de cobrar.
        val ok = synchronized(candado) {
            val anterior = ventaViva
            ventaViva = venta.takeIf { hayQueArmar }
            mutar { fs ->
            fun esDeLaLinea(f: PendingGrant, l: LineaDePaquete) =
                f.venta == venta && f.linea == l.linea && f.packId == l.packId && f.customerId == customerId &&
                    (f.estado == PendingGrant.Estado.POR_COBRAR || f.estado == PendingGrant.Estado.EN_DUDA)
            val porCobrar = setOf(PendingGrant.Estado.POR_COBRAR, PendingGrant.Estado.EN_DUDA)
            val quedan = fs.mapNotNull { f ->
                val siguePegada = hayQueArmar && lineas.any { esDeLaLinea(f, it) }
                when {
                    f.estado !in porCobrar || siguePegada -> f
                    // Nunca intentó cobrar: no hubo cobro (otra venta, o un paquete que se quitó del carrito).
                    f.intentos.isEmpty() -> null
                    // Intentó cobrar y ya no es lo que se cobra ahora (otro cliente, otro paquete, se quitó): se suelta de la venta y
                    // lo decide su evidencia. Un cobro nuevo de esta venta nunca le cuenta, ni para entregarle a otro cliente.
                    f.venta == venta -> f.copy(venta = null)
                    else -> f
                }
            }.toMutableList()
            if (hayQueArmar) {
                for (l in lineas) {
                    val foto = l.saleProof ?: catalogo.firstOrNull { it.id == l.packId }?.saleProof
                    val i = quedan.indexOfFirst { esDeLaLinea(it, l) }
                    if (i >= 0) {
                        val f = quedan[i]
                        // Con un cobro ya intentado, lo vendido es lo de ESE cobro: un catálogo refrescado no lo cambia.
                        quedan[i] = if (f.intentos.isNotEmpty()) {
                            f.copy(clienteNombre = clienteNombre)
                        } else {
                            f.copy(
                                estado = PendingGrant.Estado.POR_COBRAR, clienteNombre = clienteNombre, nombre = l.nombre,
                                precioCents = l.precioCents, saleProof = foto, motivo = null, dudaDesde = null,
                            )
                        }
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
            }.also { if (!it) ventaViva = anterior }
        }
        return ok
    }

    /**
     * Sale un intento de cobro de [venta]. false = el cobro no debe salir: el disco no aceptó, o la venta lleva paquetes ([llevaPaquetes]) y
     * no hay ninguna fila suya que los respalde (cobrarlos así sería entregar nada). Sin paquetes en esa venta no hace nada.
     */
    fun anotarIntento(venta: String, intento: IntentoDeCobro, llevaPaquetes: Boolean = false): Boolean = synchronized(candado) {
        val actuales = leer()
        val suyas = actuales.filter { it.venta == venta && it.estado != PendingGrant.Estado.RECHAZADA }
        if (llevaPaquetes && suyas.none { it.estado == PendingGrant.Estado.POR_COBRAR || it.estado == PendingGrant.Estado.EN_DUDA }) {
            Log.e(TAG, "La venta $venta lleva paquetes y no tiene su fila: el cobro no sale")
            return@synchronized false
        }
        mutar { fs -> fs.map { f -> if (f in suyas && intento !in f.intentos) f.copy(intentos = f.intentos + intento) else f } }
    }

    /** El cobro se cerró sin cobrar («Salir», «Cancelar»): la venta ya no está en pantalla y su evidencia decide desde ahora. */
    fun soltarVenta() {
        synchronized(candado) { ventaViva = null }
    }

    /** Consta que ese intento nunca nació (el servidor lo rechazó sin crearlo): no hay por qué preguntar por él; su 404 sería una duda falsa. */
    fun quitarIntento(venta: String, intento: IntentoDeCobro) {
        mutar { fs -> fs.map { if (it.venta == venta) it.copy(intentos = it.intentos - intento) else it } }
    }

    /** Consta el cobro de [venta]: lo suyo pasa a por entregar. */
    fun marcarCobrada(venta: String): Boolean = synchronized(candado) {
        // 🔴 Codex r6: primero queda escrito el cobro y DESPUÉS sale de la pantalla, en el mismo candado. Al revés, un NO atrasado del
        // barrido cabía en medio, veía la venta cerrada y la fila todavía por cobrar, y la borraba.
        val ok = mutar { fs ->
            fs.map { f ->
                if (f.venta == venta && (f.estado == PendingGrant.Estado.POR_COBRAR || f.estado == PendingGrant.Estado.EN_DUDA)) {
                    f.copy(estado = PendingGrant.Estado.POR_ENTREGAR, motivo = null)
                } else {
                    f
                }
            }
        }
        if (ok && ventaViva == venta) ventaViva = null
        ok
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
        val r = articlesRepository.sellPackToCustomer(fila.packId, fila.customerId, background, fila.id, fila.saleProof, fila.cobradaEnOrden)
        val desenlace = clasificarEntrega(r.code)
        when (desenlace) {
            DesenlaceDeEntrega.ENTREGADA -> {
                recordarEntregado(fila)
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

    /** Qué dijo el servidor de esa fila, tal como quedó. */
    fun motivoDe(id: String): String? = filas.value.firstOrNull { it.id == id }?.motivo

    /**
     * D2 (6-nov): qué se vendió con el cobro [llave] (efectivo u otra forma) y a quién, para «Cobros sin confirmar». Un efectivo que el
     * servidor rechazó al subir sí entró al cajón y su paquete se entrega igual (founder, 24-oct, «al estilo Toast»): el gerente tiene
     * que saber que ya se dio. null = el cobro no fue de un paquete de este aparato, o se entregó hace más de 30 días.
     */
    fun loVendidoConElCobro(llave: String): String? {
        val porEntregar = filas.value.filter { f -> f.intentos.any { it.id == llave } }.map(::tituloDePaquete)
        val entregados = leerEntregados()[llave]?.takeIf { reloj() - it.en < OLVIDAR_ENTREGADOS_MS }?.titulos?.values.orEmpty()
        if (porEntregar.isEmpty() && entregados.isEmpty()) return null
        val vendido = "Vendido: ${(porEntregar + entregados).joinToString("; ")}."
        return if (porEntregar.isEmpty()) "$vendido El paquete ya se entregó al cliente." else vendido
    }

    /** Lo entregado, por llave de cobro: [titulos] por fila (una por pieza), y cuándo. */
    @Serializable
    private data class Entregados(val titulos: Map<String, String>, val en: Long)

    private fun leerEntregados(): Map<String, Entregados> = prefs.getString(LLAVE_ENTREGADOS, null)
        ?.let { runCatching { json.decodeFromString<Map<String, Entregados>>(it) }.getOrNull() }
        .orEmpty()

    /** Antes de borrar lo entregado, para que «Cobros sin confirmar» lo siga diciendo. Es sólo texto: si el disco falla, no se dice. */
    private fun recordarEntregado(fila: PendingGrant) = synchronized(candado) {
        if (fila.intentos.isEmpty()) return@synchronized
        val ahora = reloj()
        val vigentes = leerEntregados().filterValues { ahora - it.en < OLVIDAR_ENTREGADOS_MS }.toMutableMap()
        for (intento in fila.intentos) {
            val antes = vigentes[intento.id]?.titulos.orEmpty()
            vigentes[intento.id] = Entregados(antes + (fila.id to tituloDePaquete(fila)), ahora)
        }
        prefs.edit().putString(LLAVE_ENTREGADOS, json.encodeToString(vigentes)).apply()
    }

    /** «Reintentar» de la cuarentena: la misma llave. null = ya no está, es de otra sucursal o no está rechazada. */
    suspend fun reintentar(id: String): DesenlaceDeEntrega? = withContext(io) {
        val venue = secureStorage.venueId
        cambiarSi(id, { it.estado == PendingGrant.Estado.RECHAZADA && it.venueId == venue }) {
            it.copy(estado = PendingGrant.Estado.POR_ENTREGAR, motivo = null)
        }?.let { enviar(it) }
    }

    /** «Sí se cobró» de la cuarentena. */
    suspend fun siSeCobro(id: String): DesenlaceDeEntrega? = withContext(io) {
        val venue = secureStorage.venueId
        cambiarSi(id, { it.estado == PendingGrant.Estado.EN_DUDA && it.venueId == venue }) {
            it.copy(estado = PendingGrant.Estado.POR_ENTREGAR, motivo = null)
        }?.let { enviar(it) }
    }

    /**
     * «Descartar» o «No se cobró» de la cuarentena, ya confirmado por una persona. Sólo lo que SIGUE por revisar al escribir, de esta
     * sucursal: si mientras tanto la evidencia confirmó el cobro, ya no se borra.
     */
    fun descartar(id: String): Boolean {
        val venue = secureStorage.venueId
        return synchronized(candado) {
            leer().any { it.id == id && it.porRevisar && it.venueId == venue } && borrar(id)
        }
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
                        is CardChargeOutcome.NotCharged -> {
                            val conocido = probe as? com.avoqado.pos.payment.domain.ChargeStatusProbe.Known
                            if (conocido?.failureCode in LIBERACIONES_REVERSIBLES || conocido?.outcomeEvidence in EVIDENCIAS_REVERSIBLES) {
                                Cobro.NO_REVERSIBLE
                            } else {
                                Cobro.NO
                            }
                        }
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
                Cobro.SI -> cambiarSi(foto.id, { it.estado == PendingGrant.Estado.POR_COBRAR || it.estado == PendingGrant.Estado.EN_DUDA }) {
                    it.copy(estado = PendingGrant.Estado.POR_ENTREGAR, motivo = null)
                }
                // Se decide con lo que hay en disco AL ESCRIBIR: mientras se preguntaba, el cobro pudo confirmarse (`marcarCobrada`), la venta
                // volver a la pantalla o anotarse otro intento. Cualquiera de esos cambios vuelve viejo este NO.
                Cobro.NO -> mutar { fs ->
                    fs.filterNot {
                        it.id == foto.id && it.estado == foto.estado && it.intentos == foto.intentos && it.venta == foto.venta && !estaViva(it)
                    }
                }
                Cobro.NO_SE_SABE -> if (conRed) anotarDuda(foto.id)
                // Nunca borra sola: fuera del cobro en pantalla lo decide una persona, y la evidencia se sigue consultando.
                Cobro.NO_REVERSIBLE -> cambiarSi(foto.id, { it.estado == PendingGrant.Estado.POR_COBRAR && !estaViva(it) }) {
                    it.copy(estado = PendingGrant.Estado.EN_DUDA, motivo = PAQUETE_TARJETA_NO_PASO)
                }
            }
        }
    }

    private fun anotarDuda(id: String) {
        val ahora = reloj()
        cambiarSi(id, { it.estado == PendingGrant.Estado.POR_COBRAR && !estaViva(it) }) { f ->
            val desde = f.dudaDesde ?: ahora
            if (ahora - desde > DUDA_MS) {
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
        private const val LLAVE_ENTREGADOS = "entregados_por_cobro"
        private const val OLVIDAR_ENTREGADOS_MS = 30L * 24 * 60 * 60 * 1000
        private const val CADA_MS = 60_000L

        /** Media hora con red sin poder saber: ya no se espera en silencio. */
        internal const val DUDA_MS = 30L * 60 * 1000

    }
}
