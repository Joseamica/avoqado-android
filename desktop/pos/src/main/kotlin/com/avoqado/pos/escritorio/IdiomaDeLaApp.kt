package com.avoqado.pos.escritorio

import java.util.Locale

/**
 * La app está escrita en español; los textos que pone Compose (el calendario de Material3: «Seleccionar fecha», los
 * meses, los días) también deben estarlo. Java toma el idioma de PANTALLA de Windows, que en México muchas veces es
 * inglés con formatos de México. Se conserva el país si Windows ya habla español (es-AR, es-ES…); si no, es-MX.
 */
fun idiomaDeLaApp(pantalla: Locale, formatos: Locale): Locale = when {
    pantalla.language == "es" -> pantalla
    formatos.language == "es" -> formatos
    else -> Locale.forLanguageTag("es-MX")
}

/** Pone la app en español (lo que lee Material3: Locale.getDefault()) SIN tocar los formatos de Windows (números, semana). */
fun aplicarIdiomaDeLaApp() {
    val pantalla = Locale.getDefault(Locale.Category.DISPLAY)
    val formatos = Locale.getDefault(Locale.Category.FORMAT)
    Locale.setDefault(idiomaDeLaApp(pantalla, formatos))
    Locale.setDefault(Locale.Category.FORMAT, formatos)
}
