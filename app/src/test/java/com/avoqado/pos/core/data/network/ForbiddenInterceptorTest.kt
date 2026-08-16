package com.avoqado.pos.core.data.network

import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ForbiddenInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var errorNotifier: ErrorNotifier
    private lateinit var client: OkHttpClient

    /**
     * Doble de prueba: responde el token que le digamos, sin UI ni red.
     *
     * `awaitToken` es el punto donde el interceptor BLOQUEA su hilo esperando a
     * que alguien teclee. En el test se resuelve al instante.
     */
    private class FakeCoordinator(
        private val tokenToReturn: String?,
    ) : ManagerOverrideCoordinator(mockk(relaxed = true)) {
        var askedFor: String? = null

        override fun awaitToken(permission: String): String? {
            askedFor = permission
            return tokenToReturn
        }
    }

    private fun clientWith(coordinator: ManagerOverrideCoordinator): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(ForbiddenInterceptor(errorNotifier, coordinator))
            .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        errorNotifier = ErrorNotifier()
        client = clientWith(FakeCoordinator(null))
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
        // hacer, nombrando la ACCIÓN que le bloquearon y nunca el código técnico.
        val shown = errorNotifier.forbiddenError.value
        assertEquals(
            "No tienes permiso para hacer esto. Pídele a un administrador que te active «abrir una cuenta».",
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
            "No tienes permiso para hacer esto. Pídele a un administrador que te active «anular artículos».",
            errorNotifier.forbiddenError.value,
        )
    }

    // MARK: - PIN de autorización de gerente
    //
    // El 403 `overridable` es el ÚNICO que abre el teclado. El de plan y el del
    // intermediario NO: ningún código de encargado arregla un plan sin pagar ni
    // un túnel caído, y ofrecer el teclado ahí sería un bucle sin salida.

    private val overridable403 =
        """{"error":"Forbidden","message":"Permission 'orders:merge' required","required":"orders:merge","userRole":"WAITER","overridable":true}"""

    @Test
    fun `403 overridable pide el codigo y reintenta la peticion con el header`() {
        val coordinator = FakeCoordinator("tok_abc")
        val client = clientWith(coordinator)

        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody(overridable403),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))

        val response = client.newCall(Request.Builder().url(server.url("/merge")).build()).execute()

        assertEquals(200, response.code)
        assertEquals("orders:merge", coordinator.askedFor)
        server.takeRequest()
        val retried = server.takeRequest()
        assertEquals("tok_abc", retried.getHeader("X-Permission-Override"))
        // Éxito: no se pinta el diálogo de "no tienes permiso".
        assertNull(errorNotifier.forbiddenError.value)
    }

    /**
     * 🔴 Regresión del review 2026-08-16 (el hallazgo más caro del lote).
     *
     * La ruta del dinero corre con `callTimeout(15 s)` y HEREDA este
     * interceptor. Si el teclado se abre dentro de esa llamada, un gerente que
     * tarda en llegar hace que OkHttp la cancele con `InterruptedIOException`
     * — y `OrderRepository.isQueueableError` la clasifica como fallo de red.
     * Una venta que el server RECHAZÓ por permisos terminaba encolada, pintada
     * como cobrada, sumada al corte y con comanda impresa.
     *
     * Marcada como fail-fast, el 403 tiene que volver como RESPUESTA —jamás
     * como excepción— para que el repositorio lo trate como rechazo de negocio.
     */
    @Test
    fun `una peticion fail-fast NO abre el teclado y devuelve el 403 tal cual`() {
        val coordinator = FakeCoordinator("tok_abc")
        val client = clientWith(coordinator)

        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody(overridable403),
        )

        val response = client.newCall(
            Request.Builder()
                .url(server.url("/orders"))
                .header(ForbiddenInterceptor.FAIL_FAST_HEADER, "1")
                .build(),
        ).execute()

        // El 403 llega como respuesta: el repositorio lo convierte en
        // ServerException(403), que NO es encolable.
        assertEquals(403, response.code)
        // Nadie esperó a un humano dentro de la llamada.
        assertNull(coordinator.askedFor)
        // Y el cajero SÍ se entera: no es un "no" mudo.
        assertNotNull(errorNotifier.forbiddenError.value)
        // Una sola petición: no hubo reintento.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `si el usuario cancela, el 403 llega tal cual y SIN dialogo generico`() {
        val coordinator = FakeCoordinator(null)
        val client = clientWith(coordinator)

        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody(overridable403),
        )

        val response = client.newCall(Request.Builder().url(server.url("/merge")).build()).execute()

        assertEquals(403, response.code)
        assertEquals("orders:merge", coordinator.askedFor)
        assertNull(errorNotifier.forbiddenError.value)
    }

    @Test
    fun `un 403 con el header ya puesto NO vuelve a pedir codigo (sin bucle)`() {
        val coordinator = FakeCoordinator("tok_abc")
        val client = clientWith(coordinator)

        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody(overridable403),
        )

        val response = client.newCall(
            Request.Builder().url(server.url("/merge")).header("X-Permission-Override", "tok_viejo").build(),
        ).execute()

        assertEquals(403, response.code)
        assertNull(coordinator.askedFor)
        assertEquals(
            "No tienes permiso para hacer esto. Pídele a un administrador que te active «fusionar cuentas».",
            errorNotifier.forbiddenError.value,
        )
    }

    @Test
    fun `si el token no alcanza, el reintento avisa en vez de quedarse mudo`() {
        val coordinator = FakeCoordinator("tok_quemado")
        val client = clientWith(coordinator)

        // Los dos 403 son overridable: el segundo llega YA con el header, así que
        // el teclado no se vuelve a abrir — pero el "no" tiene que verse.
        repeat(2) {
            server.enqueue(
                MockResponse().setResponseCode(403)
                    .setHeader("Content-Type", "application/json")
                    .setBody(overridable403),
            )
        }

        val response = client.newCall(Request.Builder().url(server.url("/merge")).build()).execute()

        assertEquals(403, response.code)
        assertEquals("orders:merge", coordinator.askedFor)
        assertEquals(
            "No tienes permiso para hacer esto. Pídele a un administrador que te active «fusionar cuentas».",
            errorNotifier.forbiddenError.value,
        )
    }

    @Test
    fun `403 SIN overridable sigue abriendo el dialogo de siempre`() {
        val coordinator = FakeCoordinator("tok_abc")
        val client = clientWith(coordinator)

        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Forbidden","message":"Permission 'orders:merge' required","required":"orders:merge"}"""),
        )

        client.newCall(Request.Builder().url(server.url("/merge")).build()).execute()

        assertNull(coordinator.askedFor)
        assertEquals(
            "No tienes permiso para hacer esto. Pídele a un administrador que te active «fusionar cuentas».",
            errorNotifier.forbiddenError.value,
        )
    }

    @Test
    fun `403 de plan sigue sin pedir codigo (va al upsell, no al PIN)`() {
        val coordinator = FakeCoordinator("tok_abc")
        val client = clientWith(coordinator)

        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"Forbidden","message":"Feature not available","featureCode":"INVENTORY_TRACKING","overridable":true}"""),
        )

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute()

        assertNull(coordinator.askedFor)
        assertNull(errorNotifier.forbiddenError.value)
    }

    @Test
    fun `403 de intermediario sigue sin pedir codigo (ningun PIN arregla un tunel caido)`() {
        val coordinator = FakeCoordinator("tok_abc")
        val client = clientWith(coordinator)

        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "text/html")
                .setBody("<!DOCTYPE html><html><body>tunnel down</body></html>"),
        )

        client.newCall(Request.Builder().url(server.url("/x")).build()).execute()

        assertNull(coordinator.askedFor)
        assertNull(errorNotifier.forbiddenError.value)
    }

    @Test
    fun `un 403 overridable en tarea de FONDO no abre el teclado a nadie`() {
        val coordinator = FakeCoordinator("tok_abc")
        val client = clientWith(coordinator)

        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody(overridable403),
        )

        client.newCall(
            Request.Builder().url(server.url("/merge")).header("X-Avoqado-Background", "1").build(),
        ).execute()

        // El replay del outbox corre solo: pedirle un código a nadie dejaría el
        // hilo de red bloqueado hasta el timeout. Va a cuarentena, como siempre.
        assertNull(coordinator.askedFor)
        assertNull(errorNotifier.forbiddenError.value)
    }

    /**
     * 🔴 El caso real medido el 2026-08-16, que es el 403 de permiso NORMAL —sin
     * `overridable`— y por eso no lo cubría el test de arriba.
     *
     * Un CASHIER cobrando veía en la pantalla de PROPINA el modal "No tienes
     * permiso… «tpv:read»", porque la app consulta sola qué terminales están
     * conectadas y esa ruta exige el permiso de ADMINISTRAR terminales. La
     * consulta corre sola: su "no" no puede saltar encima de una venta.
     */
    @Test
    fun `un 403 de permiso normal en tarea de FONDO tampoco saca el modal`() {
        val coordinator = FakeCoordinator("tok_abc")
        val client = clientWith(coordinator)

        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"error":"Forbidden","message":"Permission 'tpv:read' required","required":"tpv:read","userRole":"CASHIER"}""",
                ),
        )

        val response = client.newCall(
            Request.Builder()
                .url(server.url("/mobile/venues/venue-1/terminals/online"))
                .header(ForbiddenInterceptor.BACKGROUND_HEADER, "1")
                .build(),
        ).execute()

        // Ni modal, ni teclado de gerente: el 403 vuelve tal cual y quien llamó
        // decide qué hacer con él (aquí, seguir con la opción de tarjeta viva).
        assertNull(errorNotifier.forbiddenError.value)
        assertNull(coordinator.askedFor)
        assertEquals(403, response.code)
        assertEquals(1, server.requestCount)
    }

    /**
     * El contrapunto obligatorio: marcar de fondo NO puede volverse un silencio
     * general. La MISMA ruta, cuando nace de un toque ("Cobrar con terminal"),
     * sigue avisando — si no, el cajero se queda con un botón mudo.
     */
    @Test
    fun `el mismo 403 SIN la marca de fondo sigue avisando`() {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"error":"Forbidden","message":"Permission 'tpv:read' required","required":"tpv:read","userRole":"CASHIER"}""",
                ),
        )

        client.newCall(
            Request.Builder().url(server.url("/mobile/venues/venue-1/terminals/online")).build(),
        ).execute()

        // Se afirma que AVISA, no el texto exacto: cómo se nombra el permiso es
        // asunto de `ServerErrorText`/`PermissionLabels` y tiene sus propios tests.
        assertNotNull(errorNotifier.forbiddenError.value)
    }
}
