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
 *
 * Familia B (Codex r4 #3): el disco es la ÚNICA verdad de la sucursal en el proceso. Toda mutación toma el candado común, lee el disco,
 * aplica sólo su cambio y guarda ([conElDisco]); nada se escribe desde un mapa guardado en memoria. Sólo escribe el encendido vigente.
 *
 * Frescura (Codex r5 #1): cada entrada, en disco ([Recordada.vistoEn]) y en la lista ([EnLista.en]), lleva la hora en que se OBSERVÓ esa
 * dirección (la foto de NSD, la respuesta de `quien`), no la hora en que se escribe. Toda escritura aplica sólo si su observación no es
 * más vieja que lo guardado de esa caja: un éxito que esperó a otra respuesta, o una foto que esperó a confirmar las perdidas, ya no
 * reponen una dirección vieja.
 */
class CajasRecordadas(
    private val cache: PayloadCache,
    private val venueId: String,
    private val yo: String,
    private val preguntarQuien: suspend (LanPeer) -> LeaseResponse? = { LeaseClient().quien(it) },
    /** Reloj de pared (lo que se guarda sobrevive al reinicio; frescura y «olvidar a las 24 h»). */
    private val ahora: () -> Long = System::currentTimeMillis,
) {
    /** [vistoEn]: cuándo se OBSERVÓ esta dirección (frescura), no cuándo se escribió. */
    @Serializable
    data class Recordada(val host: String, val port: Int, val venue: String, val vistoEn: Long)

    /** Una caja en la lista por TCP y cuándo se observó ([en], frescura). */
    private data class EnLista(val peer: LanPeer, val en: Long)

    @Serializable
    data class Disco(val cajas: Map<String, Recordada> = emptyMap())

    /** Bajo [candado]. */
    private val fallas = HashMap<String, Int>()

    /** Las que hoy están en la lista por TCP. Las lee el transporte desde cualquier hilo. */
    private val enLista = ConcurrentHashMap<String, EnLista>()

    init {
        vigentes[venueId] = this // Codex r3 #3: el encendido nuevo retira al anterior de la misma sucursal (ya no escribe)
    }

    /** Lo que se publica además de lo que ve NSD (lo de NSD manda). */
    fun porTcp(ajenos: List<LanPeer>): List<LanPeer> = enLista.values.map { it.peer }.filter { p -> ajenos.none { it.deviceId == p.deviceId } }

    /**
     * NSD dejó de verlas: siguen en la lista (con lo último que NSD dijo, observado en [en]) hasta que [confirmarPerdidas] diga. No espera
     * nada.
     */
    fun sostener(perdidas: List<LanPeer>, en: Long = ahora()) {
        for (p in perdidas) if (cuenta(p)) enLista.merge(p.deviceId, EnLista(p, en)) { viejo, nuevo -> if (esMasVieja(nuevo.en, viejo.en)) viejo else nuevo }
    }

    /**
     * Frescura (Codex r5 #1, ingreso de `TransporteLan.alCambiarAjenos`): NSD cambió. Las perdidas se sostienen YA y la foto [lista] queda
     * fechada AHORA, antes de esperar a confirmar las perdidas: si mientras tanto otra foto más nueva se guarda, ésta ya no la pisa. Regresa
     * el trabajo que el transporte corre en su scope.
     */
    fun alCambiarNsd(perdidas: List<LanPeer>, lista: List<LanPeer>): suspend () -> Unit {
        val en = ahora()
        sostener(perdidas, en)
        return { if (perdidas.isNotEmpty()) confirmarPerdidas(perdidas); recordar(lista, en) }
    }

    /**
     * Al perderlas NSD: hasta dos `quien` seguidos (≤ 1.5 s cada uno), en paralelo entre cajas. Ronda 1, Menor 3: el candado no se tiene
     * durante la red (dos pérdidas a la vez, o una pérdida y la revisión, no van en fila); sólo para aplicar lo que contestaron.
     */
    suspend fun confirmarPerdidas(perdidas: List<LanPeer>) {
        val porPreguntar = perdidas.filter(::cuenta)
        if (porPreguntar.isEmpty()) return
        val resultados = coroutineScope { porPreguntar.map { p -> async { Contestado(p, hastaDos(p.deviceId, p.host, p.port)) } }.awaitAll() }
        conElDisco { r ->
            for (c in resultados) {
                if (sigueEn(r, c.en)) fallas.remove(c.en.deviceId)
                c.sondeos.forEach { aplicar(r, c.en, it) }
            }
        }
    }

    /** Lo que NSD ve, observado en [en]: recordado (sin red; si el disco no se pudo leer, no se escribe nada). */
    suspend fun recordar(vistas: List<LanPeer>, en: Long = ahora()) = conElDisco { r -> r?.let { anotarVistas(it, vistas, en) } }

    /**
     * La revisión (≤ 1 min): recuerda lo que NSD ve y pregunta, en paralelo y sin candado, a las que NSD no ve. Ronda 1, Menor 4: a una
     * que está en la lista y no contesta se le pregunta otra vez enseguida; dos seguidas y sale en ESTA revisión (antes tardaba ~2 min y
     * las mesas de esa caja trabajaban en isla mientras tanto). A una recordada que no está en la lista, una sola pregunta.
     */
    suspend fun revisar(vistas: List<LanPeer>) {
        val fotoEn = ahora() // la foto de NSD se tomó al llamar, antes de esperar el candado
        val foto = conElDisco { r ->
            if (r != null) {
                anotarVistas(r, vistas, fotoEn)
                val t = ahora()
                r.entries.removeAll { (did, c) -> (t - c.vistoEn >= OLVIDAR_MS).also { if (it) { enLista.remove(did); fallas.remove(did) } } }
            }
            // La dirección de la lista es la más nueva (lo último que dijo NSD o el `quien`); si no, la del disco.
            val direcciones = LinkedHashMap<String, Pair<LanPeer, Boolean>>()
            enLista.forEach { (did, e) -> direcciones[did] = e.peer to true }
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
        conElDisco { r -> for (c in resultados) c.sondeos.forEach { aplicar(r, c.en, it) } }
    }

    private fun cuenta(p: LanPeer) = p.venueExplicito && p.deviceId.isNotEmpty() && p.deviceId != yo

    /**
     * Sólo el disco: lo que NSD ve ya lo filtra [porTcp], y [vistas] puede ser una foto vieja (NSD la pudo perder un instante después y
     * [sostener] ya la puso en la lista: quitarla aquí la tiraría hasta la siguiente revisión).
     */
    private fun anotarVistas(r: MutableMap<String, Recordada>, vistas: List<LanPeer>, en: Long) {
        for (p in vistas) if (cuenta(p)) fechar(r, p.deviceId, p.host, p.port, en)
    }

    /**
     * Ronda 1, Menor 5a: se vuelve a escribir sólo si cambió la dirección o la fecha guardada tiene más de [REFECHAR_MS]. Frescura: una
     * observación más vieja que la guardada no escribe nada.
     */
    private fun fechar(r: MutableMap<String, Recordada>, did: String, host: String, port: Int, en: Long) {
        val antes = r[did]
        if (antes != null && esMasVieja(en, antes.vistoEn)) return
        if (antes == null || antes.host != host || antes.port != port || en - antes.vistoEn > REFECHAR_MS) r[did] = Recordada(host, port, venueId, en)
    }

    /**
     * Frescura: [en] es más vieja que [guardada]. Una hora guardada en el futuro (el reloj del aparato se atrasó después) no frena nada: si
     * no, ninguna observación nueva aplicaría hasta que el reloj la alcance.
     */
    private fun esMasVieja(en: Long, guardada: Long): Boolean = en < guardada && guardada <= ahora()

    /** La observación más nueva que se tiene de esa caja, en la lista o en el disco. */
    private fun masNueva(did: String, r: Map<String, Recordada>?): Long =
        maxOf(enLista[did]?.en ?: Long.MIN_VALUE, r?.get(did)?.vistoEn ?: Long.MIN_VALUE)

    /** Lo que contestó una dirección y cuándo ([en]: al llegar la respuesta, o al agotarse su plazo). */
    private sealed interface Sondeo {
        val en: Long
        data class Es(val peer: LanPeer, override val en: Long) : Sondeo
        data class Otra(override val en: Long) : Sondeo
        data class NoContesta(override val en: Long) : Sondeo
    }

    /** Dos `quien` seguidos si el primero no contesta (una respuesta, aunque sea de otro, no se repite: no es la red). */
    private suspend fun hastaDos(did: String, host: String, port: Int): List<Sondeo> {
        val primero = sondear(did, host, port)
        return if (primero is Sondeo.NoContesta) listOf(primero, sondear(did, host, port)) else listOf(primero)
    }

    private suspend fun sondear(did: String, host: String, port: Int): Sondeo {
        val r = preguntarQuien(LanPeer(did, host, port))
        val en = ahora()
        if (r == null) return Sondeo.NoContesta(en)
        val txt = r.txt
        // App vieja («Operación desconocida»), otro aparato u otro negocio en esa dirección: no cuenta por TCP.
        if (r.status != LeaseProtocol.STATUS_OK || txt == null) return Sondeo.Otra(en)
        if (txt[LeaseProtocol.TXT_DEVICE_ID] != did || txt[LeaseProtocol.TXT_VENUE_ID] != venueId) return Sondeo.Otra(en)
        return LanTxt.peerDesde(txt, host, port, yo, venueId)?.let { Sondeo.Es(it, en) } ?: Sondeo.Otra(en)
    }

    /** Lo que contestó la dirección [en] (su `did`, `host` y `port` son los que se preguntaron). */
    private class Contestado(val en: LanPeer, val sondeos: List<Sondeo>)

    /**
     * Ronda 2: ¿la caja sigue en la dirección que se preguntó? La red va sin candado: mientras tanto un [sostener] o un [recordar] pudo
     * traer otra (el DHCP le cambió la IP y otro aparato tomó la vieja). Lo que no esté, no protege nada.
     */
    private fun sigueEn(r: Map<String, Recordada>?, en: LanPeer): Boolean {
        val lista = enLista[en.deviceId]?.peer
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
        // Frescura (Codex r5 #1): lo que se observó ANTES que lo que ya se sabe de esa caja (una foto de NSD o un `quien` más nuevos) no
        // aplica: un éxito que esperó a otra respuesta ya no repone una dirección vieja.
        if (esMasVieja(s.en, masNueva(did, r))) return
        when (s) {
            is Sondeo.Es -> {
                enLista[did] = EnLista(s.peer, s.en)
                fallas.remove(did)
                r?.let { fechar(it, did, s.peer.host, s.peer.port, s.en) }
            }
            is Sondeo.Otra -> {
                if (enLista.remove(did) != null) Log.i(TAG, "👋 ${did.take(6)}: en su dirección contesta otro aparato o una app vieja")
                fallas.remove(did)
                r?.remove(did)
            }
            is Sondeo.NoContesta -> {
                val n = (fallas[did] ?: 0) + 1
                fallas[did] = n
                if (n >= FALLAS_PARA_SALIR && enLista.remove(did) != null) Log.i(TAG, "👋 ${did.take(6)}: no contestó por TCP $n veces seguidas")
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Familia B (Codex r4 #3): TODA mutación del disco, con el candado común de todas las instancias: comprueba que este encendido sigue
     * vigente, lee el disco, aplica [cambio] y guarda si algo cambió. [cambio] recibe null si este encendido ya no es el vigente o si leer
     * falló (Ronda 1, Menor 5b: no se toma como vacío ni se escribe encima): la lista en memoria sí cambia, el disco no. Antes la instancia
     * guardaba el mapa que cargó al nacer: la nueva lo cargaba mientras la vieja terminaba de guardar otra caja, y su escritura la borraba.
     */
    private suspend fun <T> conElDisco(cambio: (MutableMap<String, Recordada>?) -> T): T = candado.withLock {
        val leidas = if (vigentes[venueId] === this) leer() else null
        val r = leidas?.filter { (did, c) -> did != yo && c.venue == venueId }?.toMutableMap()
        val t = cambio(r)
        // Ronda 1, Menor 5a: sólo si cambió algo (una dirección, una fecha de más de 1 h, una que se olvida o sale).
        if (r != null && r != leidas) {
            runCatching { cache.saveDurable(PayloadCache.TYPE_CAJAS_RECORDADAS, venueId, json.encodeToString(Disco.serializer(), Disco(r.toMap()))) }
                .onFailure { Log.w(TAG, "⚠️ no se pudieron guardar las cajas recordadas: ${it.message}") }
        }
        if (leidas == null && vigentes[venueId] !== this) Log.i(TAG, "un encendido anterior ya no escribe las cajas recordadas")
        t
    }

    /** Lo guardado (null = leer falló). Un JSON ilegible sí cuenta como vacío: sin dirección recordada sólo se pierde el atajo, nunca dinero. */
    private suspend fun leer(): Map<String, Recordada>? {
        val guardado = try {
            cache.loadDurable(PayloadCache.TYPE_CAJAS_RECORDADAS, venueId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ no se pudieron leer las cajas recordadas (se reintenta): ${e.message}")
            return null
        }
        return guardado?.json?.let { runCatching { json.decodeFromString(Disco.serializer(), it).cajas }.getOrNull() }.orEmpty()
    }

    companion object {
        private const val TAG = "CajasRecordadas"

        /** Codex r3 #3: la instancia vigente de cada sucursal (la última que se creó: una por encendido de la red de [TransporteLan]). */
        private val vigentes = ConcurrentHashMap<String, CajasRecordadas>()

        /**
         * Familia B: el candado común de toda lectura-cambio-escritura del disco, de todas las instancias (también de encendidos viejos).
         * ponytail: uno para todas las sucursales (una sola red local por aparato); por sucursal si algún día hay más.
         */
        private val candado = Mutex()

        /** Fallas seguidas de `quien` para que una caja salga de la lista. */
        const val FALLAS_PARA_SALIR = 2

        /** Sin contestar (ni por NSD ni por TCP) en este tiempo, una caja se olvida. */
        const val OLVIDAR_MS = 24 * 60 * 60 * 1000L

        /** Ronda 1, Menor 5a: la fecha de una caja que sigue en la misma dirección se vuelve a escribir a lo más cada hora. */
        const val REFECHAR_MS = 60 * 60 * 1000L
    }
}
