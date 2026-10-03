package com.avoqado.pos.escritorio.impresion

import com.sun.jna.Structure

/**
 * El DCB de winbase.h, declarado aquí y no el de jna-platform: en la 5.19.1 `WinBase.DCB` declara XonChar, XoffChar,
 * ErrorChar, EofChar y EvtChar como `char` de Java, que JNA pasa como wchar_t (2 bytes en Windows). Mide 36 en vez de
 * 28 y Windows leería y escribiría esos campos corridos. Aquí son BYTE: 28 bytes, XonChar en 21 y wReserved1 en 26
 * (DcbDeWindowsTest lo comprueba en la Mac).
 *
 * El campo de bits (fBinary, fParity, …) no tiene nombre en winbase.h: aquí es `bits`, con las máscaras de abajo.
 */
@Suppress("PropertyName")
@Structure.FieldOrder(
    "DCBlength", "BaudRate", "bits", "wReserved", "XonLim", "XoffLim",
    "ByteSize", "Parity", "StopBits", "XonChar", "XoffChar", "ErrorChar", "EofChar", "EvtChar", "wReserved1",
)
internal class DCB : Structure() {
    @JvmField var DCBlength: Int = 0
    @JvmField var BaudRate: Int = 0
    @JvmField var bits: Int = 0
    @JvmField var wReserved: Short = 0
    @JvmField var XonLim: Short = 0
    @JvmField var XoffLim: Short = 0
    @JvmField var ByteSize: Byte = 0
    @JvmField var Parity: Byte = 0
    @JvmField var StopBits: Byte = 0
    @JvmField var XonChar: Byte = 0
    @JvmField var XoffChar: Byte = 0
    @JvmField var ErrorChar: Byte = 0
    @JvmField var EofChar: Byte = 0
    @JvmField var EvtChar: Byte = 0
    @JvmField var wReserved1: Short = 0

    init { DCBlength = size() }

    /** Dónde cae un campo (para la prueba del layout). */
    fun desplazamiento(campo: String): Int = fieldOffset(campo)
}

// Máscaras del campo de bits del DCB (winbase.h).
internal const val F_BINARY = 1 shl 0
internal const val F_PARITY = 1 shl 1
internal const val F_OUTX_CTS_FLOW = 1 shl 2
internal const val F_OUTX_DSR_FLOW = 1 shl 3
internal const val F_DTR_CONTROL = 0b11 shl 4
internal const val F_DSR_SENSITIVITY = 1 shl 6
internal const val F_OUTX = 1 shl 8
internal const val F_INX = 1 shl 9
internal const val F_RTS_CONTROL = 0b11 shl 12
internal const val F_ABORT_ON_ERROR = 1 shl 14

/**
 * Entre GetCommState y SetCommState: se respeta lo que Windows tiene configurado en ese puerto (velocidad, bits, paridad,
 * control de flujo, DTR/RTS), porque hay térmicas que SÍ necesitan su control de flujo (DTR/DSR o XON/XOFF) para no
 * perder bytes en un ticket largo. Sólo se fija:
 *  - fBinary = 1: Win32 no acepta otra cosa.
 *  - fAbortOnError = 0: con él prendido, tras un error de comunicación Windows rechaza toda operación hasta un
 *    ClearCommError, que aquí no se llama.
 *  - BaudRate, SÓLO si la dirección la trae escrita (`usb:com:COM3:115200`). «Buscar» guarda sin velocidad.
 */
internal fun prepararDcb(dcb: DCB, baudios: Int?) {
    dcb.bits = (dcb.bits or F_BINARY) and F_ABORT_ON_ERROR.inv()
    if (baudios != null) dcb.BaudRate = baudios
}
