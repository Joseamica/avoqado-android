package com.avoqado.pos.settings.domain

import android.content.Context

/**
 * Qué entradas del menú «Más» decidió esconder este aparato.
 *
 * 🔴 Existía desde antes, pero NADIE la leía: `CustomizeMenuSheet` guardaba once preferencias en
 * `avoqado_menu_prefs` y ningún otro archivo del repo las consultaba. O sea que «Personalizar
 * menú» era un botón que decía haber hecho algo y no hacía nada — el mismo defecto de
 * «Complementos». Se conectó el 2026-09-18.
 *
 * 🔴 Y de paso se alinearon las CLAVES con iOS, que sí consumía las suyas (`isVisible(...)`, 14
 * usos en `MainTabView.swift`). Antes no coincidía ni una: Android decía `menu_cashdrawer` y
 * `menu_timeclock` donde iOS dice `cash_drawer` y `time_clock`. Renombrarlas no rompió nada
 * precisamente porque en Android nadie las leía; ahora las dos apps hablan de lo mismo con el
 * mismo nombre, como la regla del workspace pide para permisos y códigos de función.
 *
 * Sigue siendo un ajuste **por aparato**: cada tablet esconde lo suyo y el servidor no se entera.
 * Eso es deliberado —es preferencia de quien usa ESE aparato, no una regla del negocio— y por eso
 * no comparte carril con los ajustes de `VenueSettings`.
 *
 * ⚠️ Una entrada SIN clave aquí no se puede esconder: se muestra siempre. Es el lado seguro —
 * esconder por accidente algo que nadie puede recuperar es peor que ofrecer una opción de menos.
 */
object PreferenciasDelMenu {

    const val PREFS = "avoqado_menu_prefs"

    /** Espejo EXACTO de las claves de iOS (`MainTabView.swift`). No inventar variantes. */
    const val PEDIDOS = "orders"
    const val ARTICULOS = "articles"
    const val CLIENTES = "customers"
    const val PRESUPUESTOS = "estimates"
    const val RESERVACIONES = "reservations"
    const val CAJA = "cash_drawer"
    const val INFORMES = "reports"
    const val RELOJ_CHECADOR = "time_clock"
    const val PANTALLA_DE_COCINA = "kds"
    const val ATENCION_AL_CLIENTE = "support"

    /** Las que el comerciante puede esconder, en el orden en que se le ofrecen. */
    val configurables: List<Pair<String, String>> = listOf(
        PEDIDOS to "Pedidos",
        ARTICULOS to "Artículos",
        CLIENTES to "Clientes",
        PRESUPUESTOS to "Presupuestos",
        RESERVACIONES to "Reservaciones",
        CAJA to "Caja",
        INFORMES to "Informes",
        RELOJ_CHECADOR to "Reloj checador",
        PANTALLA_DE_COCINA to "Pantalla de cocina",
        ATENCION_AL_CLIENTE to "Atención al cliente",
    )

    /**
     * ¿Se muestra [clave]? Todo visible por defecto: una preferencia que nunca se guardó no puede
     * esconder nada.
     */
    fun visible(context: Context, clave: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(clave, true)
}
