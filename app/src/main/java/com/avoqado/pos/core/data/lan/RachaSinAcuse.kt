package com.avoqado.pos.core.data.lan

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Etapa 3 del KDS (3.5, D11) — cuántas entregas SEGUIDAS sin acuse lleva cada estación. A la [umbral] (3) la caja lo dice
 * fijo («La pantalla de Barra no se alcanza por el WiFi»), con o sin internet; el primer acuse de esa estación lo limpia.
 * En memoria del transporte: al reiniciar la app se empieza de cero. Espejo de `RachaSinAcuse.swift`.
 */
class RachaSinAcuse(private val umbral: Int = 3) {

    private val fallos = mutableMapOf<String, Int>()
    private val _sinAlcance = MutableStateFlow<Set<String>>(emptySet())
    val sinAlcance: StateFlow<Set<String>> = _sinAlcance.asStateFlow()

    /**
     * D6 de Flautas (opción B, Codex r5 #2 y r6 #1): cuándo acusó por WiFi la ÚLTIMA entrega de cada estación. Una pantalla
     * que acusó hace poco está recibiendo aunque el servidor no la vea (perdió su internet, no el WiFi). Con hora, no un «sí»
     * para siempre: un acuse viejo no tapa una pantalla que se apagó después. Una entrega sin acuse la borra.
     */
    private val ultimoAcuse = mutableMapOf<String, Long>()

    @Synchronized
    fun registrar(intentadas: Set<String>, acusadas: Set<String>, ahora: Long = System.currentTimeMillis()) {
        for (s in intentadas) {
            if (s in acusadas) {
                fallos.remove(s)
                ultimoAcuse[s] = ahora
            } else {
                fallos[s] = (fallos[s] ?: 0) + 1
                ultimoAcuse.remove(s)
            }
        }
        _sinAlcance.value = fallos.filterValues { it >= umbral }.keys.toSet()
    }

    /** Estaciones cuya última entrega por WiFi acusó en o después de [desde] (milisegundos). */
    @Synchronized
    fun acusaronDesde(desde: Long): Set<String> = ultimoAcuse.filterValues { it >= desde }.keys.toSet()

    @Synchronized
    fun limpiar() {
        fallos.clear()
        ultimoAcuse.clear()
        _sinAlcance.value = emptySet()
    }
}
