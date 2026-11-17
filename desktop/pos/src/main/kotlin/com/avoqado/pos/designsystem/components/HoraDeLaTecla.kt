package com.avoqado.pos.designsystem.components

import androidx.compose.ui.input.key.KeyEvent

/**
 * Reemplazo de escritorio: la hora del evento de AWT (la del sistema al recibir la tecla). En Compose 1.7.3 el evento de
 * AWT viaja en `InternalKeyEvent.nativeEvent`, que es `internal`: se lee por reflexión, como `Teclas.kt` hace con
 * `toComposeEvent`. Si algo falla, la hora de ahora (la de proceso: degrada, nunca truena).
 */
internal fun horaDeLaTeclaMs(e: KeyEvent): Long {
    val nativo = e.nativeKeyEvent
    val awt = nativo as? java.awt.event.KeyEvent
        ?: runCatching { eventoDeAwt?.invoke(nativo) as? java.awt.event.KeyEvent }.getOrNull()
    return awt?.`when` ?: System.currentTimeMillis()
}

private val eventoDeAwt by lazy {
    runCatching { Class.forName("androidx.compose.ui.input.key.InternalKeyEvent").getMethod("getNativeEvent") }.getOrNull()
}
