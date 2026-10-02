package com.avoqado.pos.escritorio

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

/** La guarda como pieza suelta, con una cadena falsa: qué deja salir y qué corta ANTES de `proceed` (C4). */
class GuardaDeRedireccionesTest {
    private val guarda = GuardaDeRedirecciones("https://api.avoqado.io/api/v1")

    @Test fun `el mismo servidor pasa, con cualquier ruta`() {
        for (url in listOf("https://api.avoqado.io/api/v1/mobile/venues/v1/fast", "https://API.avoqado.io:443/otra?x=1")) {
            val cadena = CadenaFalsa(url)
            assertEquals(200, guarda.intercept(cadena).code, url)
            assertEquals(1, cadena.llamadas, url)
        }
    }

    @Test fun `otro host, otro puerto u otro esquema se cortan sin llamar proceed`() {
        val malas = listOf(
            "https://malo.example.com/api/v1/mobile/auth/refresh",   // otro host
            "https://dashboard.avoqado.io/x",                       // otro host del mismo dominio
            "https://api.avoqado.io:8443/api/v1",                   // mismo host, otro puerto
            "http://api.avoqado.io/api/v1",                         // https → http: el cuerpo viajaría en claro
        )
        for (url in malas) {
            val cadena = CadenaFalsa(url)
            val e = assertFailsWith<IOException>(url) { guarda.intercept(cadena) }
            assertTrue(e.message.orEmpty().startsWith("redirección bloqueada hacia "), e.message)
            assertEquals(0, cadena.llamadas, "salió hacia $url")
        }
    }

    @Test fun `en un servidor local de prueba vale lo mismo`() {
        val local = GuardaDeRedirecciones("http://127.0.0.1:3000/api/v1")
        assertEquals(200, local.intercept(CadenaFalsa("http://127.0.0.1:3000/api/v1/x")).code)
        assertFailsWith<IOException> { local.intercept(CadenaFalsa("http://localhost:3000/api/v1/x")) }
        assertFailsWith<IOException> { local.intercept(CadenaFalsa("https://api.avoqado.io/api/v1/x")) }
    }

    /** Sólo `request()` y `proceed()`: lo demás no lo debe tocar la guarda. */
    private class CadenaFalsa(url: String) : Interceptor.Chain {
        private val pedido = Request.Builder().url(url).build()
        var llamadas = 0
        override fun request(): Request = pedido
        override fun proceed(request: Request): Response {
            llamadas++
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").build()
        }
        override fun connection(): Connection? = null
        override fun call(): Call = error("no se usa")
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
