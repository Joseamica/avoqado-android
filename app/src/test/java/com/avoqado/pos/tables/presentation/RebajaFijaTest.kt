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
