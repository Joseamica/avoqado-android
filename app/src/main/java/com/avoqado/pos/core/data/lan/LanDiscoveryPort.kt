package com.avoqado.pos.core.data.lan

import kotlinx.coroutines.flow.StateFlow

/**
 * Lo que el hub de mesas necesita del transporte único (etapa 3 del KDS, 3.5, D1): los peers que cambian solos, y un
 * enganche para contestar leases desde ESTE aparato. Existe como interfaz para que [LanHubCoordinator] se pruebe SIN
 * `Context` ni red (`FakeDiscovery` en `LanHubCoordinatorTest`). La implementación real es [TransporteLan].
 */
interface LanDiscoveryPort {
    val peers: StateFlow<List<LanPeer>>

    /** Desde aquí el TXT dice `hub=1` y las ops de lease se contestan con [respondTo] (el `LeaseServer` puro). */
    fun conectarHub(respondTo: (String) -> LeaseResponse)

    fun desconectarHub()
}
