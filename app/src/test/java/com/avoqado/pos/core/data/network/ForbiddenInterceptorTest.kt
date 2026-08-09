package com.avoqado.pos.core.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ForbiddenInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var errorNotifier: ErrorNotifier
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        errorNotifier = ErrorNotifier()
        client = OkHttpClient.Builder()
            .addInterceptor(ForbiddenInterceptor(errorNotifier))
            .build()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `403 traduce el mensaje del server sin perder el codigo del permiso`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":"Forbidden","message":"Permission 'orders:create' required","required":"orders:create","userRole":"VIEWER"}"""),
        )

        client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        // El mensaje del server es para depurar ("Permission 'orders:create'
        // required"); lo que ve el mesero tiene que estar en su idioma y decirle qué
        // hacer, sin perder el código que el administrador necesita para activarlo.
        val shown = errorNotifier.forbiddenError.value
        assertEquals(
            "No tienes permiso para hacer esto. Pídele a un administrador que te active «orders:create».",
            shown,
        )
    }

    @Test
    fun `403 with invalid JSON sets fallback message`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("not json"),
        )

        client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertEquals("No tienes permisos para esta acción", errorNotifier.forbiddenError.value)
    }

    @Test
    fun `403 with empty body sets fallback message`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody(""),
        )

        client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertEquals("No tienes permisos para esta acción", errorNotifier.forbiddenError.value)
    }

    @Test
    fun `403 handled by the feature does not open the global permission dialog`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody(
                    """{"success":false,"data":null,"error":{"code":"CHECKOUT_TERMINAL_MISMATCH","message":"Esta terminal no está configurada como caja de vales.","retryable":false}}""",
                ),
        )

        val request = Request.Builder()
            .url(server.url("/mobile/venues/venue-1/area-ticket-checkouts"))
            .header("X-Avoqado-Local-Error", "1")
            .build()
        client.newCall(request).execute()

        assertNull(errorNotifier.forbiddenError.value)
    }

    @Test
    fun `200 response does not set error`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"ok":true}"""),
        )

        client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertNull(errorNotifier.forbiddenError.value)
    }

    @Test
    fun `401 response does not set error`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":"Unauthorized"}"""),
        )

        client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertNull(errorNotifier.forbiddenError.value)
    }

    @Test
    fun `clear resets error to null`() {
        errorNotifier.notify("some error")
        assertEquals("some error", errorNotifier.forbiddenError.value)

        errorNotifier.clear()
        assertNull(errorNotifier.forbiddenError.value)
    }
}
