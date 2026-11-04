package com.avoqado.pos.core.data.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D22 (3-nov-2026): «el servidor ya confirmó algo de esta sucursal que estaba en la cola del aparato». Un contador por
 * sucursal que SÓLO CRECE (nunca baja ni tiene que llegar a cero): un cobro retenido no esconde a los demás, y quien lo
 * compare por nivel no pierde avisos aunque no estuviera mirando.
 *
 * Lo suben, después de su escritura local, los confirmadores reales (`docs/audit/2026-11-03-d22-recarga-sola/promesa.md`,
 * diseño r2 §A): la cola de cobros, la recuperación por llave, el cobro en línea con fila y el ACK de cualquier operación de
 * la cola. Lo leen Ventas e Informes para recargarse solos. Vive en memoria: un proceso nuevo vuelve a cargar al entrar.
 */
@Singleton
class AvisosDeVentasSubidas @Inject constructor() {
    private val _porSucursal = MutableStateFlow<Map<String, Long>>(emptyMap())
    val porSucursal: StateFlow<Map<String, Long>> = _porSucursal.asStateFlow()

    fun subio(sucursal: String) {
        if (sucursal.isBlank()) return
        _porSucursal.update { it + (sucursal to ((it[sucursal] ?: 0L) + 1)) }
    }

    fun de(sucursal: String): Long = _porSucursal.value[sucursal] ?: 0L
}
