package com.avoqado.pos.core.data.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) : LanDiscoveryPort {
    private val nsdManager: NsdManager? =
        runCatching { context.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull()

    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
    /** Peers vivos vistos en la red (incluye a este dispositivo). */
    override val peers: StateFlow<List<LanPeer>> = _peers.asStateFlow()

    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)

    /** Anuncia este POS y empieza a buscar a los demás. */
    override fun start(myPort: Int, isWired: Boolean, bootedAtMillis: Long) {
        val manager = nsdManager ?: run {
            Log.w(TAG, "NSD no disponible — el hub queda en modo isla")
            return
        }
        acquireMulticastLock()

        // Este dispositivo siempre está en su propia lista: si es el único POS
        // encendido, tiene que poder elegirse árbitro a sí mismo.
        _peers.value = listOf(
            LanPeer(deviceId = deviceId, host = "127.0.0.1", port = myPort, isWired = isWired, bootedAtMillis = bootedAtMillis),
        )

        val info = NsdServiceInfo().apply {
            serviceName = "Avoqado-POS-${deviceId.take(6)}"
            serviceType = LeaseProtocol.SERVICE_TYPE
            port = myPort
            setAttribute(LeaseProtocol.TXT_DEVICE_ID, deviceId)
            setAttribute(LeaseProtocol.TXT_WIRED, if (isWired) "1" else "0")
            setAttribute(LeaseProtocol.TXT_BOOTED_AT, bootedAtMillis.toString())
            setAttribute(LeaseProtocol.TXT_VENUE_ID, venueId)
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "📡 Anunciado como ${info.serviceName} en el puerto $myPort")
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "❌ No se pudo anunciar (código $errorCode) — modo isla")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registrationListener) }
            .onFailure { Log.e(TAG, "registerService falló: ${it.message}") }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                Log.d(TAG, "🔎 Buscando POS en la red local")
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (info.serviceType?.contains("avoqado-pos") != true) return
                resolveQueue.add(info)
                drainResolveQueue()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                // Se cae del plano por NOMBRE porque el TXT ya no viaja aquí.
                val name = info.serviceName ?: return
                _peers.value = _peers.value.filterNot { it.deviceId.isNotEmpty() && name.endsWith(it.deviceId.take(6)) }
                Log.d(TAG, "👋 Peer perdido: $name")
            }
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                Log.e(TAG, "❌ Descubrimiento falló ($errorCode) — modo isla")
            }
            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {}
        }
        runCatching {
            manager.discoverServices(LeaseProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure { Log.e(TAG, "discoverServices falló: ${it.message}") }
    }

    override fun stop() {
        val manager = nsdManager
        registrationListener?.let { runCatching { manager?.unregisterService(it) } }
        discoveryListener?.let { runCatching { manager?.stopServiceDiscovery(it) } }
        registrationListener = null
        discoveryListener = null
        releaseMulticastLock()
        _peers.value = emptyList()
    }

    /**
     * Resuelve de a UNO: resolveService revienta con FAILURE_ALREADY_ACTIVE si
     * hay otro en curso, y en un restaurante llegan varios servicios de golpe.
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
        val attrs = info.attributes ?: emptyMap()
        fun txt(key: String): String? = attrs[key]?.let { String(it) }

        val peerDeviceId = txt(LeaseProtocol.TXT_DEVICE_ID) ?: return
        val peerVenue = txt(LeaseProtocol.TXT_VENUE_ID)

        // Plaza comercial / food court: el WiFi puede ser compartido. Arbitrar
        // las mesas de OTRO negocio sería catastrófico y silencioso.
        if (peerVenue != null && peerVenue != venueId) {
            Log.d(TAG, "🚫 Peer de otro venue ignorado ($peerVenue)")
            return
        }
        // El propio anuncio se filtra por deviceId, no por nombre: el SO puede
        // renombrar el servicio a "(2)" si hay colisión.
        if (peerDeviceId == deviceId) return

        val host = info.host?.hostAddress ?: return
        val peer = LanPeer(
            deviceId = peerDeviceId,
            host = host,
            port = info.port,
            isWired = txt(LeaseProtocol.TXT_WIRED) == "1",
            bootedAtMillis = txt(LeaseProtocol.TXT_BOOTED_AT)?.toLongOrNull() ?: 0L,
        )
        _peers.value = _peers.value.filterNot { it.deviceId == peerDeviceId } + peer
        Log.i(TAG, "🤝 Peer: ${peer.deviceId.take(6)} en ${peer.host}:${peer.port} (cableado=${peer.isWired})")
    }

    /**
     * Sin esto, muchos Android tiran los paquetes multicast al apagarse la
     * pantalla y el descubrimiento falla justo en producción.
     */
    private fun acquireMulticastLock() {
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
}
