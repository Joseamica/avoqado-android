package com.avoqado.pos.areatickets.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AreaTicketExternalCodesTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun line(
        quantity: String = "1.000",
        sku: String? = "P000500",
        modifiers: List<AreaTicketModifierSnapshot> = emptyList(),
    ) = AreaTicketLine(
        id = "l1", clientLineId = "c1", productNameSnapshot = "Latte", skuSnapshot = sku,
        quantity = quantity, unitPrice = "55.00", total = "55.00", modifiersSnapshot = modifiers,
    )

    @Test
    fun `P1 dos piezas con extra imprimen cada codigo dos veces`() {
        val codes = line(
            quantity = "2.000",
            modifiers = listOf(AreaTicketModifierSnapshot(name = "Shot de espresso", price = "15.00", sku = "P000672")),
        ).externalCodes()
        assertEquals(listOf("P000500", "P000500", "P000672", "P000672"), codes)
    }

    @Test
    fun `P1 extra de cantidad 2 en dos piezas sale cuatro veces`() {
        val codes = line(
            quantity = "2.000",
            modifiers = listOf(
                AreaTicketModifierSnapshot(name = "Shot de espresso", quantity = 2, price = "30.00", sku = "P000672"),
            ),
        ).externalCodes()
        assertEquals(
            listOf("P000500", "P000500", "P000672", "P000672", "P000672", "P000672"),
            codes,
        )
    }

    @Test
    fun `un extra sin codigo no sale y un codigo en blanco tampoco`() {
        val codes = line(
            modifiers = listOf(
                AreaTicketModifierSnapshot(name = "Sin azúcar", price = "0.00", sku = null),
                AreaTicketModifierSnapshot(name = "Raro", price = "0.00", sku = "   "),
            ),
        ).externalCodes()
        assertEquals(listOf("P000500"), codes)
    }

    @Test
    fun `un vale viejo sin settlementRoute ni modifiersSnapshot se lee como ruta normal`() {
        val ticket = json.decodeFromString(
            AreaTicket.serializer(),
            """{"id":"t1","code":"9470000015","status":"ISSUED","fulfillmentArea":{"id":"a1","name":"Cafetería","fulfillmentMode":"IMMEDIATE"},
               "subtotal":"55.00","total":"55.00","issuedAt":"2026-09-30T20:32:00.000Z",
               "lines":[{"id":"l1","clientLineId":"c1","productNameSnapshot":"Latte","quantity":"1.000","unitPrice":"55.00","total":"55.00"}]}""",
        )
        assertFalse(ticket.isExternalRoute)
        assertTrue(ticket.lines.single().modifiersSnapshot.isEmpty())
    }

    @Test
    fun `un vale externo trae ruta y el sku de cada extra`() {
        val ticket = json.decodeFromString(
            AreaTicket.serializer(),
            """{"id":"t1","code":"9470000015","status":"ISSUED","settlementRoute":"EXTERNAL",
               "fulfillmentArea":{"id":"a1","name":"Cafetería","fulfillmentMode":"IMMEDIATE"},
               "subtotal":"70.00","total":"70.00","issuedAt":"2026-09-30T20:32:00.000Z",
               "lines":[{"id":"l1","clientLineId":"c1","productNameSnapshot":"Latte","skuSnapshot":"P000500","quantity":"1.000","unitPrice":"55.00","total":"70.00",
                 "modifiersSnapshot":[{"modifierId":"m1","name":"Shot de espresso","quantity":1,"price":"15.00","sku":"P000672"}]}]}""",
        )
        assertTrue(ticket.isExternalRoute)
        assertEquals(listOf("P000500", "P000672"), ticket.lines.single().externalCodes())
    }

    // -- Respaldos (Task 8): lo que el servidor no manda hoy, pero si llega, el vale sale con una pieza --------------

    @Test
    fun `una cantidad que no es numero cuenta como una pieza`() {
        assertEquals(listOf("P000500"), line(quantity = "dos").externalCodes())
    }

    @Test
    fun `una cantidad cero o negativa cuenta como una pieza`() {
        assertEquals(listOf("P000500"), line(quantity = "0").externalCodes())
        assertEquals(listOf("P000500"), line(quantity = "-2.000").externalCodes())
    }

    @Test
    fun `el codigo del producto se recorta`() {
        assertEquals(listOf("P000500"), line(sku = "  P000500 ").externalCodes())
    }

    @Test
    fun `un extra con sku null explicito en el JSON no lleva codigo`() {
        val ticket = json.decodeFromString(
            AreaTicket.serializer(),
            """{"id":"t1","code":"9470000015","status":"ISSUED","settlementRoute":"EXTERNAL",
               "fulfillmentArea":{"id":"a1","name":"Cafetería","fulfillmentMode":"IMMEDIATE"},
               "subtotal":"55.00","total":"55.00","issuedAt":"2026-09-30T20:32:00.000Z",
               "lines":[{"id":"l1","clientLineId":"c1","productNameSnapshot":"Latte","skuSnapshot":"P000500","quantity":"1.000","unitPrice":"55.00","total":"55.00",
                 "modifiersSnapshot":[{"modifierId":"m1","name":"Sin azúcar","quantity":1,"price":"0.00","sku":null}]}]}""",
        )
        val renglon = ticket.lines.single()

        assertNull(renglon.modifiersSnapshot.single().sku)
        assertEquals(listOf("P000500"), renglon.externalCodes())
    }
}
