package com.avoqado.pos.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.avoqado.pos.core.data.network.ApiConstants
import dagger.hilt.android.qualifiers.ApplicationContext
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
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var retryJob: Job? = null

    private val _isConnected = MutableStateFlow(checkCurrentConnectivity())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Server reachability — set by HTTP interceptors or explicit checks
    private val _isServerReachable = MutableStateFlow(true)
    val isServerReachable: StateFlow<Boolean> = _isServerReachable.asStateFlow()

    // Combined connectivity: device has network AND server is reachable
    val isFullyConnected: Boolean get() = _isConnected.value && _isServerReachable.value

    // Synchronous check used by offline-queue logic (e.g. ReservationRepository)
    fun isOnline(): Boolean = isFullyConnected

    // Flow alias consumed by Tasks 25+ (offline sync worker)
    val isOnlineFlow: StateFlow<Boolean> get() = isConnected

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val wasDisconnected = !_isConnected.value
                _isConnected.value = true
                if (wasDisconnected) {
                    Log.d("📡", "Network reconnected")
                    // If server was marked down while offline, ping immediately
                    // to confirm recovery instead of waiting for the 10s retry tick
                    if (!_isServerReachable.value) {
                        scope.launch { pingServer() }
                    }
                }
            }

            override fun onLost(network: Network) {
                _isConnected.value = false
                Log.d("📡", "Network lost")
            }
        })
    }

    fun reportServerError() {
        if (_isServerReachable.value) {
            _isServerReachable.value = false
            Log.d("📡", "Server marked unreachable — starting retry pings")
            startRetryTimer()
        }
    }

    fun reportServerSuccess() {
        if (!_isServerReachable.value) {
            _isServerReachable.value = true
            Log.d("📡", "Server marked reachable")
            stopRetryTimer()
        }
    }

    private fun startRetryTimer() {
        retryJob?.cancel()
        retryJob = scope.launch {
            while (isActive) {
                delay(RETRY_INTERVAL_MS)
                if (_isConnected.value) {
                    pingServer()
                }
            }
        }
    }

    private fun stopRetryTimer() {
        retryJob?.cancel()
        retryJob = null
    }

    private fun pingServer() {
        if (!_isConnected.value) return
        var connection: HttpURLConnection? = null
        try {
            val url = URL(ApiConstants.BASE_URL)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = PING_TIMEOUT_MS
                readTimeout = PING_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            val ngrokError = connection.getHeaderField("ngrok-error-code")
            // Matches iOS: any 200–499 means the server (or its proxy) is answering.
            // 5xx and ngrok edge errors (tunnel offline) are still treated as down.
            if (code in 200..499 && ngrokError == null) {
                reportServerSuccess()
            } else {
                Log.d("📡", "Ping got $code (ngrok=$ngrokError) — server still down")
            }
        } catch (e: Exception) {
            Log.d("📡", "Ping failed: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun checkCurrentConnectivity(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        private const val RETRY_INTERVAL_MS = 10_000L
        private const val PING_TIMEOUT_MS = 5_000
    }
}
