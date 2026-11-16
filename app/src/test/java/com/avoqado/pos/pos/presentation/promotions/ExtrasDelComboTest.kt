package com.avoqado.pos.pos.presentation.promotions

import com.avoqado.pos.pos.data.model.Modifier
import com.avoqado.pos.pos.data.model.ModifierGroupData
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductModifierGroup
import com.avoqado.pos.pos.data.model.ProductModifierGroupEntry
import com.avoqado.pos.pos.data.model.Promotion
import com.avoqado.pos.pos.data.model.PromotionGroup
import com.avoqado.pos.pos.data.model.PromotionOption
import com.avoqado.pos.pos.data.model.SelectedModifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reglas puras de los extras de cada pieza del combo (sin Compose). */
class ExtrasDelComboTest {

    private val queso = Modifier(id = "queso", name = "Queso", price = 15.0)
    private val tocino = Modifier(id = "tocino", name = "Tocino", price = 20.0)
    private val cebolla = Modifier(id = "cebolla", name = "Cebolla", price = 0.0)

    private val termino = ProductModifierGroup(
        id = "g-termino",
        name = "Término",
        required = true,
        multipleSelect = false,
        modifiers = listOf(Modifier(id = "medio", name = "Medio"), Modifier(id = "bien", name = "Bien cocido")),
    )
    private val extras = ProductModifierGroup(
        id = "g-extras",
        name = "Extras",
        multipleSelect = true,
        maxSelections = 2,
        modifiers = listOf(queso, tocino, cebolla),
    )

    private fun producto(id: String, vararg grupos: ModifierGroupData) = Product(
        id = id,
        name = id,
        priceValue = 100.0,
        modifierGroups = grupos.mapIndexed { i, g -> ProductModifierGroupEntry(groupId = g.id, displayOrder = i, group = g) },
    )

    private fun datos(g: ProductModifierGroup) = ModifierGroupData(
        id = g.id,
        name = g.name,
        required = g.required,
        allowMultiple = g.multipleSelect,
        minSelections = g.minSelections,
        maxSelections = g.maxSelections,
        modifiers = g.modifiers,
    )

    private val hamburguesa = producto("p-hamburguesa", datos(termino), datos(extras))
    private val refresco = producto("p-refresco")

    private fun opcion(id: String, productId: String, nombre: String, cantidad: Int = 1) =
        PromotionOption(id = id, productId = productId, productName = nombre, quantity = cantidad)

    private val oHamburguesa = opcion("o-hamburguesa", "p-hamburguesa", "Hamburguesa")
    private val oRefresco = opcion("o-refresco", "p-refresco", "Refresco")

    private fun combo(vararg grupos: PromotionGroup) = Promotion(id = "promo", name = "Combo", groups = grupos.toList())

    private fun sel(grupo: ProductModifierGroup, extra: Modifier) =
        SelectedModifier(grupo.id, grupo.name, extra.id, extra.name, extra.priceInCents)

    // ── gruposDeLaPieza ──────────────────────────────────────────────────────

    @Test
    fun `capacidad apagada no ofrece extras`() {
        assertNull(gruposDeLaPieza(oHamburguesa, listOf(hamburguesa), capacidad = false))
    }

    @Test
    fun `una pieza con cantidad distinta de 1 se vende como hoy`() {
        val dos = opcion("o-2", "p-hamburguesa", "Hamburguesa", cantidad = 2)
        assertNull(gruposDeLaPieza(dos, listOf(hamburguesa), capacidad = true))
    }

    @Test
    fun `un producto que la tableta no tiene se vende como hoy`() {
        assertNull(gruposDeLaPieza(oHamburguesa, listOf(refresco), capacidad = true))
    }

    @Test
    fun `con capacidad y producto presente devuelve sus grupos`() {
        val grupos = gruposDeLaPieza(oHamburguesa, listOf(hamburguesa), capacidad = true)
        assertNotNull(grupos)
        assertEquals(setOf("g-termino", "g-extras"), grupos!!.map { it.id }.toSet())
    }

    @Test
    fun `un producto sin grupos devuelve lista vacia, no null`() {
        assertEquals(emptyList<ProductModifierGroup>(), gruposDeLaPieza(oRefresco, listOf(refresco), capacidad = true))
    }

    @Test
    fun `un grupo obligatorio sin opciones se conserva y bloquea el combo`() {
        val terminoVacio = termino.copy(modifiers = emptyList())
        val extrasVacio = extras.copy(modifiers = emptyList())
        val vacia = producto("p-vacia", datos(terminoVacio), datos(extrasVacio))
        val o = opcion("o-vacia", "p-vacia", "Vacia")
        val grupos = gruposDeLaPieza(o, listOf(vacia), capacidad = true)
        assertEquals(listOf("g-termino"), grupos!!.map { it.id })
        val c = combo(PromotionGroup("g-plato", "Plato", listOf(o)))
        assertEquals(
            listOf("Falta elegir «Término» de Vacia."),
            faltantesDelCombo(c, emptyMap(), emptyMap()) { grupos },
        )
        assertTrue(comboPideExtras(c) { grupos })
    }

    @Test
    fun `un grupo opcional sin opciones se sigue descartando`() {
        val vacia = producto("p-vacia", datos(extras.copy(modifiers = emptyList())))
        val o = opcion("o-vacia", "p-vacia", "Vacia")
        assertEquals(emptyList<ProductModifierGroup>(), gruposDeLaPieza(o, listOf(vacia), capacidad = true))
    }

    // ── topeDelGrupo ─────────────────────────────────────────────────────────

    @Test
    fun `el tope de un grupo unico es 1 y el de uno multiple es su maximo`() {
        assertEquals(1, topeDelGrupo(termino))
        assertEquals(2, topeDelGrupo(extras))
        assertNull(topeDelGrupo(extras.copy(maxSelections = null)))
    }

    // ── alternarExtra ────────────────────────────────────────────────────────

    @Test
    fun `en un grupo unico elegir otro reemplaza al anterior`() {
        val medio = termino.modifiers[0]
        val bien = termino.modifiers[1]
        val enSeguida = alternarExtra(alternarExtra(emptyList(), termino, medio, true), termino, bien, true)
        assertEquals(listOf("bien"), enSeguida.map { it.modifierId })
    }

    @Test
    fun `en un grupo multiple se agrega hasta el tope y no mas`() {
        var actuales = alternarExtra(emptyList(), extras, queso, true)
        actuales = alternarExtra(actuales, extras, tocino, true)
        actuales = alternarExtra(actuales, extras, cebolla, true)
        assertEquals(listOf("queso", "tocino"), actuales.map { it.modifierId })
    }

    @Test
    fun `marcar dos veces el mismo extra no lo repite`() {
        val una = alternarExtra(emptyList(), extras, queso, true)
        assertEquals(una, alternarExtra(una, extras, queso, true))
    }

    @Test
    fun `quitar un extra lo quita y respeta los demas grupos`() {
        val medio = termino.modifiers[0]
        val actuales = listOf(sel(termino, medio), sel(extras, queso), sel(extras, tocino))
        val sin = alternarExtra(actuales, extras, queso, false)
        assertEquals(listOf("medio", "tocino"), sin.map { it.modifierId })
    }

    @Test
    fun `el extra elegido lleva grupo nombre y precio en centavos`() {
        val elegido = alternarExtra(emptyList(), extras, queso, true).single()
        assertEquals("g-extras", elegido.groupId)
        assertEquals("Extras", elegido.groupName)
        assertEquals("Queso", elegido.modifierName)
        assertEquals(1500, elegido.priceInCents)
    }

    // ── faltantesDelCombo ────────────────────────────────────────────────────

    private val gruposPorOpcion: (PromotionOption) -> List<ProductModifierGroup>? = { o ->
        if (o.id == "o-hamburguesa") listOf(termino, extras) else null
    }

    private val comboHamburguesa = combo(PromotionGroup("g-plato", "Plato", listOf(oHamburguesa)))

    @Test
    fun `un obligatorio con minSelections 0 pide uno`() {
        val faltan = faltantesDelCombo(comboHamburguesa, emptyMap(), emptyMap(), gruposPorOpcion)
        assertEquals(listOf("Falta elegir «Término» de Hamburguesa."), faltan)
    }

    @Test
    fun `un obligatorio ya elegido no falta`() {
        val extrasElegidos = mapOf("g-plato" to listOf(sel(termino, termino.modifiers[0])))
        assertTrue(faltantesDelCombo(comboHamburguesa, emptyMap(), extrasElegidos, gruposPorOpcion).isEmpty())
    }

    @Test
    fun `un obligatorio con minimo 2 pide dos`() {
        val exige2 = extras.copy(required = true, minSelections = 2)
        val grupos: (PromotionOption) -> List<ProductModifierGroup>? = { listOf(exige2) }
        val uno = mapOf("g-plato" to listOf(sel(exige2, queso)))
        assertEquals(1, faltantesDelCombo(comboHamburguesa, emptyMap(), uno, grupos).size)
        val dos = mapOf("g-plato" to listOf(sel(exige2, queso), sel(exige2, tocino)))
        assertTrue(faltantesDelCombo(comboHamburguesa, emptyMap(), dos, grupos).isEmpty())
    }

    @Test
    fun `la opcion elegida del grupo es la que se revisa`() {
        val comboDos = combo(PromotionGroup("g-plato", "Plato", listOf(oHamburguesa, oRefresco)))
        // Eligió el refresco (sin grupos): no falta nada. Sin elegir aún: no se revisa.
        assertTrue(faltantesDelCombo(comboDos, mapOf("g-plato" to "o-refresco"), emptyMap(), gruposPorOpcion).isEmpty())
        assertTrue(faltantesDelCombo(comboDos, emptyMap(), emptyMap(), gruposPorOpcion).isEmpty())
        assertEquals(1, faltantesDelCombo(comboDos, mapOf("g-plato" to "o-hamburguesa"), emptyMap(), gruposPorOpcion).size)
    }

    @Test
    fun `una pieza que se vende como hoy no pide nada`() {
        assertTrue(faltantesDelCombo(comboHamburguesa, emptyMap(), emptyMap()) { null }.isEmpty())
    }

    // ── extrasCents ──────────────────────────────────────────────────────────

    @Test
    fun `extrasCents suma los extras de todas las piezas`() {
        val elegidos = mapOf(
            "g-plato" to listOf(sel(extras, queso), sel(extras, tocino)),
            "g-bebida" to listOf(sel(extras, cebolla)),
        )
        assertEquals(3500, extrasCents(elegidos))
        assertEquals(0, extrasCents(emptyMap()))
    }

    // ── comboPideExtras ──────────────────────────────────────────────────────

    @Test
    fun `una pieza fija con grupos hace que el combo pida extras`() {
        assertTrue(comboPideExtras(comboHamburguesa, gruposPorOpcion))
    }

    @Test
    fun `sin grupos en ninguna pieza el combo no pide extras`() {
        assertFalse(comboPideExtras(comboHamburguesa) { null })
        assertFalse(comboPideExtras(comboHamburguesa) { emptyList() })
    }
}
