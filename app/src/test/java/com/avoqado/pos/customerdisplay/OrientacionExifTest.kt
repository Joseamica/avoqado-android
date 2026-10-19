package com.avoqado.pos.customerdisplay

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * El lector de la orientación EXIF del logo (Codex bloque 3 #2): el logo heredado del negocio no pasa por el panel, y Coil (lo de
 * hoy) sí aplicaba la orientación. JPEG mínimos armados aquí: SOI, segmentos, EOI (al lector no le importan los datos de imagen).
 */
class OrientacionExifTest {

    @Test
    fun `orientacion 6 en Intel tras un APP0 JFIF`() {
        assertEquals(6, orientacionExif(jpeg(app0Jfif(), app1Exif(6, motorola = false))))
    }

    @Test
    fun `orientacion 3 en Motorola`() {
        assertEquals(3, orientacionExif(jpeg(app1Exif(3, motorola = true))))
    }

    @Test
    fun `orientacion 2 espejo`() {
        assertEquals(2, orientacionExif(jpeg(app1Exif(2, motorola = false))))
    }

    @Test
    fun `sin EXIF, PNG, recortado o basura es 1`() {
        assertEquals(1, orientacionExif(jpeg(app0Jfif())))
        assertEquals(1, orientacionExif(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)))
        assertEquals(1, orientacionExif(jpeg(app1Exif(6, motorola = false)).copyOf(20)))
        assertEquals(1, orientacionExif(ByteArray(0)))
        assertEquals(1, orientacionExif("no es imagen".toByteArray()))
        assertEquals(1, orientacionExif(jpeg(app1Exif(9, motorola = false))))   // fuera de 1-8
    }

    private fun jpeg(vararg segmentos: ByteArray): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segmentos.fold(ByteArray(0)) { a, b -> a + b } + byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    private fun segmento(marca: Int, cuerpo: ByteArray): ByteArray {
        val largo = cuerpo.size + 2
        return byteArrayOf(0xFF.toByte(), marca.toByte(), (largo shr 8).toByte(), largo.toByte()) + cuerpo
    }

    private fun app0Jfif() = segmento(0xE0, "JFIF\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0))

    /** APP1 «Exif» con IFD0 de dos entradas: Make (antes, para que el lector recorra) y Orientation. */
    private fun app1Exif(orientacion: Int, motorola: Boolean): ByteArray {
        val tiff = ByteBuffer.allocate(8 + 2 + 12 * 2 + 4).order(if (motorola) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        tiff.put((if (motorola) "MM" else "II").toByteArray(Charsets.ISO_8859_1)).putShort(42).putInt(8)
        tiff.putShort(2)
        tiff.putShort(0x010F).putShort(2).putInt(4).put("AVQ\u0000".toByteArray(Charsets.ISO_8859_1))
        tiff.putShort(0x0112).putShort(3).putInt(1).putShort(orientacion.toShort()).putShort(0)
        tiff.putInt(0)
        return segmento(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff.array())
    }
}
