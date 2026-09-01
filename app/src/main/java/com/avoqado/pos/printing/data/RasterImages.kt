package com.avoqado.pos.printing.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.avoqado.pos.printing.data.model.MonoRaster
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Bitmap → [MonoRaster] para el ráster ESC/POS. La aritmética de bits vive en
 * MonoRaster (Kotlin puro, con tests); aquí solo queda lo que necesita
 * android.graphics: escalar, compositar y medir luminancia.
 */
object RasterImages {

    /**
     * Convierte un bitmap a ráster monocromo del ancho pedido, preservando la
     * proporción (con tope de alto para que un logo vertical no se coma medio
     * rollo). Composita sobre BLANCO antes de medir luminancia — los PNG con
     * fondo transparente traen RGB basura en los pixeles invisibles y sin esto
     * el fondo sale como ruido negro.
     *
     * @param inkThreshold null ⇒ dithering Floyd–Steinberg (logos arbitrarios,
     *   fotos, grises). Con valor (0..255) ⇒ umbral duro: negro todo lo más
     *   oscuro que el umbral — para arte plano de marca que debe salir sólido.
     */
    fun toMonoRaster(
        bitmap: Bitmap,
        targetWidthDots: Int,
        maxHeightDots: Int = 300,
        inkThreshold: Int? = null,
    ): MonoRaster? {
        if (bitmap.width <= 0 || bitmap.height <= 0 || targetWidthDots <= 0) return null

        var w = min(targetWidthDots, bitmap.width * 4) // no agrandar más de 4×: saldría pixelado
        var h = (bitmap.height * (w / bitmap.width.toFloat())).roundToInt().coerceAtLeast(1)
        if (h > maxHeightDots) {
            h = maxHeightDots
            w = (bitmap.width * (h / bitmap.height.toFloat())).roundToInt().coerceAtLeast(1)
        }

        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== bitmap) scaled.recycle()

        val luminance = IntArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val alpha = (p ushr 24) and 0xFF
            val r = (p ushr 16) and 0xFF
            val g = (p ushr 8) and 0xFF
            val b = p and 0xFF
            // Luminancia perceptual, compositada sobre blanco por el alfa.
            val lum = (0.2126f * r + 0.7152f * g + 0.0722f * b)
            luminance[i] = ((lum * alpha + 255f * (255 - alpha)) / 255f).roundToInt().coerceIn(0, 255)
        }

        return if (inkThreshold != null) {
            MonoRaster.threshold(w, h, luminance, inkThreshold)
        } else {
            // El fondo del logo se imprime como papel, sea 255 o un gris claro.
            MonoRaster.dither(w, h, luminance, whitePoint = MonoRaster.autoWhitePoint(w, h, luminance))
        }
    }

    /**
     * El isotipo de Avoqado para el pie del ticket, cacheado por ancho (se
     * decodifica del drawable UNA vez; imprimir no puede pagar un decode por
     * ticket). Umbral duro y alto (232) a propósito: el verde de la marca es
     * claro y con dithering el anillo saldría como trama deslavada — así sale
     * sólido, y el centro blanco de la "Q" se conserva.
     */
    @Volatile
    private var cachedMark: Pair<Int, MonoRaster>? = null

    fun avoqadoMark(context: Context, widthDots: Int): MonoRaster? {
        cachedMark?.let { (w, raster) -> if (w == widthDots) return raster }
        val bitmap = BitmapFactory.decodeResource(context.resources, com.avoqado.pos.R.drawable.avoqado_logo_mark)
            ?: return null
        val raster = toMonoRaster(bitmap, targetWidthDots = widthDots, inkThreshold = 232)
        bitmap.recycle()
        if (raster != null) cachedMark = widthDots to raster
        return raster
    }
}
