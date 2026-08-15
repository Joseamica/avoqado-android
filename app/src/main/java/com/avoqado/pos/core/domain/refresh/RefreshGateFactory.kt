package com.avoqado.pos.core.domain.refresh

import android.os.SystemClock
import com.avoqado.pos.core.util.ConnectivityMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Crea el RefreshGate de un ViewModel con el reloj monotónico real
 * (elapsedRealtime cuenta deep sleep — spec §4.6) y el cooldown cableado a la
 * recuperación de conectividad (spec §4.3 inv. 4). El gate vive y muere con el
 * viewModelScope: identidad por (user, venue) resuelta por la recreación del
 * árbol con contentKey (spec §4.4).
 */
@Singleton
class RefreshGateFactory @Inject constructor(
    private val connectivityMonitor: ConnectivityMonitor,
) {
    fun create(scope: CoroutineScope, ttl: Duration = 30.seconds): RefreshGate {
        val gate = RefreshGate(
            ttl = ttl,
            clock = { SystemClock.elapsedRealtime().milliseconds },
        )
        scope.launch {
            connectivityMonitor.isConnected.collect { connected ->
                if (connected) gate.resetCooldown()
            }
        }
        return gate
    }
}
