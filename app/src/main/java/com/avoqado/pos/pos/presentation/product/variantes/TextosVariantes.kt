package com.avoqado.pos.pos.presentation.product.variantes

/**
 * Todo texto de variantes que ve la cajera, en UN archivo.
 * 🔴 IDÉNTICO carácter por carácter a `TextosVariantes.swift` de avoqado-ios: `scripts/paridad-textos-variantes.sh` compara los
 * literales y truena si difiere uno. Las plantillas llevan marcadores `{n}`, `{precio}` y `{nombre}`, no interpolación.
 */
object TextosVariantes {
    const val VARIANTES = "{n} variantes"
    const val DESDE = "desde {precio}"
    const val AGREGAR = "Agregar al carrito"
    const val AGREGAR_CON_PRECIO = "Agregar al carrito · {precio}"
    const val FALTA_ELEGIR = "Elige una opción de cada grupo para agregar."
    const val DISPONIBLE_UNO = "1 disponible"
    const val DISPONIBLES = "{n} disponibles"
    const val AGOTADO = "Agotado"
    const val AGOTADO_AVISO = "Agotado: se puede vender igual."
    const val SIN_COMBINACION = "No hay esa combinación"
    const val CERRAR = "Cerrar"
    const val ACCESIBLE_FAMILIA = "{nombre}, {n} variantes, {precio}"

    fun variantes(n: Int) = VARIANTES.replace("{n}", n.toString())
    fun desde(precio: String) = DESDE.replace("{precio}", precio)
    fun agregarCon(precio: String) = AGREGAR_CON_PRECIO.replace("{precio}", precio)
    fun disponibles(n: Int) = if (n == 1) DISPONIBLE_UNO else DISPONIBLES.replace("{n}", n.toString())
    fun accesibleFamilia(nombre: String, n: Int, precio: String) =
        ACCESIBLE_FAMILIA.replace("{nombre}", nombre).replace("{n}", n.toString()).replace("{precio}", precio)
}
