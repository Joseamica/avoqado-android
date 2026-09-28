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
    ): String {
        val op = KdsLanProtocol.opDe(linea)
            ?: return LeaseProtocol.encode(LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = "Petición ilegible"))
        if (op == KdsLanProtocol.OP_COMANDA) {
            val r = receptor ?: return rechazo("Operación desconocida: comanda")
            val comanda = KdsLanProtocol.decodeComanda(linea) ?: return rechazo("Petición ilegible")
            if (comanda.version != LeaseProtocol.PROTOCOL_VERSION) return rechazo(LeaseProtocol.ERROR_VERSION_MISMATCH)
            if (comanda.venueId != venueId) return rechazo("Venue ajeno")
            if (comanda.stationId !in estaciones) return rechazo("Estación no anunciada")
            // 🔴 El receptor GUARDA (commit en disco) y sólo entonces se acusa (D8): «no pude guardar» = sin acuse ⇒ papel.
            return if (r(comanda)) KdsLanProtocol.encode(KdsLanProtocol.acuse(comanda.sourceKey)) else rechazo("No se pudo guardar")
        }
        val h = hub ?: return LeaseProtocol.encode(LeaseResponse(status = LeaseProtocol.STATUS_ERROR, message = "Operación desconocida: $op"))
        return LeaseProtocol.encode(h(linea))
    }

    private fun rechazo(mensaje: String): String = KdsLanProtocol.encode(KdsLanProtocol.rechazo(mensaje))
}
