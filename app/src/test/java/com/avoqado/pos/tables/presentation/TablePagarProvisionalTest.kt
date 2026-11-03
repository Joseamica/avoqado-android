package com.avoqado.pos.tables.presentation

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.lan.LanHubService
import com.avoqado.pos.core.data.lan.LeaseOutcome
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncIntentTypes
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tables.data.DiningTable
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/** Las VMs y TableSession son reales; sólo red, impresora y límites de almacenamiento se sustituyen. */
@OptIn(ExperimentalCoroutinesApi::class)
class TablePagarProvisionalTest {
    @get:Rule val main = MainDispatcherRule()

    private val repository = mockk<TableServiceRepository>(relaxed = true)
    private val outbox = mockk<SyncOutbox>(relaxed = true)
    private val session = TableSession()
    private val storage = mockk<SecureStorage>(relaxed = true).also {
        every { it.venueId } returns "test-table-pagar-provisional"
    }

    private fun orderVm(): TableOrderViewModel {
        every { repository.tables } returns MutableStateFlow(emptyList())
        every { repository.ownership } returns MutableStateFlow(TableServiceRepository.TableOwnership())
        every { outbox.acks } returns MutableSharedFlow()
        every { outbox.pendingCount } returns MutableStateFlow(0)
        val config = mockk<PrintConfigRepository>(relaxed = true)
        every { config.getCurrentConfig() } returns PrintConfig()
        val connectivity = mockk<ConnectivityMonitor>(relaxed = true)
        every { connectivity.isConnected } returns MutableStateFlow(false)
        return TableOrderViewModel(
            repository = repository, tableSession = session, printConfigRepository = config,
            comandaPrinter = mockk(relaxed = true), printerService = mockk(relaxed = true),
            secureStorage = storage, syncOutbox = outbox, productsRepository = mockk(relaxed = true),
            connectivityMonitor = connectivity, timeEntryRepository = mockk(relaxed = true),
            comandaDispatcher = mockk(relaxed = true), comandasPendientesStore = mockk(relaxed = true),
        )
    }

    private fun provisional(total: Int) = TableSession.Active(
        tableId = "table-offline", tableNumber = "5", areaName = "Patio", orderId = "local-activo",
        orderNumber = "LOCAL-001", version = 7, totalCents = total, mode = TableSession.Mode.ORDERING,
        openSplitOnArrival = true, isProvisional = true, localOrderId = "local-origen",
        valorRondasSinRedCents = 725,
    )

    @Test fun `preparar pago conserva sesion provisional completa`() = runTest {
        val before = provisional(1200)
        session.start(before)
        val vm = orderVm()
        try {
            assertTrue(vm.preparePagar())
            val after = requireNotNull(session.current())
            assertEquals(TableSession.Mode.PAYING, after.mode)
            assertTrue(after.isProvisional)
            assertEquals("local-origen", after.localOrderId)
            assertEquals(setOf("local-activo", "local-origen"), after.idsDeLaOrden)
            assertEquals(725, after.valorRondasSinRedCents)
            assertTrue(after.openSplitOnArrival)
            assertEquals(before.tableId, after.tableId)
            assertEquals(before.tableNumber, after.tableNumber)
            assertEquals(before.areaName, after.areaName)
            assertEquals(before.orderId, after.orderId)
            assertEquals(before.orderNumber, after.orderNumber)
            assertEquals(before.version, after.version)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `preparar pago vacio no altera sesion`() = runTest {
        val before = provisional(0).copy(valorRondasSinRedCents = 0)
        session.start(before)
        val vm = orderVm()
        try {
            assertFalse(vm.preparePagar())
            assertEquals(before, session.current())
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `apertura provisional propaga mismo id al intent y sesion`() = runTest {
        val venue = "test-table-pagar-provisional"
        val table = DiningTable(id = "table-offline", number = "5", areaName = "Patio")
        val payloads = mutableListOf<JsonObject>()
        every { repository.tables } returns MutableStateFlow(listOf(table))
        every { repository.isLoading } returns MutableStateFlow(false)
        coEvery { repository.openTable(venue, table.id, 2) } returns Result.failure(IOException("sin red"))
        every { outbox.nextLocalFolio(venue) } returns "LOCAL-001"
        coEvery { outbox.enqueue(any(), any(), capture(payloads), any(), any(), isNull()) } answers { arg<String>(3) }
        val hub = mockk<LanHubService>(relaxed = true)
        coEvery { hub.acquire(any(), any(), any()) } returns LeaseOutcome.NoHub
        val vm = TablesViewModel(repository, mockk(relaxed = true), session, mockk(relaxed = true), storage, outbox, hub, com.avoqado.pos.tables.data.FloorPlanRepository(io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true)), io.mockk.mockk(relaxed = true), io.mockk.mockk<com.avoqado.pos.tables.data.ApiFuenteDeVersiones>(relaxed = true), io.mockk.mockk(relaxed = true))
        var ready = 0
        try {
            vm.openTableAndStartOrdering(table, 2) { ready++ }
            advanceUntilIdle()
            assertEquals(1, ready)
            assertEquals(TableActionState.Idle, vm.actionState.value)
            assertEquals(1, payloads.size)
            val payload = payloads.single()
            val local = requireNotNull(payload["localOrderId"]).jsonPrimitive.content
            assertTrue(local.isNotBlank())
            val active = requireNotNull(session.current())
            assertTrue(active.isProvisional)
            assertEquals(local, active.orderId)
            assertEquals(local, active.localOrderId)
            assertEquals(setOf(local), active.idsDeLaOrden)
            assertEquals(0, active.valorRondasSinRedCents)
            assertEquals(table.id, payload["tableId"]?.jsonPrimitive?.content)
            coVerify(exactly = 1) { outbox.enqueue(venue, SyncIntentTypes.OPEN_TABLE, any(), any(), false, isNull()) }
            coVerify(exactly = 1) { repository.markTableOccupiedLocally(table.id, 2, venue) }
        } finally { vm.viewModelScope.cancel() }
    }
}
