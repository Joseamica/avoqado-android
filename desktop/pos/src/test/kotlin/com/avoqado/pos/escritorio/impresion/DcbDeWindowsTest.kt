package com.avoqado.pos.escritorio.impresion

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * El DCB que se le pasa a GetCommState/SetCommState. El de jna-platform 5.19.1 declara XonChar…EvtChar como `char` de
 * Java (wchar_t: 2 bytes en Windows, 4 en la Mac) y mide 36 en Windows: Windows lee y escribe los campos corridos.
 */
class DcbDeWindowsTest {
    /** #1: winbase.h — 3 DWORD, 3 WORD, 8 BYTE y un WORD final: 28 bytes, XonChar en 21 y wReserved1 en 26. */
    @Test fun `el DCB mide 28 bytes y sus campos caen donde los pone winbase h`() {
        val dcb = DCB()
        assertEquals(28, dcb.size())
        assertEquals(28, dcb.DCBlength, "DCBlength = sizeof(DCB)")
        assertEquals(8, dcb.desplazamiento("bits"))
        assertEquals(18, dcb.desplazamiento("ByteSize"))
        assertEquals(21, dcb.desplazamiento("XonChar"))
        assertEquals(25, dcb.desplazamiento("EvtChar"))
        assertEquals(26, dcb.desplazamiento("wReserved1"))
    }

    private val flujo = F_OUTX_CTS_FLOW or F_OUTX_DSR_FLOW or F_DTR_CONTROL or F_OUTX or F_INX or F_RTS_CONTROL

    /**
     * #13, #14 y #26: SIN velocidad en la dirección se respeta TODO lo que Windows tiene configurado en ese puerto
     * (velocidad, 8N1 o lo que sea, control de flujo, DTR/RTS). Sólo se fija lo que Win32 exige (fBinary) y se apaga
     * fAbortOnError, porque no se lleva el protocolo de ClearCommError.
     */
    @Test fun `sin velocidad se respeta la configuracion del puerto en Windows`() {
        val todosLosBits = 0b0111_1111_1111_1110                                        // bits 1 a 14 prendidos, fBinary (bit 0) apagado
        val dcb = DCB().apply {
            BaudRate = 19_200; ByteSize = 7; Parity = 2; StopBits = 2
            bits = todosLosBits
            XonChar = 0x11; XoffChar = 0x13
        }
        prepararDcb(dcb, baudios = null)
        assertEquals(19_200, dcb.BaudRate)
        assertEquals(7, dcb.ByteSize.toInt()); assertEquals(2, dcb.Parity.toInt()); assertEquals(2, dcb.StopBits.toInt())
        assertEquals(0x11, dcb.XonChar.toInt()); assertEquals(0x13, dcb.XoffChar.toInt())
        assertEquals((todosLosBits or F_BINARY) and F_ABORT_ON_ERROR.inv(), dcb.bits, "sólo cambian fBinary y fAbortOnError")
        assertEquals(flujo, dcb.bits and flujo, "el control de flujo de Windows queda intacto")
        assertEquals(F_BINARY, dcb.bits and F_BINARY)
        assertEquals(0, dcb.bits and F_ABORT_ON_ERROR)
    }

    @Test fun `una velocidad escrita en la direccion se fija y lo demas se respeta`() {
        val dcb = DCB().apply { BaudRate = 9_600; ByteSize = 8; bits = F_ABORT_ON_ERROR or F_OUTX_CTS_FLOW }
        prepararDcb(dcb, baudios = 115_200)
        assertEquals(115_200, dcb.BaudRate)
        assertEquals(8, dcb.ByteSize.toInt())
        assertEquals(F_BINARY or F_OUTX_CTS_FLOW, dcb.bits)
    }

    /** Las máscaras, tal cual las posiciones de los campos de bits de winbase.h. */
    @Test fun `las mascaras del campo de bits`() {
        assertEquals(listOf(0x1, 0x4, 0x8, 0x30, 0x100, 0x200, 0x3000, 0x4000),
            listOf(F_BINARY, F_OUTX_CTS_FLOW, F_OUTX_DSR_FLOW, F_DTR_CONTROL, F_OUTX, F_INX, F_RTS_CONTROL, F_ABORT_ON_ERROR))
    }
}
