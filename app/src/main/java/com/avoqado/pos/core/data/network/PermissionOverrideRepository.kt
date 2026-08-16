package com.avoqado.pos.core.data.network

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** Lo que puede pasar al teclear el código de un encargado. */
sealed interface OverrideResult {
    data class Granted(val token: String, val authorizedByName: String) : OverrideResult
    data object WrongPin : OverrideResult
    data object Insufficient : OverrideResult
    data object TooManyAttempts : OverrideResult
    data class Failed(val message: String) : OverrideResult
}

@Serializable
private data class OverrideRequestBody(val pin: String, val permission: String)

@Serializable
private data class OverrideResponseBody(val success: Boolean = false, val data: OverrideData? = null)

@Serializable
private data class OverrideData(
    val token: String,
    val expiresAt: String? = null,
    val authorizedBy: AuthorizedBy? = null,
)

@Serializable
private data class AuthorizedBy(val id: String, val name: String)

@Serializable
private data class OverrideErrorBody(val code: String? = null, val message: String? = null)

/**
 * Pide el token de autorización de gerente.
 *
 * 🔴 Usa su PROPIO OkHttpClient a propósito. El cliente compartido lleva el
 * `ForbiddenInterceptor`, que es justo quien llama aquí — inyectarlo crearía un
 * ciclo en Hilt. Es el mismo recurso que ya usa `TokenRefreshAuthenticator`
 * para refrescar el token.
 *
 * El PIN viaja una sola vez, sobre TLS, y NUNCA se guarda en el dispositivo.
 */
@Singleton
open class PermissionOverrideRepository @Inject constructor(
    private val secureStorage: SecureStorage,
) {
    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    open suspend fun requestToken(venueId: String, pin: String, permission: String): OverrideResult =
        withContext(Dispatchers.IO) {
            val accessToken = secureStorage.accessToken
                ?: return@withContext OverrideResult.Failed("Tu sesión expiró. Vuelve a entrar.")

            val body = json
                .encodeToString(OverrideRequestBody.serializer(), OverrideRequestBody(pin, permission))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/permission-overrides")
                .addHeader("Authorization", "Bearer $accessToken")
                // El 403 de este endpoint es un rechazo de NEGOCIO ("ese código
                // tampoco puede"), no falta de permisos del que está en la caja:
                // se interpreta aquí, no en el diálogo genérico.
                .addHeader(ForbiddenInterceptor.LOCAL_ERROR_HEADER, "true")
                .post(body)
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    when (response.code) {
                        200, 201 -> {
                            val data = runCatching {
                                json.decodeFromString<OverrideResponseBody>(raw)
                            }.getOrNull()?.data
                            if (data == null) {
                                OverrideResult.Failed("No se pudo obtener la autorización.")
                            } else {
                                OverrideResult.Granted(data.token, data.authorizedBy?.name.orEmpty())
                            }
                        }
                        401 -> OverrideResult.WrongPin
                        403 -> {
                            val err = runCatching { json.decodeFromString<OverrideErrorBody>(raw) }.getOrNull()
                            if (err?.code == "OVERRIDE_INSUFFICIENT") {
                                OverrideResult.Insufficient
                            } else {
                                OverrideResult.Failed(err?.message ?: "No se pudo autorizar.")
                            }
                        }
                        429 -> OverrideResult.TooManyAttempts
                        else -> OverrideResult.Failed(
                            ServerErrorText.fromResponseBody(raw, "No se pudo autorizar."),
                        )
                    }
                }
            } catch (e: Exception) {
                // 🔴 Sin red NO se encola: un rechazo de permiso no es un fallo de
                // red, y encolarlo daría por autorizado algo que nadie autorizó.
                Log.e("🔐 Override", "Falló la petición de autorización: ${e.message}")
                OverrideResult.Failed("Necesitas conexión para pedir autorización")
            }
        }
}
