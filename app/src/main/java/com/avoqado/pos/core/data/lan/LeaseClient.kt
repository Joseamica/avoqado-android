package com.avoqado.pos.core.data.lan

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
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
class LeaseClient {

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

    /** Hub D3: la declaración de esta caja al árbitro, con plazo TOTAL [plazoMs] (conectar, escribir y leer). null = no contestó. */
    suspend fun estado(arbiter: LanPeer, request: LeaseRequest, plazoMs: Long = PLAZO_HUB_MS): LeaseResponse? =
        intentoConVigia(arbiter, request, plazoMs)

    /**
     * D12 · Opción D (R7): una pregunta `cuenta_*` con plazo ABSOLUTO de [plazoTotalMs] — conectar, escribir, leer y el reintento —. Cada
     * intento tiene a lo más [plazoIntentoMs] y un vigía CIERRA el socket al vencer (un `soTimeout` sólo corta un silencio: una respuesta
     * que gotea byte por byte lo burlaba). Un reintento sólo si queda tiempo. null = no contestó. Mesas no usa esto.
     */
    suspend fun preguntarCuenta(
        peer: LanPeer,
        request: LeaseRequest,
        plazoTotalMs: Long = PLAZO_CUENTA_MS,
        plazoIntentoMs: Long = PLAZO_INTENTO_CUENTA_MS,
    ): LeaseResponse? {
        val vence = System.nanoTime() / 1_000_000 + plazoTotalMs
        repeat(2) {
            val queda = vence - System.nanoTime() / 1_000_000
            if (queda <= 0) return null
            intentoConVigia(peer, request, minOf(plazoIntentoMs, queda))?.let { return it }
        }
        return null
    }

    /** D12 · tubería (hz:e5ee98bc:D4): «¿quién eres?» con plazo ABSOLUTO de [PLAZO_QUIEN_MS] (conectar, escribir y leer). null = no contestó. */
    suspend fun quien(peer: LanPeer): LeaseResponse? =
        preguntarCuenta(peer, LeaseRequest(op = LeaseProtocol.OP_QUIEN), PLAZO_QUIEN_MS, PLAZO_QUIEN_MS)

    private suspend fun intentoConVigia(peer: LanPeer, request: LeaseRequest, plazoMs: Long): LeaseResponse? = withContext(Dispatchers.IO) {
        val socket = Socket()
        // Hub D3 (p14d): el vigía va FUERA del trabajo de quien llama. Si lo cancelan a media lectura, la lectura bloqueante sigue, y un
        // vigía cancelado con él dejaba el socket abierto mientras el otro lado goteara. Sólo el `finally` normal lo cancela.
        val vigia = launch(NonCancellable) { delay(plazoMs); runCatching { socket.close() } }
        try {
            runCatching {
                socket.use { s ->
                    s.connect(InetSocketAddress(peer.host, peer.port), plazoMs.toInt().coerceAtLeast(1))
                    s.soTimeout = plazoMs.toInt().coerceAtLeast(1)
                    s.getOutputStream().run { write((LeaseProtocol.encode(request) + "\n").toByteArray(Charsets.UTF_8)); flush() }
                    LineaAcotada.leer(BufferedInputStream(s.getInputStream()))?.let { LeaseProtocol.decodeResponse(it) }
                }
            }.onFailure { Log.d(TAG, "📴 ${peer.host}:${peer.port} no contestó ${request.op} (${it.message}) — modo isla") }.getOrNull()
        } finally {
            vigia.cancel()
        }
    }

    /** acquire/renew/release/list: el mismo plazo TOTAL que `estado` (Hub D3; antes 1.5 s para conectar y 3 s por cada silencio). */
    private suspend fun send(arbiter: LanPeer, request: LeaseRequest): LeaseResponse? = intentoConVigia(arbiter, request, PLAZO_HUB_MS)

    companion object {
        /** Hub D3: lo más que dura un mensaje al árbitro, todo junto (conectar, escribir y leer); el socket se cierra al vencer. */
        const val PLAZO_HUB_MS = 3_000L

        /** Hub D3: la despedida de un coordinador que se apaga no espera más que esto. */
        const val PLAZO_DESPEDIDA_MS = 1_500L

        /** D12 · Opción D (R7): «Avisándole a <caja>…» nunca dura más de esto. */
        const val PLAZO_CUENTA_MS = 5_000L

        /** Cada pregunta espera esto y se reintenta una vez (regla 4 de `opcion-d.md`). */
        const val PLAZO_INTENTO_CUENTA_MS = 2_000L

        /** D12 · tubería: lo más que espera una caja el `quien` de otra (en la misma red, un aparato vivo contesta en milisegundos). */
        const val PLAZO_QUIEN_MS = 1_500L
    }
}
