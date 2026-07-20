package com.avoqado.pos.settings.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loader GLOBAL de cambios de contexto (sucursal o modo), como Square: el
 * overlay vive arriba del NavHost — sobrevive al rebuild que el propio cambio
 * detona — y bloquea la UI mientras todo recarga. Singleton con scope propio:
 * los ViewModels que lo disparan pueden morir en el rebuild sin dejar el
 * loader atorado.
 */
@Singleton
class VenueSwitchState @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _isSwitching = MutableStateFlow(false)
    val isSwitching: StateFlow<Boolean> = _isSwitching.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    fun begin(message: String) {
        _message.value = message
        _isSwitching.value = true
    }

    fun end() {
        _isSwitching.value = false
    }

    /** Cambios locales instantáneos (modo): pulso corto para dar feedback de recarga. */
    fun pulse(message: String, millis: Long = 700) {
        begin(message)
        scope.launch {
            delay(millis)
            end()
        }
    }
}
