package com.avoqado.pos.escritorio

import java.util.Calendar
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class IdiomaDeLaAppTest {
    private fun l(t: String) = Locale.forLanguageTag(t)

    /** Guarda y restaura los TRES valores del locale (general, pantalla, formatos): restaurar sólo el general pisa las categorías. */
    private fun conLocaleRestaurado(bloque: () -> Unit) {
        val general = Locale.getDefault()
        val pantalla = Locale.getDefault(Locale.Category.DISPLAY)
        val formatos = Locale.getDefault(Locale.Category.FORMAT)
        try { bloque() } finally {
            Locale.setDefault(general)
            Locale.setDefault(Locale.Category.DISPLAY, pantalla)
            Locale.setDefault(Locale.Category.FORMAT, formatos)
        }
    }

    @Test fun `Windows en ingles con formatos de Mexico usa es-MX`() =
        assertEquals(l("es-MX"), idiomaDeLaApp(pantalla = l("en-US"), formatos = l("es-MX")))

    @Test fun `Windows en espanol conserva su pais`() =
        assertEquals(l("es-AR"), idiomaDeLaApp(pantalla = l("es-AR"), formatos = l("es-AR")))

    @Test fun `todo en ingles cae a es-MX`() =
        assertEquals(l("es-MX"), idiomaDeLaApp(pantalla = l("en-US"), formatos = l("en-US")))

    @Test fun `pantalla en espanol gana a formatos de otro pais`() =
        assertEquals(l("es-ES"), idiomaDeLaApp(pantalla = l("es-ES"), formatos = l("en-GB")))

    // P1 (Codex, 1-oct): Locale.setDefault(x) también pisa FORMAT. Con pantalla es-ES y formatos es-MX el conteo de
    // inventario formatea 1.5 como "1,50" (String.format sin locale) y toDoubleOrNull() lo guarda como CERO.
    @Test fun `el idioma de la app no cambia los formatos - pantalla es-ES con formatos es-MX sigue escribiendo 1_50`() =
        conLocaleRestaurado {
            Locale.setDefault(l("es-ES"))
            Locale.setDefault(Locale.Category.FORMAT, l("es-MX"))
            aplicarIdiomaDeLaApp()
            assertEquals("1.50", String.format("%.2f", 1.5))
            assertEquals(1.5, "1.50".toDoubleOrNull())
            assertEquals(1.5, String.format("%.2f", 1.5).toDoubleOrNull())   // el camino de InventoryViewModel: formatear y releer
            assertEquals(l("es-MX"), Locale.getDefault(Locale.Category.FORMAT))
            assertEquals("es", Locale.getDefault().language)
        }

    @Test fun `pantalla en-US con formatos en-GB - la semana sigue empezando en lunes y la app habla es-MX`() =
        conLocaleRestaurado {
            Locale.setDefault(l("en-US"))
            Locale.setDefault(Locale.Category.FORMAT, l("en-GB"))
            aplicarIdiomaDeLaApp()
            assertEquals(l("en-GB"), Locale.getDefault(Locale.Category.FORMAT))
            assertEquals(Calendar.MONDAY, Calendar.getInstance().firstDayOfWeek)
            assertEquals(l("es-MX"), Locale.getDefault())
        }
}
