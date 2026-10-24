package com.avoqado.pos.transactions

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.AuthInterceptor
import com.avoqado.pos.core.data.network.ErrorNotifier
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.core.data.network.ManagerOverrideCoordinator
import com.avoqado.pos.transactions.data.TransactionRepository
import com.avoqado.pos.transactions.data.model.PendingCustomerAssignment
import io.mockk.every
import io.mockk.verify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TransactionCustomerAssignmentHttpTest {
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val pending = PendingCustomerAssignment("actor-a", "venue-a", "payment-a", "customer-a", "Ana")

    private fun repository(server: MockWebServer, client: OkHttpClient = OkHttpClient()): TransactionRepository {
        every { storage.userId } returns "actor-a"
        every { storage.venueId } returns "venue-a"
        every { storage.accessToken } returns "token-a"
        return TransactionRepository(storage, client).apply { baseUrl = server.url("/api/v1").toString().trimEnd('/') }
    }

    @Test fun `historical request sends only customer with local errors and no auth replay`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val result = repository(server).assignCustomer(pending)
            assertTrue(result.isSuccess)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/v1/mobile/venues/venue-a/transactions/payment-a/customer", request.path)
            assertEquals("""{"customerId":"customer-a"}""", request.body.readUtf8())
            assertEquals("Bearer token-a", request.getHeader("Authorization"))
            assertNotNull(request.getHeader("X-Avoqado-Local-Error"))
            assertNotNull(request.getHeader("X-No-Auto-Retry"))
        }
    }

    @Test fun `identity change inside shared auth interceptor cannot send mutation as new actor`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val client = OkHttpClient.Builder().addInterceptor {
                every { storage.userId } returns "actor-b"
                every { storage.accessToken } returns "token-b"
                it.proceed(it.request().newBuilder().header("Authorization", "Bearer token-b").build())
            }.build()
            val result = repository(server, client).assignCustomer(pending)
            assertTrue(result.isFailure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `token remains captured when shared auth interceptor overwrites request token`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val client = OkHttpClient.Builder().addInterceptor {
                it.proceed(it.request().newBuilder().header("Authorization", "Bearer token-other").build())
            }.build()
            assertTrue(repository(server, client).assignCustomer(pending).isSuccess)
            assertEquals("Bearer token-a", server.takeRequest().getHeader("Authorization"))
        }
    }

    @Test fun `unauthorized historical request cannot invoke authenticator or repeat post`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            val refreshed = AtomicInteger()
            val client = OkHttpClient.Builder().authenticator(Authenticator { _, response ->
                refreshed.incrementAndGet(); response.request.newBuilder().header("Authorization", "Bearer refreshed").build()
            }).build()
            assertTrue(repository(server, client).assignCustomer(pending).isFailure)
            assertEquals(1, server.requestCount); assertEquals(0, refreshed.get())
        }
    }

    @Test fun `forbidden request never invokes PIN even if server mistakenly advertises override`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"Forbidden","required":"orders:attach-customer","overridable":true}"""))
            val coordinator = mockk<ManagerOverrideCoordinator>()
            // Strict mock deliberately has no answer for awaitToken: using PIN would fail this real HTTP flow.
            val client = OkHttpClient.Builder().addInterceptor(ForbiddenInterceptor(ErrorNotifier(), coordinator)).build()
            val result = repository(server, client).assignCustomer(pending)
            assertTrue(result.isFailure); assertEquals(1, server.requestCount)
            verify(exactly = 0) { coordinator.awaitToken(any()) }
        }
    }

    @Test fun `foreign actor or venue cannot make a request`() = runBlocking {
        MockWebServer().use { server ->
            val repository = repository(server)
            assertTrue(repository.assignCustomer(pending.copy(actorId = "actor-b")).isFailure)
            assertTrue(repository.assignCustomer(pending.copy(venueId = "venue-b")).isFailure)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `conflict is reported without another request`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"Esta venta ya tiene otro cliente asignado"}"""))
            val result = repository(server).assignCustomer(pending)
            assertTrue(result.isFailure)
            assertEquals("Esta venta ya tiene otro cliente asignado", result.exceptionOrNull()?.message)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `redirect cannot forward a confirmed mutation and captured token to another host`() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/steal")))
                other.enqueue(MockResponse().setBody("{}"))
                assertTrue(repository(server).assignCustomer(pending).isFailure)
                assertEquals(1, server.requestCount); assertEquals(0, other.requestCount)
            }
        }
    }

    @Test fun `historical detail forbidden cannot open manager PIN during confirmation`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"Forbidden","required":"payments:read","overridable":true}"""))
            val coordinator = mockk<ManagerOverrideCoordinator>()
            val pinRequests = AtomicInteger()
            every { coordinator.awaitToken(any()) } answers { pinRequests.incrementAndGet(); null }
            val client = OkHttpClient.Builder().addInterceptor(ForbiddenInterceptor(ErrorNotifier(), coordinator)).build()
            assertNull(repository(server, client).fetchTransactionDetail("payment-a"))
            assertEquals("a historical read must not request manager authority", 0, pinRequests.get())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `historical detail unauthorized cannot refresh or replay under another actor`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
            server.enqueue(MockResponse().setBody("""{"transaction":{"id":"payment-a"}}"""))
            val refreshed = AtomicInteger()
            val client = OkHttpClient.Builder().authenticator(Authenticator { _, response ->
                refreshed.incrementAndGet()
                every { storage.userId } returns "actor-b"
                response.request.newBuilder().header("Authorization", "Bearer token-b").build()
            }).build()
            assertNull(repository(server, client).fetchTransactionDetail("payment-a"))
            assertEquals(0, refreshed.get())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `historical detail cannot use identity changed inside shared interceptor`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"transaction":{"id":"payment-a"}}"""))
            val client = OkHttpClient.Builder().addInterceptor {
                every { storage.userId } returns "actor-b"
                it.proceed(it.request().newBuilder().header("Authorization", "Bearer token-b").build())
            }.build()
            assertNull(repository(server, client).fetchTransactionDetail("payment-a"))
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun `historical detail fixes credential and request scope while reading`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"transaction":{"id":"payment-a"}}"""))
            val client = OkHttpClient.Builder().addInterceptor {
                it.proceed(it.request().newBuilder().header("Authorization", "Bearer token-other").build())
            }.build()
            assertEquals("payment-a", repository(server, client).fetchTransactionDetail("payment-a")?.id)
            val request = server.takeRequest()
            assertEquals("Bearer token-a", request.getHeader("Authorization"))
            assertNotNull(request.getHeader("X-Avoqado-Local-Error"))
            assertNotNull(request.getHeader("X-No-Auto-Retry"))
        }
    }

    @Test fun `historical detail refuses credential redirect to another host`() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", other.url("/other-detail")))
                other.enqueue(MockResponse().setBody("""{"transaction":{"id":"payment-a"}}"""))
                assertNull(repository(server).fetchTransactionDetail("payment-a"))
                assertEquals(0, other.requestCount)
            }
        }
    }

    @Test fun `explicit historical read scope refuses another actor or venue before network`() = runBlocking {
        MockWebServer().use { server ->
            val repository = repository(server)
            assertNull(repository.fetchTransactionDetail("payment-a", "actor-b", "venue-a"))
            assertNull(repository.fetchTransactionDetail("payment-a", "actor-a", "venue-b"))
            assertEquals(0, server.requestCount)
        }
    }

    // D3 (23-oct): el servidor cerró una conexión que el cliente compartido aún tenía guardada, y el GET
    // del detalle falló con «unexpected end of stream»: la venta no abría y el POS se puso «Sin conexión».
    private fun primeStaleConnection(server: MockWebServer, client: OkHttpClient) {
        server.enqueue(MockResponse().setBody("{}").setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_END))
        client.newCall(okhttp3.Request.Builder().url(server.url("/api/v1/otra")).build()).execute().close()
        Thread.sleep(200)
    }

    @Test fun `historical detail survives a pooled connection the server already closed`() = runBlocking {
        MockWebServer().use { server ->
            val client = OkHttpClient()
            primeStaleConnection(server, client)
            server.enqueue(MockResponse().setBody("""{"transaction":{"id":"payment-a"}}"""))
            assertEquals("payment-a", repository(server, client).fetchTransactionDetail("payment-a")?.id)
        }
    }

    @Test fun `assignment survives a pooled connection the server already closed and posts once`() = runBlocking {
        MockWebServer().use { server ->
            val client = OkHttpClient()
            primeStaleConnection(server, client)
            server.enqueue(MockResponse().setBody("{}"))
            assertTrue(repository(server, client).assignCustomer(pending).isSuccess)
            server.takeRequest()
            assertEquals("POST", server.takeRequest().method)
            assertEquals(2, server.requestCount)
        }
    }

    // Codex r3: OkHttp repite un POST ante «503 Retry-After: 0» aunque retryOnConnectionFailure sea false.
    @Test fun `server asking to retry now cannot make the assignment post twice`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("{}"))
            assertTrue(repository(server).assignCustomer(pending).isFailure)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun `historical read result is discarded if actor changes while response arrives`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    every { storage.userId } returns "actor-b"
                    return MockResponse().setBody("""{"transaction":{"id":"payment-a"}}""")
                }
            }
            assertNull(repository(server).fetchTransactionDetail("payment-a", "actor-a", "venue-a"))
            assertEquals("Bearer token-a", server.takeRequest().getHeader("Authorization"))
        }
    }
}
