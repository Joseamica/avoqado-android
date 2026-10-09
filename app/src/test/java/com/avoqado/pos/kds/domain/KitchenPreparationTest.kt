package com.avoqado.pos.kds.domain

import org.junit.Assert.*
import org.junit.Test

class KitchenPreparationTest {
    private val wire = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun priorityAction(value: String) = wire.decodeFromString(PreparationAction.serializer(), "\"$value\"")
    private fun urgentCounts(acknowledged: Boolean = false) = wire.decodeFromString(PreparationCounts.serializer(),
        """{"HELD":0,"PENDING":0,"PREPARING":2,"READY":0,"DELIVERED":0,"CANCELLED":0,"urgency":{"requestId":"request-a","acknowledged":$acknowledged}}""")
    @Test fun `urgent priority is preserved by preparation and acknowledgment then cleared without hiding work`() {
        val before = urgentCounts()
        val ack = before.move(priorityAction("ACK_URGENT"), 1)
        assertTrue(wire.encodeToString(PreparationCounts.serializer(), ack).contains("\"acknowledged\":true"))
        assertEquals(2, ack.PREPARING)
        val clear = ack.move(priorityAction("CLEAR_URGENT"), 1)
        assertEquals(2, clear.PREPARING); assertEquals(0, clear.HELD)
        assertFalse(wire.encodeToString(PreparationCounts.serializer(), clear).contains("request-a"))
        val lastReady = before.move(PreparationAction.READY, 2)
        assertFalse(wire.encodeToString(PreparationCounts.serializer(), lastReady).contains("request-a"))
    }
    @Test fun `urgent projection releases only its exact held line and stores the original intent identity`() {
        val line = PreparationLine("a", productName = "Café", quantity = 2, preparation = PreparationCounts.initial(2, true))
        val result = PreparationLocalLine(line, listOf(PreparationEffect("request-a", 0, priorityAction("URGENT"), 1))).projected()
        assertEquals(0, result.preparation.HELD); assertEquals(2, result.preparation.PENDING)
        assertEquals(1, result.preparationRevision)
        assertTrue(wire.encodeToString(PreparationCounts.serializer(), result.preparation).contains("request-a"))
        assertEquals(2, line.preparation.HELD)
    }
    @Test fun `urgent actions mirror floor and kitchen permissions and reject ready products`() {
        assertEquals(listOf("orders:update", "orders:create"), priorityAction("URGENT").permissions)
        assertEquals(listOf("orders:update", "orders:create"), priorityAction("CLEAR_URGENT").permissions)
        assertEquals(listOf("orders:update"), priorityAction("ACK_URGENT").permissions)
        assertThrows(IllegalArgumentException::class.java) { PreparationCounts(READY = 1).move(priorityAction("URGENT"), 1) }
        assertEquals(0, PreparationCounts(READY = 1).available(priorityAction("URGENT")))
    }
    @Test fun `renamed immediate and reordered courses follow their frozen catalog positions`() {
        fun row(label: String, kind: String, position: Int) = KDSOrderItem(id = label, productName = label, quantity = 1,
            serviceCourse = com.avoqado.pos.pos.data.model.ServiceCourseSnapshot(label, label, kind, 1, position))
        val dessert = row("Con el postre", "STANDARD", 3)
        val immediate = row("Al momento", "IMMEDIATE", 0)
        val starter = row("Para empezar", "STANDARD", 1)
        assertEquals(listOf("Al momento", "Para empezar", "Con el postre"), gruposPorTiempo(listOf(dessert, immediate, starter)).map { it.tiempo })
    }
    @Test fun `correction handles different preparation stages and preserves delivered units`() {
        val mixed = PreparationCounts(HELD = 1, PENDING = 1, PREPARING = 1, READY = 1, DELIVERED = 1)
        val canceled = mixed.move(PreparationAction.CANCEL, 2, reason = "Faltan insumos")
        assertEquals(0, canceled.HELD); assertEquals(0, canceled.PENDING)
        assertEquals(1, canceled.PREPARING); assertEquals(1, canceled.DELIVERED)
        assertEquals(2, canceled.CANCELLED)
        assertEquals(2, canceled.move(PreparationAction.REOPEN, 2, reason = "Reposición").PENDING)
    }
    @Test fun `held quantities cannot start before release`() {
        val held = PreparationCounts.initial(3, standard = true)
        assertEquals(3, held.HELD)
        assertThrows(IllegalArgumentException::class.java) { held.move(PreparationAction.START, 1) }
        val one = held.move(PreparationAction.RELEASE, 1)
        assertEquals(2, one.HELD)
        assertEquals(1, one.PENDING)
    }
    @Test fun `partial ready and delivery leave other units active`() {
        val counts = PreparationCounts.initial(3, false).move(PreparationAction.START, 2)
            .move(PreparationAction.READY, 1).move(PreparationAction.DELIVER, 1)
        assertEquals(1, counts.PENDING)
        assertEquals(1, counts.PREPARING)
        assertEquals(1, counts.DELIVERED)
        assertFalse(counts.terminal)
        assertThrows(IllegalArgumentException::class.java) { counts.move(PreparationAction.DELIVER, 1) }
    }
    @Test fun `cancel and reopen require an explicit state and reason`() {
        val pending = PreparationCounts.initial(1, false)
        assertThrows(IllegalArgumentException::class.java) { pending.move(PreparationAction.CANCEL, 1, PreparationState.PENDING) }
        val canceled = pending.move(PreparationAction.CANCEL, 1, PreparationState.PENDING, "Sin insumos")
        assertTrue(canceled.terminal)
        assertEquals(1, canceled.move(PreparationAction.REOPEN, 1, PreparationState.CANCELLED, "Reposición").PENDING)
    }
    @Test fun `permissions mirror the server for every action`() {
        assertEquals(listOf("orders:update", "orders:create"), PreparationAction.RELEASE.permissions)
        assertEquals(listOf("orders:update", "orders:create"), PreparationAction.DELIVER.permissions)
        assertEquals(listOf("orders:update"), PreparationAction.START.permissions)
        assertEquals(listOf("orders:update", "orders:cancel"), PreparationAction.REOPEN.permissions)
    }
    @Test fun `malformed progress is never guessed into ready`() {
        assertFalse(PreparationCounts(READY = 2).validFor(1))
        assertFalse(PreparationCounts(PENDING = -1, READY = 2).validFor(1))
        assertTrue(PreparationCounts.initial(1, false).validFor(1))
    }
}
