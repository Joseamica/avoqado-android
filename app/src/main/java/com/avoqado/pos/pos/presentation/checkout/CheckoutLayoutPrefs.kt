package com.avoqado.pos.pos.presentation.checkout

import android.content.Context

/**
 * Cómo se ve el mostrador EN ESTE APARATO: qué tan densos son los tiles y en
 * qué orden salen las pestañas.
 *
 * 🔴 Es local a propósito, NO del negocio. La D3 tiene 1280 dp de ancho y el
 * teléfono de un mesero 400: un solo tamaño para todo el local deja a uno de
 * los dos mal. Square lo resuelve igual — el tamaño de tile vive en el POS
 * (Ajustes › Checkout Settings › Item Details › Tile Size), no en su
 * dashboard. Por eso esto NO toca server, dashboard ni MCP.
 *
 * Espejo exacto de iOS (`CheckoutLayoutPrefs.swift`): los mismos nombres
 * guardados y los mismos números, o el mismo local se ve distinto según con
 * qué aparato lo mires.
 */
enum class TileSize(
    val etiqueta: String,
    val descripcion: String,
    /** Ancho mínimo por tile. `GridCells.Adaptive` deriva las columnas de aquí,
     *  así que el mismo número sirve en un panel de 640 dp y en un teléfono. */
    val minTileWidthDp: Int,
    /** Ancho ÷ alto del tile de categoría. >1 = rectángulo acostado. */
    val categoryAspect: Float,
    /** Ancho ÷ alto SÓLO de la imagen del tile de producto; abajo van nombre y precio. */
    val productImageAspect: Float,
    val iconoDp: Int,
    val paddingDp: Int,
    /** En compacto el tile no cabe con ícono arriba y nombre abajo: gana el nombre. */
    val mostrarIcono: Boolean,
    /** SÓLO para la miniatura del selector. Las columnas reales las decide
     *  `GridCells.Adaptive` según el ancho que haya; esto es cuántas salen en el
     *  panel de 640 dp de una D3, que es el aparato que el founder tiene enfrente. */
    val columnasDeMuestra: Int,
) {
    COMPACTO("Compacto", "Más productos a la vista", 104, 2.0f, 1.7f, 18, 8, false, 5),
    MEDIANO("Mediano", "El equilibrio de siempre", 132, 1.5f, 1.4f, 20, 12, true, 4),
    GRANDE("Grande", "Tiles amplios, fáciles de tocar", 168, 1.0f, 1.2f, 24, 12, true, 3),
    ;

    companion object {
        /** El default es MEDIANO: en la D3 son 4 columnas, igual que el default
         *  «Tall» de Square, que su propia ayuda describe como el optimizado
         *  para densidad y velocidad. */
        val PREDETERMINADO = MEDIANO
    }
}

private const val PREFS_NAME = "avoqado_checkout_layout_prefs"
private const val KEY_TILE_SIZE = "tile_size"
private const val KEY_TAB_ORDER = "tab_order"

object CheckoutLayoutPrefs {

    fun tileSize(context: Context): TileSize =
        resolverTamano(prefs(context).getString(KEY_TILE_SIZE, null))

    fun guardarTileSize(context: Context, size: TileSize) {
        prefs(context).edit().putString(KEY_TILE_SIZE, size.name).apply()
    }

    /** Los nombres crudos guardados. Se resuelven con [ordenarPestanas], que es
     *  quien sabe qué pestañas existen hoy. */
    fun ordenGuardado(context: Context): List<String> =
        prefs(context).getString(KEY_TAB_ORDER, null)
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun guardarOrden(context: Context, orden: List<InputTab>) {
        prefs(context).edit()
            .putString(KEY_TAB_ORDER, orden.joinToString(",") { it.name })
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

/** Un nombre que esta versión ya no conoce (o ninguno) cae al default en vez de reventar. */
fun resolverTamano(guardado: String?): TileSize =
    TileSize.entries.firstOrNull { it.name == guardado } ?: TileSize.PREDETERMINADO

/**
 * Aplica el orden que el aparato guardó sobre las pestañas que HOY son visibles.
 *
 * 🔴 Lo que no está en el orden guardado va al final, nunca se pierde: si mañana
 * nace una pestaña, un aparato con un orden viejo tiene que verla igual. Una
 * pestaña que desaparece en silencio es el bug que el CLAUDE.md prohíbe — el
 * cajero no tendría cómo saber que existe ni cómo recuperarla.
 */
fun ordenarPestanas(guardado: List<String>, disponibles: List<InputTab>): List<InputTab> {
    if (guardado.isEmpty()) return disponibles
    val porNombre = disponibles.associateBy { it.name }
    val elegidas = guardado.mapNotNull { porNombre[it] }.distinct()
    return elegidas + disponibles.filterNot { it in elegidas }
}

/** Mueve una pestaña un lugar arriba o abajo. Fuera de rango devuelve la misma lista. */
fun moverPestana(orden: List<InputTab>, desde: Int, hacia: Int): List<InputTab> {
    if (desde !in orden.indices || hacia !in orden.indices || desde == hacia) return orden
    return orden.toMutableList().apply { add(hacia, removeAt(desde)) }
}
