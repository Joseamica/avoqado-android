package com.avoqado.pos.kiosk.data

import com.avoqado.pos.core.data.local.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * El respaldo del kiosco: "no aparezco en la lista".
 *
 * La lista de la clase resuelve el caso normal. Esto resuelve a quien no está en ella —
 * reservó a otro nombre, se apuntó por otro canal, o llegó cuando la lista ya se cerró.
 *
 * 🔴 **El servidor no dice NADA hasta que el check-in funciona.** Un teléfono sin reserva
 * ahora mismo devuelve exactamente lo mismo que uno que no existe: un 404 genérico. Es lo
 * que impide que esta pantalla —que está en la entrada, sin sesión de nadie— se use para
 * averiguar quién viene hoy. El límite de intentos también vive allá, y es durable.
 */
@Singleton
class KioskCheckInApi @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    @Named("apiBaseUrl") private val baseUrlProvider: () -> String,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json".toMediaType()

    /** Lo mínimo para pintar la confirmación. Sólo llega DESPUÉS de un check-in bueno. */
    data class Confirmed(
        val reservationId: String?,
        val alreadyCheckedIn: Boolean,
        val displayName: String,
        val title: String,
        val staffLabel: String?,
    )

    /** "No hay nada que casar" ≠ "se cayó la red": la pantalla dice cosas distintas. */
    class NotFound : Exception("Sin coincidencia")

    /** Demasiados intentos. El tope es del servidor y es durable. */
    class TooManyAttempts : Exception("Demasiados intentos")

    suspend fun checkIn(identifier: String): Result<Confirmed> = withContext(Dispatchers.IO) {
        runCatching {
            val venueId = secureStorage.venueId ?: error("No venue")
            val payload = buildJsonObject {
                put("identifier", JsonPrimitive(identifier))
                put("stationKey", JsonPrimitive("B"))
            }
            val req = Request.Builder()
                .url("${baseUrlProvider()}/mobile/venues/$venueId/kiosk/check-in")
                .post(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload).toRequestBody(jsonMedia))
                .build()

            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (res.code == 404) throw NotFound()
                if (res.code == 429) throw TooManyAttempts()
                if (!res.isSuccessful) error("HTTP ${res.code}")

                val root = json.parseToJsonElement(body).jsonObject
                val display = root["display"]?.jsonObject
                Confirmed(
                    reservationId = root["reservationId"]?.jsonPrimitive?.contentOrNull,
                    alreadyCheckedIn = root["outcome"]?.jsonPrimitive?.contentOrNull == "ALREADY_CHECKED_IN",
                    displayName = display?.get("displayName")?.jsonPrimitive?.contentOrNull ?: "Tú",
                    title = display?.get("title")?.jsonPrimitive?.contentOrNull ?: "Tu clase",
                    staffLabel = display?.get("staffLabel")?.jsonPrimitive?.contentOrNull,
                )
            }
        }
    }

    /** Un paquete comprable, con su precio de CATÁLOGO. El kiosco nunca lo cambia. */
    data class Pack(val id: String, val name: String, val priceCents: Int, val detail: String?)

    /** Lo que el kiosco puede ofrecer. El precio sale del catálogo, no de la pantalla. */
    suspend fun packs(): Result<List<Pack>> = withContext(Dispatchers.IO) {
        runCatching {
            val venueId = secureStorage.venueId ?: error("No venue")
            val req = Request.Builder()
                .url("${baseUrlProvider()}/mobile/venues/$venueId/kiosk/packs")
                .get()
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) error("HTTP ${res.code}")
                json.parseToJsonElement(body).jsonObject["packs"]?.jsonArray.orEmpty()
                    .map { it.jsonObject }
                    .map { p ->
                        // El precio llega en PESOS del catálogo; aquí se pasa a centavos
                        // una sola vez, para que la pantalla no haga aritmética de dinero.
                        val pesos = p["price"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0
                        Pack(
                            id = p["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            name = p["name"]?.jsonPrimitive?.contentOrNull ?: "Paquete",
                            priceCents = Math.round(pesos * 100).toInt(),
                            detail = p["description"]?.jsonPrimitive?.contentOrNull,
                        )
                    }
            }
        }
    }

    /**
     * Enlace de pago para enseñarlo como QR. El cobro ocurre en el TELÉFONO del cliente:
     * el kiosco nunca ve la tarjeta ni sabe quién pagó.
     */
    suspend fun packCheckoutUrl(packId: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val venueId = secureStorage.venueId ?: error("No venue")
            val payload = buildJsonObject { put("packId", JsonPrimitive(packId)) }
            val req = Request.Builder()
                .url("${baseUrlProvider()}/mobile/venues/$venueId/kiosk/pack-checkout")
                .post(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload).toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) error("HTTP ${res.code}")
                json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.contentOrNull
                    ?: error("Sin enlace de pago")
            }
        }
    }
}
