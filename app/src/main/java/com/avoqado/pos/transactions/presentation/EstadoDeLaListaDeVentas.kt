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
 * PURA. [deLaConsulta] = la lista guardada es de la consulta vigente (sucursal, quién y búsqueda); [cargadas] = las ventas
 * de esa lista; [visibles] = las que quedan tras los filtros; [fallo] = por qué falló la última carga (`null` si salió bien).
 *
 * Con ventas de ESTA consulta en la mano, un fallo NO cambia nada: la lista se queda (la banda «Sin conexión» de arriba ya
 * lo dice). Una lista de OTRA consulta no se enseña nunca (otra sucursal, otra persona, otra búsqueda: Codex r1 #1 y #3):
 * sin la de esta consulta, el fallo manda, aunque haya filtros o búsqueda —no se pudo buscar, así que «Sin transacciones»
 * o «No se encontraron resultados» serían mentira—. Sin fallo, la consulta va en camino: el esqueleto.
 */
internal fun estadoDeLaListaDeVentas(
    deLaConsulta: Boolean,
    cargadas: Int,
    visibles: Int,
    cargando: Boolean,
    fallo: FalloDeLectura?,
): EstadoDeLaListaDeVentas = when {
    deLaConsulta && cargadas > 0 -> if (visibles == 0) EstadoDeLaListaDeVentas.Vacia else EstadoDeLaListaDeVentas.Lista
    cargando -> EstadoDeLaListaDeVentas.Cargando
    fallo != null -> EstadoDeLaListaDeVentas.NoSeCargo(fallo)
    deLaConsulta -> EstadoDeLaListaDeVentas.Vacia
    else -> EstadoDeLaListaDeVentas.Cargando
}

/** Título y texto de la lista cuando [EstadoDeLaListaDeVentas.NoSeCargo]. */
internal fun textosDeVentasSinCargar(fallo: FalloDeLectura): TextosDeLecturaFallida = when (fallo) {
    FalloDeLectura.SIN_RED -> TextosDeLecturaFallida(TextosDeVentas.SIN_RED_TITULO, TextosDeVentas.SIN_RED_TEXTO)
    FalloDeLectura.SERVIDOR -> TextosDeLecturaFallida(TextosDeVentas.SERVIDOR_TITULO, TextosDeVentas.SERVIDOR_TEXTO)
}

internal object TextosDeVentas {
    const val SIN_RED_TITULO = "Sin conexión"
    const val SIN_RED_TEXTO = "Las ventas se consultan en el servidor. Las que cobres sin red se guardan en este " +
        "aparato y aparecen aquí solas al volver la conexión."
    const val SERVIDOR_TITULO = "No se pudieron cargar las ventas"
    const val SERVIDOR_TEXTO = "Vuelve a intentarlo en un momento."

    /** D22: la píldora arriba de la lista cuando hay más de una página y subió un cobro. */
    const val HAY_VENTAS_NUEVAS = "Hay ventas nuevas · Actualizar"
}

/**
 * D22 (prueba 2 en la D3, 4-nov): tras reemplazarse la lista, ¿se sube hasta arriba? La lista tiene llaves estables y se queda
 * anclada a la fila que ya se veía: si entra algo nuevo ARRIBA (cambió la primera llave), queda fuera de la vista. Como Square
 * y Gmail: si se veía desde arriba —la primera fila visible es la primera de antes y sin desplazamiento—, sube y enseña lo
 * nuevo; si la cajera había bajado, no se mueve. «Cargar más» pega al final: la primera llave no cambia y no sube.
 */
internal fun subirAlInicioTrasRecarga(primeraAntes: Any?, primeraAhora: Any?, primeraVisible: Any?, desplazamiento: Int): Boolean =
    primeraAntes != null && primeraAhora != primeraAntes && primeraVisible == primeraAntes && desplazamiento == 0
