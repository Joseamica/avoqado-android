package com.avoqado.pos.transactions.presentation

// Acomodo de «Emitir reembolso» a pantalla completa. Es una regla pura (sin Compose) para probarla con
// números; la pantalla sólo la consulta.

// El corte es por ANCHO, nunca por nombre de aparato. 840 dp son dos columnas de ~420: la fila
// «Devolver con» lleva 3 opciones o más y no cabe en menos (el Cambio parte en 600, aquí no alcanza).
private const val ANCHO_MINIMO_DOS_COLUMNAS_DP = 840f

// Alto libre bajo el encabezado y con el teclado ya descontado. Con menos, el pie deja de ser fijo y se
// desliza con lo demás de la derecha, para llegar al motivo y a Reembolsar sin cerrar el teclado.
private const val ALTO_MINIMO_PIE_FIJO_DP = 420f

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
