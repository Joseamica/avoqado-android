package com.avoqado.pos.transactions.presentation

// Acomodo de «Emitir reembolso» a pantalla completa. Es una regla pura (sin Compose) para probarla con
// números; la pantalla sólo la consulta.

// El corte es por ANCHO, nunca por nombre de aparato. 840 dp son dos columnas de ~420: la fila
// «Devolver con» lleva 3 opciones o más y no cabe en menos (el Cambio parte en 600, aquí no alcanza).
private const val ANCHO_MINIMO_DOS_COLUMNAS_DP = 840f

// Alto libre bajo el encabezado y con el teclado ya descontado. Con menos, el pie deja de ser fijo y se
// desliza con lo demás de la derecha, para llegar al motivo y a Reembolsar sin cerrar el teclado.
private const val ALTO_MINIMO_PIE_FIJO_DP = 420f

// El mismo alto, en una columna. El pie de «Artículos» mide unos 400–560 dp: a 360×640, con propina y 4 medios, le dejaba 0 dp a la lista y
// 5 a Reembolsar; a 360×720 (659 aquí, con los 61 dp del encabezado) y saldo a favor, 1 dp a la lista. A 360×780 (719 aquí) ya va bien (3-nov).
private const val ALTO_MINIMO_PIE_FIJO_UNA_COLUMNA_DP = 700f

/**
 * ¿Van dos columnas? La tarjeta de regalo (`soloCompleto`) no trae pestañas, lista ni campo de importe:
 * dos columnas dejarían la izquierda vacía, así que va siempre en una, sin importar el ancho.
 */
internal fun enDosColumnas(anchoDp: Float, soloCompleto: Boolean): Boolean =
    !soloCompleto && anchoDp >= ANCHO_MINIMO_DOS_COLUMNAS_DP

/**
 * ¿El pie (importe, Cancelar y Reembolsar) queda fijo abajo de la columna derecha?
 * `altoDp` es el alto de adentro del `imePadding` y bajo el encabezado, no el de la pantalla: con el teclado abierto baja.
 */
internal fun pieFijoEnDosColumnas(altoDp: Float): Boolean = altoDp >= ALTO_MINIMO_PIE_FIJO_DP

/**
 * En una columna, ¿el pie queda fijo abajo y sólo la lista se desliza? Con `false` se desliza todo junto, para que la lista se vea y
 * Reembolsar se alcance deslizando. `altoDp` es el mismo de [pieFijoEnDosColumnas], y abrir el teclado casi siempre cruza este corte:
 * la pantalla sólo puede cambiar modificadores, nunca de qué nodo cuelga el campo donde se escribe.
 */
internal fun pieFijoEnUnaColumna(altoDp: Float): Boolean = altoDp >= ALTO_MINIMO_PIE_FIJO_UNA_COLUMNA_DP
