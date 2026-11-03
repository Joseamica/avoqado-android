package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.database.PendingPaymentEntity
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Old FAILED rows may have no Q15 capture. Only the original body and an exact server read identify them. */
@OptIn(ExperimentalCoroutinesApi::class)
class CanceladoCeroRecoveryContractTest {
    @get:Rule val main = MainDispatcherRule()
    @get:Rule val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(30)

    private val originalBody = """{"externalId":"venta-cancelada-original","items":[{"productId":"bebida-A","quantity":2}],"subtotal":0,"total":0,"tip":0}"""
    private val proof = """{"version":1,"venueId":"${Q16PrecioCapturadoTest.VENUE}","orderId":"orden-cancelada-original","externalId":"venta-cancelada-original","orderStatus":"CANCELLED","hasPayments":false,"hasTerminalRequests":false,"verifiedAt":"2026-10-23T20:00:00.000Z"}"""
    private fun recovery(evidence: String = proof) = """{"data":{"intents":[],"orders":[{"orderId":"orden-cancelada-original","externalId":"venta-cancelada-original","cancelledPaymentEvidence":$evidence}]}}"""

    private fun Q16PrecioCapturadoTest.Fixture.seedCancelled() {
        insertCaptured(PendingPaymentEntity(
            "folio-fallido-original", Q16PrecioCapturadoTest.VENUE, "staff-original", 0, 0, "CASH", "ORDER",
            orderId = "orden-cancelada-original", orderRequestJson = originalBody, syncStatus = "FAILED",
            q15CaptureJson = null,
        ))
        sql("UPDATE pending_payments SET lastError=:error,retryCount=3 WHERE id='folio-fallido-original'",
            mapOf("error" to "Error 400: {\"message\":\"Payment amount (0) is less than remaining balance (22000)\"}"))
        every { storage.userRole } returns "MANAGER"
        every { storage.userId } returns "gerente-actual"
        service.usarContextoDeSincronizacion(Dispatchers.Unconfined)
    }

    @Test fun `reintentar un FAILED de cero consulta la identidad original sin reenviar dinero ni revisarlo solo`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.seedCancelled()
            f.code = 200; f.response = recovery()
            val before = f.row("folio-fallido-original")
            val outcome = f.service.retryFailedPayment("folio-fallido-original")
            assertEquals("Debe consultar recovery antes de proponer una revisión", 1, f.server.requestCount)
            val request = requireNotNull(f.server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(request.path.orEmpty().endsWith("/sync/recovery"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals(true, body.opt("includeCancelledPaymentEvidence"))
            assertEquals(listOf("venta-cancelada-original"), (0 until body.getJSONArray("externalOrderIds").length()).map {
                body.getJSONArray("externalOrderIds").getString(it)
            })
            val after = f.row("folio-fallido-original")
            assertNotEquals(com.avoqado.pos.payment.data.PaymentSyncService.RetryOutcome.Applied, outcome)
            assertEquals("FAILED", after["syncStatus"])
            assertNull(after["q15ReviewedAt"])
            for (key in listOf("original", "orderId", "lastError", "retryCount", "q15CaptureJson")) {
                assertEquals(key, before[key], after[key])
            }
        }
    }

    @Test fun `backend viejo 404 500 o evidencia incompleta conservan el intento y no envian pay`() = runTest {
        for ((code, response) in listOf(
            404 to "{}", 500 to "{}", 200 to """{"data":{"orders":[]}}""",
            200 to recovery("""{"version":1}"""),
            200 to recovery(JSONObject(proof).put("hasPayments", true).toString()),
            200 to recovery(JSONObject(proof).put("hasTerminalRequests", true).toString()),
            200 to recovery(JSONObject(proof).put("externalId", "otra-venta").toString()),
        )) Q16PrecioCapturadoTest.Fixture().use { f ->
            f.seedCancelled(); f.code = code; f.response = response
            val before = f.row("folio-fallido-original")
            f.service.retryFailedPayment("folio-fallido-original")
            assertEquals("La negativa debe venir de la lectura real de recovery", 1, f.server.requestCount)
            val after = f.row("folio-fallido-original")
            assertEquals("FAILED", after["syncStatus"])
            assertNull(after["q15ReviewedAt"])
            assertEquals(before["original"], after["original"])
            assertEquals(before["lastError"], after["lastError"])
            assertTrue("La revisión sólo puede leer", (0 until f.server.requestCount).all {
                requireNotNull(f.server.takeRequest(1, java.util.concurrent.TimeUnit.SECONDS)).path.orEmpty().endsWith("/sync/recovery")
            })
        }
    }

    @Test fun `cambiar de sucursal durante recovery conserva la fila original sin marcarla revisada`() = runTest {
        Q16PrecioCapturadoTest.Fixture().use { f ->
            f.seedCancelled()
            val before = f.row("folio-fallido-original")
            f.server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    every { f.storage.venueId } returns "otro-negocio"
                    return MockResponse().setResponseCode(200).setBody(recovery())
                }
            }
            f.service.retryFailedPayment("folio-fallido-original")
            assertEquals("Debe alcanzar la respuesta que cambia de contexto", 1, f.server.requestCount)
            val after = f.row("folio-fallido-original")
            assertEquals("FAILED", after["syncStatus"])
            assertNull(after["q15ReviewedAt"])
            assertEquals(before["q15ResultJson"], after["q15ResultJson"])
            assertEquals(before["original"], after["original"])
            assertEquals(before["lastError"], after["lastError"])
        }
    }
}
