package com.avoqado.pos.printing.data

/**
 * La relación de existencias impresa en una tira (Inventario → impresora → «Lista de inventario»,
 * gratis). Espejo de `ListaDeInventario` en iOS. [Renglon.existencia] va ya formateada con su
 * unidad (`8.065 kg`) y su signo: un negativo es justo lo que hay que ir a revisar.
 */
data class ListaDeInventario(
    val negocio: String?,
    val fecha: String,
    val secciones: List<Seccion>,
) {
    data class Seccion(val titulo: String, val renglones: List<Renglon>)
    data class Renglon(val nombre: String, val existencia: String, val sku: String?)
}
