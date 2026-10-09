package com.avoqado.pos.kds.domain

import com.avoqado.pos.pos.data.model.ServiceCourseSnapshot
import org.junit.Assert.*
import org.junit.Test

class PreparationPeerProgressTest {
    @Test fun `generic peer receipt never suppresses paper without an explicit display receipt`() {
        val generic = """{"v":1,"status":"ok","deliveryId":"action:0","phase":"ACTION"}"""
        assertTrue(PreparationPeerProtocol.acknowledged(generic, command))
        assertFalse(PreparationPeerProtocol.displayed(generic, command))
        val display = """{"v":1,"status":"ok","deliveryId":"action:0","phase":"ACTION","displayStations":["s"]}"""
        assertTrue(PreparationPeerProtocol.displayed(display, command))
        assertFalse(PreparationPeerProtocol.displayed(display.replace("\"s\"", "\"other\""), command))
    }
    @Test fun `peer chunks never mix preparation stations`() {
        val other = row.copy(stationId = "other", externalId = "sync:r:1", sourceKey = "round:r:other")
        val job = preparationDeliveryJob("v1", "staff1", "d1", "action", listOf(row, other), PreparationAction.RELEASE,
            1, null, null, com.avoqado.pos.printing.routing.PrintConfig())
        assertEquals(2, job.commands.size)
        assertTrue(job.commands.all { it.items.map { item -> item.stationId }.distinct().size == 1 })
    }
    private val row = PreparationLine(id = "local:x", externalId = "sync:r:0", sourceKey = "round:r:s", stationId = "s",
        productName = "Café", quantity = 3, serviceCourse = ServiceCourseSnapshot("dessert", "Con el postre", "STANDARD", preparationVersion = 1),
        preparation = PreparationCounts(HELD = 3))
    private val item get() = PreparationPeerItem(row.sourceKey!!, row.externalId!!, row.stationId, row.quantity, row.preparationRevision, row.preparation)
    private val command get() = PreparationPeerProgress(venueId = "v1", deviceId = "d1", staffId = "staff1", deliveryId = "action:0",
        intentId = "action", action = PreparationAction.RELEASE, quantity = 1, items = listOf(item))
    @Test fun `partial peer release keeps course identity and is idempotent`() {
        val once = applyPreparationPeerProgress(PreparationLocalLine(row), item, command)
        val twice = applyPreparationPeerProgress(once, item, command)
        assertEquals(once, twice)
        val projected = twice.projected()
        assertEquals(2, projected.preparation.HELD); assertEquals(1, projected.preparation.PENDING)
        assertEquals(row.serviceCourse, projected.serviceCourse)
        assertEquals(1, projected.preparationRevision); assertEquals(1, twice.effects.size)
    }
    @Test fun `different action same revision gaps foreign identity and malformed counts are rejected`() {
        val changed = applyPreparationPeerProgress(PreparationLocalLine(row), item, command)
        assertNotNull(runCatching { applyPreparationPeerProgress(changed, item, command.copy(intentId = "other")) }.exceptionOrNull())
        assertNotNull(runCatching { applyPreparationPeerProgress(PreparationLocalLine(row), item.copy(expectedRevision = 2), command.copy(items = listOf(item.copy(expectedRevision = 2)))) }.exceptionOrNull())
        assertNotNull(runCatching { applyPreparationPeerProgress(PreparationLocalLine(row), item.copy(externalId = "other"), command.copy(items = listOf(item.copy(externalId = "other")))) }.exceptionOrNull())
        assertNotNull(runCatching { command.copy(items = listOf(item.copy(before = PreparationCounts(HELD = 2)))).validate() }.exceptionOrNull())
        assertNotNull(runCatching { command.copy(items = List(21) { item }).validate() }.exceptionOrNull())
        assertNotNull(runCatching { command.copy(staffId = "").validate() }.exceptionOrNull())
    }
    @Test fun `business rejection removes only the matching projection and ack waits for canonical baseline`() {
        val applied = applyPreparationPeerProgress(PreparationLocalLine(row), item, command)
        assertEquals(applied, applyPreparationPeerProgress(applied, item, command.copy(phase = "ACKED")))
        val rejected = applyPreparationPeerProgress(applied, item, command.copy(phase = "REJECTED"))
        assertEquals(row, rejected.projected())
        assertTrue(rejected.effects.isEmpty())
    }
    @Test fun `strict ack distinguishes phase delivery and legacy messages`() {
        fun ack(id: String = "action:0", phase: String = "ACTION", status: String = "ok") = """{"v":1,"status":"$status","deliveryId":"$id","phase":"$phase"}"""
        assertTrue(PreparationPeerProtocol.acknowledged(ack(), command))
        assertFalse(PreparationPeerProtocol.acknowledged(ack(phase = "ACKED"), command))
        assertFalse(PreparationPeerProtocol.acknowledged(ack(id = "other"), command))
        assertFalse(PreparationPeerProtocol.acknowledged(ack(status = "error"), command))
        assertFalse(PreparationPeerProtocol.acknowledged("""{"v":1,"status":"ok","sourceKey":"round:r:s"}""", command))
    }
    @Test fun `swift omitted nulls and unicode stable identity decode exactly`() {
        val raw = """{"v":1,"op":"preparation_progress","venueId":"v1","deviceId":"d1","staffId":"staff1","deliveryId":"action:0","intentId":"action","phase":"ACTION","action":"RELEASE","quantity":1,"items":[{"sourceKey":"round:☕:🍮","externalId":"sync:r:0","quantity":3,"expectedRevision":0,"before":{"HELD":3,"PENDING":0,"PREPARING":0,"READY":0,"DELIVERED":0,"CANCELLED":0}}]}"""
        val decoded = PreparationPeerProtocol.json.decodeFromString<PreparationPeerProgress>(raw)
        decoded.validate(); assertEquals("10:round:☕:🍮:8:sync:r:0:", decoded.items.single().stableKey)
    }
    @Test fun `release paper prints selected units and preserves separate combo components`() {
        val other = row.copy(id = "local:y", externalId = "sync:r:0:g:dessert", orderPromotionId = "combo", quantity = 2,
            preparation = PreparationCounts(HELD = 2))
        val paper = preparationReleasePaper(listOf(row, other), 1, com.avoqado.pos.printing.routing.PrintConfig(), "v1")!!
        assertEquals(2, paper.planes.single().lines.size)
        assertEquals(listOf(1, 1), paper.planes.single().lines.map { it.quantity })
        assertEquals(listOf(row.externalId, other.externalId), paper.planes.single().lines.map { it.externalId })
        assertEquals("s", paper.planes.single().stationId)
        assertTrue(paper.planes.single().lines.all { it.serviceCourse == row.serviceCourse })
        assertTrue(paper.orderType.startsWith("LIBERAR"))
        assertNotNull(runCatching { preparationReleasePaper(listOf(other), 3, com.avoqado.pos.printing.routing.PrintConfig(), "v1") }.exceptionOrNull())
    }
    @Test fun `one action makes bounded peer messages and retains immutable staff and original quantity`() {
        val rows = (0..44).map { row.copy(externalId = "sync:r:$it", id = "local:$it") }
        val job = preparationDeliveryJob("v1", "staff1", "d1", "action", rows, PreparationAction.RELEASE, 1, null, null,
            com.avoqado.pos.printing.routing.PrintConfig())
        assertEquals(listOf(20, 20, 5), job.commands.map { it.items.size })
        assertEquals(45, job.commands.flatMap { it.items }.map { it.stableKey }.distinct().size)
        assertTrue(job.commands.all { it.staffId == "staff1" && it.intentId == "action" && it.items.all { item -> item.quantity == 3 } })
        assertEquals("QUEUED", job.paperState)
        assertEquals(45, job.paper!!.planes.single().lines.size)
    }

    @Test fun `delivery persistence cannot replace original actor or intent`() {
        fun photo(key: String, value: String) = com.avoqado.pos.core.data.local.database.CachedPayloadEntity(key, "v1", value, 100)
        val row = photo("preparation:v1:line", "{}")
        for ((actor, intent) in listOf("other" to "action", "staff1" to "another")) {
            val job = PreparationDeliveryJob(intentId = intent, venueId = "v1", staffId = actor, deviceId = "d1", commands = emptyList())
            val delivery = photo("preparation:v1:delivery:action", PreparationPeerProtocol.json.encodeToString(PreparationDeliveryJob.serializer(), job))
            assertNotNull(runCatching { validatePreparationSnapshots("action", "v1", "staff1", listOf(row, delivery)) }.exceptionOrNull())
        }
    }
    @Test fun `delivery record is additive to the hundred product action limit`() {
        val rows = (0..<100).map { com.avoqado.pos.core.data.local.database.CachedPayloadEntity("preparation:v1:line:$it", "v1", "{}", 100) }
        val job = PreparationDeliveryJob(intentId = "action", venueId = "v1", staffId = "staff1", deviceId = "d1", commands = emptyList())
        val delivery = com.avoqado.pos.core.data.local.database.CachedPayloadEntity("preparation:v1:delivery:action", "v1",
            PreparationPeerProtocol.json.encodeToString(PreparationDeliveryJob.serializer(), job), 100)
        validatePreparationSnapshots("action", "v1", "staff1", rows + delivery)
    }
    @Test fun `transaction predecessor prevents stale selection from overwriting another peer`() {
        val job = preparationDeliveryJob("v1", "staff1", "d1", "action", listOf(row), PreparationAction.RELEASE,
            1, null, null, com.avoqado.pos.printing.routing.PrintConfig())
        fun photo(local: PreparationLocalLine) = com.avoqado.pos.core.data.local.database.CachedPayloadEntity(
            "preparation:v1:${row.stableKey}", "v1", PreparationPeerProtocol.json.encodeToString(PreparationLocalLine.serializer(), local), 100)
        val next = photo(PreparationLocalLine(row, listOf(PreparationEffect("action", 0, PreparationAction.RELEASE, 1))))
        validatePreparationPredecessors(job, listOf(next), listOf(photo(PreparationLocalLine(row))))
        val other = photo(PreparationLocalLine(row, listOf(PreparationEffect("another", 0, PreparationAction.RELEASE, 1))))
        assertNotNull(runCatching { validatePreparationPredecessors(job, listOf(next), listOf(other)) }.exceptionOrNull())
        assertNotNull(runCatching { validatePreparationPredecessors(job, listOf(next), emptyList()) }.exceptionOrNull())
    }
    @Test fun `rejected predecessor keeps later kitchen work visible as a review requirement`() {
        val released = applyPreparationPeerProgress(PreparationLocalLine(row), item, command)
        val started = released.copy(effects = released.effects + PreparationEffect("cooking", 1, PreparationAction.START, 1))
        val rejected = applyPreparationPeerProgress(started, item, command.copy(phase = "REJECTED"))
        assertEquals(listOf("cooking"), rejected.effects.map { it.intentId })
        assertNotNull(runCatching { rejected.projected() }.exceptionOrNull())
    }

    @Test fun `urgency is exact per component with variable held units and a versioned receipt`() {
        val sibling = row.copy(id = "dessert", externalId = "sync:r:0:g:dessert", orderPromotionId = "combo",
            quantity = 2, preparation = PreparationCounts(HELD = 2))
        assertEquals(listOf(sibling), selectUrgentProducts(listOf(row, sibling), setOf(sibling.urgencyProductKey)))
        val urgent = preparationDeliveryJob("v1", "staff1", "d1", "urgent", listOf(row, sibling), PreparationAction.URGENT,
            1, null, null, com.avoqado.pos.printing.routing.PrintConfig())
        assertEquals(listOf(3, 2), urgent.paper!!.planes.single().lines.map { it.quantity })
        assertTrue(urgent.paper!!.orderType.startsWith("URGENTE"))
        val wire = urgent.commands.single()
        assertEquals(2, wire.version)
        assertFalse(PreparationPeerProtocol.acknowledged("""{"v":1,"status":"ok","deliveryId":"urgent:0","phase":"ACTION"}""", wire))
        assertTrue(PreparationPeerProtocol.acknowledged("""{"v":2,"status":"ok","deliveryId":"urgent:0","phase":"ACTION"}""", wire))
    }
    @Test fun `urgent selection requires all stations and excludes ready station without touching siblings`() {
        val first = row.copy(stationCount = 2)
        val second = first.copy(stationId = "bar", sourceKey = "round:r:bar", preparation = PreparationCounts(READY = 3))
        assertNotNull(runCatching { selectUrgentProducts(listOf(first), setOf(first.urgencyProductKey)) }.exceptionOrNull())
        assertEquals(listOf(first), selectUrgentProducts(listOf(first, second), setOf(first.urgencyProductKey)))
    }
    @Test fun `legacy state transition preserves a known urgency at the same revision`() {
        val counts = PreparationCounts(PENDING = 3, urgency = PreparationUrgency("urgent"))
        val base = row.copy(preparation = counts, preparationRevision = 1)
        val legacy = item.copy(before = PreparationCounts(PENDING = 3), expectedRevision = 1)
        val move = command.copy(intentId = "start", action = PreparationAction.START, items = listOf(legacy))
        val updated = applyPreparationPeerProgress(PreparationLocalLine(base), legacy, move).projected()
        assertEquals(1, updated.preparation.PREPARING)
        assertEquals(counts.urgency, updated.preparation.urgency)
        assertNotNull(runCatching { applyPreparationPeerProgress(PreparationLocalLine(base), legacy,
            move.copy(version = 2)) }.exceptionOrNull())
    }

}
