package com.avoqado.pos.tables.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tables.data.CustomerLoyalty
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.TableRoundDraftStore
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** «Recompensas» en la cuenta de la mesa: al volver la red y el aviso del botón gris (Codex android r5). */
@OptIn(ExperimentalCoroutinesApi::class)
class TableOrderRecompensasTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val repository = mockk<TableServiceRepository>(relaxed = true)
    private val syncOutbox = mockk<SyncOutbox>(relaxed = true)
    private val tableSession = TableSession()

    private fun vm(): TableOrderViewModel {
        every { repository.tables } returns MutableStateFlow(emptyList())
        every { repository.ownership } returns MutableStateFlow(TableServiceRepository.TableOwnership())
        every { syncOutbox.acks } returns MutableSharedFlow()
        every { syncOutbox.pendingCount } returns MutableStateFlow(0)
        val connectivity = mockk<ConnectivityMonitor>(relaxed = true)
        every { connectivity.isConnected } returns MutableStateFlow(true)
        val printConfig = mockk<PrintConfigRepository>(relaxed = true)
        every { printConfig.getCurrentConfig() } returns PrintConfig()
        tableSession.start(
            TableSession.Active(
                tableId = "t1", tableNumber = "5", areaName = null, orderId = "o1", orderNumber = "ORD-1",
                version = 3, totalCents = 0, mode = TableSession.Mode.ORDERING,
            ),
        )
        return TableOrderViewModel(
            repository = repository, tableSession = tableSession, printConfigRepository = printConfig,
            comandaPrinter = mockk(relaxed = true), printerService = mockk(relaxed = true),
            secureStorage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns "venue-1" },
            syncOutbox = syncOutbox, productsRepository = mockk(relaxed = true), connectivityMonitor = connectivity,
            timeEntryRepository = mockk(relaxed = true), comandaDispatcher = mockk(relaxed = true),
            comandasPendientesStore = mockk(relaxed = true),
            draftStore = TableRoundDraftStore(java.nio.file.Files.createTempDirectory("recompensas").toFile()),
        )
    }

    private fun cuentaDe(cliente: String, version: Int) {
        coEvery { repository.getOrderDetail(any(), any()) } returns
            Result.success(OrderDetail(id = "o1", orderNumber = "ORD-1", customerId = cliente, version = version, items = emptyList()))
    }

    private fun saldoDe(cliente: String, puntos: Int) {
        coEvery { repository.getCustomerLoyalty(any(), cliente, any()) } returns Result.success(
            CustomerLoyalty(customerId = cliente, active = true, balance = puntos, redemptionRate = 0.10, maxRedeemablePoints = puntos, canRedeem = true),
        )
    }

    @Test
    fun `al volver la red se relee la cuenta antes que el saldo - el cambio de clienta de otra caja se ve (Codex android r5 1)`() = runTest {
        val s = vm()
        cuentaDe("ana", version = 3); saldoDe("ana", 500); saldoDe("beto", 50)
        s.loadCheck(); advanceUntilIdle()
        assertEquals("ana", s.loyalty.value?.customerId)

        // Sin red, otra caja cambió la clienta de la cuenta a Beto. Vuelve la red sin salir de la pantalla.
        cuentaDe("beto", version = 4)
        s.alVolverLaRed(); advanceUntilIdle()

        assertEquals("beto", s.loyalty.value?.customerId)
        assertEquals(50, s.loyalty.value?.balance)
        // Los puntos de Ana se leyeron una sola vez (al abrir); al volver la red, primero la cuenta y luego el saldo de Beto.
        coVerify(exactly = 1) { repository.getCustomerLoyalty(any(), "ana", any()) }
        coVerifyOrder {
            repository.getOrderDetail(any(), any())
            repository.getOrderDetail(any(), any())
            repository.getCustomerLoyalty(any(), "beto", any())
        }
    }

    @Test
    fun `al volver la red, mientras llega la cuenta, no se ofrece el saldo viejo`() = runTest {
        val s = vm()
        cuentaDe("ana", version = 3); saldoDe("ana", 500)
        s.loadCheck(); advanceUntilIdle()
        assertEquals("ana", s.loyalty.value?.customerId)
        // La cuenta tarda en llegar al volver la red.
        coEvery { repository.getOrderDetail(any(), any()) } coAnswers {
            kotlinx.coroutines.delay(1_000)
            Result.success(OrderDetail(id = "o1", orderNumber = "ORD-1", customerId = "ana", version = 3, items = emptyList()))
        }
        s.alVolverLaRed(); runCurrent()
        assertNull(s.loyalty.value)
        advanceUntilIdle()
        assertEquals("ana", s.loyalty.value?.customerId)
    }

    @Test
    fun `el aviso que dejó el botón gris se renueva con el motivo nuevo (Codex android r5 2)`() {
        assertEquals("pocos", avisoDeRecompensasAlDia(aviso = "sin red", motivoAnterior = "sin red", motivoNuevo = "pocos"))
        // Ya se puede canjear: el aviso se va.
        assertNull(avisoDeRecompensasAlDia(aviso = "sin red", motivoAnterior = "sin red", motivoNuevo = null))
        // El aviso de OTRO botón no se toca.
        assertEquals("otro", avisoDeRecompensasAlDia(aviso = "otro", motivoAnterior = "sin red", motivoNuevo = "pocos"))
        assertNull(avisoDeRecompensasAlDia(aviso = null, motivoAnterior = "sin red", motivoNuevo = "pocos"))
    }

    @Test
    fun `el VM renueva sólo el aviso de Recompensas`() = runTest {
        val s = vm()
        s.showBlockedReason("sin red")
        s.renovarAvisoDeRecompensas(motivoAnterior = "sin red", motivoNuevo = "pocos")
        assertEquals("pocos", s.blockedNotice.value)
        s.showBlockedReason("otro botón")
        s.renovarAvisoDeRecompensas(motivoAnterior = "pocos", motivoNuevo = null)
        assertEquals("otro botón", s.blockedNotice.value)
    }
}
