package com.avoqado.pos.core.data.lan

/**
 * Hub LAN, capa 2 — el ÁRBITRO **contestando**: PURO salvo por el reloj. Desde la 3.5 el socket vive en
 * `TransporteLan` (D1): esto sólo decide.
 *
 * Solo arbitra en el dispositivo electo ([ArbiterElection]). Atiende las 4
 * operaciones de [LeaseProtocol] contra un [LeaseRegistry] en memoria.
 *
 * Espejo EXACTO en avoqado-ios: Services/LAN/LeaseServer.swift.
 *
 * ── Decisiones que importan ────────────────────────────────────────────────
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
    private val lock = Any()

    /** Leases vivos — el coordinador los publica para pintar el plano. */
    fun activeLeases(): List<TableLease> = synchronized(lock) { registry.activeLeases(nowMillis()) }

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
}
