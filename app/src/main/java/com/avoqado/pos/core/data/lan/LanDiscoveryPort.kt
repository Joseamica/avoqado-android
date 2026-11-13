package com.avoqado.pos.core.data.lan

import kotlinx.coroutines.flow.StateFlow

/**
 * Lo que el hub de mesas necesita del transporte único (etapa 3 del KDS, 3.5, D1): los peers que cambian solos, y un
 * enganche para contestar leases desde ESTE aparato. Existe como interfaz para que [LanHubCoordinator] se pruebe SIN
 * `Context` ni red (`FakeDiscovery` en `LanHubCoordinatorTest`). La implementación real es [TransporteLan].
 */
interface LanDiscoveryPort {
    val peers: StateFlow<List<LanPeer>>

    /**
     * Cuántas veces cambió el árbitro elegido en [peers] (Codex código r2 H2). Sube en CADA publicación que cambia la elección, antes
     * de publicarla, aunque un colector lento sólo vea el último valor (X → Y → X): así una respuesta de la primera X ya no vale.
     */
    val cambiosDeArbitro: Long get() = 0L

    /** Desde aquí el TXT dice `hub=1` y las ops de lease se contestan con [respondTo] (el `LeaseServer` puro). */
    fun conectarHub(respondTo: (String) -> LeaseResponse)

    fun desconectarHub()

    /** D12 · Opción D: desde aquí el TXT dice `d=1` y `caja=<nombre>`, y las ops `cuenta_*` se contestan con [manejador]. */
    fun conectarCuentas(manejador: suspend (String) -> String, nombreCaja: String) {}

    fun desconectarCuentas() {}
}
