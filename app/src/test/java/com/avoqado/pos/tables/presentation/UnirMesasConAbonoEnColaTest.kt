package com.avoqado.pos.tables.presentation

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.EfectivoDelPedido
import com.avoqado.pos.tables.data.*
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

/**
 * D62 (Codex r2 #1): «Unir mesas» del plano también fusiona cuentas (`bulkUnir` → `mergeOrders`). Una cuenta con efectivo en la cola de
 * COBROS de este aparato no se une: su fila se queda con la llave de esa cuenta y la mesa destino ofrecería su total. Todas o ninguna.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnirMesasConAbonoEnColaTest {
    @get:Rule val main = MainDispatcherRule()
    private val venue = "v-unir"
    private val repository = mockk<TableServiceRepository>(relaxed = true)
    private val session = TableSession()
    private val drafts = TableRoundDraftStore(Files.createTempDirectory("unir-mesas").toFile())
    private val cola = mockk<CashPaymentRepository>()
    private val efectivo = mutableMapOf<String, EfectivoDelPedido?>()
    private val destino = DiningTable("t-b", "2", openOrders = listOf(OpenCheckSummary("order-B", "B-1", total = 100.0)))
    private val origen = DiningTable("t-a", "1", openOrders = listOf(OpenCheckSummary("order-A", "A-1", total = 100.0),
        OpenCheckSummary("order-C", "C-1", total = 30.0)))

    private fun vm(): TablesViewModel {
        every { repository.tables } returns MutableStateFlow(listOf(destino, origen))
        every { repository.isLoading } returns MutableStateFlow(false)
        coEvery { repository.mergeOrders(any(), any(), any()) } returns Result.success(Unit)
        coEvery { cola.efectivoDelPedido(any()) } answers {
            val id = firstArg<String>()
            if (id in efectivo) efectivo[id] ?: throw IllegalStateException("disco") else EfectivoDelPedido.NINGUNO
        }
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.venueId } returns venue
        return TablesViewModel(repository, mockk(relaxed = true), session, mockk(relaxed = true), storage, mockk<SyncOutbox>(relaxed = true),
            mockk(relaxed = true), FloorPlanRepository(mockk(relaxed = true), mockk(relaxed = true)), mockk(relaxed = true),
            mockk<ApiFuenteDeVersiones>(relaxed = true), mockk(relaxed = true), efectivoEnColaDeCobros = cola, draftStore = drafts)
            .also { it.discoParaFusionar = kotlin.coroutines.EmptyCoroutineContext }
    }

    private fun TestScope.unir(subject: TablesViewModel): Triple<Int, Int, String?>? {
        var resultado: Triple<Int, Int, String?>? = null
        subject.bulkUnir(destino, listOf(origen)) { ok, fail, err -> resultado = Triple(ok, fail, err) }
        advanceUntilIdle()
        return resultado
    }

    @Test fun `P2 D62 unir mesas no junta ninguna cuenta si una tiene un abono en la cola de cobros`() = runTest {
        val subject = vm()
        try {
            efectivo["order-C"] = EfectivoDelPedido.EN_COLA // la SEGUNDA cuenta de la mesa: tampoco se une la primera
            assertEquals(Triple(0, 2, FUSIONAR_COBRO_SIN_SINCRONIZAR), unir(subject))
            coVerify(exactly = 0) { repository.mergeOrders(any(), any(), any()) }

            efectivo["order-C"] = EfectivoDelPedido.NINGUNO // ya subió: se unen las dos
            assertEquals(Triple(2, 0, null), unir(subject))
            coVerify(exactly = 1) { repository.mergeOrders(venue, "order-B", "order-A") }
            coVerify(exactly = 1) { repository.mergeOrders(venue, "order-B", "order-C") }
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `D62 unir mesas se frena con un cobro por revisar, la cola sin leer o el id local del borrador`() = runTest {
        val subject = vm()
        try {
            efectivo["order-A"] = EfectivoDelPedido.POR_REVISAR
            assertEquals(FUSIONAR_COBRO_POR_REVISAR, unir(subject)?.third)
            efectivo["order-A"] = null
            assertEquals(FUSIONAR_COBROS_SIN_LEER, unir(subject)?.third)
            efectivo.remove("order-A")
            drafts.promote(venue, "local-A", "order-A") // A nació sin red; tras reiniciar sólo el disco sabe que fue «local-A»
            efectivo["local-A"] = EfectivoDelPedido.EN_COLA
            assertEquals(FUSIONAR_COBRO_SIN_SINCRONIZAR, unir(subject)?.third)
            coVerify(exactly = 0) { repository.mergeOrders(any(), any(), any()) }
        } finally { subject.viewModelScope.cancel() }
    }
}
