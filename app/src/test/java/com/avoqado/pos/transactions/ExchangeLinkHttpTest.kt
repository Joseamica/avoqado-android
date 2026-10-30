package com.avoqado.pos.transactions

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.transactions.data.TransactionRepository
import io.mockk.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ExchangeLinkHttpTest {
    @Test fun `forbidden linked order preserves permission denial instead of returning a connection failure`() = runBlocking {
        MockWebServer().use { server ->
            val storage = mockk<SecureStorage>(relaxed = true) {
                every { userId } returns "actor"
                every { venueId } returns "venue"
                every { accessToken } returns "token"
                every { selloYAccess() } returns (0L to "token")
                every { accessSiSigue(0L) } returns "token"
                every { siSigueLaGeneracion(0L, any()) } returns true
            }
            val repo = TransactionRepository(storage, OkHttpClient()).apply { baseUrl = server.url("/api/v1").toString().trimEnd('/') }
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Forbidden"}"""))
            try {
                repo.paymentIdForExchangeOrder("order", "actor", "venue")
                fail("Expected permission denial")
            } catch (_: SecurityException) { }
            server.enqueue(MockResponse().setResponseCode(503))
            assertNull(repo.paymentIdForExchangeOrder("order", "actor", "venue"))
        }
    }

    @Test fun `order link resolves a payment id instead of sending order id to transaction route`() = runBlocking {
        MockWebServer().use { server ->
            val storage = mockk<SecureStorage>(relaxed = true) {
                every { userId } returns "actor"
                every { venueId } returns "venue"
                every { accessToken } returns "token"
                every { selloYAccess() } returns (0L to "token")
                every { accessSiSigue(0L) } returns "token"
                every { siSigueLaGeneracion(0L, any()) } returns true
            }
            val repo = TransactionRepository(storage, OkHttpClient()).apply { baseUrl = server.url("/api/v1").toString().trimEnd('/') }
            server.enqueue(MockResponse().setBody("""{"success":true,"order":{"id":"order","payments":[{"id":"refund","status":"REFUNDED"},{"id":"payment","status":"COMPLETED"}]}}"""))
            assertEquals("payment", repo.paymentIdForExchangeOrder("order", "actor", "venue"))
            assertEquals("/api/v1/mobile/venues/venue/orders/order", server.takeRequest().path)
            server.enqueue(MockResponse().setBody("""{"order":{"id":"other","payments":[{"id":"payment","status":"COMPLETED"}]}}"""))
            assertNull(repo.paymentIdForExchangeOrder("order", "actor", "venue"))
        }
    }

    // F4 (iPhone, 28-oct): el SERVIDOR contestó y la venta nueva no tiene pago cobrado: se distingue de una falla de red.
    @Test fun `order without charge says whether it was cancelled`() = runBlocking {
        MockWebServer().use { server ->
            val storage = mockk<SecureStorage>(relaxed = true) {
                every { userId } returns "actor"
                every { venueId } returns "venue"
                every { accessToken } returns "token"
                every { selloYAccess() } returns (0L to "token")
                every { accessSiSigue(0L) } returns "token"
                every { siSigueLaGeneracion(0L, any()) } returns true
            }
            val repo = TransactionRepository(storage, OkHttpClient()).apply { baseUrl = server.url("/api/v1").toString().trimEnd('/') }
            for ((status, cancelled) in listOf("CONFIRMED" to false, "CANCELLED" to true)) {
                server.enqueue(MockResponse().setBody("""{"order":{"id":"order","status":"$status","payments":[]}}"""))
                try {
                    repo.paymentIdForExchangeOrder("order", "actor", "venue")
                    fail("Expected ExchangeOrderNotCharged")
                } catch (e: com.avoqado.pos.transactions.data.ExchangeOrderNotCharged) { assertEquals(cancelled, e.cancelled) }
            }
            // Un pago PROCESSING o REFUNDED no prueba que no se cobró: sigue siendo «no se pudo abrir» (Codex f4-r2).
            for (status in listOf("PROCESSING", "REFUNDED")) {
                server.enqueue(MockResponse().setBody("""{"order":{"id":"order","status":"CANCELLED","payments":[{"id":"p","status":"$status"}]}}"""))
                assertNull(repo.paymentIdForExchangeOrder("order", "actor", "venue"))
            }
        }
    }

    // espejo de ExchangeLinkHttpTests.swift (recorrido del iPad, 27-oct): la sesión del POS dura 10 min y el primer
    // toque decía «No se pudo abrir la venta vinculada». El autenticador de la app renueva sin reenviar (NO_AUTO_RETRY).
    @Test fun `expired session retries once with the renewed token of the same actor`() = runBlocking {
        val (payment, sent) = linkWithExpiredSession(switchActorDuringRefresh = false)
        assertEquals("payment", payment)
        assertEquals(listOf("Bearer old", "Bearer new"), sent)
    }

    @Test fun `actor switch during renewal never retries`() = runBlocking {
        val (payment, sent) = linkWithExpiredSession(switchActorDuringRefresh = true)
        assertNull(payment)
        assertEquals(listOf("Bearer old"), sent)
    }

    @Test fun `sale detail also retries once after the renewal`() = runBlocking {
        MockWebServer().use { server ->
            var token = "old"
            val storage = mockk<SecureStorage>(relaxed = true) {
                every { userId } returns "actor"
                every { venueId } returns "venue"
                every { accessToken } answers { token }
                every { selloYAccess() } answers { 0L to token }
                every { accessSiSigue(0L) } answers { token }
                every { siSigueLaGeneracion(0L, any()) } returns true
            }
            val client = OkHttpClient.Builder().authenticator { _, _ -> token = "new"; null }.build()
            val repo = TransactionRepository(storage, client).apply { baseUrl = server.url("/api/v1").toString().trimEnd('/') }
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"jwt expired"}"""))
            server.enqueue(MockResponse().setBody("""{"transaction":{"id":"payment","amount":85.0}}"""))
            assertEquals("payment", repo.fetchTransactionDetail("payment", "actor", "venue")?.id)
            assertEquals(listOf("Bearer old", "Bearer new"), List(2) { server.takeRequest().getHeader("Authorization") })
        }
    }

    // D4 (Codex código r3 #4): se decidió repetir con la sesión de A, pero justo antes de salir la misma persona salió y
    // volvió a entrar. La repetición ya es de otra sesión y no sale.
    @Test fun `the retry does not leave if the session changed right before sending`() = runBlocking {
        MockWebServer().use { server ->
            var token = "old"
            val storage = mockk<SecureStorage>(relaxed = true) {
                every { userId } returns "actor"
                every { venueId } returns "venue"
                every { accessToken } answers { token }
                every { selloYAccess() } answers { 0L to token }
                every { accessSiSigue(0L) } answers { token }
                every { siSigueLaGeneracion(0L, any()) } returnsMany listOf(true, false)
            }
            val client = OkHttpClient.Builder().authenticator { _, _ -> token = "new"; null }.build()
            val repo = TransactionRepository(storage, client).apply { baseUrl = server.url("/api/v1").toString().trimEnd('/') }
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"jwt expired"}"""))
            server.enqueue(MockResponse().setBody("""{"transaction":{"id":"payment","amount":85.0}}"""))
            assertNull(repo.fetchTransactionDetail("payment", "actor", "venue"))
            assertEquals(1, server.requestCount)
        }
    }

    // D4 H7 (Codex plan r1 #7): la misma persona salió y volvió a entrar mientras se renovaba. Es OTRA sesión: la
    // lectura vieja no se repite con la nueva aunque coincidan persona y sucursal.
    @Test fun `same actor re-entering during renewal is another session and never retries`() = runBlocking {
        val (payment, sent) = linkWithExpiredSession(switchActorDuringRefresh = false, reEnterDuringRefresh = true)
        assertNull(payment)
        assertEquals(listOf("Bearer old"), sent)
    }

    private fun linkWithExpiredSession(
        switchActorDuringRefresh: Boolean,
        reEnterDuringRefresh: Boolean = false,
    ): Pair<String?, List<String?>> = runBlocking {
        MockWebServer().use { server ->
            var token = "old"
            var actor = "actor"
            var generacion = 0L
            val storage = mockk<SecureStorage>(relaxed = true) {
                every { userId } answers { actor }
                every { venueId } returns "venue"
                every { accessToken } answers { token }
                every { selloYAccess() } answers { generacion to token }
                every { accessSiSigue(any()) } answers { if (firstArg<Long>() == generacion) token else null }
                every { siSigueLaGeneracion(any(), any()) } answers { firstArg<Long>() == generacion }
            }
            // Hace lo que TokenRefreshAuthenticator con NO_AUTO_RETRY: renueva la sesión y no reenvía.
            val client = OkHttpClient.Builder().authenticator { _, _ ->
                token = "new"
                if (switchActorDuringRefresh) actor = "other"
                if (reEnterDuringRefresh) generacion++
                null
            }.build()
            val repo = TransactionRepository(storage, client).apply { baseUrl = server.url("/api/v1").toString().trimEnd('/') }
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"jwt expired"}"""))
            server.enqueue(MockResponse().setBody("""{"order":{"id":"order","payments":[{"id":"payment","status":"COMPLETED"}]}}"""))
            val payment = repo.paymentIdForExchangeOrder("order", "actor", "venue")
            payment to List(server.requestCount) { server.takeRequest().getHeader("Authorization") }
        }
    }
}
