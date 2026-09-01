package com.avoqado.pos.tpvsettings

import com.avoqado.pos.tpvsettings.data.ReceiptInfo
import com.avoqado.pos.tpvsettings.data.VenueSettingsResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El bloque `receiptInfo` del payload de settings (encabezado del ticket
 * impreso). Contrato con el server: aditivo y opcional — un server viejo (campo
 * ausente) no puede romper el parseo ni inventar datos fiscales.
 */
class ReceiptInfoParsingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun `parsea el bloque receiptInfo del server`() {
        val body = """
            {"success":true,"data":{"settings":null,"activeTerminalId":null,
             "receiptInfo":{"name":"Testarudo Cafe","logoUrl":"https://cdn/logo.png",
               "phone":"5512345678","address":"Nápoles 47","city":"Cuauhtémoc",
               "state":"Ciudad de México","zipCode":"06600",
               "legalName":"TESTARUDO CAFE S.A.P.I. DE C.V.","rfc":"TCA2501231A6",
               "lugarExpedicion":"06600"}}}
        """.trimIndent()

        val parsed = json.decodeFromString<VenueSettingsResponse>(body)
        val info = parsed.data?.receiptInfo!!
        assertEquals("TESTARUDO CAFE S.A.P.I. DE C.V.", info.legalName)
        assertEquals("TCA2501231A6", info.rfc)
        assertEquals("06600", info.lugarExpedicion)
        assertEquals("https://cdn/logo.png", info.logoUrl)
    }

    @Test
    fun `server viejo sin el campo deja receiptInfo en null`() {
        val body = """{"success":true,"data":{"settings":null,"activeTerminalId":null}}"""
        val parsed = json.decodeFromString<VenueSettingsResponse>(body)
        assertNull(parsed.data?.receiptInfo)
    }

    @Test
    fun `addressLine compone direccion, ciudad, estado y CP en una linea`() {
        val info = ReceiptInfo(
            address = "Nápoles 47",
            city = "Cuauhtémoc",
            state = "Ciudad de México",
            zipCode = "06600",
        )
        assertEquals("Nápoles 47, Cuauhtémoc, Ciudad de México, CP 06600", info.addressLine)
    }

    @Test
    fun `addressLine ignora vacios y sin ningun dato es null`() {
        assertEquals("Nápoles 47", ReceiptInfo(address = "Nápoles 47", city = "  ").addressLine)
        assertNull(ReceiptInfo().addressLine)
    }
}
