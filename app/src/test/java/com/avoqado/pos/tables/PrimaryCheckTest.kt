package com.avoqado.pos.tables

import com.avoqado.pos.tables.data.DiningTable
import com.avoqado.pos.tables.data.OpenCheckSummary
import com.avoqado.pos.tables.data.TableOrder
import com.avoqado.pos.tables.data.TableWaiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fija el bug encontrado en la D3 (2026-07-28): la mesa M9 se pintaba OCUPADA
 * con "2 cuentas" y tocarla no hacía absolutamente nada.
 *
 * Causa: en la base, `Table.currentOrderId` estaba en NULL y `status` en
 * AVAILABLE, aunque la mesa tenía DOS órdenes PENDING ($200 + $164). El plano
 * ya se había endurecido para PINTAR ocupado desde `openOrders` (hasOpenCheck),
 * pero cada acción seguía muriendo en un `currentOrder ?: return` mudo.
 *
 * Regla que fijan estos tests: **si la mesa se ve ocupada, tiene que poder
 * abrirse.** `hasOpenCheck` y `primaryCheck` no pueden discrepar nunca.
 */
class PrimaryCheckTest {

    private fun order(id: String, number: String, total: Double, version: Int = 1) =
        TableOrder(
            id = id,
            orderNumber = number,
            total = total,
            itemCount = 3,
            version = version,
            waiter = TableWaiter(id = "staff-1", name = "Juan Pérez"),
            createdAt = "2026-07-28T01:44:11Z",
        )

    private fun check(id: String, number: String, total: Double, version: Int = 1) =
        OpenCheckSummary(id = id, orderNumber = number, total = total, itemCount = 3, version = version)

    // MARK: - El bug exacto de M9

    @Test
    fun `M9 con puntero nulo y dos cuentas abiertas SI se puede abrir`() {
        val m9 = DiningTable(
            id = "cmpe6518z00ib9k92956gp2wb",
            number = "M9",
            status = "AVAILABLE", // ← el drift real de la base
            currentOrder = null, // ← currentOrderId = NULL
            openOrders = listOf(
                check("cmpe653fz01c49k928wnq6ffh", "ORD-ITH8Z3C6", 200.00),
                check("cmpe657wn04j49k92azkr0wpe", "ORD-EUBZ7JHH", 164.00),
            ),
        )

        // Se ve ocupada...
        assertTrue("M9 debe pintarse ocupada", m9.isOccupied)
        // ...y por lo tanto DEBE poder abrirse. Antes esto era null → tap muerto.
        assertNotNull("tocar M9 no puede ser un no-op", m9.primaryCheck)
        // Cae a la MÁS ANTIGUA (el server las manda ordenadas por createdAt asc).
        assertEquals("cmpe653fz01c49k928wnq6ffh", m9.primaryCheck?.id)
        assertEquals(200.00, m9.primaryCheck!!.total, 0.001)
    }

    @Test
    fun `una mesa que se ve ocupada siempre tiene cuenta accionable`() {
        // La invariante que se rompió. Vale para cualquier combinación.
        val casos = listOf(
            DiningTable(id = "1", number = "M1", status = "OCCUPIED", currentOrder = order("o1", "ORD-1", 50.0)),
            DiningTable(id = "2", number = "M2", status = "AVAILABLE", openOrders = listOf(check("c1", "ORD-2", 603.50))),
            DiningTable(id = "3", number = "M3", status = "AVAILABLE", currentOrder = order("o3", "ORD-3", 10.0)),
        )
        casos.forEach { t ->
            assertTrue("${t.number} debe verse ocupada", t.isOccupied)
            assertNotNull("${t.number} se ve ocupada pero no se puede abrir", t.primaryCheck)
        }
    }

    // MARK: - No romper el camino normal

    @Test
    fun `con puntero sano se usa la orden apuntada, no la primera de la lista`() {
        val table = DiningTable(
            id = "t",
            number = "M4",
            status = "OCCUPIED",
            currentOrder = order("segunda", "ORD-B", 164.00, version = 7),
            openOrders = listOf(
                check("primera", "ORD-A", 200.00),
                check("segunda", "ORD-B", 164.00, version = 7),
            ),
        )
        assertEquals("debe respetar el puntero del server", "segunda", table.primaryCheck?.id)
        // La versión sale del resumen: es la que viaja en el CAS de ADD_ITEMS.
        assertEquals(7, table.primaryCheck?.version)
    }

    @Test
    fun `puntero a una orden que no viene en la lista se sigue pudiendo abrir`() {
        // Servers viejos no mandan openOrders (campo aditivo).
        val table = DiningTable(
            id = "t",
            number = "M5",
            status = "OCCUPIED",
            currentOrder = order("solo", "ORD-SOLO", 89.50, version = 3),
            openOrders = emptyList(),
        )
        assertEquals("solo", table.primaryCheck?.id)
        assertEquals("ORD-SOLO", table.primaryCheck?.orderNumber)
        assertEquals(3, table.primaryCheck?.version)
        assertEquals(89.50, table.primaryCheck!!.total, 0.001)
        assertEquals("staff-1", table.primaryCheck?.waiterId)
    }

    // MARK: - Mesa realmente libre

    @Test
    fun `puntero a una cuenta YA PAGADA cede ante la que sigue abierta`() {
        // 🔴 Caso real (D3, mesa M2): se dividió la cuenta y se cobró una de las
        // dos. `currentOrderId` se quedó apuntando a la ya PAGADA —el server la
        // manda igual, no filtra por estado— así que el mesero veía
        // "Pagar $310.50" de algo cobrado y NO podía llegar a los $144 vivos.
        val m2 = DiningTable(
            id = "t2",
            number = "M2",
            status = "AVAILABLE",
            // el puntero: la cuenta pagada, que YA NO está en openOrders
            currentOrder = order("pagada", "ORD-PAGADA", 310.50),
            openOrders = listOf(check("viva", "ORD-VIVA", 144.0)),
        )
        assertEquals("viva", m2.primaryCheck?.id)
        assertEquals(144.0, m2.primaryCheck?.total ?: 0.0, 0.001)
    }

    @Test
    fun `mesa libre no inventa cuenta`() {
        val libre = DiningTable(id = "t", number = "M6", status = "AVAILABLE")
        assertTrue(libre.isAvailable)
        assertNull("una mesa libre no debe tener cuenta accionable", libre.primaryCheck)
    }
}
