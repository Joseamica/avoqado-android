package com.avoqado.pos.pos.presentation.checkout

import com.avoqado.pos.pos.data.model.Product

/**
 * D7 (18-nov, Codex D7 r1): la captura de peso abierta, con el número de su APERTURA. Cerrarla y volver a abrir el mismo
 * producto es otra apertura: su báscula arranca de nuevo y su peso tecleado empieza vacío. Sólo la etiqueta leída con
 * ESTA apertura abierta avisa en ella.
 */
data class CapturaDePeso(
    val product: Product,
    val apertura: Long,
    /** D7 (founder, 18-nov: «A′»): lo que ya entró por su etiqueta con esta captura abierta. La captura NO se cierra. */
    val avisoDeEtiqueta: String? = null,
)

/**
 * ¿La etiqueta de báscula de [productoDeLaEtiqueta] es del producto que se está pesando? Sólo si al LEERLA estaba abierta esa
 * misma apertura del mismo producto y sigue abierta. Entonces la captura avisa «Ya entró … por su etiqueta» y sigue abierta
 * (founder, 18-nov: «A′»): el POS no sabe si es la misma pieza o una segunda, y decide la cajera. Una etiqueta tardía no avisa
 * en la captura siguiente, y la de otro producto no avisa (cremería: se pesa el queso y se escanea otra cosa).
 */
fun etiquetaEsDeLaCaptura(alLeer: CapturaDePeso?, ahora: CapturaDePeso?, productoDeLaEtiqueta: String): Boolean =
    alLeer != null && ahora != null && ahora.apertura == alLeer.apertura && ahora.product.id == productoDeLaEtiqueta

/** El aviso de la captura: «Ya entró Queso Oaxaca 0.435 kg por su etiqueta». */
fun avisoDeEtiqueta(producto: String, pesoKg: Double): String =
    "Ya entró $producto ${String.format(java.util.Locale.US, "%.3f", pesoKg)} kg por su etiqueta"
