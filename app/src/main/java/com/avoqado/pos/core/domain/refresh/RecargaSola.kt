package com.avoqado.pos.core.domain.refresh

import com.avoqado.pos.core.data.sync.AvisosDeVentasSubidas
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * D22 (diseño r2 §D, r3 y r4): atiende la [Necesidad] de una pantalla MIENTRAS SE VE. Quien la llama la corre dentro de
 * `repeatOnLifecycle(RESUMED)` y, en WINDOWS, de `VentanaALaVista.aLaVista.collectLatest`: dejar de verse la cancela, y
 * con ella su carga y su HTTP. Lo que pidió el cajero no pasa por aquí y no se corta.
 *
 * Cada vuelta carga sólo si hace falta, hay red del aparato (`isConnected`; la bandera del servidor no se usa: nuestro propio
 * fallo la apaga), no hay otra carga ni la pantalla está ocupada, ya pasó la espera tras fallos y, si es sólo por avisos,
 * ya se agruparon: 1 s después del último, y como mucho 2 s después del primero pendiente.
 */
object RecargaSola {
    const val ESPERA_MAXIMA_MS = 30_000L
    const val AGRUPAR_MS = 1_000L
    const val AGRUPAR_COMO_MUCHO_MS = 2_000L

    suspend fun atender(
        necesidad: StateFlow<Necesidad>,
        avisos: AvisosDeVentasSubidas,
        conRed: StateFlow<Boolean>,
        /** Lo que hace cambiar [ocupada] o [puedeSola] (cargas en curso, búsqueda, selector, páginas): despierta la vuelta. */
        otras: Flow<Any?>,
        sucursal: () -> String?,
        ocupada: () -> Boolean,
        puedeSola: () -> Boolean,
        reloj: RelojDeRecarga,
        recargar: suspend () -> Unit,
    ): Unit = coroutineScope {
        // Lo que cambia sólo DESPIERTA la vuelta; lo que se decide se lee en vivo. Una foto de `combine` puede venir atrasada:
        // tras una carga, el «cargando» que vio a medio camino la despertaba antes de que llegara lo que dejó la carga, y con
        // esa foto vieja volvía a pedir (pruebas N3 y r4-2).
        val despertador = combine(necesidad, avisos.porSucursal, conRed, otras) { n, a, r, o -> listOf(n, a, r, o) }.stateIn(this)
        var primerAvisoPendienteEn: Long? = null
        var avisosVistos = Long.MIN_VALUE
        var avisosCambiaronEn = 0L
        while (true) {
            val visto = despertador.value
            val ahora = reloj.ahora()
            val suc = sucursal()
            val cuenta = suc?.let { avisos.de(it) } ?: 0L
            if (cuenta != avisosVistos) {
                avisosVistos = cuenta
                avisosCambiaronEn = ahora
            }
            val n = necesidad.value
            if (suc == null || !n.haceFalta(suc, cuenta)) {
                primerAvisoPendienteEn = null
                esperarCambio(despertador, visto, null)
                continue
            }
            if (!conRed.value || ocupada() || !puedeSola()) {
                esperarCambio(despertador, visto, ESPERA_MAXIMA_MS)
                continue
            }
            val faltaDeLaEspera = n.proximoIntentoEn - ahora
            if (n.fallosSeguidos > 0 && faltaDeLaEspera > 0) {
                esperarCambio(despertador, visto, faltaDeLaEspera)
                continue
            }
            if (!n.porFallo()) {
                val primero = primerAvisoPendienteEn ?: ahora.also { primerAvisoPendienteEn = it }
                val falta = minOf(avisosCambiaronEn + AGRUPAR_MS, primero + AGRUPAR_COMO_MUCHO_MS) - ahora
                if (falta > 0) {
                    esperarCambio(despertador, visto, falta)
                    continue
                }
            }
            primerAvisoPendienteEn = null
            recargar()
            // Lo que dejó la carga llega por las entradas; si nada cambió (otra carga la precedió), no se insiste antes de 30 s.
            esperarCambio(despertador, visto, ESPERA_MAXIMA_MS)
        }
    }

    private suspend fun <T> esperarCambio(entradas: StateFlow<T>, vistas: T, hastaMs: Long?) {
        if (hastaMs == null) entradas.first { it != vistas } else withTimeoutOrNull(hastaMs) { entradas.first { it != vistas } }
    }
}
