package com.avoqado.pos.core.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * La Activity detrás de un `Context` de Compose.
 *
 * 🔴 `LocalContext.current` casi nunca ES la Activity: Compose entrega un
 * `ContextWrapper` (el del tema, el de la vista…), así que un `context as?
 * Activity` devuelve null y el botón que dependía de él "no hace nada" — sin
 * error, sin log, sin explicación. Desenvolver la cadena es la diferencia entre
 * un interruptor que funciona y uno que el cajero cree roto.
 */
fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
