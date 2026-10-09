package com.avoqado.pos.tables.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.sync.SyncIntentTypes
import com.avoqado.pos.core.data.sync.SyncOutbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "🔁 TableSync"

/**
 * Offline-first Corte B — escucha los acks del [SyncOutbox] y reconcilia el
 * estado de mesas del POS:
 *
 * - Ack de OPEN_TABLE: promueve la sesión provisional (UUID local → orderId
 *   real del server) y refresca el plano.
 * - Ack de cualquier intent de mesa: refresca el plano para que el POS pinte
 *   la verdad del server (server reconciliation — lo local se rebasea).
 *
 * Los REJECTED solo se loguean aquí: el contador de cuarentena visible vive en
 * SyncOutbox.rejectedCount y la UI lo muestra; jamás se descartan en silencio.
 */
@Singleton
class TableSyncCoordinator @Inject constructor(
    private val outbox: SyncOutbox,
    private val tableSession: TableSession,
    private val repository: TableServiceRepository,
    private val secureStorage: SecureStorage,
    private val intents: SyncIntentDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            outbox.acks.collect { ack ->
                runCatching { handle(ack) }.onFailure { Log.e(TAG, "❌ ack handler: ${it.message}") }
            }
        }
    }

    internal suspend fun handle(ack: com.avoqado.pos.core.data.sync.SyncAck) {
        if (!ack.isAcked) {
            Log.w(TAG, "🚫 Intent rechazado (${ack.errorCode}): ${ack.message}")
            return
        }
        val result = ack.result ?: return
        val venueId = secureStorage.venueId ?: return
        val type = intents.preparationStatuses(venueId, listOf(ack.id)).singleOrNull()?.type
        // Preparation revisions belong to kitchen items, never to the table order.
        if (type == SyncIntentTypes.KDS_ITEM_PROGRESS || type == SyncIntentTypes.KDS_TICKET_MARK) return
        if (secureStorage.venueId != venueId) return
        val localOrderId = result["localOrderId"]?.jsonPrimitive?.contentOrNull
        val orderId = result["orderId"]?.jsonPrimitive?.contentOrNull
        val orderNumber = result["orderNumber"]?.jsonPrimitive?.contentOrNull
        val version = result["version"]?.jsonPrimitive?.intOrNull

        if (localOrderId != null && orderId != null) {
            Log.d(TAG, "⬆️ Promoviendo sesión provisional $localOrderId → $orderId")
            tableSession.promoteProvisional(localOrderId, orderId, orderNumber, version ?: 1)
        } else if (version != null && orderId != null && tableSession.current()?.orderId == orderId) {
            // Ronda/cobro de la sesión activa confirmado: versión fresca evita 409.
            tableSession.updateVersion(version)
        }

        // Server reconciliation: el plano se rebasea a la verdad del server.
        repository.refresh(venueId)
    }

    companion object {
        /** Tipos cuyo ack toca mesas (por si se filtra a futuro). */
        val TABLE_TYPES = setOf(SyncIntentTypes.OPEN_TABLE, SyncIntentTypes.ADD_ITEMS, SyncIntentTypes.PAY_CASH)
    }
}
