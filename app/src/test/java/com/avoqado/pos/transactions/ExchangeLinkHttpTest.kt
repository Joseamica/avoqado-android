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
            }
            val repo = TransactionRepository(storage, OkHttpClient()).apply { baseUrl = server.url("/api/v1").toString().trimEnd('/') }
            server.enqueue(MockResponse().setBody("""{"success":true,"order":{"id":"order","payments":[{"id":"refund","status":"REFUNDED"},{"id":"payment","status":"COMPLETED"}]}}"""))
            assertEquals("payment", repo.paymentIdForExchangeOrder("order", "actor", "venue"))
            assertEquals("/api/v1/mobile/venues/venue/orders/order", server.takeRequest().path)
            server.enqueue(MockResponse().setBody("""{"order":{"id":"other","payments":[{"id":"payment","status":"COMPLETED"}]}}"""))
            assertNull(repo.paymentIdForExchangeOrder("order", "actor", "venue"))
        }
    }
}
