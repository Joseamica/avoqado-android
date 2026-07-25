package com.avoqado.pos.core.data.lan

import kotlinx.coroutines.flow.StateFlow

/**
 * Lo que el coordinador necesita del descubrimiento: una lista de peers que
 * cambia sola.
 *
 * Existe como interfaz para que [LanHubCoordinator] se pueda probar SIN un
 * Context de Android ni una red real — la implementación de verdad
 * ([LanDiscovery]) depende de NsdManager, que no corre en tests unitarios.
 *
 * Espejo en iOS: allí el coordinador recibe LanDiscovery directo porque su
 * `@Published peers` ya es sustituible sin protocolo.
 */
interface LanDiscoveryPort {
    val peers: StateFlow<List<LanPeer>>
    fun start(myPort: Int, isWired: Boolean, bootedAtMillis: Long)
    fun stop()
}
