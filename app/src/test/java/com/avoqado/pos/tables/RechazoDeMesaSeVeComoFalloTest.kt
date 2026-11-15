package com.avoqado.pos.tables

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.domain.SolicitudDeTarjeta
import com.avoqado.pos.giftcards.domain.SolicitudesDeTarjeta
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tables.data.DiningTable
import com.avoqado.pos.tables.data.OpenCheckSummary
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.OrderDetailItem
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.tables.presentation.TableOrderViewModel
import com.avoqado.pos.tables.presentation.TablesViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * D2 (encargo e72930): «Mover», «Dividir por puesto» y «Anular cuenta» de la mesa, y «Mover» del plano.
 *
 * Antes la pantalla mandaba el motivo de un RECHAZO (cobro de tarjeta vivo, saldo en duda, 409/403 del servidor) por el
 * mismo aviso de palomita verde que un éxito: la cuenta seguía igual y la pantalla decía lo contrario. Es el reverso de D72.
 *
 * Invariante: **un rechazo se ve como fallo** (aviso rojo con el motivo) y un éxito, nunca. El dueño del aviso de fallo es
 * el ViewModel; la pantalla sólo pone la palomita cuando `ok`.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class RechazoDeMesaSeVeComoFalloTest(private val accion: String, private val caso: String) {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())
    private val repo = mockk<TableServiceRepository>(relaxed = true)
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val outbox = mockk<SyncOutbox>(relaxed = true)
    private val session = TableSession()
    private var raw: String? = null
    private val motivoDelServidor = "La cuenta tiene un cobro con tarjeta en curso"
    private val table = DiningTable("t", "1", openOrders = listOf(OpenCheckSummary("o", total = 100.0)))
    private val destino = DiningTable("t2", "2")
    private val detalle = OrderDetail("o", total = 100.0, items = listOf(
        OrderDetailItem(id = "line", productName = "A", quantity = 1, unitPrice = 50.0, total = 50.0, seat = 1),
        OrderDetailItem(id = "line2", productName = "B", quantity = 1, unitPrice = 50.0, total = 50.0, seat = 2)))

    private fun <T> resultado(valor: T): Result<T> =
        if (caso == "rechazo") Result.failure(IllegalStateException(motivoDelServidor)) else Result.success(valor)

    @Before fun prepare() {
        every { storage.venueId } returns "a"
        every { storage.giftCardRequestsJson } answers { raw }
        every { repo.tables } returns MutableStateFlow(listOf(table, destino))
        every { repo.isLoading } returns MutableStateFlow(false)
        every { repo.ownership } returns MutableStateFlow(TableServiceRepository.TableOwnership())
        every { outbox.acks } returns MutableSharedFlow()
        every { outbox.pendingCount } returns MutableStateFlow(0)
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns
            com.avoqado.pos.core.data.sync.TrabajoSinResolver(0, 0, emptySet(), 0)
        coEvery { repo.getOrderDetail(any(), any()) } returns Result.success(detalle)
        coEvery { repo.getCustomerLoyalty(any(), any(), any()) } returns Result.failure(IllegalStateException("sin lealtad"))
        coEvery { repo.moveOrder(any(), any(), any()) } answers { resultado(Unit) }
        coEvery { repo.splitOrderBySeat(any(), any(), any()) } answers { resultado(Unit) }
        coEvery { repo.cancelOrder(any(), any(), any()) } answers { resultado(Unit) }
        coEvery { repo.clearTable(any(), any(), any()) } returns Result.success(Unit)
        session.start(TableSession.Active("t", "1", null, "o", "ORD", 1, 10000, TableSession.Mode.ORDERING))
    }

    @Test fun elAvisoDiceLoQuePaso() = runTest {
        val network = mockk<ConnectivityMonitor>(relaxed = true)
        every { network.isConnected } returns MutableStateFlow(true)
        val config = mockk<PrintConfigRepository>(relaxed = true)
        every { config.getCurrentConfig() } returns PrintConfig()
        val order = TableOrderViewModel(repo, session, config, mockk(relaxed = true), mockk(relaxed = true), storage, outbox,
            mockk(relaxed = true), network, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        val floor = TablesViewModel(repo, mockk(relaxed = true), session, mockk(relaxed = true), storage, outbox, mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), cashDrawerRepository = mockk(relaxed = true))
        try {
            order.loadCheck(); advanceUntilIdle()
            // Un canje de tarjeta de regalo sin desenlace en esta cuenta: el candado de saldo rechaza ANTES de tocar la red.
            if (caso == "candado") raw = SolicitudesDeTarjeta.agregar(null, SolicitudDeTarjeta(tipo = "GIFT_CARD_REDEEM", llave = "k",
                venueId = "a", creadaEn = 1, orderId = "o", cuerpo = "{}", mandado = true, porTerminar = false))
            var ok: Boolean? = null
            var msg: String? = null
            val listo: (Boolean, String) -> Unit = { o, m -> ok = o; msg = m }
            when (accion) {
                "moveTable" -> order.moveTable("t2", listo)
                "splitBySeat" -> order.splitBySeat(listo)
                "anularCuenta" -> order.anularCuenta("motivo", listo)
                "bulkMover" -> floor.bulkMover(table, "t2", listo)
            }
            advanceUntilIdle()
            val (aviso, esError) = if (accion == "bulkMover") floor.actionMessage.value to floor.actionIsError.value
                else order.actionMessage.value to order.actionIsError.value
            if (caso == "exito") {
                assertEquals("$accion/$caso: callback", true, ok)
                assertFalse("$accion: un éxito nunca sale en rojo", esError)
            } else {
                assertEquals("$accion/$caso: callback", false, ok)
                assertTrue("$accion/$caso: un rechazo tiene que verse como fallo, no con palomita", esError)
                assertNotNull("$accion/$caso: el motivo se dice", aviso)
                assertEquals("$accion/$caso: el aviso rojo lleva el mismo motivo que el callback", msg, aviso)
            }
        } finally { order.viewModelScope.cancel(); floor.viewModelScope.cancel() }
    }

    companion object {
        // «candado» no aplica a mover: mover no revisa el saldo a propósito (revisarSaldo = false).
        @JvmStatic @Parameterized.Parameters(name = "{0}/{1}") fun casos(): List<Array<Any>> =
            listOf("moveTable", "splitBySeat", "anularCuenta", "bulkMover").flatMap { a ->
                listOf("exito", "rechazo", "candado").filter { c -> !(c == "candado" && a in setOf("moveTable", "bulkMover")) }
                    .map { arrayOf<Any>(a, it) }
            }
    }
}
