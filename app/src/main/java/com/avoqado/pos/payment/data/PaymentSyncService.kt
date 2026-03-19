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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
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
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // MARK: - Lifecycle

    fun start() {
        if (isStarted) return
        isStarted = true
        Log.d(TAG, "Starting payment sync service")

        syncScope.launch {
            // Reset stuck SYNCING payments (crash recovery)
            dao.resetSyncingToPending()
            refreshCounts()

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
    }

    // MARK: - Timer

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = syncScope.launch {
            while (isActive) {
                delay(SYNC_INTERVAL_MS)
                if (connectivityMonitor.isConnected.value) {
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
            connectivityMonitor.isConnected.collect { connected ->
                if (!connected) {
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

                // Get pending payments
                val pending = dao.getPendingPayments(MAX_PAYMENTS_PER_SYNC)
                if (pending.isEmpty()) {
                    Log.d(TAG, "No pending payments to sync")
                    return@launch
                }

                Log.d(TAG, "Syncing ${pending.size} pending payments")

                for (payment in pending) {
                    // Check if we should stop (e.g., service was stopped)
                    if (!isStarted) break

                    // Exponential backoff: wait before retry
                    if (payment.retryCount > 0) {
                        val backoff = calculateBackoff(payment.retryCount)
                        val timeSinceLastRetry = System.currentTimeMillis() - (payment.lastRetryAt ?: 0)
                        if (timeSinceLastRetry < backoff) {
                            Log.d(TAG, "Skipping ${payment.id} — backoff not elapsed")
                            continue
                        }
                    }

                    // Mark as SYNCING
                    dao.updateStatus(payment.id, PaymentSyncStatus.SYNCING.name)

                    // Attempt sync
                    val shouldStopBatch = syncPayment(payment)
                    if (shouldStopBatch) break
                }

                refreshCounts()
            } catch (e: Exception) {
                Log.e(TAG, "Sync batch error: ${e.message}")
            }
        }
    }

    private fun calculateBackoff(retryCount: Int): Long {
        return minOf(
            (Math.pow(2.0, retryCount.toDouble()) * INITIAL_BACKOFF_MS).toLong(),
            MAX_BACKOFF_MS,
        )
    }

    // MARK: - Single Payment Sync

    private suspend fun syncPayment(payment: PendingPaymentEntity): Boolean {
        // Returns true if batch should stop (e.g., auth expired)

        val venueId = secureStorage.venueId ?: payment.venueId

        try {
            val url: String

            if (payment.paymentType == "ORDER" && payment.orderId != null) {
                // Order payment: POST /mobile/venues/{venueId}/orders/{orderId}/pay
                url = "${ApiConstants.BASE_URL}/mobile/venues/$venueId/orders/${payment.orderId}/pay"
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
                put("method", "CASH")
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
            handleNetworkError(payment, "Sin conexion")
            return false
        } catch (e: java.net.ConnectException) {
            handleNetworkError(payment, "No se pudo conectar al servidor")
            return false
        } catch (e: java.io.IOException) {
            handleNetworkError(payment, e.message ?: "Error de red")
            return false
        } catch (e: Exception) {
            handleNetworkError(payment, e.message ?: "Error desconocido")
            return false
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
                false
            }
            else -> {
                handleNetworkError(payment, "Codigo inesperado: $code")
                false
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

    fun refreshCounts() {
        syncScope.launch {
            dao.getPendingCount().collect { _pendingCount.value = it }
        }
        syncScope.launch {
            dao.getFailedCount().collect { _failedCount.value = it }
        }
    }
}
