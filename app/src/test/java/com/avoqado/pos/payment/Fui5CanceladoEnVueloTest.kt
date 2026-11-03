package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.database.*
import com.avoqado.pos.payment.data.CapturaDelCobro
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.ConciliacionDelCobro
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import java.util.concurrent.atomic.AtomicInteger

/** FUI5 is a review of retained evidence; it must never replay or manufacture a payment. */
@OptIn(ExperimentalCoroutinesApi::class)
class Fui5CanceladoEnVueloTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)

    private val key = "fui5-folio-original"
    private val body = """{"externalId":"fui5-venta-original","items":[{"productId":"articulo-A","quantity":2}],"total":0,"tip":0}"""
    private fun original(id: String = key, orderId: String? = null): PendingPaymentEntity = PendingPaymentEntity(
        id, Q16PrecioCapturadoTest.VENUE, "staff-original", 0, 0, "CASH", "ORDER",
        orderId = orderId, orderRequestJson = body, syncStatus = "EN_VUELO",
        q15Reason = ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,
        lastError = "error-original", capturedOrderRef = orderId ?: "fui5-venta-original",
        q15CaptureJson = ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(
            Q16PrecioCapturadoTest.VENUE, id, orderId, 0, 0, splitType = "FULLPAYMENT", method = "CASH", orderRequestJson = body,
        )),
    )
    private fun response(orderId: String = "orden-cancelada", payment: Boolean = false, terminal: Boolean = false, external: String = "fui5-venta-original") =
        """{"data":{"orders":[{"externalId":"$external","orderId":"$orderId","cancelledPaymentEvidence":{"version":1,"venueId":"${Q16PrecioCapturadoTest.VENUE}","orderId":"$orderId","externalId":"$external","orderStatus":"CANCELLED","hasPayments":$payment,"hasTerminalRequests":$terminal,"verifiedAt":"2026-10-24T12:00:00.000Z"}}]}}"""
    private fun reviewed(row: PendingPaymentEntity = original(), reply: String = response()): PendingPaymentEntity = row.copy(
        q15Reason = RevisionCanceladoCero.MOTIVO, q15ReviewedAt = 10, q15ReviewedBy = "gerente-actual",
        q15ReviewRole = "MANAGER", q15ResultJson = JSONObject(reply).getJSONObject("data")
            .getJSONArray("orders").getJSONObject(0).let { JSONObject().put("cancelledPaymentEvidence", it.getJSONObject("cancelledPaymentEvidence")).toString() },
    )
    private fun Q16PrecioCapturadoTest.Fixture.prepare(row: PendingPaymentEntity = original()) {
        every { storage.userRole } returns "MANAGER"
        every { storage.userId } returns "gerente-actual"
        insertCaptured(row)
    }
    private fun Q16PrecioCapturadoTest.Fixture.realReaders() {
        coEvery { dao.enVueloCount() } coAnswers { callOriginal() }
        coEvery { dao.contarEnVueloSqlQ15() } answers { (query(Q15_EN_VUELO_COUNT_SQL).single().values.single() as Number).toInt() }
        coEvery { dao.canceladosCandidatosQ15(any(), any()) } answers {
            query(Q15_CANCELLED_STATE_PAGE_SQL, mapOf("cursor" to firstArg<String>(), "estado" to secondArg<String>())).map(::decode)
        }
        coEvery { dao.getFailedPayments() } coAnswers { callOriginal() }
        coEvery { dao.fallidosOrdinariosQ15() } answers { query(Q15_FAILED_ORDINARY_SQL).map(::decode) }
        coEvery { dao.contarFallidosOrdinariosQ15() } answers { (query(Q15_FAILED_ORDINARY_COUNT_SQL).single().values.single() as Number).toInt() }
        every { dao.getFailedCount() } answers { callOriginal() }
        every { dao.observarRecuentoFallidosQ15() } returns flowOf(0)
    }
    private fun Q16PrecioCapturadoTest.Fixture.respond(external: String = "fui5-venta-original", callback: (Int) -> MockResponse): AtomicInteger {
        val calls = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                assertTrue("No create/pay route may be called", request.path.orEmpty().endsWith("/sync/recovery"))
                val sent = JSONObject(request.body.readUtf8())
                assertEquals(true, sent.opt("includeCancelledPaymentEvidence"))
                assertEquals(external, sent.getJSONArray("externalOrderIds").getString(0))
                return callback(calls.incrementAndGet())
            }
        }
        return calls
    }
    private suspend fun Q16PrecioCapturadoTest.Fixture.proposal(): PaymentSyncService.PropuestaRevisionCancelada {
        val result = service.retryFailedPayment(key)
        assertTrue("El retenido íntegro sólo ofrece revisión", result is PaymentSyncService.RetryOutcome.CancelledZeroPaymentReview)
        return (result as PaymentSyncService.RetryOutcome.CancelledZeroPaymentReview).propuesta
    }

    @Test fun `FUI5 captura integra EN_VUELO permite probar y validar revision persistida`() {
        for (row in listOf(original(), original(orderId = "orden-cancelada"))) {
            assertNotNull(RevisionCanceladoCero.evidencia(row, response()))
            assertTrue(RevisionCanceladoCero.revisado(reviewed(row)))
            assertEquals("EN_VUELO", row.syncStatus)
        }
    }

    @Test fun `FUI5 captura falta cruza identidad o cambia dinero metodo pedido o retencion no es candidata`() {
        val row = original()
        fun capture(field: String, value: Any?) = JSONObject(row.q15CaptureJson!!).put(field, value ?: JSONObject.NULL).toString()
        val invalid = listOf(
            row.copy(q15CaptureJson = null), row.copy(q15CaptureJson = "{}"),
            row.copy(q15CaptureJson = capture("venueId", "otro-venue")), row.copy(q15CaptureJson = capture("key", "otra-llave")),
            row.copy(q15CaptureJson = capture("amountCents", 1)), row.copy(q15CaptureJson = capture("tipCents", 1)),
            row.copy(q15CaptureJson = capture("method", "CARD")), row.copy(q15CaptureJson = capture("orderId", "otra-orden")),
            row.copy(q15CaptureJson = capture("splitType", "CUSTOM_AMOUNT")), row.copy(q15CaptureJson = capture("customerId", "otro-cliente")),
            row.copy(q15CaptureJson = capture("cashTenderedCents", 1)), row.copy(q15CaptureJson = capture("changeCents", 1)),
            row.copy(q15CaptureJson = capture("orderRequestJson", body.replace("articulo-A", "articulo-B"))),
            row.copy(q15CaptureJson = capture("orderRequestJson", body.replace("fui5-venta-original", "otra-venta"))),
            row.copy(q15CaptureJson = capture("tenderTypeId", "vale")), row.copy(method = "CARD"),
            row.copy(tenderTypeId = "vale", tenderRevision = 1), row.copy(amountCents = 1), row.copy(tipCents = 1),
            row.copy(paymentType = "FAST"), row.copy(q15Reason = null), row.copy(q15Reason = ConciliacionDelCobro.CAPTURA_SIN_EVIDENCIA),
            row.copy(q15Reason = ConciliacionDelCobro.PRICE_PROOF_PENDING), row.copy(q15ReviewedAt = 10),
            row.copy(syncStatus = "PENDING"), row.copy(syncStatus = "SYNCING"), row.copy(orderRequestJson = "{}"),
        )
        for (bad in invalid) {
            assertNull(bad.toString(), RevisionCanceladoCero.evidencia(bad, response()))
        }
        for (bad in invalid.filter { it.q15Reason == row.q15Reason && it.q15ReviewedAt == null })
            assertFalse(bad.toString(), RevisionCanceladoCero.revisado(reviewed(bad)))
    }

    @Test fun `FUI5 propuesta no escribe confirmacion consulta otra vez y conserva originales al reabrir`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.prepare(); val before = f.row(key)
            val calls = f.respond { MockResponse().setBody(response()) }
            val proposal = f.proposal()
            assertEquals(1, calls.get()); assertEquals(before, f.row(key))
            assertTrue(f.service.confirmarRevisionCancelada(proposal)); assertEquals(2, calls.get())
            f.reopen()
            val after = f.row(key)
            for (field in listOf("id", "venueId", "syncStatus", "orderId", "original", "orderRequestJson", "q15CaptureJson", "lastError", "retryCount", "q15InitialResultJson"))
                assertEquals(field, before[field], after[field])
            assertTrue(RevisionCanceladoCero.revisado(f.decode(after)))
            assertEquals(0, f.query("SELECT * FROM pending_payments WHERE syncStatus='PENDING'").size)
        }
    }

    @Test fun `FUI5 cuenta abierta pagos TPR falta prueba backend viejo y HTTP retienen sin replay con desenlace propio`() = runBlocking {
        val invalid = listOf(
            MockResponse().setBody(response().replace("CANCELLED", "CONFIRMED")),
            MockResponse().setBody(response(payment = true)), MockResponse().setBody(response(terminal = true)),
            MockResponse().setBody("{}"), MockResponse().setBody(response().replace("\"hasPayments\":false,", "")),
            MockResponse().setResponseCode(503),
        )
        for (reply in invalid) Q16PrecioCapturadoTest.Fixture().use { f ->
            f.prepare(); val before = f.row(key); val calls = f.respond { reply }
            assertEquals("ConsultaSinPrueba", f.service.retryFailedPayment(key).javaClass.simpleName)
            assertEquals(1, calls.get()); assertEquals(before, f.row(key))
        }
    }

    @Test fun `FUI5 captura invalida ni consulta ni reabre la cola`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.prepare(original().copy(q15CaptureJson = null)); val before = f.row(key)
            val calls = f.respond { MockResponse().setBody(response()) }
            assertEquals("ConsultaSinPrueba", f.service.retryFailedPayment(key).javaClass.simpleName)
            assertEquals(0, calls.get()); assertEquals(before, f.row(key))
        }
    }

    /** Deidentified exact writer fingerprint: original capture, known row, reconstructed external/date only. */
    private fun historical(currentDate: Long = 1792853416610L): PendingPaymentEntity {
        val capturedBody = JSONObject(body).put("externalId", key).put("createdAtLocal", 1792853379475L).toString()
        val durableBody = JSONObject(capturedBody).put("externalId", "offline-order:$key").put("createdAtLocal", currentDate).toString()
        return original(orderId = "orden-cancelada").copy(orderRequestJson = durableBody, createdAt = currentDate,
            capturedOrderRef = key, cashTenderedCents = 0, changeCents = 0,
            q15CaptureJson = ConciliacionDelCobro.guardarCaptura(CapturaDelCobro(Q16PrecioCapturadoTest.VENUE, key,
                amountCents = 0, tipCents = 0, splitType = "FULLPAYMENT", method = "CASH", cashTenderedCents = 0,
                changeCents = 0, orderRequestJson = capturedBody)))
    }

    @Test fun `FUI5 historico STRICT consulta captura dos veces preserva documentos y libera lectores incluso reloj atras`() = runBlocking {
        for (date in listOf(1792853416610L, 1792853379000L)) Q16PrecioCapturadoTest.Fixture().use { f ->
            val row = historical(date); f.prepare(row); f.realReaders()
            f.sql("UPDATE pending_payments SET q15InitialResultJson='primer-resultado-original' WHERE id=:id", mapOf("id" to key))
            val before = f.row(key); assertEquals(1, f.dao.enVueloCount())
            val calls = f.respond(key) { MockResponse().setBody(response(external = key)) }
            val proposal = f.proposal()
            assertEquals(1, calls.get()); assertEquals(before, f.row(key))
            assertTrue(f.service.confirmarRevisionCancelada(proposal)); assertEquals(2, calls.get())
            f.reopen(); val after = f.row(key)
            for (field in listOf("id", "venueId", "syncStatus", "orderId", "orderRequestJson", "q15CaptureJson", "createdAt", "capturedOrderRef", "lastError", "retryCount", "q15InitialResultJson"))
                assertEquals(field, before[field], after[field])
            val saved = f.decode(after)
            assertTrue(RevisionCanceladoCero.revisado(saved))
            assertEquals(0, f.dao.enVueloCount()); assertEquals(0, f.service.blockingWorkCount())
            assertTrue(f.dao.getFailedPayments().isEmpty()); assertEquals(0, f.dao.getFailedCount().first())
            val query = mapOf("venueId" to row.venueId, "busqueda" to "", "revisadas" to 1)
            assertEquals(1, (f.query(Q15_HISTORY_COUNT_SQL, query).single()["total"] as Number).toInt())
            assertEquals(0, (f.query(Q15_HISTORY_COUNT_SQL, query + ("revisadas" to 0)).single()["total"] as Number).toInt())
            Q16OutboxPrecioTest.Fixture().use { q ->
                q.disk.insertCaptured(saved)
                assertFalse(q.dao.hayCapturaSinResolverQ16(row.venueId))
                assertTrue(q.dao.referenciasSinResolverQ16(row.venueId, listOf(key)).isEmpty())
                q.disk.sql("UPDATE pending_payments SET q15ResultJson='{}' WHERE id=:id", mapOf("id" to key))
                assertTrue(q.dao.hayCapturaSinResolverQ16(row.venueId))
                assertEquals(listOf(key), q.dao.referenciasSinResolverQ16(row.venueId, listOf(key)))
            }
        }
    }

    @Test fun `FUI5 historico STRICT requiere known ref fingerprint documentos fechas y proof originales exactos`() {
        val row = historical()
        fun current(field: String, value: Any?) = JSONObject(row.orderRequestJson!!).put(field, value ?: JSONObject.NULL).toString()
        fun captureBody(field: String, value: Any?): String = JSONObject(row.q15CaptureJson!!).apply {
            val original = JSONObject(getString("orderRequestJson")).put(field, value ?: JSONObject.NULL)
            put("orderRequestJson", original.toString())
        }.toString()
        val invalidRows = listOf(
            row.copy(orderId = null), row.copy(orderId = " "), row.copy(orderId = "otra-orden"),
            row.copy(capturedOrderRef = null), row.copy(capturedOrderRef = "otra-ref"),
            row.copy(q15CaptureJson = null), row.copy(q15CaptureJson = "{}"),
            row.copy(q15CaptureJson = JSONObject(row.q15CaptureJson!!).put("orderId", "orden-cancelada").toString()),
            row.copy(q15CaptureJson = JSONObject(row.q15CaptureJson!!).put("amountCents", 1).toString()),
            row.copy(q15CaptureJson = captureBody("externalId", "otra-identidad")),
            row.copy(q15CaptureJson = captureBody("createdAtLocal", "1792853379475")),
            row.copy(q15CaptureJson = captureBody("createdAtLocal", 0)),
            row.copy(orderRequestJson = current("externalId", "offline-order:otra-llave")),
            row.copy(orderRequestJson = current("total", 1)), row.copy(orderRequestJson = current("staffId", "otro-staff")),
            row.copy(orderRequestJson = current("createdAtLocal", "1792853416610")),
            row.copy(orderRequestJson = current("createdAtLocal", 0), createdAt = 0), row.copy(createdAt = 1792853416611L),
        )
        for (bad in invalidRows) {
            assertNull(bad.toString(), RevisionCanceladoCero.evidencia(bad, response(external = key)))
            assertFalse(bad.toString(), RevisionCanceladoCero.revisado(reviewed(bad, response(external = key))))
        }
        val invalidReplies = listOf(response(external = "offline-order:$key"), response(orderId = "otra-orden", external = key),
            response(payment = true, external = key), response(terminal = true, external = key),
            response(external = key).replace("CANCELLED", "CONFIRMED"),
            response(external = key).replace("\"hasPayments\":false", "\"hasPayments\":null"),
            response(external = key).replace("\"hasTerminalRequests\":false", "\"hasTerminalRequests\":\"false\""))
        for (bad in invalidReplies) {
            assertNull(bad, RevisionCanceladoCero.evidencia(row, bad))
            assertFalse(bad, RevisionCanceladoCero.revisado(reviewed(row, bad)))
        }
    }

    @Test fun `FUI5 segunda consulta distinta pago TPR contexto o CAS cambiado nunca guarda revision`() = runBlocking {
        for (kind in listOf("order", "payment", "terminal", "actor", "role", "venue", "epoch", "status", "capture", "reason", "body", "method", "tender"))
            Q16PrecioCapturadoTest.Fixture().use { f ->
                f.prepare()
                val calls = f.respond { n ->
                    if (n == 2) when (kind) {
                        "actor" -> every { f.storage.userId } returns "otro-gerente"
                        "role" -> every { f.storage.userRole } returns "CASHIER"
                        "venue" -> every { f.storage.venueId } returns "otro-venue"
                        "epoch" -> every { f.outbox.contextoDeSesionQ15() } returns 42L
                        "status" -> f.sql("UPDATE pending_payments SET syncStatus='FAILED' WHERE id=:id", mapOf("id" to key))
                        "capture" -> f.sql("UPDATE pending_payments SET q15CaptureJson='{}' WHERE id=:id", mapOf("id" to key))
                        "reason" -> f.sql("UPDATE pending_payments SET q15Reason='PRICE_PROOF_PENDING' WHERE id=:id", mapOf("id" to key))
                        "body" -> f.sql("UPDATE pending_payments SET orderRequestJson='{}' WHERE id=:id", mapOf("id" to key))
                        "method" -> f.sql("UPDATE pending_payments SET method='CARD' WHERE id=:id", mapOf("id" to key))
                        "tender" -> f.sql("UPDATE pending_payments SET tenderTypeId='vale',tenderRevision=1 WHERE id=:id", mapOf("id" to key))
                    }
                    MockResponse().setBody(response(orderId = if (n == 2 && kind == "order") "otra-orden" else "orden-cancelada",
                        payment = n == 2 && kind == "payment", terminal = n == 2 && kind == "terminal"))
                }
                val proposal = f.proposal()
                assertFalse(kind, f.service.confirmarRevisionCancelada(proposal)); assertEquals(kind, 2, calls.get())
                assertNull(kind, f.row(key)["q15ReviewedAt"])
            }
    }

    @Test fun `FUI5 Q16 global y por referencias revisan mas de 50 EN_VUELO sin ocultar prueba invalida`() = runBlocking {
        Q16OutboxPrecioTest.Fixture().use { f ->
            repeat(55) { f.disk.insertCaptured(reviewed(original(id = "fui5-${it.toString().padStart(3, '0')}"))) }
            assertFalse(f.dao.hayCapturaSinResolverQ16(Q16PrecioCapturadoTest.VENUE))
            assertTrue(f.dao.referenciasSinResolverQ16(Q16PrecioCapturadoTest.VENUE, listOf("fui5-venta-original")).isEmpty())
            f.disk.sql("UPDATE pending_payments SET q15ResultJson='{}' WHERE id='fui5-054'")
            assertTrue(f.dao.hayCapturaSinResolverQ16(Q16PrecioCapturadoTest.VENUE))
            assertEquals(listOf("fui5-venta-original"), f.dao.referenciasSinResolverQ16(Q16PrecioCapturadoTest.VENUE, listOf("fui5-venta-original")))
        }
    }

    @Test fun `FUI5 count enVuelo pagina todos y no mezcla candidatos EN_VUELO con FAILED`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.realReaders()
            repeat(55) { f.insertCaptured(reviewed(original(id = "fui5-${it.toString().padStart(3, '0')}"))) }
            f.insertCaptured(reviewed(original(id = "fui5-failed")).copy(syncStatus = "FAILED", q15CaptureJson = null))
            assertEquals(0, f.dao.enVueloCount())
            assertEquals(0, f.service.blockingWorkCount())
            assertTrue(f.dao.getFailedPayments().isEmpty()); assertEquals(0, f.dao.getFailedCount().first())
            f.sql("UPDATE pending_payments SET q15ResultJson='{}' WHERE id IN ('fui5-054','fui5-failed')")
            assertEquals(1, f.dao.enVueloCount()); assertEquals(2, f.service.blockingWorkCount())
            assertEquals(listOf("fui5-failed"), f.dao.getFailedPayments().map { it.id })
            assertEquals(1, f.dao.getFailedCount().first())
            f.sql("UPDATE pending_payments SET id='' WHERE id='fui5-054'")
            assertEquals(1, f.dao.enVueloCount())
        }
    }

    @Test fun `FUI5 revision invalida EN_VUELO sigue visible como interrumpida y valida sale de ambos lectores`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            val valid = reviewed()
            val invalid = reviewed(original(id = "revision-invalida")).copy(q15ResultJson = "{}")
            val ordinary = original(id = "interrumpido-normal").copy(q15Reason = null)
            val retained = original(id = "retenido-Q15")
            val rows = listOf(valid, invalid, ordinary, retained)
            coEvery { f.dao.enVueloDelVenue(Q16PrecioCapturadoTest.VENUE) } returns rows
            every { f.dao.observarEnVuelo() } returns flowOf(rows)
            val cash = CashPaymentRepository(f.storage, f.dao)
            assertEquals(listOf("revision-invalida", "interrumpido-normal"), cash.cobrosSinConfirmar().map { it.id })
            assertEquals(listOf("revision-invalida", "interrumpido-normal"), cash.observarSinConfirmar().first().map { it.id })
        }
    }

    private fun request() = CreateOrderRequest(items = listOf(OrderItemRequest(productId = "articulo-A", name = "Artículo A", quantity = 2, unitPrice = 11000)),
        subtotal = 22000, discount = 22000, total = 0, tip = 0, paymentMethod = "CASH")

    @Test fun `FUI5 writer real re-reserva conocida conserva body y primera captura y alcanza candidato de revision`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.realWriters()
            val cash = CashPaymentRepository(f.storage, f.dao)
            cash.reservarCobro(request(), "staff-original", 0, 0, null, orderExternalId = "fui5-venta-original",
                idempotencyKey = key, venueId = Q16PrecioCapturadoTest.VENUE, capturadaEn = 1000)
            val before = f.row(key)
            cash.reservarCobro(request(), "staff-original", 0, 0, null, orderId = "orden-cancelada",
                idempotencyKey = key, venueId = Q16PrecioCapturadoTest.VENUE, capturadaEn = 2000)
            val after = f.row(key)
            for (field in listOf("id", "venueId", "orderRequestJson", "q15CaptureJson", "capturedOrderRef"))
                assertEquals(field, before[field], after[field])
            assertEquals(2000L, (after["createdAt"] as Number).toLong())
            assertEquals(1000L, ConciliacionDelCobro.leerCaptura(after["q15CaptureJson"] as String, Q16PrecioCapturadoTest.VENUE, key)!!.primeraCapturaEn)
            assertEquals("orden-cancelada", after["orderId"])
            cash.descartarPorqueNoSeCobro(key)
            val retained = requireNotNull(f.dao.findForVenue(Q16PrecioCapturadoTest.VENUE, key))
            assertNotNull("El writer real debe producir la captura coherente que admite la salida", RevisionCanceladoCero.evidencia(retained, response()))
        }
    }

    @Test fun `FUI5 re-reserva no reemplaza importe propina pedido identidad explicita ni orden conocida`() = runBlocking {
        for (kind in listOf("amount", "tip", "items", "external", "order", "blank-order", "historical")) Q16PrecioCapturadoTest.Fixture().use { f ->
            f.realWriters()
            val cash = CashPaymentRepository(f.storage, f.dao)
            cash.reservarCobro(request(), "staff-original", 0, 0, null, orderId = "orden-cancelada", orderExternalId = "fui5-venta-original",
                idempotencyKey = key, venueId = Q16PrecioCapturadoTest.VENUE, capturadaEn = 1000)
            if (kind == "historical") f.sql("UPDATE pending_payments SET orderRequestJson='{}' WHERE id=:id", mapOf("id" to key))
            val before = f.row(key)
            val failure = runCatching {
                cash.reservarCobro(if (kind == "items") request().copy(items = listOf(OrderItemRequest(productId = "otro-articulo", name = "Otro artículo", quantity = 1, unitPrice = 22000))) else request(),
                    "staff-original", 0, 0, null, orderId = when (kind) { "order" -> "otra-orden"; "blank-order" -> " "; else -> "orden-cancelada" },
                    orderExternalId = if (kind == "external") "otra-venta" else null, idempotencyKey = key,
                    venueId = Q16PrecioCapturadoTest.VENUE, amountCents = if (kind == "amount") 1 else 0, tipCents = if (kind == "tip") 1 else 0)
            }.exceptionOrNull()
            assertNotNull("Debe rechazar $kind antes de cualquier POST", failure)
            assertEquals(kind, before, f.row(key)); assertEquals(0, f.server.requestCount)
        }
    }

    @Test fun `FUI5 retry con external explicito mantiene primera hora y queue conserva captura sin orden al promover`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.realWriters()
            val cash = CashPaymentRepository(f.storage, f.dao)
            cash.reservarCobro(request(), "staff-original", 0, 0, null, orderExternalId = "fui5-venta-original",
                idempotencyKey = key, venueId = Q16PrecioCapturadoTest.VENUE, capturadaEn = 1000)
            val before = f.row(key)
            cash.reservarCobro(request(), "staff-original", 0, 0, null, orderExternalId = "fui5-venta-original",
                idempotencyKey = key, venueId = Q16PrecioCapturadoTest.VENUE, capturadaEn = 2000)
            assertEquals(before + ("createdAt" to 2000), f.row(key))
            cash.queueCashPayment(request(), "staff-original", 0, 0, null, orderId = "orden-cancelada",
                idempotencyKey = key, venueId = Q16PrecioCapturadoTest.VENUE, capturadaEn = 3000)
            val after = f.row(key)
            assertEquals("PENDING", after["syncStatus"]); assertEquals("orden-cancelada", after["orderId"])
            for (field in listOf("orderRequestJson", "q15CaptureJson", "capturedOrderRef"))
                assertEquals(field, before[field], after[field])
            assertEquals(2000L, (after["createdAt"] as Number).toLong())
            assertEquals(1000L, ConciliacionDelCobro.leerCaptura(after["q15CaptureJson"] as String, Q16PrecioCapturadoTest.VENUE, key)!!.primeraCapturaEn)
            assertNull(ConciliacionDelCobro.leerCaptura(after["q15CaptureJson"] as String, Q16PrecioCapturadoTest.VENUE, key)!!.orderId)
        }
    }

    @Test fun `FUI5 known hacia NULL conserva orden adoptada y no cambia identidad economica`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.realWriters()
            val cash = CashPaymentRepository(f.storage, f.dao)
            cash.reservarCobro(request(), "staff-original", 0, 0, null, orderId = "orden-cancelada", orderExternalId = "fui5-venta-original",
                idempotencyKey = key, venueId = Q16PrecioCapturadoTest.VENUE, capturadaEn = 1000)
            val before = f.row(key)
            cash.reservarCobro(request(), "staff-original", 0, 0, null, idempotencyKey = key, venueId = Q16PrecioCapturadoTest.VENUE, capturadaEn = 2000)
            assertEquals(before + ("createdAt" to 2000), f.row(key))
        }
    }

    @Test fun `FUI5 parcial conserva efectivo omitido al encolar y otra llave admite otro importe de misma cuenta`() = runBlocking {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.realWriters()
            val cash = CashPaymentRepository(f.storage, f.dao)
            val fullOrder = request().copy(discount = 0, total = 22000)
            cash.reservarCobro(fullOrder, "staff-original", 6000, 1000, null, orderId = "orden-cancelada",
                orderExternalId = "fui5-venta-original", idempotencyKey = key, amountCents = 5000, tipCents = 0,
                splitType = "CUSTOM_AMOUNT", venueId = Q16PrecioCapturadoTest.VENUE, capturadaEn = 1000)
            val before = f.row(key)
            cash.queueCashPayment(fullOrder, "staff-original", null, null, null, orderId = "orden-cancelada",
                idempotencyKey = key, amountCents = 5000, tipCents = 0, splitType = "CUSTOM_AMOUNT", venueId = Q16PrecioCapturadoTest.VENUE)
            val after = f.row(key)
            assertEquals(before["q15CaptureJson"], after["q15CaptureJson"])
            assertEquals(6000, f.decode(after).cashTenderedCents); assertEquals(1000, f.decode(after).changeCents)
            assertEquals(5000, f.decode(after).amountCents)
            cash.reservarCobro(fullOrder, "staff-original", null, null, null, orderId = "orden-cancelada",
                orderExternalId = "fui5-venta-original", idempotencyKey = "otra-llave-tras-rechazo", amountCents = 17000,
                tipCents = 0, splitType = "CUSTOM_AMOUNT", venueId = Q16PrecioCapturadoTest.VENUE)
            assertEquals(17000, f.decode(f.row("otra-llave-tras-rechazo")).amountCents)
            assertEquals(5000, f.decode(f.row(key)).amountCents)
        }
    }
    @Test fun `FUI5 reprice positivo real crea adopta envia y encola misma llave sin perder captura ni origen Q16`() = runBlocking {
        for (adoptedByServer in listOf(6000, 0)) Q16PrecioCapturadoTest.Fixture().use { f ->
            f.realWriters()
            every { f.storage.accessToken } returns "fixture-token-no-auth"
            val cash = CashPaymentRepository(f.storage, f.dao)
            val order = com.avoqado.pos.payment.data.OrderRepository(f.storage, f.client, f.dao)
            val pedido = request().copy(items = listOf(OrderItemRequest.promocion(
                com.avoqado.pos.payment.data.model.PromotionRefRequest("promo-original", "instancia-original"))),
                subtotal = 8000, discount = 0, total = 8000)
            cash.reservarCobro(pedido, "staff-original", 10000, 2000, null, orderExternalId = key,
                idempotencyKey = key, venueId = Q16PrecioCapturadoTest.VENUE, capturadaEn = 1000)
            val before = f.row(key)
            val calls = AtomicInteger()
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val n = calls.incrementAndGet()
                    return if (n == 1) {
                        assertTrue(request.path.orEmpty().endsWith("/orders"))
                        assertEquals(key, JSONObject(request.body.readUtf8()).getString("externalId"))
                        MockResponse().setResponseCode(201).setBody("""{"success":true,"order":{"id":"orden-cancelada","total":${adoptedByServer / 100}}}""")
                    } else {
                        assertEquals(2, n)
                        assertTrue(request.path.orEmpty().endsWith("/orders/orden-cancelada/pay"))
                        val body = JSONObject(request.body.readUtf8())
                        assertEquals(key, body.getString("idempotencyKey")); assertEquals(adoptedByServer, body.getInt("amount"))
                        MockResponse().setResponseCode(503).setBody("""{"message":"Respuesta no disponible"}""")
                    }
                }
            }
            val created = order.createOrder(pedido, "staff-original", externalId = key,
                capturedVenueId = Q16PrecioCapturadoTest.VENUE).getOrThrow()
            val adopted = com.avoqado.pos.payment.data.model.totalACobrarCents(8000, created.data, true, true)
            assertEquals(adoptedByServer, adopted)
            val paid = order.recordCashPayment("orden-cancelada", adopted, "staff-original", idempotencyKey = key,
                capturedVenueId = Q16PrecioCapturadoTest.VENUE, importeAceptadoCents = adopted)
            assertEquals(503, (paid.exceptionOrNull() as com.avoqado.pos.payment.data.OrderRepository.ServerException).code)
            val queued = cash.queueCashPayment(pedido, "staff-original", 10000, 10000 - adopted, null, orderId = "orden-cancelada",
                amountCents = adopted, idempotencyKey = key, venueId = Q16PrecioCapturadoTest.VENUE)
            assertEquals(key, queued); assertEquals(2, calls.get())
            val after = f.row(key)
            val persisted = f.decode(after)
            assertEquals("PENDING", persisted.syncStatus); assertEquals(adopted, persisted.amountCents)
            assertEquals(10000, persisted.cashTenderedCents); assertEquals(10000 - adopted, persisted.changeCents)
            assertEquals("orden-cancelada", persisted.orderId)
            assertEquals(before["q15CaptureJson"], after["q15CaptureJson"])
            assertEquals(before["capturedOrderRef"], after["capturedOrderRef"])
            val capture = requireNotNull(ConciliacionDelCobro.leerCaptura(persisted.q15CaptureJson, persisted.venueId, key))
            assertNull("Q16 conserva que nació sin orden", capture.orderId)
            assertEquals(8000, capture.amountCents); assertEquals(key, persisted.capturedOrderRef)
            assertEquals(key, JSONObject(requireNotNull(capture.orderRequestJson)).getString("externalId"))
            if (adopted == 0) {
                cash.reservarCobro(pedido, "staff-original", 10000, 10000, null, orderId = "orden-cancelada",
                    amountCents = 0, idempotencyKey = key, venueId = Q16PrecioCapturadoTest.VENUE)
                assertEquals("EN_VUELO", f.row(key)["syncStatus"])
                assertEquals(before["q15CaptureJson"], f.row(key)["q15CaptureJson"])
                cash.queueCashPayment(pedido, "staff-original", null, null, null, orderId = "orden-cancelada",
                    amountCents = 0, idempotencyKey = key, venueId = Q16PrecioCapturadoTest.VENUE)
                val retried = f.decode(f.row(key))
                assertEquals("PENDING", retried.syncStatus); assertEquals(0, retried.amountCents)
                assertEquals(10000, retried.cashTenderedCents); assertEquals(10000, retried.changeCents)
                assertEquals(before["q15CaptureJson"], retried.q15CaptureJson)
                assertNull(ConciliacionDelCobro.leerCaptura(retried.q15CaptureJson, retried.venueId, key)!!.orderId)
            }
        }
    }

}
