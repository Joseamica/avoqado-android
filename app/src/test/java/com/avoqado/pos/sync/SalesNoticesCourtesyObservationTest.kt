package com.avoqado.pos.sync

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.payment.data.CapturaDelCobro
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.sync.data.*
import com.avoqado.pos.sync.presentation.SalesNoticesViewModel
import com.avoqado.pos.tables.data.TableSession
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** VM real con proyección SQLite existente y servicio como borde suspendible.
 * Prueba quién cancela el await; NO acredita CAS/HTTP/syncNow ni Room/finanzas reales.
 * La unidad de continuación del servicio acredita su despertar por separado. */
@OptIn(ExperimentalCoroutinesApi::class)
class SalesNoticesCourtesyObservationTest {
    @get:Rule val main=MainDispatcherRule()

    private class Fixture(permissions:List<String> = listOf("orders:read","orders:comp")):AutoCloseable {
        val disk=SalesNoticeSqlTest.Fixture()
        val store=mockk<SalesNoticeStore>()
        val payments=mockk<PaymentSyncService>()
        val changes=MutableSharedFlow<Int>(extraBufferCapacity=1)
        val body="""{"externalId":"$KEY","items":[{"externalId":"$LINE","productId":"$PRODUCT","quantity":1,"isCortesia":true,"cortesiaReason":"Cortesía original"}]}"""
        val capture=ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(VENUE,KEY,amountCents=0,tipCents=0,
            method="CASH",orderRequestJson=body,primeraCapturaEn=1000))
        val original=PendingPaymentEntity(KEY,VENUE,ACTOR,0,0,"CASH","ORDER",orderRequestJson=body,
            syncStatus="FAILED",createdAt=1000,q15CaptureJson=capture,lastError="Cortesía no confirmada")
        val proposal=PaymentSyncService.PropuestaRevisionCortesia(original,ACTOR,"MANAGER",1,
            Json.parseToJsonElement("""{"orderId":"$ORDER","orderNumber":"ORIGINAL-110","total":110,"lines":[{"externalId":"$LINE","productId":"$PRODUCT","quantity":1}]}""").jsonObject)
        val subject:SalesNoticesViewModel
        init {
            disk.payment(KEY,VENUE,mapOf("staffId" to ACTOR,"amountCents" to 0,"paymentType" to "ORDER","orderRequestJson" to body,
                "q15CaptureJson" to capture,"lastError" to original.lastError))
            val storage=mockk<SecureStorage>(relaxed=true)
            every { storage.venueId } returns VENUE;every { storage.userId } returns ACTOR
            every { storage.userRole } returns "MANAGER";every { storage.venuePermissions } returns permissions
            val session=mockk<TableSession>();every { session.contextoActual() } returns 1L
            val network=mockk<ConnectivityMonitor>()
            every { network.isConnected } returns MutableStateFlow(true)
            every { network.isServerReachable } returns MutableStateFlow(true)
            every { network.isFullyConnected } returns true
            coEvery { store.page(any(),any(),any()) } coAnswers { disk.dao.page(VENUE,secondArg(),thirdArg(),emptySet()) }
            coEvery { store.summary(any()) } coAnswers { disk.dao.summary(VENUE,emptySet()) }
            every { store.changes() } returns changes
            coEvery { payments.prepararRevisionCortesia(KEY) } returns proposal
            coEvery { payments.prepararRevisionCancelada(KEY) } returns null
            subject=SalesNoticesViewModel(store,storage,RoleManager(storage),session,payments,network)
        }
        override fun close() { subject.viewModelScope.cancel();disk.close() }
    }

    @Test fun `observacion del propio CAS conserva confirmacion hasta retorno y descarta resultado visual obsoleto`()=runTest {
        Fixture().use { h ->
            val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val exited=CompletableDeferred<Unit>();var delegateReturned=false
            coEvery { h.payments.confirmarRevisionCortesia(h.proposal) } coAnswers {
                entered.complete(Unit)
                try { release.await();delegateReturned=true;true }
                finally { exited.complete(Unit) }
            }
            backgroundScope.launch { h.subject.observeChanges() };runCurrent()
            h.subject.refresh();runCurrent();h.subject.select(h.subject.rows.value.single())
            h.subject.reviewSelectedCourtesy();runCurrent();assertTrue(h.subject.courtesy.value.ready)
            val oldVersion=h.subject.selected.value!!.version
            h.subject.confirmSelectedCourtesy();runCurrent();assertTrue(entered.isCompleted)
            assertTrue(h.subject.courtesy.value.confirming)
            // El borde delegado sigue suspendido después de su CAS; el observador ve la nueva proyección local.
            h.disk.sql("UPDATE pending_payments SET syncStatus='PENDING',lastError=NULL,retryCount=0,lastRetryAt=NULL WHERE id='$KEY'")
            h.changes.emit(1);runCurrent()
            assertEquals("PENDING",h.subject.selected.value?.state)
            assertNotEquals(oldVersion,h.subject.selected.value?.version)
            assertFalse("El observador no debe cancelar el await de confirmar antes de su retorno",exited.isCompleted)
            assertTrue(h.subject.courtesy.value.confirming)
            release.complete(Unit);runCurrent()
            assertTrue("La llamada delegada alcanza su retorno sin cancelación del VM",delegateReturned)
            assertTrue(exited.isCompleted)
            assertNull("La foto seleccionada ya cambió: no se presenta como un nuevo éxito",h.subject.courtesy.value.key)
            assertFalse(h.subject.message.value?.contains("Cortesía aplicada")==true)
            coVerify(exactly=1) { h.payments.confirmarRevisionCortesia(h.proposal) }
            coVerify(exactly=0) { h.store.acknowledge(any(),any(),any()) }
        }
    }

    @Test fun `permiso de lectura permite preview y nunca habilita confirmacion sin comp`()=runTest {
        Fixture(listOf("orders:read")).use { h ->
            h.subject.refresh();runCurrent();h.subject.select(h.subject.rows.value.single())
            assertTrue(h.subject.canPreviewCourtesy);assertFalse(h.subject.canConfirmCourtesy)
            h.subject.reviewSelectedCourtesy();runCurrent();assertTrue(h.subject.courtesy.value.ready)
            h.subject.confirmSelectedCourtesy();runCurrent()
            coVerify(exactly=1) { h.payments.prepararRevisionCortesia(KEY) }
            coVerify(exactly=0) { h.payments.confirmarRevisionCortesia(any()) }
            coVerify(exactly=0) { h.store.acknowledge(any(),any(),any()) }
        }
    }

    companion object {
        private const val KEY="cjld2cjxh0030qzrmn831i7rn"
        private const val VENUE="cjld2cjxh0031qzrmn831i7rn"
        private const val ACTOR="cjld2cjxh0032qzrmn831i7rn"
        private const val LINE="cjld2cjxh0033qzrmn831i7rn"
        private const val PRODUCT="cjld2cjxh0034qzrmn831i7rn"
        private const val ORDER="cjld2cjxh0035qzrmn831i7rn"
    }
}
