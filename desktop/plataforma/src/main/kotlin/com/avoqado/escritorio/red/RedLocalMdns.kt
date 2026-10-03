package com.avoqado.escritorio.red

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/*
 * Hub LAN en escritorio: lo que en Android hace el NsdManager del sistema (anunciar este POS y encontrar a los demás por
 * mDNS), sobre jmDNS. Lo usa el sustituto android.net.nsd.NsdManager para `_avoqado-pos._tcp`; el resto del Hub LAN (socket,
 * elección, leases, KDS) es el código de Android tal cual.
 *
 * Una PC tiene varias tarjetas (WSL, Wi-Fi Direct, Tailscale, cable sin conectar…): se anuncia y busca SÓLO en la del local
 * (IPv4 privada de una tarjeta WiFi o de cable, cable primero). Si su dirección cambia, se vuelve a anunciar y a buscar sola.
 */

/** Lo mínimo de mDNS que hace falta; jmDNS de verdad o uno falso en las pruebas. Sus métodos pueden bloquear. */
interface MdnsPort {
    /** Registra y devuelve el nombre con que quedó (mDNS lo renombra si choca: «X (2)»). */
    fun registrar(tipo: String, nombre: String, puerto: Int, txt: Map<String, String>): String
    fun retirar(tipo: String, nombre: String)
    /** Escucha el tipo; devuelve con qué dejar de escuchar. Los avisos llegan en hilos de mDNS. */
    fun escuchar(tipo: String, alEncontrar: (String) -> Unit, alPerder: (String) -> Unit): () -> Unit
    /** Bloquea hasta [plazoMs]; null si no contestó o no trae IPv4 y puerto. */
    fun resolver(tipo: String, nombre: String, plazoMs: Long): Resuelto?
    fun cerrar()
}

data class Resuelto(val host: InetAddress, val puerto: Int, val txt: Map<String, String>)

class RedLocalMdns(
    private val direccionDelLocal: () -> InetAddress? = ::direccionDelLocalDeEsteEquipo,
    private val crearMdns: (InetAddress) -> MdnsPort = ::JmdnsPort,
    /** UN hilo para todo lo que se le avisa a la app: el NSD de Android entrega sus callbacks en un solo hilo. */
    private val avisos: Executor = hiloDaemon("red-local-avisos"),
    /** Registrar, escuchar y cambiar de tarjeta, en orden y fuera del hilo de quien llama. */
    private val trabajo: Executor = hiloDaemon("red-local-mdns"),
    /** Los resolves bloquean hasta 3 s: aparte, para no frenar a los demás. */
    private val resolviendo: Executor = Executors.newCachedThreadPool { r -> Thread(r, "red-local-resolve").apply { isDaemon = true } },
) {
    /*
     * 🔴 Todo el estado (anuncios, búsquedas, jmDNS) vive en el hilo [trabajo]: cada alta, baja o cambio de tarjeta se registra
     * y se aplica en el MISMO turno, en orden, y nadie más lo toca. Quien llama sólo encola: nunca espera a jmDNS (una baja
     * tarda hasta 5 s). Lo que se lee desde otros hilos (generación, activa, mdns) es @Volatile.
     */
    inner class Anuncio internal constructor(
        val tipo: String, val nombre: String, val puerto: Int, val txt: Map<String, String>,
        internal val alRegistrado: (String) -> Unit, internal val alFallar: (Throwable) -> Unit,
    ) { internal var nombreFinal: String? = null }   // hilo de trabajo

    inner class Busqueda internal constructor(
        val tipo: String, internal val alEncontrar: (String) -> Unit, internal val alPerder: (String) -> Unit,
    ) {
        internal var dejarDeEscuchar: (() -> Unit)? = null   // hilo de trabajo
        /** Lo encontrado en la tarjeta ACTUAL (hilo de avisos): al cambiar de red se avisa como perdido. */
        internal val encontrados = HashSet<String>()
        /** Baja EN EL ACTO al detener: un encontrado que ya venía en camino no llega después de «detenida». */
        @Volatile internal var activa = true
    }

    class Resolucion internal constructor() { @Volatile internal var cancelada = false }

    /** jmDNS y el número de red con que se abrió, en UN solo objeto: quien lo lee desde otro hilo los ve juntos. */
    private class Instancia(val mdns: MdnsPort, val generacion: Int)
    @Volatile private var instancia: Instancia? = null
    private val mdns: MdnsPort? get() = instancia?.mdns
    /** Sube al cambiar de tarjeta: lo que avise una instancia vieja ya no pasa. */
    @Volatile private var generacion = 0
    @Volatile var direccionActual: InetAddress? = null; private set
    private val anuncios = LinkedHashSet<Anuncio>()     // hilo de trabajo
    private val busquedas = LinkedHashSet<Busqueda>()   // hilo de trabajo

    fun anunciar(
        tipo: String, nombre: String, puerto: Int, txt: Map<String, String>,
        alRegistrado: (String) -> Unit, alFallar: (Throwable) -> Unit,
    ): Anuncio {
        val a = Anuncio(tipo, nombre, puerto, txt, alRegistrado, alFallar)
        trabajo.execute { anuncios += a; asegurarMdns()?.let(::aplicarPendientes) }
        return a
    }

    fun retirar(a: Anuncio) {
        trabajo.execute {
            if (!anuncios.remove(a)) return@execute
            a.nombreFinal?.let { nombre -> runCatching { mdns?.retirar(a.tipo, nombre) } }
            a.nombreFinal = null
            soltarSiNadieLoUsa()
        }
    }

    fun buscar(tipo: String, alEncontrar: (String) -> Unit, alPerder: (String) -> Unit): Busqueda {
        val b = Busqueda(tipo, alEncontrar, alPerder)
        trabajo.execute { busquedas += b; asegurarMdns()?.let(::aplicarPendientes) }
        return b
    }

    fun detener(b: Busqueda) {
        b.activa = false
        trabajo.execute {
            if (!busquedas.remove(b)) return@execute
            b.dejarDeEscuchar?.let { runCatching { it() } }
            b.dejarDeEscuchar = null
            soltarSiNadieLoUsa()
        }
    }

    /** Pasa [r] por el hilo de avisos, en orden con todo lo demás que se le avisa a la app. */
    fun enAvisos(r: () -> Unit) = avisos.execute { r() }

    fun resolver(tipo: String, nombre: String, alResolver: (Resuelto) -> Unit, alFallar: () -> Unit): Resolucion {
        val r = Resolucion()
        resolviendo.execute {
            val inst = instancia   // jmDNS y su número de red, leídos JUNTOS
            val resultado = inst?.let { runCatching { it.mdns.resolver(tipo, nombre, PLAZO_DE_RESOLVE_MS) }.getOrNull() }
            avisos.execute {
                if (r.cancelada) return@execute
                // Una respuesta de la red ANTERIOR (cambió la tarjeta mientras se resolvía) traería una IP vieja: falla.
                if (resultado != null && inst != null && inst.generacion == generacion) alResolver(resultado) else alFallar()
            }
        }
        return r
    }

    fun cancelar(r: Resolucion) { r.cancelada = true }

    /**
     * La vigía: si la tarjeta del local cambió (o apareció, o se fue), cambia de jmDNS y vuelve a anunciar y a buscar. Con la
     * MISMA tarjeta, aplica lo que quedó pendiente (lo que se pidió mientras no había red).
     */
    fun revisarDireccion() {
        trabajo.execute {
            if (anuncios.isEmpty() && busquedas.isEmpty()) { soltarSiNadieLoUsa(); return@execute }
            val nueva = runCatching { direccionDelLocal() }.getOrNull()
            val actual = mdns
            if (nueva == direccionActual && actual != null) { aplicarPendientes(actual); return@execute }
            if (nueva == null && actual == null) return@execute
            Log.i(TAG, "Red local: ${direccionActual?.hostAddress ?: "ninguna"} → ${nueva?.hostAddress ?: "ninguna"}")
            soltarMdns()
            asegurarMdns()?.let(::aplicarPendientes)
        }
    }

    /** Al cerrar la app y en las pruebas. */
    fun cerrar() {
        trabajo.execute {
            busquedas.forEach { it.activa = false }
            anuncios.clear(); busquedas.clear(); soltarMdns()
        }
    }

    // --- hilo de trabajo ---

    private fun asegurarMdns(): MdnsPort? {
        mdns?.let { return it }
        val d = runCatching { direccionDelLocal() }.getOrNull()
        if (d == null) {
            Log.w(TAG, "Sin red local (ni WiFi ni cable con dirección privada): el Hub LAN espera a que aparezca")
            return null
        }
        return runCatching { crearMdns(d) }
            .onSuccess {
                generacion++; instancia = Instancia(it, generacion); direccionActual = d
                val tarjeta = runCatching { NetworkInterface.getByInetAddress(d)?.displayName }.getOrNull()
                Log.i(TAG, "mDNS en ${d.hostAddress} (${tarjeta ?: "tarjeta desconocida"})")
            }
            .onFailure { Log.e(TAG, "No se pudo abrir mDNS en ${d.hostAddress}: ${it.message}") }
            .getOrNull()
    }

    /** Lo que todavía no está aplicado en ESTA instancia (pedido sin red, o que llegó antes de abrirla). Sin duplicar. */
    private fun aplicarPendientes(m: MdnsPort) {
        anuncios.filter { it.nombreFinal == null }.forEach { registrar(m, it) }
        busquedas.filter { it.dejarDeEscuchar == null }.forEach { escuchar(m, it) }
    }

    /** Sin anuncios ni búsquedas no se retiene jmDNS (ni se recrea al cambiar de tarjeta). */
    private fun soltarSiNadieLoUsa() {
        if (anuncios.isEmpty() && busquedas.isEmpty() && mdns != null) soltarMdns()
    }

    private fun soltarMdns() {
        val vieja = instancia
        instancia = null   // primero: un resolve que empiece desde aquí no ve la instancia vieja…
        generacion++       // …y lo que la vieja avise o resuelva desde aquí ya no pasa
        busquedas.forEach { b ->
            b.dejarDeEscuchar = null
            // Lo que se vio en la red que se deja ya no es alcanzable: se avisa perdido ANTES de que la nueva entregue nada
            // (mismo hilo de avisos, en orden).
            avisos.execute {
                val perdidos = b.encontrados.toList()
                b.encontrados.clear()
                if (b.activa) perdidos.forEach(b.alPerder)
            }
        }
        anuncios.forEach { it.nombreFinal = null }
        direccionActual = null
        vieja?.let { runCatching { it.mdns.cerrar() } }   // al final: cerrar jmDNS bloquea
    }

    private fun registrar(m: MdnsPort, a: Anuncio) {
        runCatching { m.registrar(a.tipo, a.nombre, a.puerto, a.txt) }
            .onSuccess { final -> a.nombreFinal = final; avisos.execute { a.alRegistrado(final) } }
            .onFailure { e ->
                // Un anuncio fallido sale del registro: LanDiscovery lo reintenta a su ritmo (1, 2, 4… s) con otro anunciar.
                anuncios.remove(a)
                Log.w(TAG, "No se pudo anunciar ${a.nombre}: ${e.message}")
                avisos.execute { a.alFallar(e) }
            }
    }

    private fun escuchar(m: MdnsPort, b: Busqueda) {
        val gen = generacion
        fun vigente() = b.activa && gen == generacion
        b.dejarDeEscuchar = runCatching {
            m.escuchar(
                b.tipo,
                { nombre -> avisos.execute { if (vigente()) { b.encontrados += nombre; b.alEncontrar(nombre) } } },
                { nombre -> avisos.execute { if (vigente()) { b.encontrados -= nombre; b.alPerder(nombre) } } },
            )
        }.onFailure { Log.w(TAG, "No se pudo buscar ${b.tipo}: ${it.message}") }.getOrNull()
    }

    companion object {
        private const val TAG = "RedLocal"
        const val PLAZO_DE_RESOLVE_MS = 3_000L
        const val VIGILANCIA_S = 10L

        /** La que usa el sustituto NsdManager: la compartida, salvo que una prueba ponga otra con mDNS falso. */
        @Volatile var enUso: RedLocalMdns? = null
        fun actual(): RedLocalMdns = enUso ?: compartida

        /** La de la app: una sola por proceso, con su vigía de la tarjeta del local. */
        val compartida: RedLocalMdns by lazy {
            RedLocalMdns().also { red ->
                Executors.newSingleThreadScheduledExecutor { Thread(it, "red-local-vigia").apply { isDaemon = true } }
                    .scheduleWithFixedDelay({ runCatching { red.revisarDireccion() } }, VIGILANCIA_S, VIGILANCIA_S, TimeUnit.SECONDS)
            }
        }

        private fun hiloDaemon(nombre: String): Executor =
            Executors.newSingleThreadExecutor { r -> Thread(r, nombre).apply { isDaemon = true } }

        /**
         * La IPv4 privada (10/8, 172.16/12, 192.168/16) de una tarjeta que el sustituto ConnectivityManager tiene por WiFi o
         * cable (excluye WSL, Hyper-V, VPN, Tailscale, Bluetooth, virtuales). Cable primero; si no, WiFi.
         */
        fun direccionDelLocalDeEsteEquipo(): InetAddress? = interfazDelLocalDeEsteEquipo()?.second

        /** La tarjeta del local y su IPv4 (ver [direccionDelLocalDeEsteEquipo]); la usa también el sustituto ConnectivityManager. */
        @JvmStatic
        fun interfazDelLocalDeEsteEquipo(): Pair<NetworkInterface, InetAddress>? {
            val candidatas = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .mapNotNull { i ->
                    val capacidades = runCatching { ConnectivityManager.capacidadesDeLaInterfaz(i) }.getOrNull() ?: return@mapNotNull null
                    val cable = capacidades.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                    if (!cable && !capacidades.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@mapNotNull null
                    val ip = i.inetAddresses.toList().firstOrNull { it is Inet4Address && it.isSiteLocalAddress } ?: return@mapNotNull null
                    Triple(cable, i, ip)
                }
            return candidatas.sortedWith(compareBy({ !it.first }, { it.second.index })).firstOrNull()?.let { it.second to it.third }
        }
    }
}

/** mDNS de verdad: jmDNS atado a UNA dirección (la de la tarjeta del local). */
class JmdnsPort(direccion: InetAddress) : MdnsPort {
    private val jmdns = JmDNS.create(direccion, "avoqado-pos-" + direccion.hostAddress.replace('.', '-'))
    private val registrados = java.util.concurrent.ConcurrentHashMap<String, ServiceInfo>()

    private fun completo(tipo: String) = tipo.trimEnd('.') + ".local."

    override fun registrar(tipo: String, nombre: String, puerto: Int, txt: Map<String, String>): String {
        val info = ServiceInfo.create(completo(tipo), nombre, puerto, 0, 0, txt)
        jmdns.registerService(info)
        val final = info.name
        registrados[final] = info
        return final
    }

    override fun retirar(tipo: String, nombre: String) {
        registrados.remove(nombre)?.let { jmdns.unregisterService(it) }
    }

    override fun escuchar(tipo: String, alEncontrar: (String) -> Unit, alPerder: (String) -> Unit): () -> Unit {
        val t = completo(tipo)
        val escucha = object : ServiceListener {
            override fun serviceAdded(e: ServiceEvent) = alEncontrar(e.name)
            override fun serviceRemoved(e: ServiceEvent) = alPerder(e.name)
            override fun serviceResolved(e: ServiceEvent) {}
        }
        jmdns.addServiceListener(t, escucha)
        return { jmdns.removeServiceListener(t, escucha) }
    }

    override fun resolver(tipo: String, nombre: String, plazoMs: Long): Resuelto? {
        val info = jmdns.getServiceInfo(completo(tipo), nombre, plazoMs) ?: return null
        val host = info.inet4Addresses.firstOrNull() ?: return null
        if (info.port <= 0) return null
        val txt = info.propertyNames.toList().associateWith { k -> info.getPropertyString(k).orEmpty() }
        return Resuelto(host, info.port, txt)
    }

    override fun cerrar() {
        runCatching { jmdns.unregisterAllServices() }
        jmdns.close()
    }
}
