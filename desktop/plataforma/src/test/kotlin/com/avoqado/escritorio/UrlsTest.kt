package com.avoqado.escritorio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UrlsTest {
    private val nada: (String) -> String? = { null }

    @Test fun `propiedad gana a variable y variable gana al default`() {
        assertEquals("http://p/api/v1", Urls.api("http://d/api/v1", { if (it == "avoqado.api") "http://p/api/v1" else null }, { "http://e/api/v1" }))
        assertEquals("http://e/api/v1", Urls.api("http://d/api/v1", nada, { if (it == "AVOQADO_API") "http://e/api/v1" else null }))
        assertEquals("http://d/api/v1", Urls.api("http://d/api/v1", nada, nada))
    }

    @Test fun `produccion se rechaza venga de donde venga y como venga escrita`() {
        assertFailsWith<IllegalStateException> { Urls.api("https://api.avoqado.io/api/v1", nada, nada) }
        assertFailsWith<IllegalStateException> { Urls.api("http://d", { "https://API.avoqado.io/api/v1" }, nada) }
        assertFailsWith<IllegalStateException> { Urls.api("http://d", nada, { "https://api.avoqado.io/api/v1" }) }
        assertFailsWith<IllegalStateException> { Urls.api("https://api%2eavoqado.io/api/v1", nada, nada) } // OkHttp decodifica el host
        assertFailsWith<IllegalStateException> { Urls.api("no es una url", nada, nada) }
        assertFailsWith<IllegalStateException> { Urls.api("https://api.avoqado.io./api/v1", nada, nada) } // punto final
        assertFailsWith<IllegalStateException> { Urls.dashboard("https://dashboard.avoqado.io", nada, nada) }
        assertFailsWith<IllegalStateException> { Urls.dashboard("http://d", { "https://dashboard.avoqado.io" }, nada) }
    }

    @Test fun `precedencia del dashboard`() {
        assertEquals("http://p", Urls.dashboard("http://d", { if (it == "avoqado.dashboard") "http://p" else null }, { "http://e" }))
        assertEquals("http://e", Urls.dashboard("http://d", nada, { if (it == "AVOQADO_DASHBOARD") "http://e" else null }))
        assertEquals("http://d", Urls.dashboard("http://d", nada, nada))
    }

    @Test fun `la IP de la Mac en la red del local se acepta`() {
        assertEquals("http://192.168.1.50:3000/api/v1", Urls.api("http://192.168.1.50:3000/api/v1", nada, nada))
    }

    @Test fun `una avoqado test baseUrl de produccion se rechaza`() {
        assertFailsWith<IllegalStateException> { Urls.validar("https://api.avoqado.io/api/v1") }
        assertEquals("http://localhost:3000/api/v1", Urls.validar("http://localhost:3000/api/v1"))
    }
}
