package com.avoqado.pos.tables.presentation

import com.avoqado.pos.tables.data.AppliedOrderDiscount
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D15 (pizarrón hz:e82938e1:D15, 18-nov): en POS SOLO → MESAS (diálogo «Descuentos»), la rebaja que quedó fija al cobrar vales se
 * sigue viendo con su monto, pero su «Quitar» sale en gris con «Fija: viene del cobro de vales», y al tocar la fila sale sólo la razón.
 * El SERVIDOR la marca con `congeladoPorVales`; uno viejo no manda el campo y la fila queda como hoy.
 */
class RebajaFijaTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `D15 la rebaja fija ensena Quitar en gris con su nota y su razon`() {
        val q = quitarDeLaRebaja(AppliedOrderDiscount(id = "od-vale", name = "10 %", amount = 10.0, congeladoPorVales = true))
        assertFalse(q.quitarActivo)
        assertEquals("Fija: viene del cobro de vales", q.nota)
        assertEquals(
            "Esta rebaja quedó fija al cobrar los vales y no se quita desde la caja. Si hay que cambiarla, avisa a un encargado.",
            q.razon,
        )
    }

    @Test
    fun `D15 una rebaja normal sigue como hoy`() {
        val q = quitarDeLaRebaja(AppliedOrderDiscount(id = "od-cuenta", name = "Cliente frecuente", amount = 5.0))
        assertTrue(q.quitarActivo)
        assertNull(q.nota)
        assertNull(q.razon)
    }

    @Test
    fun `D15 el campo llega del SERVIDOR y uno viejo sin el deja la fila como hoy`() {
        val nuevo = json.decodeFromString<AppliedOrderDiscount>("""{"id":"od-vale","name":"10 %","amount":10.0,"congeladoPorVales":true}""")
        assertTrue(nuevo.congeladoPorVales)
        val viejo = json.decodeFromString<AppliedOrderDiscount>("""{"id":"od-vale","name":"10 %","amount":10.0}""")
        assertFalse(viejo.congeladoPorVales)
        // La copia sin red guarda el detalle con el mismo modelo: el campo sobrevive al guardarse y releerse.
        assertTrue(json.decodeFromString<AppliedOrderDiscount>(json.encodeToString(AppliedOrderDiscount.serializer(), nuevo)).congeladoPorVales)
    }
}

/**
 * D15 (Codex r4 #1): la copia sin red de la mesa (`TableRoundDraftStore`, lo que reabre la mesa sin red) guarda la marca al escribirse y
 * releerse del disco, y el diálogo «Descuentos» usa la regla (se lee la fuente: aquí no hay `createComposeRule`).
 */
class RebajaFijaCopiaYCableadoTest {

    @Test
    fun `D15 la copia sin red de la mesa conserva congeladoPorVales al guardarse y releerse`() {
        val dir = kotlin.io.path.createTempDirectory("d15-borrador").toFile()
        try {
            val store = com.avoqado.pos.tables.data.TableRoundDraftStore(dir)
            val cuenta = com.avoqado.pos.tables.data.OrderDetail(
                id = "orden-1",
                discounts = listOf(
                    AppliedOrderDiscount(id = "od-vale", name = "10 %", amount = 10.0, congeladoPorVales = true),
                    AppliedOrderDiscount(id = "od-cuenta", name = "Cliente frecuente", amount = 5.0),
                ),
            )
            store.save("venue-1", "orden-1", pending = emptyList(), rounds = emptyMap(), cachedCheck = cuenta)

            val releida = com.avoqado.pos.tables.data.TableRoundDraftStore(dir).load("venue-1", "orden-1").cachedCheck!!
            assertEquals(listOf(true, false), releida.discounts.map { it.congeladoPorVales })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `D15 el dialogo Descuentos usa la regla para el boton, la nota y la razon`() {
        val pantalla = java.io.File("src/main/java/com/avoqado/pos/tables/presentation/TableOrderScreen.kt").readText()
        val dialogo = pantalla.substring(pantalla.indexOf("private fun OrderDiscountsDialog("))
        val aplicados = dialogo.substring(0, dialogo.indexOf("Text(\"Disponibles\""))
        assertTrue(aplicados.contains("val quitar = quitarDeLaRebaja(d)"))
        assertTrue(aplicados.contains("TextButton(onClick = { onRemove(d.id) }, enabled = quitar.quitarActivo)"))
        assertTrue(aplicados.contains("quitar.nota?.let"))
        assertTrue(aplicados.contains("Modifier.clickable(onClickLabel = \"Ver por qué está fija\") { conRazon = !conRazon }"))
        assertTrue(aplicados.contains("quitar.razon?.let"))
    }
}
