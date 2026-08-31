package com.avoqado.pos.customerdisplay

import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DisplayModeRemoteRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: DisplayModeRemoteRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repository = repositoryWith(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `capability PUT uses exact encoded path body and supplied identity interceptor`() = runTest {
        val sawUnadornedRepositoryRequest = AtomicBoolean(false)
        val suppliedClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val incoming = chain.request()
                sawUnadornedRepositoryRequest.set(
                    incoming.headers("X-Device-ID").isEmpty() &&
                        incoming.headers("X-Device-Platform").isEmpty(),
                )
                chain.proceed(
                    incoming.newBuilder()
                        .addHeader("X-Device-ID", "device-from-shared-client")
                        .addHeader("X-Device-Platform", "ANDROID")
                        .build(),
                )
            }
            .build()
        val subject = repositoryWith(suppliedClient)
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))

        val result = subject.reportCapabilities(
            venueId = "venue /café",
            snapshot = DisplayCapabilitySnapshot(present = true, invertible = false),
        )

        assertEquals(DisplayModeRemoteOutcome.Success(Unit), result)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PUT", request.method)
        assertEquals(
            "/api/v1/mobile/venues/venue%20%2Fcaf%C3%A9/device-capabilities",
            request.path,
        )
        assertEquals(
            "{\"customerDisplay\":{\"present\":true,\"invertible\":false}," +
                "\"displayModeProtocolVersion\":1}",
            request.body.readUtf8(),
        )
        assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
        assertEquals("1", request.getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))
        assertTrue(sawUnadornedRepositoryRequest.get())
        assertEquals(listOf("device-from-shared-client"), request.headers.values("X-Device-ID"))
        assertEquals(listOf("ANDROID"), request.headers.values("X-Device-Platform"))
    }

    @Test
    fun `request GET ignores unknown fields and maps omitted status to PENDING`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "futureTop": true,
                  "data": {
                    "terminalId": "terminal-1",
                    "futureData": 99,
                    "request": {
                      "requestId": "request-1",
                      "desiredInverted": true,
                      "requestedAt": "2026-08-29T17:00:00Z",
                      "expiresAt": "2026-08-29T17:05:00Z",
                      "futureRequest": "ignored"
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        val result = repository.fetchDisplayModeRequest("venue /café")

        assertTrue(result is DisplayModeRemoteOutcome.Success)
        val data = (result as DisplayModeRemoteOutcome.Success).data
        assertEquals("terminal-1", data.terminalId)
        assertEquals("request-1", data.request.requestId)
        assertTrue(data.request.desiredInverted)
        assertEquals(RemoteDisplayModeRequest.STATUS_PENDING, data.request.status)
        assertEquals(Instant.parse("2026-08-29T17:00:00Z"), data.request.requestedAt)
        assertEquals(Instant.parse("2026-08-29T17:05:00Z"), data.request.expiresAt)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals(
            "/api/v1/mobile/venues/venue%20%2Fcaf%C3%A9/display-mode-request",
            request.path,
        )
        assertEquals(0L, request.bodySize)
        assertEquals("1", request.getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))
    }

    @Test
    fun `request null returns NoRequest while retaining terminal binding`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                "{\"data\":{\"terminalId\":\"terminal-no-request\",\"request\":null}}",
            ),
        )

        val result = repository.fetchDisplayModeRequest("venue-1")

        assertEquals(DisplayModeRemoteOutcome.NoRequest("terminal-no-request"), result)
    }

    @Test
    fun `APPLIED ACK uses exact PATCH path and omits result code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = repository.acknowledgeDisplayMode(
            venueId = "venue-1",
            terminalId = "terminal /uno",
            requestId = "request-1",
            customerDisplayInverted = true,
            acknowledgement = DisplayModeAcknowledgement.Applied,
        )

        assertEquals(DisplayModeRemoteOutcome.Success(Unit), result)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("PATCH", request.method)
        assertEquals(
            "/api/v1/mobile/venues/venue-1/terminals/terminal%20%2Funo/display-mode",
            request.path,
        )
        assertEquals(
            "{\"customerDisplayInverted\":true,\"requestId\":\"request-1\"," +
                "\"outcome\":\"APPLIED\"}",
            request.body.readUtf8(),
        )
        assertEquals("1", request.getHeader(ForbiddenInterceptor.BACKGROUND_HEADER))
    }

    @Test
    fun `REJECTED ACK uses exact supported result code`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val result = repository.acknowledgeDisplayMode(
            venueId = "venue-1",
            terminalId = "terminal-1",
            requestId = "request-2",
            customerDisplayInverted = false,
            acknowledgement = DisplayModeAcknowledgement.Rejected(
                DisplayModeAckResultCode.LOCAL_OVERRIDE,
            ),
        )

        assertEquals(DisplayModeRemoteOutcome.Success(Unit), result)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals(
            "{\"customerDisplayInverted\":false,\"requestId\":\"request-2\"," +
                "\"outcome\":\"REJECTED\",\"resultCode\":\"LOCAL_OVERRIDE\"}",
            request.body.readUtf8(),
        )
    }

    @Test
    fun `401 becomes SessionInvalid without throwing`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setBody("{\"code\":\"SESSION_EXPIRED\",\"message\":\"expired\"}"),
        )

        assertEquals(
            DisplayModeRemoteOutcome.SessionInvalid,
            repository.fetchDisplayModeRequest("venue-1"),
        )
    }

    @Test
    fun `stable 409 and 422 preserve bounded server error details`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("{\"code\":\"REQUEST_CONFLICT\",\"message\":\"Request changed\"}"),
        )
        val oversizedCode = "C".repeat(400)
        val oversizedMessage = "M".repeat(2_000)
        server.enqueue(
            MockResponse().setResponseCode(422)
                .setBody("{\"error\":{\"code\":\"$oversizedCode\",\"message\":\"$oversizedMessage\"}}"),
        )

        assertEquals(
            DisplayModeRemoteOutcome.Rejected(
                status = 409,
                code = "REQUEST_CONFLICT",
                message = "Request changed",
            ),
            repository.fetchDisplayModeRequest("venue-1"),
        )
        val bounded = repository.fetchDisplayModeRequest("venue-1")
        assertTrue(bounded is DisplayModeRemoteOutcome.Rejected)
        bounded as DisplayModeRemoteOutcome.Rejected
        assertEquals(422, bounded.status)
        assertTrue(bounded.code!!.length <= 128)
        assertTrue(bounded.message!!.length <= 512)
        assertTrue(oversizedCode.startsWith(bounded.code!!))
        assertTrue(oversizedMessage.startsWith(bounded.message!!))
    }

    @Test
    fun `400 403 and 404 are stable Rejected outcomes`() = runTest {
        listOf(400, 403, 404).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("{}"))
            assertEquals(
                DisplayModeRemoteOutcome.Rejected(status, null, null),
                repository.reportCapabilities(
                    "venue-1",
                    DisplayCapabilitySnapshot(present = false, invertible = false),
                ),
            )
        }
    }

    @Test
    fun `408 429 5xx and unexpected status are Retryable`() = runTest {
        listOf(408, 429, 500, 503, 302).forEach { status ->
            server.enqueue(MockResponse().setResponseCode(status).setBody("{}"))
            // OkHttp retries a first 408 once by design. The second response is
            // the status that reaches the repository for classification.
            if (status == 408) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("{}"))
            }
            assertEquals(
                DisplayModeRemoteOutcome.Retryable(status),
                repository.reportCapabilities(
                    "venue-1",
                    DisplayCapabilitySnapshot(present = false, invertible = false),
                ),
            )
        }
    }

    @Test
    fun `IO and decode failures are Retryable without exception escape`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val ioResult = repository.fetchDisplayModeRequest("venue-1")
        assertTrue(ioResult is DisplayModeRemoteOutcome.Retryable)
        assertNull((ioResult as DisplayModeRemoteOutcome.Retryable).status)

        server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))
        assertEquals(
            DisplayModeRemoteOutcome.Retryable(200),
            repository.fetchDisplayModeRequest("venue-1"),
        )
    }

    @Test
    fun `poll requires bounded nonblank ids and both valid ISO instants`() = runTest {
        val overLimit = "x".repeat(129)
        val invalidBodies = listOf(
            // requestedAt is part of the exact server request contract.
            """{"data":{"terminalId":"terminal-1","request":{"requestId":"request-1","desiredInverted":true,"expiresAt":"2026-08-29T17:05:00Z"}}}""",
            """{"data":{"request":null}}""",
            """{"data":{"terminalId":"terminal-1","request":{"desiredInverted":true,"requestedAt":"2026-08-29T17:00:00Z","expiresAt":"2026-08-29T17:05:00Z"}}}""",
            """{"data":{"terminalId":" ","request":null}}""",
            """{"data":{"terminalId":"$overLimit","request":null}}""",
            """{"data":{"terminalId":"terminal-1","request":{"requestId":" ","desiredInverted":true,"requestedAt":"2026-08-29T17:00:00Z","expiresAt":"2026-08-29T17:05:00Z"}}}""",
            """{"data":{"terminalId":"terminal-1","request":{"requestId":"$overLimit","desiredInverted":true,"requestedAt":"2026-08-29T17:00:00Z","expiresAt":"2026-08-29T17:05:00Z"}}}""",
            """{"data":{"terminalId":"terminal-1","request":{"requestId":"request-1","desiredInverted":true,"requestedAt":" ","expiresAt":"2026-08-29T17:05:00Z"}}}""",
            """{"data":{"terminalId":"terminal-1","request":{"requestId":"request-1","desiredInverted":true,"requestedAt":"not-an-instant","expiresAt":"2026-08-29T17:05:00Z"}}}""",
            """{"data":{"terminalId":"terminal-1","request":{"requestId":"request-1","desiredInverted":true,"requestedAt":"2026-08-29T17:00:00Z","expiresAt":"tomorrow"}}}""",
        )

        invalidBodies.forEach { body ->
            server.enqueue(MockResponse().setResponseCode(200).setBody(body))
            assertEquals(
                DisplayModeRemoteOutcome.Retryable(200),
                repository.fetchDisplayModeRequest("venue-1"),
            )
        }
    }

    @Test
    fun `oversized response is bounded closed and Retryable before decode`() = runTest {
        val body = TrackingResponseBody("x".repeat(100_000))
        val suppliedClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
            .build()
        val subject = DisplayModeRemoteRepository(suppliedClient) {
            "https://unused.example/api/v1"
        }

        assertEquals(
            DisplayModeRemoteOutcome.Retryable(200),
            subject.fetchDisplayModeRequest("venue-1"),
        )
        assertTrue(body.closed.get())
        // The repository retains only 64 KiB + one sentinel byte. Okio's
        // BufferedSource may prefetch the remainder of that 8 KiB segment,
        // but it still never consumes the full 100 KiB response.
        assertTrue(body.bytesRead.get() <= 73_728L)
    }

    @Test
    fun `response body is closed for successful calls`() = runTest {
        val body = TrackingResponseBody("{}")
        val suppliedClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
            .build()
        val subject = DisplayModeRemoteRepository(suppliedClient) {
            "https://unused.example/api/v1"
        }

        assertEquals(
            DisplayModeRemoteOutcome.Success(Unit),
            subject.reportCapabilities(
                "venue-1",
                DisplayCapabilitySnapshot(present = true, invertible = true),
            ),
        )
        assertTrue(body.closed.get())
    }

    @Test
    fun `cancelling coroutine cancels in-flight OkHttp Call and preserves cancellation`() = runTest {
        val callCancelled = AtomicBoolean(false)
        val suppliedClient = OkHttpClient.Builder()
            .eventListener(
                object : EventListener() {
                    override fun canceled(call: Call) {
                        callCancelled.set(true)
                    }
                },
            )
            .build()
        val subject = repositoryWith(suppliedClient)
        server.enqueue(
            MockResponse()
                .setBody("{\"data\":{\"terminalId\":\"terminal-a\",\"request\":null}}")
                .setHeadersDelay(1, TimeUnit.SECONDS),
        )

        val deferred = async { subject.fetchDisplayModeRequest("venue-1") }
        runCurrent()
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS) != null)

        deferred.cancel(CancellationException("stop"))
        runCurrent()
        assertTrue("OkHttp Call.cancel() was not invoked", callCancelled.get())

        var observed: CancellationException? = null
        try {
            deferred.await()
        } catch (error: CancellationException) {
            observed = error
        }
        assertTrue(observed is CancellationException)
        assertEquals("stop", observed?.message)
    }

    private fun repositoryWith(client: OkHttpClient): DisplayModeRemoteRepository =
        DisplayModeRemoteRepository(client) {
            server.url("/api/v1").toString().removeSuffix("/")
        }

    private class TrackingResponseBody(payload: String) : ResponseBody() {
        val closed = AtomicBoolean(false)
        val bytesRead = AtomicLong(0L)
        private val trackedSource = object : ForwardingSource(Buffer().writeUtf8(payload)) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read > 0) bytesRead.addAndGet(read)
                return read
            }

            override fun close() {
                closed.set(true)
                super.close()
            }
        }.buffer()

        override fun contentType(): MediaType? = null

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = trackedSource
    }
}
