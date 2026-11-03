package com.avoqado.pos.transactions.presentation

import com.avoqado.pos.core.data.network.FalloDeLectura
import com.avoqado.pos.designsystem.components.TextosDeLecturaFallida

/** Qué enseña la lista de Ventas (D18, 3-nov-2026). */
internal sealed interface EstadoDeLaListaDeVentas {
    /** Cargando y todavía sin ninguna venta en la mano: el esqueleto de siempre. */
    data object Cargando : EstadoDeLaListaDeVentas

    /** Hay ventas que enseñar. */
    data object Lista : EstadoDeLaListaDeVentas

    /** El servidor contestó y no hay ventas, o los filtros las esconden todas. */
    data object Vacia : EstadoDeLaListaDeVentas

    /** No hay ninguna venta en la mano porque la lectura falló: no se sabe si hay ventas. */
    data class NoSeCargo(val fallo: FalloDeLectura) : EstadoDeLaListaDeVentas
}

/**
 * PURA. [cargadas] = las ventas que trajo el servidor; [visibles] = las que quedan tras los filtros; [fallo] = por qué
 * falló la última carga (`null` si salió bien).
 *
 * Con ventas en la mano, un fallo NO cambia nada: la lista se queda (la banda «Sin conexión» de arriba ya lo dice). Sin
 * ninguna, el fallo manda aunque haya filtros o búsqueda: no se pudo buscar, así que «Sin transacciones» o «No se
 * encontraron resultados» serían mentira.
 */
internal fun estadoDeLaListaDeVentas(
    cargadas: Int,
    visibles: Int,
    cargando: Boolean,
    fallo: FalloDeLectura?,
): EstadoDeLaListaDeVentas = when {
    cargadas == 0 && cargando -> EstadoDeLaListaDeVentas.Cargando
    cargadas == 0 && fallo != null -> EstadoDeLaListaDeVentas.NoSeCargo(fallo)
    visibles == 0 -> EstadoDeLaListaDeVentas.Vacia
    else -> EstadoDeLaListaDeVentas.Lista
}

/** Título y texto de la lista cuando [EstadoDeLaListaDeVentas.NoSeCargo]. */
internal fun textosDeVentasSinCargar(fallo: FalloDeLectura): TextosDeLecturaFallida = when (fallo) {
    FalloDeLectura.SIN_RED -> TextosDeLecturaFallida(TextosDeVentas.SIN_RED_TITULO, TextosDeVentas.SIN_RED_TEXTO)
    FalloDeLectura.SERVIDOR -> TextosDeLecturaFallida(TextosDeVentas.SERVIDOR_TITULO, TextosDeVentas.SERVIDOR_TEXTO)
}

internal object TextosDeVentas {
    const val SIN_RED_TITULO = "Sin conexión"
    const val SIN_RED_TEXTO = "Las ventas se consultan en el servidor. Las que cobres sin red se guardan en este " +
        "aparato y aparecen aquí al volver la conexión."
    const val SERVIDOR_TITULO = "No se pudieron cargar las ventas"
    const val SERVIDOR_TEXTO = "Vuelve a intentarlo en un momento."
}
