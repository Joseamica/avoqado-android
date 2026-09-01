package com.avoqado.pos.printing.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cache en disco del logo del negocio para el ticket impreso.
 *
 * 🔴 Cache-first, como `PrintConfigRepository`: al IMPRIMIR jamás se toca la
 * red — se usa lo que haya en disco o no se imprime logo. La descarga ocurre en
 * el refresh de settings (con red), y un refresh fallido NUNCA borra el archivo
 * bueno: un logo ligeramente viejo es infinitamente menos dañino que un ticket
 * sin logo porque el WiFi parpadeó.
 */
@Singleton
class ReceiptLogoCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    // Cliente PROPIO y pelón: el logo vive en el Storage público (otro host).
    // El OkHttp de la app arrastra interceptores de auth/refresh que aquí no
    // pintan nada — un 401 raro del Storage no puede ni refrescar ni cerrar
    // la sesión del cajero.
    private val client = OkHttpClient()

    private fun dir() = File(context.filesDir, "receipt-logos").apply { mkdirs() }

    private fun imageFile(venueId: String) = File(dir(), "$venueId.img")

    private fun urlFile(venueId: String) = File(dir(), "$venueId.url")

    /** Lo que haya en disco, sin tocar red. Null si nunca se ha podido bajar. */
    fun cachedBitmap(venueId: String): Bitmap? =
        runCatching { BitmapFactory.decodeFile(imageFile(venueId).absolutePath) }.getOrNull()

    /**
     * Baja el logo cuando la URL cambió o nunca se ha bajado. URL vacía/null =
     * el venue NO tiene logo: ahí sí se borra el cache (cambio deliberado del
     * negocio, no un fallo). El reemplazo es atómico (tmp + rename) y sólo
     * ocurre tras validar que los bytes DECODIFICAN — una descarga a medias no
     * puede pisar un logo bueno.
     */
    suspend fun refresh(venueId: String, logoUrl: String?) = withContext(Dispatchers.IO) {
        runCatching {
            if (logoUrl.isNullOrBlank()) {
                imageFile(venueId).delete()
                urlFile(venueId).delete()
                return@runCatching
            }
            val current = urlFile(venueId).takeIf { it.exists() }?.readText()
            if (current == logoUrl && imageFile(venueId).exists()) return@runCatching

            client.newCall(Request.Builder().url(logoUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@use
                val bytes = response.body?.bytes() ?: return@use
                if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) == null) return@use
                val tmp = File(dir(), "$venueId.tmp")
                tmp.writeBytes(bytes)
                if (tmp.renameTo(imageFile(venueId))) {
                    urlFile(venueId).writeText(logoUrl)
                    Log.d("🖨️", "Logo del ticket actualizado para $venueId")
                } else {
                    tmp.delete()
                }
            }
        }.onFailure { Log.w("🖨️", "No se pudo bajar el logo del ticket (se conserva el anterior): ${it.message}") }
    }
}
