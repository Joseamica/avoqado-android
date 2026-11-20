package com.avoqado.pos.pos.domain

import com.avoqado.pos.pos.data.model.ModifierGroupData
import com.avoqado.pos.pos.data.model.Modifier
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductModifierGroup
import com.avoqado.pos.pos.data.model.ProductModifierGroupEntry
import com.avoqado.pos.pos.data.model.SelectedModifier
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repetir la misma opción en un grupo (encargo a508f4, Flautas Coyoacán): «2 de Cochinita + 1 de Papa». Cada vez es un
 * [SelectedModifier] más (el SERVIDOR recibe el id repetido y cobra cada uno a su precio).
 */
class SeleccionDeExtrasTest {

    private val cochinita = Modifier(id = "cochinita", name = "Flautas Cochinita")
    private val papa = Modifier(id = "papa", name = "Flautas Papa")
    private val queso = Modifier(id = "queso", name = "Flautas Queso", price = 15.0, maxQuantity = 1)

    private val flautas = ProductModifierGroup(
        id = "g-flautas",
        name = "Flautas (hasta 3)",
        required = true,
        multipleSelect = true,
        allowRepeat = true,
        minSelections = 3,
        maxSelections = 3,
        modifiers = listOf(cochinita, papa, queso),
    )
    private val salsas = flautas.copy(id = "g-salsas", name = "Salsas", allowRepeat = false, required = false, maxSelections = 2)

    private fun sumar(actuales: List<SelectedModifier>, extra: Modifier, grupo: ProductModifierGroup = flautas) =
        sumarExtra(actuales, grupo, extra)

    @Test
    fun `2 de Cochinita y 1 de Papa son tres selecciones y cumplen el mínimo de 3`() {
        val s = sumar(sumar(sumar(emptyList(), cochinita), cochinita), papa)
        assertEquals(listOf("cochinita", "cochinita", "papa"), s.map { it.modifierId })
        assertEquals(2, vecesElegida(s, flautas, cochinita))
        assertEquals(3, s.count { it.groupId == flautas.id })
    }

    @Test
    fun `al llegar al máximo del grupo ya no suma ninguna opción`() {
        val tres = sumar(sumar(sumar(emptyList(), cochinita), cochinita), cochinita)
        assertFalse(puedeSumar(tres, flautas, papa))
        assertEquals(tres, sumar(tres, papa))
    }

    @Test
    fun `el tope de la opción manda aunque el grupo tenga lugar`() {
        val uno = sumar(emptyList(), queso)
        assertFalse(puedeSumar(uno, flautas, queso))
        assertEquals(1, vecesElegida(sumar(uno, queso), flautas, queso))
        assertTrue(puedeSumar(uno, flautas, papa))
    }

    @Test
    fun `restar quita UNA vez, la última, y dejar en cero la quita del todo`() {
        val s = sumar(sumar(sumar(emptyList(), cochinita), papa), cochinita)
        val menos = restarExtra(s, flautas, cochinita)
        assertEquals(listOf("cochinita", "papa"), menos.map { it.modifierId })
        val cero = restarExtra(restarExtra(menos, flautas, cochinita), flautas, cochinita)
        assertEquals(listOf("papa"), cero.map { it.modifierId })
    }

    @Test
    fun `restar no toca la misma opción de otro grupo`() {
        val otroGrupo = SelectedModifier("otro", "Otro", "cochinita", "Flautas Cochinita", 0)
        val s = listOf(otroGrupo) + sumar(emptyList(), cochinita)
        assertEquals(listOf(otroGrupo), restarExtra(s, flautas, cochinita))
    }

    @Test
    fun `sin máximo en el grupo suma sin tope salvo el de la opción`() {
        val libre = flautas.copy(maxSelections = null)
        var s = emptyList<SelectedModifier>()
        repeat(6) { s = sumarExtra(s, libre, cochinita) }
        assertEquals(6, vecesElegida(s, libre, cochinita))
    }

    @Test
    fun `sólo cuenta como «repetir» si el grupo deja elegir varias`() {
        assertTrue(flautas.dejaRepetir)
        assertFalse(salsas.dejaRepetir)
        assertFalse(flautas.copy(multipleSelect = false).dejaRepetir)
    }

    @Test
    fun `cada vez se cobra a su precio`() {
        val s = sumar(sumar(sumar(emptyList(), cochinita), papa), cochinita)
        val conQueso = sumarExtra(s.dropLast(1), flautas, queso)
        assertEquals(1500, conQueso.sumOf { it.priceInCents })
    }

    @Test
    fun `en pantalla y en papel la misma opción se junta como «2x Nombre» en su orden de aparición`() {
        assertEquals(
            listOf("2x Flautas Cochinita", "Flautas Papa"),
            juntarExtrasRepetidos(listOf("Flautas Cochinita", "Flautas Papa", "Flautas Cochinita")),
        )
        assertEquals(listOf("Queso", "Tocino"), juntarExtrasRepetidos(listOf("Queso", "Tocino")))
        assertEquals(emptyList<String>(), juntarExtrasRepetidos(emptyList()))
    }

    @Test
    fun `lo juntado sólo usa letras que toda impresora tiene (PC850 y PC437 no traen el signo por)`() {
        val juntado = juntarExtrasRepetidos(listOf("Queso", "Queso"))
        assertEquals(listOf("2x Queso"), juntado)
        assertTrue(juntado.single().all { it.code < 128 })
    }

    @Test
    fun `el catálogo nuevo trae allowRepeat y maxQuantity, y uno viejo sin ellos se lee como hoy`() {
        val json = Json { ignoreUnknownKeys = true }
        val nuevo = json.decodeFromString(
            ModifierGroupData.serializer(),
            """{"id":"g","name":"F","allowMultiple":true,"allowRepeat":true,"maxSelections":3,"modifiers":[{"id":"m","name":"C","price":0,"maxQuantity":2}]}""",
        )
        assertTrue(nuevo.allowRepeat)
        assertEquals(2, nuevo.modifiers.single().maxQuantity)
        val viejo = json.decodeFromString(
            ModifierGroupData.serializer(),
            """{"id":"g","name":"F","allowMultiple":true,"maxSelections":3,"modifiers":[{"id":"m","name":"C","price":0}]}""",
        )
        assertFalse(viejo.allowRepeat)
        assertEquals(null, viejo.modifiers.single().maxQuantity)
        val producto = Product(id = "p", name = "Paquete", priceValue = 168.0, modifierGroups = listOf(ProductModifierGroupEntry(group = nuevo)))
        assertTrue(producto.sortedModifierGroups.single().allowRepeat)
    }
}
