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

    private fun cuentaDe(cliente: String, version: Int, subtotal: Double = 100.0) {
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(cuenta(cliente, version, subtotal))
    }

    private fun cuenta(cliente: String, version: Int, subtotal: Double = 100.0) =
        OrderDetail(id = "o1", orderNumber = "ORD-1", customerId = cliente, version = version, subtotal = subtotal, items = emptyList())

    private fun saldo(cliente: String, puntos: Int) =
        CustomerLoyalty(customerId = cliente, active = true, balance = puntos, redemptionRate = 0.10, balanceValue = puntos * 0.10,
            minPointsRedeem = 10, maxRedeemablePoints = puntos, maxRedeemableValue = puntos * 0.10, canRedeem = true)

    private fun saldoDe(cliente: String, puntos: Int) {
        coEvery { repository.getCustomerLoyalty(any(), cliente, any()) } returns Result.success(saldo(cliente, puntos))
    }

    @Test
    fun `al tocar Recompensas (y al volver la red) se relee la cuenta y DESPUÉS el saldo de su clienta (Codex android r5 1, r6 1)`() = runTest {
        val s = vm()
        cuentaDe("ana", version = 3); saldoDe("ana", 500); saldoDe("beto", 50)
        s.loadCheck(); advanceUntilIdle()
        assertEquals("ana", s.loyalty.value?.customerId)

        // Otra caja cambió la clienta de la cuenta a Beto (con o sin red). Tocar «Recompensas» relee todo.
        cuentaDe("beto", version = 4)
        s.releerRecompensas(); advanceUntilIdle()

        assertEquals("beto", s.loyalty.value?.customerId)
        assertEquals(50, s.loyalty.value?.balance)
        coVerify(exactly = 1) { repository.getCustomerLoyalty(any(), "ana", any()) }
        coVerifyOrder {
            repository.getOrderDetail(any(), any())
            repository.getOrderDetail(any(), any())
            repository.getCustomerLoyalty(any(), "beto", any())
        }
    }

    @Test
    fun `al releer, mientras llega la cuenta, no se ofrece el saldo viejo`() = runTest {
        val s = vm()
        cuentaDe("ana", version = 3); saldoDe("ana", 500)
        s.loadCheck(); advanceUntilIdle()
        assertEquals("ana", s.loyalty.value?.customerId)
        coEvery { repository.getOrderDetail(any(), any()) } coAnswers { kotlinx.coroutines.delay(1_000); Result.success(cuenta("ana", 3)) }
        s.releerRecompensas(); runCurrent()
        assertNull(s.loyalty.value)
        advanceUntilIdle()
        assertEquals("ana", s.loyalty.value?.customerId)
    }

    @Test
    fun `cualquier recarga de la cuenta en vuelo (una mutación, un acuse) deja de ofrecer el saldo hasta que llega (Codex android r6 2)`() = runTest {
        val s = vm()
        cuentaDe("ana", version = 3); saldoDe("ana", 500)
        s.loadCheck(); advanceUntilIdle()
        assertEquals("ana", s.loyalty.value?.customerId)
        coEvery { repository.getOrderDetail(any(), any()) } coAnswers { kotlinx.coroutines.delay(1_000); Result.success(cuenta("ana", 4, subtotal = 1.0)) }
        saldoDe("ana", 10)
        s.loadCheck(); runCurrent()
        assertNull(s.loyalty.value)
        advanceUntilIdle()
        assertEquals(10, s.loyalty.value?.balance)
    }

    @Test
    fun `si la recarga de la cuenta falla no se ofrece saldo ni se lee el de la clienta vieja (Codex android r6 2)`() = runTest {
        val s = vm()
        cuentaDe("ana", version = 3); saldoDe("ana", 500)
        s.loadCheck(); advanceUntilIdle()
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.failure(java.io.IOException("sin red"))
        s.releerRecompensas(); advanceUntilIdle()
        assertNull(s.loyalty.value)
        coVerify(exactly = 1) { repository.getCustomerLoyalty(any(), "ana", any()) }
    }

    @Test
    fun `el fallo de una lectura es de esa lectura - se borra al empezar otra y no se muestra en otra cuenta (Codex android r6 4)`() = runTest {
        val s = vm()
        cuentaDe("ana", version = 3)
        coEvery { repository.getCustomerLoyalty(any(), "ana", any()) } returns Result.failure(IllegalStateException("403"))
        s.loadCheck(); advanceUntilIdle()
        assertEquals(com.avoqado.pos.core.data.network.FalloDeLectura.SERVIDOR, s.loyaltyFallo.value)

        // Otra cuenta (otra clienta): mientras llega su saldo, no se dice «no se pudo leer».
        cuentaDe("beto", version = 4)
        coEvery { repository.getCustomerLoyalty(any(), "beto", any()) } coAnswers { kotlinx.coroutines.delay(1_000); Result.success(saldo("beto", 50)) }
        s.loadCheck(); runCurrent()
        assertNull(s.loyaltyFallo.value)
        advanceUntilIdle()
        assertEquals("beto", s.loyalty.value?.customerId)
    }

    @Test
    fun `máximo 0 sin pagos y con algo que descontar - relee la cuenta UNA vez (Codex android r6 7)`() = runTest {
        val s = vm()
        // La GET de puntos llegó cuando otra caja ya había cambiado la clienta: el SERVIDOR (D650) contesta máximo 0.
        cuentaDe("ana", version = 3)
        coEvery { repository.getCustomerLoyalty(any(), "ana", any()) } returns Result.success(
            saldo("ana", 500).copy(maxRedeemablePoints = 0, maxRedeemableValue = 0.0, canRedeem = false),
        )
        saldoDe("beto", 50)
        coEvery { repository.getOrderDetail(any(), any()) } returnsMany listOf(Result.success(cuenta("ana", 3)), Result.success(cuenta("beto", 4)))
        s.loadCheck(); advanceUntilIdle()
        assertEquals("beto", s.loyalty.value?.customerId)
        coVerify(exactly = 2) { repository.getOrderDetail(any(), any()) }
    }

    @Test
    fun `si el máximo 0 se repite con la misma cuenta, no relee en ciclo`() = runTest {
        val s = vm()
        cuentaDe("ana", version = 3)
        coEvery { repository.getCustomerLoyalty(any(), "ana", any()) } returns Result.success(
            saldo("ana", 500).copy(maxRedeemablePoints = 0, maxRedeemableValue = 0.0, canRedeem = false),
        )
        s.loadCheck(); advanceUntilIdle()
        coVerify(exactly = 2) { repository.getOrderDetail(any(), any()) }
        assertEquals(0, s.loyalty.value?.maxRedeemablePoints)
    }

    @Test
    fun `el aviso de Recompensas se marca en el VM y se renueva aunque la pantalla se recree (Codex android r5 2, r6 5)`() = runTest {
        val s = vm()
        s.mostrarAvisoDeRecompensas("sin red")
        // La pantalla se recreó: sólo conoce el motivo de ahora.
        s.renovarAvisoDeRecompensas("pocos")
        assertEquals("pocos", s.blockedNotice.value)
        // Ya se puede canjear: el aviso se va.
        s.renovarAvisoDeRecompensas(null)
        assertNull(s.blockedNotice.value)
        // El aviso de OTRO botón no se toca.
        s.showBlockedReason("otro botón")
        s.renovarAvisoDeRecompensas("pocos")
        assertEquals("otro botón", s.blockedNotice.value)
    }
}
