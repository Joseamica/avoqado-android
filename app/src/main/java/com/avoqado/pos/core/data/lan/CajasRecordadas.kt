package com.avoqado.pos.core.data.lan

import android.util.Log
import com.avoqado.pos.core.data.local.PayloadCache
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * D12 · tubería de la Opción D (hz:e5ee98bc:D4). En el ensayo del 11-nov NSD perdió a la N86 entre las dos bandas del WiFi («43D» y
 * «43D-5G») y nunca la volvió a ver, aunque seguía anunciándose y contestaba por TCP en su dirección: D caía en «no la encuentro».
 *
 * - Cada caja que NSD resuelve en MI sucursal se recuerda en disco (`did → host, port, venue, vistoEn`). Nunca este aparato.
 * - La que NSD pierde (o que no ve al arrancar) sigue en la lista mientras conteste [LeaseProtocol.OP_QUIEN] en esa dirección con el
 *   MISMO `did` y el MISMO `venue`; su TXT sale de esa respuesta (así `d`, `caja` y `kds` siguen al día).
 * - Dos fallas seguidas, otra identidad (el DHCP le dio esa IP a otro aparato u otro negocio) o una app vieja (error) ⇒ fuera. Al
 *   perderla NSD se pregunta dos veces seguidas (una caja que cerró su app sale al instante, como hoy); después, una vez por revisión.
 * - 24 h sin contestar ⇒ se olvida. Una caja que NSD nunca vio aquí no entra: no hay dirección que probar.
 *
 * Una instancia por encendido de la red de [TransporteLan] (como su `LanDiscovery`), que la usa en su revisión y al perder un peer,
 * siempre en su propio scope: nunca en quien llama. Abajo de Android 14 tampoco toca NSD: es TCP puro.
 */
class CajasRecordadas(
    private val cache: PayloadCache,
    private val venueId: String,
    private val yo: String,
    private val preguntarQuien: suspend (LanPeer) -> LeaseResponse? = { LeaseClient().quien(it) },
    private val ahora: () -> Long = System::currentTimeMillis,
) {
    @Serializable
    data class Recordada(val host: String, val port: Int, val venue: String, val vistoEn: Long)

    @Serializable
    data class Disco(val cajas: Map<String, Recordada> = emptyMap())

    private val mutex = Mutex()

    /** Bajo [mutex]. null = todavía no se lee el disco. */
    private var recordadas: MutableMap<String, Recordada>? = null
    private var guardadas: Map<String, Recordada> = emptyMap()
    private val fallas = HashMap<String, Int>()

    /** Las que hoy están en la lista por TCP. Las lee el transporte desde cualquier hilo. */
    private val enLista = ConcurrentHashMap<String, LanPeer>()

    /** Lo que se publica además de lo que ve NSD (lo de NSD manda). */
    fun porTcp(ajenos: List<LanPeer>): List<LanPeer> = enLista.values.filter { p -> ajenos.none { it.deviceId == p.deviceId } }

    /** NSD dejó de verlas: siguen en la lista (con lo último que NSD dijo) hasta que [confirmarPerdidas] diga. No espera nada. */
    fun sostener(perdidas: List<LanPeer>) {
        for (p in perdidas) if (cuenta(p)) enLista[p.deviceId] = p
    }

    /** Al perderlas NSD: hasta dos `quien` seguidos (≤ 1.5 s cada uno), en paralelo entre cajas. */
    suspend fun confirmarPerdidas(perdidas: List<LanPeer>) = mutex.withLock {
        val r = cargar()
        val resultados = coroutineScope {
            perdidas.filter(::cuenta).map { p ->
                async {
                    val primero = sondear(p.deviceId, p.host, p.port)
                    p.deviceId to if (primero == Sondeo.NoContesta) listOf(primero, sondear(p.deviceId, p.host, p.port)) else listOf(primero)
                }
            }.awaitAll()
        }
        for ((did, sondeos) in resultados) {
            fallas.remove(did)
            sondeos.forEach { aplicar(r, did, it) }
        }
        guardar(r)
    }

    /** Lo que NSD ve: recordado y con su fecha al día. */
    suspend fun recordar(vistas: List<LanPeer>) = mutex.withLock {
        val r = cargar()
        anotarVistas(r, vistas)
        guardar(r)
    }

    /** La revisión (≤ 1 min): recuerda lo que NSD ve y pregunta una vez, en paralelo, a las recordadas que NSD no ve. */
    suspend fun revisar(vistas: List<LanPeer>) = mutex.withLock {
        val r = cargar()
        anotarVistas(r, vistas)
        val t = ahora()
        r.entries.removeAll { (did, c) -> (t - c.vistoEn >= OLVIDAR_MS).also { if (it) { enLista.remove(did); fallas.remove(did) } } }
        val candidatas = r.filterKeys { did -> vistas.none { it.deviceId == did } }
        val resultados = coroutineScope {
            candidatas.map { (did, c) -> async { did to sondear(did, c.host, c.port) } }.awaitAll()
        }
        for ((did, s) in resultados) aplicar(r, did, s)
        guardar(r)
    }

    private fun cuenta(p: LanPeer) = p.venueExplicito && p.deviceId.isNotEmpty() && p.deviceId != yo

    /**
     * Sólo el disco: lo que NSD ve ya lo filtra [porTcp], y [vistas] puede ser una foto vieja (NSD la pudo perder un instante después y
     * [sostener] ya la puso en la lista: quitarla aquí la tiraría hasta la siguiente revisión).
     */
    private fun anotarVistas(r: MutableMap<String, Recordada>, vistas: List<LanPeer>) {
        val t = ahora()
        for (p in vistas) if (cuenta(p)) r[p.deviceId] = Recordada(p.host, p.port, venueId, t)
    }

    private sealed interface Sondeo {
        data class Es(val peer: LanPeer) : Sondeo
        data object Otra : Sondeo
        data object NoContesta : Sondeo
    }

    private suspend fun sondear(did: String, host: String, port: Int): Sondeo {
        val r = preguntarQuien(LanPeer(did, host, port)) ?: return Sondeo.NoContesta
        val txt = r.txt
        // App vieja («Operación desconocida»), otro aparato u otro negocio en esa dirección: no cuenta por TCP.
        if (r.status != LeaseProtocol.STATUS_OK || txt == null) return Sondeo.Otra
        if (txt[LeaseProtocol.TXT_DEVICE_ID] != did || txt[LeaseProtocol.TXT_VENUE_ID] != venueId) return Sondeo.Otra
        return LanTxt.peerDesde(txt, host, port, yo, venueId)?.let { Sondeo.Es(it) } ?: Sondeo.Otra
    }

    private fun aplicar(r: MutableMap<String, Recordada>, did: String, s: Sondeo) {
        when (s) {
            is Sondeo.Es -> {
                enLista[did] = s.peer
                fallas.remove(did)
                r[did] = Recordada(s.peer.host, s.peer.port, venueId, ahora())
            }
            Sondeo.Otra -> {
                if (enLista.remove(did) != null) Log.i(TAG, "👋 ${did.take(6)}: en su dirección contesta otro aparato o una app vieja")
                fallas.remove(did)
                r.remove(did)
            }
            Sondeo.NoContesta -> {
                val n = (fallas[did] ?: 0) + 1
                fallas[did] = n
                if (n >= FALLAS_PARA_SALIR && enLista.remove(did) != null) Log.i(TAG, "👋 ${did.take(6)}: no contestó por TCP $n veces seguidas")
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Un disco ilegible o que falla cuenta como vacío: sin dirección recordada sólo se pierde el atajo, nunca dinero. */
    private suspend fun cargar(): MutableMap<String, Recordada> {
        recordadas?.let { return it }
        val leidas = runCatching {
            cache.loadDurable(PayloadCache.TYPE_CAJAS_RECORDADAS, venueId)?.json?.let { json.decodeFromString(Disco.serializer(), it).cajas }
        }.onFailure { Log.w(TAG, "⚠️ no se pudieron leer las cajas recordadas: ${it.message}") }.getOrNull().orEmpty()
        val r = leidas.filter { (did, c) -> did != yo && c.venue == venueId }.toMutableMap()
        guardadas = leidas
        recordadas = r
        return r
    }

    private suspend fun guardar(r: Map<String, Recordada>) {
        if (r == guardadas) return
        runCatching { cache.saveDurable(PayloadCache.TYPE_CAJAS_RECORDADAS, venueId, json.encodeToString(Disco.serializer(), Disco(r.toMap()))) }
            .onSuccess { guardadas = r.toMap() }
            .onFailure { Log.w(TAG, "⚠️ no se pudieron guardar las cajas recordadas: ${it.message}") }
    }

    companion object {
        private const val TAG = "CajasRecordadas"

        /** Fallas seguidas de `quien` para que una caja salga de la lista. */
        const val FALLAS_PARA_SALIR = 2

        /** Sin contestar (ni por NSD ni por TCP) en este tiempo, una caja se olvida. */
        const val OLVIDAR_MS = 24 * 60 * 60 * 1000L
    }
}
