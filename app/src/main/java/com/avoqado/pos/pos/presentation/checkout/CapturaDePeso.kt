package com.avoqado.pos.pos.presentation.checkout

import com.avoqado.pos.pos.data.model.Product

/**
 * D7 (18-nov, Codex D7 r1): la captura de peso abierta, con el número de su APERTURA. Cerrarla y volver a abrir el mismo
 * producto es otra apertura: su báscula arranca de nuevo y su peso tecleado empieza vacío. Sólo la etiqueta leída con
 * ESTA apertura abierta la puede cerrar.
 */
data class CapturaDePeso(val product: Product, val apertura: Long)

/**
 * ¿La etiqueta de báscula de [productoDeLaEtiqueta] cierra la captura? Sólo si al LEERLA estaba abierta esa misma apertura del
 * mismo producto y sigue abierta. Una etiqueta tardía no cierra la captura siguiente (ni borra su peso tecleado), y la de
 * otro producto deja la captura abierta a propósito (cremería: se pesa el queso y se escanea otra cosa).
 */
fun etiquetaCierraLaCaptura(alLeer: CapturaDePeso?, ahora: CapturaDePeso?, productoDeLaEtiqueta: String): Boolean =
    alLeer != null && ahora != null && ahora.apertura == alLeer.apertura && ahora.product.id == productoDeLaEtiqueta

/**
 * ¿Este código puede ser una etiqueta de báscula? Mientras se resuelve uno, «Agregar» de la captura abierta espera (Codex D7 r1
 * #1: el peso entraba dos veces). Cualquier código de 13 números, sin mirar los ajustes de la pantalla: pueden estar viejos
 * (se acaba de prender la báscula o cambiar su prefijo) y el carrito decide con una consulta fresca (Codex D7 r2 #1).
 */
fun puedeSerEtiquetaDeBascula(codigo: String): Boolean = codigo.trim().let { it.length == 13 && it.all(Char::isDigit) }
