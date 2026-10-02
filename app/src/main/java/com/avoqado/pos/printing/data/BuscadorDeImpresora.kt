package com.avoqado.pos.printing.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "BuscadorDeImpresora"

/** Cuánto se espera a que una dirección vecina abra el 9100. En una LAN contesta en milisegundos. */
private const val CONECTAR_MS = 350

/** Cuánto se espera la respuesta de estado de una ticketera. */
private const val ESTADO_MS = 800

/** Cuántas direcciones se tocan a la vez: 253 en ~2 s sin ahogar el WiFi. */
private const val EN_PARALELO = 48

/** La dirección de esta tablet en la red del local: IP y tamaño de la red (prefijo). */
data class DireccionLocal(val ip: String, val prefijo: Int)

/**
 * Lo que [PrinterService] necesita de la red para encontrar una impresora que cambió de
 * dirección. Es una interfaz para que las pruebas lo cambien por uno falso sin sockets.
 */
interface BuscadorDeImpresora {
    /** Las direcciones IPv4 de ESTA tablet en WiFi o Ethernet. */
    fun direccionesPropias(): List<DireccionLocal>

    /**
     * Las ticketeras ESC/POS que contestan en la red del local, sin tocar [noTocar].
     *
     * 🔴 [noTocar] son las direcciones de las OTRAS impresoras conocidas: muchas ticketeras
     * aceptan UNA sola conexión, y tocarlas a media comanda la retrasaría.
     */
    suspend fun ticketerasEnLaRed(puerto: Int, noTocar: Set<String>): List<String>

    /**
     * La dirección de la impresora que se anuncia por Bonjour/mDNS con EXACTAMENTE este nombre
     * (p. ej. «EPSON TM-m30III»), o null si no aparece en [msMax].
     *
     * 🔴 Es la identidad de las ticketeras de marca, que Epson recomienda con DHCP («specify
     * the MAC address or host name of the printer»). Hace falta porque una Epson TM tiene
     * abiertos los puertos de oficina (631/515) igual que una de tinta — medido en la oficina
     * el 2-oct: TM-m30III y ET-2800 indistinguibles por puertos — así que el barrido la salta.
     * mDNS no deja repetir un nombre en la red: si coincide, es ella.
     */
    suspend fun anunciadaConNombre(nombre: String, msMax: Long): String?

    /**
     * La identidad `mac:…` que la impresora publica en su página web (`/w5500.js` de las genéricas
     * con chip W5500, o una MAC escrita en `/`), o null. Sólo LEE: no manda nada al puerto de impresión.
     */
    suspend fun leerMac(ip: String): String?

    /** Las impresoras que se anuncian por Bonjour: IP → nombre anunciado. Para aprender su identidad. */
    suspend fun anunciadas(msMax: Long): Map<String, String>
}

/**
 * Barre la red del local buscando ticketeras: abre el puerto 9100 de cada vecina y a las que
 * contestan les pregunta su estado con `DLE EOT 1`. Sólo cuenta como ticketera la que contesta
 * con un byte de estado de forma ESC/POS ([ImpresoraMovida.esEstadoDeTicketera]).
 *
 * Funciona sin internet: todo pasa dentro del WiFi del local.
 */
class BuscadorDeImpresoraEnLan(private val context: Context) : BuscadorDeImpresora {

    override fun direccionesPropias(): List<DireccionLocal> = try {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION") // allNetworks: la red del local puede no ser la "activa" (SIM).
        cm.allNetworks.flatMap { red ->
            val caps = cm.getNetworkCapabilities(red)
            val esLan = caps != null && (
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
            if (!esLan) return@flatMap emptyList()
            cm.getLinkProperties(red)?.linkAddresses.orEmpty()
                .filter { it.address is Inet4Address && !it.address.isLoopbackAddress }
                .map { DireccionLocal(it.address.hostAddress.orEmpty(), it.prefixLength) }
        }.distinct()
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo leer la dirección de la tablet: ${e.message}")
        emptyList()
    }

    override suspend fun ticketerasEnLaRed(puerto: Int, noTocar: Set<String>): List<String> =
        withContext(Dispatchers.IO) {
            val propias = direccionesPropias()
            val vecinas = propias
                .flatMap { ImpresoraMovida.hostsDeLaSubred(it.ip, it.prefijo) }
                .distinct()
                .filter { it !in noTocar }
            if (vecinas.isEmpty()) return@withContext emptyList()

            val limite = Semaphore(EN_PARALELO)
            val encontradas = coroutineScope {
                vecinas.map { ip ->
                    async { limite.withPermit { if (esTicketera(ip, puerto)) ip else null } }
                }.awaitAll().filterNotNull()
            }
            Log.d(TAG, "🔎 Barrido de ${vecinas.size} direcciones: ticketeras en $encontradas")
            encontradas
        }

    override suspend fun anunciadaConNombre(nombre: String, msMax: Long): String? {
        if (nombre.isBlank()) return null
        val nsd = context.getSystemService(NsdManager::class.java) ?: return null
        return withTimeoutOrNull(msMax) {
            suspendCancellableCoroutine { cont ->
                val terminado = AtomicBoolean(false)
                lateinit var buscador: NsdManager.DiscoveryListener
                fun soltar() = runCatching { nsd.stopServiceDiscovery(buscador) }
                fun terminar(ip: String?) {
                    if (terminado.compareAndSet(false, true)) {
                        soltar()
                        cont.resume(ip)
                    }
                }
                buscador = object : NsdManager.DiscoveryListener {
                    override fun onServiceFound(info: NsdServiceInfo) {
                        if (info.serviceName != nombre) return
                        @Suppress("DEPRECATION")
                        runCatching {
                            nsd.resolveService(
                                info,
                                object : NsdManager.ResolveListener {
                                    // Sólo IPv4: el servidor rechaza IPv6 y la ronda la vería "movida" siempre (M4).
                                    override fun onServiceResolved(r: NsdServiceInfo) =
                                        terminar((r.host as? Inet4Address)?.hostAddress)
                                    override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                                        Log.w(TAG, "No se pudo resolver «$nombre» ($errorCode)")
                                    }
                                },
                            )
                        }
                    }
                    override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = terminar(null)
                    override fun onDiscoveryStarted(serviceType: String) {}
                    override fun onDiscoveryStopped(serviceType: String) {}
                    override fun onServiceLost(info: NsdServiceInfo) {}
                    override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
                }
                cont.invokeOnCancellation { if (terminado.compareAndSet(false, true)) soltar() }
                runCatching { nsd.discoverServices("_pdl-datastream._tcp", NsdManager.PROTOCOL_DNS_SD, buscador) }
                    .onFailure { terminar(null) }
            }
        }
    }

    override suspend fun leerMac(ip: String): String? = withContext(Dispatchers.IO) {
        pedirPagina(ip, "/w5500.js")?.let { IdentidadDeImpresora.macDeW5500(it) }
            ?: pedirPagina(ip, "/")?.let { IdentidadDeImpresora.macDeHtml(it) }
    }

    /**
     * GET por un socket directo (HTTP/1.0, sin cifrar). No por `HttpURLConnection`: Android bloquea
     * el HTTP sin cifrar (`cleartextTrafficPermitted`) y las ticketeras no tienen HTTPS. Lee como
     * mucho 64 KB y siempre suelta.
     */
    private fun pedirPagina(ip: String, ruta: String): String? = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, PUERTO_WEB), CONECTAR_WEB_MS)
            // Plazo TOTAL, no por lectura: un servidor que gotea bytes no alarga la espera (M2).
            val limite = System.currentTimeMillis() + LEER_WEB_MS
            socket.soTimeout = LEER_WEB_MS
            socket.getOutputStream().apply {
                write("GET $ruta HTTP/1.0\r\nHost: $ip\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val entrada = socket.getInputStream()
            val bufer = java.io.ByteArrayOutputStream()
            val trozo = ByteArray(4096)
            while (bufer.size() < MAX_PAGINA_BYTES) {
                val restante = limite - System.currentTimeMillis()
                if (restante <= 0) break
                socket.soTimeout = restante.toInt()
                // Un servidor que no cierra la conexión: se usa lo que ya llegó.
                val n = try { entrada.read(trozo) } catch (_: java.net.SocketTimeoutException) { -1 }
                if (n < 0) break
                bufer.write(trozo, 0, n)
            }
            val respuesta = bufer.toString(Charsets.ISO_8859_1.name())
            val estado = respuesta.substringBefore("\r\n")
            if (!estado.contains(" 200")) null else respuesta.substringAfter("\r\n\r\n", "")
        }
    } catch (_: Exception) {
        null
    }

    override suspend fun anunciadas(msMax: Long): Map<String, String> {
        val nsd = context.getSystemService(NsdManager::class.java) ?: return emptyMap()
        val resultado = java.util.concurrent.ConcurrentHashMap<String, String>()
        val encontrados = kotlinx.coroutines.channels.Channel<NsdServiceInfo>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val oyente = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) { encontrados.trySend(info) }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { encontrados.close() }
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        if (runCatching { nsd.discoverServices("_pdl-datastream._tcp", NsdManager.PROTOCOL_DNS_SD, oyente) }.isFailure) {
            return emptyMap()
        }
        try {
            withTimeoutOrNull(msMax) {
                // EN SERIE: resolveService truena con FAILURE_ALREADY_ACTIVE si hay otro en curso.
                for (info in encontrados) {
                    resolver(nsd, info)?.let { ip -> resultado[ip] = info.serviceName }
                }
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(oyente) }
        }
        return resultado
    }

    @Suppress("DEPRECATION")
    private suspend fun resolver(nsd: NsdManager, info: NsdServiceInfo): String? = withTimeoutOrNull(RESOLVER_MS) {
        suspendCancellableCoroutine { cont ->
            runCatching {
                nsd.resolveService(
                    info,
                    object : NsdManager.ResolveListener {
                        override fun onServiceResolved(r: NsdServiceInfo) {
                            if (cont.isActive) cont.resume((r.host as? Inet4Address)?.hostAddress)
                        }
                        override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    /**
     * Abre, pregunta el estado y SIEMPRE suelta: dejar el 9100 tomado es el teléfono descolgado.
     *
     * 🔴 A una impresora de OFICINA no se le manda NI UN byte. También escucha en el 9100 e
     * imprime lo que le llegue: tres bytes de control pueden sacarle una hoja en blanco o con
     * basura, y esto corre en cada comanda que falla (nmap excluye el 9100 por lo mismo). En la
     * red de Testarudo hay una HP, una Kyocera, una Ricoh y una Brother. Se distinguen SIN
     * mandar datos: tienen abierto el puerto de IPP (631) o de LPD (515), que una ticketera
     * genérica no trae. Abrir y cerrar una conexión sin escribir no imprime nada.
     *
     * Costo aceptado: una Epson TM también anuncia IPP y no se encuentra sola por aquí.
     */
    private fun esTicketera(ip: String, puerto: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, puerto), CONECTAR_MS)
            if (PUERTOS_DE_OFICINA.any { estaAbierto(ip, it) }) {
                Log.d(TAG, "🖨️ $ip parece impresora de oficina (IPP/LPD): no se le manda nada")
                return false
            }
            socket.soTimeout = ESTADO_MS
            socket.getOutputStream().apply {
                write(byteArrayOf(0x10, 0x04, 0x01)) // DLE EOT 1 — estado de la impresora
                flush()
            }
            ImpresoraMovida.esEstadoDeTicketera(socket.getInputStream().read())
        }
    } catch (_: Exception) {
        false
    }

    private fun estaAbierto(ip: String, puerto: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, puerto), CONECTAR_MS); true }
    } catch (_: Exception) {
        false
    }

    private companion object {
        /** IPP y LPD: los puertos de una impresora de oficina (HP, Kyocera, Ricoh, Brother). */
        val PUERTOS_DE_OFICINA = listOf(631, 515)
        const val PUERTO_WEB = 80
        const val CONECTAR_WEB_MS = 1_500
        /** Plazo para leer la página de la impresora. Su servidor web es lento; se ajusta con la medición real. */
        const val LEER_WEB_MS = 8_000
        const val MAX_PAGINA_BYTES = 64 * 1024
        const val RESOLVER_MS = 1_500L
    }
}
