package com.avoqado.pos.core.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.avoqado.pos.core.data.network.ApiConstants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Las redes con internet que el sistema reporta vivas. `registerNetworkCallback` avisa POR RED:
 * perder el WiFi no deja al aparato sin red si siguen los datos del SIM. Medido en la N86 (29-sep):
 * `onLost` del WiFi ponía «sin conexión», los pings al servidor se detenían y el aviso se quedó
 * pegado ~10 min con el servidor contestando por datos. Espejo de iOS, que mira la ruta completa del
 * aparato (`NWPathMonitor`, conectado si CUALQUIER interfaz sirve).
 */
internal class RedesVivas {
    private val redes = mutableSetOf<Any>()

    @Synchronized
    fun agregar(red: Any) {
        redes.add(red)
    }

    /** true si queda alguna red viva. */
    @Synchronized
    fun quitar(red: Any): Boolean {
        redes.remove(red)
        return redes.isNotEmpty()
    }
}

@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var retryJob: Job? = null
    private val redesVivas = RedesVivas()

    private val _isConnected = MutableStateFlow(checkCurrentConnectivity())
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    // Server reachability — set by HTTP interceptors or explicit checks
    private val _isServerReachable = MutableStateFlow(true)
    val isServerReachable: StateFlow<Boolean> = _isServerReachable.asStateFlow()

    /**
     * D12 (final fix ronda 2, R2-2): ¿el último ping o la última consulta de la salud se quedó SIN respuesta (una
     * [java.io.IOException]: conexión rechazada, tiempo agotado, DNS)? Cualquier respuesta HTTP, también un 5xx, lo apaga, y una
     * respuesta buena también. Mientras [isServerReachable] siga en falso, el reintento lo vuelve a decidir cada 10 s: así el WiFi
     * conectado con el módem sin internet sí se nota. Menor 2: la frontera real es «contestó en [PING_TIMEOUT_MS]» (para conectar y otro
     * tanto para leer; la resolución de DNS no la limita), no «el WiFi está bien»: un servidor colgado que no contesta en ese plazo
     * también cuenta como «nadie contesta».
     */
    private val _sinRespuesta = MutableStateFlow(false)
    val sinRespuesta: StateFlow<Boolean> = _sinRespuesta.asStateFlow()

    /**
     * D12 · `RendirseRapido` (Codex r1 #2): cuándo contestó el servidor por última vez, con CUALQUIER código (ping, salud o una respuesta
     * HTTP del cliente principal), en reloj monotónico (ms). Sólo se escribe; no cambia [sinRespuesta] ni [isServerReachable]. Sirve
     * para que el recuerdo de «la última marcada se quedó sin respuesta» caduque en cuanto alguien contesta.
     */
    @Volatile var ultimaRespuestaMs: Long = 0L
        private set

    /** Alguien contestó (cualquier código). Ver [ultimaRespuestaMs]. */
    fun anotarRespuesta() {
        ultimaRespuestaMs = System.nanoTime() / 1_000_000
    }

    /** Seam de pruebas: la URL del servidor para el ping y la salud. En producción, [ApiConstants.BASE_URL]. */
    @androidx.annotation.VisibleForTesting
    internal var urlBase: String = ApiConstants.BASE_URL

    // Combined connectivity: device has network AND server is reachable
    val isFullyConnected: Boolean get() = _isConnected.value && _isServerReachable.value

    // Synchronous check used by offline-queue logic (e.g. ReservationRepository)
    fun isOnline(): Boolean = isFullyConnected

    // Flow alias consumed by Tasks 25+ (offline sync worker)
    val isOnlineFlow: StateFlow<Boolean> get() = isConnected

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        connectivityManager.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                redesVivas.agregar(network)
                val wasDisconnected = !_isConnected.value
                _isConnected.value = true
                if (wasDisconnected) {
                    Log.d("📡", "Network reconnected")
                    // If server was marked down while offline, ping immediately
                    // to confirm recovery instead of waiting for the 10s retry tick
                    if (!_isServerReachable.value) {
                        scope.launch { pingServer() }
                    }
                }
            }

            override fun onLost(network: Network) {
                val quedaRed = redesVivas.quitar(network)
                _isConnected.value = quedaRed
                Log.d("📡", if (quedaRed) "Network lost — otra red sigue viva" else "Network lost")
            }
        })
    }

    fun reportServerError() {
        if (_isServerReachable.value) {
            _isServerReachable.value = false
            Log.d("📡", "Server marked unreachable — starting retry pings")
            startRetryTimer()
        }
    }

    fun reportServerSuccess() {
        anotarRespuesta()
        _sinRespuesta.value = false
        if (!_isServerReachable.value) {
            _isServerReachable.value = true
            Log.d("📡", "Server marked reachable")
            stopRetryTimer()
        }
    }

    private fun startRetryTimer() {
        retryJob?.cancel()
        retryJob = scope.launch {
            while (isActive) {
                delay(RETRY_INTERVAL_MS)
                if (_isConnected.value) {
                    pingServer()
                }
            }
        }
    }

    private fun stopRetryTimer() {
        retryJob?.cancel()
        retryJob = null
    }

    /**
     * 🔴 ¿NUESTRO servidor contesta AHORA, con su base de datos? Una consulta real a la salud profunda (`/api/public/healthcheck`
     * hace `SELECT 1`), no la bandera. Cuenta sólo un 200 con JSON `"status":"ok"`: un portal cautivo, un proxy o el servidor sin su
     * base devuelven otra cosa. Lo usa el tope del cierre que espera a sus ventas (efectivo sin red en el turno correcto, 24-oct-2026).
     */
    suspend fun elServidorContesta(): Boolean = consultarSalud() == true

    /**
     * D12 · familia C (Codex r4 #2): [elServidorContesta] con plazo TOTAL [plazoMs] (resolver, conectar, encabezados y cuerpo). «No» al
     * instante, sin preguntar, si el monitor ya sabe que no hay red ([isConnected] falso o [sinRespuesta]). Al vencer cuenta como «no» (falla
     * cerrado) y no toca [sinRespuesta]: un plazo corto propio no prueba que nadie conteste. La consulta corre en el scope del monitor: quien
     * pregunta regresa al vencer, sin esperar a un DNS o a un cuerpo que gotea; la consulta deja de leer y cierra SU conexión en su hilo,
     * a más tardar un plazo después (cada espera de conectar o leer dura ≤ [plazoMs]). Cerrarla desde el hilo de quien pregunta no sirve:
     * `disconnect()` espera el candado de la conexión, que el hilo que lee vuelve a tomar en cada byte.
     */
    suspend fun elServidorContestaEn(plazoMs: Long): Boolean {
        if (!_isConnected.value || _sinRespuesta.value) return false
        val plazo = PlazoDeLaSalud(plazoMs.toInt())
        val consulta = scope.async { consultarSalud(plazo) }
        try {
            return kotlinx.coroutines.withTimeoutOrNull(plazoMs) { consulta.await() } == true
        } finally {
            if (!consulta.isCompleted) plazo.vencido = true
        }
    }

    /** UNA consulta de la salud con plazo total: [ms] topa cada espera; al [vencido], la consulta deja de leer y cierra su conexión. */
    private class PlazoDeLaSalud(val ms: Int) {
        @Volatile var vencido = false

        fun revisar() {
            if (vencido) throw java.io.IOException("plazo de la salud vencido")
        }
    }

    /**
     * D12 (final fix, F3): ¿se fue la red DE VERDAD? Sin red del aparato, o nadie contesta la salud (sin datos, DNS, tiempo agotado: un
     * [java.io.IOException]). Si alguien contesta, aunque sea mal (un 5xx), NO es «sin red»: el mismo criterio que `FalloDeLectura`. La
     * bandera [isServerReachable] no lo distingue: cualquier 5xx de cualquier pantalla la apaga. Pone al día [sinRespuesta] (ronda 2);
     * la frontera es «contestó en [PING_TIMEOUT_MS]», no «WiFi bueno» (menor 2).
     */
    suspend fun nadieContesta(): Boolean = consultarSalud() == null

    /** La salud profunda: true = nuestra API contestó bien · false = alguien contestó, pero no eso · null = nadie contestó (o sin red). */
    private suspend fun consultarSalud(plazo: PlazoDeLaSalud? = null): Boolean? = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (!_isConnected.value) return@withContext null
        var connection: HttpURLConnection? = null
        try {
            val url = java.net.URI(urlBase).resolve("/api/public/healthcheck").toURL()
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = plazo?.let { minOf(it.ms, PING_TIMEOUT_MS) } ?: PING_TIMEOUT_MS
                readTimeout = plazo?.let { minOf(it.ms, PING_TIMEOUT_MS) } ?: PING_TIMEOUT_MS
                instanceFollowRedirects = false
                // Codex apps r7 #3: nunca una respuesta guardada (ni aquí ni en un proxy): la salud vale sólo si es de AHORA.
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache, no-store")
                setRequestProperty("Pragma", "no-cache")
            }
            plazo?.revisar() // la consulta tardó en arrancar y el plazo ya venció: no se conecta
            val code = connection.responseCode
            anotarRespuesta()
            _sinRespuesta.value = false // alguien contestó, aunque sea mal
            val cuerpo = if (code == 200) leerCuerpo(connection, plazo) else null
            val ok = saludContesta(code, connection.contentType, cuerpo)
            if (ok) reportServerSuccess() else Log.d("📡", "Salud del servidor: $code (${connection.contentType}) — no cuenta")
            ok
        } catch (e: java.io.IOException) {
            Log.d("📡", "Salud del servidor sin respuesta: ${e.message}")
            if (plazo == null) _sinRespuesta.value = true // familia C: lo que no alcanza a contestar en el plazo propio no es «nadie contesta»
            null
        } catch (e: Exception) {
            Log.d("📡", "Salud del servidor sin respuesta: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    /** El cuerpo de la salud; con [plazo], se deja de leer en cuanto vence (su conexión la cierra el `finally` de quien lee). */
    private fun leerCuerpo(c: HttpURLConnection, plazo: PlazoDeLaSalud?): String = c.inputStream.use { entrada ->
        val salida = java.io.ByteArrayOutputStream()
        val trozo = ByteArray(1024)
        while (true) {
            plazo?.revisar()
            val n = entrada.read(trozo)
            if (n < 0) break
            salida.write(trozo, 0, n)
        }
        salida.toString(Charsets.UTF_8.name())
    }

    internal fun pingServer() {
        if (!_isConnected.value) return
        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlBase)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = PING_TIMEOUT_MS
                readTimeout = PING_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            val code = connection.responseCode
            anotarRespuesta()
            _sinRespuesta.value = false // R2-2: alguien contestó (también un 5xx)
            val ngrokError = connection.getHeaderField("ngrok-error-code")
            // Matches iOS: any 200–499 means the server (or its proxy) is answering.
            // 5xx and ngrok edge errors (tunnel offline) are still treated as down.
            if (code in 200..499 && ngrokError == null) {
                reportServerSuccess()
            } else {
                Log.d("📡", "Ping got $code (ngrok=$ngrokError) — server still down")
            }
        } catch (e: java.io.IOException) {
            Log.d("📡", "Ping sin respuesta: ${e.message}")
            _sinRespuesta.value = true // R2-2: nadie contestó
        } catch (e: Exception) {
            Log.d("📡", "Ping failed: ${e.message}")
        } finally {
            connection?.disconnect()
        }
    }

    private fun checkCurrentConnectivity(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        /**
         * PURA: ¿la salud la contestó NUESTRA API? Un 200 con JSON cuyo campo `status` es `"ok"` (con o sin espacios, Codex apps r7
         * #4). Un portal cautivo o un proxy devuelven HTML, un 5xx o un JSON sin ese campo.
         */
        internal fun saludContesta(code: Int, contentType: String?, cuerpo: String?): Boolean {
            if (code != 200 || contentType?.lowercase()?.startsWith("application/json") != true || cuerpo == null) return false
            return runCatching {
                val raiz = kotlinx.serialization.json.Json.parseToJsonElement(cuerpo) as? kotlinx.serialization.json.JsonObject
                (raiz?.get("status") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content == "ok"
            }.getOrDefault(false)
        }

        private const val RETRY_INTERVAL_MS = 10_000L
        private const val PING_TIMEOUT_MS = 5_000
    }
}
