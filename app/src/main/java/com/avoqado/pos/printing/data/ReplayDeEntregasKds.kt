package com.avoqado.pos.printing.data

import android.util.Log
import com.avoqado.pos.core.data.lan.KdsLanProtocol
import com.avoqado.pos.core.data.sync.SyncIntentTypes
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.local.EntregaKdsPendienteEntity
import com.avoqado.pos.kds.data.local.EntregasKdsPendientesDao
import com.avoqado.pos.printing.routing.KitchenDeliveryPolicy
import kotlinx.coroutines.CancellationException
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
 * entrega, o su papel de respaldo no salió): las de < 10 min se reintentan UNA vez por WiFi (esperando a que aparezcan
 * pantallas); las demás y las que no acusen salen en papel con su trabajo congelado y se marcan `FALLBACK_PRINTED`. Las
 * de otro venue se quedan (suspendidas), como en `ComandasPendientesStore`.
 *
 * Una fila se borra SÓLO cuando su destino ya se decidió (ronda 1 de la revisión): acusó, su papel salió, su estación
 * no tiene impresora, pasó la vigencia de 8 h, o es ilegible. Si el papel no sale, el trabajo va a la libreta (si su
 * única ranura está libre) y la fila se queda: la cierra el papel cuando por fin salga
 * ([EntregaPorWifi.cerrarPorPapel]) o la retoma la próxima apertura.
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
     * I1 de la revisión — la regla que acota una fila que truena: se intenta A LO MÁS UNA vez por proceso (las 2-3
     * pasadas de `startOfflineOutbox` al abrir no la repiten) y la vigencia de 8 h la retira sin imprimir. Así una fila
     * envenenada no tumba la app ni se reintenta en bucle. Sólo lo toca el lazo, bajo [candado].
     */
    private val intentadas = mutableSetOf<String>()

    /**
     * 🔴 Guarda contra un despacho VIVO: la fila de una entrega en curso existe hasta que `dispatch` decide su papel
     * (`insistir` puede tardar ~1 min), y `startOfflineOutbox()` corre también en cada `refreshTabs()` (cambio de sucursal
     * o de modo, refresco tras el login). Sin esto el replay la vería «reciente», la re-empujaría o reimprimiría el
     * RESPALDO y encolaría otra marca. Sólo se toman filas creadas ANTES de que existiera este objeto: nace con
     * `AppState`, antes de cualquier venta, así que toda fila más nueva es de ESTE proceso. Sin `SystemClock` a propósito
     * (las pruebas de la JVM no lo tienen). ponytail: reloj de pared; un reloj que retrocede tras abrir haría ver vivas
     * como muertas (M2 de la revisión) — el arreglo es un conjunto en memoria de folios en vuelo en [EntregaPorWifi].
     */
    private val arranqueDelProcesoMillis = System.currentTimeMillis()

    suspend fun reproducirAlAbrir(
        venueId: String,
        ahora: Long = System.currentTimeMillis(),
        arranqueDelProceso: Long = arranqueDelProcesoMillis,
    ) {
        // M3: sin «si está ocupado, sal»: una pasada de OTRO venue (cambio de sucursal a media pasada) espera su turno en
        // vez de perderse. Las pasadas repetidas no cuestan: lo resuelto ya se borró y lo intentado se salta.
        candado.withLock {
            val filas = try {
                dao.delVenue(venueId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudieron leer las entregas pendientes: ${e.message}")
                return
            }
            for (fila in filas) {
                if (fila.creadaEnMillis >= arranqueDelProceso) {
                    Log.d(TAG, "⏭️ ${fila.sourceKey} es de un despacho de este proceso: se deja")
                    continue
                }
                if (fila.sourceKey in intentadas) continue
                try {
                    reproducir(fila, venueId, ahora)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // I1: se queda en disco para la próxima apertura; ésta no la vuelve a intentar.
                    Log.w(TAG, "La entrega ${fila.sourceKey} tropezó: ${e.message} — se queda para la próxima apertura")
                }
            }
        }
    }

    private suspend fun reproducir(fila: EntregaKdsPendienteEntity, venueId: String, ahora: Long) {
        // I4: una comanda de hace más de 8 h ya se resolvió (la cocina la preparó, alguien la cantó, el cliente se fue).
        // Imprimirla sola en la cocina manda comida que nadie pidió: se retira sin papel ni marca.
        if (ahora - fila.creadaEnMillis > VIGENCIA_MS) {
            Log.w(TAG, "🗑️ ${fila.sourceKey} tiene más de 8 h: se descarta sin imprimir")
            dao.borrar(fila.sourceKey)
            return
        }
        val mensaje = KdsLanProtocol.decodeComanda(fila.mensajeJson)
        val trabajo = runCatching { json.decodeFromString(TrabajoPendiente.serializer(), fila.trabajoJson) }.getOrNull()
        if (mensaje == null || trabajo == null) {
            Log.w(TAG, "Entrega ilegible ${fila.sourceKey}: se descarta")
            dao.borrar(fila.sourceKey)
            return
        }
        // La libreta ya tiene ESTE papel (se guardó cuando no salió): su reloj lo imprime y cierra la fila. Imprimirlo
        // aquí también saldría dos veces. La fila espera, y no cuenta como intentada: si la libreta lo suelta, la
        // siguiente pasada lo toma.
        if (laLibretaLoTiene(trabajo)) {
            Log.d(TAG, "📒 ${fila.sourceKey} ya está en la libreta: su reloj lo imprime")
            return
        }
        intentadas += fila.sourceKey
        val reciente = ahora - fila.creadaEnMillis < VENTANA_REINTENTO_MS
        if (reciente && entregaPorWifi.empujar(mensaje, ESPERA_PANTALLAS_MS)) {
            Log.i(TAG, "📡 ${fila.sourceKey} llegó a la pantalla al reabrir")
            dao.borrar(fila.sourceKey)
            return
        }
        when (val estado = comandaDispatcher.reintentar(trabajo)) {
            is EstadoDeComanda.Salio -> {
                marcar(venueId, fila.sourceKey, fila.stationId, trabajo.orderNumber)
                dao.borrar(fila.sourceKey)
            }
            is EstadoDeComanda.NoSalio -> when {
                // Sin ninguna impresora: no hay papel que reintentar (reenviarlo no le inventa una). Decidida, sin marca.
                estado.trabajo == null -> dao.borrar(fila.sourceKey)
                // 🔴 Sin papel no se marca (escondería una comanda que nadie vio). La libreta es de UNA ranura: sólo se
                // guarda si está libre (M4: pisarla le quitaría el reintento a otra comanda). La fila se queda.
                comandasPendientesStore.pendiente.value == null -> comandasPendientesStore.guardar(estado)
                else -> Log.w(TAG, "🧾 ${fila.sourceKey} no salió y la libreta está ocupada: espera a la próxima apertura")
            }
            else -> Unit
        }
    }

    /** ¿La libreta guarda el papel de esta fila? Mismo venue, misma orden y el MISMO plan (renglón por renglón). */
    private fun laLibretaLoTiene(trabajo: TrabajoPendiente): Boolean {
        val enLibreta = comandasPendientesStore.pendiente.value?.trabajo ?: return false
        return trabajo.planes.isNotEmpty() &&
            enLibreta.venueId == trabajo.venueId &&
            enLibreta.orderNumber == trabajo.orderNumber &&
            trabajo.planes.all { it in enLibreta.planes }
    }

    private suspend fun marcar(venueId: String, sourceKey: String, stationId: String, label: String) {
        try {
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo encolar la marca de papel $sourceKey: ${e.message}")
        }
    }

    companion object {
        /** Spec §6: «al abrir reintenta las de menos de 10 min y el resto sale en papel». */
        const val VENTANA_REINTENTO_MS = 10L * 60 * 1_000
        /** El descubrimiento mDNS tarda unos segundos al abrir la app: se espera a lo más esto a que aparezca la pantalla. */
        const val ESPERA_PANTALLAS_MS = 3_000L
        /**
         * I4 (decisión del controlador): la MISMA vigencia que la libreta — espejo de `ComandasPendientesStore.VIGENCIA_MS`,
         * que es privada. 8 h cubren un turno de mostrador y nunca llegan al día siguiente.
         */
        const val VIGENCIA_MS = 8L * 60 * 60 * 1_000
    }
}
