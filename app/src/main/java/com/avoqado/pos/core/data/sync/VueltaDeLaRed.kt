package com.avoqado.pos.core.data.sync

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentDao
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.PaymentSyncService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Volvió la red» para las pantallas que sólo leen del servidor (Ventas e Informes, D18, 3-nov-2026). Codex r1 #2 y #4:
 *
 * - Es una vuelta REAL de la red del aparato (sin red → con red). Un parpadeo de la bandera del servidor con el WiFi
 *   arriba no cuenta: con un fallo parcial (una ruta agota el tiempo y la salud sigue contestando) cada parpadeo volvía a
 *   lanzar la recarga, sin fin.
 * - Espera a que la ruta suba (los mismos 2 s que la cola de cobros) y a que el servidor conteste.
 * - Espera a que terminen de subir los cobros y las operaciones guardadas sin red: recargar a la vez que suben dejaba
 *   Ventas en «Sin transacciones» e Informes en ceros. Si en [esperaDeLaColaMs] no se vacía (algo atorado), se recarga
 *   igual: mejor lo que haya que una pantalla esperando para siempre.
 *
 * Nunca termina sola: vive mientras viva quien la llama (la pantalla a la vista).
 */
@Singleton
class VueltaDeLaRed internal constructor(
    private val red: Flow<Boolean>,
    private val servidor: Flow<Boolean>,
    private val porSubir: Flow<Int>,
    private val esperaDeLaRutaMs: Long = PaymentSyncService.ESPERA_DE_ESTABILIZACION_MS,
    private val esperaDeLaColaMs: Long = ESPERA_DE_LA_COLA_MS,
) {
    @Inject
    constructor(
        connectivityMonitor: ConnectivityMonitor,
        cobros: PendingPaymentDao,
        operaciones: SyncOutbox,
        secureStorage: SecureStorage,
    ) : this(
        red = connectivityMonitor.isConnected,
        servidor = connectivityMonitor.isServerReachable,
        // La sucursal se lee al esperar, no al construir: es la de la sesión de ese momento.
        porSubir = flow {
            emitAll(combine(cobros.observarPorSubir(secureStorage.venueId.orEmpty()), operaciones.pendingCount) { c, o -> c + o })
        },
    )

    /** Corre [accion] cada vez que la red del aparato vuelve de verdad, con el servidor contestando y la cola ya arriba. */
    suspend fun alVolver(accion: suspend () -> Unit) {
        var estuvoSinRed = false
        red.distinctUntilChanged().collectLatest { conectado ->
            if (!conectado) {
                estuvoSinRed = true
                return@collectLatest
            }
            if (!estuvoSinRed) return@collectLatest
            delay(esperaDeLaRutaMs)
            servidor.first { it }
            withTimeoutOrNull(esperaDeLaColaMs) { porSubir.first { it == 0 } }
            estuvoSinRed = false
            accion()
        }
    }

    companion object {
        /** Lo más que se espera a que la cola sin red termine de subir antes de recargar de todos modos. */
        internal const val ESPERA_DE_LA_COLA_MS = 30_000L
    }
}
