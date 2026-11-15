package com.avoqado.pos.timeclock

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.timeclock.data.TimeEntryRepository
import io.mockk.every
import io.mockk.mockk
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * «Foto de entrada» del checador (encargo b0c1a1, 15-nov). El identify dice si el negocio la pide, y la Entrada manda la
 * selfie en la MISMA petición (`checkInPhoto`, JPEG en base64). Sin foto, la petición es la de siempre.
 */
class FotoDeEntradaHttpTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: TimeEntryRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val storage = mockk<SecureStorage>()
        every { storage.venueId } returns "venue-1"
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val original = chain.request()
                chain.proceed(original.newBuilder().url(server.url(original.url.encodedPath)).build())
            }
            .build()
        repository = TimeEntryRepository(storage, client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun cuerpoEnviado(): Map<String, String?> {
        val body = server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()
        return Json.parseToJsonElement(body).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    @Test
    fun `el identify trae requireClockInPhoto del negocio`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"success":true,"staff":{"id":"s1","firstName":"Ana","lastName":"Lopez"},"currentEntry":null,"requireClockInPhoto":true}""",
        ))
        val staff = repository.identifyStaff("1234").getOrThrow()
        assertTrue(staff.requireClockInPhoto)
        assertFalse(staff.clockedIn)
    }

    @Test
    fun `regresion - un servidor viejo sin el campo no pide foto`() = runBlocking {
        server.enqueue(MockResponse().setBody(
            """{"success":true,"staff":{"id":"s1","firstName":"Ana","lastName":"Lopez"},"currentEntry":null}""",
        ))
        assertFalse(repository.identifyStaff("1234").getOrThrow().requireClockInPhoto)
    }

    @Test
    fun `la Entrada con selfie manda checkInPhoto en base64 junto con el PIN`() = runBlocking {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"success":true,"timeEntry":{"id":"te-1","checkInPhotoUrl":"private/dev/attendance/v/s/1_entrada.jpg"}}""",
        ))

        assertEquals(true, repository.clockInConSelfie("1234", null, jpeg).getOrThrow())

        val enviado = cuerpoEnviado()
        assertEquals("1234", enviado["pin"])
        assertEquals(Base64.getEncoder().encodeToString(jpeg), enviado["checkInPhoto"])
    }

    @Test
    fun `regresion - la Entrada sin selfie no manda el campo`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true}"""))
        assertTrue(repository.clockIn("1234", note = "llegue").isSuccess)
        val enviado = cuerpoEnviado()
        assertFalse(enviado.containsKey("checkInPhoto"))
        assertEquals("llegue", enviado["note"])
    }

    @Test
    fun `un rechazo del servidor llega con su mensaje`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"La foto de entrada no es válida."}"""))
        val r = repository.clockInConSelfie("1234", null, byteArrayOf(1, 2, 3))
        assertTrue(r.isFailure)
        assertEquals("La foto de entrada no es válida.", r.exceptionOrNull()?.message)
    }

    @Test
    fun `si el servidor no pudo guardar la foto el acuse no dice con tu foto`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"timeEntry":{"id":"te-1","checkInPhotoUrl":null}}"""))
        assertEquals(false, repository.clockInConSelfie("1234", null, byteArrayOf(1, 2, 3)).getOrThrow())
    }

    @Test
    fun `sin foto - aparato sin camara - la entrada sale bien y no hay foto guardada`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"success":true,"timeEntry":{"id":"te-1"}}"""))
        assertEquals(false, repository.clockInConSelfie("1234", "nota", null).getOrThrow())
        assertFalse(cuerpoEnviado().containsKey("checkInPhoto"))
    }
}
