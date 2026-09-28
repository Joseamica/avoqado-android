package com.avoqado.pos.core.data.lan

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

private const val TAG = "ClienteDeComandas"

/**
 * Etapa 3 del KDS (3.5, D5) — la caja le empuja UNA comanda a UNA pantalla. Presupuesto propio, más corto que el del
 * hub: conectar ≤ 1 s, total ≤ 1.5 s. `withTimeoutOrNull` no interrumpe una lectura bloqueante, así que el `soTimeout`
 * se fija a lo que QUEDE del presupuesto y el socket se cierra al salir. `false` = sin acuse ⇒ papel (nunca lanza).
 *
 * Espejo de avoqado-ios: Services/LAN/ClienteDeComandas.swift.
 */
class ClienteDeComandas @Inject constructor() {

    suspend fun entregar(peer: LanPeer, mensaje: KdsComanda, presupuestoMs: Long = PRESUPUESTO_MS): Boolean =
        KdsLanProtocol.esAcuse(enviar(peer, KdsLanProtocol.encode(mensaje), presupuestoMs), mensaje.sourceKey)

    /** Una conexión = una línea = una respuesta. `null` = sin respuesta dentro del presupuesto (o ilegible/excesiva). */
    suspend fun enviar(peer: LanPeer, linea: String, presupuestoMs: Long = PRESUPUESTO_MS): String? =
        withTimeoutOrNull(presupuestoMs) {
            val socket = Socket()
            try {
                withContext(Dispatchers.IO) {
                    val inicio = System.currentTimeMillis()
                    socket.connect(InetSocketAddress(peer.host, peer.port), CONEXION_MS)
                    val resto = (presupuestoMs - (System.currentTimeMillis() - inicio)).coerceIn(1, presupuestoMs).toInt()
                    socket.soTimeout = resto
                    socket.getOutputStream().run { write((linea + "\n").toByteArray(Charsets.UTF_8)); flush() }
                    LineaAcotada.leer(BufferedInputStream(socket.getInputStream()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "📴 ${peer.host}:${peer.port} sin acuse (${e.message})")
                null
            } finally {
                runCatching { socket.close() }
            }
        }

    companion object {
        const val CONEXION_MS = 1_000
        const val PRESUPUESTO_MS = 1_500L
    }
}
