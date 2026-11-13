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
 * - Hub D3 (mesa declarada): `estado` lo decide [Presencias], bajo este mismo `lock` y con el reloj MONOTÓNICO
 *   ([monoMillis]); la hora de pared ([nowMillis]) sólo sigue en los `expiresAtMillis` de los permisos viejos.
 * - Convive con apps viejas en el mismo árbitro: cada `estado` aceptado borra los permisos viejos de ese aparato (adopción) y, mientras
 *   esté «en presencia», sus acquire/renew/release son `error`. El acquire viejo de una mesa que declara una caja nueva viva se niega,
 *   y el permiso viejo vivo de otro aparato ocupa la mesa para `estado`. `list` junta las dos fuentes, una vez por mesa.
 */
class LeaseServer(
    private val registry: LeaseRegistry = LeaseRegistry(),
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    /** Va en `arbitro` de cada respuesta a `estado`: la caja descarta lo que conteste otro árbitro. */
    private val deviceId: String = "",
    private val monoMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val lock = Any()
    private val presencias = Presencias()

    /** Leases vivos — el coordinador los publica para pintar el plano. */
    fun activeLeases(): List<TableLease> = synchronized(lock) { registry.activeLeases(nowMillis()) }

    /** Esta tablet empieza a arbitrar: lo que sabía de antes ya no vale y las aperturas esperan a oír a [esperados] (o 12 s). */
    fun asumirArbitraje(esperados: Set<String>) {
        synchronized(lock) { presencias.asumirArbitraje(esperados, monoMillis()) }
    }

    /** Deja de arbitrar: lo concedido antes deja de valer y toda apertura contesta «esperar» 1 s. */
    fun dejarArbitraje() {
        synchronized(lock) { presencias.dejarArbitraje() }
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
            val mono = monoMillis()
            // Adopción (D3): un aparato que ya declara su estado no habla las ops viejas; un acquire suyo que llegue tarde no revive nada.
            val opVieja = request.op == LeaseProtocol.OP_ACQUIRE || request.op == LeaseProtocol.OP_RENEW || request.op == LeaseProtocol.OP_RELEASE
            if (opVieja && presencias.enPresencia(request.deviceId, mono)) {
                return@synchronized LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = "en presencia")
            }
            when (request.op) {
                // App vieja: se le niega la mesa que declara una caja nueva viva; si no, el registro de siempre.
                LeaseProtocol.OP_ACQUIRE -> presencias.comoPermiso(request.tableId, request.deviceId, mono, now)
                    ?.let { LeaseResponse(status = LeaseProtocol.STATUS_DENIED, holder = it) }
                    ?: LeaseProtocol.toResponse(
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
                // Una entrada por mesa: primero los permisos viejos vivos, luego las mesas que sólo declaran presencias vivas.
                LeaseProtocol.OP_LIST -> {
                    val viejos = registry.activeLeases(now).map { it.toWire() }
                    LeaseResponse(
                        status = LeaseProtocol.STATUS_LEASES,
                        leases = viejos + presencias.comoPermisos(mono, now, viejos.mapTo(HashSet()) { it.tableId }),
                    )
                }
                LeaseProtocol.OP_ESTADO -> presencias.estado(request, mono, deviceId, registry.activeLeases(now)).also {
                    if (it.status == LeaseProtocol.STATUS_OK) registry.soltarDe(request.deviceId)
                }
                else -> LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = "Operación desconocida: ${request.op}")
            }
        }
    }
}
