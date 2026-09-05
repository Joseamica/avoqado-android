package com.avoqado.pos.core.data.sync

import android.content.Context
import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.util.ConnectivityMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val TAG = "🔁 SyncOutbox"

/**
 * Offline-first Corte B — el OUTBOX del POS.
 *
 * Toda mutación de mesas hecha sin red se `enqueue()` aquí (write-ahead, antes
 * de cualquier efecto) y se reproduce FIFO contra el reducer del server
 * (POST /mobile/venues/:id/sync/intents) en cuanto hay conexión.
 *
 * Triggers de replay (lección de la auditoría de la TPV — la cola LIGADA a la
 * reconexión, no a un worker de 15 min):
 * 1. Inmediato al encolar (si hay red, el intent ni se nota).
 * 2. Al evento de reconexión de ConnectivityMonitor.
 * 3. Timer de seguridad (5 min) por si ambos fallan.
 *
 * Los acks se emiten en [acks] para que TableSession/VMs intercambien
 * localOrderId → orderId de server. Los REJECTED quedan en cuarentena visible
 * ([rejectedCount]) — jamás se descartan en silencio.
 */
@Singleton
class SyncOutbox @Inject constructor(
    private val dao: SyncIntentDao,
    private val apiService: ApiService,
    private val connectivityMonitor: ConnectivityMonitor,
    private val secureStorage: SecureStorage,
    /**
     * 🔴 EL EFECTIVO DE MESAS VIAJA POR AQUÍ, Y TAMBIÉN TIENE QUE ESPERAR A LA CAJA (P2-3).
     *
     * En este workspace un cobro en efectivo llega al servidor por DOS colas: la de
     * `PaymentSyncService` (venta rápida) y los intents `PAY_CASH` de este outbox (mesas). La
     * ronda anterior sólo protegió la primera, así que al volver la red un `PAY_CASH` de mesa
     * podía aterrizar antes que el `POST /open` y nacer con `shiftId = null` — el defecto F1 por
     * la otra puerta, con el mismo dinero de por medio y sin nadie mirando.
     */
    private val cajon: com.avoqado.pos.cashdrawer.data.CashDrawerRepository,
    @ApplicationContext context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val replayMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("sync_outbox", Context.MODE_PRIVATE)

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _rejectedCount = MutableStateFlow(0)
    val rejectedCount: StateFlow<Int> = _rejectedCount.asStateFlow()

    private val _acks = MutableSharedFlow<SyncAck>(extraBufferCapacity = 64)
    val acks: SharedFlow<SyncAck> = _acks.asSharedFlow()

    private var started = false
    private var activeVenueId: String? = null
    private var connectivityJob: Job? = null
    private var timerJob: Job? = null

    /** Identidad estable del dispositivo para el server (y la partición de folios). */
    val deviceId: String by lazy {
        prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
    }

    // MARK: - Folios particionados (truco Toast: cero colisiones offline)

    /**
     * Folio local provisional: partición estable de 2 dígitos derivada del
     * deviceId + contador persistido por venue → "47-001", "47-002"...
     * Dos dispositivos aislados jamás acuñan el mismo folio visible.
     */
    fun nextLocalFolio(venueId: String): String {
        val partition = abs(deviceId.hashCode()) % 90 + 10 // 10..99, estable
        val key = "folio_counter_$venueId"
        val next = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, next).apply()
        return "$partition-${next.toString().padStart(3, '0')}"
    }

    // MARK: - Lifecycle

    fun start(venueId: String) {
        if (started && activeVenueId == venueId) return
        if (started) stop()
        started = true
        activeVenueId = venueId
        Log.d(TAG, "start() venue=$venueId device=$deviceId")

        scope.launch {
            dao.deleteOldAcked(System.currentTimeMillis() - ACKED_TTL_MS)
            refreshCounts(venueId)
            replayNow(venueId)
        }

        connectivityJob?.cancel()
        connectivityJob = scope.launch {
            var wasDown = false
            // "Plenamente conectado" = red física Y servidor alcanzable. Escuchar
            // AMBAS señales es clave: en el outage tipo Toast/Square (internet o
            // el server de Avoqado caído pero el WiFi del local vivo) la red
            // física NUNCA cambia — solo isServerReachable lo hace. Sin esto el
            // replay esperaría hasta el timer de seguridad (verificado en la
            // T3 Pro: sin el fix, la cola no se reproducía al volver el server).
            combine(
                connectivityMonitor.isConnected,
                connectivityMonitor.isServerReachable,
            ) { net, server -> net && server }.collect { fullyConnected ->
                if (!fullyConnected) {
                    wasDown = true
                } else if (wasDown) {
                    wasDown = false
                    Log.d(TAG, "Reconexión detectada (red+server) — replay del outbox")
                    delay(2_000) // estabilización de red (patrón PaymentSyncService)
                    replayNow(venueId)
                }
            }
        }

        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                delay(SAFETY_TIMER_MS)
                if (
                    connectivityMonitor.isConnected.value &&
                    connectivityMonitor.isServerReachable.value
                ) {
                    replayNow(venueId)
                }
            }
        }
    }

    fun stop() {
        started = false
        activeVenueId = null
        connectivityJob?.cancel()
        timerJob?.cancel()
    }

    // MARK: - Enqueue (write-ahead)

    /**
     * Escribe el intent ANTES de cualquier efecto y dispara replay inmediato.
     * @return el id del intent (UUID = idempotencyKey).
     */
    suspend fun enqueue(venueId: String, type: String, payload: JsonObject): String {
        val id = UUID.randomUUID().toString()
        // seq asignado ATÓMICAMENTE (maxSeq+insert en una transacción) — evita
        // seq duplicado que desordenaría el replay.
        val seq = dao.insertWithNextSeq(
            SyncIntentEntity(
                id = id,
                venueId = venueId,
                staffId = secureStorage.userId,
                seq = 0, // reemplazado dentro de insertWithNextSeq
                type = type,
                payloadJson = json.encodeToString(JsonObject.serializer(), payload),
            ),
        )
        refreshCounts(venueId)
        Log.d(TAG, "📥 Encolado $type seq=$seq id=$id")
        scope.launch { replayNow(venueId) }
        return id
    }

    // MARK: - Replay

    suspend fun replayNow(venueId: String) {
        if (
            activeVenueId != venueId ||
            !connectivityMonitor.isConnected.value ||
            !connectivityMonitor.isServerReachable.value
        ) {
            return
        }
        // 🔴 EL CAJÓN VA PRIMERO, TAMBIÉN POR ESTA PUERTA (P2-3). Es el MISMO punto de entrada y el
        // MISMO candado de un solo vuelo que usa `PaymentSyncService`: si ya hay un replay del
        // cajón en vuelo, éste espera su turno y encuentra la cola vacía, así que la apertura NUNCA
        // se manda dos veces por sumar un segundo llamador.
        val cobrosPuedenSalir = runCatching { cajon.sincronizarCajonPrimero() }
            .onFailure { Log.e(TAG, "❌ No se pudo sincronizar el cajón antes del outbox: ${it.message}") }
            .getOrDefault(true)
        replayMutex.withLock {
            while (true) {
                if (activeVenueId != venueId) break
                val leidos = dao.pendingFifo(venueId, BATCH_SIZE)
                if (leidos.isEmpty()) break

                // 🔴 SE CORTA EN EL PRIMER `PAY_CASH`, NO SE REORDENA. El FIFO por aparato es lo
                // que hace que una mesa se abra antes de que le agreguen artículos: adelantar lo
                // que no es dinero rompería ese orden. Lo que NO es dinero sigue fluyendo; el
                // cobro en efectivo y todo lo posterior esperan a que la caja aterrice.
                val cuantos = cuantosIntentsSePuedenMandar(leidos.map { it.type }, cobrosPuedenSalir)
                val batch = leidos.take(cuantos)
                if (batch.isEmpty()) {
                    Log.w(TAG, "⏸️ El outbox se detiene en el primer PAY_CASH: la caja aún no llega al servidor")
                    break
                }

                val request = SyncIntentsRequest(
                    deviceId = deviceId,
                    intents = batch.map {
                        SyncIntentWire(
                            id = it.id,
                            seq = it.seq,
                            type = it.type,
                            payload = json.decodeFromString(JsonObject.serializer(), it.payloadJson),
                            staffId = it.staffId,
                            createdAtLocal = it.createdAt,
                        )
                    },
                )

                val response = try {
                    apiService.syncIntents(venueId, request)
                } catch (e: Exception) {
                    // Error de red/5xx: los intents quedan PENDING y el próximo
                    // trigger reintenta (idempotente). Jamás se pierden.
                    Log.w(TAG, "⚠️ Replay interrumpido (${batch.size} pendientes): ${e.message}")
                    break
                }

                var sawRetry = false
                for (ack in response.data) {
                    if (ack.isRetry) {
                        // Transitorio: el intent se queda PENDING (no lo resolvemos).
                        // El server ya cortó el batch aquí (FIFO), así que salimos
                        // del while para NO re-leer el mismo PENDING en caliente;
                        // el próximo trigger (timer/reconexión) reintenta.
                        sawRetry = true
                        Log.d(TAG, "🔁 Intent ${ack.id} RETRY (${ack.errorCode}) — sigue pendiente, reintentaré")
                        _acks.emit(ack)
                        break
                    }
                    dao.resolve(
                        id = ack.id,
                        status = if (ack.isAcked) SyncIntentEntity.STATUS_ACKED else SyncIntentEntity.STATUS_REJECTED,
                        errorCode = ack.errorCode,
                        message = ack.message,
                        resultJson = ack.result?.let { json.encodeToString(JsonObject.serializer(), it) },
                    )
                    _acks.emit(ack)
                    if (!ack.isAcked) {
                        Log.w(TAG, "🚫 Intent ${ack.id} RECHAZADO: ${ack.errorCode} — ${ack.message}")
                    }
                }
                refreshCounts(venueId)
                Log.d(TAG, "✅ Replay de ${batch.size} intents aplicado")
                if (sawRetry) break // no hot-loop sobre el mismo PENDING
                if (batch.size < leidos.size) {
                    // El lote venía recortado por la barrera del cajón: lo que sigue es el
                    // `PAY_CASH` que tiene que esperar. Volver al `while` lo re-leería en caliente.
                    Log.w(TAG, "⏸️ Quedan ${leidos.size - batch.size} intents esperando a que la caja llegue al servidor")
                    break
                }
            }
        }
    }

    private suspend fun refreshCounts(venueId: String) {
        _pendingCount.value = dao.pendingCount(venueId)
        _rejectedCount.value = dao.rejectedCount(venueId)
    }

    // MARK: - Cuarentena (operaciones rechazadas, visibles al gerente)

    /** Un intent rechazado, ya legible para la pantalla de resolución. */
    data class QuarantinedIntent(
        val id: String,
        val type: String,
        val errorCode: String?,
        val message: String?,
        val createdAt: Long,
    )

    suspend fun rejectedIntents(venueId: String): List<QuarantinedIntent> =
        dao.rejectedIntents(venueId).map {
            QuarantinedIntent(it.id, it.type, it.errorCode, it.message, it.createdAt)
        }

    /** El gerente resolvió el rechazo a mano y lo descarta de la cuarentena. */
    suspend fun dismissRejected(venueId: String, id: String) {
        dao.dismiss(id)
        refreshCounts(venueId)
    }

    /** Lectura persistida usada por las guardas de sesión y cambio de venue. */
    suspend fun blockingWorkCount(venueId: String): Int =
        dao.pendingCount(venueId) + dao.rejectedCount(venueId)

    companion object {
        /** El tipo de intent que MUEVE DINERO EN EFECTIVO. Espejo exacto del `SyncIntentType` del server. */
        internal const val TIPO_PAGO_EN_EFECTIVO = "PAY_CASH"

        /**
         * 🔴 Cuántas entradas del lote se pueden mandar cuando la caja todavía no llegó al servidor.
         *
         * Función PURA para poder ejercitar el corte sin red ni base: con la barrera abierta va el
         * lote entero; con la barrera cerrada va TODO lo anterior al primer `PAY_CASH` y ahí se
         * detiene. Lo que se protege es el dinero, no la cola: un `OPEN_TABLE` o un `ADD_ITEMS`
         * que aterrice sin caja abierta no pierde nada — un cobro en efectivo, sí, y para siempre.
         */
        internal fun cuantosIntentsSePuedenMandar(tipos: List<String>, cobrosPuedenSalir: Boolean): Int {
            if (cobrosPuedenSalir) return tipos.size
            val primerCobro = tipos.indexOf(TIPO_PAGO_EN_EFECTIVO)
            return if (primerCobro < 0) tipos.size else primerCobro
        }

        private const val KEY_DEVICE_ID = "sync_device_id"
        private const val BATCH_SIZE = 50
        private const val SAFETY_TIMER_MS = 5L * 60 * 1000
        private const val ACKED_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
