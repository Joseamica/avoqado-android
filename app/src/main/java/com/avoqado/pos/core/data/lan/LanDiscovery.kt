package com.avoqado.pos.core.data.lan

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

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
 * 5. UN TXT NUEVO NO SE AVISA (QA D1, 29-sep, Android 14): si un servicio ya visto vuelve a registrarse con el MISMO
 *    nombre y otro TXT (la pantalla entra al Tablero y agrega `kds=`), NSD no manda ni perdido ni encontrado. Por eso
 *    [refrescar] vuelve a resolver lo conocido: el transporte lo pide en cada revisión y la entrega sin pantalla, ya.
 * 6. UN RESOLVE PUEDE NO CONTESTAR NUNCA (un servicio que se fue sin despedirse): Android no le pone plazo, y antes de
 *    Android 14 ni se puede cancelar. [refrescar] suelta el que lleva más de [PLAZO_DE_RESOLVE_MS] para que la cola en
 *    serie no se trabe para siempre.
 */
class LanDiscovery(
    private val context: Context,
    private val deviceId: String,
    private val venueId: String,
    private val ahoraMs: () -> Long = { SystemClock.elapsedRealtime() },
    /** Los peers AJENOS vivos (este aparato lo agrega [TransporteLan]). */
    private val alCambiarPeers: (List<LanPeer>) -> Unit,
) {
    private val nsdManager: NsdManager? =
        runCatching { context.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull()

    private var multicastLock: WifiManager.MulticastLock? = null
    /** `@Volatile`: el fallo de un registro llega en el hilo de NSD; el anuncio se arma en IO (revisión I1). */
    @Volatile private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var txtRegistrado: Map<String, String>? = null

    /**
     * Revisión I1/I2: una instancia PARADA es terminal (el transporte crea otra al volver). Desde `parar()` ningún
     * callback tardío de NSD —un fallo de registro, un resolve en vuelo— re-anuncia, toma el multicast ni publica peers:
     * si no, quedaba un servicio fantasma con el venue viejo y el puerto cerrado, o un peer viejo en la elección.
     */
    @Volatile private var parado = false

    @Volatile private var peers: List<LanPeer> = emptyList()
        set(value) { field = value; if (!parado) alCambiarPeers(value) }

    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()

    /** Lo que NSD dijo que existe y no ha dicho que se perdió, por nombre: lo que [refrescar] vuelve a resolver. */
    private val conocidos = ConcurrentHashMap<String, NsdServiceInfo>()

    /** El resolve en curso (en serie: a lo más uno). Su respuesta sólo libera la cola si sigue siendo ÉSTE (ver [refrescar]). */
    private class Resolucion(val desdeMs: Long) { @Volatile var listener: NsdManager.ResolveListener? = null }
    private val enVuelo = AtomicReference<Resolucion?>(null)

    /**
     * Anuncia este POS con ese TXT. NSD no edita un registro vivo: si el TXT cambió (`kds=`, `hub=`) se da de baja y
     * se vuelve a registrar; los peers ven perdido → encontrado, con el MISMO puerto. Con el mismo TXT no hace nada.
     *
     * 🔴 Un fallo de registro NO es definitivo: `onRegistrationFailed` suelta el listener y reintenta con espera acotada
     * (1, 2, 4, 8, 16, 30 s: 6 intentos por ráfaga). Si la guarda de arriba lo dejara puesto, cada `anunciar` (el
     * transporte lo revisa cada minuto, `TransporteLan.REVISION_DE_RED_MS`) sería un no-op y la pantalla nunca volvería a
     * anunciarse: todo «sólo pantalla» saldría en papel sin causa aparente. Agotada la ráfaga, la siguiente revisión
     * vuelve a empezar desde cero.
     */
    @Synchronized
    fun anunciar(port: Int, txt: Map<String, String>) {
        if (parado) return
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
                synchronized(this@LanDiscovery) {
                    // Revisión I1: NSD entrega el fallo de un registro que YA se reemplazó (el `unregister` es asíncrono).
                    // Sin esto borraba al vigente —que ya no se daba de baja ni al parar— y re-anunciaba el TXT VIEJO:
                    // dos anuncios del mismo `did`. Uno que no es el vigente no toca nada.
                    if (registrationListener !== this) return
                    // Mismo estado que el `onFailure` de `registerService`: sin esto la guarda de `anunciar` lo dejaba mudo para siempre.
                    registrationListener = null
                    txtRegistrado = null
                    programarReintentoDeAnuncio(port, txt, errorCode)
                }
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

    @Synchronized
    private fun programarReintentoDeAnuncio(port: Int, txt: Map<String, String>, errorCode: Int) {
        if (parado) return
        val espera = esperaDeReintentoDeAnuncio(intentosDeAnuncio)
        if (espera == null) {
            Log.e(TAG, "❌ No se pudo anunciar (código $errorCode) tras $intentosDeAnuncio intentos — modo isla hasta la siguiente revisión del transporte (≤ 1 min)")
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

    @Synchronized
    fun dejarDeAnunciar() {
        reintentoDeAnuncio?.let { reloj.removeCallbacks(it); reintentoDeAnuncio = null }
        registrationListener?.let { runCatching { nsdManager?.unregisterService(it) } }
        registrationListener = null
        txtRegistrado = null
    }

    /** Empieza a buscar a los demás. Idempotente. */
    @Synchronized
    fun buscar() {
        if (parado) return
        val manager = nsdManager ?: run { Log.w(TAG, "NSD no disponible — sin descubrimiento"); return }
        if (discoveryListener != null) return
        acquireMulticastLock()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) { Log.d(TAG, "🔎 Buscando POS en la red local") }
            override fun onServiceFound(info: NsdServiceInfo) {
                if (parado || info.serviceType?.contains("avoqado-pos") != true) return
                conocidos[info.serviceName ?: return] = info
                resolveQueue.add(info)
                drainResolveQueue()
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                // Se cae del plano por NOMBRE porque el TXT ya no viaja aquí (`contains`: el SO puede renombrar a «(2)»).
                val name = info.serviceName ?: return
                conocidos.remove(name) // un re-resolve en vuelo o en cola ya no lo revive (ledger T4)
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

    /**
     * Baja el anuncio, para de buscar y suelta el lock. TERMINAL: la instancia no revive (el transporte crea otra).
     * `parado` va PRIMERO: desde aquí lo que llegue tarde de NSD se ignora, y lo encolado ya no se resuelve (revisión I2).
     * No avisa la lista vacía: el transporte limpia lo suyo al apagar la red.
     */
    @Synchronized
    fun parar() {
        parado = true
        resolveQueue.clear()
        conocidos.clear()
        dejarDeAnunciar()
        intentosDeAnuncio = 0
        discoveryListener?.let { runCatching { nsdManager?.stopServiceDiscovery(it) } }
        discoveryListener = null
        releaseMulticastLock()
        peers = emptyList()
    }

    /**
     * Vuelve a resolver lo conocido (detalles 5 y 6 del encabezado), por la MISMA cola en serie; lo que ya está en cola no
     * se repite. Antes suelta un resolve colgado más de [PLAZO_DE_RESOLVE_MS]. Devuelve si hay algo conocido: sin nada, no
     * hay a quién esperar.
     */
    fun refrescar(): Boolean {
        if (parado) return false
        enVuelo.get()?.let { r ->
            if (ahoraMs() - r.desdeMs > PLAZO_DE_RESOLVE_MS && enVuelo.compareAndSet(r, null)) {
                Log.w(TAG, "⏱️ Un resolve no contestó en ${PLAZO_DE_RESOLVE_MS / 1_000} s — se suelta y la cola sigue")
                // Android 14+ sí deja cancelarlo; antes, el siguiente puede fallar con FAILURE_ALREADY_ACTIVE hasta que conteste.
                if (Build.VERSION.SDK_INT >= 34) r.listener?.let { l -> runCatching { nsdManager?.stopServiceResolution(l) } }
            }
        }
        for ((nombre, info) in conocidos) if (resolveQueue.none { it.serviceName == nombre }) resolveQueue.add(info)
        drainResolveQueue()
        return conocidos.isNotEmpty()
    }

    /**
     * Resuelve de a UNO: resolveService revienta con FAILURE_ALREADY_ACTIVE si hay otro en curso, y en un restaurante
     * llegan varios servicios de golpe. Lo que se perdió mientras esperaba en la cola ya no se pide.
     */
    private fun drainResolveQueue() {
        if (parado) return
        val turno = Resolucion(ahoraMs())
        if (!enVuelo.compareAndSet(null, turno)) return
        var next = resolveQueue.poll()
        while (next != null && !conocidos.containsKey(next.serviceName)) next = resolveQueue.poll()
        if (next == null) {
            enVuelo.set(null)
            return
        }
        val manager = nsdManager ?: run { enVuelo.set(null); return }
        val nombre = next.serviceName
        // Sólo el turno VIGENTE libera la cola: uno ya soltado por [refrescar] que contesta tarde no arranca otro encima.
        fun terminar() { if (enVuelo.compareAndSet(turno, null)) drainResolveQueue() }

        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.d(TAG, "resolve falló para ${info.serviceName} ($errorCode)")
                terminar()
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                // Ledger T4: NSD dijo «perdido» mientras se resolvía ⇒ no revive. Los callbacks de NSD llegan en UN hilo.
                if (conocidos.containsKey(nombre)) onPeerResolved(info) // `containsKey`: el `in` de un ConcurrentHashMap mira VALORES
                terminar()
            }
        }
        turno.listener = listener
        runCatching { manager.resolveService(next, listener) }
            .onFailure { terminar() }
    }

    private fun onPeerResolved(info: NsdServiceInfo) {
        if (parado) return // un resolve que estaba en vuelo al parar (revisión I2)
        val txt = info.attributes.orEmpty().mapValues { (_, v) -> v?.let { String(it) } }
        // El TXT se lee PURO (`LanTxt`): otro venue, este mismo aparato o sin host ⇒ null.
        val peer = LanTxt.peerDesde(txt, info.host?.hostAddress, info.port, deviceId, venueId) ?: return
        val actuales = peers
        val i = actuales.indexOfFirst { it.deviceId == peer.deviceId }
        if (i >= 0 && actuales[i] == peer) return // re-resolve sin cambios (cada minuto, por peer): nadie se entera
        peers = if (i >= 0) actuales.toMutableList().apply { set(i, peer) } else actuales + peer
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
        /** Un resolve normal contesta en menos de un segundo; uno que lleva esto sin contestar ya no va a contestar. */
        const val PLAZO_DE_RESOLVE_MS = 10_000L
        const val MAX_INTENTOS_DE_ANUNCIO = 6
        const val ESPERA_MAXIMA_DE_ANUNCIO_MS = 30_000L

        /** PURA: 1, 2, 4, 8, 16, 30 s para los intentos 0..5; `null` = ráfaga agotada (se espera al siguiente `reanunciar()`). */
        fun esperaDeReintentoDeAnuncio(intento: Int): Long? =
            if (intento >= MAX_INTENTOS_DE_ANUNCIO) null else minOf(ESPERA_MAXIMA_DE_ANUNCIO_MS, 1_000L shl intento)
    }
}
