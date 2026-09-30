package com.avoqado.pos.escritorio.e2e

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** app → este proxy → backend local. `cortar()` = WiFi arriba sin internet: cada conexión nueva se cierra al aceptarla. */
class ProxyCortable(private val host: String, private val puertoDestino: Int) : AutoCloseable {
    private val servidor = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val puerto: Int get() = servidor.localPort
    @Volatile private var cortado = false
    private val vivas = ConcurrentHashMap.newKeySet<Socket>()

    init {
        thread(isDaemon = true, name = "proxy-e2e") {
            while (!servidor.isClosed) {
                val cliente = runCatching { servidor.accept() }.getOrNull() ?: break
                if (cortado) { cliente.close(); continue }
                vivas += cliente
                thread(isDaemon = true) {
                    runCatching {
                        Socket(host, puertoDestino).use { destino ->
                            vivas += destino
                            val ida = thread(isDaemon = true) {
                                runCatching { cliente.getInputStream().copyTo(destino.getOutputStream()) }
                                runCatching { destino.shutdownOutput() }
                            }
                            runCatching { destino.getInputStream().copyTo(cliente.getOutputStream()) }
                            ida.join()
                        }
                    }
                    runCatching { cliente.close() }
                }
            }
        }
    }

    fun cortar() { cortado = true; vivas.forEach { runCatching { it.close() } }; vivas.clear() }
    fun restaurar() { cortado = false }
    override fun close() { cortar(); servidor.close() }
}
