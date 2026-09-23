package com.avoqado.pos.printing.data

/**
 * Una etiqueta de precio para el anaquel (PRO `PRICE_LABELS`). Espejo de `EtiquetaDePrecio` en iOS.
 *
 * [precio] va YA formateado (`$45.00`, `$420.00/kg`): con IVA incluido, como se cobra en México.
 */
data class EtiquetaDePrecio(
    val nombre: String,
    val precio: String,
    val codigo: String?,
    val negocio: String?,
    val copias: Int,
) {
    companion object {
        /**
         * El código que va en las barras. Al escanear, el POS encuentra el artículo por cualquiera
         * de los tres (`CartViewModel.resolveScannedBarcode`); se prefiere el GTIN porque es el que
         * ya traen impreso los productos de fábrica, así la etiqueta y el empaque escanean igual.
         */
        fun codigoDe(gtin: String?, barcode: String?, sku: String?): String? =
            listOf(gtin, barcode, sku).firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
    }
}
