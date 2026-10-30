package com.avoqado.pos.sync

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.data.local.database.Q15_CANCELLED_ZERO_PAYMENT_REVIEW_SQL
import com.avoqado.pos.payment.Q16PrecioCapturadoTest
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import com.avoqado.pos.sync.data.SalesNoticeStore
import com.avoqado.pos.sync.presentation.SalesNoticesViewModel
import com.avoqado.pos.sync.presentation.CourtesyReviewKind
import com.avoqado.pos.sync.presentation.courtesyReviewCandidate
import com.avoqado.pos.tables.data.TableSession
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** VM y servicio reales. El writer produce la fuente/captura en SQLite; la proyección usa el DAO SQL real.
 * El adaptador JDBC existente no acredita la generación Room, que conserva su familia propia. */
@OptIn(ExperimentalCoroutinesApi::class)
class SalesNoticesCancelledRoutingTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)

    private class Fixture : AutoCloseable {
        val financial = Q16PrecioCapturadoTest.Fixture()
        val projection = SalesNoticeSqlTest.Fixture()
        val paths = CopyOnWriteArrayList<String>()
        val changes = MutableSharedFlow<Int>(extraBufferCapacity = 1)
        val session = mockk<TableSession>()
        var epoch = 1L
        @Volatile var hasPayments = false
        @Volatile var financialPreview: String? = null
        @Volatile var onRecovery: (() -> Unit)? = null
        val store = mockk<SalesNoticeStore>()
        val subject: SalesNoticesViewModel
        init {
            every { financial.storage.userId } returns ACTOR
            every { financial.storage.userRole } returns "MANAGER"
            every { financial.storage.venuePermissions } returns listOf("orders:read")
            every { financial.network.isFullyConnected } returns true
            every { financial.outbox.contextoDeSesionQ15() } answers { epoch }
            every { session.contextoActual() } answers { epoch }
            coEvery { store.page(any(), any(), any()) } coAnswers {
                if (financial.query("SELECT id FROM pending_payments WHERE id=:id", mapOf("id" to KEY)).isNotEmpty()) copyProjection()
                projection.dao.page(firstArg(), secondArg(), thirdArg(), emptySet())
            }
            coEvery { store.summary(any()) } coAnswers { projection.dao.summary(firstArg(), emptySet()) }
            every { store.changes() } returns changes
            subject = SalesNoticesViewModel(store, financial.storage, RoleManager(financial.storage), session,
                financial.service, financial.network)
        }
        suspend fun produce() {
            val request = CreateOrderRequest(items = listOf(OrderItemRequest(productId = PRODUCT, name = "Artículo",
                quantity = 1, unitPrice = 11000, externalId = LINE, isCortesia = true, cortesiaReason = "Invitación")),
                subtotal = 0, total = 0, paymentMethod = "CASH")
            CashPaymentRepository(financial.storage, financial.dao).queueCashPayment(request, ACTOR, 0, 0, null,
                orderExternalId = KEY, idempotencyKey = KEY, amountCents = 0, tipCents = 0,
                venueId = Q16PrecioCapturadoTest.VENUE)
            financial.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.endsWith("/orders") -> MockResponse().setResponseCode(201).setBody(
                            """{"success":true,"data":{"id":"$ORDER","total":0,"items":[{"externalId":"$LINE","productId":"$PRODUCT","quantity":1,"isCortesia":true,"cortesiaReason":"Invitación"}]}}""")
                        path.endsWith("/pay") -> MockResponse().setResponseCode(400).setBody("""{"message":"rechazo original"}""")
                        else -> MockResponse().setResponseCode(500).setBody("{}")
                    }
                }
            }
            financial.service.usarContextoDeSincronizacion(Dispatchers.Unconfined)
            financial.service.start(); financial.service.esperarSincronizacionActualQ16(); financial.service.stop()
            val original = financial.row(KEY)
            assertEquals("prerrequisito: rechazo del drainer real", "FAILED", original["syncStatus"])
            assertEquals(2, financial.server.requestCount)
            copyProjection()
            financial.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty(); paths += path
                    return if (path.endsWith("/sync/recovery")) {
                        val body = JSONObject(request.body.readUtf8())
                        assertEquals(true, body.opt("includeCancelledPaymentEvidence"))
                        assertEquals(KEY, body.getJSONArray("externalOrderIds").getString(0))
                        onRecovery?.invoke()
                        MockResponse().setResponseCode(200).setBody("""{"data":{"orders":[{"orderId":"$ORDER","externalId":"$KEY","cancelledPaymentEvidence":{"version":1,"venueId":"${Q16PrecioCapturadoTest.VENUE}","orderId":"$ORDER","externalId":"$KEY","orderStatus":"CANCELLED","hasPayments":$hasPayments,"hasTerminalRequests":false,"verifiedAt":"2026-10-28T18:00:00.000Z"}}]}}""")
                    } else if (path.endsWith("/courtesy-review") && financialPreview != null) {
                        MockResponse().setResponseCode(200).setBody(requireNotNull(financialPreview))
                    } else MockResponse().setResponseCode(409).setBody("""{"message":"La cuenta cambió"}""")
                }
            }
        }
        // Copia de lectura de la fuente PRODUCIDA/actual; nunca fabrica captura ni cuerpo desde un resultado.
        fun copyProjection() {
            val row = financial.decode(financial.row(KEY))
            projection.sql("DELETE FROM pending_payments WHERE id='$KEY'")
            projection.payment(KEY, Q16PrecioCapturadoTest.VENUE, mapOf("staffId" to row.staffId,
                "amountCents" to row.amountCents, "tipCents" to row.tipCents, "paymentType" to row.paymentType,
                "method" to row.method, "syncStatus" to row.syncStatus, "orderId" to row.orderId, "orderRequestJson" to row.orderRequestJson,
                "q15CaptureJson" to row.q15CaptureJson, "capturedOrderRef" to row.capturedOrderRef,
                "q15InitialResultJson" to row.q15InitialResultJson, "q15ResultJson" to row.q15ResultJson,
                "q15Reason" to row.q15Reason, "q15ReviewedAt" to row.q15ReviewedAt,
                "q15ReviewedBy" to row.q15ReviewedBy, "q15ReviewRole" to row.q15ReviewRole,
                "lastError" to row.lastError, "retryCount" to row.retryCount, "createdAt" to row.createdAt,
                "splitType" to row.splitType, "customerId" to row.customerId,
                "cashTenderedCents" to row.cashTenderedCents, "changeCents" to row.changeCents))
        }
        fun holdAfterRealCAS(entered: CompletableDeferred<Unit>, release: CompletableDeferred<Unit>) {
            coEvery { financial.dao.revisarCanceladoSinCobroQ15(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any()) } coAnswers {
                // Mismo SQL y enlaces del adaptador Q16 existente; la pausa ocurre DESPUÉS de la escritura real.
                val saved = financial.sql(Q15_CANCELLED_ZERO_PAYMENT_REVIEW_SQL,mapOf("venueId" to firstArg<String>(),"id" to secondArg<String>(),
                    "originalOrderId" to arg<String?>(2),"originalOrderRequestJson" to arg<String?>(3),"originalResultJson" to arg<String?>(4),
                    "originalSyncStatus" to arg<String>(5),"originalCaptureJson" to arg<String?>(6),"originalCreatedAt" to arg<Long>(7),
                    "originalCapturedOrderRef" to arg<String?>(8),"originalSplitType" to arg<String>(9),"originalCustomerId" to arg<String?>(10),
                    "originalCashTenderedCents" to arg<Int?>(11),"originalChangeCents" to arg<Int?>(12),"originalReason" to arg<String?>(13),
                    "resultadoJson" to arg<String>(14),"actor" to arg<String>(15),"rol" to arg<String>(16),"ahora" to arg<Long>(17)))
                entered.complete(Unit); changes.emit(1); release.await(); saved
            }
        }
        override fun close() { subject.viewModelScope.cancel(); financial.close(); projection.close() }
    }

    private suspend fun TestScope.settle(vm: SalesNoticesViewModel) {
        repeat(600) {
            runCurrent()
            if (!vm.loading.value && !vm.courtesy.value.busy) return
            withContext(Dispatchers.Default) { delay(5) }
        }
        fail("El VM no terminó su consulta acotada")
    }

    private fun exercise(directed: Boolean) = runTest {
        Fixture().use { f ->
            f.produce()
            val before = f.financial.row(KEY); val projectionBefore = f.projection.economic()
            // Control test-only: la rama existente debe demostrar la prueba positiva ANTES de juzgar al caller.
            val control = f.financial.service.retryFailedPayment(KEY)
            assertTrue("prerrequisito: ESTE writer y respuesta producen revisión estricta, sin reenviar",
                control is PaymentSyncService.RetryOutcome.CancelledZeroPaymentReview)
            val positive = (control as PaymentSyncService.RetryOutcome.CancelledZeroPaymentReview).propuesta
            assertEquals(ORDER, positive.ordenAcreditada)
            assertEquals(before, f.financial.row(KEY))
            assertEquals(projectionBefore, f.projection.economic())
            assertEquals(listOf("/api/v1/mobile/venues/${Q16PrecioCapturadoTest.VENUE}/sync/recovery"), f.paths.toList())
            coVerify(exactly = 0) { f.financial.dao.retryFailed(any()) }
            coVerify(exactly = 0) { f.store.acknowledge(any(), any(), any()) }
            f.paths.clear()
            f.subject.refresh(); settle(f.subject)
            val row = f.subject.rows.value.single { it.id == KEY }
            assertTrue("prerrequisito: la tarjeta admite el intento producido", courtesyReviewCandidate(row))
            if (directed) f.subject.openCourtesyPayment(KEY) else {
                f.subject.select(row); f.subject.reviewSelectedCourtesy()
            }
            settle(f.subject)
            assertEquals(CourtesyReviewKind.CANCELLED_ZERO, f.subject.courtesy.value.kind)
            assertTrue(f.subject.canConfirmCancelledReview)
            assertFalse(f.subject.canConfirmCourtesy)
            assertTrue(f.subject.courtesy.value.ready)
            assertEquals("RED: el acceso primario consulta cancelación, nunca el preview financiero",
                listOf("/api/v1/mobile/venues/${Q16PrecioCapturadoTest.VENUE}/sync/recovery"), f.paths.toList())
            assertEquals(before, f.financial.row(KEY))
            assertEquals(projectionBefore, f.projection.economic())
            coVerify(exactly = 0) { f.financial.dao.retryFailed(any()) }
            coVerify(exactly = 0) { f.store.acknowledge(any(), any(), any()) }
            f.subject.closeCourtesy(); runCurrent()
            assertNull(f.subject.courtesy.value.key)
            assertEquals(1, f.paths.size)
            assertEquals(before, f.financial.row(KEY))
        }
    }
    @Test fun `tarjeta primaria consulta cancelacion estricta sin preview financiero ni escritura`() = exercise(false)
    @Test fun `ruta dirigida exacta usa la misma consulta cancelada y cerrar no confirma`() = exercise(true)

    private suspend fun TestScope.openCancelled(f: Fixture): Map<String, Any?> {
        f.produce()
        val before = f.financial.row(KEY)
        f.subject.refresh(); settle(f.subject)
        f.subject.select(f.subject.rows.value.single { it.id == KEY })
        f.subject.reviewSelectedCourtesy(); settle(f.subject)
        assertEquals(CourtesyReviewKind.CANCELLED_ZERO, f.subject.courtesy.value.kind)
        assertTrue(f.subject.courtesy.value.ready)
        return before
    }

    private suspend fun TestScope.finished(job: Job) {
        repeat(600) {
            runCurrent()
            if (job.isCompleted) return
            withContext(Dispatchers.Default) { delay(5) }
        }
        fail("El dueño cancelado no terminó al liberar su respuesta HTTP")
    }

    @Test fun `confirmacion fresca sin comp escribe una sola revision y tolera notificacion de su propio CAS`() = runTest {
        Fixture().use { f ->
            val before = openCancelled(f)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.holdAfterRealCAS(entered, release)
            backgroundScope.launch { f.subject.observeChanges() }; runCurrent()
            f.subject.confirmSelectedCancelledReview(); f.subject.confirmSelectedCancelledReview(); runCurrent()
            try {
                for (attempt in 0 until 600) {
                    runCurrent()
                    if (entered.isCompleted && f.subject.selected.value == null) break
                    withContext(Dispatchers.Default) { delay(5) }
                }
                assertTrue("prerrequisito: SQL real llegó a su CAS", entered.isCompleted)
                assertNotNull(f.financial.row(KEY)["q15ReviewedAt"])
                assertNull("la proyección ya retiró la fila durante confirmación", f.subject.selected.value)
                assertTrue("el observador conserva el await del dueño", f.subject.courtesy.value.confirming)
            } finally { release.complete(Unit) }
            settle(f.subject)
            assertNull(f.subject.courtesy.value.key)
            assertEquals("Revisión guardada en el historial.", f.subject.message.value)
            assertEquals(2, f.paths.size); assertTrue(f.paths.all { it.endsWith("/sync/recovery") })
            val after = f.financial.row(KEY)
            for (key in before.keys - setOf("q15ResultJson", "q15Reason", "q15DetectedAt", "q15ReviewedAt", "q15ReviewedBy", "q15ReviewRole")) {
                assertEquals(key, before[key], after[key])
            }
            assertEquals("la detección inicial se conserva o nace con esta revisión",
                before["q15DetectedAt"] ?: after["q15ReviewedAt"], after["q15DetectedAt"])
            assertEquals("CANCELLED_ZERO_PAYMENT", after["q15Reason"])
            assertEquals(ACTOR, after["q15ReviewedBy"])
            coVerify(exactly = 1) { f.financial.dao.revisarCanceladoSinCobroQ15(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any()) }
            coVerify(exactly = 0) { f.financial.dao.retryFailed(any()) }
            coVerify(exactly = 0) { f.store.acknowledge(any(), any(), any()) }
        }
    }

    @Test fun `pago nuevo en confirmacion fresca conserva fuente sin revision ni reenvio`() = runTest {
        Fixture().use { f ->
            val before = openCancelled(f); f.hasPayments = true
            f.subject.confirmSelectedCancelledReview(); settle(f.subject)
            assertEquals(before, f.financial.row(KEY))
            assertFalse(f.subject.canConfirmCancelledReview); assertNotNull(f.subject.courtesy.value.error)
            assertEquals(2, f.paths.size); assertTrue(f.paths.all { it.endsWith("/sync/recovery") })
            coVerify(exactly = 0) { f.financial.dao.retryFailed(any()) }
            coVerify(exactly = 0) { f.store.acknowledge(any(), any(), any()) }
        }
    }

    private fun latePreparation(confirming: Boolean = false, change: (Fixture) -> Unit) = runTest {
        Fixture().use { f ->
            val before = if (confirming) openCancelled(f) else { f.produce(); f.financial.row(KEY) }
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.onRecovery = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            f.subject.refresh(); settle(f.subject); f.subject.select(f.subject.rows.value.single { it.id == KEY })
            val previous = f.subject.viewModelScope.coroutineContext.job.children.toSet()
            if (confirming) f.subject.confirmSelectedCancelledReview() else f.subject.reviewSelectedCourtesy()
            runCurrent()
            val operation = f.subject.viewModelScope.coroutineContext.job.children.single { it !in previous }
            try {
                assertTrue("prerrequisito: preparación suspendida en HTTP real",
                    withContext(Dispatchers.Default) { entered.await(3, TimeUnit.SECONDS) })
                change(f)
            } finally { release.countDown() }
            finished(operation); runCurrent()
            assertNull(f.subject.courtesy.value.key)
            assertEquals(before, f.financial.row(KEY))
            assertEquals(if (confirming) 2 else 1, f.paths.size); assertTrue(f.paths.all { it.endsWith("/sync/recovery") })
            coVerify(exactly = 0) { f.financial.dao.retryFailed(any()) }
            coVerify(exactly = 0) { f.store.acknowledge(any(), any(), any()) }
        }
    }
    @Test fun `cambiar seleccion descarta propuesta tardia`() = latePreparation { it.subject.select(null) }
    @Test fun `salir de pantalla descarta propuesta tardia`() = latePreparation { it.subject.leaveCourtesyView() }
    @Test fun `cambiar actor descarta propuesta tardia`() = latePreparation {
        every { it.financial.storage.userId } returns "otro-actor"
    }
    @Test fun `cambiar rol descarta propuesta tardia`() = latePreparation {
        every { it.financial.storage.userRole } returns "CASHIER"
    }
    @Test fun `cambiar venue descarta propuesta tardia`() = latePreparation {
        every { it.financial.storage.venueId } returns "otro-venue"
    }
    @Test fun `cambiar epoch descarta propuesta tardia`() = latePreparation { it.epoch++ }
    @Test fun `revocar lectura descarta propuesta tardia`() = latePreparation {
        every { it.financial.storage.venuePermissions } returns listOf("orders:comp")
    }
    @Test fun `cambiar actor durante confirmacion conserva intento`() = latePreparation(true) {
        every { it.financial.storage.userId } returns "otro-actor"
    }
    @Test fun `cambiar rol durante confirmacion conserva intento`() = latePreparation(true) {
        every { it.financial.storage.userRole } returns "CASHIER"
    }
    @Test fun `cambiar venue durante confirmacion conserva intento`() = latePreparation(true) {
        every { it.financial.storage.venueId } returns "otro-venue"
    }
    @Test fun `cambiar epoch durante confirmacion conserva intento`() = latePreparation(true) { it.epoch++ }
    @Test fun `salir durante confirmacion conserva intento sin efecto tardio`() = latePreparation(true) { it.subject.leaveCourtesyView() }

    @Test fun `observacion de version nueva descarta propuesta sin confirmar ni escribir`() = runTest {
        Fixture().use { f ->
            openCancelled(f)
            f.financial.sql("UPDATE pending_payments SET lastError='otra causa' WHERE id=:id", mapOf("id" to KEY))
            val changed = f.financial.row(KEY)
            f.subject.refresh(); settle(f.subject)
            assertNull(f.subject.courtesy.value.key)
            f.subject.confirmSelectedCancelledReview(); runCurrent()
            assertEquals(changed, f.financial.row(KEY)); assertNull(changed["q15ReviewedAt"])
            assertEquals(1, f.paths.size)
        }
    }

    @Test fun `sin red no consulta ni reenvia y conserva fuente`() = runTest {
        Fixture().use { f ->
            f.produce(); val before = f.financial.row(KEY)
            every { f.financial.network.isFullyConnected } returns false
            f.subject.refresh(); settle(f.subject); f.subject.select(f.subject.rows.value.single { it.id == KEY })
            f.subject.reviewSelectedCourtesy(); runCurrent()
            assertNull(f.subject.courtesy.value.key); assertTrue(f.paths.isEmpty())
            assertEquals(before, f.financial.row(KEY))
        }
    }

    @Test fun `sin prueba cancelada conserva preview financiero real110 y exige comp para aplicarlo`() = runTest {
        Fixture().use { f ->
            f.produce(); val before = f.financial.row(KEY); f.hasPayments = true
            f.financialPreview = """{"success":true,"data":{"outcome":"PREVIEW","originalPaymentKey":"$KEY","snapshot":{"orderId":"$ORDER","externalId":"$KEY","orderNumber":"ORIGINAL-110","version":1,"status":"PENDING","paymentStatus":"PENDING","originSystem":"AVOQADO","contratoDePrecio":"CATALOG","tableId":null,"servedById":null,"subtotal":110,"discountAmount":0,"taxAmount":0,"serviceChargeAmount":0,"deliveryFeeAmount":0,"tipAmount":0,"total":110,"paidAmount":0,"remainingBalance":110,"lines":[{"id":"cjld2cjxh0045qzrmn831i7rn","externalId":"$LINE","productId":"$PRODUCT","quantity":1,"modifierIds":[],"isCortesia":false,"cortesiaReason":null,"unitPrice":110,"total":110,"discountAmount":0,"taxAmount":0,"appliedDiscountId":null,"modifiers":[]}]}}}"""
            f.subject.refresh(); settle(f.subject); f.subject.select(f.subject.rows.value.single { it.id == KEY })
            f.subject.reviewSelectedCourtesy(); settle(f.subject)
            assertEquals(CourtesyReviewKind.FINANCIAL, f.subject.courtesy.value.kind)
            assertTrue(f.subject.courtesy.value.ready)
            assertEquals(110, f.subject.courtesy.value.snapshot?.get("total")?.jsonPrimitive?.intOrNull)
            assertFalse(f.subject.canConfirmCourtesy)
            f.subject.confirmSelectedCourtesy(); runCurrent()
            assertEquals(before, f.financial.row(KEY))
            assertEquals(2, f.paths.size)
            assertTrue(f.paths[0].endsWith("/sync/recovery")); assertTrue(f.paths[1].endsWith("/courtesy-review"))
            coVerify(exactly = 0) { f.financial.dao.retryFailed(any()) }
            coVerify(exactly = 0) { f.store.acknowledge(any(), any(), any()) }
        }
    }

    companion object {
        private const val KEY = "cjld2cjxh0040qzrmn831i7rn"
        private const val ACTOR = "cjld2cjxh0041qzrmn831i7rn"
        private const val PRODUCT = "cjld2cjxh0042qzrmn831i7rn"
        private const val LINE = "cjld2cjxh0043qzrmn831i7rn"
        private const val ORDER = "cjld2cjxh0044qzrmn831i7rn"
    }
}
