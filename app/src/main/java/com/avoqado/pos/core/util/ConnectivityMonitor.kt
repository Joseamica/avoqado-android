package com.avoqado.pos.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _isConnected = MutableStateFlow(checkCurrentConnectivity())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Server reachability — set by HTTP interceptors or explicit checks
    private val _isServerReachable = MutableStateFlow(true)
    val isServerReachable: StateFlow<Boolean> = _isServerReachable.asStateFlow()

    // Combined connectivity: device has network AND server is reachable
    val isFullyConnected: Boolean get() = _isConnected.value && _isServerReachable.value

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
            Log.d("📡", "Server marked unreachable")
        }
    }

    fun reportServerSuccess() {
        if (!_isServerReachable.value) {
            _isServerReachable.value = true
            Log.d("📡", "Server marked reachable")
        }
    }

    private fun checkCurrentConnectivity(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
