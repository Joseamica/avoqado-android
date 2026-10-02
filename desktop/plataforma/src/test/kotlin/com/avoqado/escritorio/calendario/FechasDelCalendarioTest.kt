package com.avoqado.escritorio.calendario

import java.util.Locale
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FechasDelCalendarioTest {
    private val zonaOriginal = TimeZone.getDefault()
    @AfterTest fun restaurar() { TimeZone.setDefault(zonaOriginal); CalendarioDeEscritorio.cancelar() }

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

    @Test fun `1 de octubre de 2026 es medianoche UTC y el mes va de 0 a 11`() {
        assertEquals(1_790_812_800_000L, FechaDeAndroid(2026, 9, 1).aMilisUtc())   // 2026-10-01T00:00:00Z
        assertEquals(FechaDeAndroid(2026, 9, 1), fechaDeMilisUtc(1_790_812_800_000L))
    }

    @Test fun `el dia no se corre con la zona de la PC`() {
        for (zona in listOf("America/Mexico_City", "Pacific/Kiritimati", "Pacific/Pago_Pago", "UTC")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zona))
            val f = FechaDeAndroid(2026, 1, 28)
            assertEquals(f, fechaDeMilisUtc(f.aMilisUtc()), "zona $zona")
            // Lo que devuelve el DatePicker al elegir el 20: medianoche UTC del 20, aunque en la zona local sea el 19.
            assertEquals(FechaDeAndroid(2026, 9, 20), fechaDeMilisUtc(FechaDeAndroid(2026, 9, 20).aMilisUtc()), "zona $zona")
            // Cada dirección por separado contra la constante (2026-10-20T00:00Z): dos conversiones equivocadas con la misma
            // zona se cancelan en la vuelta completa de arriba; éstas no.
            assertEquals(1_792_454_400_000L, FechaDeAndroid(2026, 9, 20).aMilisUtc(), "zona $zona")
            assertEquals(FechaDeAndroid(2026, 9, 20), fechaDeMilisUtc(1_792_454_400_000L), "zona $zona")
        }
    }

    @Test fun `una fecha rara se normaliza como Calendar y no lanza`() {
        assertEquals(FechaDeAndroid(2026, 9, 1), fechaDeMilisUtc(FechaDeAndroid(2026, 8, 31).aMilisUtc()))   // 31 de sept = 1 de oct
        assertEquals(FechaDeAndroid(2027, 0, 1), fechaDeMilisUtc(FechaDeAndroid(2026, 12, 1).aMilisUtc()))   // mes 12 = enero siguiente
    }

    @Test fun `aceptar llama al listener de la ultima solicitud y la limpia`() {
        val vistas = mutableListOf<String>()
        CalendarioDeEscritorio.pedir(SolicitudDeCalendario(FechaDeAndroid(2026, 9, 1)) { vistas += "primera $it" })
        CalendarioDeEscritorio.pedir(SolicitudDeCalendario(FechaDeAndroid(2026, 9, 5)) { vistas += "segunda $it" })
        CalendarioDeEscritorio.elegir(FechaDeAndroid(2026, 9, 7))
        assertEquals(listOf("segunda ${FechaDeAndroid(2026, 9, 7)}"), vistas)
        assertNull(CalendarioDeEscritorio.pendiente)
    }

    @Test fun `cancelar no llama a nadie`() {
        var llamado = false
        CalendarioDeEscritorio.pedir(SolicitudDeCalendario(FechaDeAndroid(2026, 9, 1)) { llamado = true })
        CalendarioDeEscritorio.cancelar()
        CalendarioDeEscritorio.elegir(FechaDeAndroid(2026, 9, 2))   // un «Aceptar» tardío tras cancelar no hace nada
        assertEquals(false, llamado)
        assertNull(CalendarioDeEscritorio.pendiente)
    }

    @Test fun `el sustituto de android_app_DatePickerDialog pide el calendario con su fecha y su listener`() {
        val vistas = mutableListOf<Triple<Int, Int, Int>>()
        android.app.DatePickerDialog(null, { vista, y, m, d -> requireNotNull(vista); vistas += Triple(y, m, d) }, 2026, 9, 15).show()
        assertEquals(FechaDeAndroid(2026, 9, 15), CalendarioDeEscritorio.pendiente?.inicial)
        CalendarioDeEscritorio.elegir(FechaDeAndroid(2026, 9, 16))
        assertEquals(listOf(Triple(2026, 9, 16)), vistas)
    }

    @Test fun `con un locale de calendario no gregoriano el anio sigue siendo gregoriano`() = conLocaleRestaurado {
        Locale.setDefault(Locale.forLanguageTag("th-TH-u-ca-buddhist"))
        assertEquals(1_790_812_800_000L, FechaDeAndroid(2026, 9, 1).aMilisUtc())
        assertEquals(FechaDeAndroid(2026, 9, 1), fechaDeMilisUtc(1_790_812_800_000L))
    }

    @Test fun `una fecha inicial fuera de 1900-2100 no truena el calendario y abre en hoy`() {
        assertEquals(null, inicialSegura(FechaDeAndroid(1800, 0, 1)))
        assertEquals(null, inicialSegura(FechaDeAndroid(2200, 0, 1)))
        assertEquals(null, inicialSegura(FechaDeAndroid(-2025, 9, 1)))   // año negativo: Calendar lo vuelve «2026 a. C.»
        assertEquals(FechaDeAndroid(2026, 9, 1).aMilisUtc(), inicialSegura(FechaDeAndroid(2026, 9, 1)))
    }
}
