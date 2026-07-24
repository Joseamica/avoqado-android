package com.avoqado.pos.core.data.lan

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "LeaseClient"

/**
 * Hub LAN, capa 2 — cliente que le pide mesas al árbitro.
 *
 * Espejo EXACTO en avoqado-ios: Services/LAN/LeaseClient.swift.
 *
 * Devuelve `null` cuando NO se pudo hablar con el árbitro (apagado, fuera de
 * cobertura, red caída). Ese null es información, no un error a tragar: el
 * coordinador lo interpreta como "no hay hub" y degrada a MODO ISLA — que es
 * exactamente el comportamiento que ya teníamos antes del hub. Nunca se
 * bloquea al mesero por no encontrar el árbitro.
 */
class LeaseClient(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
) {

    suspend fun acquire(
        arbiter: LanPeer,
        tableId: String,
        deviceId: String,
        staffId: String,
        staffName: String,
    ): LeaseResponse? = send(
        arbiter,
        LeaseRequest(
            op = LeaseProtocol.OP_ACQUIRE,
            tableId = tableId,
            deviceId = deviceId,
            staffId = staffId,
            staffName = staffName,
        ),
    )

    suspend fun renew(arbiter: LanPeer, tableId: String, deviceId: String, epoch: Long): LeaseResponse? =
        send(arbiter, LeaseRequest(op = LeaseProtocol.OP_RENEW, tableId = tableId, deviceId = deviceId, epoch = epoch))

    suspend fun release(arbiter: LanPeer, tableId: String, deviceId: String, epoch: Long): LeaseResponse? =
        send(arbiter, LeaseRequest(op = LeaseProtocol.OP_RELEASE, tableId = tableId, deviceId = deviceId, epoch = epoch))

    suspend fun list(arbiter: LanPeer): LeaseResponse? = send(arbiter, LeaseRequest(op = LeaseProtocol.OP_LIST))

    private suspend fun send(arbiter: LanPeer, request: LeaseRequest): LeaseResponse? = withContext(Dispatchers.IO) {
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(arbiter.host, arbiter.port), connectTimeoutMs)
                socket.soTimeout = LeaseServer.SOCKET_TIMEOUT_MS
                PrintWriter(socket.getOutputStream(), true).println(LeaseProtocol.encode(request))
                val line = BufferedReader(InputStreamReader(socket.getInputStream())).readLine()
                line?.let { LeaseProtocol.decodeResponse(it) }
            }
        }.onFailure {
            // No es un error de negocio: el árbitro no está. Modo isla.
            Log.d(TAG, "📴 Árbitro ${arbiter.host}:${arbiter.port} no responde (${it.message}) — modo isla")
        }.getOrNull()
    }

    companion object {
        /** 1.5s: si el árbitro no contesta en ese tiempo desde la MISMA red
         *  local, no está. Esperar más solo congela la UI del mesero. */
        const val CONNECT_TIMEOUT_MS = 1_500
    }
}
