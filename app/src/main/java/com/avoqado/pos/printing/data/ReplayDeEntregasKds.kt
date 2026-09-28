package com.avoqado.pos.printing.data

import android.util.Log
import com.avoqado.pos.core.data.lan.KdsLanProtocol
import com.avoqado.pos.core.data.sync.SyncIntentTypes
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.local.EntregasKdsPendientesDao
import com.avoqado.pos.printing.routing.KitchenDeliveryPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ReplayDeEntregasKds"

/**
 * Etapa 3 del KDS (3.5, D7) — al abrir la app, las entregas por WiFi que quedaron en disco (el proceso murió a media
 * entrega): las de < 10 min se reintentan UNA vez por WiFi (esperando a que aparezcan pantallas); las demás y las que
 * no acusen salen en papel con su trabajo congelado y se marcan `FALLBACK_PRINTED`. Si el papel tampoco sale, el
 * trabajo pasa al almacén de comandas pendientes (su reloj insiste y Cobrar ofrece «Volver a imprimir»). Las de otro
 * venue se quedan (suspendidas), como en `ComandasPendientesStore`.
 */
@Singleton
class ReplayDeEntregasKds @Inject constructor(
    private val dao: EntregasKdsPendientesDao,
    private val entregaPorWifi: EntregaPorWifi,
    private val comandaDispatcher: ComandaDispatcher,
    private val syncOutbox: SyncOutbox,
    private val comandasPendientesStore: ComandasPendientesStore,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val candado = Mutex()

    /**
     * 🔴 Guarda contra un despacho VIVO: la fila de una entrega en curso existe hasta el `finally` de `dispatch` (`insistir`
     * puede tardar ~1 min), y `startOfflineOutbox()` corre también en cada `refreshTabs()` (cambio de sucursal o de modo,
     * refresco tras el login). Sin esto el replay la vería «reciente», la re-empujaría o reimprimiría el RESPALDO y
     * encolaría otra marca, y borraría la fila antes de tiempo. Sólo se toman filas creadas ANTES de que existiera este
     * objeto: nace con `AppState`, antes de cualquier venta, así que toda fila más nueva es de un despacho de ESTE proceso,
     * que la cierra él mismo. Con eso las pasadas repetidas son idempotentes: la primera borra lo del proceso muerto y las
     * siguientes no ven nada. Sin `SystemClock` a propósito (las pruebas de la JVM no lo tienen).
     */
    private val arranqueDelProcesoMillis = System.currentTimeMillis()

    suspend fun reproducirAlAbrir(
        venueId: String,
        ahora: Long = System.currentTimeMillis(),
        arranqueDelProceso: Long = arranqueDelProcesoMillis,
    ) {
        if (candado.isLocked) return // `startOfflineOutbox` corre 2-3 veces al abrir: una sola pasada basta
        candado.withLock {
            for (fila in dao.delVenue(venueId)) {
                if (fila.creadaEnMillis >= arranqueDelProceso) {
                    Log.d(TAG, "⏭️ ${fila.sourceKey} es de un despacho vivo de este proceso: se deja")
                    continue
                }
                val mensaje = KdsLanProtocol.decodeComanda(fila.mensajeJson)
                val trabajo = runCatching { json.decodeFromString(TrabajoPendiente.serializer(), fila.trabajoJson) }.getOrNull()
                if (mensaje == null || trabajo == null) {
                    Log.w(TAG, "Entrega ilegible ${fila.sourceKey}: se descarta")
                    dao.borrar(fila.sourceKey)
                    continue
                }
                val reciente = ahora - fila.creadaEnMillis < VENTANA_REINTENTO_MS
                if (reciente && entregaPorWifi.empujar(mensaje, ESPERA_PANTALLAS_MS)) {
                    Log.i(TAG, "📡 ${fila.sourceKey} llegó a la pantalla al reabrir")
                    dao.borrar(fila.sourceKey)
                    continue
                }
                when (val estado = comandaDispatcher.reintentar(trabajo)) {
                    is EstadoDeComanda.Salio -> marcar(venueId, fila.sourceKey, fila.stationId, trabajo.orderNumber)
                    // 🔴 Sin papel no se marca (esconderia una comanda que nadie vio): el reloj de siempre insiste.
                    is EstadoDeComanda.NoSalio -> comandasPendientesStore.guardar(estado)
                    else -> Unit
                }
                dao.borrar(fila.sourceKey)
            }
        }
    }

    private suspend fun marcar(venueId: String, sourceKey: String, stationId: String, label: String) {
        runCatching {
            syncOutbox.enqueue(
                venueId,
                SyncIntentTypes.KDS_TICKET_MARK,
                buildJsonObject {
                    put("sourceKey", sourceKey)
                    put("stationId", stationId)
                    put("action", KitchenDeliveryPolicy.FALLBACK_PRINTED)
                    put("label", label)
                },
            )
        }.onFailure { Log.w(TAG, "No se pudo encolar la marca de papel $sourceKey: ${it.message}") }
    }

    companion object {
        /** Spec §6: «al abrir reintenta las de menos de 10 min y el resto sale en papel». */
        const val VENTANA_REINTENTO_MS = 10L * 60 * 1_000
        /** El descubrimiento mDNS tarda unos segundos al abrir la app: se espera a lo más esto a que aparezca la pantalla. */
        const val ESPERA_PANTALLAS_MS = 3_000L
    }
}
