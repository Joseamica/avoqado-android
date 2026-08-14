package com.avoqado.pos.core.data.network

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
private data class ForbiddenResponse(
    val error: String? = null,
    val message: String? = null,
    val required: String? = null,
    val userRole: String? = null,
    /**
     * Sólo viene cuando el 403 es del CANDADO DE PLAN, no de permisos. Se
     * distingue porque presentarlo como "no tienes permisos" manda al mesero a
     * pedirle permisos a su jefe en vez de al upsell — el mismo bug que iOS ya
     * documenta haber evitado en `APIClient.swift`.
     */
    val featureCode: String? = null,
)

@Singleton
class ErrorNotifier @Inject constructor() {
    private val _forbiddenError = MutableStateFlow<String?>(null)
    val forbiddenError: StateFlow<String?> = _forbiddenError.asStateFlow()

    fun notify(message: String) {
        _forbiddenError.value = message
    }

    fun clear() {
        _forbiddenError.value = null
    }
}

class ForbiddenInterceptor(
    private val errorNotifier: ErrorNotifier,
) : Interceptor {

    companion object {
        /** Marca las peticiones que corren solas, sin que nadie las pida. */
        const val BACKGROUND_HEADER = "X-Avoqado-Background"

        /** La función interpreta y presenta su propio error con contexto operativo. */
        const val LOCAL_ERROR_HEADER = "X-Avoqado-Local-Error"
    }

    private val json = Json { ignoreUnknownKeys = true }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        // 🔴 Un rechazo de una tarea de FONDO no interrumpe a nadie.
        //
        // Este interceptor es global, así que el replay del outbox — que corre
        // solo, sin que nadie lo pida — sacaba el diálogo modal encima de la
        // pantalla en la que estuviera el mesero. Medido en la tablet: 23
        // rechazos seguidos del mismo intent = el diálogo saltando una y otra
        // vez sobre el calendario. Esos rechazos ya tienen su sitio: la
        // cuarentena, donde alguien puede resolverlos con calma.
        if (request.header(BACKGROUND_HEADER) != null) {
            if (response.code == 403) {
                Log.w("🔒 RBAC", "403 en tarea de fondo (${request.url.encodedPath}) — va a cuarentena, sin diálogo")
            }
            return response
        }

        // Algunos módulos tienen errores de dominio mucho más útiles que el modal
        // RBAC global (por ejemplo: "esta terminal es Cremería, escanea en Caja").
        // Si el repositorio ya los presentará, no dupliques ni tapes ese mensaje.
        if (request.header(LOCAL_ERROR_HEADER) != null) return response

        if (response.code == 403) {
            val body = response.peekBody(4096).string()

            // 🔴 Un 403 NO prueba que falten permisos: prueba que ALGUIEN dijo que
            // no, y ese alguien puede no ser nuestra API.
            //
            // Medido en hardware el 2026-08-13: con el túnel de ngrok caído, la
            // app decía "No tienes permisos" y mandaba a buscar un problema de
            // roles inexistente — mientras el banner de sin conexión estaba
            // puesto, o sea contradiciéndose en la misma pantalla. En producción
            // la API vive detrás de Cloudflare, que responde 403 con HTML (WAF,
            // rate limit, reglas de país), y las terminales de PlayTelecom operan
            // dentro de redes corporativas de Walmart con proxy.
            //
            // Nuestro 403 SIEMPRE trae JSON con `error` o `message`
            // (`authorizeRole.middleware.ts`). El del intermediario, no. Si el
            // cuerpo no es nuestro, esto es un fallo de transporte y le toca al
            // ConnectivityBanner, no a un modal de permisos.
            val parsed = try {
                json.decodeFromString<ForbiddenResponse>(body)
            } catch (_: Exception) {
                null
            }
            if (parsed == null || (parsed.error == null && parsed.message == null)) {
                Log.w(
                    "🔒 RBAC",
                    "403 sin cuerpo nuestro en ${request.url.encodedPath} " +
                        "(${response.header("Content-Type")}) — intermediario, no permisos",
                )
                return response
            }

            // El candado de PLAN tampoco es falta de permiso: lo resuelve el
            // upsell, no un administrador dando permisos.
            if (parsed.featureCode != null) {
                Log.w("🔒 RBAC", "403 de candado de plan (${parsed.featureCode}) — sin modal de permisos")
                return response
            }

            // El server manda el detalle técnico ("Permission 'x' required"), útil
            // para logs; a la persona se le enseña algo que pueda accionar.
            val message = ServerErrorText.humanize(parsed.message, "No tienes permisos para esta acción")
            Log.w("🔒 RBAC", "403 Forbidden: $message")
            errorNotifier.notify(message)
        }

        return response
    }
}
