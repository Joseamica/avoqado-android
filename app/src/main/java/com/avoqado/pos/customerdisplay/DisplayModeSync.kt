package com.avoqado.pos.customerdisplay

/** Qué hacer tras comparar el valor local con el del server. */
internal sealed interface DisplayModeAction {
    data class Adopt(val value: Boolean) : DisplayModeAction
    data class Push(val value: Boolean) : DisplayModeAction
    /** No se llama `Nothing`: ese nombre ya es un tipo de Kotlin y confunde al leer. */
    data object Keep : DisplayModeAction
}

/** Reconciliación legacy, usada sólo cuando no existe journal remoto in-flight. */
internal fun reconcileDisplayMode(
    local: Boolean,
    dirty: Boolean,
    server: Boolean?,
): DisplayModeAction = when {
    dirty -> DisplayModeAction.Push(local)
    server == null -> DisplayModeAction.Keep
    server == local -> DisplayModeAction.Keep
    else -> DisplayModeAction.Adopt(server)
}
