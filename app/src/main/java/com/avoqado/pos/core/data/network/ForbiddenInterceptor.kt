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

        if (response.code == 403) {
            val body = response.peekBody(4096).string()
            // El server manda el detalle técnico ("Permission 'x' required"), útil
            // para logs; a la persona se le enseña algo que pueda accionar.
            val message = try {
                val parsed = json.decodeFromString<ForbiddenResponse>(body)
                ServerErrorText.humanize(parsed.message, "No tienes permisos para esta acción")
            } catch (_: Exception) {
                "No tienes permisos para esta acción"
            }
            Log.w("🔒 RBAC", "403 Forbidden: $message")
            errorNotifier.notify(message)
        }

        return response
    }
}
