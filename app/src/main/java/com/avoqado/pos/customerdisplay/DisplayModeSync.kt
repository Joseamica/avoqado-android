package com.avoqado.pos.customerdisplay

/** Qué hacer tras comparar el valor local con el del server. */
internal sealed interface DisplayModeAction {
    data class Adopt(val value: Boolean) : DisplayModeAction
    data class Push(val value: Boolean) : DisplayModeAction
    /** No se llama `Nothing`: ese nombre ya es un tipo de Kotlin y confunde al leer. */
    data object Keep : DisplayModeAction
}

/**
 * Regla de conflicto, PURA. **Manda el APARATO; el server sólo lleva el registro.**
 *
 * 🔴 Antes el server ganaba cuando no había cambios pendientes, y estaba mal por una razón
 * que no es de implementación sino de qué significa este ajuste: "cuál pantalla mira el
 * cliente" describe cómo está ATORNILLADA ESTA D3 a ESTE mostrador. Dos aparatos del mismo
 * negocio pueden estar montados al revés entre sí, y el server guarda UN valor por negocio.
 *
 * Con la regla vieja, invertir en el aparato A empujaba `true` al negocio, y el aparato B
 * —montado normal y sin nada pendiente— lo ADOPTABA y volteaba sus pantallas solo. Un
 * mostrador reconfigurando otro, sin que nadie lo tocara.
 *
 * Medido el 2026-08-25 en la D3: se puso el valor local en `true` y el siguiente arranque
 * lo devolvió a `false` porque el server decía `false`. El interruptor peleaba con el
 * servidor y el resultado dependía de quién llegara primero.
 *
 * Ahora: el aparato NUNCA cambia por lo que diga el server. Si difieren, se EMPUJA el valor
 * del aparato para que el registro del negocio se ponga al día — sirve para soporte y para
 * que el dashboard muestre la verdad, pero no manda.
 *
 * `server == null` (servidor viejo que no conoce el campo) sigue sin cambiar nada.
 */
internal fun reconcileDisplayMode(
    local: Boolean,
    dirty: Boolean,
    server: Boolean?,
): DisplayModeAction = when {
    dirty -> DisplayModeAction.Push(local)
    server == null -> DisplayModeAction.Keep
    server == local -> DisplayModeAction.Keep
    // Difieren y no hay nada pendiente: gana el aparato y el server se pone al día.
    else -> DisplayModeAction.Push(local)
}
