package com.avoqado.pos.areatickets.data

import com.avoqado.pos.printing.data.model.AreaTicketData
import com.avoqado.pos.printing.data.model.ReceiptItem
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Del vale del servidor a lo imprimible (caja externa, spec 2026-09-30). El vale NORMAL no puede
 * cambiar ni un campo respecto a como se armaba antes dentro del ViewModel: lo externo sólo AGREGA.
 */
class AreaTicketPrintableTest {

    /** 2026-09-30T20:32:00.000Z, el `issuedAt` de todos los vales de esta prueba. */
    private val emitidoA = Date(1_790_800_320_000L)

    private val shot = AreaTicketModifierSnapshot(
        modifierId = "m1", name = "Shot de espresso", price = "15.00", sku = "P000672",
    )

    private fun line(
        name: String = "Latte",
        quantity: String = "1.000",
        sku: String? = "P000500",
        unitPrice: String = "55.00",
        total: String = "55.00",
        weightKg: String? = null,
        notes: String? = null,
        modifiers: List<AreaTicketModifierSnapshot> = emptyList(),
        discountAmount: String = "0.00",
    ) = AreaTicketLine(
        id = "l1", clientLineId = "c1", productNameSnapshot = name, skuSnapshot = sku,
        quantity = quantity, weightKg = weightKg, unitPrice = unitPrice, discountAmount = discountAmount, total = total,
        notes = notes, modifiersSnapshot = modifiers,
    )

    /** Un renglón por peso: 0.250 kg de jamón a $100.00/kg = $25.00 (la ruta normal lo pesa; la pistola no). */
    private fun porPeso() = line(
        name = "Jamón", quantity = "1", sku = "P000123", unitPrice = "100.00", total = "25.00", weightKg = "0.250",
    )

    /** Un renglón con descuento por renglón: $55.00 de lista, $50.00 cobrados. */
    private fun conDescuento() = line(total = "50.00", discountAmount = "5.00")

    private fun ticket(
        route: String,
        total: String,
        lines: List<AreaTicketLine>,
        mode: String = "IMMEDIATE",
    ) = AreaTicket(
        id = "t1", code = "9470000015", status = "ISSUED",
        fulfillmentArea = AreaTicketArea(id = "a1", name = "Cafetería", fulfillmentMode = mode),
        subtotal = total, total = total, issuedAt = "2026-09-30T20:32:00.000Z",
        lines = lines, settlementRoute = route,
    )

    @Test
    fun `P1 el vale normal no cambia - el extra del snapshot no sale ni hay codigos`() {
        val vale = ticket(
            route = "AVOQADO",
            total = "176.74",
            lines = listOf(
                line(quantity = "2.000", total = "140.00", notes = "Sin espuma", modifiers = listOf(shot)),
                line(name = "LOMO CANADIENSE", sku = null, unitPrice = "164.00", total = "36.74", weightKg = "0.224"),
            ),
        )

        val data = vale.toAreaTicketData("La Galeterie")

        assertFalse(data.externalRoute)
        assertNull(data.items[0].modifiers)
        assertTrue(data.items[0].externalCodes.isEmpty())
        // Y lo demás, campo por campo, exactamente como se armaba antes en el ViewModel.
        assertEquals(
            AreaTicketData(
                areaTicketCode = "9470000015",
                areaName = "Cafetería",
                items = listOf(
                    ReceiptItem(name = "Latte", quantity = 2, unitPrice = 5500, totalPrice = 14000, note = "Sin espuma"),
                    ReceiptItem(
                        name = "LOMO CANADIENSE", quantity = 1, unitPrice = 16400, totalPrice = 3674,
                        weightSummary = "0.224 kg × \$164.00/kg",
                    ),
                ),
                totalCents = 17674,
                venueName = "La Galeterie",
                timestamp = emitidoA,
                holdsProduct = false,
            ),
            data,
        )
    }

    @Test
    fun `P1 el vale externo lleva el extra, un codigo por pieza, el importe y la hora del vale`() {
        val vale = ticket(
            route = "EXTERNAL",
            total = "140.00",
            lines = listOf(line(quantity = "2.000", total = "140.00", modifiers = listOf(shot))),
        )

        val data = vale.toAreaTicketData("La Galeterie")

        assertTrue(data.externalRoute)
        assertEquals(listOf("Shot de espresso"), data.items[0].modifiers)
        assertEquals(listOf("P000500", "P000500", "P000672", "P000672"), data.items[0].externalCodes)
        assertEquals(14000, data.totalCents)
        assertEquals(emitidoA, data.timestamp)
    }

    @Test
    fun `P1 vale externo viejo - el extra que cobra y no trae codigo se ve en el papel, el gratis no`() {
        val vale = ticket(
            route = "EXTERNAL",
            total = "70.00",
            lines = listOf(
                line(
                    total = "70.00",
                    modifiers = listOf(
                        AreaTicketModifierSnapshot(name = "Leche de almendra", price = "10.00"),
                        AreaTicketModifierSnapshot(name = "Sin azúcar", price = "0.00"),
                        AreaTicketModifierSnapshot(name = "Canela", price = "5.00", sku = "   "),
                    ),
                ),
            ),
        )

        val item = vale.toAreaTicketData(null).items[0]

        assertEquals(
            listOf(
                "Leche de almendra (sin código: cóbralo a mano)",
                "Sin azúcar",
                "Canela (sin código: cóbralo a mano)",
            ),
            item.modifiers,
        )
        // Nada se inventa: sólo el código del producto.
        assertEquals(listOf("P000500"), item.externalCodes)
    }

    // Codex final #1 (D5 c/d): un vale EXTERNO emitido antes de la guarda del servidor puede traer un renglón por peso o
    // con descuento. Un código fijo cobraría 1 pieza a precio de lista bajo un «Importe de referencia» que dice otra cosa.

    @Test
    fun `P3 vale externo viejo por peso - sin codigos y el nombre pide cobrarlo a mano`() {
        val item = ticket(route = "EXTERNAL", total = "25.00", lines = listOf(porPeso())).toAreaTicketData(null).items.single()

        assertEquals(emptyList<String>(), item.externalCodes)
        assertEquals("Jamón (por peso: cóbralo a mano)", item.name)
    }

    @Test
    fun `P3 vale externo viejo con descuento - sin codigos y el nombre pide cobrarlo a mano`() {
        val item = ticket(route = "EXTERNAL", total = "50.00", lines = listOf(conDescuento())).toAreaTicketData(null).items.single()

        assertEquals(emptyList<String>(), item.externalCodes)
        assertEquals("Latte (con descuento: cóbralo a mano)", item.name)
    }

    @Test
    fun `los mismos renglones en un vale normal salen como hoy`() {
        val vale = ticket(route = "AVOQADO", total = "75.00", lines = listOf(porPeso(), conDescuento()))

        assertEquals(
            listOf(
                ReceiptItem(
                    name = "Jamón", quantity = 1, unitPrice = 10000, totalPrice = 2500,
                    weightSummary = "0.250 kg × \$100.00/kg",
                ),
                ReceiptItem(name = "Latte", quantity = 1, unitPrice = 5500, totalPrice = 5000),
            ),
            vale.toAreaTicketData(null).items,
        )
    }

    @Test
    fun `un renglon externo sin extras no lleva lista de extras`() {
        val vale = ticket(route = "EXTERNAL", total = "55.00", lines = listOf(line()))

        // null y no una lista vacía: lo mismo que el renglón del vale normal.
        assertNull(vale.toAreaTicketData(null).items[0].modifiers)
    }

    @Test
    fun `la reimpresion pasa tal cual y por omision no lo es`() {
        val vale = ticket(route = "EXTERNAL", total = "55.00", lines = listOf(line()))

        assertFalse(vale.toAreaTicketData(null).isReprint)
        assertTrue(vale.toAreaTicketData(null, isReprint = true).isReprint)
    }
}
