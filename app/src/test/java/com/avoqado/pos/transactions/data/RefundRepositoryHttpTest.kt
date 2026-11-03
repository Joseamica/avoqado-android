package com.avoqado.pos.transactions.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.transactions.data.model.esRechazoDefinitivoDeSaldo
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** El Reembolso conserva el status y el `code` del rechazo (A2; lo usa la regla de lo pendiente de A3). Espejo de RefundRepositoryHTTPTests.swift. */
class RefundRepositoryHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: RefundRepository

    @Before fun setup() {
        server = MockWebServer(); server.start()
        val storage = mockk<SecureStorage>(relaxed = true) { every { venueId } returns "venue" }
        repo = RefundRepository(storage, OkHttpClient.Builder().retryOnConnectionFailure(false).build())
        repo.baseUrl = server.url("/api/v1").toString().trimEnd('/')
    }
    @After fun cleanup() { server.shutdown() }

    private fun rechazo(status: Int, body: String): RefundApiException = runBlocking {
        server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(body))
        repo.issueAssociatedRefund("payment", "RETURNED_GOODS", amountCents = 100, refundMethod = "STORE_CREDIT").exceptionOrNull() as RefundApiException
    }

    @Test fun `el reembolso conserva el status y el code del rechazo`() {
        val enCurso = rechazo(409, """{"success":false,"code":"STORE_CREDIT_TERMINAL_REFUND_IN_PROGRESS","message":"La terminal está devolviendo este cobro"}""")
        assertEquals("/api/v1/mobile/venues/venue/payments/payment/refund", server.takeRequest().path)
        assertEquals(409, enCurso.statusCode)
        assertEquals("STORE_CREDIT_TERMINAL_REFUND_IN_PROGRESS", enCurso.code)
        assertEquals("La terminal está devolviendo este cobro", enCurso.message)
        assertTrue(esRechazoDefinitivoDeSaldo(enCurso.statusCode, enCurso.code))

        val reusada = rechazo(409, """{"success":false,"code":"IDEMPOTENCY_KEY_REUSED","message":"Esta llave ya se usó."}""")
        assertEquals("IDEMPOTENCY_KEY_REUSED", reusada.code)
        assertFalse(esRechazoDefinitivoDeSaldo(reusada.statusCode, reusada.code))

        val sinCodigo = rechazo(400, """{"success":false,"message":"El cuerpo no es válido"}""")
        assertNull(sinCodigo.code)
        assertTrue(esRechazoDefinitivoDeSaldo(sinCodigo.statusCode, sinCodigo.code))
    }
}
