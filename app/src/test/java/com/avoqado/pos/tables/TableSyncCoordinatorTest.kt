package com.avoqado.pos.tables

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.sync.SyncAck
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.tables.data.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.assertEquals
import org.junit.Test

class TableSyncCoordinatorTest {
    @Test fun `kitchen acknowledgements never read tables or reset active order version`() = runTest {
        val storage = mockk<SecureStorage>(); every { storage.venueId } returns "v"
        val repository = mockk<TableServiceRepository>(relaxed = true)
        val session = TableSession().also { it.start(TableSession.Active("t", "8", null, "order", "24", 7, 8000, TableSession.Mode.ORDERING)) }
        val intents = mockk<SyncIntentDao>()
        val coordinator = TableSyncCoordinator(mockk<SyncOutbox>(), session, repository, storage, intents)
        for (type in listOf("KDS_ITEM_PROGRESS", "KDS_TICKET_MARK")) {
            coEvery { intents.preparationStatuses("v", listOf("a")) } returns listOf(SyncIntentEntity("a", "v", seq = 1, type = type, payloadJson = "{}"))
            coordinator.handle(SyncAck("a", "ACKED", result = buildJsonObject { put("orderId", "order"); put("items", JsonArray(emptyList())) }))
            assertEquals(7, session.current()!!.version)
        }
        coVerify(exactly = 0) { repository.refresh(any()) }
    }

    @Test fun `table acknowledgements reconcile the floor and advance only a supplied version`() = runTest {
        val storage = mockk<SecureStorage>(); every { storage.venueId } returns "v"
        val repository = mockk<TableServiceRepository>(relaxed = true)
        val session = TableSession().also { it.start(TableSession.Active("t", "8", null, "order", "24", 7, 8000, TableSession.Mode.ORDERING)) }
        val intents = mockk<SyncIntentDao>(relaxed = true)
        val coordinator = TableSyncCoordinator(mockk<SyncOutbox>(), session, repository, storage, intents)
        coordinator.handle(SyncAck("a", "ACKED", result = buildJsonObject { put("orderId", "order"); put("version", 8) }))
        assertEquals(8, session.current()!!.version)
        coordinator.handle(SyncAck("b", "ACKED", result = buildJsonObject { put("orderId", "order") }))
        assertEquals(8, session.current()!!.version)
        coVerify(exactly = 2) { repository.refresh("v") }
    }
}
