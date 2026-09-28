package com.avoqado.pos.core.data.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "LanDiscovery"

/**
 * Hub LAN, capa 2 — DESCUBRIMIENTO por mDNS/NSD.
 *
 * Cada POS se anuncia como `_avoqado-pos._tcp` y busca a los demás. De ahí sale
 * la lista de [LanPeer] con la que [ArbiterElection] decide quién arbitra, sin
 * que nadie configure IPs a mano.
 *
 * Desde la 3.5 (D1) es una pieza del transporte único: anuncia con el TXT que le dan y busca; el socket vive en
 * `TransporteLan`.
 *
 * Espejo EXACTO en avoqado-ios: Services/LAN/LanDiscovery.swift.
 *
 * ── Detalles que cuestan horas si no se saben ──────────────────────────────
 * 1. MULTICAST LOCK: muchos Android tiran los paquetes multicast para ahorrar
 *    batería cuando la pantalla se apaga. Sin el lock (permiso
 *    CHANGE_WIFI_MULTICAST_STATE, ya en el manifest) el descubrimiento
 *    "funciona en el escritorio y falla en el salón".
 * 2. RESOLVES EN SERIE: `resolveService` falla con FAILURE_ALREADY_ACTIVE si se
 *    llama otra vez antes de que termine el anterior. En un restaurante con 6
 *    tablets aparecen 6 servicios de golpe, así que se encolan.
 * 3. VENUE EN EL TXT: dos negocios vecinos pueden compartir WiFi (plazas,
 *    food courts). Un peer de OTRO venue se ignora — arbitrar mesas ajenas
 *    sería catastrófico y silencioso.
 * 4. NO se filtra el propio anuncio por nombre (el SO puede renombrarlo a
 *    "Avoqado-POS (2)" si hay colisión): se filtra por deviceId del TXT, que
 *    es lo único estable.
 */
class LanDiscovery(
    private val context: Context,
    private val deviceId: String,
    private val venueId: String,
    /** Los peers AJENOS vivos (este aparato lo agrega [TransporteLan]). */
    private val alCambiarPeers: (List<LanPeer>) -> Unit,
) {
    private val nsdManager: NsdManager? =
        runCatching { context.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var txtRegistrado: Map<String, String>? = null

    private var peers: List<LanPeer> = emptyList()
        set(value) { field = value; alCambiarPeers(value) }

    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)

    /**
     * Anuncia este POS con ese TXT. NSD no edita un registro vivo: si el TXT cambió (`kds=`, `hub=`) se da de baja y
     * se vuelve a registrar; los peers ven perdido → encontrado, con el MISMO puerto. Con el mismo TXT no hace nada.
     *
     * 🔴 Un fallo de registro NO es definitivo: `onRegistrationFailed` suelta el listener y reintenta con espera acotada
     * (1, 2, 4, 8, 16, 30 s: 6 intentos por ráfaga). Si la guarda de arriba lo dejara puesto, cada `reanunciar()` (el KDS lo
     * llama cada minuto) sería un no-op y la pantalla nunca volvería a anunciarse: todo «sólo pantalla» saldría en papel
     * sin causa aparente. Agotada la ráfaga, el siguiente `reanunciar()` vuelve a empezar desde cero.
     */
    fun anunciar(port: Int, txt: Map<String, String>) {
        val manager = nsdManager ?: run { Log.w(TAG, "NSD no disponible — sin anuncio"); return }
        if (registrationListener != null && txtRegistrado == txt) return
        dejarDeAnunciar()
        acquireMulticastLock()
        val info = NsdServiceInfo().apply {
            serviceName = "Avoqado-POS-${deviceId.take(6)}"
            serviceType = LeaseProtocol.SERVICE_TYPE
            this.port = port
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                intentosDeAnuncio = 0
                Log.i(TAG, "📡 Anunciado como ${info.serviceName} en el puerto $port txt=$txt")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                // Mismo estado que el `onFailure` de `registerService`: sin esto la guarda de `anunciar` lo dejaba mudo para siempre.
                registrationListener = null
                txtRegistrado = null
                programarReintentoDeAnuncio(port, txt, errorCode)
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        txtRegistrado = txt
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.e(TAG, "registerService falló: ${it.message}"); registrationListener = null; txtRegistrado = null }
    }

    private var intentosDeAnuncio = 0
    private var reintentoDeAnuncio: Runnable? = null
    /** `lazy`: en la JVM de las pruebas `nsdManager` es null y este camino nunca corre — no se toca `Looper` en vano. */
    private val reloj by lazy { Handler(Looper.getMainLooper()) }

    private fun programarReintentoDeAnuncio(port: Int, txt: Map<String, String>, errorCode: Int) {
        val espera = esperaDeReintentoDeAnuncio(intentosDeAnuncio)
        if (espera == null) {
            Log.e(TAG, "❌ No se pudo anunciar (código $errorCode) tras $intentosDeAnuncio intentos — modo isla hasta el siguiente reanunciar()")
            intentosDeAnuncio = 0
            return
        }
        intentosDeAnuncio++
        Log.w(TAG, "⚠️ No se pudo anunciar (código $errorCode) — reintento $intentosDeAnuncio en ${espera / 1_000} s")
        reintentoDeAnuncio?.let { reloj.removeCallbacks(it) }
        val r = Runnable { reintentoDeAnuncio = null; anunciar(port, txt) }
        reintentoDeAnuncio = r
        reloj.postDelayed(r, espera)
    }

    fun dejarDeAnunciar() {
        reintentoDeAnuncio?.let { reloj.removeCallbacks(it); reintentoDeAnuncio = null }
        registrationListener?.let { runCatching { nsdManager?.unregisterService(it) } }
        registrationListener = null
        txtRegistrado = null
    }

    /** Empieza a buscar a los demás. Idempotente. */
    fun buscar() {
        val manager = nsdManager ?: run { Log.w(TAG, "NSD no disponible — sin descubrimiento"); return }
        if (discoveryListener != null) return
        acquireMulticastLock()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { Log.d(TAG, "🔎 Buscando POS en la red local") }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType?.contains("avoqado-pos") != true) return
                resolveQueue.add(info)
                drainResolveQueue()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                // Se cae del plano por NOMBRE porque el TXT ya no viaja aquí (`contains`: el SO puede renombrar a «(2)»).
                val name = info.serviceName ?: return
                peers = peers.filterNot { it.deviceId.isNotEmpty() && name.contains(it.deviceId.take(6)) }
                Log.d(TAG, "👋 Peer perdido: $name")
            }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) { Log.e(TAG, "❌ Descubrimiento falló ($errorCode) — modo isla") }
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
        }
        discoveryListener = listener
        runCatching { manager.discoverServices(LeaseProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.e(TAG, "discoverServices falló: ${it.message}"); discoveryListener = null }
    }

    /** Baja el anuncio, para de buscar y suelta el lock. */
    fun parar() {
        dejarDeAnunciar()
        intentosDeAnuncio = 0
        discoveryListener?.let { runCatching { nsdManager?.stopServiceDiscovery(it) } }
        discoveryListener = null
        releaseMulticastLock()
        peers = emptyList()
    }

    /**
     * Resuelve de a UNO: resolveService revienta con FAILURE_ALREADY_ACTIVE si hay otro en curso, y en un restaurante
     * llegan varios servicios de golpe.
     */
    private fun drainResolveQueue() {
        if (!resolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll()
        if (next == null) {
            resolving.set(false)
            return
        }
        val manager = nsdManager ?: run { resolving.set(false); return }

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.d(TAG, "resolve falló para ${info.serviceName} ($errorCode)")
                resolving.set(false)
                drainResolveQueue()
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                onPeerResolved(info)
                resolving.set(false)
                drainResolveQueue()
            }
        }
        runCatching { manager.resolveService(next, listener) }
            .onFailure {
                resolving.set(false)
                drainResolveQueue()
            }
    }

    private fun onPeerResolved(info: NsdServiceInfo) {
        val txt = info.attributes.orEmpty().mapValues { (_, v) -> v?.let { String(it) } }
        // El TXT se lee PURO (`LanTxt`): otro venue, este mismo aparato o sin host ⇒ null.
        val peer = LanTxt.peerDesde(txt, info.host?.hostAddress, info.port, deviceId, venueId) ?: return
        peers = peers.filterNot { it.deviceId == peer.deviceId } + peer
        Log.i(TAG, "🤝 Peer: ${peer.deviceId.take(6)} en ${peer.host}:${peer.port} kds=${peer.kdsStations} hub=${peer.sirveLeases}")
    }

    /**
     * Sin esto, muchos Android tiran los paquetes multicast al apagarse la
     * pantalla y el descubrimiento falla justo en producción.
     */
    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("avoqado-lan-hub")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        }.onFailure { Log.w(TAG, "MulticastLock no disponible: ${it.message}") }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    companion object {
        const val MAX_INTENTOS_DE_ANUNCIO = 6
        const val ESPERA_MAXIMA_DE_ANUNCIO_MS = 30_000L

        /** PURA: 1, 2, 4, 8, 16, 30 s para los intentos 0..5; `null` = ráfaga agotada (se espera al siguiente `reanunciar()`). */
        fun esperaDeReintentoDeAnuncio(intento: Int): Long? =
            if (intento >= MAX_INTENTOS_DE_ANUNCIO) null else minOf(ESPERA_MAXIMA_DE_ANUNCIO_MS, 1_000L shl intento)
    }
}
