package com.avoqado.pos.core.data.lan

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket

private const val TAG = "LeaseServer"

/**
 * Hub LAN, capa 2 — el ÁRBITRO escuchando en la red local.
 *
 * Solo corre en el dispositivo electo ([ArbiterElection]). Atiende las 4
 * operaciones de [LeaseProtocol] contra un [LeaseRegistry] en memoria.
 *
 * Espejo EXACTO en avoqado-ios: Services/LAN/LeaseServer.swift.
 *
 * ── Decisiones que importan ────────────────────────────────────────────────
 * - Puerto EFÍMERO (0 = que el SO elija). El puerto real se publica en el
 *   anuncio mDNS, así que nadie tiene que adivinarlo y no hay choque si dos
 *   apps conviven en el mismo equipo.
 * - Una conexión = una petición = una respuesta = cerrar. Sin conexiones
 *   persistentes: un POS que se va del área de cobertura no deja sockets
 *   colgados en el árbitro, y el TTL del lease ya cubre la desconexión.
 * - El registro se toca bajo `synchronized`: llegan peticiones de varias
 *   tablets a la vez y el conteo de épocas NO puede correr carreras.
 * - El estado vive en memoria a propósito. Si el árbitro se reinicia, los
 *   leases se pierden y las mesas se liberan solas — que es el mismo efecto
 *   que su TTL. Persistirlos daría la ilusión de una verdad que el server ya
 *   posee de todos modos.
 */
class LeaseServer(
    private val registry: LeaseRegistry = LeaseRegistry(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private val lock = Any()

    /** Puerto real asignado por el SO — es el que se anuncia por mDNS. */
    val port: Int get() = serverSocket?.localPort ?: -1

    val isRunning: Boolean get() = serverSocket?.isClosed == false

    /** Abre el socket y empieza a aceptar. Devuelve el puerto, o -1 si falló. */
    fun start(): Int {
        if (isRunning) return port
        return try {
            val socket = ServerSocket(0) // 0 = puerto efímero
            serverSocket = socket
            val job = SupervisorJob()
            val serverScope = CoroutineScope(Dispatchers.IO + job)
            scope = serverScope
            serverScope.launch { acceptLoop(socket, job) }
            Log.i(TAG, "🛰️ Árbitro escuchando en el puerto ${socket.localPort}")
            socket.localPort
        } catch (e: Exception) {
            Log.e(TAG, "❌ No se pudo abrir el socket del árbitro: ${e.message}")
            -1
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        scope?.coroutineContext?.get(Job)?.cancel()
        serverSocket = null
        scope = null
        Log.i(TAG, "🛑 Árbitro detenido")
    }

    /** Leases vivos — el coordinador los publica para pintar el plano. */
    fun activeLeases(): List<TableLease> = synchronized(lock) { registry.activeLeases(nowMillis()) }

    private suspend fun acceptLoop(socket: ServerSocket, job: Job) {
        while (!socket.isClosed && job.isActive) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (!socket.isClosed) Log.w(TAG, "accept falló: ${e.message}")
                return
            }
            // Cada peticion en su propia corrutina: una tablet lenta no puede
            // bloquear a las demás pidiendo su mesa.
            scope?.launch { handle(client) }
        }
    }

    private fun handle(client: Socket) {
        client.use { sock ->
            runCatching {
                sock.soTimeout = SOCKET_TIMEOUT_MS
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val writer = PrintWriter(sock.getOutputStream(), true)
                val line = reader.readLine() ?: return@runCatching
                writer.println(LeaseProtocol.encode(respondTo(line)))
            }.onFailure { Log.w(TAG, "conexión fallida: ${it.message}") }
        }
    }

    /** Puro salvo por el reloj: es lo que se prueba sin abrir sockets. */
    fun respondTo(line: String): LeaseResponse {
        val request = LeaseProtocol.decodeRequest(line)
            ?: return LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = "Petición ilegible")

        // Un POS con otra versión del protocolo se rechaza EXPLÍCITO. Durante
        // los días que tarda un APK en llegar a todos conviven versiones, y
        // malinterpretar un payload sería peor que negarse.
        if (request.version != LeaseProtocol.PROTOCOL_VERSION) {
            return LeaseResponse(
                status = LeaseProtocol.STATUS_ERROR,
                message = LeaseProtocol.ERROR_VERSION_MISMATCH,
            )
        }

        val now = nowMillis()
        return synchronized(lock) {
            when (request.op) {
                LeaseProtocol.OP_ACQUIRE -> LeaseProtocol.toResponse(
                    registry.acquire(request.tableId, request.deviceId, request.staffId, request.staffName, now),
                )
                LeaseProtocol.OP_RENEW -> LeaseProtocol.toResponse(
                    registry.renew(request.tableId, request.deviceId, request.epoch, now),
                )
                LeaseProtocol.OP_RELEASE -> {
                    val released = registry.release(request.tableId, request.deviceId, request.epoch)
                    LeaseResponse(
                        status = if (released) LeaseProtocol.STATUS_OK else LeaseProtocol.STATUS_STALE,
                        currentEpoch = if (released) null else request.epoch,
                    )
                }
                LeaseProtocol.OP_LIST -> LeaseResponse(
                    status = LeaseProtocol.STATUS_LEASES,
                    leases = registry.activeLeases(now).map { it.toWire() },
                )
                else -> LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = "Operación desconocida: ${request.op}")
            }
        }
    }

    companion object {
        /** Corto a propósito: una petición de lease que tarda más de 3s no
         *  sirve — el mesero ya está esperando frente al cliente. */
        const val SOCKET_TIMEOUT_MS = 3_000
    }
}
