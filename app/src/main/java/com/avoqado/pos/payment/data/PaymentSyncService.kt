package com.avoqado.pos.payment.data

import android.util.Log
import com.avoqado.pos.cashdrawer.data.CODIGO_CIERRE_EN_PROCESO
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.core.data.local.database.RevisionCanceladoCero
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaymentSyncService @Inject constructor(
    private val dao: PendingPaymentDao,
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    private val connectivityMonitor: ConnectivityMonitor,
    /**
     * 🔴 EL CAJÓN VIAJA EN ESTE MISMO CARRIL, Y VA PRIMERO (F1, 5-sep-2026).
     *
     * La cola del cajón no tenía quién la reprodujera al volver la red: sólo se disparaba al entrar
     * a la pantalla de Caja, que es justo donde el cajero NO está (está en Cobrar). Este servicio ya
     * tiene lo que hace falta —ciclo de vida de app, observador de red y temporizador— así que el
     * replay del cajón se engancha aquí en vez de estrenar un servicio y un timer paralelos.
     */
    private val cajon: CashDrawerRepository,
    private val syncOutbox: SyncOutbox,
    /**
     * 🔴 D2 v7 (24-oct): lo que nadie resolvió se da por COBRADO antes de reproducir la cola (ver
     * [CashPaymentRepository.darPorCobradasLasAbandonadas]). Obligatorio a propósito (ronda 1, M6): sin él la cola no barre, y
     * eso no se puede apagar en silencio.
     */
    private val cashPaymentRepository: CashPaymentRepository,
) {
    // MARK: - Constants

    companion object {
        private const val TAG = "🔄 PaymentSync"
        private const val MAX_RETRIES = 10
        private const val SYNC_INTERVAL_MS = 15L * 60 * 1000  // 15 minutes
        private const val MAX_PAYMENTS_PER_SYNC = 10
        private const val MAX_DRAIN_BATCHES = 100
        private const val INITIAL_BACKOFF_MS = 1000L  // 1 second
        private const val MAX_BACKOFF_MS = 30_000L    // 30 seconds
        private const val CLEANUP_AFTER_MS = 24L * 60 * 60 * 1000  // 24 hours

        /**
         * 🔴 Cuántas vueltas «caja → cobros» da UNA sincronización mientras alguna avance (efectivo sin red en el turno
         * correcto, barrera 3). Con un aparato que abrió A, cerró A y abrió B sin red: 1.ª apertura A y cobros de A (el cierre
         * de A espera a sus cobros); 2.ª cierre de A, apertura B y cobros de B; 3.ª por si algo más quedó listo.
         */
        internal const val MAX_VUELTAS = 3

        /** El largo máximo que el servidor acepta para la caja de la venta; algo más largo no se manda (sería un 400). */
        private const val LARGO_MAXIMO_DE_LA_CAJA = 64
        private val JSON_MEDIA = "application/json".toMediaType()

        private val RESPONSE_JSON = Json { ignoreUnknownKeys = true }

        /** Lo que se espera a que la ruta suba antes de mandar nada. Ver [alReconectar]. */
        internal const val ESPERA_DE_ESTABILIZACION_MS = 2000L

        /**
         * «Volvió la red» = pasar de NO plenamente conectado a plenamente conectado, esperar a que
         * la ruta suba, y sólo entonces actuar. UNA vez por transición, nunca por cada emisión.
         *
         * Vive aquí, fuera del `collect`, para que un test lo ejercite con un flujo falso y tiempo
         * virtual: es la única parte del disparador donde se puede meter un defecto (actuar dos
         * veces, actuar sin haber estado caído, mandar antes de que la ruta suba) y las tres se
         * ven en segundos. Medido el 5-sep: la app mandó su `POST /open` justo cuando el WiFi
         * apenas subía, falló, y NADIE volvió a intentarlo en 8 minutos.
         */
        internal suspend fun alReconectar(
            plenamenteConectado: Flow<Boolean>,
            esperaMs: Long = ESPERA_DE_ESTABILIZACION_MS,
            accion: suspend () -> Unit,
        ) {
            var estuvoCaido = false
            plenamenteConectado.collect { conectado ->
                if (!conectado) {
                    estuvoCaido = true
                } else if (estuvoCaido) {
                    estuvoCaido = false
                    delay(esperaMs)
                    accion()
                }
            }
        }

        /**
         * The create-order endpoint answers {"success":true,"order":{...}}; other endpoints
         * wrap their payload in "data". Both envelopes live in the same controller file, so
         * read both — OrderRepository.parseCreateOrderResponse learned this on day one and
         * this parser never did, which is why a successful creation looked like a failure.
         */
        internal fun classifyOrderCreation(code: Int, body: String): OrderResolution = when {
            code in 200..299 -> {
                val orderId = extractOrderId(body)
                if (orderId.isNullOrBlank()) {
                    // The server accepted it, so the order EXISTS — we just cannot read its
                    // id. Retrying would POST /orders again and create a duplicate, which is
                    // strictly worse than stopping. Quarantine it so a person can link it.
                    OrderResolution.PermanentFailure("Orden creada pero sin id legible en la respuesta")
                } else {
                    OrderResolution.Ready(orderId)
                }
            }
            code == 401 -> OrderResolution.AuthExpired
            code in 400..499 -> OrderResolution.PermanentFailure("Order recreate failed ($code): $body")
            else -> OrderResolution.RetryableFailure("Order recreate server error ($code)")
        }

        /**
         * El cuerpo EXACTO que la cola reproduce contra el server.
         *
         * Vive aquí —y no dentro de `syncPayment`— para que un test pruebe el camino
         * que de verdad se ejecuta: un campo agregado a otro constructor de cuerpos
         * (el del cobro en vivo) NO llega jamás a la cola, y el hueco sólo se ve
         * meses después, en las ventas que el local cobró sin red.
         *
         * @param esCobroDeOrden true = va a `/orders/:id/pay`; false = va a `/fast`.
         */
        internal fun buildReplayBody(
            payment: PendingPaymentEntity,
            venueId: String,
            esCobroDeOrden: Boolean,
            /**
             * La caja de la venta YA TRADUCIDA al id del servidor (`CashDrawerRepository.idDeCajaParaElServidor`). Nula = no
             * viaja (fila sin caja, o caja local sin alias): el cuerpo queda byte a byte como hoy.
             */
            cajaParaElServidor: String? = null,
        ): String = buildJsonObject {
            put("venueId", venueId)
            put("amount", payment.amountCents)
            put("tip", payment.tipCents)
            put("status", "COMPLETED")
            // El método REAL de la cola, no un "CASH" fijo: reproducir un
            // cobro con terminal ajena como efectivo descuadra el arqueo.
            // 🔑 Si la venta se cobró con un TIPO DEL CATÁLOGO, viaja la referencia
            // {id, revision} y NO `method`: el server los rechaza juntos a propósito
            // (ambigüedad de dinero) y resuelve él la semántica desde su historial.
            // Sin esto la venta aterrizaba como EFECTIVO, callada.
            val tenderId = payment.tenderTypeId
            val tenderRev = payment.tenderRevision
            if (tenderId != null && tenderRev != null) {
                put("tenderTypeId", tenderId)
                put("tenderRevision", tenderRev)
            } else {
                val manual = runCatching {
                    com.avoqado.pos.payment.domain.ManualPaymentMethod.valueOf(payment.method)
                }.getOrNull()
                put("method", manual?.serverMethod ?: "CASH")
                manual?.externalSource?.let { put("externalSource", it) }
            }
            put("splitType", payment.splitType)
            put("staffId", payment.staffId)
            put("source", "AVOQADO_ANDROID")
            put("idempotencyKey", payment.id)
            // 🔴 "Esta venta YA OCURRIÓ y viene de mi cola." Con un tipo del catálogo,
            // el server honra la revisión que el cajero tenía enfrente al cobrar. Sin
            // esto, subir la comisión de un tipo el martes RECHAZA para siempre las
            // ventas del lunes que no habían sincronizado: atoradas en la cola, con un
            // banner que el cajero no puede quitar. Sólo lo manda ESTA cola, nunca un
            // cobro en vivo.
            put("isOfflineReplay", true)
            // 🔴 EL CLIENTE de la venta rápida. Sólo en `/fast`: el cobro de una ORDEN
            // ya lleva su cliente desde que la orden se creó (`orderRequestJson`), y
            // `recordOrderPayment` ni siquiera lee este campo — mandarlo ahí sería
            // ruido que promete algo que ese endpoint no hace.
            if (!esCobroDeOrden) {
                payment.customerId?.takeIf { it.isNotBlank() }?.let { put("customerId", it) }
            }
            // 🔴 LA CAJA DE LA VENTA (efectivo sin red en el turno correcto): el servidor saca de ahí el turno de la venta, y una
            // venta cuyo turno ya cerró queda aparte con aviso en vez de caer en el turno nuevo. Sólo cuenta con
            // `isOfflineReplay`, que este cuerpo siempre lleva. Al final y sólo si viene: sin caja, el cuerpo es el de hoy.
            cajaParaElServidor?.let { put("cashDrawerSessionId", it) }
            // D1 «fotocopia del ticket»: la copia del papel de esta parte, sólo en `/pay` (el contrato no la lleva a `/fast`). Una
            // columna ilegible no viaja: es texto del ticket y nunca puede frenar el cobro. Sin copia, el cuerpo es el de hoy.
            if (esCobroDeOrden) {
                payment.lineasDelTicketJson
                    ?.let { runCatching { RESPONSE_JSON.parseToJsonElement(it) as? kotlinx.serialization.json.JsonObject }.getOrNull() }
                    ?.let { put("lineasDelTicket", it) }
            }
        }.toString()

        /**
         * El pedido congelado de la fila con la caja de la venta para `POST /orders` (efectivo sin red en el turno correcto):
         * la orden que la cola crea al volver la red nace en el turno de su caja. Sin caja, o con un pedido ilegible, el texto
         * ORIGINAL sin tocar (una fila vieja sube byte a byte como hoy).
         */
        internal fun pedidoConLaCaja(orderRequestJson: String, cajaParaElServidor: String?): String {
            if (cajaParaElServidor == null) return orderRequestJson
            val pedido = runCatching { RESPONSE_JSON.parseToJsonElement(orderRequestJson) as? kotlinx.serialization.json.JsonObject }
                .getOrNull() ?: return orderRequestJson
            return kotlinx.serialization.json.JsonObject(
                pedido + ("cashDrawerSessionId" to kotlinx.serialization.json.JsonPrimitive(cajaParaElServidor)),
            ).toString()
        }

        /**
         * 🔴 ¿Este cobro puede salir ya? (barrera 1 por caja). Con caja: sí, salvo que su apertura siga esperando al servidor.
         * Sin caja (fila vieja, o no había caja abierta al cobrar): la regla global de hoy.
         */
        internal fun elCobroPuedeSalir(caja: String?, sinCajaPuedenSalir: Boolean, cajasEsperando: Set<String>): Boolean =
            if (caja == null) sinCajaPuedenSalir else caja !in cajasEsperando

        internal fun extractOrderId(body: String): String? {
            return try {
                val root = RESPONSE_JSON.parseToJsonElement(body).jsonObject
                root["order"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: root["data"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: root["id"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) {
                null
            }
        }
    }

    // MARK: - State

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _failedCount = MutableStateFlow(0)
    val failedCount: StateFlow<Int> = _failedCount.asStateFlow()

    private var isStarted = false
    private var syncJob: Job? = null
    private var syncRequested: SyncContext? = null
    private var syncCycle = 0L
    private data class SyncContext(val venue: String?, val actor: String?, val role: String?, val epoch: Long)
    private fun syncContext() = SyncContext(
        secureStorage.venueId, secureStorage.userId, secureStorage.userRole, syncOutbox.contextoDeSesionQ15(),
    )
    private var timerJob: Job? = null
    private var connectivityJob: Job? = null
    private var pendingCountJob: Job? = null
    private var failedCountJob: Job? = null
    private var syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 🔴 SÓLO PARA PRUEBAS, y se fija ANTES de `start()`.
     *
     * En producción el servicio corre en `Dispatchers.IO` y nadie toca esto. Existe porque las
     * pruebas del ORDEN entre las dos colas dependían de `Thread.sleep(500)` de tiempo REAL: en
     * esta Mac, que corre con ~20 sesiones encima, medio segundo puede no alcanzar y la prueba
     * falla por CARGA, no por código. Con un `TestDispatcher` la misma prueba corre en tiempo
     * virtual y es determinista.
     */
    @Synchronized
    internal fun usarContextoDeSincronizacion(contexto: kotlin.coroutines.CoroutineContext) {
        syncCycle++
        syncRequested = null
        syncJob = null
        syncScope.cancel()
        syncScope = CoroutineScope(SupervisorJob() + contexto)
    }
    private val json = Json { ignoreUnknownKeys = true }

    internal sealed interface OrderResolution {
        data class Ready(val orderId: String) : OrderResolution
        data class RetryableFailure(val message: String) : OrderResolution
        data class PermanentFailure(val message: String) : OrderResolution
        data object AuthExpired : OrderResolution
    }

    /** Qué dejó la recuperación de una fila retenida (Q16). */
    private sealed interface Recuperacion {
        /** La recuperación vacía soltó la venta sin red: se relee la cola y sale en su lugar FIFO. */
        data object Soltada : Recuperacion
        /** Sigue retenida, o se le adoptó su orden del servidor (sale la vuelta siguiente): la vuelta sigue sin ella. */
        data class Retenida(val ordenAdoptada: String? = null) : Recuperacion
        /**
         * Codex N5 (regla final del controlador, igual en iOS): la consulta NO obtuvo respuesta del servidor (sin red,
         * conexión rechazada, transporte) o la sesión está muerta (401 / sesión sin renovar). Corta la vuelta (C1) sin
         * gastar reintentos. Una RESPUESTA que no sirve (5xx, 408, 429, ilegible) no es pausa: la fila sigue retenida, para
         * que un 500 persistente no detenga la cola para siempre.
         */
        data object Pausa : Recuperacion
    }

    /** Waits for the real launched drain; no alternate test pipeline. */
    internal suspend fun esperarSincronizacionActualQ16() { syncJob?.join() }

    // MARK: - Lifecycle

    @Synchronized
    fun start() {
        if (isStarted) return
        isStarted = true
        Log.d(TAG, "Starting payment sync service")

        val scope = syncScope
        val cycle = syncCycle
        scope.launch {
            if (!synchronized(this@PaymentSyncService) { isStarted && syncCycle == cycle && syncScope === scope }) return@launch
            // Reset stuck SYNCING payments (crash recovery)
            dao.resetSyncingToPending()
            synchronized(this@PaymentSyncService) {
                if (!isStarted || syncCycle != cycle || syncScope !== scope) return@launch
                startCountObservers()
                // Immediate sync attempt, only while this start still owns its lifecycle.
                syncNow()
            }
        }

        // Start periodic timer
        startTimer()

        // Listen for connectivity changes
        startConnectivityListener()
    }

    @Synchronized
    fun stop() {
        if (!isStarted && syncJob == null) return
        isStarted = false
        syncCycle++
        syncRequested = null
        val stoppingJob = syncJob
        syncJob = null
        Log.d(TAG, "Stopping payment sync service")
        timerJob?.cancel()
        connectivityJob?.cancel()
        stoppingJob?.cancel()
        pendingCountJob?.cancel()
        failedCountJob?.cancel()
    }

    // MARK: - Timer

    private fun startTimer() {
        timerJob?.cancel()
        val scope = syncScope
        val cycle = syncCycle
        timerJob = scope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                // 🔴 Codex apps r8 #1: con red del APARATO basta (como iOS). Si sólo se sincronizaba con la bandera del servidor
                // encendida, una falla parcial (503 en el ping y en los cobros, salud profunda sana) la dejaba apagada para siempre y
                // el cierre que espera a sus ventas no volvía a contar un intento. El drenado conserva sus barreras; la salud la reenciende.
                if (connectivityMonitor.isConnected.value) {
                    synchronized(this@PaymentSyncService) {
                        if (isStarted && syncCycle == cycle && syncScope === scope) syncNow()
                    }
                }
            }
        }
    }

    // MARK: - Connectivity Listener

    private fun startConnectivityListener() {
        connectivityJob?.cancel()
        val scope = syncScope
        val cycle = syncCycle
        connectivityJob = scope.launch {
            alReconectar(
                combine(
                    connectivityMonitor.isConnected,
                    connectivityMonitor.isServerReachable,
                ) { network, server -> network && server },
            ) {
                // 🔴 `syncNow()` arranca por el CAJÓN. Sin esa línea, esta reconexión mandaba los
                // cobros y dejaba la apertura en la cola — el defecto F1 completo.
                Log.d(TAG, "Network reconnected — triggering sync")
                synchronized(this@PaymentSyncService) {
                    if (isStarted && syncCycle == cycle && syncScope === scope) syncNow()
                }
            }
        }
    }

    /**
     * Q16: intenta soltar o adoptar una fila retenida. Una escritura que no se logra (CAS) o un alias ilegible SUBEN y
     * cortan la vuelta; sin respuesta del servidor o con la sesión muerta es [Recuperacion.Pausa] (Codex N5).
     */
    private suspend fun recuperarOrdenRetenidaQ16(payment: PendingPaymentEntity): Recuperacion {
        val sigue = Recuperacion.Retenida()
        if (payment.paymentType != "ORDER" || payment.q15Reason != ConciliacionDelCobro.PRICE_PROOF_PENDING ||
            (!payment.orderId.isNullOrBlank() && syncOutbox.aliasDurableQ16(payment.venueId,payment.orderId)?.isNotBlank() != true) || payment.venueId.isBlank() ||
            ConciliacionDelCobro.leerCaptura(payment.q15CaptureJson,payment.venueId,payment.id) == null) return sigue
        val external = externalIdDelPedido(payment.orderRequestJson) ?: return sigue
        val (code, response) = try {
            val request = Request.Builder().url("${ApiConstants.BASE_URL}/mobile/venues/${payment.venueId}/sync/recovery")
                .post(buildJsonObject { put("externalOrderIds",kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(external)))) }.toString().toRequestBody(JSON_MEDIA)).build()
            withContext(Dispatchers.IO) { client.newCall(request).execute().use { it.code to it.body?.string() } }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            // Sin respuesta (sin red, conexión rechazada, transporte) o sesión sin renovar ⇒ pausa, sin gastar reintentos.
            Log.w(TAG,"La recuperación no obtuvo respuesta; la vuelta se corta: ${error.message}")
            return Recuperacion.Pausa
        }
        if (code == 401) return Recuperacion.Pausa
        // Cualquier otra respuesta que no sirva (5xx, 408, 429, 404 de un backend viejo, ilegible) ⇒ sigue retenida y la vuelta sigue.
        if (code !in 200..299) return sigue
        val data = runCatching { json.parseToJsonElement(response ?: "").jsonObject["data"] as? kotlinx.serialization.json.JsonObject }.getOrNull()
            ?: return sigue
        if ((data["intents"] as? kotlinx.serialization.json.JsonArray)?.isEmpty() != true) return sigue
        val orders = data["orders"] as? kotlinx.serialization.json.JsonArray ?: return sigue
        if (orders.isEmpty()) {
            // Q16 (2.24.1): el servidor no tiene la orden porque nunca se creó (no había red). Se quita la marca
            // que puso la 2.24.0 y esta misma vuelta la crea y la cobra (ver `ventaSinRedConPedido`).
            if (!ventaSinRedConPedido(payment)) return sigue
            check(dao.soltarVentaSinRedQ16(payment.venueId,payment.id) == 1)
            return Recuperacion.Soltada
        }
        if (orders.size != 1) return sigue
        // Codex P1-3: la captura dice qué venta se cobró. Si trae pedido, su `externalId` tiene que ser el consultado
        // (el del pedido de la fila); si no, la orden encontrada es de OTRA venta y no se adopta. Captura vieja sin pedido ⇒ como antes.
        ConciliacionDelCobro.leerCaptura(payment.q15CaptureJson,payment.venueId,payment.id)?.orderRequestJson?.let {
            if (externalIdDelPedido(it) != external) return sigue
        }
        val order = orders.single() as? kotlinx.serialization.json.JsonObject ?: return sigue
        val ref = (order["externalId"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        val id = (order["orderId"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: return sigue
        if (ref != external) return sigue
        if (!payment.orderId.isNullOrBlank() && syncOutbox.aliasDurableQ16(payment.venueId,payment.orderId) != id) return sigue
        check(dao.recuperarOrdenQ16(payment.venueId,payment.id,payment.orderId,id) == 1)
        // Codex N1: la orden adoptada entra a lo bloqueado de esta vuelta (la fila ya la trae; el objeto en memoria no).
        return Recuperacion.Retenida(ordenAdoptada = id)
    }

    /**
     * Adenda Q16: con qué orden se identifica una fila — su `orderId` y el de su captura (servidor, `local_…` o alias de
     * Mesas, cada uno con la orden a la que resuelve su alias) y el `externalId` del pedido de la fila y del de la captura
     * (Codex N2). Dos filas que comparten una son de la MISMA cuenta. Vacío (FAST sin artículos) = no depende de nadie. Si
     * un alias no se puede leer, el error SUBE y corta la vuelta: seguir con menos identidades dejaría cobrar antes otra fila.
     */
    private suspend fun identidadesDeOrden(payment: PendingPaymentEntity): Set<String> {
        val captura = ConciliacionDelCobro.leerCaptura(payment.q15CaptureJson, payment.venueId, payment.id)
        val ordenes = listOfNotNull(payment.orderId, captura?.orderId).filter { it.isNotBlank() }.distinct()
        return buildSet {
            addAll(ordenes)
            for (id in ordenes) syncOutbox.aliasDurableQ16(payment.venueId, id)?.takeIf { it.isNotBlank() }?.let { add(it) }
            externalIdDelPedido(payment.orderRequestJson)?.let { add(it) }
            externalIdDelPedido(captura?.orderRequestJson)?.let { add(it) }
        }
    }

    private fun externalIdDelPedido(raw: String?): String? = runCatching {
        (json.parseToJsonElement(raw ?: return null).jsonObject["externalId"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString && it.content.isNotBlank() && it.content.length <= 256 }?.content
    }.getOrNull()

    /**
     * 🔴 Q16 (2.24.1): la venta de mostrador cobrada SIN RED. Nace con `orderId` nulo porque crear la orden falló, y su
     * captura Q15 guarda el pedido completo con su `externalId`. Con eso la cola crea la orden al volver la red con el
     * MISMO `externalId` (el servidor deduplica) y la cobra con la MISMA llave; si el precio cambió, lo marca
     * `ConciliacionDelCobro` (aviso Q15 al gerente). La 2.24.0 la retenía esperando una «prueba de precio» que nadie
     * emite, y la cola se congelaba para siempre. Sólo `orderId == null`, que es lo que escribe la caja: `""`, el alias
     * de Mesas o un pedido distinto del capturado siguen retenidos.
     */
    private fun ventaSinRedConPedido(payment: PendingPaymentEntity): Boolean = payment.orderId == null && nacioSinOrden(payment)

    /**
     * La CAPTURA (inmutable) dice que la venta nació SIN orden y con el mismo pedido que la fila. Se decide con la captura
     * y no con la fila porque la recuperación Q16 puede escribirle a la fila el `orderId` de una orden ya creada (Codex P1-4).
     */
    private fun nacioSinOrden(payment: PendingPaymentEntity): Boolean {
        if (payment.paymentType != "ORDER") return false
        val captura = ConciliacionDelCobro.leerCaptura(payment.q15CaptureJson, payment.venueId, payment.id) ?: return false
        val external = externalIdDelPedido(captura.orderRequestJson) ?: return false
        return captura.orderId == null && external == externalIdDelPedido(payment.orderRequestJson)
    }

    private suspend fun requierePruebaDePrecio(payment: PendingPaymentEntity): Boolean {
        if (payment.paymentType == "ORDER") return try {
            !ventaSinRedConPedido(payment) &&
                (payment.orderId.isNullOrBlank() || syncOutbox.aliasDurableQ16(payment.venueId,payment.orderId)?.isNotBlank() == true)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { true }
        if (payment.paymentType != "FAST") return true
        // Sólo FAST sin artículos conserva su ruta; no degradar una orden a venta rápida.
        if (payment.orderRequestJson != null) return true
        return payment.itemsJson?.let { raw ->
            val items = runCatching { json.parseToJsonElement(raw) as? kotlinx.serialization.json.JsonArray }.getOrNull()
            items == null || items.isNotEmpty()
        } ?: false
    }

    // MARK: - Core Sync Logic

    // Codex N4: «¿hay un drenado activo? / arranco uno» es atómico; si no, reconexión y reintento manual podían
    // arrancar dos drenados a la vez sobre la misma cola.
    @Synchronized
    fun syncNow() {
        if (syncJob?.isActive == true) {
            syncRequested = syncContext()
            Log.d(TAG, "Sync already in progress, one wake requested")
            return
        }

        val scope = syncScope
        val cycle = syncCycle
        val context = syncContext()
        syncRequested = null
        // Install the owner and completion hook before the body can finish, even on an immediate dispatcher.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // 🔴 EL CAJÓN VA PRIMERO, Y LOS COBROS DE UNA CAJA ESPERAN A SU APERTURA (F1).
                //
                // El orden NO es cosmético: un cobro en efectivo que aterriza ANTES que el `OPEN`
                // de su caja nace con `shiftId = null` y sin `CASH_SALE` en el cajón, y ya no hay
                // quién lo reatribuya — el barrido del servidor no puede, porque el `openedAt` que
                // acaba registrando es la hora del replay y no la real. Medido en una Samsung el
                // 5-sep-2026. Esperar un ciclo es barato; un cobro huérfano es permanente.
                //
                // 🔴 Y DESDE EL 21-OCT LA BARRERA ES POR CAJA Y SE EVALÚA FILA POR FILA (efectivo sin red en el turno
                // correcto): un cobro cuya caja ya está confirmada sale aunque haya otra apertura pendiente; uno sin caja
                // conserva la regla global de hoy. Y como el cierre de una caja espera a SUS cobros (barrera 2), una sola
                // sincronización da hasta [MAX_VUELTAS] vueltas caja → outbox → cobros mientras alguna avance: apertura A,
                // cobros de A, cierre de A, apertura B, cobros de B — en ese orden y sin esperar al siguiente ciclo.
                val esperan = mutableSetOf<String>()
                val bloqueadas = mutableSetOf<String>()
                var limpiado = false
                // El tope del cierre que espera a sus ventas cuenta intentos (ver [contarPaseDelCierre]): qué cierres retuvo esta
                // sincronización y si, después de retenerlos, la subida de cobros tuvo su oportunidad.
                val retenidos = mutableListOf<com.avoqado.pos.cashdrawer.data.PendingDrawerOp>()
                var corrioElDrenado = false
                var ultimaBarrera: Pair<Boolean, Set<String>> = true to emptySet()
                for (vuelta in 1..MAX_VUELTAS) {
                    val vivasAntes = cajon.operacionesVivasDelCajon()
                    val sinCajaPuedenSalir = cajon.sincronizarCajonPrimero()
                    cajon.cierreQueEspera()?.let { retenidos += it }
                    val cajasEsperando = cajon.cajasQueEsperanSuApertura()
                    ultimaBarrera = sinCajaPuedenSalir to cajasEsperando
                    val avanzoElCajon = cajon.operacionesVivasDelCajon() < vivasAntes
                    if (!sinCajaPuedenSalir) {
                        Log.w(TAG, "⏸️ La apertura de la caja aún no llega al servidor: los cobros SIN caja esperan al siguiente ciclo")
                    }
                    if (!limpiado) {
                        // Cleanup old synced payments
                        dao.deleteSynced(System.currentTimeMillis() - CLEANUP_AFTER_MS)
                        limpiado = true
                    }
                    // El efectivo de MESAS (`PAY_CASH` del outbox) también es cobro de su caja: sin esta vuelta, el cierre de A
                    // esperaría a un cobro de A que sólo sale con el siguiente disparo del outbox.
                    val delOutbox = try {
                        secureStorage.venueId?.takeIf { it.isNotBlank() }?.let { syncOutbox.replayNow(it) } ?: 0
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (e: Exception) { Log.e(TAG, "El outbox no se pudo reproducir en esta vuelta: ${e.message}"); 0 }
                    darPorCobradasLasAbandonadas()
                    val drenado = drenarCobros(sinCajaPuedenSalir, cajasEsperando, esperan, bloqueadas)
                    if (retenidos.isNotEmpty() && drenado.corrio) corrioElDrenado = true
                    if (drenado.cortada) break
                    if (!avanzoElCajon && delOutbox == 0 && drenado.resueltos == 0) break
                    if (vuelta < MAX_VUELTAS) {
                        Log.d(TAG, "🔁 La vuelta $vuelta avanzó (cajón=$avanzoElCajon, outbox=$delOutbox, cobros=${drenado.resueltos}): otra vuelta caja → cobros")
                    }
                }
                contarPaseDelCierre(retenidos, corrioElDrenado) {
                    drenarCobros(ultimaBarrera.first, ultimaBarrera.second, esperan, bloqueadas).corrio
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Sync batch error: ${e.message}")
            }
        }
        syncJob = job
        job.invokeOnCompletion { cause ->
            synchronized(this@PaymentSyncService) {
                // stop/new owner may already have replaced it; an old finalizer owns nothing now.
                if (syncJob !== job) return@synchronized
                syncJob = null
                val wake = syncRequested == context && cause == null && !job.isCancelled &&
                    syncScope === scope && scope.isActive && syncCycle == cycle && syncContext() == context
                syncRequested = null
                // The Job is REALLY completed and released here, never merely in its still-active finally.
                if (wake) syncNow()
            }
        }
        job.start()
    }

    /**
     * 🔴 D2 v7, punto 3: antes de cada pasada de la cola (también la primera, al arrancar), los apartados de efectivo que ya no son
     * de un flujo vivo pasan a `PENDING` con su misma llave y esta misma pasada los sube. Un fallo aquí no detiene la cola.
     */
    private suspend fun darPorCobradasLasAbandonadas() {
        try {
            // Rediseño r9: cada una entra al cajón del aparato en la MISMA escritura que la pasa a la cola (`SalidaDelApartado`).
            cashPaymentRepository.darPorCobradasLasAbandonadas().takeIf { it > 0 }
                ?.let { Log.d(TAG, "💵 $it cobro(s) en efectivo que nadie resolvió se dan por cobrados y suben con su llave") }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (e: Exception) { Log.e(TAG, "No se pudieron dar por cobrados los apartados sin dueño: ${e.message}") }
    }

    /**
     * 🔴 Ronda 1 (M4): un flujo de cobro acaba de soltar sus apartados (otra venta, pantalla recreada, `onCleared`). Se dan por
     * cobrados EN EL ACTO, aunque haya una vuelta corriendo (que `syncNow` se saltaría), para que no se vean «sin confirmar» hasta
     * la siguiente. Corre en el alcance de la cola, que sobrevive a la pantalla.
     */
    fun darPorCobradasLasAbandonadasYa(): Job = syncScope.launch { darPorCobradasLasAbandonadas() }

    /**
     * Lo que dejó UNA pasada del drenado. [cortada] = la red, el backoff, la sesión o un error detienen la sincronización.
     * [corrio] = la pasada recorrió la cola (no se detuvo antes de empezar por el servidor marcado caído o el servicio detenido):
     * sin eso, la sincronización no intentó subir nada y no le cuenta un pase al cierre ([contarPaseDelCierre]).
     */
    private data class Drenado(val resueltos: Int, val cortada: Boolean, val corrio: Boolean = true)

    /**
     * 🔴 EL TOPE DEL CIERRE QUE ESPERA A SUS VENTAS CUENTA INTENTOS, NO MINUTOS (founder, 24-oct-2026). Cuatro rondas de Codex
     * encontraron cuatro formas de contar tiempo sin que las ventas pudieran subir (sin red, servidor caído, app dormida o
     * Windows suspendida, reloj cambiado), y dos más que anotar el desenlace de cada cobro en cada salida de dos colas no se
     * puede hacer completo (r5, r6). Ahora: al terminar una sincronización cuya subida de cobros SÍ corrió, con el cierre retenido por sus
     * ventas (la marca de la última corrida TERMINADA del replay), si la consulta de salud del servidor (con su base de datos) contesta,
     * se le PIDE el pase al cajón ([com.avoqado.pos.cashdrawer.data.CashDrawerRepository.solicitarPaseConServidor]); lo cuenta la
     * siguiente corrida del replay que de verdad retenga ese cierre, bajo su candado, con al menos
     * [com.avoqado.pos.cashdrawer.data.ESPACIO_ENTRE_PASES_MS] de reloj del sistema encendido entre uno y otro (Codex apps r10–r11: así
     * la marca compartida sólo propone, y un cierre con algo vivo delante no cuenta). A los
     * [com.avoqado.pos.cashdrawer.data.PASES_DEL_CIERRE] pases sale igual y la venta tardía queda aparte con aviso. Sin red o
     * con el servidor caído no cuenta nada (y el cierre tampoco podría salir).
     */
    private suspend fun contarPaseDelCierre(
        retenidos: List<com.avoqado.pos.cashdrawer.data.PendingDrawerOp>,
        corrioElDrenado: Boolean,
        /** Una pasada real de la subida de cobros; devuelve si corrió. */
        drenarOtraVez: suspend () -> Boolean,
    ) {
        if (retenidos.isEmpty()) return
        if (!servidorContesta()) {
            Log.d(TAG, "📡 El servidor no contestó al terminar: el cierre retenido no suma pase")
            return
        }
        // 🔴 Codex apps r7 #1: un 500 de otra ruta (el outbox de Mesas) apaga la bandera del servidor y la subida no empieza; la
        // salud sí contestó (y volvió a encenderla), así que se da una pasada real antes de contar. Sin esto, el cierre no sumaba nunca.
        if (!corrioElDrenado && !drenarOtraVez()) return
        cajon.solicitarPaseConServidor(retenidos)
    }

    /** La consulta real del pase del cierre. Costura de prueba. */
    internal var servidorContesta: suspend () -> Boolean = { connectivityMonitor.elServidorContesta() }

    /**
     * La cola de cobros, FIFO, con las barreras de siempre más la de la caja de CADA fila ([elCobroPuedeSalir]). [esperan] y
     * [bloqueadas] duran toda la sincronización (no la vuelta): cada retenida pide `/sync/recovery` a lo más una vez.
     */
    private suspend fun drenarCobros(
        sinCajaPuedenSalir: Boolean,
        cajasEsperando: Set<String>,
        esperan: MutableSet<String>,
        bloqueadas: MutableSet<String>,
    ): Drenado {
        var resueltos = 0
        // 🔴 Adenda Q16 (founder, 16-oct) + Codex 2.ª pasada: una venta RETENIDA ya no tapa la cola. Se queda
        // PENDIENTE con su marca y la vuelta sigue; sólo esperan, sin tocar la red, las filas de su MISMA orden
        // (`identidadesDeOrden`, más la orden que se le adoptó o que su envío resolvió). Como iOS, cada lote lee la
        // cola PENDIENTE entera y el tope cuenta cobros INTENTADOS: lo que espera no gasta el lote (N6). `esperan`
        // dura la sincronización entera, vueltas incluidas ⇒ cada retenida pide `/sync/recovery` a lo más una vez. La red, el backoff, la recuperación
        // caída y la cuenta por sincronizar siguen cortando la vuelta (FIFO, C1).
        fun esperar(payment: PendingPaymentEntity, identidades: Set<String>) { esperan += payment.id; bloqueadas += identidades }
        var lotes = 0
        repeat(MAX_DRAIN_BATCHES) {
            // Cortada antes de recorrer nada en esta pasada (el primer lote): no corrió, no es un intento para el tope del cierre.
            if (!isStarted || !connectivityMonitor.isServerReachable.value) return Drenado(resueltos, cortada = true, corrio = lotes > 0)
            lotes++

            // ponytail: cada lote lee y decodifica toda la cola local (decenas de filas; la prueba usa 1,000). Techo:
            // con decenas de miles pendientes cada lote pesa. Salida: paginar por cursor (createdAt, id).
            val pending = dao.getPendingPayments(Int.MAX_VALUE).filter { it.id !in esperan }
            if (pending.isEmpty()) {
                Log.d(TAG, "No pending payments to sync")
                return Drenado(resueltos, cortada = false)
            }

            Log.d(TAG, "Syncing ${pending.size} pending payments")
            var intentados = 0
            var soltada = false

            for (payment in pending) {
                if (!isStarted) return Drenado(resueltos, cortada = true)
                if (intentados >= MAX_PAYMENTS_PER_SYNC) break // lote lleno: se relee la cola
                // 🔴 BARRERA 1 POR CAJA: si la apertura de la caja de ESTE cobro todavía no llega, la fila se detiene aquí
                // (FIFO: lo de detrás no se adelanta). Las anteriores ya salieron. No es un corte: si el cajón avanzó, la
                // siguiente vuelta la vuelve a evaluar.
                val cajaDeLaFila = payment.cashDrawerSessionId?.takeIf { it.isNotBlank() }
                if (!elCobroPuedeSalir(cajaDeLaFila, sinCajaPuedenSalir, cajasEsperando)) {
                    // Codex apps r1 #5 (como iOS): la fila SIN caja (versión anterior) espera, con su orden, pero no detiene a las
                    // de una caja ya confirmada; si las detuviera, el cierre de esa caja esperaría a una fila que espera a la
                    // apertura siguiente, que espera a ese cierre.
                    if (cajaDeLaFila == null) {
                        Log.w(TAG, "⏸️ ${payment.id}: cobro sin caja, espera a la apertura pendiente sin detener a los demás")
                        esperar(payment, identidadesDeOrden(payment))
                        continue
                    }
                    Log.w(TAG, "⏸️ ${payment.id}: la apertura de su caja ($cajaDeLaFila) aún no llega; la fila espera")
                    return Drenado(resueltos, cortada = false)
                }
                val identidades = identidadesDeOrden(payment)
                if (identidades.any { it in bloqueadas }) { esperar(payment, identidades); continue }

                if (payment.q15Reason != null) {
                    when (val recuperacion = recuperarOrdenRetenidaQ16(payment)) {
                        // Q16: se soltó ⇒ se relee la cola y sale en esta misma vuelta, en su lugar FIFO.
                        Recuperacion.Soltada -> { soltada = true; break }
                        Recuperacion.Pausa -> return Drenado(resueltos, cortada = true)
                        is Recuperacion.Retenida -> { esperar(payment, identidades + listOfNotNull(recuperacion.ordenAdoptada)); continue }
                    }
                }

                // FIFO: si el más viejo sigue en backoff, no adelantamos
                // cobros posteriores y esperamos el próximo trigger.
                if (payment.retryCount > 0) {
                    val backoff = calculateBackoff(payment.retryCount)
                    val timeSinceLastRetry = System.currentTimeMillis() - (payment.lastRetryAt ?: 0)
                    // Codex apps r7 #2: un último intento EN EL FUTURO (se corrigió la hora del aparato) no es una espera: se reintenta.
                    if (timeSinceLastRetry in 0 until backoff) {
                        Log.d(TAG, "Stopping drain at ${payment.id} — backoff not elapsed")
                        return Drenado(resueltos, cortada = true)
                    }
                }

                if (ConciliacionDelCobro.leerCaptura(payment.q15CaptureJson, payment.venueId, payment.id) == null) {
                    check(dao.retenerPagoQ15(payment.venueId, payment.id, ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA, System.currentTimeMillis()) == 1) { "No se pudo conservar el cobro por revisar" }
                    esperar(payment, identidades); continue
                }
                if (requierePruebaDePrecio(payment)) {
                    check(dao.retenerPagoQ15(payment.venueId, payment.id, ConciliacionDelCobro.PRICE_PROOF_PENDING, System.currentTimeMillis()) == 1)
                    esperar(payment, identidades); continue
                }
                if (payment.orderId != null) {
                    val trabajo = syncOutbox.trabajoSinResolverDeLaOrden(
                        payment.venueId, setOf(payment.orderId),
                    )
                    if (trabajo.enCamino > 0) {
                        Log.d(TAG, "Stopping drain at ${payment.id} — order changes still queued")
                        return Drenado(resueltos, cortada = true)
                    }
                }

                intentados++
                dao.updateStatus(payment.id, PaymentSyncStatus.SYNCING.name)
                var resuelta: String? = null
                if (syncPayment(payment) { resuelta = it }) {
                    // Codex N3/N4: «retenida» lo dice la FILA (quedó PENDIENTE con su marca en la misma escritura),
                    // no una bandera. Retenida ⇒ sólo espera su orden (más la que su envío resolvió, N1); lo demás corta.
                    val fila = dao.findForVenue(payment.venueId, payment.id)
                    if (fila != null && fila.syncStatus == PaymentSyncStatus.PENDING.name && fila.q15Reason != null) {
                        esperar(payment, identidades + listOfNotNull(resuelta)); continue
                    }
                    return Drenado(resueltos, cortada = true)
                }
                resueltos++
            }

            if (!soltada && intentados < MAX_PAYMENTS_PER_SYNC) return Drenado(resueltos, cortada = false)
        }
        Log.w(TAG, "Drain alcanzó el límite de seguridad de $MAX_DRAIN_BATCHES batches")
        return Drenado(resueltos, cortada = true)
    }

    /** Cobros que agotaron reintentos o que el servidor rechazó permanentemente. */
    suspend fun failedPayments(): List<PendingPaymentEntity> = dao.getFailedPayments().filter(RevisionCanceladoCero::mostrarFallido)

    data class PropuestaRevisionCortesia(
        val original: PendingPaymentEntity,
        val actor: String,
        val rol: String,
        val contexto: Long,
        val expected: kotlinx.serialization.json.JsonObject,
    )

    /** Consulta la cuenta original sin modificar la fuente ni habilitar su envío. */
    suspend fun prepararRevisionCortesia(id: String): PropuestaRevisionCortesia? {
        val venue = secureStorage.venueId?.takeIf(String::isNotBlank) ?: return null
        val actor = secureStorage.userId?.takeIf(String::isNotBlank) ?: return null
        val rol = secureStorage.userRole?.uppercase() ?: return null
        val contexto = syncOutbox.contextoDeSesionQ15()
        fun vigente() = contextoCortesiaVigente(venue, actor, rol, contexto, false)
        if (!vigente()) return null
        return try {
            val original = dao.findForVenue(venue, id)
            if (!vigente() || original == null) return null
            val entrada = entradaRevisionCortesia(original) ?: return null
            val data = consultarRevisionCortesia(venue, entrada, ::vigente) ?: return null
            if (!vigente() || data.textoCortesia("outcome") != "PREVIEW" || data.textoCortesia("originalPaymentKey") != original.id) return null
            val snapshot = data["snapshot"] as? JsonObject ?: return null
            if (!snapshotRevisionCortesiaValido(entrada, snapshot) || !vinculoRevisionCortesia(original, snapshot, ::vigente)) return null
            if (!vigente() || dao.findForVenue(venue, id) != original || !vigente()) return null
            PropuestaRevisionCortesia(original, actor, rol, contexto, snapshot)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }

    /** true sólo habilita K; la conciliación del drenado conserva la autoridad sobre su desenlace. */
    suspend fun confirmarRevisionCortesia(propuesta: PropuestaRevisionCortesia): Boolean {
        val original = propuesta.original
        fun vigente() = contextoCortesiaVigente(original.venueId, propuesta.actor, propuesta.rol, propuesta.contexto, true)
        if (!vigente()) return false
        return try {
            if (dao.findForVenue(original.venueId, original.id) != original || !vigente()) return false
            val entrada = entradaRevisionCortesia(original) ?: return false
            if (!snapshotRevisionCortesiaValido(entrada, propuesta.expected)) return false
            val body = JsonObject(entrada + mapOf("confirm" to JsonPrimitive(true), "expected" to propuesta.expected))
            val data = consultarRevisionCortesia(original.venueId, body, ::vigente) ?: return false
            if (!vigente() || data.textoCortesia("outcome") !in setOf("APPLIED", "ALREADY_APPLIED") ||
                data.textoCortesia("originalPaymentKey") != original.id) return false
            val snapshot = data["snapshot"] as? JsonObject ?: return false
            if (!snapshotRevisionCortesiaValido(entrada, snapshot) ||
                !resultadoRevisionCortesiaValido(original, propuesta.expected, snapshot) ||
                !vinculoRevisionCortesia(original, snapshot, ::vigente) || !vigente()) return false
            val changed = dao.reanudarCortesiaAntiguaQ4(original, ::vigente)
            // COMMIT ya pudo ocurrir: una revocación no se representa como rollback ni despierta la sesión anterior.
            if (changed != 1 || !vigente()) return false
            syncNow()
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    private fun contextoCortesiaVigente(venue: String, actor: String, rol: String, contexto: Long, confirmar: Boolean): Boolean {
        if (venue.isBlank() || actor.isBlank() || secureStorage.venueId != venue || secureStorage.userId != actor ||
            secureStorage.userRole?.uppercase() != rol || syncOutbox.contextoDeSesionQ15() != contexto ||
            !RevisionCanceladoCero.esGerente(rol)) return false
        val roles = com.avoqado.pos.core.domain.RoleManager(secureStorage)
        // Lista efectiva actual; la lista antigua vacía conserva el respaldo gerente ya vigente en las revisiones.
        return secureStorage.venuePermissions.isEmpty() || roles.hasVenuePermission(if (confirmar) "orders:comp" else "orders:read")
    }

    /** Sólo proyecta el contrato público desde el original íntegro. Nunca reserializa para persistirlo. */
    private fun entradaRevisionCortesia(original: PendingPaymentEntity): JsonObject? {
        if (original.syncStatus != "FAILED" || original.q15Reason != null || original.paymentType != "ORDER" ||
            original.method != "CASH" || original.amountCents != 0 || original.tipCents != 0 ||
            original.tenderTypeId != null || original.tenderRevision != null) return null
        val capture = ConciliacionDelCobro.leerCaptura(original.q15CaptureJson, original.venueId, original.id) ?: return null
        if (capture.amountCents != 0 || capture.tipCents != 0 || capture.method != "CASH" ||
            capture.tenderTypeId != null || capture.tenderRevision != null || capture.splitType != original.splitType ||
            capture.customerId != original.customerId || capture.cashTenderedCents != original.cashTenderedCents ||
            capture.changeCents != original.changeCents || capture.orderRequestJson == null ||
            capture.orderRequestJson != original.orderRequestJson) return null
        val pedido = runCatching { json.parseToJsonElement(capture.orderRequestJson) as? JsonObject }.getOrNull() ?: return null
        val external = externalIdDelPedido(original.orderRequestJson) ?: return null
        val references = listOfNotNull(original.orderId, capture.orderId, capture.localOrderId, external)
        if (original.id.isBlank() || original.capturedOrderRef?.let { it.isBlank() || it !in references } == true) return null
        if (pedido.enteroCortesia("total") != 0) return null
        val items = pedido["items"] as? JsonArray ?: return null
        if (items.size !in 1..100) return null
        val lines = ArrayList<JsonElement>(items.size)
        val externalIds = HashSet<String>()
        for (raw in items) {
            val item = raw as? JsonObject ?: return null
            val lineId = item.textoCortesia("externalId")?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return null
            val product = item.textoCortesia("productId")?.takeIf(String::isNotBlank) ?: return null
            val quantity = item.enteroCortesia("quantity")?.takeIf { it > 0 } ?: return null
            val reason = item.textoCortesia("cortesiaReason")?.takeIf { it.length <= 500 && it.isNotBlank() } ?: return null
            if (!externalIds.add(lineId) || item.booleanCortesia("isCortesia") != true ||
                item["promotionRef"]?.let { it != JsonNull } == true) return null
            val modifiers = if (item["modifierIds"] == null) JsonArray(emptyList()) else item["modifierIds"] as? JsonArray ?: return null
            if (modifiers.size > 100 || modifiers.any { (it as? JsonPrimitive)?.takeIf { id -> id.isString && id.content.isNotBlank() } == null }) return null
            lines += buildJsonObject {
                put("externalId", lineId); put("productId", product); put("quantity", quantity)
                put("modifierIds", modifiers); put("isCortesia", true); put("cortesiaReason", reason.trim())
            }
        }
        return buildJsonObject {
            put("originalPaymentKey", original.id); put("externalId", external)
            put("expectedAmount", 0); put("expectedTip", 0); put("lines", JsonArray(lines)); put("confirm", false)
        }
    }

    private suspend fun consultarRevisionCortesia(venue: String, body: JsonObject, vigente: () -> Boolean): JsonObject? {
        if (!vigente()) return null
        return withContext(Dispatchers.IO) {
            // La espera del dispatcher también puede cambiar sesión/permisos. El cliente conserva su autenticación instalada.
            if (!vigente() || !connectivityMonitor.isConnected.value || !connectivityMonitor.isServerReachable.value) return@withContext null
            val request = Request.Builder().url("${ApiConstants.BASE_URL}/mobile/venues/$venue/orders/courtesy-review")
                .post(body.toString().toRequestBody(JSON_MEDIA)).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful || !vigente()) return@use null
                val root = json.parseToJsonElement(response.body?.string() ?: return@use null) as? JsonObject ?: return@use null
                if (!vigente() || root.booleanCortesia("success") != true) return@use null
                root["data"] as? JsonObject
            }
        }
    }

    private suspend fun vinculoRevisionCortesia(original: PendingPaymentEntity, snapshot: JsonObject, vigente: () -> Boolean): Boolean {
        val canonical = snapshot.textoCortesia("orderId") ?: return false
        val capture = ConciliacionDelCobro.leerCaptura(original.q15CaptureJson, original.venueId, original.id) ?: return false
        for (bound in listOfNotNull(original.orderId, capture.orderId, capture.localOrderId).distinct()) {
            if (!vigente() || bound.isBlank()) return false
            if (bound != canonical) {
                val resolved = syncOutbox.aliasDurableQ16(original.venueId, bound)
                if (!vigente() || resolved != canonical) return false
            }
        }
        return vigente()
    }

    /** Presupuesto acotado y tupla pública PESOS completa; no calcula descuentos ni el resultado financiero. */
    private fun snapshotRevisionCortesiaValido(entrada: JsonObject, snapshot: JsonObject): Boolean {
        if (snapshot.textoCortesia("orderId").isNullOrBlank() || snapshot.textoCortesia("externalId") != entrada.textoCortesia("externalId") ||
            listOf("orderNumber", "status", "paymentStatus", "originSystem", "contratoDePrecio").any { snapshot.textoCortesia(it) == null } ||
            snapshot.enteroCortesia("version")?.let { it >= 0 } != true ||
            listOf("tableId", "servedById").any { snapshot[it] != JsonNull && snapshot.textoCortesia(it) == null }) return false
        val money = listOf("subtotal", "discountAmount", "taxAmount", "serviceChargeAmount", "deliveryFeeAmount", "tipAmount", "total", "paidAmount", "remainingBalance")
        if (money.any { snapshot.centavosCortesia(it) == null } || snapshot.centavosCortesia("total")!! < 0 ||
            snapshot.centavosCortesia("paidAmount") != 0L || snapshot.centavosCortesia("tipAmount") != 0L ||
            snapshot.textoCortesia("paymentStatus") != "PENDING" || snapshot.textoCortesia("originSystem") == "POS_SOFTRESTAURANT" ||
            snapshot.textoCortesia("status") !in setOf("PENDING", "CONFIRMED", "PREPARING", "READY")) return false
        val originals = (entrada["lines"] as? JsonArray)?.map { it as? JsonObject ?: return false } ?: return false
        val actuals = snapshot["lines"] as? JsonArray ?: return false
        if (actuals.size !in 1..100 || actuals.size != originals.size) return false
        val source = originals.associateBy { it.textoCortesia("externalId") }
        val lineIds = HashSet<String>(); val externalIds = HashSet<String>(); val modifierRowIds = HashSet<String>()
        for (raw in actuals) {
            val line = raw as? JsonObject ?: return false
            val original = source[line.textoCortesia("externalId")] ?: return false
            val id = line.textoCortesia("id")?.takeIf(String::isNotBlank) ?: return false
            if (!lineIds.add(id) || !externalIds.add(line.textoCortesia("externalId")!!) ||
                line.textoCortesia("productId") != original.textoCortesia("productId") ||
                line.enteroCortesia("quantity") != original.enteroCortesia("quantity") ||
                listOf("unitPrice", "total", "discountAmount", "taxAmount").any { line.centavosCortesia(it) == null } ||
                line.booleanCortesia("isCortesia") == null ||
                (line["cortesiaReason"] != JsonNull && line.textoCortesia("cortesiaReason") == null) ||
                (line["appliedDiscountId"] != JsonNull && line.textoCortesia("appliedDiscountId") == null)) return false
            if (line.textoCortesia("cortesiaReason")?.length?.let { it > 500 } == true) return false
            if (line.booleanCortesia("isCortesia") == true &&
                (line.textoCortesia("cortesiaReason") != original.textoCortesia("cortesiaReason") || line.centavosCortesia("total") != 0L)) return false
            val modifierIds = line["modifierIds"] as? JsonArray ?: return false
            val modifiers = line["modifiers"] as? JsonArray ?: return false
            if (modifierIds.size > 100 || modifiers.size > 100) return false
            val expanded = ArrayList<String>()
            for (modifierRaw in modifiers) {
                val modifier = modifierRaw as? JsonObject ?: return false
                val rowId = modifier.textoCortesia("id")?.takeIf(String::isNotBlank) ?: return false
                val modifierId = modifier.textoCortesia("modifierId")?.takeIf(String::isNotBlank) ?: return false
                val quantity = modifier.enteroCortesia("quantity") ?: return false
                if (!modifierRowIds.add(rowId) || quantity !in 1..(100 - expanded.size) || modifier.centavosCortesia("price") == null) return false
                repeat(quantity) { expanded.add(modifierId) }
            }
            val declared = modifierIds.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: return false }.sorted()
            val originalModifiers = (original["modifierIds"] as JsonArray).map { (it as JsonPrimitive).content }.sorted()
            if (declared != expanded.sorted() || declared != originalModifiers) return false
        }
        return true
    }

    private fun resultadoRevisionCortesiaValido(original: PendingPaymentEntity, expected: JsonObject, actual: JsonObject): Boolean {
        if (listOf("orderId", "externalId", "orderNumber", "status", "paymentStatus", "originSystem", "contratoDePrecio", "tableId", "servedById").any { expected[it] != actual[it] }) return false
        val before = (expected["lines"] as JsonArray).map { it as JsonObject }.associateBy { it.textoCortesia("externalId") }
        for (raw in actual["lines"] as JsonArray) {
            val line = raw as JsonObject; val previous = before[line.textoCortesia("externalId")] ?: return false
            if (line["id"] != previous["id"] || line.centavosCortesia("unitPrice") != previous.centavosCortesia("unitPrice") ||
                line["modifiers"] != previous["modifiers"]) return false
        }
        if (actual.enteroCortesia("version")!! < expected.enteroCortesia("version")!!) return false
        // Reutiliza la prueba existente de total/cortesías originales, adaptando sólo la respuesta temporal de revisión.
        val evidence = buildJsonObject { put("data", buildJsonObject { put("total", actual.getValue("total")); put("items", actual.getValue("lines")) }) }
        return CortesiaPersistida.confirmada(requireNotNull(original.orderRequestJson), evidence.toString())
    }

    private fun JsonObject.textoCortesia(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.booleanCortesia(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    private fun JsonObject.enteroCortesia(key: String) = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
    private fun JsonObject.centavosCortesia(key: String): Long? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.content?.let {
        // aCentavos() redondea para otros flujos; aquí una fracción no representable se rechaza, nunca se redondea.
        runCatching { it.toBigDecimal().movePointRight(2).longValueExact() }.getOrNull()
            ?.takeIf { cents -> cents in -9_007_199_254_740_991L..9_007_199_254_740_991L }
    }

    data class PropuestaRevisionCancelada(
        val original: PendingPaymentEntity, val actor: String, val rol: String, val contexto: Long,
        val ordenAcreditada: String? = null,
    )
    private fun vigente(propuesta: PropuestaRevisionCancelada) = secureStorage.venueId == propuesta.original.venueId &&
        secureStorage.userId == propuesta.actor && secureStorage.userRole?.uppercase() == propuesta.rol &&
        RevisionCanceladoCero.esGerente(propuesta.rol) && syncOutbox.contextoDeSesionQ15() == propuesta.contexto

    private suspend fun consultarCancelado(row: PendingPaymentEntity): String? {
        val external = RevisionCanceladoCero.externalId(row) ?: return null
        val body = buildJsonObject {
            put("externalOrderIds", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(external))))
            put("includeCancelledPaymentEvidence", true)
        }.toString()
        return try {
            withContext(Dispatchers.IO) {
                val request = Request.Builder().url("${ApiConstants.BASE_URL}/mobile/venues/${row.venueId}/sync/recovery")
                    .post(body.toRequestBody(JSON_MEDIA)).build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) RevisionCanceladoCero.evidencia(row, response.body?.string().orEmpty()) else null
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { null }
    }

    private val revisionCanceladaMutex = kotlinx.coroutines.sync.Mutex()

    /** Sólo consulta; no habilita replay ni despierta el drenador. */
    suspend fun prepararRevisionCancelada(id: String): PropuestaRevisionCancelada? {
        val venue = secureStorage.venueId ?: return null
        val original = dao.findForVenue(venue, id) ?: return null
        return (prepararRevisionCancelada(original) as? RetryOutcome.CancelledZeroPaymentReview)?.propuesta
    }

    private suspend fun prepararRevisionCancelada(original: PendingPaymentEntity): RetryOutcome {
        if ((original.syncStatus != "FAILED" && !(original.syncStatus == "EN_VUELO" &&
                original.q15Reason == ConciliacionDelCobro.RESULTADO_NO_COMPROBADO)) ||
            original.paymentType != "ORDER" || original.amountCents != 0 || original.tipCents != 0) return RetryOutcome.StillQueued
        val actor = secureStorage.userId?.takeIf { it.isNotBlank() } ?: return RetryOutcome.StillQueued
        val propuesta = PropuestaRevisionCancelada(original, actor, secureStorage.userRole?.uppercase().orEmpty(), syncOutbox.contextoDeSesionQ15())
        if (!vigente(propuesta) || original.q15ReviewedAt != null) return RetryOutcome.StillQueued
        val proof = consultarCancelado(original) ?: return RetryOutcome.ConsultaSinPrueba
        val orderId = RevisionCanceladoCero.ordenAcreditada(original, proof) ?: return RetryOutcome.ConsultaSinPrueba
        if (!vigente(propuesta) || dao.findForVenue(original.venueId, original.id) != original) return RetryOutcome.StillQueued
        return RetryOutcome.CancelledZeroPaymentReview(propuesta.copy(ordenAcreditada = orderId))
    }

    /** Confirmation is a new read, never permission inferred from the proposal or a timestamp. */
    suspend fun confirmarRevisionCancelada(propuesta: PropuestaRevisionCancelada): Boolean {
        if (!revisionCanceladaMutex.tryLock()) return false
        try {
            if (!vigente(propuesta) || propuesta.ordenAcreditada.isNullOrBlank()) return false
            val original = propuesta.original
            if (dao.findForVenue(original.venueId, original.id) != original || original.q15ReviewedAt != null) return false
            val proof = consultarCancelado(original) ?: return false
            if (RevisionCanceladoCero.ordenAcreditada(original, proof) != propuesta.ordenAcreditada) return false
            return com.avoqado.pos.core.data.sync.AdmisionDePrecioQ16.write(original.venueId) {
                if (!vigente(propuesta) || dao.findForVenue(original.venueId, original.id) != original) return@write false
                dao.revisarCanceladoSinCobroQ15(original.venueId,original.id,original.orderId,
                    original.orderRequestJson,original.q15ResultJson,original.syncStatus,original.q15CaptureJson,
                    original.createdAt,original.capturedOrderRef,original.splitType,original.customerId,
                    original.cashTenderedCents,original.changeCents,original.q15Reason,proof,propuesta.actor,propuesta.rol,System.currentTimeMillis()) == 1
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { return false }
        finally { revisionCanceladaMutex.unlock() }
    }

    /**
     * Reabre explícitamente un cobro fallido para conciliación. Nunca crea una
     * llave nueva: conserva [PendingPaymentEntity.id] y por tanto la garantía
     * de idempotencia del intento original.
     */
    /**
     * Reintento MANUAL desde "Operaciones por revisar", con el desenlace real.
     *
     * 🔴 Antes devolvía Unit y la pantalla pintaba una palomita verde pasara lo
     * que pasara. Con el cobro rechazado por el server (400), el gerente veía
     * "Cobro enviado de nuevo a sincronización" y el mismo cobro seguía en la
     * lista: la pantalla decía que sí y los hechos decían que no. En una
     * pantalla cuyo único trabajo es responder "¿este cobro entró?", esa mentira
     * es peor que no tener el botón.
     */
    suspend fun retryFailedPayment(id: String): RetryOutcome {
        val venue = secureStorage.venueId ?: return RetryOutcome.StillQueued
        val original = dao.findForVenue(venue, id) ?: return RetryOutcome.StillQueued
        if (original.syncStatus == "SYNCED") return RetryOutcome.Applied
        if ((original.syncStatus == "FAILED" || (original.syncStatus == "EN_VUELO" &&
                original.q15Reason == ConciliacionDelCobro.RESULTADO_NO_COMPROBADO)) &&
            original.paymentType == "ORDER" && original.amountCents == 0 && original.tipCents == 0) {
            return prepararRevisionCancelada(original)
        }
        if (original.syncStatus != "FAILED" || original.q15Reason != null) return RetryOutcome.StillQueued
        if (ConciliacionDelCobro.leerCaptura(original.q15CaptureJson, original.venueId, original.id) == null) {
            check(dao.retenerPagoQ15(original.venueId, original.id, ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA, System.currentTimeMillis()) == 1)
            return RetryOutcome.StillQueued
        }
        if (requierePruebaDePrecio(original)) {
            check(dao.retenerPagoQ15(original.venueId, original.id, ConciliacionDelCobro.PRICE_PROOF_PENDING, System.currentTimeMillis()) == 1)
            return RetryOutcome.StillQueued
        }
        dao.retryFailed(id)
        syncNow()
        // `syncNow()` corre en su propio scope: hay que ESPERAR (con tope) el
        // desenlace de ESTA fila. Leer el estado de inmediato contestaba
        // "quedó en cola" con el server contestando 400 un segundo después —
        // el mensaje salía antes del round-trip (visto en la T3, 2026-08-09).
        // El tope lo pone withTimeoutOrNull, NO System.currentTimeMillis(): la espera
        // usa delay(), y tope y espera tienen que leer el MISMO reloj. Mezclarlos
        // colgaba la suite — bajo runTest delay() es tiempo virtual y vuelve al
        // instante, así que el reloj de pared no avanzaba y el bucle giraba sin fin
        // hasta agotar el heap.
        return withTimeoutOrNull(12_000) {
            var outcome: RetryOutcome? = null
            while (outcome == null) {
                outcome = when (dao.syncStatusOf(id)) {
                    // La fila desaparece si otra pasada ya la limpió: se aplicó.
                    PaymentSyncStatus.SYNCED.name -> RetryOutcome.Applied
                    null -> RetryOutcome.StillQueued
                    PaymentSyncStatus.FAILED.name -> RetryOutcome.Rejected(dao.lastErrorOf(id))
                    else -> {
                        delay(400)
                        null
                    }
                }
            }
            outcome
        }
        // Tope alcanzado sin resolución: genuinamente en cola (sin red o server
        // lento). No es éxito ni rechazo.
            ?: RetryOutcome.StillQueued
    }

    /**
     * El gerente resolvió el cobro a mano y lo descarta de la cuarentena.
     * Devuelve false si la fila ya no estaba FAILED (otra pasada la movió).
     */
    suspend fun dismissFailedPayment(id: String): Boolean = dao.deleteFailed(id) > 0

    /** Qué pasó con un reintento manual. */
    sealed interface RetryOutcome {
        /** El server lo aceptó (o ya lo tenía). Se acabó. */
        data object Applied : RetryOutcome
        /** Sigue encolado — típicamente sin red. Se reintentará solo. */
        data object StillQueued : RetryOutcome
        /** La consulta no acreditó cancelación sin cobros; se conserva sin replay. */
        data object ConsultaSinPrueba : RetryOutcome
        /** El server lo rechazó otra vez. Necesita a un humano. */
        data class Rejected(val error: String?) : RetryOutcome
        data class CancelledZeroPaymentReview(val propuesta: PropuestaRevisionCancelada) : RetryOutcome
    }

    /** Lectura persistida para impedir logout/cambio de venue con trabajo oculto. */
    suspend fun blockingWorkCount(): Int =
        dao.getUnsyncedPayments().size + dao.getFailedPayments().count { !RevisionCanceladoCero.revisado(it) } +
            // Un cobro interrumpido (la app murió a media petición) tampoco puede irse con la sesión:
            // nadie ha decidido todavía si ese dinero entró.
            dao.enVueloCount()

    private fun calculateBackoff(retryCount: Int): Long {
        return minOf(
            (Math.pow(2.0, retryCount.toDouble()) * INITIAL_BACKOFF_MS).toLong(),
            MAX_BACKOFF_MS,
        )
    }

    // MARK: - Single Payment Sync

    /** [alResolverOrden] recibe la orden que creó `/orders`, para bloquear su cuenta si el envío termina retenido (Codex N1). */
    private suspend fun syncPayment(payment: PendingPaymentEntity, alResolverOrden: (String) -> Unit = {}): Boolean {
        // true = detener el drain (auth o infraestructura transitoria).
        val venueId = payment.venueId.ifBlank { secureStorage.venueId.orEmpty() }
        if (venueId.isBlank()) {
            dao.updateStatusWithError(
                payment.id,
                PaymentSyncStatus.FAILED.name,
                "No venueId for pending payment",
            )
            return false
        }

        try {
            // 🔴 La caja de la venta, ya traducida al id del servidor. Nula = no viaja (fila sin caja, o caja local sin alias).
            val cajaParaElServidor = cajaDelCobroParaElServidor(payment, venueId)
            var resolvedOrderId: String? = payment.orderId
            if (payment.paymentType == "ORDER" && (resolvedOrderId == null || CortesiaPersistida.solicitada(payment.orderRequestJson))) {
                when (val result = resolveMissingOrderId(payment, venueId, cajaParaElServidor)) {
                    is OrderResolution.Ready -> {
                        if (resolvedOrderId != null && resolvedOrderId != result.orderId) {
                            dao.updateStatusWithError(payment.id,PaymentSyncStatus.FAILED.name,CortesiaPersistida.AVISO)
                            return false
                        }
                        resolvedOrderId = result.orderId.also(alResolverOrden)
                    }
                    is OrderResolution.AuthExpired -> {
                        dao.updateStatus(payment.id, PaymentSyncStatus.PENDING.name)
                        return true
                    }
                    is OrderResolution.PermanentFailure -> {
                        dao.updateStatusWithError(payment.id, PaymentSyncStatus.FAILED.name, result.message)
                        return false
                    }
                    is OrderResolution.RetryableFailure -> {
                        handleNetworkError(payment, result.message)
                        return true
                    }
                }
            }

            val esCobroDeOrden = payment.paymentType == "ORDER" && resolvedOrderId != null
            val url: String

            if (esCobroDeOrden) {
                // Order payment: POST /mobile/venues/{venueId}/orders/{orderId}/pay
                url = "${ApiConstants.BASE_URL}/mobile/venues/$venueId/orders/$resolvedOrderId/pay"
            } else {
                // Fast payment: POST /mobile/venues/{venueId}/fast
                url = "${ApiConstants.BASE_URL}/mobile/venues/$venueId/fast"
            }

            val capture = ConciliacionDelCobro.leerCaptura(payment.q15CaptureJson,venueId,payment.id)
            val originalId = capture?.orderId
            val aliasComprobado = if (esCobroDeOrden && originalId != null && originalId != resolvedOrderId) {
                val external = runCatching { json.parseToJsonElement(payment.orderRequestJson ?: "").jsonObject["externalId"] as? kotlinx.serialization.json.JsonPrimitive }.getOrNull()
                val durable = try { syncOutbox.aliasDurableQ16(venueId,originalId) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                if (external?.isString != true || external.content.isBlank() || durable != resolvedOrderId) {
                    // Codex N3: PENDIENTE y con su marca en la MISMA escritura (la de resultado); si no se logra, corta la vuelta.
                    check(dao.registrarResultadoQ15(venueId,payment.id,false,ConciliacionDelCobro.PRICE_PROOF_PENDING,null,System.currentTimeMillis()) == 1)
                    return true
                }
                originalId to requireNotNull(resolvedOrderId)
            } else null
            val bodyJson = buildReplayBody(payment, venueId, esCobroDeOrden, cajaParaElServidor)

            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(JSON_MEDIA))
                .build()

            val (code, responseBody, headers) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                Triple(
                    response.code,
                    response.body?.string() ?: "",
                    response.header("content-type") to response.header("ngrok-error-code"),
                )
            }

            return handleSyncResult(payment, code, responseBody, headers.first, headers.second, aliasComprobado)

        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (e: com.avoqado.pos.core.data.network.SesionSinRenovar) {
            // La sesión venció sin red para renovarla: igual que el 401 de siempre, no gasta
            // reintentos (diez parpadeos del internet no pueden mandar un efectivo a cuarentena).
            Log.w(TAG, "⚠️ Sesión sin renovar (${e.motivo}) — pausing sync, payment ${payment.id} stays PENDING")
            dao.updateStatus(payment.id, PaymentSyncStatus.PENDING.name)
            return true
        } catch (e: java.net.UnknownHostException) {
            handleNetworkError(payment, "Sin conexión")
            return true
        } catch (e: java.net.ConnectException) {
            handleNetworkError(payment, "No se pudo conectar al servidor")
            return true
        } catch (e: java.io.IOException) {
            handleNetworkError(payment, e.message ?: "Error de red")
            return true
        } catch (e: Exception) {
            handleNetworkError(payment, e.message ?: "Error desconocido")
            return true
        }
    }

    /**
     * La caja con la que este cobro viaja: la de la fila, traducida por el alias del cajón. Sólo se pregunta si la fila trae
     * caja (una fila vieja no toca el cajón), y un id inválido para el servidor no se manda (sería un 400 permanente).
     */
    private fun cajaDelCobroParaElServidor(payment: PendingPaymentEntity, venueId: String): String? =
        payment.cashDrawerSessionId?.takeIf { it.isNotBlank() }
            ?.let { cajon.idDeCajaParaElServidor(it, venueId) }
            ?.takeIf { it.isNotBlank() && it.length <= LARGO_MAXIMO_DE_LA_CAJA }

    private suspend fun resolveMissingOrderId(payment: PendingPaymentEntity, venueId: String, cajaParaElServidor: String?): OrderResolution {
        val orderRequestJson = payment.orderRequestJson
            ?: return OrderResolution.PermanentFailure("Missing orderRequestJson for ORDER sync")

        // La orden que la cola crea nace en el turno de la caja de la venta (el pedido congelado no cambia en disco).
        val request = Request.Builder()
            .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/orders")
            .post(pedidoConLaCaja(orderRequestJson, cajaParaElServidor).toRequestBody(JSON_MEDIA))
            .build()

        return try {
            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299 && !CortesiaPersistida.confirmada(orderRequestJson, body)) {
                return OrderResolution.PermanentFailure(CortesiaPersistida.AVISO)
            }
            classifyOrderCreation(code, body).also {
                if (it is OrderResolution.Ready) {
                    Log.d(TAG, "✅ Recreated order for queued payment ${payment.id}: ${it.orderId}")
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (e: com.avoqado.pos.core.data.network.SesionSinRenovar) {
            OrderResolution.AuthExpired
        } catch (e: java.net.UnknownHostException) {
            OrderResolution.RetryableFailure("Sin conexión")
        } catch (e: java.net.ConnectException) {
            OrderResolution.RetryableFailure("No se pudo conectar al servidor")
        } catch (e: java.io.IOException) {
            OrderResolution.RetryableFailure(e.message ?: "Error de red")
        } catch (e: Exception) {
            OrderResolution.RetryableFailure(e.message ?: "Error recreando orden")
        }
    }

    private fun extractOrderId(body: String): String? = Companion.extractOrderId(body)

    @androidx.annotation.VisibleForTesting
    internal suspend fun handleSyncResult(
        payment: PendingPaymentEntity,
        code: Int,
        body: String,
        contentType: String? = null,
        ngrokError: String? = null,
        aliasComprobado: Pair<String,String>? = null,
    ): Boolean {
        return when {
            code in 200..299 -> {
                val evaluation = ConciliacionDelCobro.evaluar(payment.q15CaptureJson, payment.venueId, payment.id,
                    if (payment.paymentType == "FAST") CanalDelCobro.FAST else CanalDelCobro.ORDER, body, aliasComprobado,
                    // Q16: la orden nació en la cola desde el pedido de esta fila (`resolveMissingOrderId`, o la que adoptó
                    // la recuperación). Se decide con la captura: la adopción ya le escribió `orderId` a la fila (Codex P1-4).
                    ordenNacidaEnLaCola = nacioSinOrden(payment))
                try {
                    check(dao.registrarResultadoQ15(payment.venueId, payment.id, evaluation.aplicado,
                        evaluation.motivo, evaluation.resultadoJson, System.currentTimeMillis()) == 1) { "No se pudo conservar el resultado del cobro" }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) {
                    // Codex N3: el respaldo deja la fila PENDIENTE con su marca en una sola escritura (no SYNCING).
                    runCatching { dao.registrarResultadoQ15(payment.venueId,payment.id,false,ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,null,System.currentTimeMillis()) }
                    Log.e(TAG,"No se pudo conservar el resultado; el cobro sigue guardado",e)
                    return true
                }
                !evaluation.aplicado // Unknown identity holds this UUID (and, desde la adenda Q16, sólo a las filas de su orden); applied never replays.
            }
            code == 401 -> {
                // Auth expired — don't burn retries, stop batch
                Log.w(TAG, "⚠️ Auth expired — pausing sync, payment ${payment.id} stays PENDING")
                dao.updateStatus(payment.id, PaymentSyncStatus.PENDING.name)
                true  // STOP batch
            }
            code == 409 && codigoDelCuerpo(body) == ConciliacionDelCobro.PRICE_PROOF_PENDING -> {
                try { check(dao.registrarResultadoQ15(payment.venueId, payment.id, false,
                    ConciliacionDelCobro.PRICE_PROOF_PENDING, null, System.currentTimeMillis()) == 1) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { Log.e(TAG, "No se pudo conservar la espera de precio", error) }
                true
            }
            // 🔴 El servidor está CERRANDO el turno de este cobro (efectivo sin red en el turno correcto): dura milisegundos. Se
            // queda PENDIENTE sin gastar un reintento —diez cierres seguidos no pueden mandar un efectivo a cuarentena— y la
            // fila se detiene (FIFO). Es el mismo código que el cajón ya trata como transitorio.
            code == 409 && codigoDelCuerpo(body) == CODIGO_CIERRE_EN_PROCESO -> {
                Log.w(TAG, "⏸️ Payment ${payment.id}: el servidor está cerrando su turno ($CODIGO_CIERRE_EN_PROCESO); sigue PENDIENTE sin gastar reintento")
                dao.updateStatus(payment.id, PaymentSyncStatus.PENDING.name)
                true
            }
            code == 409 -> {
                // 🔴 Un 409 NO afirma que el cobro haya quedado registrado.
                //
                // Se daba por hecho que 409 == "duplicado, ya lo tengo" y se marcaba SYNCED,
                // o sea que se BORRABA de la cola. Pero el reintento idempotente de verdad
                // responde 200 con el pago existente (`order.mobile.service.ts`, rama
                // `Reintento idempotente detectado`) — nunca 409. Los 409 que sí manda ese
                // endpoint significan lo contrario: `ORDER_PAYMENT_CONFLICT` es
                // "la cuenta cambió mientras se cobraba, NO lo registré, vuelve a intentar".
                // Marcarlo SYNCED perdía la venta con el efectivo ya en el cajón.
                //
                // Reintentar es seguro SIEMPRE: `idempotencyKey` = el id local del pago y
                // viaja idéntico en cada intento, así que el server deduplica por
                // [venueId, idempotencyKey]. O cae en el camino idempotente (200) o lo
                // registra bien. Agotados los reintentos cae a cuarentena VISIBLE, nunca
                // a un borrado silencioso.
                //
                // Es la misma lección del doble cobro con tarjeta: nunca concluir un
                // desenlace desde una respuesta que no lo afirma.
                Log.w(TAG, "⚠️ Payment ${payment.id} recibió 409 (no confirma registro) — se reintenta: $body")
                handleNetworkError(payment, "Conflicto al registrar el cobro; se reintentará")
                true  // corta el batch para preservar el FIFO
            }
            // 🔴 Un 4xx de un INTERMEDIARIO (portal cautivo, proxy de plaza,
            // túnel caído) no dice nada de la venta: es red, no negocio. Mandarlo
            // a cuarentena marcaba un cobro legítimo como fallido permanente.
            OrderRepository.isTransient4xx(code, contentType, ngrokError, body) -> {
                handleNetworkError(payment, "Respuesta $code de la red, no del servidor de Avoqado")
                true
            }
            code in 400..499 -> {
                // Rechazo de NEGOCIO de nuestra API — permanente, a cuarentena.
                Log.e(TAG, "❌ Payment ${payment.id} failed permanently: $code - $body")
                dao.updateStatusWithError(payment.id, PaymentSyncStatus.FAILED.name, "Error $code: $body")
                false
            }
            code >= 500 -> {
                // Server error — retryable
                handleNetworkError(payment, "Error del servidor ($code)")
                true
            }
            else -> {
                handleNetworkError(payment, "Código inesperado: $code")
                true
            }
        }
    }

    /** El `code` de negocio del cuerpo de un error (`{code}` o `{error:{code}}`), o `null`. Se lee la LLAVE, nunca el texto. */
    private fun codigoDelCuerpo(body: String): String? = runCatching {
        val value = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
        val reason = value?.get("code") ?: (value?.get("error") as? kotlinx.serialization.json.JsonObject)?.get("code")
        (reason as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
    }.getOrNull()

    private suspend fun handleNetworkError(payment: PendingPaymentEntity, error: String) {
        if (payment.retryCount >= MAX_RETRIES) {
            Log.e(TAG, "❌ Payment ${payment.id} exceeded max retries — marking FAILED")
            dao.updateStatusWithError(payment.id, PaymentSyncStatus.FAILED.name, error)
        } else {
            Log.w(TAG, "⚠️ Payment ${payment.id} retry ${payment.retryCount + 1}/$MAX_RETRIES: $error")
            dao.incrementRetry(payment.id)
        }
    }

    // MARK: - Count Refresh

    private fun startCountObservers() {
        pendingCountJob?.cancel()
        failedCountJob?.cancel()

        pendingCountJob = syncScope.launch {
            dao.getPendingCount().collect { _pendingCount.value = it }
        }
        failedCountJob = syncScope.launch {
            dao.getFailedCount().collect { _failedCount.value = it }
        }
    }
}
