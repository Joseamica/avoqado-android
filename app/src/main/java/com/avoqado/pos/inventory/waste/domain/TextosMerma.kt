package com.avoqado.pos.inventory.waste.domain

/**
 * Todo texto que el cajero ve en «Registrar merma», en UN archivo.
 *
 * 🔴 Es IDÉNTICO, carácter por carácter, a `TextosMerma.swift` de avoqado-ios: el script
 * `scripts/paridad-textos-merma.sh` compara los literales de los dos archivos y truena si
 * difiere uno. Por eso las plantillas llevan marcadores `{nombre}` y no interpolación: así el
 * literal es el mismo en Kotlin y en Swift.
 */
object TextosMerma {
    const val TITULO = "Registrar merma"
    const val SIN_PERMISO = "Pídele acceso a tu gerente"
    const val INCLUIDO_EN_PLAN = "Incluido en el Plan Premium"
    const val PLAN = "Premium"

    const val BUSCAR = "Busca por nombre o SKU"
    const val ARTICULO = "Artículo"
    const val CAMBIAR = "Cambiar"
    const val CANTIDAD = "Cantidad"
    const val MOTIVO = "Motivo"
    const val NOTA = "Nota"
    const val NOTA_OPCIONAL = "Agrega una nota (opcional)"
    const val NOTA_OBLIGATORIA = "Escribe qué pasó (obligatoria con «Otro»)"
    const val REGISTRAR = "Registrar"
    const val SIN_RESULTADOS = "No encontramos ese artículo."
    const val SIN_CATALOGO = "Sin catálogo guardado. Conéctate a internet para descargarlo."
    const val SIN_RED = "Sin conexión: la merma se guarda en este aparato y se sube sola."
    const val SIN_SESION = "Inicia sesión para registrar mermas."

    const val AVISO_DE_PLAN =
        "Registrar merma está incluido en el Plan Premium. Lo que registres se guarda y se sube cuando tu negocio lo active."
    const val ACTUALIZAR = "Actualizar"

    const val CONFIRMAR_TITULO = "¿Registrar merma?"
    const val CONFIRMACION = "Vas a registrar {cantidad} de {articulo} como merma."
    const val CONFIRMAR = "Confirmar"

    const val REGISTRADA = "¡Merma registrada!"
    const val GUARDADA = "Merma guardada"
    const val SE_SUBIRA_SIN_RED = "Se subirá al recuperar la conexión."
    const val SE_SUBIRA_SIN_PLAN = "Se subirá cuando tu negocio active el Plan Premium."

    const val HACE_MINUTOS = "Catálogo de hace {n} min"
    const val HACE_HORAS = "Catálogo de hace {n} h"
    const val HACE_DIAS = "Catálogo de hace {n} días"

    /** La unidad del catálogo (el enum del servidor) → lo que lee el cajero. */
    val UNIDADES: Map<String, String> = mapOf(
        "KILOGRAM" to "kg",
        "GRAM" to "g",
        "MILLIGRAM" to "mg",
        "POUND" to "lb",
        "OUNCE" to "oz",
        "LITER" to "L",
        "MILLILITER" to "ml",
        "FLUID_OUNCE" to "fl oz",
        "CUP" to "taza",
        "TABLESPOON" to "cda",
        "TEASPOON" to "cdta",
        "UNIT" to "pza",
        "PIECE" to "pza",
        "DOZEN" to "doc",
        "BOX" to "caja",
        "BOTTLE" to "bot",
        "BAG" to "bolsa",
        "CAN" to "lata",
    )
}
