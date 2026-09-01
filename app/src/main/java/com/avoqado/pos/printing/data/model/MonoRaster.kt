package com.avoqado.pos.printing.data.model

/**
 * Imagen monocroma lista para el ráster de ESC/POS (`GS v 0`): 1 bit por punto,
 * MSB primero, cada fila alineada a byte. 1 = NEGRO (así lo define el comando).
 *
 * Es Kotlin puro a propósito: ESCPOSPrinter no conoce android.graphics y los
 * tests de JVM tampoco. La conversión desde Bitmap vive en RasterImages.kt.
 */
class MonoRaster(
    val widthDots: Int,
    val heightDots: Int,
    val bits: ByteArray,
) {
    val widthBytes: Int = (widthDots + 7) / 8

    init {
        require(widthDots > 0 && heightDots > 0) { "Ráster vacío (${widthDots}x$heightDots)" }
        require(bits.size == widthBytes * heightDots) {
            "bits=${bits.size}, esperaba $widthBytes x $heightDots = ${widthBytes * heightDots}"
        }
    }

    companion object {
        /**
         * Empaqueta luminancias (0 = negro, 255 = blanco) difundiendo el error
         * (Floyd–Steinberg): un logo con grises, color o degradados se imprime
         * como trama en vez de perder zonas enteras contra un umbral fijo — es
         * el modo para el logo del NEGOCIO, que puede ser cualquier imagen.
         *
         * [luminance] se MUTA (la difusión escribe sobre él): pásale una copia
         * si lo vas a reusar.
         */
        fun dither(widthDots: Int, heightDots: Int, luminance: IntArray): MonoRaster {
            require(luminance.size == widthDots * heightDots) { "luminance no corresponde al tamaño" }
            val widthBytes = (widthDots + 7) / 8
            val bits = ByteArray(widthBytes * heightDots)
            for (y in 0 until heightDots) {
                for (x in 0 until widthDots) {
                    val i = y * widthDots + x
                    val old = luminance[i]
                    val black = old < 128
                    if (black) bits[y * widthBytes + (x shr 3)] = (bits[y * widthBytes + (x shr 3)].toInt() or (0x80 shr (x and 7))).toByte()
                    val error = old - if (black) 0 else 255
                    if (x + 1 < widthDots) luminance[i + 1] += error * 7 / 16
                    if (y + 1 < heightDots) {
                        val below = i + widthDots
                        if (x > 0) luminance[below - 1] += error * 3 / 16
                        luminance[below] += error * 5 / 16
                        if (x + 1 < widthDots) luminance[below + 1] += error * 1 / 16
                    }
                }
            }
            return MonoRaster(widthDots, heightDots, bits)
        }

        /**
         * Umbral duro: NEGRO todo lo que no sea casi blanco. Es el modo para
         * arte de marca plano (el isotipo de Avoqado): su verde es CLARO (~73%
         * de luz) y con dithering el anillo saldría como trama deslavada; con
         * el umbral alto sale sólido y nítido. No usar con fotos — ahí todo lo
         * que no es highlight se vuelve una mancha.
         */
        fun threshold(widthDots: Int, heightDots: Int, luminance: IntArray, cutoff: Int = 216): MonoRaster {
            require(luminance.size == widthDots * heightDots) { "luminance no corresponde al tamaño" }
            val widthBytes = (widthDots + 7) / 8
            val bits = ByteArray(widthBytes * heightDots)
            for (y in 0 until heightDots) {
                for (x in 0 until widthDots) {
                    if (luminance[y * widthDots + x] < cutoff) {
                        bits[y * widthBytes + (x shr 3)] = (bits[y * widthBytes + (x shr 3)].toInt() or (0x80 shr (x and 7))).toByte()
                    }
                }
            }
            return MonoRaster(widthDots, heightDots, bits)
        }
    }
}
