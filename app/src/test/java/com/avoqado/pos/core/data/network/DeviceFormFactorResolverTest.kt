package com.avoqado.pos.core.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Clase de aparato que se reporta al server en `x-device-form-factor`.
 *
 * Los valores espejan el enum `DeviceFormFactor` del backend por nombre EXACTO. Un
 * nombre mal escrito no truena: el server lo descarta y el aparato queda "sin
 * identificar" para siempre. Falla en silencio, por eso hay test.
 *
 * Verificado ademas en hardware real (2026-07-28): un Sunmi D3 se registro como
 * COUNTERTOP_POS y una Samsung SM-X133 (sw601dp) como TABLET.
 */
class DeviceFormFactorResolverTest {

    // ── Hardware POS: se resuelve por MARCA, no por tamano de pantalla ────────────

    @Test
    fun `una PAX es terminal de mano`() {
        assertEquals("HANDHELD_POS", DeviceFormFactorResolver.resolve("PAX", 800))
    }

    @Test
    fun `una NexGo es terminal de mano`() {
        assertEquals("HANDHELD_POS", DeviceFormFactorResolver.resolve("NEXGO", 320))
    }

    @Test
    fun `un Sunmi es POS de mostrador aunque su pantalla mida como tablet`() {
        // Este es el caso que motiva resolver por marca: un Sunmi de mostrador reporta
        // dimensiones de tablet y NO es una tablet.
        assertEquals("COUNTERTOP_POS", DeviceFormFactorResolver.resolve("SUNMI", 900))
    }

    @Test
    fun `la marca se compara sin importar mayusculas ni espacios`() {
        // Build MANUFACTURER llega en minusculas en muchos equipos ("samsung").
        assertEquals("COUNTERTOP_POS", DeviceFormFactorResolver.resolve("sunmi", 320))
        assertEquals("HANDHELD_POS", DeviceFormFactorResolver.resolve("  Pax  ", 320))
    }

    // ── Hardware generico: decide el ancho de pantalla ────────────────────────────

    @Test
    fun `un telefono comun es PHONE`() {
        assertEquals("PHONE", DeviceFormFactorResolver.resolve("samsung", 411))
    }

    @Test
    fun `una tablet comun es TABLET`() {
        assertEquals("TABLET", DeviceFormFactorResolver.resolve("samsung", 800))
    }

    @Test
    fun `el umbral de 600dp es inclusivo`() {
        // La Samsung SM-X133 con la que se probo reporta 601dp: un solo dp arriba del
        // corte. Si el limite se moviera, esa tablet pasaria a reportarse como telefono.
        assertEquals("PHONE", DeviceFormFactorResolver.resolve("samsung", 599))
        assertEquals("TABLET", DeviceFormFactorResolver.resolve("samsung", 600))
        assertEquals("TABLET", DeviceFormFactorResolver.resolve("samsung", 601))
    }

    // ── Casos degenerados: nunca devuelve algo vacio o invalido ───────────────────

    @Test
    fun `sin fabricante sigue decidiendo por pantalla`() {
        assertEquals("PHONE", DeviceFormFactorResolver.resolve("", 411))
        assertEquals("TABLET", DeviceFormFactorResolver.resolve("", 800))
    }

    @Test
    fun `un ancho absurdo no rompe nada`() {
        assertEquals("PHONE", DeviceFormFactorResolver.resolve("marca rara", 0))
        assertEquals("TABLET", DeviceFormFactorResolver.resolve("marca rara", 99999))
    }

    @Test
    fun `todo resultado es un valor valido del enum del server`() {
        val validos = setOf("PHONE", "TABLET", "HANDHELD_POS", "COUNTERTOP_POS", "DESKTOP", "UNKNOWN")
        val casos = listOf(
            "PAX" to 320, "NEXGO" to 800, "SUNMI" to 400, "samsung" to 411,
            "samsung" to 800, "" to 0, "marca rara" to 99999,
        )
        for ((marca, ancho) in casos) {
            assert(DeviceFormFactorResolver.resolve(marca, ancho) in validos) {
                "resolve($marca, $ancho) devolvio un valor fuera del enum"
            }
        }
    }
}
