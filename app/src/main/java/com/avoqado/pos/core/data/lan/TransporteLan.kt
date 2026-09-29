package com.avoqado.pos.core.data.lan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.printing.routing.PrintConfigRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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

    /** ¿Alguien enganchó un receptor (un Tablero en pantalla)? Decide si la red local debe vivir; NO dice que se reciba. */
    private val _receptorEnganchado = MutableStateFlow(false)

    /**
     * ¿Recibiendo DE VERDAD? Receptor enganchado Y el socket sirviendo (puerto > 0). Se prende hasta que el socket abre y
     * se apaga si se cierra o se está reabriendo: la pantalla de cocina lo usa para decir «recibiendo por el WiFi del
     * local» (D11). Espejo de `receptorActivo` de iOS (ronda de la Task 3).
     */
    private val _receptorActivo = MutableStateFlow(false)
    val receptorActivo: StateFlow<Boolean> = _receptorActivo.asStateFlow()

    private val _hubConectado = MutableStateFlow(false)
    val hubConectado: StateFlow<Boolean> = _hubConectado.asStateFlow()

    /** D11: entregas seguidas sin acuse por estación ([EntregaPorWifi] la alimenta; la banda de la caja la dice). */
    val racha = RachaSinAcuse()

    @Volatile private var venueId: String? = null
    @Volatile private var hub: ((String) -> LeaseResponse)? = null
    @Volatile private var receptor: (suspend (KdsComanda) -> Boolean)? = null

    /**
     * Task 8b (paridad iOS `RuteoLan.generacion`): sube SÓLO al desactivar el receptor, al cambiar de estación o al
     * reiniciar el transporte ([detener], M2 de la revisión final) — nunca al re-enganchar la MISMA estación con el
     * receptor ya puesto. `ReceptorDeComandas.activar` crea una lambda
     * NUEVA en cada sondeo de rutina; comparar su identidad (`receptor === r`) le quitaba el acuse a un guardado que
     * cruzaba con esa re-activación (papel de más sin motivo). El acuse compara esto, no la lambda.
     */
    @Volatile private var generacion = 0
    @Volatile private var ajenos: List<LanPeer> = emptyList()
    /** `@Volatile`: [puerto] lo lee cualquier hilo (revisión M1). */
    @Volatile private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var configJob: Job? = null
    private var discovery: LanDiscovery? = null
    private val bootedAtMillis = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /**
     * I4 (revisión T8): las redes WiFi/Ethernet vivas. `ServerSocket(0)` escucha en 0.0.0.0 y sigue «sirviendo» sólo por
     * loopback cuando se cae el router: sin ninguna de éstas nadie del local alcanza la pantalla. Se registra UNA vez
     * (el transporte vive con la app) y cada cambio vuelve a publicar [receptorActivo].
     */
    private val redesLocales = mutableSetOf<Network>()
    private var vigiaDeRed: ConnectivityManager.NetworkCallback? = null

    /** Puerto del socket propio, o -1 si la red local está apagada. */
    val puerto: Int get() = serverSocket?.takeIf { !it.isClosed }?.localPort ?: -1

    // MARK: - Ciclo de vida (D12)

    /** Idempotente por venue; con otro venue reinicia. Se llama 2-3 veces al arrancar (`startOfflineOutbox`). */
    @Synchronized
    fun iniciar(venueId: String) {
        if (this.venueId == venueId && configJob?.isActive == true) return
        detener()
        vigilarRedLocal()
        this.venueId = venueId
        configJob = scope.launch {
            combine(printConfigRepository.config, _hubConectado, _receptorEnganchado) { config, hub, receptor ->
                hub || receptor || config.stations.any { it.active && it.hasKitchenDisplay }
            }.distinctUntilChanged().collectLatest { debeVivir ->
                if (!debeVivir) { apagarRed(); return@collectLatest }
                // Revisión M5/M6: mientras la red local debe vivir se revisa cada minuto. `encender` es idempotente (mismo
                // socket, mismo TXT ⇒ nada), pero levanta un socket que no abrió y re-anuncia tras una ráfaga de
                // registros fallida agotada — sin esto un aparato sólo-hub se quedaba invisible hasta el siguiente cambio.
                while (true) {
                    encender()
                    delay(REVISION_DE_RED_MS)
                }
            }
        }
        Log.i(TAG, "🛰️ Transporte LAN listo para venue=$venueId device=${deviceId.take(6)}")
    }

    /**
     * Apaga la red local. NO suelta el hub ni el receptor: son de quien los enganchó ([LanHubService], el Tablero), y
     * `AppState` apaga el hub ANTES (revisión M2). Si el hub sigue vivo, el siguiente [iniciar] lo vuelve a servir —
     * es el mismo coordinador, no uno viejo.
     */
    @Synchronized
    fun detener() {
        configJob?.cancel(); configJob = null
        racha.limpiar() // otra sesión u otra sucursal empieza de cero
        // M2 (revisión final, 8b OOS4): un guardado que cruza el reinicio (cambio de sucursal en la tablet de cocina,
        // cierre de sesión) ya no es de esta pantalla aunque el receptor siga puesto con la MISMA estación: sin acuse, la
        // caja saca el papel. Sin esto se acusaba y la fila quedaba bajo la sucursal vieja, donde nadie la ve.
        generacion++
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
    fun activarReceptor(estaciones: Set<String>, alRecibir: suspend (KdsComanda) -> Boolean) = fijarReceptor(estaciones, alRecibir)

    fun desactivarReceptor() = fijarReceptor(emptySet(), null)

    /**
     * Task 8b: la generación sube sólo si cambia el conjunto de estaciones o si el receptor pasa de enganchado a
     * suelto (o viceversa) — nunca al re-enganchar la MISMA estación con el receptor ya puesto. Espejo de
     * `RuteoLan.fijar(estaciones:receptor:)` de iOS.
     */
    @Synchronized
    private fun fijarReceptor(estaciones: Set<String>, r: (suspend (KdsComanda) -> Boolean)?) {
        if (estaciones != _estacionesAnunciadas.value || (r == null) != (receptor == null)) generacion++
        receptor = r
        _estacionesAnunciadas.value = estaciones
        _receptorEnganchado.value = estaciones.isNotEmpty()
        publicarReceptor()
        reanunciar()
    }

    /** Las pantallas que hoy anuncian esa estación — incluido este aparato si la tiene en pantalla. */
    fun pantallasDe(stationId: String): List<LanPeer> = _peers.value.filter { stationId in it.kdsStations }

    // MARK: - Red

    @Synchronized
    private fun encender() {
        val v = venueId ?: return
        val port = abrirSocket() ?: return
        val d = discovery ?: run {
            lateinit var nueva: LanDiscovery
            nueva = LanDiscovery(context, deviceId, v) { lista -> alCambiarAjenos(nueva, lista) }
            nueva.also { discovery = it }
        }
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
        publicarReceptor()
    }

    /**
     * Sincronizado: el que corre al último lee los datos ya escritos (el socket lo cambia el hilo de IO; el enganche, el
     * Tablero; la red, el vigía de conectividad). I4: sin WiFi ni Ethernet no se recibe, aunque el socket escuche.
     */
    @Synchronized
    private fun publicarReceptor() {
        _receptorActivo.value = _receptorEnganchado.value && puerto > 0 && redesLocales.isNotEmpty()
    }

    @Synchronized
    private fun cambioDeRed(cambio: MutableSet<Network>.() -> Unit) {
        redesLocales.cambio()
        publicarReceptor()
    }

    /** I4: una sola vez por proceso. Sin `ConnectivityManager` (pruebas sin él) no hay red local que anunciar como viva. */
    @Synchronized
    private fun vigilarRedLocal() {
        if (vigiaDeRed != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val vigia = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = cambioDeRed { add(network) }
            override fun onLost(network: Network) = cambioDeRed { remove(network) }
        }
        runCatching {
            // Sin encadenar: en las pruebas JVM el `Builder` de android.jar devuelve null en cada llamada.
            val pedido = NetworkRequest.Builder()
            pedido.addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            pedido.addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            // Un WiFi sin internet sigue siendo la red del local: no se exige internet.
            pedido.removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            cm.registerNetworkCallback(pedido.build(), vigia)
            vigiaDeRed = vigia
        }.onFailure { Log.w(TAG, "No se pudo vigilar la red local: ${it.message} — la banda no dirá que recibe por WiFi") }
    }

    /**
     * El `accept` murió con el socket abierto: se apaga la red local entera (M4 de la revisión T8). Cerrar sólo el socket
     * dejaba el anuncio NSD con el puerto viejo — `anunciar` no re-registra si el TXT no cambia, y el puerto no va en el
     * TXT —, así que los peers seguían llamando a un puerto muerto. La revisión de cada minuto lo reabre con anuncio nuevo.
     */
    @Synchronized
    private fun soltarSocket(socket: ServerSocket) {
        if (serverSocket !== socket) return
        apagarRed()
    }

    /** Cambió el TXT (`kds=` o `hub=`): se vuelve a registrar el servicio (NSD no edita un TXT ya registrado). */
    @Synchronized
    private fun reanunciar() {
        val v = venueId ?: return
        val d = discovery ?: return
        if (puerto > 0) d.anunciar(puerto, txt(v))
        publicarPeers()
    }

    /**
     * Revisión I2: sólo la instancia VIVA escribe los peers ajenos. Un resolve que contesta tarde en una instancia ya
     * parada (red apagada, otra sucursal) metía peers viejos —o de otro venue— en la elección del siguiente encendido.
     */
    @Synchronized
    private fun alCambiarAjenos(origen: LanDiscovery, lista: List<LanPeer>) {
        if (discovery !== origen) return
        ajenos = lista
        publicarPeers()
    }

    private fun txt(v: String) = LanTxt.construir(deviceId, v, isWiredConnection(), bootedAtMillis, _estacionesAnunciadas.value, hub != null)

    @Synchronized
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
            publicarReceptor()
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
                soltarSocket(socket)
                return
            }
            // Cada conexión en su corrutina: una tablet lenta no bloquea a las demás.
            scope.launch { atender(client) }
        }
    }

    /** Una conexión = una línea = una respuesta = cerrar. */
    private suspend fun atender(client: Socket) = client.use { sock ->
        runCatching {
            sock.soTimeout = PLAZO_DE_LECTURA_MS // respaldo por lectura
            // D3 (revisión I3): el plazo es de la LÍNEA ENTERA, como `LectorDeLinea` de iOS. `soTimeout` sólo corta un
            // silencio: un peer que gotea un byte cada 2.9 s retenía este hilo 65 536 × 3 s. El vigía cierra el socket
            // al vencer y se desarma ANTES de rutear: nunca corta el guardado del receptor.
            val vigia = scope.launch { delay(PLAZO_DE_LECTURA_MS.toLong()); runCatching { sock.close() } }
            val linea = try {
                LineaAcotada.leer(BufferedInputStream(sock.getInputStream()))
            } finally {
                vigia.cancelAndJoin()
            }
            // D3: línea más larga que el tope, nada o plazo vencido ⇒ se corta SIN responder (el que envía: sin acuse).
            if (linea == null || sock.isClosed) return@runCatching
            // M3 (revisión T8): la pantalla pudo cerrarse o cambiar de estación MIENTRAS se guardaba. Guardada queda (el
            // lado seguro: papel y pantalla); el acuse sólo sale si sigue enganchado el MISMO receptor con esa estación.
            val r = receptor
            val g = generacion
            val vigente: (suspend (KdsComanda) -> Boolean)? =
                if (r == null) null else { c: KdsComanda -> r(c) && generacion == g && c.stationId in _estacionesAnunciadas.value }
            val respuesta = EnrutadorLan.responder(linea, venueId, _estacionesAnunciadas.value, hub, vigente)
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

        /** Cada cuánto se revisa que el socket y el anuncio sigan en pie mientras la red local debe vivir (M5/M6). */
        const val REVISION_DE_RED_MS = 60_000L
    }
}
