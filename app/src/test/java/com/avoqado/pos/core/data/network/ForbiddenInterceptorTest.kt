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
    fun `403 with valid JSON sets error message from response`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("""{"error":"Forbidden","message":"Permission 'orders:create' required","required":"orders:create","userRole":"VIEWER"}"""),
        )

        client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertEquals("Permission 'orders:create' required", errorNotifier.forbiddenError.value)
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
