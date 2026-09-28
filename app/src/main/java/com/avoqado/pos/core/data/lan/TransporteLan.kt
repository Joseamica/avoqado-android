package com.avoqado.pos.core.data.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.printing.routing.PrintConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TransporteLan"

/**
 * Etapa 3 del KDS (3.5, D1) — UN transporte por aparato: dueño de UN `ServerSocket`, UN anuncio NSD y UN buscador.
 * Rutea por `op` ([EnrutadorLan]). El hub de mesas ([LanHubCoordinator]) y el receptor de cocina se ENGANCHAN aquí;
 * no abren nada propio. Dos registros del mismo `did` se pisaban (`LanDiscovery` se queda con el último que resuelve:
 * las ops de lease irían al socket de cocina) y un aparato sólo-cocina podía ganar la elección del hub: por eso UNO.
 *
 * Vive con la app (`@Singleton`, ámbito propio): `AppState` sólo lo enciende y lo apaga (D12). Anuncia y busca SÓLO si
 * alguna estación activa tiene `hasKitchenDisplay`, si hay receptor o si el hub está conectado; si no, ni socket.
 *
 * Espejo de avoqado-ios: Services/LAN/TransporteLan.swift.
 */
@Singleton
class TransporteLan @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncOutbox: SyncOutbox,
    private val printConfigRepository: PrintConfigRepository,
) : LanDiscoveryPort {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** El del outbox, NUNCA otro (regla §2.4 del hub): si cambiara, el mismo POS se vería como dos peers. */
    val deviceId: String get() = syncOutbox.deviceId

    private val _peers = MutableStateFlow<List<LanPeer>>(emptyList())
    /** Peers vivos, INCLUIDO este aparato (127.0.0.1): una caja que además es pantalla se empuja a sí misma por loopback. */
    override val peers: StateFlow<List<LanPeer>> = _peers.asStateFlow()

    private val _estacionesAnunciadas = MutableStateFlow<Set<String>>(emptySet())
    val estacionesAnunciadas: StateFlow<Set<String>> = _estacionesAnunciadas.asStateFlow()

    /** ¿Hay un receptor de comandas vivo (un Tablero en pantalla)? La banda de la caja lo usa (D11). */
    private val _receptorActivo = MutableStateFlow(false)
    val receptorActivo: StateFlow<Boolean> = _receptorActivo.asStateFlow()

    private val _hubConectado = MutableStateFlow(false)
    val hubConectado: StateFlow<Boolean> = _hubConectado.asStateFlow()

    @Volatile private var venueId: String? = null
    @Volatile private var hub: ((String) -> LeaseResponse)? = null
    @Volatile private var receptor: (suspend (KdsComanda) -> Boolean)? = null
    @Volatile private var ajenos: List<LanPeer> = emptyList()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var configJob: Job? = null
    private var discovery: LanDiscovery? = null
    private val bootedAtMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /** Puerto del socket propio, o -1 si la red local está apagada. */
    val puerto: Int get() = serverSocket?.takeIf { !it.isClosed }?.localPort ?: -1

    // MARK: - Ciclo de vida (D12)

    /** Idempotente por venue; con otro venue reinicia. Se llama 2-3 veces al arrancar (`startOfflineOutbox`). */
    @Synchronized
    fun iniciar(venueId: String) {
        if (this.venueId == venueId && configJob?.isActive == true) return
        detener()
        this.venueId = venueId
        configJob = scope.launch {
            combine(printConfigRepository.config, _hubConectado, _receptorActivo) { config, hub, receptor ->
                hub || receptor || config.stations.any { it.active && it.hasKitchenDisplay }
            }.distinctUntilChanged().collect { debeVivir -> if (debeVivir) encender() else apagarRed() }
        }
        Log.i(TAG, "🛰️ Transporte LAN listo para venue=$venueId device=${deviceId.take(6)}")
    }

    @Synchronized
    fun detener() {
        configJob?.cancel(); configJob = null
        apagarRed()
        venueId = null
    }

    override fun conectarHub(respondTo: (String) -> LeaseResponse) {
        hub = respondTo
        _hubConectado.value = true
        reanunciar()
    }

    override fun desconectarHub() {
        hub = null
        _hubConectado.value = false
        reanunciar()
    }

    /**
     * El receptor de cocina: SÓLO mientras un Tablero de esas estaciones está en pantalla. [alRecibir] corre en el hilo
     * del socket y tiene que GUARDAR antes de devolver `true` (D8): el acuse sale sólo entonces.
     */
    fun activarReceptor(estaciones: Set<String>, alRecibir: suspend (KdsComanda) -> Boolean) {
        receptor = alRecibir
        _estacionesAnunciadas.value = estaciones
        _receptorActivo.value = estaciones.isNotEmpty()
        reanunciar()
    }

    fun desactivarReceptor() {
        receptor = null
        _estacionesAnunciadas.value = emptySet()
        _receptorActivo.value = false
        reanunciar()
    }

    /** Las pantallas que hoy anuncian esa estación — incluido este aparato si la tiene en pantalla. */
    fun pantallasDe(stationId: String): List<LanPeer> = _peers.value.filter { stationId in it.kdsStations }

    // MARK: - Red

    @Synchronized
    private fun encender() {
        val v = venueId ?: return
        val port = abrirSocket() ?: return
        val d = discovery ?: LanDiscovery(context, deviceId, v) { ajenos = it; publicarPeers() }.also { discovery = it }
        d.anunciar(port, txt(v))
        d.buscar()
        publicarPeers()
    }

    @Synchronized
    private fun apagarRed() {
        discovery?.parar(); discovery = null
        acceptJob?.cancel(); acceptJob = null
        runCatching { serverSocket?.close() }; serverSocket = null
        ajenos = emptyList()
        publicarPeers()
    }

    /** Cambió el TXT (`kds=` o `hub=`): se vuelve a registrar el servicio (NSD no edita un TXT ya registrado). */
    @Synchronized
    private fun reanunciar() {
        val v = venueId ?: return
        val d = discovery ?: return
        if (puerto > 0) d.anunciar(puerto, txt(v))
        publicarPeers()
    }

    private fun txt(v: String) = LanTxt.construir(deviceId, v, isWiredConnection(), bootedAtMillis, _estacionesAnunciadas.value, hub != null)

    private fun publicarPeers() {
        val p = puerto
        if (venueId == null || p <= 0) { _peers.value = emptyList(); return }
        val yo = LanPeer(deviceId, "127.0.0.1", p, isWiredConnection(), bootedAtMillis, _estacionesAnunciadas.value, hub != null)
        _peers.value = listOf(yo) + ajenos
    }

    private fun abrirSocket(): Int? {
        serverSocket?.takeIf { !it.isClosed }?.let { return it.localPort }
        return try {
            val socket = ServerSocket(0) // puerto efímero: se publica en el TXT, nadie lo adivina
            serverSocket = socket
            acceptJob = scope.launch { aceptar(socket) }
            Log.i(TAG, "🛰️ Transporte LAN escuchando en el puerto ${socket.localPort}")
            socket.localPort
        } catch (e: Exception) {
            Log.e(TAG, "❌ No se pudo abrir el socket del transporte: ${e.message} — sin red local")
            null
        }
    }

    private suspend fun aceptar(socket: ServerSocket) {
        while (!socket.isClosed && currentCoroutineContext().isActive) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (!socket.isClosed) Log.w(TAG, "accept falló: ${e.message}")
                return
            }
            // Cada conexión en su corrutina: una tablet lenta no bloquea a las demás.
            scope.launch { atender(client) }
        }
    }

    /** Una conexión = una línea = una respuesta = cerrar. */
    private suspend fun atender(client: Socket) = client.use { sock ->
        runCatching {
            sock.soTimeout = PLAZO_DE_LECTURA_MS
            // D3: línea más larga que el tope, o nada ⇒ se corta SIN responder (el que envía lo ve como sin acuse).
            val linea = LineaAcotada.leer(BufferedInputStream(sock.getInputStream())) ?: return@runCatching
            val respuesta = EnrutadorLan.responder(linea, venueId, _estacionesAnunciadas.value, hub, receptor)
            sock.getOutputStream().run { write((respuesta + "\n").toByteArray(Charsets.UTF_8)); flush() }
        }.onFailure { Log.w(TAG, "conexión fallida: ${it.message}") }
    }

    /** Ethernet/dock → mejor candidato a árbitro (movido de `LanHubService`: el TXT se arma aquí). */
    private fun isWiredConnection(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        cm?.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
    }.getOrDefault(false)

    companion object {
        /** Plazo para que un peer mande su línea completa (D3). El mismo 3 s que tenía el árbitro. */
        const val PLAZO_DE_LECTURA_MS = 3_000
    }
}
