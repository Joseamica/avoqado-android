package com.avoqado.pos.printing.data

import com.avoqado.pos.inventory.waste.domain.etiquetaDeUnidad

/**
 * Lo que el cajero acaba de registrar, guardado al confirmar: alcanza para imprimir su comprobante
 * aunque la fila ya haya subido y salido de la cola. Espejo de `MermaRegistrada` en iOS.
 */
data class MermaRegistrada(
    val folio: String,
    val articulo: String,
    /** Como la registra el servidor (la coma del teclado ya es punto). */
    val cantidad: String,
    val unit: String,
    /** La etiqueta en español del motivo, no el código. */
    val motivo: String,
    val nota: String?,
    val registro: String?,
    val creadaEn: Long,
)

/**
 * Comprobante impreso de una merma, para firmar. Nunca sale solo: lo pide el cajero. Si la merma sigue
 * en la cola (sin red), lo DICE: el papel no puede afirmar que ya está en el sistema.
 * Espejo de `ComprobanteDeMerma` en iOS.
 */
data class ComprobanteDeMerma(
    val negocio: String?,
    val folio: String,
    val fecha: String,
    val registro: String?,
    val articulo: String,
    val cantidad: String,
    val motivo: String,
    val nota: String?,
    val pendiente: Boolean,
) {
    companion object {
        fun desde(m: MermaRegistrada, negocio: String?, fecha: String, pendiente: Boolean) = ComprobanteDeMerma(
            negocio = negocio,
            folio = m.folio.take(8).uppercase(),
            fecha = fecha,
            registro = m.registro,
            articulo = m.articulo,
            cantidad = "${m.cantidad} ${etiquetaDeUnidad(m.unit)}",
            motivo = m.motivo,
            nota = m.nota,
            pendiente = pendiente,
        )
    }
}
