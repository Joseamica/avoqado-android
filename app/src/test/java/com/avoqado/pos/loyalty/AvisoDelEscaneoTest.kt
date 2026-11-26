package com.avoqado.pos.loyalty

import com.avoqado.pos.loyalty.data.StampThisSale
import com.avoqado.pos.loyalty.data.WalletScanResponse
import com.avoqado.pos.loyalty.domain.TextosDelSello
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * D50 (hz:b37dd7ea:D50): el aviso del escaneo de la tarjeta digital decía «esta compra le suma otro» con texto fijo, aunque la
 * clienta ya tuviera su sello del día o los sellos estuvieran apagados. Ahora lo dice el servidor (`stampThisSale`).
 */
class AvisoDelEscaneoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `servidor viejo sin el dato dice lo de siempre`() {
        assertEquals("esta compra le suma otro", TextosDelSello.estaCompra(null))
    }

    @Test
    fun `con el sello del dia disponible suma`() {
        assertEquals("esta compra le suma otro", TextosDelSello.estaCompra(StampThisSale(willStamp = true)))
    }

    @Test
    fun `si ya recibio su sello de hoy no promete otro`() {
        assertEquals(
            "ya recibió su sello de hoy",
            TextosDelSello.estaCompra(StampThisSale(willStamp = false, reason = "DAILY_LIMIT_REACHED")),
        )
    }

    @Test
    fun `con la tarjeta de sellos apagada lo dice`() {
        assertEquals(
            "la tarjeta de sellos está apagada",
            TextosDelSello.estaCompra(StampThisSale(willStamp = false, reason = "STAMPS_DISABLED")),
        )
    }

    @Test
    fun `sin comprobar no promete ni niega el sello`() {
        assertNull(TextosDelSello.estaCompra(StampThisSale(willStamp = false, reason = "UNCHECKED")))
    }

    @Test
    fun `una razon que esta app no conoce no promete el sello`() {
        assertEquals("esta compra no suma sello", TextosDelSello.estaCompra(StampThisSale(willStamp = false, reason = "ALGO_NUEVO")))
    }

    @Test
    fun `la respuesta del escaneo trae el pronostico y sin el campo queda null`() {
        val conDato = json.decodeFromString<WalletScanResponse>(
            """{"found":true,"stampsEarned":3,"stampsRequired":7,"stampThisSale":{"willStamp":false,"reason":"DAILY_LIMIT_REACHED"}}""",
        )
        assertEquals(StampThisSale(willStamp = false, reason = "DAILY_LIMIT_REACHED"), conDato.stampThisSale)

        val sinDato = json.decodeFromString<WalletScanResponse>("""{"found":true,"stampsEarned":3,"stampsRequired":7}""")
        assertNull(sinDato.stampThisSale)
    }
}
