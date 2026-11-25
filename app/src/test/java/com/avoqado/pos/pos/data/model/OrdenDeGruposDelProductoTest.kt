package com.avoqado.pos.pos.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * hz:960ee149:D1 — los grupos de un producto salen en el orden en que el negocio los guardó (`displayOrder` de menor a
 * mayor), igual que en el panel, la terminal y el iPad. Antes el POS los volteaba (el último guardado salía primero).
 */
class OrdenDeGruposDelProductoTest {

    private fun grupo(nombre: String, orden: Int?) = ProductModifierGroupEntry(
        groupId = "g-$nombre",
        displayOrder = orden,
        group = ModifierGroupData(id = "g-$nombre", name = nombre),
    )

    private fun producto(vararg grupos: ProductModifierGroupEntry) =
        Product(id = "p", name = "Paquete", modifierGroups = grupos.toList())

    @Test
    fun `los grupos salen de menor a mayor displayOrder aunque lleguen revueltos`() {
        val p = producto(grupo("Postre", 4), grupo("Plato", 1), grupo("Bebida", 3), grupo("Guarnición", 2))
        assertEquals(listOf("Plato", "Guarnición", "Bebida", "Postre"), p.sortedModifierGroups.map { it.name })
    }

    @Test
    fun `con el mismo displayOrder desempata el nombre`() {
        val p = producto(grupo("Salsa", 1), grupo("Bebida", 1))
        assertEquals(listOf("Bebida", "Salsa"), p.sortedModifierGroups.map { it.name })
    }

    @Test
    fun `sin displayOrder cuenta como 0 y va antes que el 1`() {
        val p = producto(grupo("Bebida", 1), grupo("Guarnición", null))
        assertEquals(listOf("Guarnición", "Bebida"), p.sortedModifierGroups.map { it.name })
    }

    @Test
    fun `sin grupos da lista vacía`() {
        assertEquals(emptyList<ProductModifierGroup>(), Product(id = "p", name = "Paquete", modifierGroups = null).sortedModifierGroups)
    }
}
