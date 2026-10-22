package com.avoqado.pos.escritorio

import com.avoqado.pos.R

/**
 * LA tabla id de `R.drawable.*` → archivo del classpath (copiado de ../app/src/main/res por processResources).
 * La usan `painterResource` (pantalla) y `BitmapFactory.decodeResource` (ticket, vía RecursosDeImagen). Un recurso nuevo
 * se agrega SÓLO aquí (y en el `include` de processResources, en build.gradle.kts).
 */
fun rutaDeRecurso(id: Int): String? = when (id) {
    R.drawable.avoqado_logo_mark -> "android-res/drawable-nodpi/avoqado_logo_mark.png"
    R.drawable.ic_whatsapp -> "android-res/drawable/ic_whatsapp.xml"
    R.drawable.avoqado_logo_inline_black -> "android-res/drawable-nodpi/avoqado_logo_inline_black.png"
    R.drawable.avoqado_logo_inline_white -> "android-res/drawable-nodpi/avoqado_logo_inline_white.png"
    else -> null
}
