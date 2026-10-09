package com.avoqado.pos.printing.data

import android.util.Log
import com.avoqado.pos.core.data.lan.KdsLanProtocol
import com.avoqado.pos.core.data.lan.RachaSinAcuse
import com.avoqado.pos.core.data.lan.TransporteLan
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncIntentTypes
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.local.EntregaKdsPendienteEntity
import com.avoqado.pos.kds.data.local.EntregasKdsPendientesDao
import com.avoqado.pos.printing.routing.KitchenDeliveryPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ReplayDeEntregasKds"

/**
 * Etapa 3 del KDS (3.5, D7) — las entregas por WiFi que quedaron en disco: el proceso murió a media entrega, o su papel
 * de respaldo no salió. Las de < 10 min se reintentan por WiFi (esperando a que aparezcan pantallas); las demás y las que
 * no acusen salen en papel con su trabajo congelado y se marcan `FALLBACK_PRINTED`. Las de otro venue se quedan
 * (suspendidas), como en `ComandasPendientesStore`.
 *
 * Una fila se borra SÓLO cuando su destino ya se decidió (ronda 1): acusó, su papel salió, su estación no tiene
 * impresora, pasó la vigencia de 8 h, o es ilegible. Si el papel no sale, el trabajo va a la libreta (si su única ranura
 * está libre) y la fila se queda: la cierra el papel cuando por fin sale ([EntregaPorWifi.cerrarPorPapel]).
 *
 * Ronda 2 (N2): corre con un RELOJ ([iniciar]) — una pasada al abrir y otra cada minuto, como `ReplayDeComandasPendientes`
 * — así que una impresora que se cae y regresa saca su papel sin reabrir la app. Cada pasada reintenta lo elegible; la
 * vigencia de 8 h es la que acota a una fila que no sale nunca.
 */
@Singleton
class ReplayDeEntregasKds @Inject constructor(
    private val dao: EntregasKdsPendientesDao,
    private val entregaPorWifi: EntregaPorWifi,
    private val comandaDispatcher: ComandaDispatcher,
    private val syncOutbox: SyncOutbox,
    private val comandasPendientesStore: ComandasPendientesStore,
    /**
     * Ronda 3 (I1 de la revisión de la Task 7): la sucursal VIGENTE. Una pasada que se traslapa con un cambio de sucursal
     * imprimiría las filas de A en la LAN de B (con la config de B, sin encabezado de respaldo) y su `reintentar` pisaría
     * la config de impresión global con la de A. Por eso se revalida antes de CADA fila y antes de imprimir.
     */
    private val secureStorage: SecureStorage,
    /** M1 de la revisión de la Task 10: el acuse del replay limpia la racha de esa estación ([RachaSinAcuse]). */
    private val transporte: TransporteLan,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val candado = Mutex()
    private var reloj: Job? = null
    @Volatile private var venueDelReloj: String? = null

    /**
     * Paridad con iOS (ronda 1 de la Task 7): filas cuyo papel SALIÓ en este proceso pero no se pudieron borrar (disco).
     * La siguiente pasada sólo reintenta BORRARLAS; sin esto, con el reloj de 60 s, sería un papel repetido por minuto
     * durante 8 h. Sólo se toca dentro de una pasada, bajo [candado]. ponytail: en memoria — si el proceso muere antes
     * de borrarla, la siguiente apertura la imprime UNA vez más (duplicado, nunca pérdida).
     */
    private val yaSalieron = mutableSetOf<String>()

    /**
     * 🔴 Guarda contra un despacho VIVO: la fila de una entrega en curso existe mientras `dispatch` decide su papel
     * (`insistir` puede tardar ~1 min). Se toman las filas creadas ANTES de que existiera este objeto (nace con
     * `AppState`, antes de cualquier venta: son de un proceso muerto) y las de este proceso que su despacho ya SOLTÓ
     * (`soltadaEnMillis`, N2). Una fila de este proceso sin soltar es de un despacho en curso: se deja. Sin `SystemClock` a
     * propósito (las pruebas de la JVM no lo tienen). ponytail: reloj de pared; un reloj que retrocede tras abrir haría
     * ver vivas como muertas (M2) — el arreglo es un conjunto en memoria de entregas en vuelo en [EntregaPorWifi].
     */
    private val arranqueDelProcesoMillis = System.currentTimeMillis()

    /**
     * El reloj: una pasada ya y otra cada [INTERVALO_MS]. Idempotente: llamarlo otra vez (`startOfflineOutbox` corre 2-3
     * veces al abrir y en cada cambio de sucursal) no crea otro reloj, sólo cambia la sucursal a la que apunta.
     */
    fun iniciar(scope: CoroutineScope, venueId: String) {
        venueDelReloj = venueId
        if (reloj?.isActive == true) return
        reloj = scope.launch {
            while (isActive) {
                venueDelReloj?.let { v ->
                    try {
                        reproducirAlAbrir(v)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "La pasada de entregas tropezó: ${e.message}")
                    }
                }
                delay(INTERVALO_MS)
            }
        }
    }

    /** Al cerrar sesión: sin sesión no se imprime nada de la sucursal anterior. */
    fun detener() {
        reloj?.cancel()
        reloj = null
        venueDelReloj = null
    }

    /**
     * UNA pasada. Una sola a la vez (como `ReplayDeComandasPendientes`): si otra está en curso, ésta se omite — el
     * siguiente tic la repite. Una fila que truena no tumba la pasada ni la app (I1): se queda y el siguiente tic la
     * vuelve a intentar.
     */
    suspend fun reproducirAlAbrir(
        venueId: String,
        ahora: Long = System.currentTimeMillis(),
        arranqueDelProceso: Long = arranqueDelProcesoMillis,
    ) {
        if (!candado.tryLock()) {
            Log.d(TAG, "Ya hay una pasada en curso — ésta se omite")
            return
        }
        try {
            val filas = try {
                dao.delVenue(venueId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "No se pudieron leer las entregas pendientes: ${e.message}")
                return
            }
            for (fila in filas) {
                if (!sigueVigente(venueId)) return // las filas que faltan quedan SUSPENDIDAS: su sucursal las retoma
                if (fila.creadaEnMillis >= arranqueDelProceso && fila.soltadaEnMillis == null) {
                    Log.d(TAG, "⏭️ ${fila.entregaId} es de un despacho en curso: se deja")
                    continue
                }
                try {
                    reproducir(fila, venueId, ahora)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "La entrega ${fila.entregaId} tropezó: ${e.message} — el siguiente tic la vuelve a intentar")
                }
            }
        } finally {
            candado.unlock()
        }
    }

    private suspend fun reproducir(fila: EntregaKdsPendienteEntity, venueId: String, ahora: Long) {
        if (fila.entregaId in yaSalieron) {
            dao.borrar(fila.entregaId) // si vuelve a tronar, se queda en el conjunto y el siguiente tic insiste
            yaSalieron.remove(fila.entregaId)
            return
        }
        // I4: una comanda de hace más de 8 h ya se resolvió (la cocina la preparó, alguien la cantó, el cliente se fue).
        // Imprimirla sola en la cocina manda comida que nadie pidió: se retira sin papel ni marca.
        val preparation = KdsLanProtocol.decodeComanda(fila.mensajeJson)?.preparationVersion == 1
        if (!preparation && ahora - fila.creadaEnMillis > VIGENCIA_MS) {
            Log.w(TAG, "🗑️ ${fila.entregaId} tiene más de 8 h: se descarta sin imprimir")
            dao.borrar(fila.entregaId)
            return
        }
        val mensaje = KdsLanProtocol.decodeComanda(fila.mensajeJson)
        val trabajo = runCatching { json.decodeFromString(TrabajoPendiente.serializer(), fila.trabajoJson) }.getOrNull()
        if (mensaje == null || trabajo == null) {
            Log.w(TAG, "Entrega ilegible ${fila.entregaId}: se descarta")
            dao.borrar(fila.entregaId)
            return
        }
        // La libreta ya tiene ESTE papel (se guardó cuando no salió): su reloj lo imprime y cierra la fila. Imprimirlo
        // aquí también saldría dos veces. La fila espera: si la libreta lo suelta, el siguiente tic lo toma.
        if (laLibretaLoTiene(trabajo)) {
            Log.d(TAG, "📒 ${fila.entregaId} ya está en la libreta: su reloj lo imprime")
            return
        }
        val reciente = ahora - fila.creadaEnMillis < VENTANA_REINTENTO_MS
        if ((reciente || preparation) && entregaPorWifi.empujar(mensaje, ESPERA_PANTALLAS_MS)) {
            Log.i(TAG, "📡 ${fila.entregaId} llegó a la pantalla")
            // M1: SÓLO se registra el acuse — un empuje sin acuse del replay nunca cuenta como fallo (ya lo cuenta el
            // envío en vivo de EntregaPorWifi.entregar; contarlo aquí también duplicaría la racha).
            transporte.racha.registrar(setOf(fila.stationId), setOf(fila.stationId))
            dao.borrar(fila.entregaId)
            return
        }
        // A retained course is visible on KDS, but never becomes a preparation ticket on paper.
        if (preparation && trabajo.planes.isEmpty()) return
        // I1: la espera a la pantalla (hasta 3 s + el empuje) da tiempo a un cambio de sucursal, y el refresco de la config
        // (hasta 1.5 s) también. Se revalida ANTES — nunca se refresca la config de una sucursal que ya no es la vigente —
        // y DESPUÉS: la config en memoria podría ser ya la de la otra y el papel saldría en su cocina. Paridad con iOS.
        if (!sigueVigente(venueId)) return
        comandaDispatcher.refrescarConfig(venueId)
        if (!sigueVigente(venueId)) return
        when (val estado = comandaDispatcher.reintentar(trabajo, refrescar = false)) {
            is EstadoDeComanda.Salio -> {
                // Codex 3.6 (#1): una fila de RONDA no sabe si su folio tenía otros tiempos (uno ya en la pantalla, sin
                // papel), y la marca esconde el folio ENTERO: sale en papel sin marca (duplicado, nunca pérdida).
                if (!fila.sourceKey.startsWith("round:")) marcar(venueId, fila.sourceKey, fila.stationId, trabajo.orderNumber)
                try {
                    dao.borrar(fila.entregaId)
                } catch (e: Throwable) {
                    yaSalieron += fila.entregaId // el papel ya salió: el siguiente tic sólo reintenta borrarla
                    throw e
                }
            }
            is EstadoDeComanda.NoSalio -> when {
                // Sin ninguna impresora: no hay papel que reintentar (reenviarlo no le inventa una). Decidida, sin marca;
                // su pantalla la verá cuando el servidor tenga la venta (N5: aceptado, y se deja rastro).
                estado.trabajo == null -> {
                    Log.w(TAG, "🧾 ${fila.entregaId}: su estación no tiene impresora — se da por decidida y se borra")
                    dao.borrar(fila.entregaId)
                }
                // 🔴 Sin papel no se marca (escondería una comanda que nadie vio). La libreta es de UNA ranura: sólo se
                // guarda si está libre (M4: pisarla le quitaría el reintento a otra comanda). La fila se queda.
                comandasPendientesStore.pendiente.value == null -> comandasPendientesStore.guardar(estado)
                else -> Log.d(TAG, "🧾 ${fila.entregaId} no salió y la libreta está ocupada: el siguiente tic insiste")
            }
            else -> Unit
        }
    }

    /** I1: si la sucursal ya no es la vigente, se deja todo (nada se borra) y la pasada termina. */
    private fun sigueVigente(venueId: String): Boolean {
        val vigente = secureStorage.venueId
        if (vigente == venueId) return true
        Log.i(TAG, "🏬 La sucursal cambió ($venueId → $vigente): sus entregas quedan suspendidas hasta que vuelva")
        return false
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
        /** N2: un minuto, como `ReplayDeComandasPendientes` — un intento TCP contra la impresora de la LAN es barato. */
        const val INTERVALO_MS = 60_000L
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
