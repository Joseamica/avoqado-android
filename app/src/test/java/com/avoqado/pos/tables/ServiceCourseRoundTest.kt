package com.avoqado.pos.tables

import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.ServiceCourseSnapshot
import com.avoqado.pos.tables.data.buildTableRoundRequests
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class ServiceCourseRoundTest {
    @Test fun `invalid local draft remains recoverable instead of losing duplicate identities`() {
        val row = com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine(line("g1", dessert.copy(preparationVersion = 1)), null)
        for (rows in listOf(listOf(row, row), listOf(row.copy(item = row.item.copy(quantity = 0))))) {
            assertNotNull(runCatching { com.avoqado.pos.tables.data.seedTableRoundPreparation(rows, "r", "o", "8", com.avoqado.pos.printing.routing.PrintConfig()) }.exceptionOrNull())
        }
    }
    @Test fun `frozen round config keeps relevant routes and printers without copying the whole venue catalog`() {
        val rows = listOf(com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine(line("g1", dessert.copy(preparationVersion = 1)), null))
        val original = com.avoqado.pos.printing.routing.PrintConfig(
            printers = listOf(com.avoqado.pos.printing.routing.PrinterInfo("printer", "Barra", "wifi"), com.avoqado.pos.printing.routing.PrinterInfo("unused", "Otra", "wifi")),
            stations = listOf(com.avoqado.pos.printing.routing.StationInfo("barra", "Barra", printerId = "printer"), com.avoqado.pos.printing.routing.StationInfo("other", "Otra", printerId = "unused")),
            productOverrides = listOf(com.avoqado.pos.printing.routing.ProductOverride("same-product", "barra")) + (0..999).map { com.avoqado.pos.printing.routing.ProductOverride("unused-$it", "other") },
            version = "frozen")
        val small = com.avoqado.pos.tables.data.compactRoundPreparationConfig(rows, original)
        assertEquals(listOf("barra"), small.stations.map { it.id }); assertEquals(listOf("printer"), small.printers.map { it.id })
        assertEquals(1, small.productOverrides.size); assertEquals("frozen", small.version)
        assertEquals(com.avoqado.pos.tables.data.seedTableRoundPreparation(rows, "r", "o", "8", original),
            com.avoqado.pos.tables.data.seedTableRoundPreparation(rows, "r", "o", "8", small))
    }
    @Test fun `local preparation uses frozen route stable component identities and initial quantities`() {
        val first = line("g1", immediate.copy(preparationVersion = 1))
        val second = line("g2", dessert.copy(preparationVersion = 1)).copy(quantity = 2)
        val source = listOf(first, second).map { com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine(it, null) }
        val config = com.avoqado.pos.printing.routing.PrintConfig(
            stations = listOf(com.avoqado.pos.printing.routing.StationInfo("barra", "Barra")), defaultStationId = "barra")
        val rows = com.avoqado.pos.tables.data.seedTableRoundPreparation(source, "round", "local-order", "Mesa 8", config, true)
        assertEquals(2, rows.size)
        assertEquals(listOf("sync:round:0:g:g1", "sync:round:0:g:g2"), rows.map { it.externalId })
        assertEquals(listOf("round:round:barra", "round:round:barra"), rows.map { it.sourceKey })
        assertEquals(1, rows[0].preparation.PENDING)
        assertEquals(2, rows[1].preparation.HELD)
        assertEquals(listOf(0, 0), rows.map { it.preparationRevision })
        assertTrue(rows.all { it.localOrderId == "local-order" && it.orderPromotionId == "instance" })
        assertEquals(listOf(4950, 9900), source.map { it.item.totalPrice })
        val newRound = com.avoqado.pos.tables.data.seedTableRoundPreparation(source, "round-2", "order", "Mesa 8", config)
        assertTrue(rows.map { it.stableKey }.toSet().intersect(newRound.map { it.stableKey }.toSet()).isEmpty())
    }
    @Test fun `local preparation keeps legacy rounds untouched and unrouted tracked items visible`() {
        val legacy = listOf(com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine(line("g1", dessert), null))
        assertTrue(com.avoqado.pos.tables.data.seedTableRoundPreparation(legacy, "r", "o", "8", com.avoqado.pos.printing.routing.PrintConfig()).isEmpty())
        val tracked = legacy.map { it.copy(item = it.item.copy(serviceCourse = dessert.copy(preparationVersion = 1))) }
        val row = com.avoqado.pos.tables.data.seedTableRoundPreparation(tracked, "r", "o", "8", com.avoqado.pos.printing.routing.PrintConfig()).single()
        assertNull(row.stationId)
        assertEquals("round:r:none", row.sourceKey)
        assertEquals(1, row.preparation.HELD)
    }
    private val immediate = ServiceCourseSnapshot("immediate", "Al momento", "IMMEDIATE")
    private val dessert = ServiceCourseSnapshot("desserts", "Dulces", "STANDARD")
    private fun line(group: String, course: ServiceCourseSnapshot) = CartItem(
        type = CartItemType.ProductItem("same-product"), name = "Café", unitPrice = 4950,
        promotionId = "combo", promotionInstanceId = "instance", promotionGroupId = group,
        promotionOptionId = "option-$group", serviceCourse = course,
    )

    @Test fun `same product in two groups keeps both snapshots in one combo reference`() {
        val requests = buildTableRoundRequests(listOf(line("g1", immediate), line("g2", dessert)))
        assertEquals(1, requests.size)
        assertNull(requests.single().productId)
        assertEquals(1, requests.single().quantity)
        val selections = requests.single().promotionRef!!.selections
        assertEquals(listOf("g1", "g2"), selections.map { it.groupId })
        assertEquals(listOf(immediate, dessert), selections.map { it.serviceCourse })
        val wire = Json.encodeToString(requests.single())
        val snapshot = Json.parseToJsonElement(wire).jsonObject["promotionRef"]!!.jsonObject["selections"]!!.jsonArray
        assertEquals("Al momento", snapshot[0].jsonObject["serviceCourse"]!!.jsonObject["label"]!!.jsonPrimitive.content)
        assertFalse(wire.contains("same-product"))
    }

    @Test fun `immediate rename preserves null legacy course and durable snapshot`() {
        val product = CartItem(type = CartItemType.ProductItem("p"), name = "Agua", unitPrice = 1000, serviceCourse = immediate)
        val request = buildTableRoundRequests(listOf(product)).single()
        assertNull(request.course)
        assertEquals(immediate, request.serviceCourse)
        assertEquals(request, Json.decodeFromString<com.avoqado.pos.tables.data.AddOrderItemRequest>(Json.encodeToString(request)))
    }

    @Test fun `standard snapshot is sent with legacy label and no catalog lookup`() {
        val product = CartItem(type = CartItemType.ProductItem("p"), name = "Pan", unitPrice = 1000, serviceCourse = dessert)
        val request = buildTableRoundRequests(listOf(product)).single()
        assertEquals("Dulces", request.course)
        assertEquals(dessert, request.serviceCourse)
    }

    @Test fun `incomplete combo identity fails visibly instead of charging components as regular products`() {
        val corrupt = line("g1", immediate).copy(promotionOptionId = null)
        assertThrows(IllegalArgumentException::class.java) { buildTableRoundRequests(listOf(corrupt)) }
    }
    @Test fun `repeat cannot turn a combo into regular products`() {
        val item = com.avoqado.pos.tables.data.OrderDetailItem(id = "sent", productId = "coffee", productName = "Café", unitPrice = 49.5, orderPromotionId = "order-combo")
        assertThrows(IllegalArgumentException::class.java) { com.avoqado.pos.tables.data.buildRepeatedTableLines(listOf(item)) }
    }
    @Test fun `ordinary repeat preserves historical immediate choice and price`() {
        val item = com.avoqado.pos.tables.data.OrderDetailItem(id = "sent", productId = "coffee", productName = "Café", unitPrice = 80.0, serviceCourse = immediate)
        val copy = com.avoqado.pos.tables.data.buildRepeatedTableLines(listOf(item)).single()
        assertEquals(immediate, copy.serviceCourse)
        assertEquals(8000, copy.totalPrice)
    }

    @Test fun `default immediate kitchen header remains compatible while renamed immediate is shown`() {
        assertNull(com.avoqado.pos.tables.data.kitchenCourseLabel(ServiceCourseSnapshot.DEFAULTS.first(), null))
        assertEquals("Al momento", com.avoqado.pos.tables.data.kitchenCourseLabel(immediate, null))
    }

    @Test fun `counter kitchen keeps two times and bundle name without changing legacy path`() {
        val items = listOf(line("g1", immediate), line("g2", dessert)).map { it.copy(promotionName = "Dos cafés") }
        val courses = com.avoqado.pos.payment.domain.buildCounterCoursePedidos(items)!!
        assertEquals(listOf("Al momento", "Dulces"), courses.map { it.curso })
        assertEquals(listOf("En tienda · Al momento", "En tienda · Dulces"), courses.map { it.orderType })
        assertTrue(courses.all { it.lines.single().comboName == "Dos cafés" && it.etiquetaPantalla == "En tienda" })
        assertNull(com.avoqado.pos.payment.domain.buildCounterCoursePedidos(items.map { it.copy(serviceCourse = null) }))
    }

}
