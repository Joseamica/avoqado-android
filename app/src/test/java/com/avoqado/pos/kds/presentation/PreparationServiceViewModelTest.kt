package com.avoqado.pos.kds.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.data.sync.SyncAck
import com.avoqado.pos.kds.data.KitchenPreparationRepository
import com.avoqado.pos.kds.domain.*
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PreparationServiceViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    @Test fun `offline confirmed cache preserves load more without claiming the server total`() = runTest {
        val repository = mockk<KitchenPreparationRepository>(relaxed = true)
        every { repository.notice } returns MutableStateFlow(null)
        every { repository.negotiated("v") } returns true
        val row = PreparationLine("line", orderId = "order", productName = "Café", quantity = 1, preparation = PreparationCounts(PENDING = 1))
        val page = PreparationPage(1, listOf(row), 132, true, "saved:preparation:v:line", cachedOnly = true)
        coEvery { repository.localPage(any(), any(), any(), any()) } returns null
        coEvery { repository.savedPage(any(), any(), any(), any()) } returns page
        coEvery { repository.page(any(), any(), any(), any()) } returns page
        val storage = mockk<SecureStorage>(); every { storage.venueId } returns "v"
        val outbox = mockk<SyncOutbox>(); every { outbox.acks } returns MutableSharedFlow<SyncAck>()
        val model = PreparationServiceViewModel(repository, storage, outbox)
        try {
            model.open("order"); runCurrent()
            assertEquals(listOf(row), model.state.value.items)
            assertFalse(model.state.value.serverTotalKnown); assertTrue(model.state.value.hasMore)
            model.refresh(more = true); runCurrent()
            coVerify { repository.page("v", "order", page.nextCursor, false) }
        } finally { model.close() }
    }
    @Test fun `saved products are visible while capability HTTP waits and remain after auth rejection`() = runTest {
        val repository = mockk<KitchenPreparationRepository>(relaxed = true)
        every { repository.notice } returns MutableStateFlow(null)
        coEvery { repository.restoreCapabilities("v") } just Runs
        coEvery { repository.pending(any(), any()) } returns false
        coEvery { repository.localPage(any(), any(), any(), any()) } returns null
        val storage = mockk<SecureStorage>(); every { storage.venueId } returns "v"
        val outbox = mockk<SyncOutbox>(); every { outbox.acks } returns MutableSharedFlow<SyncAck>()
        val gate = CompletableDeferred<Unit>()
        val row = PreparationLine("line", orderId = "order", productName = "Café", quantity = 1, preparation = PreparationCounts(HELD = 1))
        coEvery { repository.savedPage("v", "order", null, false) } returns PreparationPage(1, listOf(row), 1, false)
        coEvery { repository.refreshCapabilities("v") } coAnswers { gate.await() }
        coEvery { repository.page("v", "order", null, false) } throws IllegalStateException("Tu sesión necesita validarse")
        val model = PreparationServiceViewModel(repository, storage, outbox)
        try {
            model.open("order"); runCurrent()
            assertEquals(model.state.value.toString(), listOf(row), model.state.value.items)
            assertEquals(1, model.state.value.total)
            assertTrue(model.state.value.loading)
            gate.complete(Unit); runCurrent()
            assertEquals(model.state.value.toString(), listOf(row), model.state.value.items)
            assertFalse(model.state.value.loading)
            assertTrue(model.state.value.message!!.contains("sesión"))
        } finally { gate.complete(Unit); model.close() }
    }
    @Test fun `local pages remain visible after HTTP failure and load more does not reread an exhausted server page`() = runTest {
        val repository = mockk<KitchenPreparationRepository>(relaxed = true)
        every { repository.notice } returns MutableStateFlow(null)
        coEvery { repository.restoreCapabilities("v") } just Runs
        coEvery { repository.pending(any(), any()) } returns false
        coEvery { repository.savedPage(any(), any(), any(), any()) } returns null
        val storage = mockk<SecureStorage>(); every { storage.venueId } returns "v"
        val outbox = mockk<SyncOutbox>(); every { outbox.acks } returns MutableSharedFlow<SyncAck>()
        val first = PreparationLine("first", orderId = "order", productName = "Café", quantity = 1, preparation = PreparationCounts(HELD = 1))
        val second = first.copy(id = "second")
        coEvery { repository.localPage("v", "order", null, false) } returns PreparationPage(1, listOf(first), 2, true, "draft:1")
        coEvery { repository.localPage("v", "order", "draft:1", false) } returns PreparationPage(1, listOf(second), 2, false)
        coEvery { repository.page("v", "order", null, false) } returns PreparationPage(1, emptyList(), 0, false)
        val model = PreparationServiceViewModel(repository, storage, outbox)
        try {
            model.open("order"); runCurrent()
            assertEquals(listOf(first), model.state.value.items); assertTrue(model.state.value.hasMore)
            assertEquals(2, model.state.value.localTotal); assertEquals(0, model.state.value.total)
            model.refresh(more = true); runCurrent()
            assertEquals(listOf(first, second), model.state.value.items); assertFalse(model.state.value.hasMore)
            coVerify(exactly = 1) { repository.page("v", "order", null, false) }
            coEvery { repository.page("v", "order", null, false) } throws java.io.IOException("Sin conexión")
            model.refresh(); runCurrent()
            assertEquals(listOf(first), model.state.value.items); assertTrue(model.state.value.hasMore)
            assertTrue(model.state.value.message!!.contains("Sin conexión"))
        } finally { model.close() }
    }
    @Test fun `canonical server row wins local overlap without adding their totals`() = runTest {
        val repository = mockk<KitchenPreparationRepository>(relaxed = true)
        every { repository.notice } returns MutableStateFlow(null)
        coEvery { repository.restoreCapabilities("v") } just Runs
        coEvery { repository.pending(any(), any()) } returns false
        coEvery { repository.savedPage(any(), any(), any(), any()) } returns null
        val storage = mockk<SecureStorage>(); every { storage.venueId } returns "v"
        val outbox = mockk<SyncOutbox>(); every { outbox.acks } returns MutableSharedFlow<SyncAck>()
        val local = PreparationLine("local:r:p:s", orderId = "order", externalId = "sync:r:0", sourceKey = "round:r:s", stationId = "s",
            productName = "Café", quantity = 1, preparation = PreparationCounts(HELD = 1), unavailableReason = "Ronda guardada")
        val canonical = local.copy(id = "server-row", orderItemId = "server-item", preparation = PreparationCounts(PENDING = 1), unavailableReason = null)
        coEvery { repository.localPage("v", "order", null, false) } returns PreparationPage(1, listOf(local), 1, false)
        coEvery { repository.page("v", "order", null, false) } returns PreparationPage(1, listOf(canonical), 1, false)
        val model = PreparationServiceViewModel(repository, storage, outbox)
        try {
            model.open("order"); runCurrent()
            assertEquals(listOf(canonical), model.state.value.items)
            assertEquals(1, model.state.value.total); assertEquals(1, model.state.value.localTotal)
            assertEquals(1, model.limit(canonical, PreparationAction.START))
        } finally { model.close() }
    }

}
