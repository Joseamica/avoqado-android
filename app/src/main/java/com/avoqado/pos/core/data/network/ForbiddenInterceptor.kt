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

    private val json = Json { ignoreUnknownKeys = true }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())

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
