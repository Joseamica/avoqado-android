package com.avoqado.pos.core.data.lan

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject

private const val TAG = "ClienteDeComandas"

/**
 * Etapa 3 del KDS (3.5, D5) — la caja le empuja UNA comanda a UNA pantalla. Presupuesto propio, más corto que el del
 * hub: conectar ≤ 1 s, total ≤ 1.5 s. `false` = sin acuse ⇒ papel (nunca lanza).
 *
 * 🔴 El total lo impone un VIGÍA que cierra el socket al vencer (D5 «+ cierre del socket»), no `soTimeout`: ése sólo
 * acota el silencio de UNA lectura, y una pantalla que gotea un byte cada medio segundo lo esquivaba y retenía el
 * despacho entero (I3 de la revisión; el servidor ya tiene el mismo vigía en `TransporteLan`). Cerrar el socket
 * desbloquea `connect`/`read` con una excepción.
 *
 * Espejo de avoqado-ios: Services/LAN/ClienteDeComandas.swift.
 */
class ClienteDeComandas @Inject constructor() {

    suspend fun entregar(peer: LanPeer, mensaje: KdsComanda, presupuestoMs: Long = PRESUPUESTO_MS): Boolean =
        KdsLanProtocol.esAcuse(enviar(peer, KdsLanProtocol.encode(mensaje), presupuestoMs), mensaje.sourceKey)

    /** Una conexión = una línea = una respuesta. `null` = sin respuesta dentro del presupuesto (o ilegible/excesiva). */
    suspend fun enviar(peer: LanPeer, linea: String, presupuestoMs: Long = PRESUPUESTO_MS): String? = coroutineScope {
        val socket = Socket()
        // En `Dispatchers.IO` a propósito: ahí `delay` es reloj real también dentro de `runTest`. El cierre va en `finally`
        // (N4 de la revisión): si quien llama se cancela, el vigía se cancela CERRANDO — sin eso la lectura seguía colgada.
        val vigia = launch(Dispatchers.IO) {
            try {
                delay(presupuestoMs)
            } finally {
                runCatching { socket.close() }
            }
        }
        try {
            withContext(Dispatchers.IO) {
                socket.connect(InetSocketAddress(peer.host, peer.port), CONEXION_MS)
                socket.soTimeout = presupuestoMs.toInt() // cinturón: el vigía es quien manda
                socket.getOutputStream().run { write((linea + "\n").toByteArray(Charsets.UTF_8)); flush() }
                LineaAcotada.leer(BufferedInputStream(socket.getInputStream()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "📴 ${peer.host}:${peer.port} sin acuse (${e.message})")
            null
        } finally {
            vigia.cancel()
            runCatching { socket.close() }
        }
    }

    companion object {
        const val CONEXION_MS = 1_000
        const val PRESUPUESTO_MS = 1_500L
    }
}
