package com.avoqado.pos.tables.presentation

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.lan.Apertura
import com.avoqado.pos.core.data.lan.LanHubService
import com.avoqado.pos.core.data.lan.LeaseOutcome
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncIntentTypes
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.tables.data.DiningTable
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * D8 (hz:033d44ca:D8): la mesa que este aparato soltó sin red tras cobrarla se vuelve a abrir POR LA COLA (detrás del cobro y del
 * «liberar» de la cuenta anterior), aunque haya red, y su `OPEN_TABLE` dice `visitaNueva`: el SERVIDOR nunca la mete en otra cuenta.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MesaSoltadaVisitaNuevaTest {
    @get:Rule val main = MainDispatcherRule()

    private val venue = "test-d8-visita-nueva"
    private val repository = mockk<TableServiceRepository>(relaxed = true)
    private val outbox = mockk<SyncOutbox>(relaxed = true)
    private val session = TableSession()
    private val storage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns venue }
    private val table = DiningTable(id = "t7", number = "7", areaName = "Salón")

    private fun kotlinx.coroutines.test.TestScope.abrir(): List<JsonObject> {
        val payloads = mutableListOf<JsonObject>()
        every { repository.tables } returns MutableStateFlow(listOf(table))
        every { repository.isLoading } returns MutableStateFlow(false)
        every { outbox.nextLocalFolio(venue) } returns "LOCAL-001"
        coEvery { outbox.enqueue(any(), any(), capture(payloads), any(), any(), isNull()) } answers { arg<String>(3) }
        val hub = mockk<LanHubService>(relaxed = true)
        every { hub.abrirApertura(any(), any(), any()) } answers { Apertura("ap-1", firstArg(), secondArg(), thirdArg(), venue, 0L) }
        coEvery { hub.esperarApertura(any(), any(), any()) } returns LeaseOutcome.NoHub
        val vm = TablesViewModel(repository, mockk(relaxed = true), session, mockk(relaxed = true), storage, outbox, hub,
            com.avoqado.pos.tables.data.FloorPlanRepository(mockk(relaxed = true), mockk(relaxed = true)), mockk(relaxed = true),
            mockk<com.avoqado.pos.tables.data.ApiFuenteDeVersiones>(relaxed = true), mockk(relaxed = true))
        var listas = 0
        try {
            vm.openTableAndStartOrdering(table, 2) { listas++ }
            advanceUntilIdle()
            assertEquals(1, listas)
        } finally { vm.viewModelScope.cancel() }
        return payloads
    }

    @Test fun `mesa soltada sin red - se abre por la cola aunque haya red, como visita nueva`() = runTest {
        every { repository.abrirComoVisitaNueva(venue, "t7") } returns true

        val payload = abrir().single()

        coVerify(exactly = 0) { repository.openTable(any(), any(), any()) }
        coVerify(exactly = 1) { outbox.enqueue(venue, SyncIntentTypes.OPEN_TABLE, any(), any(), false, isNull()) }
        assertEquals("true", payload["visitaNueva"]?.jsonPrimitive?.content)
        assertTrue(requireNotNull(session.current()).isProvisional)
    }

    @Test fun `mesa sin cuenta soltada - como siempre, y sin red el OPEN_TABLE no dice visitaNueva`() = runTest {
        every { repository.abrirComoVisitaNueva(venue, "t7") } returns false
        coEvery { repository.openTable(venue, "t7", 2) } returns Result.failure(IOException("sin red"))

        val payload = abrir().single()

        coVerify(exactly = 1) { repository.openTable(venue, "t7", 2) }
        assertNull(payload["visitaNueva"])
    }
}
