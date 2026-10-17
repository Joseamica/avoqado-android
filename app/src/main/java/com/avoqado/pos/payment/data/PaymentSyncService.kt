package com.avoqado.pos.payment.data

import android.util.Log
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import kotlinx.coroutines.CoroutineScope
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
        }.toString()

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
    internal fun usarContextoDeSincronizacion(contexto: kotlin.coroutines.CoroutineContext) {
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

    fun start() {
        if (isStarted) return
        isStarted = true
        Log.d(TAG, "Starting payment sync service")

        syncScope.launch {
            // Reset stuck SYNCING payments (crash recovery)
            dao.resetSyncingToPending()
            startCountObservers()

            // Immediate sync attempt
            syncNow()
        }

        // Start periodic timer
        startTimer()

        // Listen for connectivity changes
        startConnectivityListener()
    }

    fun stop() {
        if (!isStarted) return
        isStarted = false
        Log.d(TAG, "Stopping payment sync service")
        timerJob?.cancel()
        connectivityJob?.cancel()
        syncJob?.cancel()
        pendingCountJob?.cancel()
        failedCountJob?.cancel()
    }

    // MARK: - Timer

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = syncScope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                if (
                    connectivityMonitor.isConnected.value &&
                    connectivityMonitor.isServerReachable.value
                ) {
                    syncNow()
                }
            }
        }
    }

    // MARK: - Connectivity Listener

    private fun startConnectivityListener() {
        connectivityJob?.cancel()
        connectivityJob = syncScope.launch {
            alReconectar(
                combine(
                    connectivityMonitor.isConnected,
                    connectivityMonitor.isServerReachable,
                ) { network, server -> network && server },
            ) {
                // 🔴 `syncNow()` arranca por el CAJÓN. Sin esa línea, esta reconexión mandaba los
                // cobros y dejaba la apertura en la cola — el defecto F1 completo.
                Log.d(TAG, "Network reconnected — triggering sync")
                syncNow()
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
            Log.d(TAG, "Sync already in progress, skipping")
            return
        }

        syncJob = syncScope.launch {
            try {
                // 🔴 EL CAJÓN VA PRIMERO, Y SI SU APERTURA NO LLEGA LOS COBROS ESPERAN (F1).
                //
                // El orden NO es cosmético: un cobro en efectivo que aterriza ANTES que el `OPEN`
                // de su caja nace con `shiftId = null` y sin `CASH_SALE` en el cajón, y ya no hay
                // quién lo reatribuya — el barrido del servidor no puede, porque el `openedAt` que
                // acaba registrando es la hora del replay y no la real. Medido en una Samsung el
                // 5-sep-2026. Esperar un ciclo es barato; un cobro huérfano es permanente.
                if (!cajon.sincronizarCajonPrimero()) {
                    Log.w(TAG, "⏸️ La apertura de la caja aún no llega al servidor: los cobros esperan al siguiente ciclo")
                    return@launch
                }

                // Cleanup old synced payments
                val cutoff = System.currentTimeMillis() - CLEANUP_AFTER_MS
                dao.deleteSynced(cutoff)

                // 🔴 Adenda Q16 (founder, 16-oct) + Codex 2.ª pasada: una venta RETENIDA ya no tapa la cola. Se queda
                // PENDIENTE con su marca y la vuelta sigue; sólo esperan, sin tocar la red, las filas de su MISMA orden
                // (`identidadesDeOrden`, más la orden que se le adoptó o que su envío resolvió). Como iOS, cada lote lee la
                // cola PENDIENTE entera y el tope cuenta cobros INTENTADOS: lo que espera no gasta el lote (N6). `esperan`
                // dura la vuelta ⇒ cada retenida pide `/sync/recovery` a lo más una vez. La red, el backoff, la recuperación
                // caída y la cuenta por sincronizar siguen cortando la vuelta (FIFO, C1).
                val esperan = mutableSetOf<String>()
                val bloqueadas = mutableSetOf<String>()
                fun esperar(payment: PendingPaymentEntity, identidades: Set<String>) { esperan += payment.id; bloqueadas += identidades }
                repeat(MAX_DRAIN_BATCHES) {
                    if (!isStarted || !connectivityMonitor.isServerReachable.value) return@launch

                    // ponytail: cada lote lee y decodifica toda la cola local (decenas de filas; la prueba usa 1,000). Techo:
                    // con decenas de miles pendientes cada lote pesa. Salida: paginar por cursor (createdAt, id).
                    val pending = dao.getPendingPayments(Int.MAX_VALUE).filter { it.id !in esperan }
                    if (pending.isEmpty()) {
                        Log.d(TAG, "No pending payments to sync")
                        return@launch
                    }

                    Log.d(TAG, "Syncing ${pending.size} pending payments")
                    var intentados = 0
                    var soltada = false

                    for (payment in pending) {
                        if (!isStarted) return@launch
                        if (intentados >= MAX_PAYMENTS_PER_SYNC) break // lote lleno: se relee la cola
                        val identidades = identidadesDeOrden(payment)
                        if (identidades.any { it in bloqueadas }) { esperar(payment, identidades); continue }

                        if (payment.q15Reason != null) {
                            when (val recuperacion = recuperarOrdenRetenidaQ16(payment)) {
                                // Q16: se soltó ⇒ se relee la cola y sale en esta misma vuelta, en su lugar FIFO.
                                Recuperacion.Soltada -> { soltada = true; break }
                                Recuperacion.Pausa -> return@launch
                                is Recuperacion.Retenida -> { esperar(payment, identidades + listOfNotNull(recuperacion.ordenAdoptada)); continue }
                            }
                        }

                        // FIFO: si el más viejo sigue en backoff, no adelantamos
                        // cobros posteriores y esperamos el próximo trigger.
                        if (payment.retryCount > 0) {
                            val backoff = calculateBackoff(payment.retryCount)
                            val timeSinceLastRetry = System.currentTimeMillis() - (payment.lastRetryAt ?: 0)
                            if (timeSinceLastRetry < backoff) {
                                Log.d(TAG, "Stopping drain at ${payment.id} — backoff not elapsed")
                                return@launch
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
                                return@launch
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
                            return@launch
                        }
                    }

                    if (!soltada && intentados < MAX_PAYMENTS_PER_SYNC) return@launch
                }
                Log.w(TAG, "Drain alcanzó el límite de seguridad de $MAX_DRAIN_BATCHES batches")
            } catch (e: Exception) {
                Log.e(TAG, "Sync batch error: ${e.message}")
            }
        }
    }

    /** Cobros que agotaron reintentos o que el servidor rechazó permanentemente. */
    suspend fun failedPayments(): List<PendingPaymentEntity> = dao.getFailedPayments()

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
        if (original.q15Reason != null) return RetryOutcome.StillQueued
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
        /** El server lo rechazó otra vez. Necesita a un humano. */
        data class Rejected(val error: String?) : RetryOutcome
    }

    /** Lectura persistida para impedir logout/cambio de venue con trabajo oculto. */
    suspend fun blockingWorkCount(): Int =
        dao.getUnsyncedPayments().size + dao.getFailedPayments().size +
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
            var resolvedOrderId: String? = payment.orderId
            if (payment.paymentType == "ORDER" && resolvedOrderId == null) {
                when (val result = resolveMissingOrderId(payment, venueId)) {
                    is OrderResolution.Ready -> resolvedOrderId = result.orderId.also(alResolverOrden)
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
            val bodyJson = buildReplayBody(payment, venueId, esCobroDeOrden)

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

    private suspend fun resolveMissingOrderId(payment: PendingPaymentEntity, venueId: String): OrderResolution {
        val orderRequestJson = payment.orderRequestJson
            ?: return OrderResolution.PermanentFailure("Missing orderRequestJson for ORDER sync")

        val request = Request.Builder()
            .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/orders")
            .post(orderRequestJson.toRequestBody(JSON_MEDIA))
            .build()

        return try {
            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
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
            code == 409 && runCatching {
                val value = json.parseToJsonElement(body) as? kotlinx.serialization.json.JsonObject
                val reason = value?.get("code") ?: (value?.get("error") as? kotlinx.serialization.json.JsonObject)?.get("code")
                (reason as? kotlinx.serialization.json.JsonPrimitive)?.let { it.isString && it.content == ConciliacionDelCobro.PRICE_PROOF_PENDING } == true
            }.getOrDefault(false) -> {
                try { check(dao.registrarResultadoQ15(payment.venueId, payment.id, false,
                    ConciliacionDelCobro.PRICE_PROOF_PENDING, null, System.currentTimeMillis()) == 1) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { Log.e(TAG, "No se pudo conservar la espera de precio", error) }
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
