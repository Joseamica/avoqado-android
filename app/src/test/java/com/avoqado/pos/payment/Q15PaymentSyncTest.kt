package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.*
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.*
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class Q15PaymentSyncTest {
    @get:Rule val main = MainDispatcherRule()
    private val dao = mockk<PendingPaymentDao>(relaxed=true)
    private val client = mockk<OkHttpClient>(relaxed=true)
    private val storage = mockk<SecureStorage>(relaxed=true)
    private val outbox = mockk<SyncOutbox>(relaxed=true)
    private fun TestScope.service(): PaymentSyncService {
        coEvery { dao.registrarResultadoQ15(any(),any(),any(),any(),any(),any()) } returns 1
        coEvery { dao.retenerPagoQ15(any(),any(),any(),any()) } returns 1
        every { storage.venueId } returns "FULLTEST-B"
        val red = mockk<ConnectivityMonitor>(relaxed=true)
        every { red.isConnected } returns MutableStateFlow(true)
        every { red.isServerReachable } returns MutableStateFlow(true)
        val drawer=mockk<CashDrawerRepository>(relaxed=true)
        coEvery { drawer.sincronizarCajonPrimero() } returns true
        every { dao.getPendingCount() } returns flowOf(0); every { dao.getFailedCount() } returns flowOf(0)
        return PaymentSyncService(dao,storage,client,red,drawer,outbox).also { it.usarContextoDeSincronizacion(StandardTestDispatcher(testScheduler)) }
    }
    private fun captured() = PendingPaymentEntity("p1","FULLTEST-A","staff",10005,500,"CASH","ORDER",orderId="o1",
        q15CaptureJson=ConciliacionDelCobro.guardarCaptura(CapturaDelCobro("FULLTEST-A","p1","o1",10005,500)),capturedOrderRef="o1")
    @Test fun resultado_distinto_se_guarda_en_el_pago_original_aplicado_sin_reintento() = runTest {
        val stop = service().handleSyncResult(captured(),200,"""{"success":true,"payment":{"paymentId":"sp1","orderId":"o1","amount":9505,"tipAmount":1000,"status":"COMPLETED"}}""")
        assertFalse(stop)
        coVerify(exactly=1) { dao.registrarResultadoQ15("FULLTEST-A","p1",true,ConciliacionDelCobro.DIFERENCIA,any(),any()) }
        coVerify(exactly=0) { dao.incrementRetry(any(),any()) }
        coVerify(exactly=0) { dao.updateStatus("p1","SYNCED") }
    }
    @Test fun dos_xx_identificado_sin_importe_no_se_reproduce_y_deja_aviso() = runTest {
        assertFalse(service().handleSyncResult(captured(),200,"""{"success":true,"payment":{"paymentId":"sp1","orderId":"o1","tipAmount":500,"status":"COMPLETED"}}"""))
        coVerify(exactly=1) { dao.registrarResultadoQ15("FULLTEST-A","p1",true,ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,any(),any()) }
    }
    @Test fun dos_xx_sin_identidad_se_retiene_y_corta_fifo() = runTest {
        assertTrue(service().handleSyncResult(captured(),200,"{}"))
        coVerify(exactly=1) { dao.registrarResultadoQ15("FULLTEST-A","p1",false,ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,null,any()) }
        coVerify(exactly=0) { dao.incrementRetry(any(),any()) }
    }
    @Test fun historico_sin_evidencia_se_retiene_antes_crear_orden_o_post_y_no_adelanta_fifo() = runTest {
        val legacy = captured().copy(q15CaptureJson=null,orderId=null,orderRequestJson="{\"items\":[{\"productId\":\"sku1\"}]}")
        coEvery { dao.getPendingPayments(any()) } returns listOf(legacy,captured().copy(id="p2"))
        val subject=service()
        try {
            subject.start(); runCurrent()
            coVerify(exactly=1) { dao.retenerPagoQ15("FULLTEST-A","p1",ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA,any()) }
            verify(exactly=0) { client.newCall(any()) }
            coVerify(exactly=0) { dao.updateStatus(any(),"SYNCING") }
        } finally { subject.stop() }
    }
    @Test fun price_proof_pending_no_sale_por_reconexion_o_retry() = runTest {
        val payment=captured().copy(q15Reason=ConciliacionDelCobro.PRICE_PROOF_PENDING)
        coEvery { dao.getPendingPayments(any()) } returns listOf(payment)
        val subject=service()
        try {
            subject.start(); runCurrent(); subject.syncNow(); runCurrent()
            verify(exactly=0) { client.newCall(any()) }
            coVerify(exactly=0) { dao.updateStatus(any(),"SYNCING") }
        } finally { subject.stop() }
    }
    @Test fun fila_ausente_no_canta_pago_aplicado() = runTest {
        coEvery { dao.syncStatusOf("missing") } returns null
        val outcome=service().retryFailedPayment("missing")
        assertEquals(PaymentSyncService.RetryOutcome.StillQueued,outcome)
    }
    @Test fun fallo_al_guardar_resultado_no_gasta_retries_ni_avanza_fifo() = runTest {
        val subject=service()
        coEvery { dao.registrarResultadoQ15(any(),any(),any(),any(),any(),any()) } throws java.io.IOException("disco de prueba")
        assertTrue(subject.handleSyncResult(captured(),200,"""{"success":true,"payment":{"paymentId":"sp1","orderId":"o1","amount":10005,"tipAmount":500,"status":"COMPLETED"}}"""))
        // Codex 2.ª pasada (N3): el respaldo deja la fila PENDIENTE con su marca en la MISMA escritura (la de resultado),
        // no sólo la marca con la fila en SYNCING (la vuelta siguiente no la vería y su orden se cobraría).
        coVerify(exactly=1) { dao.registrarResultadoQ15("FULLTEST-A","p1",false,ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,null,any()) }
        coVerify(exactly=0) { dao.retenerPagoQ15(any(),any(),any(),any()) }
        coVerify(exactly=0) { dao.incrementRetry(any(),any()) }
        coVerify(exactly=0) { dao.updateStatus("p1","FAILED") }
    }

}
