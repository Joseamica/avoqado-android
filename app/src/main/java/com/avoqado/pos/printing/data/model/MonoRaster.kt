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
         * Todo lo MÁS CLARO que esto se imprime como blanco puro, antes de
         * difundir el error. Es el piso; [autoWhitePoint] lo baja cuando la
         * imagen trae un fondo gris.
         */
        const val DEFAULT_WHITE_POINT = 245

        /** Un borde con mediana por debajo de esto es un logo sobre fondo OSCURO: no se toca. */
        const val MIN_LIGHT_BACKGROUND = 200

        /** Si el punto más oscuro del borde baja de esto, hay ARTE tocando el borde: no se toca. */
        const val MIN_BORDER_FLOOR = 180

        /**
         * El "blanco" de la imagen es su FONDO, no el 255 — y el fondo se lee
         * en el borde, que en un logo es siempre fondo.
         *
         * 🔴 Medido en papel el 2026-09-01: el logo de un venue real salía con un
         * marco gris sucio (**3.9 % de puntos negros en el borde**). La primera
         * hipótesis —ruido JPEG alrededor del blanco— era FALSA: al sacar el JPG
         * del aparato y medirlo, el borde era un gris claro UNIFORME (100 % de
         * los puntos entre 230 y 239), que un umbral fijo en 245 nunca alcanza.
         * Floyd–Steinberg hacía bien su trabajo: un fondo al 92 % de luz ES una
         * trama al 8 %. En papel, el fondo tiene que ser papel.
         *
         * Devuelve el punto blanco a usar: si el borde es claro y sin arte, el
         * punto más oscuro del fondo (percentil 1, con 2 de margen); si no, el
         * default. Ordenar el borde cuesta nada comparado con el ráster.
         */
        fun autoWhitePoint(widthDots: Int, heightDots: Int, luminance: IntArray, ring: Int = 4): Int {
            require(luminance.size == widthDots * heightDots) { "luminance no corresponde al tamaño" }
            val border = ArrayList<Int>()
            for (y in 0 until heightDots) {
                for (x in 0 until widthDots) {
                    if (x < ring || x >= widthDots - ring || y < ring || y >= heightDots - ring) {
                        border += luminance[y * widthDots + x]
                    }
                }
            }
            if (border.isEmpty()) return DEFAULT_WHITE_POINT
            border.sort()
            val median = border[border.size / 2]
            val p01 = border[border.size / 100]
            if (median < MIN_LIGHT_BACKGROUND || p01 < MIN_BORDER_FLOOR) return DEFAULT_WHITE_POINT
            return minOf(DEFAULT_WHITE_POINT, p01 - 2)
        }

        /**
         * Empaqueta luminancias (0 = negro, 255 = blanco) difundiendo el error
         * (Floyd–Steinberg): un logo con grises, color o degradados se imprime
         * como trama en vez de perder zonas enteras contra un umbral fijo — es
         * el modo para el logo del NEGOCIO, que puede ser cualquier imagen.
         *
         * [luminance] se MUTA (la difusión escribe sobre él): pásale una copia
         * si lo vas a reusar.
         */
        fun dither(
            widthDots: Int,
            heightDots: Int,
            luminance: IntArray,
            whitePoint: Int = DEFAULT_WHITE_POINT,
        ): MonoRaster {
            require(luminance.size == widthDots * heightDots) { "luminance no corresponde al tamaño" }
            // Blanquear ANTES de difundir: si se hiciera después, el error ya
            // se habría repartido a los vecinos y el ruido seguiría ahí.
            for (i in luminance.indices) {
                if (luminance[i] >= whitePoint) luminance[i] = 255
            }
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
