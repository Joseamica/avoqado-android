package com.avoqado.pos.pos.domain

import com.avoqado.pos.pos.data.model.Modifier
import com.avoqado.pos.pos.data.model.ProductModifierGroup
import com.avoqado.pos.pos.data.model.SelectedModifier
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D40 (Flautas Coyoacán): una nota por cada parte del paquete. La nota es de la OPCIÓN (todas sus veces), viaja aparte al SERVIDOR
 * y dentro de la etiqueta a la cocina, en una segunda línea («Consomé⏎Nota: sin cebolla»), igual que `toKdsModifierLabels` del SERVIDOR.
 */
class NotaDelExtraTest {

    private val consome = SelectedModifier("g-sopa", "Sopa", "consome", "Consomé", 0)
    private val cochinita = Modifier(id = "cochinita", name = "Flautas Cochinita")
    private val flautas = ProductModifierGroup(
        id = "g-flautas", name = "Flautas", required = true, multipleSelect = true, allowRepeat = true,
        minSelections = 3, maxSelections = 3, modifiers = listOf(cochinita),
    )

    @Test
    fun `P1 la nota sale debajo de su parte, también con la opción repetida`() {
        var lista = sumarExtra(listOf(consome), flautas, cochinita)
        lista = ponerNotaAlExtra(lista, "g-flautas", "cochinita", "salsa combinada")
        lista = sumarExtra(lista, flautas, cochinita) // la segunda vez hereda la nota de la opción
        lista = ponerNotaAlExtra(lista, "g-sopa", "consome", "sin cebolla")

        val etiquetas = juntarExtrasRepetidos(lista.etiquetasEnCocina())
        assertEquals(listOf("Consomé\nNota: sin cebolla", "2x Flautas Cochinita\nNota: salsa combinada"), etiquetas)
        assertEquals("2x Flautas Cochinita" to "salsa combinada", separarNotaDelExtra(etiquetas[1]))
        // La caja lo lee en un renglón; el reparto por WiFi de una comanda enorme la manda sin la nota.
        assertEquals("Consomé (Nota: sin cebolla)", enUnRenglon(etiquetas[0]))
        assertEquals("2x Flautas Cochinita", sinNota(etiquetas[1]))
        assertEquals(mapOf("consome" to "sin cebolla", "cochinita" to "salsa combinada"), lista.notasPorExtra())
    }

    @Test
    fun `P1 sin nota todo sale como siempre`() {
        val lista = listOf(consome, consome)
        assertEquals(listOf("2x Consomé"), juntarExtrasRepetidos(lista.etiquetasEnCocina()))
        assertEquals("Consomé" to null, separarNotaDelExtra("Consomé"))
        assertEquals(emptyMap<String, String>(), lista.notasPorExtra())
        // Un carrito guardado por una versión vieja (sin la llave) se lee sin nota.
        assertNull(Json { ignoreUnknownKeys = true }.decodeFromString<SelectedModifier>(
            """{"groupId":"g","groupName":"G","modifierId":"m","modifierName":"M","priceInCents":0}""").note)
    }

    @Test
    fun `P2 la nota se limpia y quitarla la borra de todas las veces`() {
        assertEquals("sin cebolla", limpiarNotaDelExtra("  sin\n cebolla \t"))
        assertEquals("sin cebolla", limpiarNotaDelExtra("sin\u0000cebolla\u0007")) // un NUL tumbaba el guardado en Postgres
        assertNull(limpiarNotaDelExtra("   "))
        assertEquals(MAX_NOTA_DEL_EXTRA, limpiarNotaDelExtra("x".repeat(500))!!.length)

        val con = ponerNotaAlExtra(listOf(consome, consome), "g-sopa", "consome", "sin cebolla")
        assertEquals("sin cebolla", notaDelExtra(con, "g-sopa", "consome"))
        val sin = ponerNotaAlExtra(con, "g-sopa", "consome", " ")
        assertEquals(listOf(null, null), sin.map { it.note })
    }

    @Test
    fun `P1 un extra que se llama como una nota no se confunde (Codex r1 #5)`() {
        assertEquals("Consomé (Nota: sin cebolla)" to null, separarNotaDelExtra("Consomé (Nota: sin cebolla)"))
        // Un nombre nunca lleva salto de línea: el «⏎Nota: » sólo lo pone la nota.
        assertEquals("Consomé Nota: x", etiquetaEnCocina("Consomé\nNota: x", null))
        assertEquals("Consomé Nota: x" to null, separarNotaDelExtra(etiquetaEnCocina("Consomé\nNota: x", null)))
        assertEquals("\nNota: x" to null, separarNotaDelExtra("\nNota: x"))
    }
}
