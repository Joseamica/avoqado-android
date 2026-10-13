package com.avoqado.pos.core.data.sync

import android.content.Context
import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.IntentSinResolver
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.util.ConnectivityMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

private const val TAG = "🔁 SyncOutbox"

data class TrabajoSinResolver(val intents: Int, val rechazados: Int, val tipos: Set<String>, val cobradoEnColaCents: Int) {
    val enCamino: Int get() = intents - rechazados
}

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
    private val cachedPayloads: com.avoqado.pos.core.data.local.database.CachedPayloadDao,
    private val tableSession: com.avoqado.pos.tables.data.TableSession = com.avoqado.pos.tables.data.TableSession(),
) {
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Se fija antes de start; producción conserva IO. Permite probar el drenaje real sin timers concurrentes. */
    internal fun usarContextoDeSincronizacion(contexto: kotlin.coroutines.CoroutineContext) {
        check(!started)
        scope.cancel()
        scope = CoroutineScope(SupervisorJob() + contexto)
    }
    private val replayMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("sync_outbox", Context.MODE_PRIVATE)
    private val tableDrafts by lazy { com.avoqado.pos.tables.data.TableRoundDraftStore(context) }

    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    private val _rejectedCount = MutableStateFlow(0)
    val rejectedCount: StateFlow<Int> = _rejectedCount.asStateFlow()

    private val _q15ReviewCount=MutableStateFlow(0)
    val q15ReviewCount:StateFlow<Int> = _q15ReviewCount.asStateFlow()
    private val _q15HistoryUnavailable=MutableStateFlow(false)
    val q15HistoryUnavailable:StateFlow<Boolean> = _q15HistoryUnavailable.asStateFlow()
    private var reviewCountJob:Job?=null
    private data class ReviewCountContext(val venue: String, val actor: String?, val role: String?, val epoch: Long)
    private var reviewCountContext: ReviewCountContext? = null

    /** Rearma sólo la lectura Q15 al cambiar sesión; no vuelve a iniciar replay ni timers. */
    fun refreshQ15Observation() {
        val venue = activeVenueId
        val next = if (started && venue != null && secureStorage.venueId == venue) {
            ReviewCountContext(venue, secureStorage.userId, secureStorage.userRole, tableSession.contextoActual())
        } else null
        if (next != null && next == reviewCountContext && reviewCountJob?.isActive == true) return
        reviewCountJob?.cancel()
        reviewCountJob = null
        reviewCountContext = next
        _q15ReviewCount.value = 0
        _q15HistoryUnavailable.value = false
        if (next == null) return
        fun vigente() = started && reviewCountContext === next && activeVenueId == next.venue &&
            secureStorage.venueId == next.venue && secureStorage.userId == next.actor &&
            secureStorage.userRole == next.role && tableSession.contextoActual() == next.epoch
        reviewCountJob = scope.launch {
            try {
                dao.observarRevisionesQ15(next.venue, false, "").collect { count ->
                    if (vigente()) {
                        _q15ReviewCount.value = count
                        _q15HistoryUnavailable.value = false
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (vigente()) _q15HistoryUnavailable.value = true }
        }
    }

    private val _acks = MutableSharedFlow<SyncAck>(extraBufferCapacity = 64)
    val acks: SharedFlow<SyncAck> = _acks.asSharedFlow()

    private var started = false

    /**
     * Una ronda retenida en disco al arrancar es de un proceso que MURIÓ a medio envío (spec §5): se suelta UNA vez
     * por proceso, antes del primer replay, y el servidor deduplica por su llave. Sólo la primera vez: en un cambio
     * de sucursal podría haber una ronda en vuelo de verdad.
     */
    private var retenidosDeOtroProcesoSoltados = false
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
        if (started && activeVenueId == venueId) {
            refreshQ15Observation()
            return
        }
        if (started) stop()
        started = true
        activeVenueId = venueId
        refreshQ15Observation()
        Log.d(TAG, "start() venue=$venueId device=${prefs.getString(KEY_DEVICE_ID, null)}")

        // 🔴 Una ronda retenida en disco al arrancar es de un proceso que MURIÓ a medio envío (spec §5): se suelta UNA
        // vez por proceso, antes del primer replay, y el servidor deduplica por su llave. Sólo la primera vez: en un
        // cambio de sucursal podría haber una ronda en vuelo de verdad.
        val soltarRetenidos = !retenidosDeOtroProcesoSoltados
        retenidosDeOtroProcesoSoltados = true

        scope.launch {
            // Corre en este `launch`, no antes de volver de `start()`: una ronda enviada en los primeros milisegundos de
            // un proceso nuevo podría soltarse aquí y reproducirse junto a su envío en línea. No duplica: las dos llevan
            // las MISMAS llaves y el servidor deduplica por `(orderId, externalId)`.
            if (soltarRetenidos) {
                val soltados = dao.soltarRetenidos()
                if (soltados > 0) Log.w(TAG, "🔓 $soltados ronda(s) retenida(s) de un proceso anterior vuelven a la cola")
            }
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
        reviewCountJob?.cancel();reviewCountJob=null;reviewCountContext=null
        _q15ReviewCount.value=0;_q15HistoryUnavailable.value=false
        started = false
        activeVenueId = null
        connectivityJob?.cancel()
        timerJob?.cancel()
    }

    // MARK: - Enqueue (write-ahead)

    /**
     * Escribe el intent ANTES de cualquier efecto y, si no va retenido, dispara el replay.
     *
     * @param id el id del intent (= idempotencyKey). Un llamador write-ahead pasa el suyo para poner la MISMA llave en
     *   el payload antes de escribirlo (patrón PAX `TablesRepository.addItems`).
     * @param retenido `true` = red de seguridad de un intento EN LÍNEA: se escribe pero no sale —y nada detrás sale—
     *   hasta [soltar] o [descartar].
     */
    /** El estado en vivo de un intent (lo mira la pantalla de resultado de un cobro encolado). */
    fun observarEstado(id: String): kotlinx.coroutines.flow.Flow<String?> = dao.observarEstado(id)

    suspend fun enqueue(
        venueId: String,
        type: String,
        payload: JsonObject,
        id: String = UUID.randomUUID().toString(),
        retenido: Boolean = false,
        createdAtLocal: Long? = null,
        preparationSnapshots: List<com.avoqado.pos.core.data.local.database.CachedPayloadEntity>? = null,
        expectedActorStaffId: String? = null,
        cashTenderedCents: Int? = null, changeCents: Int? = null, splitType: String? = null,
    ): String {
        val actor = secureStorage.userId
        if (type == SyncIntentTypes.KDS_ITEM_PROGRESS) {
            check(!actor.isNullOrBlank() && actor == expectedActorStaffId && secureStorage.venueId == venueId) {
                "Cambió la sesión. Revisa el producto antes de continuar."
            }
            require(!retenido && preparationSnapshots != null)
        } else require(preparationSnapshots == null)
        val originalPayload = json.encodeToString(JsonObject.serializer(), payload)
        val capture = if(type == SyncIntentTypes.PAY_CASH) com.avoqado.pos.payment.data.ConciliacionDelCobro.capturarIntent(
            venueId,id,originalPayload,cashTenderedCents,changeCents,splitType) else null
        require(type != SyncIntentTypes.PAY_CASH || capture != null) { "No se pudo guardar el importe original del cobro" }
        // seq assigned atomically with the original proof, before any replay.
        val entity = SyncIntentEntity(
                id = id,
                venueId = venueId,
                staffId = actor,
                seq = 0, // reemplazado dentro de insertWithNextSeq
                type = type,
                payloadJson = originalPayload,
                q15CaptureJson = capture,
                capturedOrderRef = if(type == SyncIntentTypes.PAY_CASH) com.avoqado.pos.core.data.local.database.referenciaOriginalDelCobro(originalPayload) else null,
                status = if (retenido) SyncIntentEntity.STATUS_HELD else SyncIntentEntity.STATUS_PENDING,
                createdAt = createdAtLocal ?: System.currentTimeMillis(),
            )
        val seq = if (preparationSnapshots == null) dao.insertWithNextSeq(entity)
            else dao.insertPreparationWithNextSeq(entity, preparationSnapshots)
        refreshCounts(venueId)
        Log.d(TAG, "📥 Encolado $type seq=$seq id=$id${if (retenido) " (retenido)" else ""}")
        if (!retenido) scope.launch { replayNow(venueId) }
        return id
    }

    /** La ronda en vuelo falló por red: su red de seguridad vuelve a la cola y el replay la manda (y lo de detrás). */
    suspend fun soltar(venueId: String, id: String) {
        withContext(NonCancellable) { dao.soltar(id) }
        refreshCounts(venueId)
        scope.launch { replayNow(venueId) }
    }

    /** La ronda llegó o el servidor la rechazó: su red de seguridad sobra; lo que esperaba detrás ya puede salir. */
    suspend fun descartar(venueId: String, id: String) {
        withContext(NonCancellable) { dao.descartar(id) }
        refreshCounts(venueId)
        scope.launch { replayNow(venueId) }
    }

    // MARK: - Replay

    private suspend fun preparationLanesNegotiated(venueId: String): Boolean {
        if (secureStorage.venueId != venueId) return false
        val saved = cachedPayloads.get("preparation_capabilities:$venueId") ?: return false
        if (saved.venueId != venueId) return false
        val capability = runCatching {
            json.decodeFromString<com.avoqado.pos.kds.domain.PreparationCapabilities>(saved.json)
        }.getOrNull() ?: return false
        return capability.version == 1 && capability.enabled && capability.replayLaneVersion == 1
    }

    /** Q16: ningún precio local/actual autoriza revalorar una venta ya capturada. */
    private suspend fun antesDePrecioPendiente(venueId: String, rows: List<SyncIntentEntity>): List<SyncIntentEntity> {
        require(rows.size <= BATCH_SIZE)
        val available = rows.takeWhile { it.q15Reason != com.avoqado.pos.payment.data.ConciliacionDelCobro.PRICE_PROOF_PENDING }
        val aliases = linkedMapOf<Int, Set<String>>()
        val resolved = mutableMapOf<String, Set<String>>()
        var venueHasCapture: Boolean? = null
        for ((index, row) in available.withIndex()) {
            if (row.type !in setOf(SyncIntentTypes.OPEN_TABLE, SyncIntentTypes.ADD_ITEMS)) continue
            val payload = json.decodeFromString(JsonObject.serializer(), row.payloadJson)
            val original = (payload["localOrderId"] ?: payload["orderId"]) as? JsonPrimitive
            require(original != null && original.isString && original.content.isNotBlank()) { "No se pudo identificar la orden original" }
            val ref = original.content
            aliases[index] = try {
                resolved[ref] ?: try { tableDrafts.priceReferencesQ16(venueId, ref) }
                catch (unknown: com.avoqado.pos.tables.data.TableRoundDraftStore.UnknownHistoryQ16) {
                    val hasCapture = venueHasCapture ?: dao.hayCapturaSinResolverQ16(venueId).also { venueHasCapture = it }
                    if (hasCapture) throw unknown
                    setOf(ref) // Absence proved for this attempt only; do not rewrite the historical Snapshot.
                }.also { resolved[ref] = it }
            }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                check(dao.retenerIntentQ15(venueId,row.id,com.avoqado.pos.payment.data.ConciliacionDelCobro.PRICE_PROOF_PENDING,System.currentTimeMillis()) == 1)
                return rows.take(index)
            }
        }
        val refs = aliases.values.flatten().distinct()
        require(refs.size <= 100)
        if (refs.isEmpty()) return available
        val captured = dao.referenciasSinResolverQ16(venueId, refs).toSet()
        val index = aliases.entries.firstOrNull { (_, ids) -> ids.any { it in captured } }?.key ?: return available
        check(dao.retenerIntentQ15(venueId, rows[index].id, com.avoqado.pos.payment.data.ConciliacionDelCobro.PRICE_PROOF_PENDING, System.currentTimeMillis()) == 1) {
            "No se pudo conservar la espera de precio"
        }
        return rows.take(index)
    }

    /** Recover only an already stored ACK. Missing/ambiguous/processing/rejected stay held. */
    private suspend fun recuperarRetenidoQ16(row: SyncIntentEntity, venueId: String, epoch: Long) {
        if (row.venueId != venueId || row.type !in setOf(SyncIntentTypes.OPEN_TABLE, SyncIntentTypes.ADD_ITEMS, SyncIntentTypes.PAY_CASH)) return
        val savedDevice = prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() } ?: return
        try {
            val payload = json.decodeFromString(JsonObject.serializer(), row.payloadJson)
            val local = payload["localOrderId"]
            if (local != null && (local !is JsonPrimitive || !local.isString || local.content.isBlank())) return
            val response = apiService.recoverSyncQ16(venueId, kotlinx.serialization.json.buildJsonObject {
                put("deviceId", JsonPrimitive(savedDevice))
                put("intentIds", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive(row.id))))
            })
            val data = response["data"] as? JsonObject ?: return
            val records = data["intents"] as? kotlinx.serialization.json.JsonArray ?: return
            if (records.size != 1 || (data["orders"] as? kotlinx.serialization.json.JsonArray)?.isEmpty() != true) return
            val raw = records.single() as? JsonObject ?: return
            fun string(key: String) = (raw[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val seq = raw["seq"] as? JsonPrimitive ?: return
            if (seq.isString || seq.content.toLongOrNull() != row.seq || string("id") != row.id ||
                string("type") != row.type || string("deviceId") != savedDevice ||
                !raw.containsKey("localRef") || raw["localRef"] != (local ?: kotlinx.serialization.json.JsonNull) ||
                string("status") != "ACKED" || raw["errorCode"] != kotlinx.serialization.json.JsonNull ||
                prefs.getString(KEY_DEVICE_ID, null) != savedDevice) return
            val result = raw["result"] as? JsonObject ?: return
            val ack = SyncAck(id = row.id, status = "ACKED", result = result)
            if (row.type == SyncIntentTypes.OPEN_TABLE && local != null) {
                val resolved = (result["orderId"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: return
                if (result["localOrderId"] != local) return
                tableDrafts.promote(venueId, (local as JsonPrimitive).content, resolved)
            }
            conservarAck(ack, row, venueId, savedDevice, savedDevice)
            if (activeVenueId == venueId) _acks.emit(ack.copy(contextoDeSesion = epoch))
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { Log.w(TAG, "La recuperación sigue esperando: ${error.message}") }
    }

    internal suspend fun aliasDurableQ16(venueId: String, original: String): String? =
        withContext(Dispatchers.IO) { tableDrafts.loadResolved(venueId, original).first.takeIf { it != original && it.isNotBlank() } }

    private suspend fun enviarAdmitidoQ16(venueId: String, request: SyncIntentsRequest,
        token: AdmisionDePrecioQ16.Token): SyncIntentsResponse = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        val call=apiService.syncIntentsAdmittedQ16(venueId,request,token)
        continuation.invokeOnCancellation { call.cancel() }
        val admitted=AdmisionDePrecioQ16.admit(token) {
            if (continuation.isActive) call.enqueue(object: retrofit2.Callback<SyncIntentsResponse> {
                override fun onResponse(call: retrofit2.Call<SyncIntentsResponse>, response: retrofit2.Response<SyncIntentsResponse>) {
                    if (!continuation.isActive) return
                    val body=response.body()
                    if (response.isSuccessful && body != null) continuation.resumeWith(Result.success(body))
                    else continuation.resumeWith(Result.failure(retrofit2.HttpException(response)))
                }
                override fun onFailure(call: retrofit2.Call<SyncIntentsResponse>, error: Throwable) {
                    if (continuation.isActive) continuation.resumeWith(Result.failure(error))
                }
            })
        }
        if (!admitted && continuation.isActive) continuation.resumeWith(Result.failure(AdmisionDePrecioQ16.Changed()))
    }

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
            val lanes: List<Boolean?> = if (preparationLanesNegotiated(venueId)) listOf(false, true) else listOf(null)
            for (lane in lanes) {
                while (true) {
                    if (activeVenueId != venueId || lane != null && secureStorage.venueId != venueId) break
                    val contextoDeSesion = tableSession.contextoActual()
                    val precioToken = AdmisionDePrecioQ16.snapshot(venueId)
                    val leidos = if (lane == null) dao.pendingFifo(venueId, BATCH_SIZE)
                        else dao.pendingFifoLane(venueId, lane, BATCH_SIZE)
                    if (leidos.isEmpty()) break
                    if (leidos.first().q15Reason != null) {
                        recuperarRetenidoQ16(leidos.first(), venueId, contextoDeSesion)
                        break // no same-cycle re-price or hot loop after recovery
                    }

                    // 🔴 RONDA EN VUELO (spec §5): lo que va detrás de una ronda retenida NO sale hasta que ella se suelte o
                    // se descarte. Mismo principio que la barrera del cajón: cortar, nunca reordenar.
                    val antesDeLaRetenida = try {
                        antesDePrecioPendiente(venueId, leidos.take(hastaElPrimerRetenido(leidos.map { it.status })))
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        Log.e(TAG, "No se pudo verificar la venta capturada antes de reproducirla", error)
                        break
                    }
                    if (antesDeLaRetenida.isEmpty()) {
                        Log.d(TAG, "⏸️ Una ronda en vuelo retiene la cola")
                        break
                    }

                    // 🔴 SE CORTA EN EL PRIMER `PAY_CASH`, NO SE REORDENA. El FIFO por aparato es lo
                    // que hace que una mesa se abra antes de que le agreguen artículos: adelantar lo
                    // que no es dinero rompería ese orden. Lo que NO es dinero sigue fluyendo; el
                    // cobro en efectivo y todo lo posterior esperan a que la caja aterrice.
                    val cuantos = cuantosIntentsSePuedenMandar(antesDeLaRetenida.map { it.type }, cobrosPuedenSalir)
                    val antesDeCaja = antesDeLaRetenida.take(cuantos)
                    val firstUnknown = antesDeCaja.indexOfFirst { it.type == SyncIntentTypes.PAY_CASH &&
                        (it.q15Reason != null || com.avoqado.pos.payment.data.ConciliacionDelCobro.leerCaptura(it.q15CaptureJson,it.venueId,it.id) == null) }
                    if (firstUnknown >= 0) {
                        val row=antesDeCaja[firstUnknown]
                        try { check(dao.retenerIntentQ15(row.venueId,row.id,row.q15Reason ?: com.avoqado.pos.payment.data.ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA,System.currentTimeMillis()) == 1) }
                        catch(e: Exception) { Log.e(TAG,"No se pudo conservar el cobro por revisar",e);break }
                    }
                    val batch = if(firstUnknown >= 0) antesDeCaja.take(firstUnknown) else antesDeCaja
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

                    // Context captured before reading this batch also fences the Q16 query await.
                    val response = try {
                        if (batch.any { it.type == SyncIntentTypes.OPEN_TABLE || it.type == SyncIntentTypes.ADD_ITEMS })
                            enviarAdmitidoQ16(venueId, request, precioToken)
                        else apiService.syncIntents(venueId, request)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (e: Exception) {
                        // Error de red/5xx: los intents quedan PENDING y el próximo
                        // trigger reintenta (idempotente). Jamás se pierden.
                        Log.w(TAG, "⚠️ Replay interrumpido (${batch.size} pendientes): ${e.message}")
                        break
                    }

                    if (response.data.isEmpty() || response.data.any { ack ->
                        batch.none { it.id == ack.id } || ack.status !in setOf("ACKED", "REJECTED", "RETRY")
                    } || response.data.map { it.id }.distinct().size != response.data.size) {
                        Log.w(TAG, "Respuesta de sync ilegible; las operaciones siguen pendientes")
                        break
                    }

                    var sawRetry = false
                    for (ack in response.data) {
                        val row = batch.firstOrNull { it.id == ack.id } ?: continue
                        if (ack.isRetry) {
                            // Transitorio: el intent se queda PENDING (no lo resolvemos).
                            // El server ya cortó el batch aquí (FIFO), así que salimos
                            // del while para NO re-leer el mismo PENDING en caliente;
                            // el próximo trigger (timer/reconexión) reintenta.
                            sawRetry = true
                            Log.d(TAG, "🔁 Intent ${ack.id} RETRY (${ack.errorCode}) — sigue pendiente, reintentaré")
                            if (activeVenueId == venueId) _acks.emit(ack.copy(contextoDeSesion = contextoDeSesion))
                            break
                        }
                        if (ack.isAcked && row.type == SyncIntentTypes.OPEN_TABLE) {
                            try {
                                val payload = json.decodeFromString(JsonObject.serializer(), row.payloadJson)
                                if (payload["localOrderId"] != null || ack.result?.get("localOrderId") != null) {
                                    val local = (ack.result?.get("localOrderId") as? kotlinx.serialization.json.JsonPrimitive)?.content
                                    val order = (ack.result?.get("orderId") as? kotlinx.serialization.json.JsonPrimitive)?.content
                                    require(!local.isNullOrBlank() && !order.isNullOrBlank() && payload["localOrderId"] == ack.result?.get("localOrderId"))
                                    tableDrafts.promote(row.venueId, local, order)
                                }
                            } catch (e: Exception) {
                                val message = "No se pudo recuperar el pedido guardado. No se borró; se intentará de nuevo."
                                dao.resolve(ack.id, SyncIntentEntity.STATUS_PENDING, LOCAL_DRAFT_RECOVERY_FAILED, message, null)
                                val localRef = runCatching { json.decodeFromString(JsonObject.serializer(), row.payloadJson)["localOrderId"] }.getOrNull()
                                val recoveryResult = JsonObject((ack.result ?: JsonObject(emptyMap())) + listOfNotNull(localRef?.let { "localOrderId" to it }).toMap())
                                if (activeVenueId == venueId) _acks.emit(ack.copy(status = "RETRY", errorCode = LOCAL_DRAFT_RECOVERY_FAILED, message = message, result = recoveryResult, contextoDeSesion = contextoDeSesion))
                                Log.e(TAG, "No se pudo guardar el cambio de identidad de la mesa", e)
                                sawRetry = true
                                break
                            }
                        }
                        try {
                            conservarAck(ack,row,venueId,request.deviceId,deviceId)
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Nada se notifica antes del commit; detenerse evita repetir este PENDING en caliente.
                            Log.e(TAG, "No se pudo guardar el resultado de sync", e)
                            sawRetry = true
                            break
                        }
                        if (activeVenueId == venueId) _acks.emit(ack.copy(contextoDeSesion = contextoDeSesion))
                        if (!ack.isAcked) {
                            Log.w(TAG, "🚫 Intent ${ack.id} RECHAZADO: ${ack.errorCode} — ${ack.message}")
                        }
                    }
                    refreshCounts(venueId)
                    if (!sawRetry) Log.d(TAG, "✅ Replay de ${batch.size} intents aplicado")
                    if (sawRetry) break // no hot-loop sobre el mismo PENDING
                    if (response.data.size != batch.size) break // partial response must not spin
                    if (batch.size < leidos.size) {
                        // El lote venía recortado por la barrera del cajón o por una ronda en vuelo. Volver al
                        // `while` lo re-leería en caliente.
                        Log.w(TAG, "⏸️ Quedan ${leidos.size - batch.size} intents esperando (caja o ronda en vuelo)")
                        break
                    }
                }
            }
        }
    }

    /** Both HTTP replay and readonly recovery must use this same local commit before emitting ACK. */
    internal suspend fun conservarAck(ack: SyncAck, row: SyncIntentEntity, venueId: String, sentDeviceId: String, currentDeviceId: String) {
        val resultJson = ack.result?.let { json.encodeToString(JsonObject.serializer(), it) }
        if(ack.status == "REJECTED" && ack.errorCode == "STALE_DEVICE_SEQUENCE") {
            require(row.venueId == venueId && sentDeviceId.isNotBlank() && sentDeviceId == currentDeviceId)
            dao.resolveStaleSequence(ack.id,venueId,row.seq,
                ack.latestSeqFor(row.id,row.seq,row.venueId,venueId,sentDeviceId,currentDeviceId),ack.errorCode,ack.message,resultJson)
        } else if(row.type == SyncIntentTypes.PAY_CASH) {
            val evaluation = if(ack.isAcked) com.avoqado.pos.payment.data.ConciliacionDelCobro.evaluar(row.q15CaptureJson,row.venueId,row.id,
                com.avoqado.pos.payment.data.CanalDelCobro.ACK,resultJson ?: "") else null
            dao.resolverCobroQ15(row.id,venueId,row.seq,if(ack.isAcked) SyncIntentEntity.STATUS_ACKED else SyncIntentEntity.STATUS_REJECTED,
                ack.errorCode,ack.message,resultJson,evaluation?.motivo ?: if(!ack.isAcked) com.avoqado.pos.payment.data.ConciliacionDelCobro.RESULTADO_NO_COMPROBADO else null,
                evaluation?.resultadoJson,System.currentTimeMillis())
        } else dao.resolve(ack.id,if(ack.isAcked) SyncIntentEntity.STATUS_ACKED else SyncIntentEntity.STATUS_REJECTED,ack.errorCode,ack.message,resultJson)
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

    /**
     * Lectura persistida usada por las guardas de sesión y cambio de venue. Cuenta PENDING + HELD —
     * una ronda en vuelo (Task 7 review, 2026-09-28) también bloquea salir, aunque el badge visible
     * ([pendingCount]) no la muestre.
     */
    suspend fun blockingWorkCount(venueId: String): Int =
        dao.pendingCount(venueId) + dao.heldCount(venueId) + dao.rejectedCount(venueId)

    suspend fun trabajoSinResolverDeLaOrden(venueId: String, ids: Set<String>): TrabajoSinResolver = withContext(Dispatchers.IO) {
        val filas = dao.sinResolver(venueId)
        val expanded = ids.toMutableSet()
        val localIds = filas.mapNotNull { fila ->
            runCatching {
                val payload = Json.parseToJsonElement(fila.payloadJson) as? JsonObject
                (payload?.get("localOrderId") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }.toSet()
        // OPEN puede estar confirmado y depurado mientras ADD conserva el id local.
        for (local in localIds - ids) {
            if (tableDrafts.loadResolved(venueId, local).first in ids) expanded += local
        }
        resumirTrabajo(filas, expanded)
    }

    companion object {
        internal fun resumirTrabajo(filas: List<IntentSinResolver>, ids: Set<String>): TrabajoSinResolver {
            var intents = 0
            var rechazados = 0
            var cobrado = 0
            val tipos = mutableSetOf<String>()
            for (fila in filas) {
                if (!tocaLaOrden(fila.payloadJson, ids)) continue
                intents++
                if (fila.status == SyncIntentEntity.STATUS_REJECTED) rechazados++
                tipos += fila.type
                if (fila.type == TIPO_PAGO_EN_EFECTIVO) {
                    val payload = Json.parseToJsonElement(fila.payloadJson) as JsonObject
                    val amount = payload["amountCents"] as? JsonPrimitive ?: error("Importe de pago ilegible")
                    require(!amount.isString) { "Importe de pago ilegible" }
                    val cents = amount.content.toBigDecimal().intValueExact()
                    require(cents >= 0) { "Importe de pago negativo" }
                    cobrado = Math.addExact(cobrado, cents)
                }
            }
            return TrabajoSinResolver(intents, rechazados, tipos, cobrado)
        }

        internal fun tocaLaOrden(payloadJson: String, ids: Set<String>): Boolean {
            fun contieneId(value: JsonElement): Boolean = when (value) {
                is JsonObject -> value.values.any(::contieneId)
                is JsonArray -> value.any(::contieneId)
                is JsonPrimitive -> value.isString && value.content in ids
            }
            // Un payload ilegible no permite afirmar que esta orden está limpia.
            return runCatching { contieneId(Json.parseToJsonElement(payloadJson)) }.getOrDefault(true)
        }

        const val LOCAL_DRAFT_RECOVERY_FAILED = "LOCAL_DRAFT_RECOVERY_FAILED"
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

        /** Cuántas entradas del lote van ANTES de la primera ronda retenida (todas si no hay). Pura, como la del cajón. */
        internal fun hastaElPrimerRetenido(estados: List<String>): Int =
            estados.indexOf(SyncIntentEntity.STATUS_HELD).let { if (it < 0) estados.size else it }

        private const val KEY_DEVICE_ID = "sync_device_id"
        private const val BATCH_SIZE = 50
        private const val SAFETY_TIMER_MS = 5L * 60 * 1000
        private const val ACKED_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
