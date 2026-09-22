package com.avoqado.pos.inventory.waste.data

import java.text.Normalizer

/**
 * Busca en el catálogo de merma SIN distinguir mayúsculas NI acentos: «limon» encuentra «Limón».
 *
 * 🔴 Por qué en memoria y no con un `LIKE`: SQLite sólo ignora mayúsculas en letras SIN acento y
 * nunca ignora el acento. Con `LIKE`, «azucar» no encontraba «Azúcar», y en una tablet casi nadie
 * teclea acentos: el cajero concluía que el artículo no existía. Además el `LIKE` con letras
 * acentuadas depende de cómo esté compilado SQLite en cada sistema; aquí Android e iOS corren la
 * MISMA función (`WasteCatalogBusqueda.swift`), con los mismos casos de prueba.
 *
 * Lo que teclea la persona es texto: `%` y `_` no son comodines, porque no hay SQL de por medio.
 *
 * ponytail: pliega cada nombre en cada búsqueda. Con cientos de artículos no se nota; si un
 * negocio llega a miles y teclear se siente lento, precalcular la llave plegada al cargar la lista.
 */
fun buscarEnCatalogo(
    catalogo: List<WasteCatalogEntity>,
    texto: String,
    limite: Int,
): List<WasteCatalogEntity> {
    val buscado = plegar(texto.trim())
    return catalogo
        .map { it to plegar(it.name) }
        .filter { (item, nombre) -> buscado.isEmpty() || buscado in nombre || buscado in plegar(item.sku) }
        // Por el nombre YA plegado: así «Árbol» va antes que «Zanahoria». Ordenar por bytes lo
        // mandaría al final, porque la `Á` pesa más que la `Z`.
        .sortedWith(compareBy({ it.second }, { it.first.itemId }))
        .take(limite)
        .map { it.first }
}

/**
 * Minúsculas y sin acentos: «LIMÓN» → «limon», «Piña» → «pina».
 *
 * Mismo algoritmo que `ReceiptText.normalizeForMatch` y que su gemela de iOS. Se repite a
 * propósito en vez de reusarla: aquélla es la regla del TICKET y puede cambiar por razones del
 * ticket; ésta es la del buscador.
 */
fun plegar(texto: String): String =
    Normalizer.normalize(texto.lowercase(), Normalizer.Form.NFD)
        .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
