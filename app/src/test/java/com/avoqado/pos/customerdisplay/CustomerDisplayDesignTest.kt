package com.avoqado.pos.customerdisplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** La ficha del aparato se normaliza campo por campo: un campo raro cae a fábrica sin tirar los demás. */
class CustomerDisplayDesignTest {

    private val origins = listOf("https://storage.googleapis.com", "https://firebasestorage.googleapis.com")
    private val huella = "0123456789abcdef0123456789abcdef"
    private val sha = "a".repeat(64)
    private val logoUrl = "https://storage.googleapis.com/bucket/venues/v1/logo.png"

    private fun ficha(
        schemaVersion: Int = 1,
        fingerprint: String = huella,
        theme: String? = "DARK",
        accent: AccentDto? = AccentDto(custom = true, color = "#1A7F5A", on = "#FFFFFF"),
        logo: LogoDto? = LogoDto(source = "asset", url = logoUrl, sha256 = sha),
        welcomeText: String? = "Bienvenido",
        thanksText: String? = "Gracias por tu compra",
    ) = FichaDto(schemaVersion, fingerprint, theme, accent, logo, welcomeText, thanksText)

    @Test
    fun `ficha valida se normaliza completa`() {
        assertEquals(
            Ficha(
                fingerprint = huella,
                dark = true,
                accentArgb = 0xFF1A7F5A,
                onAccentArgb = 0xFFFFFFFF,
                logo = LogoRef.Asset(logoUrl, sha),
                welcomeText = "Bienvenido",
                thanksText = "Gracias por tu compra",
            ),
            ficha().toFicha(origins),
        )
    }

    @Test
    fun `schema 2 no se aplica`() {
        assertNull(ficha(schemaVersion = 2).toFicha(origins))
    }

    @Test
    fun `huella invalida descarta la ficha`() {
        assertNull(ficha(fingerprint = huella.uppercase()).toFicha(origins))
        assertNull(ficha(fingerprint = "abc").toFicha(origins))
    }

    @Test
    fun `campos invalidos caen a fabrica uno por uno`() {
        val result = ficha(
            theme = "SEPIA",
            accent = AccentDto(custom = true, color = "#GGG", on = "#FFFFFF"),
            welcomeText = "x".repeat(300),
            logo = LogoDto(source = "asset", url = "https://evil.test/x.png", sha256 = sha),
        ).toFicha(origins)!!

        assertFalse(result.dark)
        assertNull(result.accentArgb)
        assertNull(result.onAccentArgb)
        assertNull(result.welcomeText)
        assertNull(result.logo)
        assertEquals(huella, result.fingerprint)
        assertEquals("Gracias por tu compra", result.thanksText)
    }

    @Test
    fun `texto de 40 emojis cabe y de 41 no`() {
        val emoji = "😀" // 😀: un code point, dos chars
        assertEquals(emoji.repeat(40), ficha(welcomeText = emoji.repeat(40)).toFicha(origins)!!.welcomeText)
        assertNull(ficha(welcomeText = emoji.repeat(41)).toFicha(origins)!!.welcomeText)
    }

    @Test
    fun `texto en blanco cae a fabrica`() {
        assertNull(ficha(thanksText = "   ").toFicha(origins)!!.thanksText)
    }

    @Test
    fun `logo de venue necesita version y el asset su sha de 64 hex`() {
        val venueUrl = "https://firebasestorage.googleapis.com/v0/b/x/o/logo.png"
        assertEquals(
            LogoRef.Venue(venueUrl, "2026-10-18T12:00:00.000Z"),
            ficha(logo = LogoDto("venue", venueUrl, version = "2026-10-18T12:00:00.000Z")).toFicha(origins)!!.logo,
        )
        assertNull(ficha(logo = LogoDto("venue", venueUrl)).toFicha(origins)!!.logo)
        assertNull(ficha(logo = LogoDto("asset", logoUrl, sha256 = "abc")).toFicha(origins)!!.logo)
        assertNull(ficha(logo = LogoDto("asset", logoUrl)).toFicha(origins)!!.logo)
    }

    @Test
    fun `acento sin custom queda de fabrica`() {
        val result = ficha(accent = AccentDto(custom = false)).toFicha(origins)!!
        assertNull(result.accentArgb)
        assertNull(result.onAccentArgb)
    }

    @Test
    fun `isAllowedImageUrl exige el origen exacto seguido de diagonal`() {
        assertTrue(isAllowedImageUrl("https://storage.googleapis.com/b/x.png", origins))
        assertFalse(isAllowedImageUrl("https://storage.googleapis.com.evil.test/x", origins))
        assertFalse(isAllowedImageUrl("http://storage.googleapis.com/b/x.png", origins))
        assertFalse(isAllowedImageUrl("https://evil.test/storage.googleapis.com/x.png", origins))
        assertFalse(isAllowedImageUrl("https://storage.googleapis.com", origins))
    }
}
