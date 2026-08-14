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

    // Estos dos tests afirmaban lo contrario hasta 2026-08-13: que un cuerpo
    // inválido o vacío DEBÍA presentarse como falta de permisos. Esa premisa era
    // el bug — un 403 sin nuestro cuerpo no lo mandó nuestra API. Se invierten a
    // propósito, no se relajan.

    @Test
    fun `403 con cuerpo invalido no se presenta como falta de permiso`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("not json"),
        )

        client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertNull(errorNotifier.forbiddenError.value)
    }

    @Test
    fun `403 sin cuerpo no se presenta como falta de permiso`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody(""),
        )

        client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertNull(errorNotifier.forbiddenError.value)
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

    // MARK: - Un 403 que NO viene de nuestra API no es falta de permiso
    //
    // Medido en hardware el 2026-08-13: con el túnel de ngrok caído, la app
    // decía "No tienes permisos para esta acción" y mandaba a buscar un
    // problema de roles que no existía. En producción la API vive detrás de
    // Cloudflare, que también responde 403 con HTML (WAF, rate limit, reglas
    // de país), y las terminales de PlayTelecom operan dentro de redes
    // corporativas de Walmart con proxy — el mismo 403 ajeno.

    @Test
    fun `403 con HTML de ngrok no se presenta como falta de permiso`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "text/html")
                .setBody("<!DOCTYPE html><html><body>ERR_NGROK_727</body></html>"),
        )

        client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertNull(errorNotifier.forbiddenError.value)
    }

    @Test
    fun `403 con HTML de Cloudflare no se presenta como falta de permiso`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody("<!DOCTYPE html><html><head><title>Attention Required! | Cloudflare</title></head></html>"),
        )

        client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertNull(errorNotifier.forbiddenError.value)
    }

    @Test
    fun `403 con cuerpo vacio no se presenta como falta de permiso`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody(""))

        client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertNull(errorNotifier.forbiddenError.value)
    }

    @Test
    fun `403 con JSON ajeno sin nuestros campos no se presenta como falta de permiso`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail":"blocked by policy"}"""),
        )

        client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertNull(errorNotifier.forbiddenError.value)
    }

    // MARK: - El candado de PLAN tampoco es falta de permiso
    //
    // Sólo el 403 del candado de plan trae `featureCode`. Presentarlo como
    // "no tienes permisos" manda al mesero a pedirle permisos a su jefe en vez
    // de al upsell — el bug silencioso que iOS ya documenta haber evitado
    // (`APIClient.swift`), y que Android sí tenía.

    @Test
    fun `403 de candado de plan no se presenta como falta de permiso`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Forbidden","message":"Feature not available","featureCode":"INVENTORY_TRACKING"}"""),
        )

        client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertNull(errorNotifier.forbiddenError.value)
    }

    @Test
    fun `403 de permiso real sigue avisando`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Forbidden","message":"Permission 'orders:void' required","required":"orders:void"}"""),
        )

        client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertEquals(
            "No tienes permiso para hacer esto. Pídele a un administrador que te active «orders:void».",
            errorNotifier.forbiddenError.value,
        )
    }
}
