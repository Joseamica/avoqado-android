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

    @Synchronized
    fun registrar(intentadas: Set<String>, acusadas: Set<String>) {
        for (s in intentadas) {
            if (s in acusadas) fallos.remove(s) else fallos[s] = (fallos[s] ?: 0) + 1
        }
        _sinAlcance.value = fallos.filterValues { it >= umbral }.keys.toSet()
    }

    @Synchronized
    fun limpiar() {
        fallos.clear()
        _sinAlcance.value = emptySet()
    }
}
