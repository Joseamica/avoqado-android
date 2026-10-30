package com.avoqado.pos.kiosk.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.kiosk.domain.KioskPerson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
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

    /**
     * La lista y el check-in del kiosco: el MISMO cliente de la app (sesión, `ForbiddenInterceptor`, conexiones) con un
     * tope total de 10 s por llamada. Con WiFi sin datos la persona no espera 30 s por su ✓; el tope sale como
     * `InterruptedIOException` (sin red): la fila se queda sin gastar intento.
     */
    internal val kioskClient: OkHttpClient by lazy { client.newBuilder().callTimeout(10, TimeUnit.SECONDS).build() }

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

    /** 422 `CHECK_IN_OUTSIDE_WINDOW`: la hora del toque cae fuera de la ventana de la reserva. */
    class OutsideWindow(message: String? = null) : Exception(message)

    /** Cualquier otra respuesta HTTP que no es éxito, con el texto del SERVIDOR (lo que la cuarentena enseña). */
    class Rejected(val status: Int, message: String, val code: String? = null) : Exception(message)

    /**
     * La clase del kiosco ahora mismo, o null si no hay ninguna en ventana. La ventana la calcula el SERVIDOR (0.e):
     * aquí no se manda ningún rango ni se usa la hora del aparato.
     */
    suspend fun roster(): Result<KioskRosterDto?> = withContext(Dispatchers.IO) {
        runCatching {
            val venueId = secureStorage.venueId ?: error("No venue")
            val req = Request.Builder()
                .url("${baseUrlProvider()}/mobile/venues/$venueId/kiosk/roster")
                // Corre sola cada 15 s en la pantalla del cliente: un 403 no puede abrirle un diálogo al cajero.
                .header(ForbiddenInterceptor.BACKGROUND_HEADER, "1")
                .get()
                .build()
            kioskClient.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw rejected(res.code, body)
                val roster = json.parseToJsonElement(body).jsonObject["roster"] as? JsonObject ?: return@use null
                KioskRosterDto(
                    classTitle = roster.text("classTitle") ?: "Tu clase",
                    startsAt = roster.text("startsAt").orEmpty(),
                    staffFirstName = roster.text("staffFirstName"),
                    people = roster["people"]?.jsonArray.orEmpty().map { it.jsonObject }.map { p ->
                        KioskPerson(
                            reservationId = p.text("reservationId") ?: error("Persona sin reserva"),
                            displayName = p.text("displayName") ?: "Invitada",
                            checkedIn = p["checkedIn"]?.jsonPrimitive?.booleanOrNull == true,
                            spotLabel = p.text("spotLabel"),
                        )
                    },
                    // Sin cierre no hay forma de guardar una llegada sin red: es falla, no una lista que nunca caduca.
                    closesAt = roster.text("closesAt") ?: error("Lista sin cierre"),
                    closesInMs = roster["closesInMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: error("Lista sin cierre"),
                )
            }
        }
    }

    /**
     * Registra la llegada de [reservationId] en la sucursal [venueId] de la FILA (no la de la sesión: la llegada pudo
     * guardarse en otra). Manda el cierre que vio la persona, tal cual vino del servidor, y lo que faltaba para él; el
     * servidor reconstruye la hora del toque con eso. Sólo la llama el reintentador: un solo mensajero.
     *
     * Red caída ⇒ la `IOException` tal cual, sin envolver (la fila se queda y se manda sola).
     *
     * D4: si el SERVIDOR corrigió un «No asistió» con esta llegada (o ya lo había hecho y sigue sin resolver), devuelve
     * su `correction`; si no, null. Un cuerpo que no se entiende NUNCA convierte el éxito en falla: da null.
     */
    suspend fun checkInReservation(
        venueId: String,
        reservationId: String,
        closesAt: String,
        msBeforeClose: Long,
    ): Result<KioskTap.Correction?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = buildJsonObject {
                    put("closesAt", JsonPrimitive(closesAt))
                    put("msBeforeClose", JsonPrimitive(msBeforeClose))
                }
                val req = Request.Builder()
                    .url("${baseUrlProvider()}/mobile/venues/$venueId/kiosk/reservations/$reservationId/check-in")
                    // De fondo: lo dispara una fila, no un dedo del cajero. Un 403 va a la cuarentena, sin diálogo.
                    .header(ForbiddenInterceptor.BACKGROUND_HEADER, "1")
                    .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(jsonMedia))
                    .build()
                kioskClient.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        return@use runCatching {
                            val c = json.parseToJsonElement(res.body?.string().orEmpty()).jsonObject["correction"] as? JsonObject
                            c?.let { json.decodeFromJsonElement(KioskTap.Correction.serializer(), it) }
                        }.getOrNull()
                    }
                    val error = rejected(res.code, res.body?.string().orEmpty())
                    if (res.code == 422 && error.code == "CHECK_IN_OUTSIDE_WINDOW") throw OutsideWindow(error.message)
                    throw error
                }
            }
        }

    /** El error del servidor viene como `{ message, code }` en la raíz (`app.ts`, manejador de `AppError`). */
    private fun rejected(status: Int, body: String): Rejected {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        return Rejected(status, root?.text("message") ?: "HTTP $status", root?.text("code"))
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

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
     * el kiosco nunca ve la tarjeta.
     *
     * 🔴 Lleva el teléfono (E.164) para que las clases caigan a SU nombre: sin contacto, Stripe
     * cobraba y el servidor no podía entregar (D3 del pizarrón). Sin `url` o sin `sessionId` es
     * falla: nunca se pinta un QR vacío.
     */
    suspend fun packCheckout(packId: String, phone: String): Result<PackCheckout> = withContext(Dispatchers.IO) {
        runCatching {
            val venueId = secureStorage.venueId ?: error("No venue")
            val payload = buildJsonObject {
                put("packId", JsonPrimitive(packId))
                put("phone", JsonPrimitive(phone))
            }
            val req = Request.Builder()
                .url("${baseUrlProvider()}/mobile/venues/$venueId/kiosk/pack-checkout")
                .post(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload).toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) error("HTTP ${res.code}")
                val root = json.parseToJsonElement(body).jsonObject
                PackCheckout(
                    url = root["url"]?.jsonPrimitive?.contentOrNull ?: error("Sin enlace de pago"),
                    sessionId = root["sessionId"]?.jsonPrimitive?.contentOrNull ?: error("Sin enlace de pago"),
                    expiresAt = root["expiresAt"]?.jsonPrimitive?.contentOrNull,
                )
            }
        }
    }

    /** ¿Ya se pagó? `PAID` sólo cuando las clases ya son suyas (el servidor ya entregó). */
    suspend fun packCheckoutStatus(sessionId: String): Result<PackStatus> = withContext(Dispatchers.IO) {
        runCatching {
            val venueId = secureStorage.venueId ?: error("No venue")
            val req = Request.Builder()
                .url("${baseUrlProvider()}/mobile/venues/$venueId/kiosk/pack-checkout/$sessionId/status")
                .get()
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) error("HTTP ${res.code}")
                val status = json.parseToJsonElement(body).jsonObject["status"]?.jsonPrimitive?.contentOrNull
                PackStatus.entries.firstOrNull { it.name == status } ?: error("Estado desconocido: $status")
            }
        }
    }
}

/**
 * La clase del kiosco, tal como la manda el servidor. [closesAt] es el cierre de su ventana en hora del SERVIDOR (texto
 * ISO con `Z`, se devuelve tal cual) y [closesInMs] lo que le quedaba al contestar: con eso el aparato guarda una
 * llegada sin depender de su propio reloj.
 */
data class KioskRosterDto(
    val classTitle: String,
    val startsAt: String,
    val staffFirstName: String?,
    val people: List<KioskPerson>,
    val closesAt: String,
    val closesInMs: Long,
)

/** El pago de un paquete, listo para enseñarse como QR. */
data class PackCheckout(val url: String, val sessionId: String, val expiresAt: String?)

/** Lo que dice el servidor del QR: sigue abierto, ya se entregó, o venció. */
enum class PackStatus { OPEN, PAID, EXPIRED }
