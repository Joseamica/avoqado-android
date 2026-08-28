package com.avoqado.pos.announcements

import com.avoqado.pos.announcements.data.parseVentanaPendiente
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El botón del aviso decía literalmente "null" en la tablet.
 *
 * 🔴 La causa no se ve leyendo la pantalla: en kotlinx.serialization `JsonNull` ES un
 * `JsonPrimitive`, y su `.content` vale la CADENA "null". Por eso el respaldo
 * `?: "Ver más"` del gate nunca entraba — el valor no era null, era un texto que
 * decía "null". Sólo salió mirando el aparato.
 */
class VentanaPendienteParseoTest {

    private fun cuerpo(actionLabel: String) = """
        {"success":true,"data":{"modal":{"id":"a1","title":"Terminal nueva",
        "body":"Ya disponible","actionLabel":$actionLabel}}}
    """.trimIndent()

    @Test
    fun `actionLabel null NO se convierte en la cadena null`() {
        assertNull(parseVentanaPendiente(cuerpo("null"))?.etiquetaAccion)
    }

    @Test
    fun `actionLabel ausente tampoco`() {
        val json = """{"success":true,"data":{"modal":{"id":"a1","title":"T","body":"B"}}}"""
        assertNull(parseVentanaPendiente(json)?.etiquetaAccion)
    }

    @Test
    fun `una etiqueta de verdad sí se conserva`() {
        assertEquals("Quiero una", parseVentanaPendiente(cuerpo("\"Quiero una\""))?.etiquetaAccion)
    }

    @Test
    fun `sin ventana pendiente devuelve null`() {
        assertNull(parseVentanaPendiente("""{"success":true,"data":{"modal":null}}"""))
        assertNull(parseVentanaPendiente("""{"success":true,"data":{}}"""))
    }

    @Test
    fun `un cuerpo corrupto no tumba el punto de venta`() {
        assertNull(parseVentanaPendiente("no es json"))
    }

    @Test
    fun `titulo y cuerpo llegan completos`() {
        val v = parseVentanaPendiente(cuerpo("\"Ver\""))
        assertEquals("a1", v?.id)
        assertEquals("Terminal nueva", v?.titulo)
        assertEquals("Ya disponible", v?.cuerpo)
    }
}
