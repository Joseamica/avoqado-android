package com.avoqado.pos.maquina

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

enum class Conexion { DESCONECTADA, CONECTANDO, CONECTADA }

/**
 * Un mensaje de la máquina con la [generacion] de la conexión que lo recibió (no la vigente al leerlo) y su texto [crudo]
 * tal cual llegó: es la evidencia que se guarda cuando un cierre no cuadra.
 */
data class Recibido(val generacion: Int, val mensaje: MensajeDeLaMaquina, val crudo: String)

/** Lo que el cobro ([CobroConMaquina]) necesita del cliente WebSocket; las pruebas ponen uno falso. */
interface CanalDeLaMaquina {
    val conexion: StateFlow<Conexion>
    val generacion: StateFlow<Int>
    val recibidos: SharedFlow<Recibido>

    /** Manda [json] sólo si la conexión abierta es la de [generacion]. false = no salió nada. */
    fun enviar(json: String, generacion: Int): Boolean

    /**
     * R4-6 (Codex código r1 #3): manda [json] (un `cancel`, que no lleva folio) sólo si, ATÓMICAMENTE con lo que llega, la conexión
     * abierta es la de [generacion] y el ÚLTIMO `status` que llegó por ella es de [referenceCC] y [dueCents] y sigue cobrando. Un
     * `status` de otra operación (o el cierre del nuestro) que llegó mientras la PC guardaba el modo ya no deja salir nada.
     */
    fun enviarSiSigueSiendoNuestro(json: String, generacion: Int, referenceCC: String, dueCents: Int): Boolean

    /** C5: conectar a la máquina de [ip] (o reconectar a otra). Un canal de pruebas no hace nada. */
    fun conectar(ip: String) {}

    fun desconectar() {}
}

/**
 * El único que le habla a la QuickPay por `ws://<ip>:8080/` (manual 2.1). Reconecta cada [reconexionMs] y manda `ping`
 * cada [pingMs]; si en tres intervalos seguidos no oye nada, da la conexión por muerta y reconecta.
 *
 * Cada conexión nueva sube la [generacion] (nunca baja, ni al reconectar ni al cambiar de IP). La propiedad de un cobro
 * se gana en una generación, y [enviar] sólo sale por la conexión abierta de ESA generación (R3#2): un `cancel` decidido
 * con la conexión vieja nunca sale por la nueva.
 *
 * [recibidos] no repite lo pasado: hay que suscribirse antes de [conectar]. No tiene tope, para que un `status` de
 * cierre no se pierda porque quien escucha va lento (la máquina manda pocos mensajes por segundo).
 */
class ClienteDeLaMaquina(
    private val okHttp: OkHttpClient,
    private val scope: CoroutineScope,
    private val reloj: () -> Long,
    private val reconexionMs: Long = 3_000,
    private val pingMs: Long = 10_000,
) : CanalDeLaMaquina {
    private val _conexion = MutableStateFlow(Conexion.DESCONECTADA)
    override val conexion: StateFlow<Conexion> = _conexion.asStateFlow()

    private val _generacion = MutableStateFlow(0)
    override val generacion: StateFlow<Int> = _generacion.asStateFlow()

    private val _recibidos = MutableSharedFlow<Recibido>(extraBufferCapacity = Int.MAX_VALUE)
    override val recibidos: SharedFlow<Recibido> = _recibidos.asSharedFlow()

    // Todo lo de abajo cambia sólo con el candado.
    private val candado = Any()
    private var trabajo: Job? = null
    private var intento: WebSocket? = null          // la conexión vigente, abierta o abriéndose
    private var socketAbierto: WebSocket? = null    // la vigente ya abierta…
    private var generacionAbierta = 0               // …y su generación
    private var ultimoStatus: Recibido? = null      // el último `status` que llegó (de cualquier generación)

    override fun conectar(ip: String) = conectar(ip, PUERTO)

    /** Conecta (o reconecta a otra IP). Lanza IllegalArgumentException si [ip] no es un host válido. */
    fun conectar(ip: String, puerto: Int) {
        val url = HttpUrl.Builder().scheme("http").host(ip).port(puerto).build()   // OkHttp lo abre como ws://
        synchronized(candado) {
            desconectar()
            trabajo = scope.launch { mantener(url) }
        }
    }

    override fun desconectar() {
        synchronized(candado) {
            trabajo?.cancel()
            trabajo = null
            intento?.cancel()
            intento = null
            socketAbierto = null
            _conexion.value = Conexion.DESCONECTADA
        }
    }

    override fun enviar(json: String, generacion: Int): Boolean = synchronized(candado) {
        val socket = socketAbierto
        socket != null && generacionAbierta == generacion && socket.send(json)
    }

    override fun enviarSiSigueSiendoNuestro(json: String, generacion: Int, referenceCC: String, dueCents: Int): Boolean =
        synchronized(candado) {
            val ultimo = ultimoStatus
            val m = ultimo?.mensaje as? MensajeDeLaMaquina.Status
            ultimo?.generacion == generacion && m != null && m.cobrando && m.referenceCC == referenceCC && m.dueCents == dueCents &&
                enviar(json, generacion)
        }

    private suspend fun mantener(url: HttpUrl) {
        while (true) {
            val contexto = currentCoroutineContext()
            val cerrado = CompletableDeferred<Unit>()
            val (oyente, socket) = synchronized(candado) {
                contexto.ensureActive()   // desconectar() cancela con el candado: no abre una conexión huérfana
                val nuevo = Oyente(_generacion.value + 1, cerrado)
                val ws = okHttp.newWebSocket(Request.Builder().url(url).build(), nuevo)
                intento = ws
                _generacion.value = nuevo.generacion
                _conexion.value = Conexion.CONECTANDO
                nuevo to ws
            }
            try {
                vigilar(oyente, cerrado)
            } finally {
                socket.cancel()
                soltar(socket)
            }
            delay(reconexionMs)
        }
    }

    /** Hasta que se cierre: un `ping` por intervalo; tres intervalos sin oír nada = conexión muerta. */
    private suspend fun vigilar(oyente: Oyente, cerrado: CompletableDeferred<Unit>) {
        var intervalosSinOir = 0
        while (withTimeoutOrNull(pingMs) { cerrado.await() } == null) {
            if (!oyente.abierto) continue   // abriéndose: el timeout de OkHttp lo termina
            if (oyente.oyo.getAndSet(false)) intervalosSinOir = 0 else intervalosSinOir++
            if (intervalosSinOir >= INTERVALOS_SIN_OIR) return
            enviar(ping(Instant.ofEpochMilli(reloj()).toString()), oyente.generacion)
        }
    }

    private fun soltar(socket: WebSocket) {
        synchronized(candado) {
            if (socketAbierto === socket) socketAbierto = null
            if (intento === socket) {
                intento = null
                _conexion.value = Conexion.DESCONECTADA
            }
        }
    }

    private inner class Oyente(val generacion: Int, private val cerrado: CompletableDeferred<Unit>) : WebSocketListener() {
        @Volatile var abierto = false
        val oyo = AtomicBoolean(false)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(candado) {
                if (intento !== webSocket) return   // ya la reemplazó otra o se desconectó
                abierto = true
                socketAbierto = webSocket
                generacionAbierta = generacion
                _conexion.value = Conexion.CONECTADA
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            oyo.set(true)
            val r = leerMensaje(text)?.let { Recibido(generacion, it, text) } ?: return
            // Con el candado y ANTES de avisar: `enviarSiSigueSiendoNuestro` ve este `status` aunque nadie lo haya procesado.
            if (r.mensaje is MensajeDeLaMaquina.Status) synchronized(candado) { ultimoStatus = r }
            _recibidos.tryEmit(r)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            termino(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = termino(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = termino(webSocket)

        private fun termino(webSocket: WebSocket) {
            soltar(webSocket)
            cerrado.complete(Unit)
        }
    }

    private companion object {
        const val PUERTO = 8080
        const val INTERVALOS_SIN_OIR = 3
    }
}
