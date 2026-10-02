package com.avoqado.escritorio

/**
 * De dónde sale la imagen de un id de `R.drawable.*`. Los ids viven en :pos (aquí no se conocen), así que :pos registra
 * su tabla al arrancar y `BitmapFactory.decodeResource` le pregunta la ruta (dentro del classpath). Por omisión, ninguna.
 */
object RecursosDeImagen {
    @Volatile
    @JvmField
    var rutaDe: (Int) -> String? = { null }
}
