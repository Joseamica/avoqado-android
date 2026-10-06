package com.avoqado.pos.printing

import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.SavedPrinter
import com.avoqado.pos.printing.data.model.TablaDeAcentos
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La Galeterie (5-oct): su impresora de barra ignora `ESC t 16` y se queda en PC437 de fábrica — la «Í» sale «═», la «á»
 * «ß», la «í» «φ» (medido en su página de prueba, letra por letra). Cada impresora elige su tabla; la de siempre no cambia.
 */
class TablaDeAcentosTest {

    private fun bytes(vararg v: Int) = v.map { it.toByte() }.toByteArray()

    @Test
    fun `P1 sin elegir nada el ticket sale byte por byte igual que antes`() {
        // Lo que hacía la app antes de esta opción: ESC t 16 y el texto en Latin-1.
        val antes = ESCPOSPrinter(paperWidth = PaperWidth.MM80).apply { reset(); printLine("Pásalo en la caja · CAFETERÍA ñ ¿") }.getData()
        val ahora = ESCPOSPrinter(paperWidth = PaperWidth.MM80, tablaDeAcentos = TablaDeAcentos.WINDOWS_1252)
            .apply { reset(); printLine("Pásalo en la caja · CAFETERÍA ñ ¿") }.getData()
        assertArrayEquals(antes, ahora)
        assertTrue("Sigue mandando ESC t 16", antes.toList().windowed(3).contains(bytes(0x1B, 0x74, 0x10).toList()))
    }

    @Test
    fun `PC850 manda ESC t 2 y el espanol con sus codigos`() {
        val datos = ESCPOSPrinter(tablaDeAcentos = TablaDeAcentos.PC850).apply { reset(); printLine("áéíóú ÁÉÍÓÚ ñÑ ¿¡ü°") }.getData()
        assertTrue(datos.toList().windowed(3).contains(bytes(0x1B, 0x74, 0x02).toList()))
        val esperado = bytes(0xA0, 0x82, 0xA1, 0xA2, 0xA3, 0x20, 0xB5, 0x90, 0xD6, 0xE0, 0xE9, 0x20, 0xA4, 0xA5, 0x20, 0xA8, 0xAD, 0x81, 0xF8, 0x0A)
        assertArrayEquals(esperado, datos.takeLast(esperado.size).toByteArray())
    }

    @Test
    fun `PC437 manda ESC t 0 y las mayusculas acentuadas que no existen ahi salen sin acento`() {
        val datos = ESCPOSPrinter(tablaDeAcentos = TablaDeAcentos.PC437).apply { reset(); printLine("áéíóú ÁÉÍÓÚ ñÑ ¿") }.getData()
        assertTrue(datos.toList().windowed(3).contains(bytes(0x1B, 0x74, 0x00).toList()))
        val esperado = bytes(0xA0, 0x82, 0xA1, 0xA2, 0xA3, 0x20, 'A'.code, 0x90, 'I'.code, 'O'.code, 'U'.code, 0x20, 0xA4, 0xA5, 0x20, 0xA8, 0x0A)
        assertArrayEquals(esperado, datos.takeLast(esperado.size).toByteArray())
    }

    @Test
    fun `una letra que la tabla no tiene sale sin acento y no rompe el renglon`() {
        assertArrayEquals("a".toByteArray(), TablaDeAcentos.PC437.codificar("ã"))
        assertArrayEquals("o".toByteArray(), TablaDeAcentos.PC850.codificar("ō"))
        assertArrayEquals("?".toByteArray(), TablaDeAcentos.PC850.codificar("€"))
    }

    @Test
    fun `cada caracter ocupa un byte, asi las columnas siguen cuadrando`() {
        val texto = "CAFETERÍA Pásalo aquí ñ"
        TablaDeAcentos.entries.forEach { assertEquals(it.name, texto.length, it.codificar(texto).size) }
    }

    @Test
    fun `la impresora guardada recuerda su tabla y una vieja sin el campo usa la de siempre`() {
        fun guardada(t: String?) = SavedPrinter(id = "p", name = "BARRA", connectionType = "usb", address = "x", tablaDeAcentos = t)
        assertEquals(TablaDeAcentos.WINDOWS_1252, TablaDeAcentos.deGuardada(guardada(null).tablaDeAcentos))
        assertEquals(TablaDeAcentos.PC850, TablaDeAcentos.deGuardada(guardada("PC850").tablaDeAcentos))
        assertEquals("Un valor raro no rompe: la de siempre", TablaDeAcentos.WINDOWS_1252, TablaDeAcentos.deGuardada("XYZ"))
    }

    @Test
    fun `del panel sólo PC850 y PC437 cambian algo, el CP858 de fabrica queda como siempre`() {
        assertEquals(TablaDeAcentos.WINDOWS_1252, TablaDeAcentos.delPanel("CP858"))
        assertEquals(TablaDeAcentos.WINDOWS_1252, TablaDeAcentos.delPanel(null))
        assertEquals(TablaDeAcentos.PC850, TablaDeAcentos.delPanel("pc850"))
        assertEquals(TablaDeAcentos.PC850, TablaDeAcentos.delPanel("CP850"))
        assertEquals(TablaDeAcentos.PC437, TablaDeAcentos.delPanel(" PC437 "))
    }

    @Test
    fun `la pagina de prueba trae la linea de acentos en cada tabla y regresa a la elegida`() {
        val texto = String(ESCPOSPrinter(tablaDeAcentos = TablaDeAcentos.PC850).generateTestPrint(), Charsets.ISO_8859_1)
        TablaDeAcentos.entries.forEach { assertTrue("Falta la línea de ${it.etiqueta}", texto.contains("${it.etiqueta}:")) }
        // Después de las tres líneas la impresora vuelve a la tabla elegida (ESC t 2), no se queda en la última probada.
        val despues = texto.substringAfter("${TablaDeAcentos.entries.last().etiqueta}:")
        assertTrue(despues.contains("\u001Bt\u0002"))
    }
}
