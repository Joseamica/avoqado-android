package com.avoqado.pos.core.data.lan

/**
 * Etapa 3 del KDS (3.5, D1) — el ruteo por `op` del transporte único, PURO. Espejo de `EnrutadorLan.swift`.
 * `comanda` ⇒ el receptor de cocina (si hay Tablero en pantalla); lease ops ⇒ el hub (si está encendido AQUÍ); lo
 * demás «Operación desconocida». La pantalla rechaza —sin acuse— `venueId` ajeno y `stationId` que no anuncia (D4).
 */
object EnrutadorLan {

    suspend fun responder(
        linea: String,
        venueId: String?,
        estaciones: Set<String>,
        hub: ((String) -> LeaseResponse)?,
        receptor: (suspend (KdsComanda) -> Boolean)?,
        preparation: (suspend (com.avoqado.pos.kds.domain.PreparationPeerProgress) -> Boolean)? = null,
        preparationDisplay: ((com.avoqado.pos.kds.domain.PreparationPeerProgress) -> Set<String>)? = null,
    ): String {
        val op = KdsLanProtocol.opDe(linea)
            ?: return LeaseProtocol.encode(LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = "Petición ilegible"))
        if (op == "preparation_progress") {
            val protocol = com.avoqado.pos.kds.domain.PreparationPeerProtocol
            val command = runCatching { protocol.json.decodeFromString<com.avoqado.pos.kds.domain.PreparationPeerProgress>(linea) }.getOrNull()
                ?: return rechazo("Petición ilegible")
            val accepted = command.venueId == venueId && preparation != null && runCatching { command.validate(); preparation(command) }.getOrDefault(false)
            return protocol.json.encodeToString(com.avoqado.pos.kds.domain.PreparationPeerAck.serializer(),
                com.avoqado.pos.kds.domain.PreparationPeerAck(version = command.version, status = if (accepted) "ok" else "error", deliveryId = command.deliveryId,
                    phase = command.phase, message = if (accepted) null else "No se pudo guardar el avance. Revisa el producto.",
                    displayStations = if (accepted && receptor != null) preparationDisplay?.invoke(command)?.intersect(estaciones)?.toList().orEmpty() else emptyList()))
        }
        if (op == KdsLanProtocol.OP_COMANDA) {
            val r = receptor ?: return rechazo("Operación desconocida: comanda")
            val comanda = KdsLanProtocol.decodeComanda(linea) ?: return rechazo("Petición ilegible")
            if (comanda.version != LeaseProtocol.PROTOCOL_VERSION) return rechazo(LeaseProtocol.ERROR_VERSION_MISMATCH)
            if (comanda.preparationVersion !in 0..1) return rechazo("Versión de preparación incompatible")
            if (comanda.venueId != venueId) return rechazo("Venue ajeno")
            if (comanda.stationId !in estaciones) return rechazo("Estación no anunciada")
            // 🔴 El receptor GUARDA (commit en disco) y sólo entonces se acusa (D8): «no pude guardar» = sin acuse ⇒ papel.
            return if (r(comanda)) KdsLanProtocol.encode(KdsLanProtocol.acuse(comanda.sourceKey, comanda.preparationVersion)) else rechazo("No se pudo guardar")
        }
        val h = hub ?: return LeaseProtocol.encode(LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = "Operación desconocida: $op"))
        return LeaseProtocol.encode(h(linea))
    }

    private fun rechazo(mensaje: String): String = KdsLanProtocol.encode(KdsLanProtocol.rechazo(mensaje))
}
