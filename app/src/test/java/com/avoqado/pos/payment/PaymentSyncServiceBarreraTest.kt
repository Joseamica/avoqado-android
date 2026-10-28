package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.*
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.CapturaDelCobro
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaymentSyncServiceBarreraTest {
    @get:Rule val main = MainDispatcherRule()
    private val dao = mockk<PendingPaymentDao>(relaxed = true)
    private val outbox = mockk<SyncOutbox>(relaxed = true)
    private fun cobro(id: String, order: String?, venue: String = "FULLTEST-v1") = PendingPaymentEntity(
        id = id, venueId = venue, staffId = "FULLTEST-staff", amountCents = 13300, tipCents = 0,
        method = "CASH", paymentType = if (order == null) "FAST" else "ORDER", orderId = order,
        q15CaptureJson = ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(
            venueId = venue, key = id, orderId = order, amountCents = 13300, tipCents = 0,
            method = "CASH", splitType = "FULLPAYMENT",
        )),
        capturedOrderRef = order,
    )
    private fun TestScope.servicio(payments: List<PendingPaymentEntity>): PaymentSyncService {
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.venueId } returns "FULLTEST-seleccionada"
        val red = mockk<ConnectivityMonitor>(relaxed = true)
        every { red.isConnected } returns MutableStateFlow(true)
        every { red.isServerReachable } returns MutableStateFlow(true)
        val cajon = mockk<CashDrawerRepository>(relaxed = true)
        coEvery { cajon.sincronizarCajonPrimero() } returns true
        coEvery { dao.getPendingCount() } returns flowOf(payments.size)
        coEvery { dao.getFailedCount() } returns flowOf(0)
        coEvery { dao.getPendingPayments(any()) } returns payments
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(0, 0, emptySet(), 0)
        return PaymentSyncService(dao, storage, mockk<OkHttpClient>(relaxed = true), red, cajon, outbox, io.mockk.mockk<com.avoqado.pos.payment.data.CashPaymentRepository>(relaxed = true)).also {
            it.usarContextoDeSincronizacion(StandardTestDispatcher(testScheduler))
        }
    }
    private fun TestScope.arrancar(service: PaymentSyncService) {
        service.start()
        runCurrent()
        coVerify(exactly = 1) { dao.getPendingPayments(any()) }
    }

    @Test fun `PENDING o HELD de la orden impide empezar el cobro`() = runTest {
        val service = servicio(listOf(cobro("p1", "o1")))
        coEvery { outbox.trabajoSinResolverDeLaOrden("FULLTEST-v1", setOf("o1")) } returns TrabajoSinResolver(1, 0, setOf("ADD_ITEMS"), 0)
        try {
            arrancar(service)
            coVerify(exactly = 0) { dao.updateStatus("p1", PaymentSyncStatus.SYNCING.name) }
        } finally { service.stop() }
    }
    @Test fun `el rechazo pendiente del gerente no detiene efectivo ya capturado`() = runTest {
        val service = servicio(listOf(cobro("p1", "o1")))
        coEvery { outbox.trabajoSinResolverDeLaOrden("FULLTEST-v1", setOf("o1")) } returns TrabajoSinResolver(1, 1, setOf("APPLY_DISCOUNT"), 0)
        try {
            arrancar(service)
            coVerify(exactly = 1) { dao.updateStatus("p1", PaymentSyncStatus.SYNCING.name) }
        } finally { service.stop() }
    }
    @Test fun `fallo de lectura conserva cobro pendiente sin iniciar envio`() = runTest {
        val service = servicio(listOf(cobro("p1", "o1")))
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } throws IllegalStateException("disco")
        try {
            arrancar(service)
            coVerify(exactly = 0) { dao.updateStatus("p1", PaymentSyncStatus.SYNCING.name) }
        } finally { service.stop() }
    }
    @Test fun `FIFO no adelanta la segunda orden tras la primera retenida`() = runTest {
        val service = servicio(listOf(cobro("p1", "o1"), cobro("p2", "o2")))
        coEvery { outbox.trabajoSinResolverDeLaOrden("FULLTEST-v1", setOf("o1")) } returns TrabajoSinResolver(1, 0, setOf("ADD_ITEMS"), 0)
        try {
            arrancar(service)
            coVerify(exactly = 0) { dao.updateStatus(any(), PaymentSyncStatus.SYNCING.name) }
        } finally { service.stop() }
    }
    @Test fun `la sucursal del cobro manda aunque la seleccionada sea otra`() = runTest {
        val service = servicio(listOf(cobro("p1", "o1", "FULLTEST-otra")))
        coEvery { outbox.trabajoSinResolverDeLaOrden("FULLTEST-otra", setOf("o1")) } returns TrabajoSinResolver(1, 0, setOf("ADD_ITEMS"), 0)
        try {
            arrancar(service)
            coVerify(exactly = 0) { dao.updateStatus("p1", PaymentSyncStatus.SYNCING.name) }
        } finally { service.stop() }
    }
    @Test fun `sin trabajo de la orden sale aunque existan otras ordenes pendientes`() = runTest {
        val service = servicio(listOf(cobro("p2", "o2")))
        coEvery { outbox.trabajoSinResolverDeLaOrden("FULLTEST-v1", setOf("o1")) } returns TrabajoSinResolver(1, 0, setOf("ADD_ITEMS"), 0)
        try {
            arrancar(service)
            coVerify(exactly = 1) { dao.updateStatus("p2", PaymentSyncStatus.SYNCING.name) }
        } finally { service.stop() }
    }
    @Test fun `FAST sin orden sale sin consultar outbox`() = runTest {
        val service = servicio(listOf(cobro("fast", null)))
        try {
            arrancar(service)
            coVerify(exactly = 1) { dao.updateStatus("fast", PaymentSyncStatus.SYNCING.name) }
            coVerify(exactly = 0) { outbox.trabajoSinResolverDeLaOrden(any(), any()) }
        } finally { service.stop() }
    }
    @Test fun legacyWithoutCaptureIsHeldBeforeOrderQuery() = runTest {
        val legacy = cobro("legacy", "o1").copy(q15CaptureJson = null)
        val service = servicio(listOf(legacy))
        coEvery { dao.retenerPagoQ15("FULLTEST-v1", "legacy", ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA, any()) } returns 1
        try {
            arrancar(service)
            coVerify(exactly = 1) { dao.retenerPagoQ15("FULLTEST-v1", "legacy", ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA, any()) }
            coVerify(exactly = 0) { dao.updateStatus(any(), PaymentSyncStatus.SYNCING.name) }
            coVerify(exactly = 0) { outbox.trabajoSinResolverDeLaOrden(any(), any()) }
        } finally { service.stop() }
    }

}
