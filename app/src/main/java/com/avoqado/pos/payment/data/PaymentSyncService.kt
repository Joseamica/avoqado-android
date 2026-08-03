package com.avoqado.pos.payment.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.data.local.database.PaymentSyncStatus
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.util.ConnectivityMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }

    private sealed interface OrderResolution {
        data class Ready(val orderId: String) : OrderResolution
        data class RetryableFailure(val message: String) : OrderResolution
        data class PermanentFailure(val message: String) : OrderResolution
        data object AuthExpired : OrderResolution
    }

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
            var wasDisconnected = false
            combine(
                connectivityMonitor.isConnected,
                connectivityMonitor.isServerReachable,
            ) { network, server -> network && server }.collect { fullyConnected ->
                if (!fullyConnected) {
                    wasDisconnected = true
                } else if (wasDisconnected) {
                    wasDisconnected = false
                    Log.d(TAG, "Network reconnected — triggering sync")
                    delay(2000) // small delay for network to stabilize
                    syncNow()
                }
            }
        }
    }

    // MARK: - Core Sync Logic

    fun syncNow() {
        if (syncJob?.isActive == true) {
            Log.d(TAG, "Sync already in progress, skipping")
            return
        }

        syncJob = syncScope.launch {
            try {
                // Cleanup old synced payments
                val cutoff = System.currentTimeMillis() - CLEANUP_AFTER_MS
                dao.deleteSynced(cutoff)

                repeat(MAX_DRAIN_BATCHES) {
                    if (!isStarted || !connectivityMonitor.isServerReachable.value) return@launch

                    val pending = dao.getPendingPayments(MAX_PAYMENTS_PER_SYNC)
                    if (pending.isEmpty()) {
                        Log.d(TAG, "No pending payments to sync")
                        return@launch
                    }

                    Log.d(TAG, "Syncing ${pending.size} pending payments")
                    var shouldStopDrain = false

                    for (payment in pending) {
                        if (!isStarted) return@launch

                        // FIFO: si el más viejo sigue en backoff, no adelantamos
                        // cobros posteriores y esperamos el próximo trigger.
                        if (payment.retryCount > 0) {
                            val backoff = calculateBackoff(payment.retryCount)
                            val timeSinceLastRetry = System.currentTimeMillis() - (payment.lastRetryAt ?: 0)
                            if (timeSinceLastRetry < backoff) {
                                Log.d(TAG, "Stopping drain at ${payment.id} — backoff not elapsed")
                                shouldStopDrain = true
                                break
                            }
                        }

                        dao.updateStatus(payment.id, PaymentSyncStatus.SYNCING.name)
                        if (syncPayment(payment)) {
                            shouldStopDrain = true
                            break
                        }
                    }

                    if (shouldStopDrain || pending.size < MAX_PAYMENTS_PER_SYNC) return@launch
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
    suspend fun retryFailedPayment(id: String) {
        dao.retryFailed(id)
        syncNow()
    }

    /** Lectura persistida para impedir logout/cambio de venue con trabajo oculto. */
    suspend fun blockingWorkCount(): Int =
        dao.getUnsyncedPayments().size + dao.getFailedPayments().size

    private fun calculateBackoff(retryCount: Int): Long {
        return minOf(
            (Math.pow(2.0, retryCount.toDouble()) * INITIAL_BACKOFF_MS).toLong(),
            MAX_BACKOFF_MS,
        )
    }

    // MARK: - Single Payment Sync

    private suspend fun syncPayment(payment: PendingPaymentEntity): Boolean {
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
                    is OrderResolution.Ready -> resolvedOrderId = result.orderId
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

            val url: String

            if (payment.paymentType == "ORDER" && resolvedOrderId != null) {
                // Order payment: POST /mobile/venues/{venueId}/orders/{orderId}/pay
                url = "${ApiConstants.BASE_URL}/mobile/venues/$venueId/orders/$resolvedOrderId/pay"
            } else {
                // Fast payment: POST /mobile/venues/{venueId}/fast
                url = "${ApiConstants.BASE_URL}/mobile/venues/$venueId/fast"
            }

            // Build payment body
            val bodyJson = buildJsonObject {
                put("venueId", venueId)
                put("amount", payment.amountCents)
                put("tip", payment.tipCents)
                put("status", "COMPLETED")
                // El método REAL de la cola, no un "CASH" fijo: reproducir un
                // cobro con terminal ajena como efectivo descuadra el arqueo.
                val manual = runCatching {
                    com.avoqado.pos.payment.domain.ManualPaymentMethod.valueOf(payment.method)
                }.getOrNull()
                put("method", manual?.serverMethod ?: "CASH")
                manual?.externalSource?.let { put("externalSource", it) }
                put("splitType", "FULLPAYMENT")
                put("staffId", payment.staffId)
                put("source", "AVOQADO_ANDROID")
                put("idempotencyKey", payment.id)
            }.toString()

            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody(JSON_MEDIA))
                .build()

            val (code, responseBody) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            return handleSyncResult(payment, code, responseBody)

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

            when {
                code in 200..299 -> {
                    val orderId = extractOrderId(body)
                    if (orderId.isNullOrBlank()) {
                        OrderResolution.RetryableFailure("Order sync missing orderId in response")
                    } else {
                        Log.d(TAG, "✅ Recreated order for queued payment ${payment.id}: $orderId")
                        OrderResolution.Ready(orderId)
                    }
                }
                code == 401 -> OrderResolution.AuthExpired
                code in 400..499 -> OrderResolution.PermanentFailure("Order recreate failed ($code): $body")
                else -> OrderResolution.RetryableFailure("Order recreate server error ($code)")
            }
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

    private fun extractOrderId(body: String): String? {
        return try {
            val root = json.parseToJsonElement(body).jsonObject
            root["data"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                ?: root["id"]?.jsonPrimitive?.contentOrNull
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun handleSyncResult(payment: PendingPaymentEntity, code: Int, body: String): Boolean {
        return when {
            code in 200..299 -> {
                // Success
                Log.d(TAG, "✅ Payment ${payment.id} synced successfully")
                dao.updateStatus(payment.id, PaymentSyncStatus.SYNCED.name)
                false  // continue batch
            }
            code == 401 -> {
                // Auth expired — don't burn retries, stop batch
                Log.w(TAG, "⚠️ Auth expired — pausing sync, payment ${payment.id} stays PENDING")
                dao.updateStatus(payment.id, PaymentSyncStatus.PENDING.name)
                true  // STOP batch
            }
            code == 409 -> {
                // Duplicate — already processed (idempotency key matched)
                Log.d(TAG, "✅ Payment ${payment.id} already exists (409), marking synced")
                dao.updateStatus(payment.id, PaymentSyncStatus.SYNCED.name)
                false
            }
            code in 400..499 -> {
                // Client error — permanent failure (bad request, not retryable)
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
