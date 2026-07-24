package com.avoqado.pos.core.data.lan

/**
 * Elección del ÁRBITRO del hub LAN (Fase 3, PREMIUM `OFFLINE_LAN_HUB`).
 *
 * Cuando se cae internet, los POS se descubren en la red local y uno tiene que
 * llevar el registro de leases. La elección es DETERMINISTA a propósito: todos
 * calculan el mismo ganador a partir del mismo conjunto de peers, sin negociar
 * nada. Un protocolo de consenso real (Raft) sería lo correcto para un sistema
 * distribuido general, pero aquí sobra: el árbitro no es fuente de verdad (lo es
 * el server), así que un empate mal resuelto durante unos segundos se paga con
 * un rechazo en cuarentena, no con dinero perdido.
 *
 * Espejo EXACTO en avoqado-ios: Services/LAN/ArbiterElection.swift.
 *
 * Criterios, en orden:
 *  1. CABLEADO antes que WiFi. Toast recomienda lo mismo: el árbitro no debe ser
 *     el equipo que se mueve por el salón y pierde señal.
 *  2. Mayor UPTIME (arrancó antes). Un equipo que lleva horas encendido es mejor
 *     apuesta que uno que acaba de prender — y ya tiene el estado caliente.
 *  3. deviceId más bajo. Desempate puramente léxico para que NUNCA quede
 *     ambiguo: sin esto, dos equipos idénticos podrían elegirse distinto y
 *     habría dos árbitros.
 */

/** Un POS visible en la red local. */
data class LanPeer(
    val deviceId: String,
    val host: String,
    val port: Int,
    /** Ethernet/dock con cable → candidato preferido. */
    val isWired: Boolean = false,
    /** Epoch ms de arranque del dispositivo. Menor = lleva más tiempo vivo. */
    val bootedAtMillis: Long = 0L,
)

object ArbiterElection {

    /**
     * El árbitro para este conjunto de peers. Devuelve null solo si no hay
     * ninguno (sin peers no hay hub: el dispositivo trabaja como isla, que es
     * exactamente el comportamiento de siempre).
     *
     * La lista se ordena entera en vez de buscar el máximo para que el orden
     * sea inspeccionable en pruebas y logs.
     */
    fun pick(peers: List<LanPeer>): LanPeer? = ranked(peers).firstOrNull()

    /** Los peers de mejor a peor candidato. Determinista para el mismo input. */
    fun ranked(peers: List<LanPeer>): List<LanPeer> =
        peers.sortedWith(
            compareByDescending<LanPeer> { it.isWired }
                .thenBy { it.bootedAtMillis }
                .thenBy { it.deviceId },
        )

    /** ¿Me toca a mí ser árbitro? */
    fun isArbiter(myDeviceId: String, peers: List<LanPeer>): Boolean = pick(peers)?.deviceId == myDeviceId
}
