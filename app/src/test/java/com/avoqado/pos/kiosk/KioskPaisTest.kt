package com.avoqado.pos.kiosk

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.util.VenueDateTimeFormatter
import com.avoqado.pos.designsystem.components.Countries
import com.avoqado.pos.kiosk.data.KioskCheckInApi
import com.avoqado.pos.kiosk.domain.KioskContent
import com.avoqado.pos.kiosk.domain.KioskDriver
import com.avoqado.pos.kiosk.domain.KioskPack
import com.avoqado.pos.kiosk.domain.KioskState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

/**
 * El teclado del kiosco elige el país (Codex r1, D3): con la lada fija en +52, una clienta con teléfono de EE.UU.
 * tecleaba sus 10 dígitos y se le componía «+52…» — sus clases podían caer en la ficha de OTRA persona.
 */
class KioskPaisTest {

    @get:Rule
    val mainRule = MainDispatcherRule()

    private val pack = KioskPack("p1", "10 clases", 150_000, null)
    private val api = mockk<KioskCheckInApi>().also {
        coEvery { it.packCheckout(any(), any()) } returns Result.failure(IOException())
        coEvery { it.checkIn(any()) } returns Result.failure(IOException())
    }
    // Perezoso: KioskState toma Dispatchers.Main al construirse, y la regla lo cambia hasta que empieza la prueba.
    private val kiosk by lazy { KioskState() }

    private fun driver() = KioskDriver(kiosk, mockk<VenueDateTimeFormatter>(relaxed = true), api, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
        .also { it.attach() }

    private fun comprar() {
        kiosk.show(KioskContent.Offer(customerName = "", packs = listOf(pack), selectedId = pack.id))
        kiosk.onBuy?.invoke()
    }

    private fun teclear(digitos: String) = digitos.forEach { kiosk.onDigit?.invoke(it.toString()) }

    @Test
    fun `con Estados Unidos elegido, la compra va con +1`() {
        driver()
        comprar()
        kiosk.onCountry?.invoke(Countries.byIso("US"))
        teclear("2025550123")
        kiosk.onSearch?.invoke()

        coVerify { api.packCheckout("p1", "+12025550123") }
    }

    @Test
    fun `con Mexico, como antes, la compra va con +52`() {
        driver()
        comprar()
        teclear("5512345678")
        kiosk.onSearch?.invoke()

        coVerify { api.packCheckout("p1", "+525512345678") }
    }

    @Test
    fun `buscar la reserva tambien manda la lada elegida`() {
        driver()
        kiosk.onStart?.invoke()
        kiosk.onCountry?.invoke(Countries.byIso("US"))
        teclear("2025550123")
        kiosk.onSearch?.invoke()

        coVerify { api.checkIn("+12025550123") }
    }

    @Test
    fun `cambiar de pais limpia los avisos y borra el numero, nunca lo recorta`() {
        driver()
        kiosk.onStart?.invoke()
        kiosk.onCountry?.invoke(Countries.byIso("CR")) // +506: hasta 12 dígitos (E.164 = 15 con la lada)
        teclear("123456789012345")
        assertEquals("123456789012", (kiosk.content.value as KioskContent.Identify).national)

        kiosk.show((kiosk.content.value as KioskContent.Identify).copy(notFound = true, failed = true))
        kiosk.onCountry?.invoke(Countries.byIso("MX"))

        val ahora = kiosk.content.value as KioskContent.Identify
        assertEquals("", ahora.national)
        assertFalse(ahora.notFound)
        assertFalse(ahora.failed)
    }

    @Test
    fun `digitos por pais - Mexico y EEUU exactamente 10, los demas de 8 en adelante`() {
        fun puede(iso: String, digitos: String) =
            KioskContent.Identify(country = Countries.byIso(iso), national = digitos).canSearch

        assertFalse(puede("MX", "551234567"))
        assertTrue(puede("MX", "5512345678"))
        assertFalse(puede("US", "202555012"))
        assertTrue(puede("US", "2025550123"))
        assertFalse(puede("CR", "1234567"))
        assertTrue(puede("CR", "12345678"))
        // +49 + 8 = 10 dígitos: el SERVIDOR lo leería como celular mexicano (Codex r2, D3).
        assertFalse(puede("DE", "20123456"))
        assertTrue(puede("DE", "201234567"))
    }
}
