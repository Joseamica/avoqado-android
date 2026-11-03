package com.avoqado.pos.pos.domain

/**
 * D12 — el «Ajuste de precio» de un producto sólo BAJA el precio: viaja al servidor como descuento
 * (`adjustedUnitPrice`) y subirlo a mano no cobraría nada de más (el servidor lo ignora). Para cobrar más está el
 * producto de precio libre. Mismo texto y misma regla que iOS.
 */
const val MENSAJE_SUBIR_PRECIO = "Para cobrar más, usa un producto de precio libre."

/** `true` sólo si el precio nuevo es mayor a cero y no pasa del original (sin extras; por kg en venta por peso). */
fun ajusteDePrecioPermitido(nuevoCents: Int, originalCents: Int): Boolean = nuevoCents in 1..originalCents

/**
 * Lo que se guarda en la línea al confirmar: `null` si el precio escrito es igual al original (no descuenta nada, y una
 * línea «ajustada» sin descuento bloquearía cosas como los vales), el valor si es más bajo. Se llama sólo cuando
 * [ajusteDePrecioPermitido] es `true`.
 */
fun ajusteAGuardar(nuevoCents: Int, originalCents: Int): Int? = nuevoCents.takeIf { it < originalCents }
