package com.avoqado.pos.reservations.data

import com.avoqado.pos.core.data.local.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * "Mi clase ahora" — Fase 8 del kiosco.
 *
 * Quien da la clase necesita UNA cosa: quién viene y quién ya llegó. Para verlo hoy
 * tendría que entrar a la agenda del negocio, que enseña todo lo demás — el resto del
 * día, otros instructores, cuánto pagó cada quien.
 *
 * El endpoint es estrecho a propósito: sólo devuelve sesiones donde ESTA persona es la
 * asignada, sólo la que está ocurriendo, y con nombre e inicial. El permiso
 * `class-sessions:read-assigned` no implica `reservations:read`.
 */
@Singleton
class CoachClassApi @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    @Named("apiBaseUrl") private val baseUrlProvider: () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class Attendee(
        val reservationId: String,
        val displayName: String,
        val checkedIn: Boolean,
        val spotLabel: String?,
    )

    data class MyClass(
        val sessionId: String,
        val productName: String,
        val startsAt: String,
        val endsAt: String,
        val capacity: Int,
        val booked: Int,
        val checkedIn: Int,
        val attendees: List<Attendee>,
    )

    /**
     * `null` NO es un error: es el estado normal el 90 % del día. La pantalla dice
     * "no tienes clase ahora", que es información, no una falla.
     */
    suspend fun myClassNow(): Result<MyClass?> = withContext(Dispatchers.IO) {
        runCatching {
            val venueId = secureStorage.venueId ?: error("No venue")
            val req = Request.Builder()
                .url("${baseUrlProvider()}/mobile/venues/$venueId/my-class-now")
                .get()
                .build()

            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) error("HTTP ${res.code}")
                val root = json.parseToJsonElement(body).jsonObject
                if (root["hasClass"]?.jsonPrimitive?.booleanOrNull != true) return@use null
                val c = root["class"]?.jsonObject ?: return@use null
                MyClass(
                    sessionId = c["sessionId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    productName = c["productName"]?.jsonPrimitive?.contentOrNull ?: "Clase",
                    startsAt = c["startsAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    endsAt = c["endsAt"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    capacity = c["capacity"]?.jsonPrimitive?.intOrNull ?: 0,
                    booked = c["booked"]?.jsonPrimitive?.intOrNull ?: 0,
                    checkedIn = c["checkedIn"]?.jsonPrimitive?.intOrNull ?: 0,
                    attendees = c["attendees"]?.jsonArray.orEmpty().map { it.jsonObject }.map { a ->
                        Attendee(
                            reservationId = a["reservationId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            displayName = a["displayName"]?.jsonPrimitive?.contentOrNull ?: "Invitado",
                            checkedIn = a["checkedIn"]?.jsonPrimitive?.booleanOrNull ?: false,
                            spotLabel = a["spotLabel"]?.jsonPrimitive?.contentOrNull,
                        )
                    },
                )
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
    this ?: emptyList()
