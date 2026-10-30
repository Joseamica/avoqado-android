package com.avoqado.pos.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.PrefsEnMemoria
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CortesiaPersistida
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PayLaterQueue
import com.avoqado.pos.payment.data.PayLaterRow
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.reservations.data.ReservationRepository
import com.avoqado.pos.sync.data.SalesNoticeStore
import com.avoqado.pos.sync.data.SalesNoticeSummary
import com.avoqado.pos.sync.presentation.QuarantineViewModel
import com.avoqado.pos.sync.presentation.SalesNoticesViewModel
import com.avoqado.pos.sync.presentation.openSalesNoticeBanner
import com.avoqado.pos.sync.presentation.salesNoticeBannerCount
import com.avoqado.pos.sync.presentation.salesNoticeBannerActionLabel
import com.avoqado.pos.tables.data.TableSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Task10: real replay, durable queue, SQL aggregate and the router consumed by both NavGraph layouts. */
@OptIn(ExperimentalCoroutinesApi::class)
class PagarDespuesAvisoTest {
    @get:Rule val main = MainDispatcherRule()

    private class Fixture : AutoCloseable {
        val sql = SalesNoticeSqlTest.Fixture()
        val disk = PrefsEnMemoria()
        val copies = PrefsEnMemoria()
        var venue: String? = "venue-A"
        var role = "MANAGER"
        val storage = mockk<SecureStorage>(relaxed = true).also {
            every { it.venueId } answers { venue }
            every { it.userId } returns "manager-A"
            every { it.userRole } answers { role }
        }
        val orders = mockk<OrderRepository>()
        val network = mockk<ConnectivityMonitor>().also {
            every { it.isConnected } returns MutableStateFlow(false)
            every { it.isServerReachable } returns MutableStateFlow(false)
            every { it.isFullyConnected } returns false
        }
        val queue = PayLaterQueue(disk.context, orders, storage, SavedCartsRepository(copies.context), network)
            .apply { io = Dispatchers.Unconfined }
        private val cash = mockk<CashPaymentRepository>().also {
            every { it.salesNoticeReservedIds() } returns emptySet()
            every { it.observarSinConfirmar() } returns flowOf(emptyList())
        }
        val store = SalesNoticeStore(sql.dao, cash)
        private val models = mutableListOf<ViewModel>()

        fun sales(): SalesNoticesViewModel {
            val session = mockk<TableSession>().also { every { it.contextoActual() } returns 1L }
            return SalesNoticesViewModel(store, storage, RoleManager(storage), session, mockk(), network)
                .also { models.add(it) }
        }

        fun quarantine(): QuarantineViewModel {
            val outbox = mockk<SyncOutbox>().also { every { it.rejectedCount } returns MutableStateFlow(0) }
            return QuarantineViewModel(outbox, storage, mockk<PaymentSyncService>(), mockk<ReservationRepository>(),
                RoleManager(storage), cash, queue).also { models.add(it) }
        }

        suspend fun rejected(id: String = "pay-later:own", venueId: String = "venue-A"): PayLaterRow {
            val pending = PayLaterRow(id, venueId, "cashier-A", "customer-A", "Ana", 10000,
                """{"externalId":"$id","isOfflineReplay":true}""", creadaEn = 1000,
                estado = PayLaterRow.Estado.PENDIENTE)
            assertTrue(queue.guardar(pending))
            coEvery { orders.enviarPagarDespues(venueId, pending.cuerpo) } returns
                OrderRepository.RespuestaCruda(400, "application/json", null, """{"message":"Cliente no encontrado"}""")
            queue.drenar()
            return queue.filas.value.single { it.id == id }.also { assertEquals(PayLaterRow.Estado.RECHAZADA, it.estado) }
        }

        suspend fun review(): PayLaterRow {
            val pending = PayLaterRow("pay-later:review", "venue-A", "cashier-A", "customer-A", "Ana", 10000,
                """{"externalId":"pay-later:review","isOfflineReplay":true}""", creadaEn = 1001,
                estado = PayLaterRow.Estado.PENDIENTE)
            assertTrue(queue.guardar(pending))
            coEvery { orders.enviarPagarDespues("venue-A", pending.cuerpo) } returns
                OrderRepository.RespuestaCruda(201, "application/json", null,
                    """{"success":true,"order":{"id":"order-A","total":101.0}}""")
            queue.drenar()
            return queue.filas.value.single().also {
                assertEquals(PayLaterRow.Estado.REVISAR, it.estado)
                assertEquals(10100, it.totalRegistradoCents)
            }
        }

        suspend fun count(summary: SalesNoticeSummary?, reservations: Int? = 0, fallback: Int? = null): Int {
            val own = queue.porRevisar.first()
            return salesNoticeBannerCount(summary, reservations, fallback ?: own, payLaterCount = own)
        }

        suspend fun count(reservations: Int? = 0, fallback: Int? = null): Int =
            count(store.summary(requireNotNull(venue)), reservations, fallback)

        override fun close() {
            models.forEach { it.viewModelScope.cancel() }
            sql.close()
        }
    }

    @Test fun rejected_replay_stays_visible_with_a_valid_empty_sales_summary() = runTest {
        Fixture().use { f ->
            f.rejected()
            val before = f.disk.enDisco.toMap()
            val sales = f.sales(); sales.refreshAlertSummary(); runCurrent()
            assertNotNull(sales.summary.value)
            assertEquals(0, sales.summary.value?.pendingCount)
            assertEquals(1, f.count(sales.summary.value))
            assertEquals(before, f.disk.enDisco)
        }
    }

    @Test fun replay_created_for_another_amount_stays_visible_with_a_valid_empty_summary() = runTest {
        Fixture().use { f ->
            val row = f.review()
            assertEquals("order-A", row.orderId)
            assertEquals(1, f.count())
        }
    }

    @Test fun unknown_sales_or_reservations_uses_raw_total_without_counting_pay_later_twice() = runTest {
        Fixture().use { f ->
            f.rejected()
            assertEquals(1, f.count(summary = null, fallback = 1))
            assertEquals(1, f.count(reservations = null, fallback = 1))
        }
    }

    @Test fun understood_cash_with_the_same_id_cannot_acknowledge_pay_later() = runTest {
        Fixture().use { f ->
            val row = f.rejected()
            f.sql.payment(row.id)
            val source = f.sql.economic(); val queueSource = f.disk.enDisco.toMap()
            val payment = f.store.page("venue-A").rows.single()
            assertEquals("PAYMENT", payment.origin)
            assertTrue(f.sql.write { f.store.acknowledge(payment, "manager-A", 2000) })
            assertEquals(1, f.store.summary("venue-A").pendingCount)
            assertEquals(0, f.store.summary("venue-A").unseenCount)
            assertEquals(source, f.sql.economic())
            assertEquals(queueSource, f.disk.enDisco)
            assertEquals(1, f.queue.porRevisar.first())
            assertEquals(1, f.count(fallback = 2))
        }
    }

    @Test fun mixed_cash_and_pay_later_keep_both_contributions() = runTest {
        Fixture().use { f ->
            f.rejected(); f.sql.payment()
            assertEquals(2, f.count(fallback = 2))
        }
    }

    @Test fun manager_notice_opens_the_quarantine_that_actually_lists_pay_later() = runTest {
        Fixture().use { f ->
            val own = f.rejected()
            val sales = f.sales(); val quarantine = f.quarantine(); runCurrent()
            assertTrue(sales.opensPendingSales)
            assertEquals(listOf(own), quarantine.cuentasPagarDespues.value)
            assertTrue(f.store.page("venue-A").rows.isEmpty())
            var pendingSales = 0; var quarantines = 0
            openSalesNoticeBanner(sales.opensPendingSales, true, f.queue.porRevisar.first(),
                onPendingSales = { pendingSales++ }, onQuarantine = { quarantines++ })
            assertEquals("The primary notice must reach the list holding this row", 1, quarantines)
            assertEquals(0, pendingSales)
        }
    }

    @Test fun mixed_notice_still_reaches_pay_later_while_cash_keeps_its_existing_sales_access() = runTest {
        Fixture().use { f ->
            f.rejected(); f.sql.payment()
            val sales = f.sales()
            assertTrue(sales.opensPendingSales)
            assertEquals(listOf("cash"), f.store.page("venue-A").rows.map { it.id })
            var pendingSales = 0; var quarantines = 0
            openSalesNoticeBanner(sales.opensPendingSales, true, f.queue.porRevisar.first(),
                onPendingSales = { pendingSales++ }, onQuarantine = { quarantines++ })
            assertEquals(1, quarantines)
            assertEquals(0, pendingSales)
            assertTrue("Task8 directed sales access is preserved", sales.opensPendingSales)
        }
    }

    @Test fun zero_pay_later_preserves_the_previous_sales_route_and_count() = runTest {
        Fixture().use { f ->
            f.sql.payment()
            val sales = f.sales()
            var pendingSales = 0; var quarantines = 0
            openSalesNoticeBanner(sales.opensPendingSales, true, f.queue.porRevisar.first(),
                onPendingSales = { pendingSales++ }, onQuarantine = { quarantines++ })
            assertEquals(1, pendingSales); assertEquals(0, quarantines)
            assertEquals(1, f.count(fallback = 1))
            openSalesNoticeBanner(sales.opensPendingSales, false, 0,
                onPendingSales = { pendingSales++ }, onQuarantine = { quarantines++ })
            assertEquals(1, pendingSales); assertEquals(1, quarantines)
        }
    }

    @Test fun venue_change_updates_the_same_counter_and_destination_before_sales_refresh() = runTest {
        Fixture().use { f ->
            val own = f.rejected()
            f.venue = "venue-B"; f.queue.alCambiarLaSesion()
            val foreign = f.rejected("pay-later:other", "venue-B")
            val secondForeign = f.rejected("pay-later:other-two", "venue-B")
            f.venue = "venue-A"; f.queue.alCambiarLaSesion()
            val quarantine = f.quarantine(); runCurrent()
            assertEquals(listOf(own), quarantine.cuentasPagarDespues.value)
            assertEquals(1, f.queue.porRevisar.first())
            val disk = f.disk.enDisco.toMap()
            f.venue = "venue-B"; f.queue.alCambiarLaSesion(); runCurrent()
            assertEquals(setOf(foreign, secondForeign), quarantine.cuentasPagarDespues.value.toSet())
            assertEquals(2, f.queue.porRevisar.first())
            assertEquals(disk, f.disk.enDisco)
            f.venue = "venue-C"; f.queue.alCambiarLaSesion(); runCurrent()
            assertTrue(quarantine.cuentasPagarDespues.value.isEmpty())
            assertEquals(0, f.queue.porRevisar.first())
            assertEquals(0, f.count())
            f.venue = "venue-B"; f.queue.alCambiarLaSesion(); runCurrent()
            assertEquals(2, f.count())
        }
    }

    @Test fun review_discard_reaches_zero_through_the_existing_manager_action() = runTest {
        Fixture().use { f ->
            val own = f.review()
            val quarantine = f.quarantine(); runCurrent()
            val money = f.sql.economic()
            assertEquals(1, f.queue.porRevisar.first())
            quarantine.descartarCuenta(own.id); runCurrent()
            assertEquals(0, f.queue.porRevisar.first())
            assertTrue(quarantine.cuentasPagarDespues.value.isEmpty())
            assertEquals(0, f.count())
            assertTrue(f.disk.enDisco.isEmpty())
            assertEquals(money, f.sql.economic())
        }
    }

    @Test fun legitimate_discard_reduces_one_to_zero_and_does_not_touch_other_venue_or_money() = runTest {
        Fixture().use { f ->
            val own = f.rejected()
            f.venue = "venue-B"; f.queue.alCambiarLaSesion()
            val foreign = f.rejected("pay-later:other", "venue-B")
            val foreignDisk = f.disk.enDisco.getValue("fila:${foreign.id}")
            f.venue = "venue-A"; f.queue.alCambiarLaSesion()
            val money = f.sql.economic()
            val quarantine = f.quarantine(); runCurrent()
            assertEquals(1, f.queue.porRevisar.first())
            quarantine.descartarCuenta(own.id); runCurrent()
            assertEquals(0, f.queue.porRevisar.first())
            assertTrue(quarantine.cuentasPagarDespues.value.isEmpty())
            assertEquals(0, f.count())
            assertEquals(listOf(foreign), f.queue.filas.value)
            assertEquals(foreignDisk, f.disk.enDisco.getValue("fila:${foreign.id}"))
            assertEquals(money, f.sql.economic())
        }
    }

    @Test fun cashier_cannot_discard_the_queue_even_when_the_notice_opens_quarantine() = runTest {
        Fixture().use { f ->
            val own = f.rejected(); f.role = "CASHIER"
            val source = f.disk.enDisco.toMap()
            val sales = f.sales(); val quarantine = f.quarantine(); runCurrent()
            assertFalse(sales.opensPendingSales); assertFalse(quarantine.canResolve)
            var pendingSales = 0; var quarantines = 0
            openSalesNoticeBanner(sales.opensPendingSales, true, f.queue.porRevisar.first(),
                onPendingSales = { pendingSales++ }, onQuarantine = { quarantines++ })
            assertEquals(0, pendingSales); assertEquals(1, quarantines)
            quarantine.descartarCuenta(own.id); runCurrent()
            assertEquals(source, f.disk.enDisco)
            assertEquals(listOf(own), quarantine.cuentasPagarDespues.value)
        }
    }

    @Test fun notice_label_follows_the_pay_later_route_and_returns_to_sales_at_zero() {
        assertEquals("Revisar", salesNoticeBannerActionLabel(true, 1))
        assertEquals("Ver ventas", salesNoticeBannerActionLabel(true, 0))
        assertEquals("Revisar", salesNoticeBannerActionLabel(false, 0))
    }

    @Test fun known_account_keeps_its_folio_visible_when_saving_review_fails() = runTest {
        Fixture().use { f ->
            val rejected = f.rejected()
            val source = rejected.copy(cuerpo = """{"externalId":"${rejected.id}","total":10000,"items":[{"externalId":"comp-line","isCortesia":true,"cortesiaReason":"Invitación"}]}""")
            assertTrue(f.queue.guardar(source))
            coEvery { f.orders.enviarPagarDespues("venue-A", source.cuerpo) } returns
                OrderRepository.RespuestaCruda(201, "application/json", null, """{"success":true,"order":{"id":"order-A","total":100.0}}""")
            val quarantine = f.quarantine(); runCurrent()
            val disk = f.disk.enDisco.toMap()
            f.disk.discoSano = false
            quarantine.reintentarCuenta(source.id); runCurrent()
            assertEquals(listOf(source), f.queue.filas.value)
            assertEquals(disk, f.disk.enDisco)
            assertNull(quarantine.successMessage.value)
            assertEquals("${CortesiaPersistida.AVISO} Folio: order-A.", quarantine.errorMessage.value)
        }
    }
}
