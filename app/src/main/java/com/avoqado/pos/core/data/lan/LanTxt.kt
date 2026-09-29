package com.avoqado.pos.core.data.lan

/**
 * Etapa 3 del KDS (3.5, D1) — el TXT del anuncio mDNS, PURO: se construye y se lee aquí, sin `NsdManager`, para que la
 * prueba corra en la JVM (`LanTxtTest`). Antes vivía inline en `LanDiscovery.onPeerResolved`. Espejo de `LanTxt.swift`.
 *
 * DNS-SD topa cada entrada `clave=valor` en 255 bytes: con cuids de ~25 caracteres caben ~9 estaciones en `kds`, y
 * `KdsPrefs` guarda UNA por venue.
 */
object LanTxt {

    fun construir(
        deviceId: String,
        venueId: String,
        isWired: Boolean,
        bootedAtMillis: Long,
        kdsStations: Set<String>,
        hub: Boolean,
    ): Map<String, String> {
        val txt = linkedMapOf(
            LeaseProtocol.TXT_DEVICE_ID to deviceId,
            LeaseProtocol.TXT_WIRED to if (isWired) "1" else "0",
            LeaseProtocol.TXT_BOOTED_AT to bootedAtMillis.toString(),
            LeaseProtocol.TXT_VENUE_ID to venueId,
            LeaseProtocol.TXT_HUB to if (hub) "1" else "0",
        )
        if (kdsStations.isNotEmpty()) txt[LeaseProtocol.TXT_KDS] = kdsStations.sorted().joinToString(",")
        return txt
    }

    fun estacionesDe(kds: String?): Set<String> =
        kds?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet().orEmpty()

    /**
     * El peer que describe un TXT resuelto, o `null` si no cuenta: sin `did`; de OTRO venue (plazas y food courts
     * comparten WiFi: arbitrar mesas ajenas o mandarles comandas sería catastrófico y silencioso); este mismo aparato
     * (por `did`, no por nombre: el SO renombra a «(2)»); o sin host.
     */
    fun peerDesde(txt: Map<String, String?>, host: String?, port: Int, myDeviceId: String, myVenueId: String): LanPeer? {
        val peerDeviceId = txt[LeaseProtocol.TXT_DEVICE_ID] ?: return null
        val peerVenue = txt[LeaseProtocol.TXT_VENUE_ID]
        if (peerVenue != null && peerVenue != myVenueId) return null
        if (peerDeviceId == myDeviceId) return null
        val h = host ?: return null
        return LanPeer(
            deviceId = peerDeviceId,
            host = h,
            port = port,
            isWired = txt[LeaseProtocol.TXT_WIRED] == "1",
            bootedAtMillis = txt[LeaseProtocol.TXT_BOOTED_AT]?.toLongOrNull() ?: 0L,
            kdsStations = estacionesDe(txt[LeaseProtocol.TXT_KDS]),
            sirveLeases = txt[LeaseProtocol.TXT_HUB] != "0",
        )
    }
}
