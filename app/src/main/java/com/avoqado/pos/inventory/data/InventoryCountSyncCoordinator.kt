package com.avoqado.pos.inventory.data

import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.inventory.data.model.StockCountItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Transporte de las cuatro mutaciones que comparten orden por conteo. */
interface InventoryCountTransport {
    suspend fun enviarAvance(
        venueId: String,
        countId: String,
        items: List<StockCountItem>,
        expectedRevision: Int,
    ): RespuestaHttp

    suspend fun enviarFinal(
        venueId: String,
        countId: String,
        items: List<StockCountItem>,
        note: String?,
        expectedRevision: Int,
    ): RespuestaHttp

    suspend fun confirmarConteo(
        venueId: String,
        countId: String,
        expectedRevision: Int,
    ): RespuestaHttp

    suspend fun cancelStockCount(
        venueId: String,
        countId: String,
        expectedRevision: Int,
    ): RespuestaHttp
}

/**
 * Dueño de sesión del replay de inventario. No depende de que exista una pantalla o ViewModel.
 */
class InventoryCountSyncCoordinator(
    private val store: BorradorDeConteoStore,
    private val remote: InventoryCountTransport,
    @Suppress("unused") private val connectivityMonitor: ConnectivityMonitor,
) {
    private var initialDrain: Job? = null

    fun start(scope: CoroutineScope) {
        if (initialDrain?.isActive == true) return
        initialDrain = scope.launch { drainOnce() }
    }

    fun stop() {
        initialDrain?.cancel()
        initialDrain = null
    }

    private suspend fun drainOnce() {
        for (venueId in store.venuesConTrabajo()) {
            val draft = store.leer(venueId) ?: continue
            val countId = draft.countId ?: continue // CYCLE local: sólo el toque explícito lo crea.
            val revision = draft.revision ?: continue // Base desconocida: nunca inventar 0.
            if (draft.conflictoRevision != null) continue
            val items = ConteoEnCurso.lineasParaEnviar(draft.lineas, draft.pendientesDeEnviar)
            if (items.isEmpty()) continue
            val sellos = items.associate { it.id to (it.counted to it.countedAt) }
            val respuesta = remote.enviarAvance(venueId, countId, items, revision)
            if (respuesta.code in 200..299) {
                val nuevaRevision = respuesta.revision ?: continue
                store.reconocerPut(
                    venueId = venueId,
                    countId = countId,
                    expectedRevision = revision,
                    nuevaRevision = nuevaRevision,
                    sellos = sellos,
                )
            }
        }
    }
}
