package com.avoqado.pos.inventory.waste

import com.avoqado.pos.inventory.waste.data.WasteCatalogEntity
import com.avoqado.pos.inventory.waste.data.buscarEnCatalogo
import com.avoqado.pos.inventory.waste.data.plegar
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El buscador del catálogo de merma. Función PURA: su gemela de iOS
 * (`WasteCatalogBusquedaTests.swift`) tiene exactamente los mismos casos.
 *
 * 🔴 Se busca SIN distinguir mayúsculas NI acentos. SQLite sólo ignora mayúsculas en letras
 * sin acento: con un `LIKE`, «limon» no encontraba «Limón» ni «azucar» encontraba «Azúcar»,
 * y en una tablet casi nadie teclea acentos. El cajero concluía que el artículo no existía.
 */
class WasteCatalogBusquedaTest {

    private fun item(itemId: String, name: String, sku: String = "") =
        WasteCatalogEntity("v1", "RAW_MATERIAL", itemId, name, sku, "kg", actualizadoEn = 0L)

    private val catalogo = listOf(
        item("rm1", "Limón", sku = "LIM-01"),
        item("rm2", "Azúcar"),
        item("rm3", "Leche 100%"),
        item("rm4", "Caf_e"),
        item("rm5", "Zanahoria"),
        item("rm6", "Árbol de canela"),
    )

    private fun ids(texto: String, limite: Int = 50) = buscarEnCatalogo(catalogo, texto, limite).map { it.itemId }

    @Test
    fun `sin acento encuentra el nombre con acento, y al reves`() {
        assertEquals(listOf("rm1"), ids("limon"))
        assertEquals(listOf("rm2"), ids("azucar"))
        assertEquals(listOf("rm1"), ids("LIMÓN"))
    }

    @Test
    fun `sin distinguir mayusculas, por nombre y por SKU`() {
        assertEquals(listOf("rm1"), ids("LIM-0"))
        assertEquals(listOf("rm5"), ids("zanah"))
        assertEquals(emptyList<String>(), ids("zzz"))
    }

    /** Lo que teclea la persona es TEXTO: `%` y `_` no son comodines. */
    @Test
    fun `el porcentaje y el guion bajo se buscan literales`() {
        assertEquals(listOf("rm3"), ids("%"))
        assertEquals(listOf("rm4"), ids("_"))
    }

    /**
     * Sin texto devuelve todo, ordenado como lo leería una persona: «Árbol» va ANTES que
     * «Zanahoria». Ordenar por bytes lo mandaría al final, porque la `Á` pesa más que la `Z`.
     */
    @Test
    fun `sin texto devuelve todo en orden alfabetico, con los acentos donde van`() {
        assertEquals(listOf("rm6", "rm2", "rm4", "rm3", "rm1", "rm5"), ids(""))
        assertEquals(listOf("rm6", "rm2", "rm4", "rm3", "rm1", "rm5"), ids("   "))
    }

    @Test
    fun `el limite acota la lista`() {
        assertEquals(2, ids("", limite = 2).size)
    }

    @Test
    fun `plegar quita mayusculas y acentos, y la enie queda como n`() {
        assertEquals("limon azucar arbol pina", plegar("LIMÓN Azúcar Árbol Piña"))
    }
}
