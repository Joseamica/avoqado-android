package com.avoqado.pos.navigation

/**
 * Qué pestaña marca la barra de abajo y qué hace tocarla.
 *
 * Una pantalla que se abre DESDE una pestaña sin ser pestaña (lista de espera, «Mi clase ahora», ajustes
 * del calendario) no aparece en la jerarquía de ninguna. Antes la barra de tableta caía en la pestaña de
 * INICIO —«Calendario» en un negocio de reservas— aunque el cajero viniera de «Más», y la de teléfono no
 * marcaba ninguna. Y tocar «Más» no la cerraba: `navigateToTab` guarda y RESTAURA la pila de la pestaña,
 * con la pantalla encima.
 */
internal object PestanaDeLaBarra {

    /** La pestaña de la ruta actual; si la ruta no es de ninguna, la última pestaña real en la que estuvo. */
    fun marcada(deLaRuta: MainTab?, ultima: MainTab, visibles: List<MainTab>, inicio: MainTab): MainTab =
        deLaRuta ?: ultima.takeIf { it in visibles } ?: inicio

    enum class AlTocar { NAVEGAR, VOLVER_A_SU_RAIZ }

    /** Tocar la pestaña ya marcada con una pantalla encima regresa a su raíz en vez de restaurar la pila. */
    fun alTocar(tocada: MainTab, marcada: MainTab, enUnaPestana: Boolean): AlTocar =
        if (tocada == marcada && !enUnaPestana) AlTocar.VOLVER_A_SU_RAIZ else AlTocar.NAVEGAR
}
