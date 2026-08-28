package com.avoqado.pos.core.data.network

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class TokenRefreshAuthenticator @Inject constructor(
    private val secureStorage: SecureStorage,
) : Authenticator {

    private val json = Json { ignoreUnknownKeys = true }

    private val refreshLock = Any()

    @Volatile
    private var isRefreshing = false

    /**
     * Lo que dejó el ÚLTIMO refresco que corrió. Quien esperaba (Task 14) lo
     * REUSA en vez de disparar el suyo — el servidor rota el refresh token,
     * así que un segundo refresco consumiría un grant ya usado y se leería
     * como reutilización (revoca la sesión entera, cajero fuera a media
     * venta). Marcado `@Volatile` para que la escritura del líder sea visible
     * al seguidor apenas sale de `wait()`, sin depender de en qué instante
     * exacto se toma `refreshLock`.
     */
    @Volatile
    private var lastRefreshOutcome: RefreshOutcome? = null

    /**
     * Cliente y base URL PROPIOS del refresco, deliberadamente separados del
     * OkHttpClient que arma NetworkModule — ese cliente necesita a este
     * Authenticator como su propio `.authenticator(...)`, así que usarlo aquí
     * sería un ciclo de dependencias (mismo patrón que
     * ManagerOverrideCoordinator; ver el comentario en NetworkModule.kt).
     * Producción usa los valores por defecto; las pruebas los sustituyen por
     * un MockWebServer — `internal var`, no parámetro del constructor, para
     * no obligar a Hilt/Dagger a resolver un `OkHttpClient`/`String` sueltos
     * que no tienen binding en el grafo.
     */
    internal var refreshHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    internal var refreshBaseUrl: String = ApiConstants.BASE_URL

    /**
     * Resultado de UN intento de refresco. Nunca colapsa un fallo de RED y un
     * rechazo de NEGOCIO en el mismo `null`: esa mezcla era justo lo que
     * convertía cualquier apagón de wifi en un logout (Task 13,
     * offline-first-y-hub-lan.md §2.3: "un fallo de RED se convierte en
     * intent; un rechazo de NEGOCIO se propaga tal cual").
     */
    private sealed class RefreshOutcome {
        data class Success(val accessToken: String, val refreshToken: String) : RefreshOutcome()
        object NetworkFailure : RefreshOutcome()

        /**
         * El servidor SÍ contestó, pero con algo que no afirma que el
         * refresh token murió (500/502/503/429, o un 200 con cuerpo
         * inválido). Se comporta EXACTAMENTE como `NetworkFailure`: nunca
         * cierra sesión. Encontrado en revisión (Crítico): un deploy normal
         * del backend —que aquí tarda minutos— responde 502/503 mientras
         * está arriba; sin este caso, cualquier POS con el access vencido
         * en ese instante perdía la sesión completa por un tropiezo
         * temporal del servidor, exactamente lo que estas dos tareas
         * existen para evitar.
         */
        data class TransientServerFailure(val httpCode: Int) : RefreshOutcome()

        /** El servidor respondió 401/403: dijo explícitamente que el refresh
         *  token ya no sirve. El ÚNICO caso (junto con no tener refresh
         *  token guardado) que cierra sesión. */
        data class Rejected(val httpCode: Int) : RefreshOutcome()
    }

    override fun authenticate(route: Route?, response: Response): Request? {
        val requestPath = response.request.url.encodedPath

        // Time clock PIN endpoints can legitimately return 401 ("PIN inválido").
        // Do not refresh/retry token for those responses.
        if (requestPath.contains("/mobile/venues/") && requestPath.contains("/time-clock/")) {
            return null
        }

        if (response.request.header("X-Retry-After-Refresh") != null) {
            // Esta petición YA se reintentó una vez con el token que un
            // refresco EXITOSO dejó vigente (ver `buildRetry`: el header sólo
            // se agrega tras un `RefreshOutcome.Success`), y aun así volvió
            // 401. Un fallo de RED nunca produce un reintento, así que esta
            // rama sólo se alcanza con una respuesta HTTP real del servidor:
            // es rechazo de negocio de verdad.
            Log.e("🔐", "Token refresh failed - logging out")
            secureStorage.clearSession()
            return null
        }

        synchronized(refreshLock) {
            if (isRefreshing) {
                // Otro hilo ya está refrescando (Task 14): esperar su
                // resultado y REUSARLO en vez de refrescar también.
                while (isRefreshing) {
                    try {
                        (refreshLock as Object).wait(10_000)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                }
                // El líder ya notificó (o el wait venció, caso extremo): si
                // dejó éxito, reintentamos con SU token nuevo. Si dejó fallo
                // de red o rechazo, no reintentamos — nada nuevo que probar.
                return buildRetry(response, lastRefreshOutcome)
            }
            isRefreshing = true
        }

        try {
            val refreshToken = secureStorage.refreshToken
            val outcome = if (refreshToken == null) {
                // Sin refresh token no hay nada que intentar: no es un fallo
                // de red, es una sesión que ya no existe localmente.
                RefreshOutcome.Rejected(httpCode = 0)
            } else {
                refreshTokens(refreshToken)
            }
            lastRefreshOutcome = outcome
            return applyOutcome(response, outcome)
        } finally {
            synchronized(refreshLock) {
                isRefreshing = false
                (refreshLock as Object).notifyAll()
            }
        }
    }

    private fun applyOutcome(response: Response, outcome: RefreshOutcome): Request? {
        return when (outcome) {
            is RefreshOutcome.Success -> {
                secureStorage.updateTokens(
                    accessToken = outcome.accessToken,
                    refreshToken = outcome.refreshToken,
                )
                Log.d("🔐", "Token refreshed successfully")
                buildRetry(response, outcome)
            }
            RefreshOutcome.NetworkFailure -> {
                // offline-first-y-hub-lan.md §2.3: un fallo de RED se
                // convierte en "sigue vivo", nunca en logout. La sesión, el
                // outbox y la pantalla se quedan como están; esta petición
                // sola falla (el 401 original se propaga al llamador, que ya
                // sabe encolarla) y se reintenta cuando vuelva la red.
                Log.e("🔐", "Token refresh failed: sin red - la sesion sigue viva")
                null
            }
            is RefreshOutcome.TransientServerFailure -> {
                // El servidor contestó (no fue un fallo de red), pero con
                // algo que tampoco afirma que la sesión murió — se trata
                // IGUAL que NetworkFailure: nunca cierra sesión.
                Log.e("🔐", "Token refresh failed: error transitorio del servidor (${outcome.httpCode}) - la sesion sigue viva")
                null
            }
            is RefreshOutcome.Rejected -> {
                // El servidor respondió 401/403: dijo explícitamente que el
                // refresh token ya no sirve (vencido de verdad, o
                // reutilización detectada y la familia entera fue
                // revocada), o no había refresh token guardado. Eso SÍ es
                // logout real.
                Log.e("🔐", "Token refresh rejected by server (${outcome.httpCode}) - logging out")
                secureStorage.clearSession()
                null
            }
        }
    }

    private fun buildRetry(response: Response, outcome: RefreshOutcome?): Request? {
        val success = outcome as? RefreshOutcome.Success ?: return null
        return response.request.newBuilder()
            .header("Authorization", "Bearer ${success.accessToken}")
            .header("X-Retry-After-Refresh", "true")
            .build()
    }

    private fun refreshTokens(refreshToken: String): RefreshOutcome {
        return try {
            val body = json.encodeToString(
                TokenRefreshRequest.serializer(),
                TokenRefreshRequest(refreshToken),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$refreshBaseUrl/mobile/auth/refresh")
                .header("ngrok-skip-browser-warning", "true")
                .post(body)
                .build()

            refreshHttpClient.newCall(request).execute().use { httpResponse ->
                if (httpResponse.isSuccessful) {
                    val tokens = httpResponse.body?.string()?.let {
                        json.decodeFromString<TokenRefreshResponse>(it)
                    }
                    if (tokens != null) {
                        RefreshOutcome.Success(tokens.accessToken, tokens.refreshToken)
                    } else {
                        // 200 sin cuerpo utilizable: SÍ hubo respuesta, pero
                        // ninguna que afirme que el refresh token es
                        // inválido. Nunca cerramos sesión por algo que el
                        // servidor no dijo.
                        RefreshOutcome.TransientServerFailure(httpResponse.code)
                    }
                } else if (httpResponse.code == 401 || httpResponse.code == 403) {
                    // SÓLO 401/403 es el servidor afirmando que el refresh
                    // token murió. Cualquier otro código (500/502/503/429…)
                    // es el servidor tropezando, no negando la sesión —
                    // clasificarlo aquí desloguearía a cualquier POS con el
                    // access vencido durante un deploy normal del backend.
                    RefreshOutcome.Rejected(httpResponse.code)
                } else {
                    RefreshOutcome.TransientServerFailure(httpResponse.code)
                }
            }
        } catch (e: IOException) {
            // Sin conexión, DNS, timeout, host inalcanzable: la definición
            // exacta de "fallo de RED" de offline-first-y-hub-lan.md §2.3.
            Log.e("🔐", "Token refresh network error: ${e.message}")
            RefreshOutcome.NetworkFailure
        } catch (e: Exception) {
            // JSON corrupto u otro tropiezo que tampoco es el servidor
            // afirmando que la sesión murió.
            Log.e("🔐", "Token refresh unexpected error: ${e.message}")
            RefreshOutcome.NetworkFailure
        }
    }
}

@Serializable
private data class TokenRefreshRequest(val refreshToken: String)

@Serializable
private data class TokenRefreshResponse(
    val accessToken: String,
    val refreshToken: String,
)
