package com.avoqado.pos.printing.data

import com.avoqado.pos.inventory.data.model.StockCount
import com.avoqado.pos.inventory.data.model.StockCountItem
import com.avoqado.pos.inventory.data.model.StockCountType
import com.avoqado.pos.printing.data.model.PaperWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprobante impreso de un conteo completado (gratis dentro de conteos). Lo que no puede fallar:
 * la diferencia con su signo y unidad (contado − esperado), que lo NO contado no se presente como
 * faltante, y que las diferencias vayan primero: es lo que el encargado firma.
 */
class ComprobanteDeConteoTest {

    private fun item(nombre: String, esperado: Double, contado: Double, contada: Boolean = true, unit: String? = null) =
        StockCountItem(
            productName = nombre,
            expected = esperado,
            counted = contado,
            unit = unit,
            countedAt = if (contada) "2026-09-23T15:00:00Z" else null,
        )

    private val conteo = StockCount(
        id = "cmtabc12345678xyz",
        type = StockCountType.FULL,
        status = "COMPLETED",
        createdBy = "Viridiana",
        note = "Cierre de mes",
        items = listOf(
            item("Agua", 10.0, 10.0),
            item("Coca-Cola", 12.0, 9.0),
            item("Jamon", 2.0, 2.5, unit = "KILOGRAM"),
            item("Pan", 5.0, 0.0, contada = false),
        ),
    )

    private val comprobante = ComprobanteDeConteo.desde(conteo, negocio = "Testarudo", fecha = "23/09/2026 09:30")

    @Test
    fun `la diferencia es contado menos esperado, con signo y unidad`() {
        val porNombre = comprobante.renglones.associateBy { it.nombre }
        assertEquals("0", porNombre.getValue("Agua").diferencia)
        assertEquals("-3", porNombre.getValue("Coca-Cola").diferencia)
        assertEquals("+0.5 kg", porNombre.getValue("Jamon").diferencia)
    }

    @Test
    fun `lo no contado no aparece como faltante, se cuenta aparte`() {
        assertFalse(comprobante.renglones.any { it.nombre == "Pan" })
        assertEquals(1, comprobante.sinContar)
    }

    @Test
    fun `las diferencias van primero`() {
        assertEquals(listOf("Coca-Cola", "Jamon", "Agua"), comprobante.renglones.map { it.nombre })
    }

    @Test
    fun `el papel trae encabezado, folio, resumen y firmas`() {
        val t = ESCPOSPrinter(PaperWidth.MM58).generateCountReceipt(comprobante).toString(Charsets.ISO_8859_1)
        assertTrue(t.contains("Testarudo"))
        assertTrue(t.contains("Conteo completo"))
        assertTrue(t.contains("Folio 5678XYZ".uppercase()) || t.contains("5678XYZ"))
        assertTrue(t.contains("Viridiana"))
        assertTrue(t.contains("Cierre de mes"))
        assertTrue(t.contains("Contados: 3 de 4"))
        assertTrue(t.contains("Con diferencia: 2"))
        assertTrue(t.contains("Sin contar: 1"))
        assertTrue(t.contains("Contó") && t.contains("Revisó"))
    }
}
