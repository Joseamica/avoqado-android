package com.avoqado.pos.onlineorders.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * D661 · Ruling 48: ¿una ventana de la caja es la ACTIVA del sistema (la que tiene el foco)? Sólo decide si el vigilante le dice al
 * SERVIDOR «aquí hay alguien que ve y oye los pagados en línea» (`x-online-orders-card-alert`); no cambia el sonido, la banda ni el
 * sondeo. Aparte de [VentanaALaVista], que es de D662.
 *
 * - ANDROID: siempre `true`. Nadie la publica: al frente = visible (Ruling 35).
 * - WINDOWS: `Main.kt` publica si la ventana activa del sistema es de este programa (la caja o uno de sus diálogos). Tapada por otro
 *   programa (un navegador maximizado), `false`. Ruling 78: `Arranque.iniciar` la pone en `false` antes de registrar el vigilante (la
 *   ventana todavía no existe) y la prende la ventana en cuanto es la activa; al volver, el vigilante lee ya ([VigilanteDePedidosEnLinea]).
 */
object VentanaActiva {
    private val _activa = MutableStateFlow(true)
    val activa: StateFlow<Boolean> = _activa.asStateFlow()

    fun publicar(activa: Boolean) { _activa.value = activa }
}
