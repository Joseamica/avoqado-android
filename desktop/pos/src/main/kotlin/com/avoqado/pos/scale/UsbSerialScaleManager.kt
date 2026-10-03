package com.avoqado.pos.scale

import android.util.Log
import com.avoqado.pos.areatickets.data.ScaleProfile
import com.avoqado.pos.escritorio.bascula.CanalSerie
import com.avoqado.pos.escritorio.bascula.EleccionDePuerto
import com.avoqado.pos.escritorio.bascula.PuertoSerie
import com.avoqado.pos.escritorio.bascula.RegistroDeWindows
import com.avoqado.pos.escritorio.bascula.abrirCanalSerieDeWindows
import com.avoqado.pos.escritorio.bascula.elegirPuerto
import com.avoqado.pos.escritorio.bascula.puertosSerie
import com.avoqado.pos.pos.data.model.NormalizedScaleReading
import com.avoqado.pos.pos.data.model.ScaleFrameAssembler
import com.avoqado.pos.pos.data.model.ScaleFrameRejection
import com.avoqado.pos.pos.data.model.ScaleProtocol
import com.avoqado.pos.pos.data.model.ScaleStabilityTracker
import com.avoqado.pos.pos.data.model.decodeScaleFrame
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private const val TAG = "UsbSerialScale"

/** Copiado tal cual del original: lo pintan WeightCapturePanel y StockCountingView. */
sealed interface ScaleConnectionState {
    data object NotConfigured : ScaleConnectionState
    data class Connecting(val profileName: String) : ScaleConnectionState
    data class Ready(val profileName: String) : ScaleConnectionState
    data class Unstable(
        val profileName: String,
        val reading: NormalizedScaleReading,
    ) : ScaleConnectionState
    data class Stable(
        val profileName: String,
        val reading: NormalizedScaleReading,
    ) : ScaleConnectionState
    data class Problem(
        val profileName: String,
        val message: String,
    ) : ScaleConnectionState
}

/**
 * Reemplazo de escritorio: la báscula por un PUERTO COM de Windows (adaptador USB-serie o COM de la tarjeta madre) en vez
 * de usb-serial-for-android. Mismo perfil del servidor, mismas validaciones y mensajes, y la MISMA lógica de tramas,
 * estabilidad y consulta que el original (huella en excluidos.txt). Lo que cambia es el transporte: el COM se elige por el
 * VID/PID del perfil (bascula/PuertosDeBascula.kt) y se lee en un hilo propio.
 */
@Singleton
class UsbSerialScaleManager @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ScaleConnectionState>(ScaleConnectionState.NotConfigured)
    val state: StateFlow<ScaleConnectionState> = _state.asStateFlow()

    /** Costuras: los COM vivos y cómo se abre uno (las pruebas ponen unos falsos). */
    internal var puertos: () -> List<PuertoSerie> = {
        if (System.getProperty("os.name").orEmpty().startsWith("Windows")) puertosSerie(RegistroDeWindows) else emptyList()
    }
    internal var abrir: (PuertoSerie, Int, Int, String?, Int?) -> CanalSerie =
        { p, baudios, datos, paridad, parada -> abrirCanalSerieDeWindows(p.nombre, baudios, datos, paridad, parada) }

    /**
     * 🔴 Escritorio: UNA conexión = UN objeto con su perfil, protocolo, canal, armador de tramas y consulta. El lector, la
     * consulta y una apertura tardía sólo publican si su conexión sigue siendo [actual], y la comprobación y el cambio de estado
     * van juntos bajo el candado: una conexión vieja nunca pisa a la nueva (ni su peso, ni su error, ni su «Lista»).
     */
    private inner class Conexion(val profile: ScaleProfile, val protocol: ScaleProtocol, val canal: CanalSerie) {
        val frameAssembler = ScaleFrameAssembler()
        val stabilityTracker = ScaleStabilityTracker()
        @Volatile var consulta: Job? = null
    }

    private val candado = Any()
    private var actual: Conexion? = null   // bajo el candado
    private var generacion = 0             // bajo el candado: sube en cada disconnect

    /** Publica [estado] sólo si [c] sigue siendo la conexión vigente. */
    private fun publicar(c: Conexion, estado: ScaleConnectionState): Boolean =
        synchronized(candado) { (actual === c).also { if (it) _state.value = estado } }

    suspend fun connect(
        profile: ScaleProfile,
        usageContext: ScaleUsageContext = ScaleUsageContext.AREA_TICKET_LINE,
    ) = withContext(Dispatchers.IO) {
        // Cortar lo anterior y tomar MI número en el mismo paso: un disconnect del cajero que llegue después, aunque cerrar el
        // puerto viejo tarde, cambia el número y esta conexión ya no revive.
        val (mia, vieja) = cortar(conectando = profile.name)
        soltar(vieja)
        /** Un problema ANTES de tener conexión: sólo si nadie desconectó mientras tanto. */
        fun fallar(message: String) = synchronized(candado) {
            if (generacion == mia) { _state.value = ScaleConnectionState.Problem(profile.name, message); Log.w(TAG, "${profile.name}: $message") }
        }

        if (!profile.active ||
            profile.transport != "ANDROID_USB_SERIAL" ||
            usageContext.wireValue !in profile.allowedContexts
        ) {
            fallar("Este perfil no está habilitado para ${usageContext.operatorLabel}.")
            return@withContext
        }

        val protocol = ScaleProtocol.fromProfileType(
            profile.frameParser?.get("type")?.jsonPrimitive?.contentOrNull,
        )
        if (protocol == null) {
            fallar("Falta configurar el protocolo certificado de esta báscula.")
            return@withContext
        }

        val puerto = when (val e = elegirPuerto(runCatching { puertos() }.getOrDefault(emptyList()), profile.vendorId, profile.productId)) {
            is EleccionDePuerto.Falla -> { fallar(e.mensaje); return@withContext }
            is EleccionDePuerto.Elegido -> e.puerto
        }

        val abierto = runCatching {
            abrir(puerto, profile.baudRate ?: protocol.defaultBaudRate, profile.dataBits ?: 8, profile.parity, profile.stopBits)
        }.getOrElse { error ->
            fallar(error.message ?: "No se pudo abrir la báscula en ${puerto.nombre}.")
            return@withContext
        }
        // Abrir puede tardar: si mientras tanto se desconectó (el cajero cerró el panel), esta apertura ya no es de nadie. La
        // conexión y su «Lista» se publican JUNTAS: un disconnect no puede caer en medio.
        val c = Conexion(profile, protocol, abierto)
        val vigente = synchronized(candado) {
            (generacion == mia && isActive).also { if (it) { actual = c; _state.value = ScaleConnectionState.Ready(profile.name) } }
        }
        if (!vigente) {
            runCatching { abierto.cerrar() }
            return@withContext
        }
        leer(c)
        startPolling(c)
        Log.i(TAG, "Connected ${profile.name} with ${protocol.profileType} on ${puerto.nombre} for ${usageContext.wireValue}")
    }

    fun disconnect(updateState: Boolean = true) {
        soltar(cortar(if (updateState) ScaleConnectionState.NotConfigured else null).second)
    }

    /** Bajo el candado: nueva generación, deja de haber conexión vigente y (si se pide) el estado nuevo. Devuelve ambas. */
    private fun cortar(estado: ScaleConnectionState?): Pair<Int, Conexion?> = synchronized(candado) {
        generacion++
        estado?.let { _state.value = it }
        generacion to actual.also { actual = null }
    }

    private fun cortar(conectando: String): Pair<Int, Conexion?> = cortar(ScaleConnectionState.Connecting(conectando))

    /** Fuera del candado: cerrar el puerto puede tardar (espera a que la lectura en curso lo suelte). */
    private fun soltar(c: Conexion?) {
        c?.consulta?.cancel()
        c?.let { runCatching { it.canal.cerrar() } }
    }

    /** El hilo que escucha a la báscula (lo que en Android hace SerialInputOutputManager). Sale cuando su conexión deja de ser la vigente. */
    private fun leer(c: Conexion) {
        Thread({
            val buffer = ByteArray(256)
            while (synchronized(candado) { actual === c }) {
                val n = runCatching { c.canal.leer(buffer) }.getOrDefault(-1)
                when {
                    n > 0 -> onNewData(c, buffer.copyOf(n))
                    n < 0 -> { onRunError(c, IllegalStateException("el puerto se cerró")); break }
                }
            }
        }, "bascula-lectura").apply { isDaemon = true; start() }
    }

    private fun onNewData(c: Conexion, data: ByteArray) {
        val text = data.toString(StandardCharsets.US_ASCII)
        val frames = synchronized(c.frameAssembler) {
            c.frameAssembler.append(c.protocol, text)
        }
        frames.forEach { handleFrame(c, it) }
    }

    /** Sólo la conexión vigente se da por perdida: el error de una anterior no toca a la nueva (ni cancela SU consulta). */
    private fun onRunError(c: Conexion, e: Exception) {
        val message = "Se perdió la conexión con la báscula: ${e.message ?: "revisa el cable"}"
        val mia = synchronized(candado) {
            (actual === c).also { if (it) { actual = null; _state.value = ScaleConnectionState.Problem(c.profile.name, message) } }
        }
        if (!mia) return
        Log.w(TAG, "${c.profile.name}: $message")
        c.consulta?.cancel()
        runCatching { c.canal.cerrar() }
    }

    private fun handleFrame(c: Conexion, frame: String) {
        val profile = c.profile
        val protocol = c.protocol
        val decoded = decodeScaleFrame(
            protocol = protocol,
            deviceId = profile.id,
            rawFrame = frame,
        )
        val rawReading = decoded.reading
        if (rawReading != null) {
            val reading = if (protocol.requiresSyntheticStability) {
                c.stabilityTracker.observe(rawReading)
            } else {
                rawReading
            }
            publicar(
                c,
                if (reading.stable) {
                    ScaleConnectionState.Stable(profile.name, reading)
                } else {
                    ScaleConnectionState.Unstable(profile.name, reading)
                },
            )
            return
        }
        when (decoded.rejection) {
            ScaleFrameRejection.OVERLOAD ->
                fail(c, "La báscula reporta sobrecarga o peso fuera de rango.")
            ScaleFrameRejection.NEGATIVE_WEIGHT ->
                fail(c, "La báscula reporta peso negativo. Revisa tara y cero.")
            ScaleFrameRejection.UNSUPPORTED_UNIT ->
                fail(c, "La báscula no está enviando kilogramos.")
            ScaleFrameRejection.EMPTY,
            ScaleFrameRejection.MALFORMED,
            null,
            -> Log.w(TAG, "Ignored frame from ${profile.name}: ${frame.take(80)}")
        }
    }

    private fun startPolling(c: Conexion) {
        val parserMode = c.profile
            .frameParser
            ?.get("mode")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.uppercase()
        if (parserMode == "CONTINUOUS") return
        val command = c.protocol.pollCommand ?: return
        c.consulta = scope.launch {
            while (isActive) {
                val escrito = runCatching { c.canal.escribir(command) }.getOrDefault(false)
                if (!isActive) return@launch   // la escritura bloqueó y mientras tanto se desconectó
                if (!escrito) {
                    onRunError(c, IllegalStateException("El puerto serial ya no está disponible."))
                    return@launch
                }
                delay(c.protocol.pollIntervalMillis)
            }
        }
    }

    private fun fail(c: Conexion, message: String) {
        if (publicar(c, ScaleConnectionState.Problem(c.profile.name, message))) Log.w(TAG, "${c.profile.name}: $message")
    }
}
