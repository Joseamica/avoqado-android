package com.avoqado.pos.printing.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D1 «fotocopia del ticket» (28-oct): la copia de los renglones del papel de una parte, con los topes del SERVIDOR
 * (`lineasDelTicket.ts`). Lo que no cabe no se manda (null) y el cobro va como hoy.
 */
class CopiaDelTicketTest {

    private fun leer(json: String?): JsonObject = Json.parseToJsonElement(requireNotNull(json)).jsonObject
    private fun JsonObject.lineas() = getValue("lineas").jsonArray.map { it.jsonObject }
    private fun JsonObject.cents(llave: String) = getValue(llave).jsonPrimitive.int

    private val cafe = ReceiptItem(name = "Café", quantity = 1, unitPrice = 3000, totalPrice = 3000)

    @Test
    fun `Cafe sola de Cafe 30 y Agua 20 - un renglon Cafe 3000, subtotal 3000 y descuento 0`() {
        val copia = leer(CopiaDelTicket.armar(listOf(cafe), subtotalBruto = 3000, cobradoSinPropina = 3000))
        assertEquals(1, copia.lineas().size)
        val linea = copia.lineas().single()
        assertEquals("Café", linea.getValue("nombre").jsonPrimitive.content)
        assertEquals(1, linea.cents("cantidad"))
        assertEquals(3000, linea.cents("unitarioCents"))
        assertEquals(3000, linea.cents("totalCents"))
        // Lo opcional vacío no viaja.
        assertEquals(setOf("nombre", "cantidad", "unitarioCents", "totalCents"), linea.keys)
        assertEquals(3000, copia.cents("subtotalCents"))
        assertEquals(0, copia.cents("descuentoCents"))
    }

    @Test
    fun `con 10 por ciento de descuento de cuenta - Cafe 3000, subtotal 3000 y descuento 300`() {
        val copia = leer(CopiaDelTicket.armar(listOf(cafe), subtotalBruto = 3000, cobradoSinPropina = 2700))
        assertEquals(3000, copia.lineas().single().cents("totalCents"))
        assertEquals(3000, copia.cents("subtotalCents"))
        assertEquals(300, copia.cents("descuentoCents"))
    }

    @Test
    fun `partes iguales - el renglon Pago parcial que ya imprime el papel`() {
        val parcial = ReceiptItem(name = "Pago parcial", quantity = 1, unitPrice = 2500, totalPrice = 2500)
        val copia = leer(CopiaDelTicket.armar(listOf(parcial), subtotalBruto = 2500, cobradoSinPropina = 2500))
        assertEquals("Pago parcial", copia.lineas().single().getValue("nombre").jsonPrimitive.content)
        assertEquals(2500, copia.cents("subtotalCents"))
        assertEquals(0, copia.cents("descuentoCents"))
    }

    @Test
    fun `producto pesado con nota, area y modificadores - cada texto en su renglon`() {
        val queso = ReceiptItem(
            name = "Queso Oaxaca", quantity = 1, unitPrice = 18270, totalPrice = 18270,
            modifiers = listOf("Rebanado", " "), note = "Sin cáscara", weightSummary = "0.435 kg × \$420.00/kg",
            areaSourceLabel = "Cremería · Vale 9016719357",
        )
        val linea = leer(CopiaDelTicket.armar(listOf(queso), 18270, 18270)).lineas().single()
        assertEquals("0.435 kg × \$420.00/kg", linea.getValue("peso").jsonPrimitive.content)
        assertEquals("Sin cáscara", linea.getValue("nota").jsonPrimitive.content)
        assertEquals("Cremería · Vale 9016719357", linea.getValue("area").jsonPrimitive.content)
        // Un modificador en blanco no viaja (el SERVIDOR exige de 1 a 60 caracteres).
        assertEquals(listOf("Rebanado"), linea.getValue("modificadores").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `combo - el renglon del combo lleva el precio y sus piezas van en 0 marcadas`() {
        val renglones = ComboPrintLines.receipt(
            listOf(
                ComboTag("c1", "Combo del día") to ReceiptItem("Hamburguesa", 1, 8000, 8000),
                ComboTag("c1", "Combo del día") to ReceiptItem("Refresco", 1, 2000, 2000),
            ),
        )
        val copia = leer(CopiaDelTicket.armar(renglones, 10000, 10000))
        val lineas = copia.lineas()
        assertEquals(listOf("Combo del día", "Hamburguesa", "Refresco"), lineas.map { it.getValue("nombre").jsonPrimitive.content })
        assertEquals(listOf(10000, 0, 0), lineas.map { it.cents("totalCents") })
        assertFalse(lineas[0].containsKey("pieza"))
        assertTrue(lineas.drop(1).all { it.getValue("pieza").jsonPrimitive.content == "true" })
        assertEquals(10000, copia.cents("subtotalCents"))
    }

    @Test
    fun `cortesia viaja marcada`() {
        val regalo = ReceiptItem("Postre", 1, 5000, 0, isCortesia = true)
        val linea = leer(CopiaDelTicket.armar(listOf(cafe, regalo), 3000, 3000)).lineas()[1]
        assertEquals("true", linea.getValue("cortesia").jsonPrimitive.content)
    }

    @Test
    fun `comillas, diagonales y etiquetas en el nombre - JSON valido que regresa igual`() {
        val raro = "Café \"La Güera\" \\ <b>doble</b>"
        val json = CopiaDelTicket.armar(listOf(cafe.copy(name = raro)), 3000, 3000)
        assertEquals(raro, leer(json).lineas().single().getValue("nombre").jsonPrimitive.content)
    }

    @Test
    fun `una copia de mas de 8 KB no se manda`() {
        // 100 renglones (el tope) de 120 caracteres con su nota: cada uno cabe, la copia entera no.
        val renglones = (1..100).map { ReceiptItem("x".repeat(118) + "%02d".format(it % 100), 1, 100, 100, note = "n".repeat(140)) }
        assertNull(CopiaDelTicket.armar(renglones, 10_000, 10_000))
        // Control: los mismos renglones, sin nota y con nombre corto, sí caben.
        assertNotNull(CopiaDelTicket.armar(renglones.map { it.copy(name = "Pan", note = null) }, 10_000, 10_000))
    }

    @Test
    fun `fuera de los topes del servidor no se manda`() {
        val muchos = (1..101).map { ReceiptItem("Pan", 1, 100, 100) }
        assertNull("más de 100 renglones", CopiaDelTicket.armar(muchos, 10_100, 10_100))
        assertNull("nombre de más de 120", CopiaDelTicket.armar(listOf(cafe.copy(name = "x".repeat(121))), 3000, 3000))
        assertNull("nombre vacío", CopiaDelTicket.armar(listOf(cafe.copy(name = " ")), 3000, 3000))
        assertNull("modificador de más de 60", CopiaDelTicket.armar(listOf(cafe.copy(modifiers = listOf("m".repeat(61)))), 3000, 3000))
        assertNull("más de 10 modificadores", CopiaDelTicket.armar(listOf(cafe.copy(modifiers = (1..11).map { "m$it" })), 3000, 3000))
        assertNull("peso de más de 40", CopiaDelTicket.armar(listOf(cafe.copy(weightSummary = "p".repeat(41))), 3000, 3000))
        assertNull("nota de más de 140", CopiaDelTicket.armar(listOf(cafe.copy(note = "n".repeat(141))), 3000, 3000))
        assertNull("área de más de 60", CopiaDelTicket.armar(listOf(cafe.copy(areaSourceLabel = "a".repeat(61))), 3000, 3000))
        assertNull("sin renglones", CopiaDelTicket.armar(emptyList(), 0, 0))
        // Control: en el tope exacto, sí.
        assertNotNull(CopiaDelTicket.armar(listOf(cafe.copy(name = "x".repeat(120), modifiers = (1..10).map { "m".repeat(60) },
            weightSummary = "p".repeat(40), note = "n".repeat(140), areaSourceLabel = "a".repeat(60))), 3000, 3000))
    }

    @Test
    fun `un papel que no cuadra consigo mismo o con IVA o cargo aparte no tiene copia`() {
        // Lo cobrado supera lo bruto: el papel imprime subtotal = cobrado y sus renglones ya no lo suman.
        assertNull(CopiaDelTicket.armar(listOf(cafe), subtotalBruto = 3000, cobradoSinPropina = 3500))
        // El bruto que no es la suma de los renglones.
        assertNull(CopiaDelTicket.armar(listOf(cafe), subtotalBruto = 5000, cobradoSinPropina = 3000))
        assertNull(CopiaDelTicket.armar(listOf(cafe), 3000, 3480, impuestoAgregado = 480))
        assertNull(CopiaDelTicket.armar(listOf(cafe), 3000, 3300, cargo = 300))
    }
}
