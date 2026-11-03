package com.avoqado.pos.timeclock

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.timeclock.data.TimeEntryRepository
import io.mockk.every
import io.mockk.mockk
import java.util.Collections
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimeEntryRepositoryHttpTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `las cinco rutas envian el PIN sin escribirlo en el registro HTTP`() = runBlocking {
        val storage = mockk<SecureStorage>()
        every { storage.venueId } returns "venue-1"
        val pin = "81726394" // Ficticio, sólo para transporte local.
        val lines = Collections.synchronizedList(mutableListOf<String>())
        val logger = HttpLoggingInterceptor { lines.add(it) }
            .apply { level = HttpLoggingInterceptor.Level.BODY }
        val client = OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(11, TimeUnit.SECONDS)
            .writeTimeout(13, TimeUnit.SECONDS)
            .addInterceptor(logger)
            .addInterceptor { chain ->
                assertEquals(7000, chain.connectTimeoutMillis())
                assertEquals(11000, chain.readTimeoutMillis())
                assertEquals(13000, chain.writeTimeoutMillis())
                val original = chain.request()
                // Reescribe antes de proceed: ninguna petición sale al backend real.
                chain.proceed(original.newBuilder()
                    .url(server.url(original.url.encodedPath))
                    .header("X-Test-Chain", "preserved")
                    .build())
            }
            .build()

        server.enqueue(MockResponse().setBody("{}"))
        client.newCall(Request.Builder().url(server.url("/control"))
            .post("""{"control":"registro-activo"}""".toRequestBody("application/json".toMediaType()))
            .build()).execute().use { assertTrue(it.isSuccessful) }
        assertTrue("control positivo: BODY registra cuerpos", lines.any { "registro-activo" in it })
        assertEquals("/control", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        lines.clear()

        server.enqueue(MockResponse().setBody(
            """{"success":true,"staff":{"id":"staff-1","firstName":"Ana","lastName":"Prueba"},"currentEntry":null}""",
        ))
        repeat(4) { server.enqueue(MockResponse().setBody("""{"success":true}""")) }
        val repository = TimeEntryRepository(storage, client)
        assertTrue("identify funciona", repository.identifyStaff(pin).isSuccess)
        assertTrue("clock-in funciona", repository.clockIn(pin, "entrada").isSuccess)
        assertTrue("clock-out funciona", repository.clockOut(pin, "salida").isSuccess)
        assertTrue("break/start funciona", repository.startBreak(pin, "MEAL").isSuccess)
        assertTrue("break/end funciona", repository.endBreak(pin, "regreso").isSuccess)

        listOf("identify", "clock-in", "clock-out", "break/start", "break/end").forEach { action ->
            val request = requireNotNull(server.takeRequest(2, TimeUnit.SECONDS)) { "falta $action" }
            assertEquals(URI(ApiConstants.BASE_URL).rawPath + "/mobile/venues/venue-1/time-clock/$action", request.path)
            assertEquals("POST", request.method)
            assertEquals("preserved", request.getHeader("X-Test-Chain"))
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertTrue("el PIN llega en $action", body["pin"]?.jsonPrimitive?.content == pin)
            val extras = when (action) {
                "clock-in" -> mapOf("note" to "entrada")
                "clock-out" -> mapOf("note" to "salida")
                "break/start" -> mapOf("breakType" to "MEAL")
                "break/end" -> mapOf("note" to "regreso")
                else -> emptyMap()
            }
            assertEquals(setOf("pin") + extras.keys, body.keys)
            extras.forEach { (key, value) -> assertEquals(value, body[key]?.jsonPrimitive?.content) }
        }
        assertEquals("control y cinco peticiones protegidas", 6, server.requestCount)
        assertTrue("el registro HTTP no contiene el PIN ni su clave", lines.none { pin in it || "\"pin\"" in it })
    }
}
