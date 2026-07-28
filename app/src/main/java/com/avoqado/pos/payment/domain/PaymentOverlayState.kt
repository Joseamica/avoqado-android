package com.avoqado.pos.payment.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ¿Hay un cobro abierto encima de todo?
 *
 * El cobro de mesas vive en un Dialog, cuya ventana nace más chica que la
 * pantalla (1920x972 en la Sunmi): por esos ~108px de abajo asomaba el tab bar
 * de la app DEBAJO del cobro, y era tocable — el mesero podía irse a
 * "Inventario" a media transacción, con el cliente enfrente.
 *
 * En vez de pelear con el tamaño de la ventana del Dialog (probados sin éxito
 * `setLayout(MATCH_PARENT)` y `FLAG_LAYOUT_NO_LIMITS`), se oculta lo que asoma.
 * Square resuelve esto usando una pantalla dedicada en vez de un diálogo; esto
 * consigue el mismo efecto visible sin tocar el camino del dinero.
 */
object PaymentOverlayState {
    private val _isPaying = MutableStateFlow(false)
    val isPaying: StateFlow<Boolean> = _isPaying.asStateFlow()

    fun setPaying(value: Boolean) {
        _isPaying.value = value
    }
}
