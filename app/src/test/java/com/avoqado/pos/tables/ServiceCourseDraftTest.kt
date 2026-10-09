package com.avoqado.pos.tables

import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.ServiceCourseSnapshot
import com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine
import com.avoqado.pos.tables.data.TableRoundDraftStore
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ServiceCourseDraftTest {
    @Test fun `staging a preparation round persists its route in the same draft write`() {
        val root = Files.createTempDirectory("service-course-stage-routing").toFile()
        try {
            val store = TableRoundDraftStore(root)
            val original = com.avoqado.pos.printing.routing.PrintConfig(defaultStationId = "barra")
            store.save("v", "o", emptyList(), mapOf("round" to listOf(line())), preparationRouting = mapOf("round" to original))
            store.save("v", "o", emptyList(), mapOf("round" to listOf(line())), preparationRouting = mapOf("round" to original.copy(defaultStationId = "otra")))
            assertEquals(original, TableRoundDraftStore(root).load("v", "o").preparationRouting["round"])
            assertThrows(IllegalArgumentException::class.java) {
                store.save("v", "foreign", emptyList(), emptyMap(), preparationRouting = mapOf("round" to original))
            }
            assertTrue(store.load("v", "foreign").rounds.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun `preparation routing is frozen before enqueue and survives restart promotion and later edits`() {
        val root = Files.createTempDirectory("service-course-routing").toFile()
        try {
            val store = TableRoundDraftStore(root)
            val original = com.avoqado.pos.printing.routing.PrintConfig(defaultStationId = "barra", version = "original")
            store.save("v", "local", listOf(line()), mapOf("stable-round" to listOf(line())))
            store.freezePreparationRouting("v", "local", "stable-round", original)
            val cold = TableRoundDraftStore(root)
            assertEquals(original, cold.load("v", "local").preparationRouting["stable-round"])
            assertEquals(original, cold.freezePreparationRouting("v", "local", "stable-round", original.copy(defaultStationId = "otra")))
            cold.promote("v", "local", "real")
            cold.save("v", "local", emptyList(), mapOf("stable-round" to listOf(line())))
            val snapshot = cold.load("v", "real")
            assertEquals(original, snapshot.preparationRouting["stable-round"])
            assertEquals(4950, snapshot.rounds.getValue("stable-round").single().item.totalPrice)
            assertTrue(cold.load("other-venue", "local").preparationRouting.isEmpty())
            cold.save("v", "real", emptyList(), emptyMap())
            assertTrue(cold.load("v", "real").preparationRouting.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun `routing cannot create an unstaged round or another venue draft`() {
        val root = Files.createTempDirectory("service-course-routing").toFile()
        try {
            val store = TableRoundDraftStore(root)
            store.save("v", "o", emptyList(), mapOf("round" to listOf(line())))
            val config = com.avoqado.pos.printing.routing.PrintConfig()
            assertThrows(IllegalArgumentException::class.java) { store.freezePreparationRouting("v", "o", "other", config) }
            assertThrows(IllegalArgumentException::class.java) { store.freezePreparationRouting("foreign", "o", "round", config) }
        } finally { root.deleteRecursively() }
    }
    @Test fun `promotion merges stable identities and old writers follow alias without resurrecting retired rounds`() {
        val root = Files.createTempDirectory("service-course-promotion").toFile()
        try {
            val source = TableRoundDraftStore(root)
            val prepared = line().copy(item = line().item.copy(id = "prepared"))
            val before = source.save("v", "local", listOf(line()), mapOf("stable-round" to listOf(prepared)))
            val newer = line().copy(item = line().item.copy(unitPrice = 5500))
            val other = line().copy(item = line().item.copy(id = "other"))
            val bill = com.avoqado.pos.tables.data.OrderDetail(id = "real", total = 99.0,
                payments = listOf(com.avoqado.pos.tables.data.OrderDetailPayment(amount = 50.0, status = "COMPLETED")))
            TableRoundDraftStore(root).save("v", "real", listOf(newer, other), emptyMap(), bill)
            source.promote("v", "local", "real")
            val cold = TableRoundDraftStore(root)
            val merged = cold.load("v", "real")
            assertEquals(listOf(newer, other), merged.pending)
            assertEquals(before.createdAtLocal, merged.createdAtLocal)
            assertEquals(bill, merged.cachedCheck)
            assertEquals(merged, cold.load("v", "local"))
            source.save("v", "local", listOf(other), emptyMap())
            cold.promote("v", "local", "real")
            assertEquals(listOf(other), cold.load("v", "real").pending)
            assertTrue(cold.load("v", "local").rounds.isEmpty())
            assertEquals(bill, cold.load("v", "real").cachedCheck)
            assertTrue(cold.load("another-venue", "local").pending.isEmpty())
            assertThrows(IllegalStateException::class.java) { cold.promote("v", "local", "different-order") }
        } finally { root.deleteRecursively() }
    }
    @Test fun `saved check survives edits and cannot cross order or venue`() {
        val root = Files.createTempDirectory("service-course-balance").toFile()
        try {
            val store = TableRoundDraftStore(root)
            val detail = com.avoqado.pos.tables.data.OrderDetail(id = "o1", total = 109.0,
                payments = listOf(com.avoqado.pos.tables.data.OrderDetailPayment(amount = 50.0, tipAmount = 10.0, status = "COMPLETED")))
            store.save("v1", "o1", emptyList(), emptyMap(), detail)
            store.save("v1", "o1", listOf(line()), emptyMap())
            assertEquals(detail, TableRoundDraftStore(root).load("v1", "o1").cachedCheck)
            assertNull(store.load("v2", "o1").cachedCheck)
            assertNull(store.load("v1", "o2").cachedCheck)
            assertThrows(IllegalArgumentException::class.java) { store.save("v1", "o2", emptyList(), emptyMap(), detail) }
        } finally { root.deleteRecursively() }
    }
    private fun line() = PendingLine(CartItem(id = "line", type = CartItemType.ProductItem("coffee"), name = "Café", unitPrice = 4950,
        promotionInstanceId = "instance", promotionId = "combo", promotionGroupId = "group", promotionOptionId = "option",
        serviceCourse = ServiceCourseSnapshot("desserts", "Con el postre", "STANDARD")), "Con el postre")

    @Test fun `cold restart preserves unsent package price and historical choice only in its venue`() {
        val root = Files.createTempDirectory("service-course-draft").toFile()
        try {
            TableRoundDraftStore(root).save("v1", "o1", listOf(line()), emptyMap())
            val restored = TableRoundDraftStore(root).load("v1", "o1")
            assertEquals("Con el postre", restored.pending.single().item.serviceCourse!!.label)
            assertEquals(4950, restored.pending.single().item.totalPrice)
            assertEquals("instance", restored.pending.single().item.promotionInstanceId)
            assertTrue(TableRoundDraftStore(root).load("v2", "o1").pending.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun `prepared round keeps the same key and cannot also restore as unsent`() {
        val root = Files.createTempDirectory("service-course-draft").toFile()
        try {
            val store = TableRoundDraftStore(root)
            store.save("v", "o", listOf(line()), mapOf("stable-round" to listOf(line())))
            val restored = store.load("v", "o")
            assertTrue(restored.pending.isEmpty())
            assertEquals(setOf("stable-round"), restored.rounds.keys)
            store.save("v", "o", emptyList(), mapOf("stable-round" to listOf(line())))
            assertEquals(restored.createdAtLocal, store.load("v", "o").createdAtLocal)
        } finally { root.deleteRecursively() }
    }
    @Test fun `corrupt saved draft is reported and never silently replaced`() {
        val root = Files.createTempDirectory("service-course-draft").toFile()
        try {
            val store = TableRoundDraftStore(root)
            store.save("v", "o", listOf(line()), emptyMap())
            root.listFiles()!!.single().writeText("broken")
            assertThrows(Exception::class.java) { store.load("v", "o") }
            assertThrows(Exception::class.java) { store.save("v", "o", emptyList(), emptyMap()) }
            assertEquals("broken", root.listFiles()!!.single().readText())
        } finally { root.deleteRecursively() }
    }

}
