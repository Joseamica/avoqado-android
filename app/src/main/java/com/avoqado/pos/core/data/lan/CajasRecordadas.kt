package com.avoqado.pos.core.data.lan

import android.util.Log
import com.avoqado.pos.core.data.local.PayloadCache
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
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
 * - Dos fallas seguidas, otra identidad (el DHCP le dio esa IP a otro aparato u otro negocio) o una app vieja (error) ⇒ fuera. A una
 *   en la lista que no contesta (al perderla NSD o en la revisión) se le pregunta otra vez enseguida: sale en segundos, como hoy.
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

    /** Bajo [mutex]. null = todavía no se lee el disco (o la última lectura falló: se vuelve a intentar). */
    private var recordadas: MutableMap<String, Recordada>? = null
    private var guardadas: Map<String, Recordada> = emptyMap()
    private val fallas = HashMap<String, Int>()

    /** Las que hoy están en la lista por TCP. Las lee el transporte desde cualquier hilo. */
    private val enLista = ConcurrentHashMap<String, LanPeer>()

    init {
        vigentes[venueId] = this // Codex r3 #3: el encendido nuevo retira al anterior de la misma sucursal (ya no escribe)
    }

    /** Lo que se publica además de lo que ve NSD (lo de NSD manda). */
    fun porTcp(ajenos: List<LanPeer>): List<LanPeer> = enLista.values.filter { p -> ajenos.none { it.deviceId == p.deviceId } }

    /** NSD dejó de verlas: siguen en la lista (con lo último que NSD dijo) hasta que [confirmarPerdidas] diga. No espera nada. */
    fun sostener(perdidas: List<LanPeer>) {
        for (p in perdidas) if (cuenta(p)) enLista[p.deviceId] = p
    }

    /**
     * Al perderlas NSD: hasta dos `quien` seguidos (≤ 1.5 s cada uno), en paralelo entre cajas. Ronda 1, Menor 3: el candado no se tiene
     * durante la red (dos pérdidas a la vez, o una pérdida y la revisión, no van en fila); sólo para aplicar lo que contestaron.
     */
    suspend fun confirmarPerdidas(perdidas: List<LanPeer>) {
        val porPreguntar = perdidas.filter(::cuenta)
        if (porPreguntar.isEmpty()) return
        val resultados = coroutineScope { porPreguntar.map { p -> async { Contestado(p, hastaDos(p.deviceId, p.host, p.port)) } }.awaitAll() }
        mutex.withLock {
            val r = cargar()
            for (c in resultados) {
                if (sigueEn(r, c.en)) fallas.remove(c.en.deviceId)
                c.sondeos.forEach { aplicar(r, c.en, it) }
            }
            guardar(r)
        }
    }

    /** Lo que NSD ve: recordado (sin red; si el disco no se pudo leer, no se escribe nada). */
    suspend fun recordar(vistas: List<LanPeer>) = mutex.withLock {
        val r = cargar() ?: return@withLock
        anotarVistas(r, vistas)
        guardar(r)
    }

    /**
     * La revisión (≤ 1 min): recuerda lo que NSD ve y pregunta, en paralelo y sin candado, a las que NSD no ve. Ronda 1, Menor 4: a una
     * que está en la lista y no contesta se le pregunta otra vez enseguida; dos seguidas y sale en ESTA revisión (antes tardaba ~2 min y
     * las mesas de esa caja trabajaban en isla mientras tanto). A una recordada que no está en la lista, una sola pregunta.
     */
    suspend fun revisar(vistas: List<LanPeer>) {
        val foto = mutex.withLock {
            val r = cargar()
            if (r != null) {
                anotarVistas(r, vistas)
                val t = ahora()
                r.entries.removeAll { (did, c) -> (t - c.vistoEn >= OLVIDAR_MS).also { if (it) { enLista.remove(did); fallas.remove(did) } } }
                guardar(r)
            }
            // La dirección de la lista es la más nueva (lo último que dijo NSD o el `quien`); si no, la del disco.
            val direcciones = LinkedHashMap<String, Pair<LanPeer, Boolean>>()
            enLista.forEach { (did, p) -> direcciones[did] = p to true }
            r?.forEach { (did, c) -> direcciones.putIfAbsent(did, LanPeer(did, c.host, c.port) to false) }
            direcciones.filterKeys { did -> vistas.none { it.deviceId == did } }
        }
        if (foto.isEmpty()) return
        val resultados = coroutineScope {
            foto.map { (did, d) ->
                val (p, sostenida) = d
                async { Contestado(p, if (sostenida) hastaDos(did, p.host, p.port) else listOf(sondear(did, p.host, p.port))) }
            }.awaitAll()
        }
        mutex.withLock {
            val r = cargar()
            for (c in resultados) c.sondeos.forEach { aplicar(r, c.en, it) }
            guardar(r)
        }
    }

    private fun cuenta(p: LanPeer) = p.venueExplicito && p.deviceId.isNotEmpty() && p.deviceId != yo

    /**
     * Sólo el disco: lo que NSD ve ya lo filtra [porTcp], y [vistas] puede ser una foto vieja (NSD la pudo perder un instante después y
     * [sostener] ya la puso en la lista: quitarla aquí la tiraría hasta la siguiente revisión).
     */
    private fun anotarVistas(r: MutableMap<String, Recordada>, vistas: List<LanPeer>) {
        for (p in vistas) if (cuenta(p)) fechar(r, p.deviceId, p.host, p.port)
    }

    /** Ronda 1, Menor 5a: se vuelve a escribir sólo si cambió la dirección o la fecha guardada tiene más de [REFECHAR_MS]. */
    private fun fechar(r: MutableMap<String, Recordada>, did: String, host: String, port: Int) {
        val t = ahora()
        val antes = r[did]
        if (antes == null || antes.host != host || antes.port != port || t - antes.vistoEn > REFECHAR_MS) r[did] = Recordada(host, port, venueId, t)
    }

    private sealed interface Sondeo {
        data class Es(val peer: LanPeer) : Sondeo
        data object Otra : Sondeo
        data object NoContesta : Sondeo
    }

    /** Dos `quien` seguidos si el primero no contesta (una respuesta, aunque sea de otro, no se repite: no es la red). */
    private suspend fun hastaDos(did: String, host: String, port: Int): List<Sondeo> {
        val primero = sondear(did, host, port)
        return if (primero == Sondeo.NoContesta) listOf(primero, sondear(did, host, port)) else listOf(primero)
    }

    private suspend fun sondear(did: String, host: String, port: Int): Sondeo {
        val r = preguntarQuien(LanPeer(did, host, port)) ?: return Sondeo.NoContesta
        val txt = r.txt
        // App vieja («Operación desconocida»), otro aparato u otro negocio en esa dirección: no cuenta por TCP.
        if (r.status != LeaseProtocol.STATUS_OK || txt == null) return Sondeo.Otra
        if (txt[LeaseProtocol.TXT_DEVICE_ID] != did || txt[LeaseProtocol.TXT_VENUE_ID] != venueId) return Sondeo.Otra
        return LanTxt.peerDesde(txt, host, port, yo, venueId)?.let { Sondeo.Es(it) } ?: Sondeo.Otra
    }

    /** Lo que contestó la dirección [en] (su `did`, `host` y `port` son los que se preguntaron). */
    private class Contestado(val en: LanPeer, val sondeos: List<Sondeo>)

    /**
     * Ronda 2: ¿la caja sigue en la dirección que se preguntó? La red va sin candado: mientras tanto un [sostener] o un [recordar] pudo
     * traer otra (el DHCP le cambió la IP y otro aparato tomó la vieja). Lo que no esté, no protege nada.
     */
    private fun sigueEn(r: Map<String, Recordada>?, en: LanPeer): Boolean {
        val lista = enLista[en.deviceId]
        val disco = r?.get(en.deviceId)
        return (lista == null || (lista.host == en.host && lista.port == en.port)) && (disco == null || (disco.host == en.host && disco.port == en.port))
    }

    /**
     * [r] null = el disco no se pudo leer: la lista sí cambia (en memoria), el disco no. Un «es otro» o un «no contesta» de una dirección
     * que la caja ya dejó se descarta (ronda 2): borraría la nueva, y entre bandas NSD quizá nunca la vuelva a traer.
     */
    private fun aplicar(r: MutableMap<String, Recordada>?, en: LanPeer, s: Sondeo) {
        val did = en.deviceId
        if (s !is Sondeo.Es && !sigueEn(r, en)) return
        when (s) {
            is Sondeo.Es -> {
                enLista[did] = s.peer
                fallas.remove(did)
                r?.let { fechar(it, did, s.peer.host, s.peer.port) }
            }
            Sondeo.Otra -> {
                if (enLista.remove(did) != null) Log.i(TAG, "👋 ${did.take(6)}: en su dirección contesta otro aparato o una app vieja")
                fallas.remove(did)
                r?.remove(did)
            }
            Sondeo.NoContesta -> {
                val n = (fallas[did] ?: 0) + 1
                fallas[did] = n
                if (n >= FALLAS_PARA_SALIR && enLista.remove(did) != null) Log.i(TAG, "👋 ${did.take(6)}: no contestó por TCP $n veces seguidas")
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Ronda 1, Menor 5b: si LEER falla (Room no contesta), null: no se toma como vacío ni se escribe encima de lo guardado (se perderían
     * justo las cajas del otro lado de la banda); se vuelve a intentar la siguiente vez, como `CuentasEntreCajas.leer`. Un JSON ilegible
     * sí cuenta como vacío: sin dirección recordada sólo se pierde el atajo, nunca dinero.
     */
    private suspend fun cargar(): MutableMap<String, Recordada>? {
        recordadas?.let { return it }
        val guardado = try {
            cache.loadDurable(PayloadCache.TYPE_CAJAS_RECORDADAS, venueId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ no se pudieron leer las cajas recordadas (se reintenta): ${e.message}")
            return null
        }
        val leidas = guardado?.json?.let { runCatching { json.decodeFromString(Disco.serializer(), it).cajas }.getOrNull() }.orEmpty()
        val r = leidas.filter { (did, c) -> did != yo && c.venue == venueId }.toMutableMap()
        guardadas = leidas
        recordadas = r
        return r
    }

    /**
     * Codex r3 #3: sólo escribe la instancia VIGENTE (la del último encendido de esta sucursal), y nunca a la vez que otra. Una tarea de un
     * encendido anterior que termina tarde (un `quien` retenido) reescribía la fila entera encima de lo que la nueva ya guardó.
     */
    private suspend fun guardar(r: Map<String, Recordada>?) {
        if (r == null || r == guardadas) return
        escritura.withLock {
            if (vigentes[venueId] !== this) {
                Log.i(TAG, "un encendido anterior ya no escribe las cajas recordadas")
            } else {
                runCatching { cache.saveDurable(PayloadCache.TYPE_CAJAS_RECORDADAS, venueId, json.encodeToString(Disco.serializer(), Disco(r.toMap()))) }
                    .onSuccess { guardadas = r.toMap() }
                    .onFailure { Log.w(TAG, "⚠️ no se pudieron guardar las cajas recordadas: ${it.message}") }
            }
        }
    }

    companion object {
        private const val TAG = "CajasRecordadas"

        /** Codex r3 #3: la instancia vigente de cada sucursal (la última que se creó: una por encendido de la red de [TransporteLan]). */
        private val vigentes = ConcurrentHashMap<String, CajasRecordadas>()

        /** ponytail: un candado para toda escritura de cualquier sucursal (una sola red local por aparato); por sucursal si algún día hay más. */
        private val escritura = Mutex()

        /** Fallas seguidas de `quien` para que una caja salga de la lista. */
        const val FALLAS_PARA_SALIR = 2

        /** Sin contestar (ni por NSD ni por TCP) en este tiempo, una caja se olvida. */
        const val OLVIDAR_MS = 24 * 60 * 60 * 1000L

        /** Ronda 1, Menor 5a: la fecha de una caja que sigue en la misma dirección se vuelve a escribir a lo más cada hora. */
        const val REFECHAR_MS = 60 * 60 * 1000L
    }
}
