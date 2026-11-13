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
private data class OverrideRequestBody(
    val pin: String,
    val permission: String,
    /** Sólo si viene (el `Json` de aquí no escribe los default): ata la autorización a UNA operación, p. ej. `llaveDelRetiro`. */
    val operationKey: String? = null,
)

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
 * Pero sí lleva el MISMO `TokenRefreshAuthenticator` que el principal (D16, 13-nov): con la sesión de la cajera
 * vencida, el 401 del token se renueva y el PIN se reintenta una vez, en vez de dejar el teclado en «Tu sesión
 * expiró» mientras la app renovaba por otro lado. El 401 de un PIN equivocado no se renueva (exención del autenticador).
 *
 * El PIN viaja una sola vez, sobre TLS, y NUNCA se guarda en el dispositivo.
 */
@Singleton
open class PermissionOverrideRepository internal constructor(
    private val secureStorage: SecureStorage,
    /** 🔴 Se llama `client` a propósito: escritorio (`Inyector`) le pone su guarda por reflexión a este campo. */
    private val client: OkHttpClient,
) {
    @Inject constructor(secureStorage: SecureStorage, autenticador: TokenRefreshAuthenticator) :
        this(secureStorage, OkHttpClient.Builder().authenticator(autenticador).build())

    private val json = Json { ignoreUnknownKeys = true }

    /** [operationKey]: la autorización sólo servirá para esa operación (el retiro: `llaveDelRetiro`). Sin ella, la suelta de siempre. */
    open suspend fun requestToken(venueId: String, pin: String, permission: String, operationKey: String? = null): OverrideResult =
        withContext(Dispatchers.IO) {
            // Con su sello, como las del principal: si mientras viaja entra otra persona, su 401 no se renueva con la sesión de ella.
            val (sello, accessToken) = secureStorage.selloYAccess()
            if (accessToken == null) return@withContext OverrideResult.Failed("Tu sesión expiró. Vuelve a entrar.")

            val body = json
                .encodeToString(OverrideRequestBody.serializer(), OverrideRequestBody(pin, permission, operationKey))
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/permission-overrides")
                .addHeader("Authorization", "Bearer $accessToken")
                .tag(SelloDePeticion::class.java, SelloDePeticion(sello))
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
                        // 🔴 Dos 401 distintos llegan por aquí y sólo uno es un
                        // PIN malo: `authenticateTokenMiddleware` corre ANTES y
                        // también responde 401 cuando el token de sesión caducó.
                        // Ése ya lo intentó renovar el autenticador; si llega aquí
                        // es que la renovación no se pudo (rechazada, o entró otra
                        // persona). Pintarlos igual mandaba al encargado a
                        // reteclear un código correcto hasta agotar el límite.
                        // El PIN malo trae `code`; el de sesión, no.
                        401 -> {
                            val err = runCatching { json.decodeFromString<OverrideErrorBody>(raw) }.getOrNull()
                            if (err?.code == "OVERRIDE_INVALID_PIN") {
                                OverrideResult.WrongPin
                            } else {
                                OverrideResult.Failed("Tu sesión expiró. Vuelve a entrar.")
                            }
                        }
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
