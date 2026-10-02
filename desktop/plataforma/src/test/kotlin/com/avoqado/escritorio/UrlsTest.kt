package com.avoqado.escritorio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UrlsTest {
    private val nada: (String) -> String? = { null }

    @Test fun `propiedad gana a variable y variable gana al default`() {
        assertEquals("http://p/api/v1", Urls.api("http://d/api/v1", false, { if (it == "avoqado.api") "http://p/api/v1" else null }, { "http://e/api/v1" }))
        assertEquals("http://e/api/v1", Urls.api("http://d/api/v1", false, nada, { if (it == "AVOQADO_API") "http://e/api/v1" else null }))
        assertEquals("http://d/api/v1", Urls.api("http://d/api/v1", false, nada, nada))
    }

    @Test fun `produccion se rechaza venga de donde venga y como venga escrita`() {
        assertFailsWith<IllegalStateException> { Urls.api("https://api.avoqado.io/api/v1", false, nada, nada) }
        assertFailsWith<IllegalStateException> { Urls.api("http://d", false, { "https://API.avoqado.io/api/v1" }, nada) }
        assertFailsWith<IllegalStateException> { Urls.api("http://d", false, nada, { "https://api.avoqado.io/api/v1" }) }
        assertFailsWith<IllegalStateException> { Urls.api("https://api%2eavoqado.io/api/v1", false, nada, nada) } // OkHttp decodifica el host
        assertFailsWith<IllegalStateException> { Urls.api("no es una url", false, nada, nada) }
        assertFailsWith<IllegalStateException> { Urls.api("https://api.avoqado.io./api/v1", false, nada, nada) } // punto final
        assertFailsWith<IllegalStateException> { Urls.dashboard("https://dashboard.avoqado.io", false, nada, nada) }
        assertFailsWith<IllegalStateException> { Urls.dashboard("http://d", false, { "https://dashboard.avoqado.io" }, nada) }
    }

    @Test fun `precedencia del dashboard`() {
        assertEquals("http://p", Urls.dashboard("http://d", false, { if (it == "avoqado.dashboard") "http://p" else null }, { "http://e" }))
        assertEquals("http://e", Urls.dashboard("http://d", false, nada, { if (it == "AVOQADO_DASHBOARD") "http://e" else null }))
        assertEquals("http://d", Urls.dashboard("http://d", false, nada, nada))
    }

    @Test fun `la IP de la Mac en la red del local se acepta`() {
        assertEquals("http://192.168.1.50:3000/api/v1", Urls.api("http://192.168.1.50:3000/api/v1", false, nada, nada))
    }

    @Test fun `una avoqado test baseUrl de produccion se rechaza`() {
        assertFailsWith<IllegalStateException> { Urls.validar("https://api.avoqado.io/api/v1", false) }
        assertEquals("http://localhost:3000/api/v1", Urls.validar("http://localhost:3000/api/v1", false))
    }

    // --- Build de producción: sólo HTTPS y el host EXACTO ---

    @Test fun `produccion acepta sus hosts exactos, normalizados`() {
        assertEquals("https://api.avoqado.io/api/v1", Urls.api("https://api.avoqado.io/api/v1", true, nada, nada))
        assertEquals("https://API.avoqado.io./api/v1", Urls.api("https://API.avoqado.io./api/v1", true, nada, nada))
        assertEquals("https://dashboard.avoqado.io", Urls.dashboard("https://dashboard.avoqado.io", true, nada, nada))
        assertEquals("https://api.avoqado.io/api/v1", Urls.validar("https://api.avoqado.io/api/v1", true))
    }

    @Test fun `produccion rechaza todo lo que no sea https y su host exacto`() {
        val malas = listOf(
            "http://api.avoqado.io/api/v1",
            "https://api.avoqado.io.malo.com/",
            "https://maloavoqado.io/",
            "https://otro.avoqado.io/",
            "https://ejemplo.ngrok-free.dev/api/v1",
            "http://192.168.1.10:3000/api/v1",
            "no es una url",
        )
        for (m in malas) assertFailsWith<IllegalStateException>(m) { Urls.api(m, true, nada, nada) }
        for (m in malas) assertFailsWith<IllegalStateException>(m) { Urls.validar(m, true) }
        // el host de la API no vale como dashboard y al revés
        assertFailsWith<IllegalStateException> { Urls.dashboard("https://api.avoqado.io", true, nada, nada) }
        assertFailsWith<IllegalStateException> { Urls.api("https://dashboard.avoqado.io", true, nada, nada) }
    }

    @Test fun `un AVOQADO_API heredado pasa por la guarda en los dos modos`() {
        assertFailsWith<IllegalStateException> { Urls.api("https://api.avoqado.io/api/v1", true, nada, { if (it == "AVOQADO_API") "https://ngrok.io/x" else null }) }
        assertFailsWith<IllegalStateException> { Urls.api("http://d", false, nada, { if (it == "AVOQADO_API") "https://api.avoqado.io/api/v1" else null }) }
        assertFailsWith<IllegalStateException> { Urls.api("https://api.avoqado.io/api/v1", true, { if (it == "avoqado.api") "http://localhost:3000/api/v1" else null }, nada) }
    }

    @Test fun `prueba rechaza avoqado_io y subdominios, acepta ngrok e IP local`() {
        assertFailsWith<IllegalStateException> { Urls.api("https://avoqado.io/x", false, nada, nada) }
        assertFailsWith<IllegalStateException> { Urls.api("https://x.avoqado.io/x", false, nada, nada) }
        assertEquals("https://algo.ngrok-free.dev/api/v1", Urls.api("https://algo.ngrok-free.dev/api/v1", false, nada, nada))
        assertEquals("http://192.168.1.10:3000/api/v1", Urls.api("http://192.168.1.10:3000/api/v1", false, nada, nada))
    }

    @Test fun `produccion acepta el puerto 443 explicito`() {
        assertEquals("https://api.avoqado.io:443/api/v1", Urls.api("https://api.avoqado.io:443/api/v1", true, nada, nada))
    }

    @Test fun `produccion rechaza puerto, credenciales, query y fragmento`() {
        val malas = listOf(
            "https://api.avoqado.io:3000/api/v1",
            "https://api.avoqado.io:8443/api/v1",
            "https://u:p@api.avoqado.io/api/v1",
            "https://x@api.avoqado.io/api/v1",
            "https://api.avoqado.io/api/v1?x=1",
            "https://api.avoqado.io/api/v1#x",
            "https://api.avoqado.io@malo.com/",
            "https://malo.com#@api.avoqado.io/",
        )
        for (m in malas) assertFailsWith<IllegalStateException>(m) { Urls.api(m, true, nada, nada) }
        for (m in malas) assertFailsWith<IllegalStateException>(m) { Urls.validar(m, true) }
        assertFailsWith<IllegalStateException> { Urls.dashboard("https://dashboard.avoqado.io:3000", true, nada, nada) }
    }

    @Test fun `validar en produccion es base de API y no acepta el dashboard`() {
        assertFailsWith<IllegalStateException> { Urls.validar("https://dashboard.avoqado.io", true) }
        assertEquals("https://api.avoqado.io/api/v1", Urls.validar("https://api.avoqado.io/api/v1", true))
    }
}
