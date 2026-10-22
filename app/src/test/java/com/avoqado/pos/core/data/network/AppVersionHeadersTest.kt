package com.avoqado.pos.core.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `x-app-version` y `x-app-version-code` salen de UNA lista: así una cabecera nunca viaja sin la otra. El server las lee
 * en `registerDevice.middleware.ts` (el número decide qué versión de diseño entiende la tablet).
 */
class AppVersionHeadersTest {

    @Test
    fun `la version viaja siempre con su numero`() {
        assertEquals(
            listOf("x-app-version" to "2.25.1", "x-app-version-code" to "55"),
            appVersionHeaders("2.25.1", 55),
        )
    }
}
