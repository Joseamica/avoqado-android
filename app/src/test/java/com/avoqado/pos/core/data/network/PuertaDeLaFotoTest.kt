package com.avoqado.pos.core.data.network

import com.avoqado.pos.core.data.sync.FotoDeMesa
import com.avoqado.pos.core.data.sync.FotosDeMesas
import com.avoqado.pos.tables.data.OrderDetail
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * D5 · B2b, punto de paso ① con el interceptor REAL sobre un servidor de verdad (MockWebServer): la foto de una mesa se borra del
 * DISCO antes de que la escritura llegue al servidor; la ronda, al volver. Las rutas y los cuerpos son los de la app.
 */
class PuertaDeLaFotoTest {
    private val dir = java.nio.file.Files.createTempDirectory("fotos").toFile()
    private val fotos = FotosDeMesas(dir)
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().addInterceptor(PuertaDeLaFoto(fotos)).build()
    /** Lo que había en DISCO (otra instancia, como tras reiniciar) cuando el servidor recibió cada petición. */
    private val fotoAlLlegar = mutableListOf<Boolean>()
    private var respuesta: () -> MockResponse = { MockResponse().setResponseCode(200).setBody("{}") }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                fotoAlLlegar += enDisco("order-F") != null
                return respuesta()
            }
        }
        server.start()
    }

    @After fun cerrar() { server.shutdown(); dir.setWritable(true); dir.deleteRecursively() }

    private fun enDisco(orderId: String) = FotosDeMesas(dir).foto("v1", orderId)

    private fun tomar(orderId: String = "order-F", alias: String = "local-F", mesa: String = "table-F") {
        val ids = setOf(orderId, alias, mesa)
        val lectura = fotos.empezarLectura(ids)
        assertTrue(fotos.crearSi(FotoDeMesa("v1", orderId, listOf(alias), mesa, OrderDetail(orderId, total = 100.0), 7, emptyList(),
            System.currentTimeMillis()), lectura))
        fotos.terminarLectura(lectura)
        assertNotNull(enDisco(orderId))
    }

    private fun json(body: String) = body.toRequestBody("application/json".toMediaType())
    private fun post(ruta: String, body: String = "{}", metodo: String = "POST", cuerpo: RequestBody? = json(body)) =
        client.newCall(Request.Builder().url(server.url("/api/v1/mobile/venues/v1/$ruta")).method(metodo, cuerpo).build()).execute().close()

    // MARK: - Toda escritura que menciona la mesa la borra ANTES de salir

    @Test fun `borra antes de salir con cada escritura de la app sobre la orden, su alias o su mesa`() {
        val escrituras = listOf(
            "orders/order-F/pay" to """{"amount":10000,"tip":0,"idempotencyKey":"k1"}""",
            "terminal-payment" to """{"terminalId":"term-1","amountCents":10000,"orderId":"order-F","requestId":"r1"}""",
            "orders/order-F/pay-gift-card" to """{"codigo":"GC-1","operationKey":"op-1"}""",
            "orders/order-F/gift-balance-return-and-cancel" to """{"operationKey":"op-2"}""",
            "orders/order-F/items/item-9/comp" to """{"reason":"Se cayó"}""",
            "orders/order-F/move" to """{"tableId":"table-X"}""",
            "orders/order-F/split" to """{"items":[{"id":"item-9"}]}""",
            "orders/order-F/discounts" to """{"discountId":"d-1"}""",
            "tables/table-F/open" to """{"covers":2}""",
            "inventada/ruta-nueva" to """{"cuenta":"local-F"}""",
        )
        for ((ruta, body) in escrituras) {
            tomar()
            post(ruta, body)
            assertFalse("$ruta: la foto ya no estaba en disco al llegar al servidor", fotoAlLlegar.last())
            assertNull("$ruta: y no vuelve", enDisco("order-F"))
        }
        tomar(); post("orders/order-F/discounts/od-1", "", "DELETE", null)
        assertFalse("DELETE sin cuerpo también", fotoAlLlegar.last())
    }

    @Test fun `juntar borra las fotos de las dos ordenes`() {
        tomar(); tomar("order-G", "local-G", "table-G")
        post("orders/order-G/merge", """{"sourceOrderId":"order-F"}""")
        assertNull(enDisco("order-F")); assertNull(enDisco("order-G"))
    }

    @Test fun `una escritura de OTRA mesa, un GET y lo que solo reproduce no la tocan`() {
        tomar()
        post("orders/order-Z/discounts", """{"discountId":"d-1"}""")
        client.newCall(Request.Builder().url(server.url("/api/v1/mobile/venues/v1/orders/order-F")).build()).execute().close()
        post("sync/intents", """{"intents":[{"payload":{"orderId":"order-F"}}]}""")
        post("sync/recovery", """{"ids":["order-F"]}""")
        post("print-jobs/sync", """{"orderId":"order-F"}""")
        assertTrue(fotoAlLlegar.all { it })
        assertNotNull(enDisco("order-F"))
    }

    // MARK: - La ronda (POST …/orders/{id}/items): al VOLVER, con 2xx o 4xx (r4 #4); sin respuesta, 409 o 5xx la conserva

    @Test fun `la ronda no borra al salir y borra al volver con 2xx 400 o 403`() {
        for (codigo in listOf(201, 400, 403)) {
            tomar(); respuesta = { MockResponse().setResponseCode(codigo).setBody("{}") }
            post("orders/order-F/items", """{"items":[{"productId":"p","externalId":"sync:k1:0"}],"version":3}""")
            assertTrue("$codigo: la ronda sale con la foto", fotoAlLlegar.last())
            assertNull("$codigo: al volver la borra", enDisco("order-F"))
        }
    }

    @Test fun `la ronda conserva la foto con 409, con 5xx y sin respuesta`() {
        tomar()
        for (codigo in listOf(409, 500, 503)) {
            respuesta = { MockResponse().setResponseCode(codigo).setBody("{}") }
            post("orders/order-F/items", """{"items":[]}""")
            assertNotNull("$codigo: la ronda sigue en la cola", enDisco("order-F"))
        }
        respuesta = { MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
        try { post("orders/order-F/items", """{"items":[]}"""); fail("sin respuesta") } catch (_: IOException) {}
        assertNotNull("sin respuesta: se conserva", enDisco("order-F"))
    }

    // MARK: - Cuerpo ilegible, disco que falla y lecturas en curso

    @Test fun `un cuerpo de un solo uso o de mas de 1 MB borra todas las fotos`() {
        val unSoloUso = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) { sink.writeUtf8("{}") }
        }
        tomar(); tomar("order-G", "local-G", "table-G")
        post("otra/cosa", cuerpo = unSoloUso)
        assertNull(enDisco("order-F")); assertNull(enDisco("order-G"))
        tomar()
        post("otra/cosa", "x".repeat(1024 * 1024 + 1))
        assertNull(enDisco("order-F"))
    }

    @Test fun `si borrar el disco falla la peticion no sale`() {
        tomar()
        assertTrue(dir.setWritable(false))
        try { post("orders/order-F/pay", """{"amount":1}"""); fail("debió fallar") } catch (_: IOException) {}
        dir.setWritable(true)
        assertEquals("el servidor nunca la recibió", 0, server.requestCount)
        assertNotNull("la foto sigue (no se borró a medias)", enDisco("order-F"))
    }

    @Test fun `r4 #1 una orden SIN foto con lectura en curso ensucia la lectura y no nace foto`() {
        val ids = setOf("order-H", "table-H")
        val lectura = fotos.empezarLectura(ids)
        post("orders/order-H/items/item-1/comp", """{"reason":"cortesía desde el plano"}""")
        assertFalse(fotos.crearSi(FotoDeMesa("v1", "order-H", emptyList(), "table-H", OrderDetail("order-H", total = 100.0), 1, emptyList(),
            System.currentTimeMillis()), lectura))
        fotos.terminarLectura(lectura)
        assertNull(FotosDeMesas(dir).foto("v1", "order-H"))
    }

    @Test fun `una escritura en vuelo desde OTRA pantalla al pedir la lectura impide la foto`() {
        val llego = CountDownLatch(1); val suelta = CountDownLatch(1)
        respuesta = { llego.countDown(); suelta.await(5, TimeUnit.SECONDS); MockResponse().setResponseCode(200) }
        // La cortesía desde el plano sale ANTES de que la cuenta empiece a leer (r3 #3): la orden aún no estaba vigilada.
        val enVuelo = Thread { post("orders/order-H/items/item-1/comp", """{"reason":"x"}""") }.also { it.start() }
        assertTrue(llego.await(5, TimeUnit.SECONDS))
        val lectura = fotos.empezarLectura(setOf("order-H", "table-H"))
        suelta.countDown(); enVuelo.join()
        assertFalse(fotos.crearSi(FotoDeMesa("v1", "order-H", emptyList(), "table-H", OrderDetail("order-H", total = 100.0), 1, emptyList(),
            System.currentTimeMillis()), lectura))
        // La siguiente lectura, ya sin nada en vuelo, sí.
        val otra = fotos.empezarLectura(setOf("order-H", "table-H"))
        assertTrue(fotos.crearSi(FotoDeMesa("v1", "order-H", emptyList(), "table-H", OrderDetail("order-H", total = 100.0), 1, emptyList(),
            System.currentTimeMillis()), otra))
    }

    @Test fun `sin fotos ni lecturas no lee nada ni toca el disco`() {
        var escrito = 0
        val espia = object : RequestBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = 2L
            override fun writeTo(sink: BufferedSink) { escrito++; sink.writeUtf8("{}") }
        }
        val vacio = File(dir, "nunca")
        val cliente = OkHttpClient.Builder().addInterceptor(PuertaDeLaFoto(FotosDeMesas(vacio))).build()
        cliente.newCall(Request.Builder().url(server.url("/api/v1/mobile/venues/v1/orders/order-F/pay")).post(espia).build()).execute().close()
        assertEquals("el servidor la recibió igual", 1, server.requestCount)
        assertEquals("sólo OkHttp escribió el cuerpo (al mandarlo): la puerta no lo leyó", 1, escrito)
        assertFalse("la carpeta de fotos ni se creó", vacio.exists())
        // Con algo vigilado, la misma petición sí se lee (una vez más) para buscar la mesa.
        tomar(); escrito = 0
        client.newCall(Request.Builder().url(server.url("/api/v1/mobile/venues/v1/orders/order-Z/pay")).post(espia).build()).execute().close()
        assertEquals(2, escrito)
    }

    @Test fun `la foto vence a las 24 h y sobrevive a reiniciar mientras tanto`() {
        var ahora = 1_000_000L
        val conReloj = FotosDeMesas(dir) { ahora }
        val lectura = conReloj.empezarLectura(setOf("order-V"))
        assertTrue(conReloj.crearSi(FotoDeMesa("v1", "order-V", emptyList(), null, OrderDetail("order-V", total = 1.0), 1, emptyList(), ahora), lectura))
        assertNotNull("otra instancia (reinicio) la lee", FotosDeMesas(dir) { ahora + 1 }.foto("v1", "order-V"))
        ahora += FotosDeMesas.VIGENCIA_MS
        assertNull(conReloj.foto("v1", "order-V"))
        assertNull("de otra sucursal tampoco", FotosDeMesas(dir) { 1_000_001L }.foto("v2", "order-V"))
    }
}
