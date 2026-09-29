package com.avoqado.pos.inventory.waste

import com.avoqado.pos.inventory.waste.data.MotivoNoRegistrable
import com.avoqado.pos.inventory.waste.data.ProductoDelMenu
import com.avoqado.pos.inventory.waste.data.ProductoNoRegistrable
import com.avoqado.pos.inventory.waste.data.WasteCatalogEntity
import com.avoqado.pos.inventory.waste.data.buscarEnCatalogo
import com.avoqado.pos.inventory.waste.data.plegar
import com.avoqado.pos.inventory.waste.data.productosNoRegistrables
import com.avoqado.pos.inventory.waste.domain.TextosMerma
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

/**
 * Los productos del menú que coinciden con lo buscado pero NO se pueden registrar como merma.
 * Caso real: Testarudo buscó «pan de muerto», no salió nada y concluyó que la app sólo acepta
 * ingredientes. Gemela de iOS: `ProductosNoRegistrablesTests` en `WasteCatalogBusquedaTests.swift`.
 */
class ProductosNoRegistrablesTest {

    private fun producto(
        id: String,
        name: String,
        sku: String? = null,
        type: String? = "FOOD_AND_BEV",
        trackInventory: Boolean? = false,
        active: Boolean? = true,
    ) = ProductoDelMenu(id, name, sku, type, trackInventory, active)

    private fun enCatalogo(itemType: String, itemId: String, name: String) =
        WasteCatalogEntity("v1", itemType, itemId, name, "", "UNIT", actualizadoEn = 0L)

    private val productos = listOf(
        producto("p1", "PAN DE MUERTO", sku = "SKU-1788"),
        producto("p2", "Pan de elote", trackInventory = true),
        producto("p3", "Pan dulce", trackInventory = true),
        producto("p4", "Pan brioche", trackInventory = true),
        producto("p5", "Clase de panadería", type = "CLASS"),
        producto("p6", "Café americano"),
        producto("p7", "Pan viejo", active = false),
        producto("p8", "Boleto cata", type = "EVENT"),
    )
    private val catalogo = listOf(
        enCatalogo("PRODUCT", "p4", "Pan brioche"),
        enCatalogo("RAW_MATERIAL", "p3", "Harina"), // mismo id pero es INGREDIENTE: no excluye al producto p3
    )

    private fun buscar(texto: String, limite: Int = 50, lista: List<ProductoDelMenu> = productos) =
        productosNoRegistrables(lista, catalogo, texto, limite)

    @Test
    fun `sin inventario y con inventario, cada uno con su motivo y en orden alfabetico`() {
        assertEquals(
            listOf(
                ProductoNoRegistrable("p2", "Pan de elote", MotivoNoRegistrable.CON_INVENTARIO),
                ProductoNoRegistrable("p1", "PAN DE MUERTO", MotivoNoRegistrable.SIN_INVENTARIO),
                ProductoNoRegistrable("p3", "Pan dulce", MotivoNoRegistrable.CON_INVENTARIO),
            ),
            buscar("pan"),
        )
    }

    @Test
    fun `el producto que ya esta en el catalogo nunca sale como no registrable`() {
        assertEquals(emptyList<ProductoNoRegistrable>(), buscar("brioche"))
    }

    @Test
    fun `clases y desactivados no salen, eventos si`() {
        assertEquals(emptyList<String>(), buscar("clase").map { it.id })
        assertEquals(emptyList<String>(), buscar("viejo").map { it.id })
        assertEquals(listOf("p8"), buscar("cata").map { it.id })
    }

    @Test
    fun `sin acento, sin mayusculas y por SKU`() {
        assertEquals(listOf("p1"), buscar("muerto").map { it.id })
        assertEquals(listOf("p6"), buscar("CAFE AMERICANO").map { it.id })
        assertEquals(listOf("p1"), buscar("sku-17").map { it.id })
    }

    @Test
    fun `texto en blanco o lista vacia no devuelven nada`() {
        assertEquals(emptyList<ProductoNoRegistrable>(), buscar(""))
        assertEquals(emptyList<ProductoNoRegistrable>(), buscar("   "))
        assertEquals(emptyList<ProductoNoRegistrable>(), buscar("pan", lista = emptyList()))
    }

    @Test
    fun `respeta el limite y no repite el mismo id`() {
        assertEquals(listOf("p2", "p1"), buscar("pan", limite = 2).map { it.id })
        val repetidos = listOf(producto("p1", "PAN DE MUERTO"), producto("p1", "PAN DE MUERTO"))
        assertEquals(listOf("p1"), buscar("pan", lista = repetidos).map { it.id })
    }

    /** El de «sin inventario» NUNCA manda a activar a secas: con receta, el asistente la borraría. */
    @Test
    fun `cada motivo tiene su texto, y sin inventario empieza por los ingredientes`() {
        assertEquals(TextosMerma.MOTIVO_SIN_INVENTARIO, TextosMerma.motivoNoRegistrable(MotivoNoRegistrable.SIN_INVENTARIO))
        assertEquals(TextosMerma.MOTIVO_CON_INVENTARIO, TextosMerma.motivoNoRegistrable(MotivoNoRegistrable.CON_INVENTARIO))
        assertTrue(TextosMerma.MOTIVO_SIN_INVENTARIO.startsWith("No lleva inventario. Si lo preparan aquí, registra la merma de sus ingredientes."))
    }
}
