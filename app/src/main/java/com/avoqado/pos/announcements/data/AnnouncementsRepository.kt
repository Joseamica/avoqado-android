package com.avoqado.pos.announcements.data

import android.util.Log
import com.avoqado.pos.announcements.data.model.AnnouncementDetail
import com.avoqado.pos.announcements.data.model.AnnouncementDetailResponse
import com.avoqado.pos.announcements.data.model.ContentBlock
import com.avoqado.pos.announcements.data.model.bloquesDe
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anuncios de plataforma. Espejo de `AnnouncementsRepository.swift` en iOS.
 *
 * 🔴 El detalle se abre DENTRO de la app, no navegando a una ruta: el aviso llega sin
 * `actionUrl` propio y se reconoce por `entityType == "PlatformAnnouncement"`. Una
 * versión anterior mandaba a `/announcements/<id>` y en el dashboard daba 404.
 */
@Singleton
class AnnouncementsRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    data class Detalle(val anuncio: AnnouncementDetail, val bloques: List<ContentBlock>)

    suspend fun fetchDetail(announcementId: String): Detalle? {
        val token = secureStorage.accessToken ?: return null
        return try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/announcements/$announcementId")
                .header("Authorization", "Bearer $token")
                .build()

            val cuerpo = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    r.body?.string()
                }
            } ?: return null

            val parsed = json.decodeFromString<AnnouncementDetailResponse>(cuerpo)
            val anuncio = parsed.data?.announcement ?: return null
            Detalle(anuncio, bloquesDe(cuerpo, json))
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo cargar el anuncio", e)
            null
        }
    }

    /**
     * ¿Hay un anuncio que deba interrumpir ahora?
     *
     * Devuelve el id del anuncio marcado como ventana cuyo aviso sigue SIN LEER. Al
     * cerrarlo queda leído, así que interrumpe una sola vez y después vive en el buzón —
     * es exactamente lo que se pidió, y lo que evita que la gente aprenda a cerrar
     * ventanas sin leerlas.
     *
     * Si algo falla, devuelve null: un anuncio nunca puede impedir usar el punto de venta.
     */
    /** Lo mínimo para el aviso chico: qué es, sin cargar el detalle completo. */
    data class VentanaPendiente(val id: String, val titulo: String, val cuerpo: String, val etiquetaAccion: String?)

    suspend fun fetchVentanaPendiente(): VentanaPendiente? {
        val token = secureStorage.accessToken ?: return null
        return try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/announcements/home")
                .header("Authorization", "Bearer $token")
                .build()

            val cuerpo = withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    r.body?.string()
                }
            } ?: return null

            parseVentanaPendiente(cuerpo)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo consultar si hay anuncio que mostrar", e)
            null
        }
    }

    /** Registra que lo abrió. Si falla no pasa nada: es medición, no funcionalidad. */
    suspend fun recordOpen(announcementId: String) = post("$announcementId/open")

    /** Registra que tocó el botón del anuncio. */
    suspend fun recordCta(announcementId: String) = post("$announcementId/cta")

    /**
     * Cierra la ventana: a partir de aquí el anuncio vive sólo en el buzón.
     *
     * 🔴 Marca propia, NO el estado de leído: la campana del dashboard marca todo como
     * leído nada más abrirla, así que asomarse al buzón apagaba una ventana que la
     * persona nunca vio.
     */
    suspend fun recordDismiss(announcementId: String) = post("$announcementId/dismiss")

    private suspend fun post(path: String) {
        val token = secureStorage.accessToken ?: return
        try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/announcements/$path")
                .header("Authorization", "Bearer $token")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()
            withContext(Dispatchers.IO) { client.newCall(request).execute().close() }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo registrar la interacción con el anuncio", e)
        }
    }

    private companion object {
        const val TAG = "AnnouncementsRepo"
    }
}

/**
 * El parseo del aviso chico, aparte de la red para poder probarlo.
 *
 * 🔴 Nace de un defecto que SÓLO se vio en la tablet: el botón decía literalmente
 * "null". En kotlinx.serialization `JsonNull` ES un `JsonPrimitive` y su `.content`
 * vale la CADENA "null", así que el respaldo `?: "Ver más"` del gate nunca entraba —
 * el valor no era nulo, era un texto que decía "null". `texto()` sólo acepta el
 * contenido cuando el primitivo es de verdad una cadena.
 */
internal fun parseVentanaPendiente(cuerpo: String): AnnouncementsRepository.VentanaPendiente? {
    val root = runCatching { Json.parseToJsonElement(cuerpo).jsonObject }.getOrNull() ?: return null
    val data = root["data"]?.takeIf { it !is JsonNull }?.jsonObject ?: return null
    val modal = data["modal"]?.takeIf { it !is JsonNull }?.jsonObject ?: return null
    val id = modal.texto("id") ?: return null
    return AnnouncementsRepository.VentanaPendiente(
        id = id,
        titulo = modal.texto("title").orEmpty(),
        cuerpo = modal.texto("body").orEmpty(),
        etiquetaAccion = modal.texto("actionLabel"),
    )
}

private fun JsonObject.texto(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
