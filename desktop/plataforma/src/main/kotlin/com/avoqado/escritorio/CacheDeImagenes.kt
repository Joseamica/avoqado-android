package com.avoqado.escritorio

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.net.URI
import java.util.Collections

/** Imágenes por URL con caché en memoria (las fotos de productos). */
object CacheDeImagenes {
    // ponytail: LRU de 200 en memoria, sin disco; agregar caché en disco si el catálogo sin red lo pide.
    private val memoria: MutableMap<String, ImageBitmap> = Collections.synchronizedMap(
        object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<String, ImageBitmap>) = size > 200
        },
    )

    fun enMemoria(url: String): ImageBitmap? = memoria[url]

    fun cargar(url: String): ImageBitmap? = memoria[url] ?: runCatching {
        val conexion = URI(url).toURL().openConnection().apply { connectTimeout = 5_000; readTimeout = 15_000 }
        val bytes = conexion.getInputStream().use { it.readBytes() }
        org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }.onFailure { android.util.Log.w("Imagen", "No se pudo cargar $url: ${it.message}") }
        .getOrNull()?.also { memoria[url] = it }
}
