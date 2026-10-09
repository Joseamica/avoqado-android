package com.avoqado.pos.tables

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.database.CachedPayloadDao
import com.avoqado.pos.core.data.local.database.CachedPayloadEntity
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.tables.data.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TableProvisionalFloorTest {
    @get:Rule val main = MainDispatcherRule()
    @Test fun `provisional check is durable and survives a server floor read before open ack`() = runTest {
        val root = java.nio.file.Files.createTempDirectory("floor-draft").toFile()
        try {
            val drafts = TableRoundDraftStore(root)
            val rows = mutableMapOf<String, CachedPayloadEntity>()
            val dao = mockk<CachedPayloadDao>()
            coEvery { dao.get(any()) } answers { rows[firstArg()] }
            coEvery { dao.upsert(any()) } answers { val row = firstArg<CachedPayloadEntity>(); rows[row.cacheKey] = row }
            val api = mockk<ApiService>()
            val session = TableSession()
            val original = DiningTable(id = "t1", number = "5", shape = "ROUND", positionX = 0.25f, positionY = 0.75f, rotation = 45)
            coEvery { api.getTables("venue-floor") } returns TablesResponse(data = listOf(original))
            val repository = TableServiceRepository(api, PayloadCache(dao), mockk<SyncOutbox>(relaxed = true), session, mockk<RoleManager>(relaxed = true), drafts)
            repository.refresh("venue-floor").getOrThrow()
            session.start(TableSession.Active(tableId = "t1", tableNumber = "5", areaName = null, orderId = "local", orderNumber = "LOCAL", version = 1, totalCents = 0, mode = TableSession.Mode.ORDERING, isProvisional = true))
            coEvery { dao.upsert(any()) } throws java.io.IOException("FULLTEST disk full")
            assertTrue(runCatching { repository.markTableOccupiedLocally("t1") }.isFailure)
            assertFalse("A failed local write cannot publish an occupied table", repository.tables.value.single().isOccupied)
            coEvery { dao.upsert(any()) } answers { val row = firstArg<CachedPayloadEntity>(); rows[row.cacheKey] = row }
            repository.markTableOccupiedLocally("t1")
            val table = Json.parseToJsonElement(rows.getValue("tables:venue-floor").json).jsonObject["tables"]!!.jsonArray.first().jsonObject
            assertEquals("local", table["openOrders"]?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content)
            assertEquals("ROUND", table["shape"]!!.jsonPrimitive.content)
            assertEquals(45, table["rotation"]!!.jsonPrimitive.int)
            assertTrue(repository.tables.value.single().isOccupied)
            repository.refresh("venue-floor").getOrThrow()
            assertEquals("local", repository.tables.value.single().primaryCheck!!.id)
            assertTrue(repository.tables.value.single().isOccupied)
            coEvery { api.getTables(any()) } throws java.io.IOException("sin red")
            val cold = TableServiceRepository(api, PayloadCache(dao), mockk(relaxed = true), TableSession(), mockk(relaxed = true), drafts)
            cold.refresh("venue-floor")
            assertEquals("local", cold.tables.value.single().primaryCheck!!.id)
            assertTrue(cold.tables.value.single().isOccupied)
            assertTrue(cold.tables.value.single().primaryCheck!!.isProvisional)
            val floorVm = com.avoqado.pos.tables.presentation.TablesViewModel(cold, mockk(relaxed = true), session,
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
            var reopened = false
            floorVm.startOrdering(cold.tables.value.single()) { reopened = true }
            assertTrue(reopened)
            assertTrue(session.current()!!.isProvisional)
            drafts.promote("venue-floor", "local", "real")
            coEvery { api.getTables("venue-floor") } returns TablesResponse(data = listOf(original.copy(openOrders = listOf(OpenCheckSummary("real", "ORD-real")))))
            cold.refresh("venue-floor").getOrThrow()
            assertEquals(listOf("real"), cold.tables.value.single().openOrders.map { it.id })
            assertFalse(cold.tables.value.single().primaryCheck!!.isProvisional)
            coEvery { api.getTables("venue-other") } throws java.io.IOException("sin red")
            cold.refresh("venue-other")
            assertTrue("Previous venue data cannot leak when the next venue is offline", cold.tables.value.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
