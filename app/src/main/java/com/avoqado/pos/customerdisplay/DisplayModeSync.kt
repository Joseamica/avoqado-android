package com.avoqado.pos.customerdisplay

/** Qué hacer tras comparar el valor local con el del server. */
internal sealed interface DisplayModeAction {
    data class Adopt(val value: Boolean) : DisplayModeAction
    data class Push(val value: Boolean) : DisplayModeAction
    /** No se llama `Nothing`: ese nombre ya es un tipo de Kotlin y confunde al leer. */
    data object Keep : DisplayModeAction
}

/**
 * Regla de conflicto, PURA.
 *
 * El valor local manda mientras haya un cambio sin confirmar (`dirty`): así un
 * equipo sin internet que acaba de invertir sus pantallas no las ve regresar
 * solas. Cuando no hay nada pendiente, el server manda. Un `server == null`
 * (servidor viejo que no conoce el campo) NO cambia nada.
 */
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
