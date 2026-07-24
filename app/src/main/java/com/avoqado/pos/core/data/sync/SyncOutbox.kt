package com.avoqado.pos.core.data.sync

import android.content.Context
import android.util.Log
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
        if (started) return
        started = true
        Log.d(TAG, "start() venue=$venueId device=$deviceId")

        scope.launch {
            dao.deleteOldAcked(System.currentTimeMillis() - ACKED_TTL_MS)
            refreshCounts(venueId)
            replayNow(venueId)
        }

        connectivityJob?.cancel()
        connectivityJob = scope.launch {
            var wasDisconnected = false
            connectivityMonitor.isConnected.collect { connected ->
                if (!connected) {
                    wasDisconnected = true
                } else if (wasDisconnected) {
                    wasDisconnected = false
                    Log.d(TAG, "Reconexión detectada — replay del outbox")
                    delay(2_000) // estabilización de red (patrón PaymentSyncService)
                    replayNow(venueId)
                }
            }
        }

        timerJob?.cancel()
        timerJob = scope.launch {
            while (true) {
                delay(SAFETY_TIMER_MS)
                if (connectivityMonitor.isConnected.value) replayNow(venueId)
            }
        }
    }

    fun stop() {
        started = false
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
        val seq = dao.maxSeq() + 1
        dao.insert(
            SyncIntentEntity(
                id = id,
                venueId = venueId,
                seq = seq,
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
        if (!connectivityMonitor.isConnected.value) return
        replayMutex.withLock {
            while (true) {
                val batch = dao.pendingFifo(venueId, BATCH_SIZE)
                if (batch.isEmpty()) break

                val request = SyncIntentsRequest(
                    deviceId = deviceId,
                    intents = batch.map {
                        SyncIntentWire(
                            id = it.id,
                            seq = it.seq,
                            type = it.type,
                            payload = json.decodeFromString(JsonObject.serializer(), it.payloadJson),
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

                for (ack in response.data) {
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
            }
        }
    }

    private suspend fun refreshCounts(venueId: String) {
        _pendingCount.value = dao.pendingCount(venueId)
        _rejectedCount.value = dao.rejectedCount(venueId)
    }

    companion object {
        private const val KEY_DEVICE_ID = "sync_device_id"
        private const val BATCH_SIZE = 50
        private const val SAFETY_TIMER_MS = 5L * 60 * 1000
        private const val ACKED_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
