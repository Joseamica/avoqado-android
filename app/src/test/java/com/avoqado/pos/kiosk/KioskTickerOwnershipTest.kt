package com.avoqado.pos.kiosk

import com.avoqado.pos.designsystem.components.Countries
import com.avoqado.pos.kiosk.domain.KioskContent
import com.avoqado.pos.kiosk.domain.KioskPack
import com.avoqado.pos.kiosk.domain.KioskSession
import com.avoqado.pos.kiosk.domain.tickerOwnsScreen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quién manda en cada pantalla del kiosco: el reloj o la persona que está enfrente.
 *
 * 🔴 Estas pruebas nacen de un defecto que SÓLO se vio en la D3 real. El tick de refresco
 * corre cada 15 s y respetaba una gracia de 25 s desde el último toque — pero nada más.
 * Resultado: alguien tocaba "¿No apareces?", empezaba a teclear su teléfono, se detenía a
 * pensar, y a los 25 segundos exactos la pantalla lo devolvía a la lista con los dígitos
 * perdidos. Y habría hecho lo mismo con un QR de pago a medio escanear, que es peor.
 *
 * Ni el compilador ni las 8 pruebas de ventana lo vieron: es un defecto de TIEMPO sobre
 * hardware real.
 */
class KioskTickerOwnershipTest {

    private val sesion = KioskSession(
        reservationId = "r1", title = "Yoga", timeLabel = "7:00 PM", staffLabel = "con Sofía",
    )
    private val paquete = KioskPack(id = "p1", name = "10 clases", priceCents = 150_000, detail = null)

    @Test
    fun `el reposo es del reloj`() {
        assertTrue(tickerOwnsScreen(KioskContent.Welcome))
    }

    @Test
    fun `la lista de la clase es del reloj`() {
        assertTrue(tickerOwnsScreen(KioskContent.Roster(classTitle = "Yoga", timeLabel = "7:00 PM", staffLabel = null, people = emptyList())))
    }

    @Test
    fun `tecleando el telefono NO se le quita la pantalla`() {
        val tecleando = KioskContent.Identify(country = Countries.pinned.first(), national = "55123")
        assertFalse(tickerOwnsScreen(tecleando))
    }

    @Test
    fun `eligiendo un paquete NO se le quita la pantalla`() {
        assertFalse(tickerOwnsScreen(KioskContent.Offer(customerName = "", packs = listOf(paquete))))
    }

    @Test
    fun `con el QR de pago enfrente NO se le quita la pantalla`() {
        val pagando = KioskContent.Paying(customerName = "", pack = paquete, payUrl = "https://checkout")
        assertFalse(tickerOwnsScreen(pagando))
    }

    @Test
    fun `el acuse de llegada NO se lo quita el reloj — se va con su propio temporizador`() {
        assertFalse(tickerOwnsScreen(KioskContent.CheckedIn(customerName = "Ana G.", session = sesion)))
    }

    @Test
    fun `un aviso de problema NO se lo quita el reloj`() {
        assertFalse(tickerOwnsScreen(KioskContent.Trouble("Sin conexión")))
    }
}
