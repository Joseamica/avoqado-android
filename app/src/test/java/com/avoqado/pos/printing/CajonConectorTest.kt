package com.avoqado.pos.printing

import com.avoqado.pos.printing.data.ESCPOSPrinter
import com.avoqado.pos.printing.data.model.PaperWidth
import com.avoqado.pos.printing.data.model.SavedPrinter
import com.avoqado.pos.printing.presentation.conEdiciones
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El pulso que abre el cajón sale por el pin 2 o el pin 5 del conector (ESC p m t1 t2,
 * m = 0 → pin 2, m = 1 → pin 5). Casi todos los cajones usan el 2; uno cableado al 5 no
 * abre con el 2. No se mandan los dos a la vez: con dos cajones en «Y» se abrirían ambos.
 */
class CajonConectorTest {

    private fun comando(pin: Int): ByteArray {
        val escpos = ESCPOSPrinter(paperWidth = PaperWidth.MM80)
        escpos.openCashDrawer(pin)
        return escpos.getData()
    }

    @Test
    fun `pin 2 manda ESC p 0`() {
        assertArrayEquals(byteArrayOf(0x1B, 0x70, 0x00, 0x19, 0xFA.toByte()), comando(2))
    }

    @Test
    fun `pin 5 manda ESC p 1 con el mismo pulso`() {
        assertArrayEquals(byteArrayOf(0x1B, 0x70, 0x01, 0x19, 0xFA.toByte()), comando(5))
    }

    @Test
    fun `un pin desconocido cae al 2`() {
        assertArrayEquals(comando(2), comando(7))
        assertArrayEquals(comando(2), comando(0))
    }

    @Test
    fun `sin argumento sigue siendo el pin 2 de siempre`() {
        val escpos = ESCPOSPrinter(paperWidth = PaperWidth.MM80)
        escpos.openCashDrawer()
        assertArrayEquals(comando(2), escpos.getData())
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `una impresora guardada antes de este cambio lee pin 2`() {
        // Forma exacta de lo guardado en la Sunmi D3 el 2026-09-17.
        val vieja = """{"id":"fe2d","name":"Impresora integrada","connectionType":"internal","address":"internal","dateAdded":1788881784930}"""
        assertEquals(2, json.decodeFromString<SavedPrinter>(vieja).cashDrawerPin)
    }

    @Test
    fun `el pin elegido sobrevive a guardar y leer`() {
        val p = SavedPrinter(name = "Epson", connectionType = "wifi", address = "10.0.0.5", cashDrawerPin = 5)
        assertEquals(5, json.decodeFromString<SavedPrinter>(json.encodeToString(p)).cashDrawerPin)
    }

    @Test
    fun `editar la hoja conserva o cambia el pin`() {
        val p = SavedPrinter(name = "Epson", connectionType = "wifi", address = "10.0.0.5", cashDrawerPin = 5)
        fun SavedPrinter.editar(pin: Int? = null) = conEdiciones(
            name = name, roles = roles, paperWidthMm = paperWidthMm, leftMarginChars = leftMarginChars,
            autoPrintReceipts = autoPrintReceipts, autoPrintKitchenTickets = autoPrintKitchenTickets,
            autoOpenCashDrawer = autoOpenCashDrawer, numberOfCopies = numberOfCopies,
            cashDrawerPin = pin ?: cashDrawerPin,
        )
        assertEquals(5, p.editar().cashDrawerPin)
        assertEquals(2, p.editar(pin = 2).cashDrawerPin)
    }
}
