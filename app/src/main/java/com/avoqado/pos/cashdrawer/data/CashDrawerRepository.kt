package com.avoqado.pos.cashdrawer.data

import android.os.Build
import android.util.Log
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerEventType
import com.avoqado.pos.cashdrawer.data.model.CashDrawerSessionEntity
import com.avoqado.pos.cashdrawer.data.model.CashDrawerStatus
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.contentOrNull

private const val TAG = "💰 CashDrawerRepo"

// MARK: - API Request/Response Models

@Serializable
private data class OpenDrawerRequest(val startingAmount: Double)

/**
 * 🔴 `localId` es la LLAVE DE IDEMPOTENCIA del movimiento, y es el mismo id con el
 * que la fila vive en Room. Sin ella, la fila que crea el server nace anónima y
 * **ninguna** versión futura del cliente puede reconocerla como suya: el eco del sync
 * entra como fila nueva y el mismo ingreso/retiro se cuenta dos veces, para siempre.
 * Ése era el agujero — la fusión por llave estaba escrita en el cliente pero la
 * tubería llegaba seca, porque `payIn`/`payOut` del server creaban el evento sin
 * `localId` (nadie se lo mandaba).
 *
 * El valor NUNCA se regenera: si cambiara entre dos reintentos del mismo movimiento
 * dejaría de ser una llave y el server insertaría dos filas en vez de deduplicar
 * contra `@@unique([venueId, localId])`. Es el mismo contrato que ya usa el push de
 * ventas (`SyncEventDto.localId`) y el outbox de intents.
 *
 * Aditivo a propósito: un server viejo que no conozca el campo simplemente lo ignora
 * y todo se comporta como hoy. Mandar la llave nunca puede impedir registrar dinero.
 */
@Serializable
private data class PayInRequest(val amount: Double, val note: String? = null, val localId: String)

@Serializable
private data class PayOutRequest(val amount: Double, val note: String? = null, val localId: String)

@Serializable
private data class CloseDrawerRequest(val actualAmount: Double, val note: String? = null)

@Singleton
class CashDrawerRepository @Inject constructor(
    private val dao: CashDrawerDao,
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    private val pendingCashSales: PendingCashSales,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val venueId: String
        get() = secureStorage.venueId ?: ""

    /** Nombre del local para el encabezado del corte impreso. */
    val venueName: String
        get() = secureStorage.venueName?.takeIf { it.isNotBlank() } ?: "Avoqado"

    private val staffId: String
        get() = secureStorage.userId ?: ""

    private val staffName: String
        get() {
            val first = secureStorage.userFirstName ?: ""
            val last = secureStorage.userLastName ?: ""
            return "$first $last".trim().ifEmpty { secureStorage.userEmail ?: "Staff" }
        }

    private val deviceName: String
        get() = "${Build.MANUFACTURER} ${Build.MODEL}"

    private val baseUrl: String
        get() = "${ApiConstants.BASE_URL}/mobile/venues/$venueId/cash-drawer"

    // MARK: - Session Queries

    suspend fun getOpenSession(): CashDrawerSessionEntity? {
        return dao.getOpenSession(venueId)
    }

    suspend fun getHistory(): List<CashDrawerSessionEntity> {
        return dao.getClosedSessions(venueId)
    }

    // MARK: - Tender breakdown (corte de caja — all payment methods, not just cash)

    /** One tender row for the corte's "Desglose por método de pago". */
    /**
     * @param totalCents lo cobrado por ese método, propina INCLUIDA — es lo que
     *   entró y, en efectivo, lo que está físicamente en el cajón.
     * @param tipsCents cuánto de ese total fue propina. Viaja aparte porque la
     *   propina NO es dinero del negocio: se le entrega al mesero. Un corte que la
     *   suma sin distinguirla hace que el cajón "cuadre" con dinero que se va a
     *   repartir.
     */
    data class TenderRow(val method: String, val totalCents: Int, val tipsCents: Int = 0)

    /**
     * Payments grouped by method for the session window [fromMillis, toMillis].
     * The drawer only tracks CASH physically, so card/other totals come from the
     * server's payment records. Returns empty on any failure (corte still renders).
     */
    suspend fun getTenderBreakdown(fromMillis: Long, toMillis: Long): List<TenderRow> {
        if (venueId.isEmpty()) return emptyList()
        return try {
            val from = java.time.Instant.ofEpochMilli(fromMillis).toString()
            val to = java.time.Instant.ofEpochMilli(toMillis).toString()
            val url = "$baseUrl/tender-breakdown?from=${java.net.URLEncoder.encode(from, "UTF-8")}&to=${java.net.URLEncoder.encode(to, "UTF-8")}"
            val request = Request.Builder().url(url).get().build()
            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }
            if (code !in 200..299 || body.isEmpty()) {
                Log.e(TAG, "❌ tender-breakdown failed: $code")
                return emptyList()
            }
            val root = json.decodeFromString<JsonObject>(body)
            val arr = root["data"]?.jsonObject?.get("tenderBreakdown")?.jsonArray ?: return emptyList()
            arr.mapNotNull { el ->
                val obj = el.jsonObject
                val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val dollars = obj["total"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val tips = obj["tips"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                TenderRow(
                    method = method,
                    totalCents = (dollars * 100).toInt(),
                    tipsCents = (tips * 100).toInt(),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ tender-breakdown error: ${e.message}")
            emptyList()
        }
    }

    // MARK: - End of day ("Cierre del día")

    /**
     * Fetches the end-of-day summary: the day's sales by tender + the blockers
     * (open checks, open drawers, clocked-in staff). Read-only; null on failure.
     */
    suspend fun getEndOfDay(): EndOfDaySummary? {
        if (venueId.isEmpty()) return null
        return try {
            val url = "${ApiConstants.BASE_URL}/mobile/venues/$venueId/end-of-day"
            val request = Request.Builder().url(url).get().build()
            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }
            if (code !in 200..299 || body.isEmpty()) {
                Log.e(TAG, "❌ end-of-day failed: $code")
                return null
            }
            json.decodeFromString(EndOfDayResponse.serializer(), body).data
        } catch (e: Exception) {
            Log.e(TAG, "❌ end-of-day error: ${e.message}")
            null
        }
    }

    // MARK: - Sync from API

    /**
     * Fetch open session + events from API and update Room.
     * Called on launch / pull-to-refresh.
     */
    suspend fun syncFromApi() {
        if (venueId.isEmpty()) return
        try {
            syncCurrentSession()
            syncHistory()
        } catch (e: Exception) {
            Log.e(TAG, "❌ Sync from API error: ${e.message}")
        }
    }

    private suspend fun syncCurrentSession() {
        val request = Request.Builder()
            .url("$baseUrl/current")
            .get()
            .build()

        val (code, body) = withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            response.code to (response.body?.string() ?: "")
        }

        if (code in 200..299 && body.isNotEmpty()) {
            try {
                val root = json.decodeFromString<JsonObject>(body)
                val sessionObj = parseSessionEnvelope(root)
                if (sessionObj != null) {
                    // Events live inside the session payload (fallback: top-level).
                    val session = adoptServerSession(sessionObj, root["events"]?.jsonArray)
                    Log.d(TAG, "✅ Caja del server sincronizada: ${session.id}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Parse current session error: ${e.message}")
            }
        }
    }

    /**
     * Server envelope is {success, data: session} with the events ARRAY EMBEDDED in
     * the session; accept legacy top-level keys too. `null` = no hay caja abierta.
     */
    private fun parseSessionEnvelope(root: JsonObject): JsonObject? =
        (root["data"] as? JsonObject)
            ?: root["data"]?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonObject }
            ?: root["session"]?.jsonObject

    /**
     * 🔴 LA CAJA ADOPTA EL ID DEL SERVER — el corazón de la reconciliación.
     *
     * Sin red, la sesión NACE LOCAL a propósito: el POS no puede pedirle un id a
     * nadie antes de dejar abrir la caja. Pero cuando el server confirma, la fila
     * local tiene que **promoverse** a su id en vez de quedarse como una segunda
     * caja abierta. Es el mismo patrón que el outbox ya usa con las órdenes
     * (`TableSession.promoteProvisional`, `localOrderId → orderId`), sólo que aquí
     * la promoción vive en Room.
     *
     * Lo que costaba no hacerlo, medido con sqlite3 (2026-08-16): Room terminaba con
     * DOS sesiones OPEN del mismo local y `getOpenSession` devolvía siempre la
     * provisional, así que los movimientos que manda el server —el PAY_OUT del
     * reembolso, entre ellos— caían en la sesión que la pantalla no lee. El server ya
     * restaba bien y en el corte del cajero seguían sobrando los $150.
     *
     * Tres pasos, en este orden:
     *  1. Entra la fila del server. Va primero para que ningún evento quede
     *     apuntando a una sesión que no existe, ni siquiera por un instante.
     *  2. Las provisionales adoptan su id: los eventos se MUDAN con ellas
     *     (`repointEventsFrom`) y la fila vieja desaparece. Un retiro registrado sin
     *     red sigue contando; si no se mudara, el cajón "aparecería" con ese dinero
     *     de más. 🔴 La mudanza está ACOTADA a la ventana de la caja del server (ver
     *     abajo), y sólo se borra la fila provisional si ya no le queda nada.
     *  3. Entran los eventos confirmados. Antes de insertar cada uno se intenta la
     *     FUSIÓN POR LLAVE ([promoteEvent] con el `localId` que ahora manda el
     *     server); después se borran las copias locales de los tipos que el server
     *     escribe por su cuenta (ver [SERVER_OWNED_EVENT_TYPES]).
     *
     * 🔴 **Por qué la mudanza se acota por tiempo.** Antes se llevaba TODOS los
     * eventos de cualquier otra sesión abierta — incluida la caja de un turno
     * anterior que este aparato nunca vio cerrar. El `CASH_SALE` de ayer sí lo
     * borraba la limpieza por tipo, pero el retiro a mano de ayer se colaba al turno
     * de hoy: medido, $5,050.00 donde debía decir $5,130.00, un sobrante inventado
     * del tamaño exacto del retiro de ayer.
     *
     * La cota es `server.openedAt`, y no es un número arbitrario: es la MISMA ventana
     * con la que el server calcula su esperado (`calculateExpectedAmount` suma los
     * eventos colgados de la sesión, y un evento anterior a su apertura no puede
     * estar colgado de ella). Cliente y server quedan atados por construcción.
     *
     * Lo que queda fuera de la ventana NO se borra ni se toca: es dinero que salió de
     * verdad, sigue colgado de su propia caja. No contaminar nunca puede significar
     * destruir.
     */
    private suspend fun adoptServerSession(
        sessionObj: JsonObject,
        fallbackEvents: kotlinx.serialization.json.JsonArray? = null,
    ): CashDrawerSessionEntity {
        val server = parseSessionFromApi(sessionObj)
        dao.insertSession(server)

        if (server.status == CashDrawerStatus.OPEN.name && venueId.isNotEmpty()) {
            dao.getOpenSessions(venueId)
                .filter { it.id != server.id }
                .forEach { provisional ->
                    Log.d(TAG, "⬆️ Promoviendo caja provisional ${provisional.id} → ${server.id}")
                    dao.repointEventsFrom(provisional.id, server.id, server.openedAt)
                    if (dao.getSessionEvents(provisional.id).isEmpty()) {
                        dao.deleteSession(provisional.id)
                    } else {
                        Log.d(TAG, "🗄️ Caja ${provisional.id} conservada: tiene movimientos anteriores a esta caja")
                    }
                }
        }

        val eventsArray = sessionObj["events"]?.jsonArray ?: fallbackEvents
        val confirmedIds = eventsArray.orEmpty().map { eventJson ->
            val obj = eventJson.jsonObject
            val event = parseEventFromApi(obj, server.id)
            // 🔑 Si el server dice que este evento es el MÍO, mi fila adopta su id
            // ANTES de insertarlo: así queda UNA sola fila y gana el contenido del
            // server. La llave es un hecho, no una inferencia — por eso vence a la
            // ventana de arriba: un movimiento mal archivado en la caja vieja que el
            // server cuenta en la de hoy tiene que venirse, o lo perderíamos.
            promoteEvent(obj["localId"]?.jsonPrimitive?.contentOrNull, event.id)
            dao.insertEvent(event)
            event.id
        }

        // Un payload SIN eventos no autoriza a borrar nada: sería el mismo error que
        // pisar la config de impresoras con un refresh fallido. Sólo se limpia cuando
        // el server efectivamente dijo qué eventos tiene.
        //
        // 🔴 Y aun entonces, una venta cuyo cobro SIGUE EN LA COLA no puede venir
        // confirmada: el server todavía no la conoce. Borrarla ahí le desaparecía al
        // cajero dinero que sí está en el cajón, justo al abrir esta pantalla para
        // cerrar su turno. Ver [PendingCashSales].
        if (confirmedIds.isNotEmpty()) {
            dao.deleteUnconfirmedEvents(
                server.id,
                SERVER_OWNED_EVENT_TYPES,
                confirmedIds,
                pendingCashSales.unreplayedOrderIds(venueId).toList(),
            )
        }
        return server
    }

    /**
     * ⬆️ Un movimiento que el cliente escribió y el server confirmó adopta el id
     * real, igual que la sesión.
     *
     * Sin esto, el eco del sync entra como una fila NUEVA (el server le pone su
     * propio cuid) y el mismo retiro de $50 se resta dos veces. La copia local es
     * necesaria —el cajero tiene que ver el movimiento al instante, con o sin red—
     * así que la salida no es dejar de escribirla, es que comparta identidad.
     *
     * Se llama desde DOS lados, y el segundo es el que cierra el agujero:
     *  - **al ESCRIBIR** el movimiento, con el id que devuelve el POST. Sólo alcanza
     *    a lo que esta app escribe estando corriendo.
     *  - **al SINCRONIZAR**, con el `localId` que el server ahora devuelve en cada
     *    evento. Es lo único que alcanza a una fila que ya estaba en Room desde antes
     *    de actualizar la app: la limpieza por tipo excluye `PAY_IN`/`PAY_OUT` a
     *    propósito y la promoción al escribir ya no puede correr para ellas, así que
     *    sin la llave el movimiento heredado se contaba dos veces PARA SIEMPRE.
     *
     * 🔴 Degradación: `localId` nulo o en blanco (server viejo, o fila sin llave) →
     * no hace nada y el comportamiento es idéntico al de antes. La llave mejora la
     * fusión; no es requisito para funcionar.
     */
    private suspend fun promoteEvent(localId: String?, serverId: String?): CashDrawerEventEntity? {
        if (localId.isNullOrBlank() || serverId.isNullOrBlank() || serverId == localId) return null
        val local = dao.getEvent(localId) ?: return null
        val promoted = local.copy(id = serverId)
        dao.insertEvent(promoted)
        dao.deleteEvent(localId)
        Log.d(TAG, "⬆️ Movimiento confirmado $localId → $serverId")
        return promoted
    }

    /** El id que el server le puso al evento que acabamos de registrar, si lo mandó. */
    private fun parseEventId(body: String): String? = try {
        val root = json.decodeFromString<JsonObject>(body)
        (root["data"] as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            ?: root["event"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
        null
    }

    private suspend fun syncHistory() {
        val request = Request.Builder()
            .url("$baseUrl/history")
            .get()
            .build()

        val (code, body) = withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            response.code to (response.body?.string() ?: "")
        }

        if (code in 200..299 && body.isNotEmpty()) {
            try {
                val root = json.decodeFromString<JsonObject>(body)
                val sessionsArray = root["sessions"]?.jsonArray
                sessionsArray?.forEach { sessionJson ->
                    val session = parseSessionFromApi(sessionJson.jsonObject)
                    dao.insertSession(session)
                }
                Log.d(TAG, "✅ Synced ${sessionsArray?.size ?: 0} history sessions from API")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Parse history error: ${e.message}")
            }
        }
    }

    // MARK: - Open Session

    suspend fun openSession(startingAmountCents: Int): CashDrawerSessionEntity {
        val session = CashDrawerSessionEntity(
            id = UUID.randomUUID().toString(),
            venueId = venueId,
            deviceName = deviceName,
            openedByStaffId = staffId,
            openedByName = staffName,
            openedAt = System.currentTimeMillis(),
            startingAmountCents = startingAmountCents,
            status = CashDrawerStatus.OPEN.name,
        )
        dao.insertSession(session)

        // Record OPEN event
        val event = CashDrawerEventEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            venueId = venueId,
            type = CashDrawerEventType.OPEN.name,
            amountCents = startingAmountCents,
            note = null,
            staffId = staffId,
            staffName = staffName,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertEvent(event)

        Log.d(TAG, "✅ Session opened locally: ${session.id}, starting: $startingAmountCents")

        // Si el server contesta, la caja adopta SU id aquí mismo. Si no contesta
        // —sin red, o 409 porque otra tablet ya la abrió— se queda provisional y la
        // adopta el primer sync que lo logre. Abrir la caja nunca depende de eso.
        return fireApiOpen(startingAmountCents) ?: session
    }

    private suspend fun fireApiOpen(startingAmountCents: Int): CashDrawerSessionEntity? {
        try {
            val dollars = startingAmountCents / 100.0
            val requestBody = json.encodeToString(
                OpenDrawerRequest.serializer(),
                OpenDrawerRequest(startingAmount = dollars),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/open")
                .post(requestBody)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d(TAG, "✅ API open session success")
                val sessionObj = parseSessionEnvelope(json.decodeFromString<JsonObject>(body))
                return sessionObj?.let { adoptServerSession(it) }
            } else {
                Log.e(TAG, "❌ API open session failed: $code - $body")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ API open session error: ${e.message}")
        }
        return null
    }

    // MARK: - Events

    suspend fun addPayIn(amountCents: Int, note: String?): CashDrawerEventEntity? {
        val session = getOpenSession() ?: return null
        val event = CashDrawerEventEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            venueId = venueId,
            type = CashDrawerEventType.PAY_IN.name,
            amountCents = amountCents,
            note = note,
            staffId = staffId,
            staffName = staffName,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertEvent(event)
        Log.d(TAG, "✅ Pay-in recorded locally: $amountCents")

        // La fila local nace provisional y adopta el id del server si éste confirma.
        return fireApiPayIn(amountCents, note, event.id) ?: event
    }

    private suspend fun fireApiPayIn(amountCents: Int, note: String?, localEventId: String): CashDrawerEventEntity? {
        try {
            val dollars = amountCents / 100.0
            val requestBody = json.encodeToString(
                PayInRequest.serializer(),
                // La llave es el id con el que la fila YA quedó en Room, no uno nuevo.
                PayInRequest(amount = dollars, note = note, localId = localEventId),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/pay-in")
                .post(requestBody)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d(TAG, "✅ API pay-in success")
                return promoteEvent(localEventId, parseEventId(body))
            } else {
                Log.e(TAG, "❌ API pay-in failed: $code - $body")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ API pay-in error: ${e.message}")
        }
        return null
    }

    suspend fun addPayOut(amountCents: Int, note: String?): CashDrawerEventEntity? {
        val session = getOpenSession() ?: return null
        val event = CashDrawerEventEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            venueId = venueId,
            type = CashDrawerEventType.PAY_OUT.name,
            amountCents = amountCents,
            note = note,
            staffId = staffId,
            staffName = staffName,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertEvent(event)
        Log.d(TAG, "✅ Pay-out recorded locally: $amountCents")

        // La fila local nace provisional y adopta el id del server si éste confirma.
        return fireApiPayOut(amountCents, note, event.id) ?: event
    }

    private suspend fun fireApiPayOut(amountCents: Int, note: String?, localEventId: String): CashDrawerEventEntity? {
        try {
            val dollars = amountCents / 100.0
            val requestBody = json.encodeToString(
                PayOutRequest.serializer(),
                // La llave es el id con el que la fila YA quedó en Room, no uno nuevo.
                PayOutRequest(amount = dollars, note = note, localId = localEventId),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/pay-out")
                .post(requestBody)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d(TAG, "✅ API pay-out success")
                return promoteEvent(localEventId, parseEventId(body))
            } else {
                Log.e(TAG, "❌ API pay-out failed: $code - $body")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ API pay-out error: ${e.message}")
        }
        return null
    }

    /**
     * La venta en efectivo que el cajero acaba de cobrar, PROVISIONAL en Room.
     *
     * 🔴 El dueño de este movimiento es el SERVER: lo crea al cobrar
     * (`shared/cashDrawerPosting.postCashSaleToDrawer`) y el endpoint
     * `/cash-drawer/sync` descarta a propósito el que empuja el cliente. Esta fila
     * existe sólo para que la pantalla no se quede muda entre el cobro y el
     * siguiente sync —con o sin red— y desaparece en cuanto llega la confirmada, vía
     * [SERVER_OWNED_EVENT_TYPES]. Si no desapareciera, la MISMA venta sumaría dos
     * veces y el cajón inventaría un sobrante.
     */
    suspend fun addCashSale(amountCents: Int, orderId: String?): CashDrawerEventEntity? {
        val session = getOpenSession() ?: return null
        val event = CashDrawerEventEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            venueId = venueId,
            type = CashDrawerEventType.CASH_SALE.name,
            amountCents = amountCents,
            note = null,
            staffId = staffId,
            staffName = staffName,
            orderId = orderId,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertEvent(event)
        Log.d(TAG, "✅ Cash sale recorded: $amountCents, order: $orderId")

        // Push to the server so the backend session's expectedAmount tracks
        // real cash sales (was Room-only → server drawer drifted). Uses the
        // batch /sync endpoint; fire-and-forget like the other event POSTs.
        try {
            val payload = SyncEventsRequest(
                events = listOf(
                    SyncEventDto(
                        type = CashDrawerEventType.CASH_SALE.name,
                        amount = amountCents / 100.0,
                        staffId = staffId,
                        staffName = staffName,
                        orderId = orderId,
                        createdAt = java.time.Instant.ofEpochMilli(event.createdAt).toString(),
                        localId = event.id,
                    ),
                ),
            )
            val body = json.encodeToString(SyncEventsRequest.serializer(), payload)
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("$baseUrl/sync")
                .post(body)
                .build()
            val code = withContext(Dispatchers.IO) { client.newCall(request).execute().use { it.code } }
            if (code !in 200..299) Log.e(TAG, "❌ API cash-sale sync failed: $code")
        } catch (e: Exception) {
            Log.e(TAG, "❌ API cash-sale sync error: ${e.message}")
        }
        return event
    }

    @kotlinx.serialization.Serializable
    private data class SyncEventDto(
        val type: String,
        val amount: Double,
        val staffId: String,
        val staffName: String,
        val orderId: String? = null,
        val createdAt: String,
        /**
         * Llave de idempotencia: el MISMO id con el que el evento vive en Room.
         *
         * Este push es fire-and-forget y sin cola de reintento: si la respuesta se pierde y
         * el lote se reenvía, sin esta llave el server insertaba las filas otra vez y el
         * cajón quedaba con efectivo que nunca existió. Con ella el server deduplica contra
         * `@@unique([venueId, localId])`. NUNCA generar un UUID nuevo aquí — tiene que ser
         * el de Room, o la llave deja de ser estable entre reintentos y no sirve de nada.
         */
        val localId: String,
    )

    @kotlinx.serialization.Serializable
    private data class SyncEventsRequest(val events: List<SyncEventDto>)

    // MARK: - Close Session

    suspend fun closeSession(actualAmountCents: Int, note: String?): CashDrawerSessionEntity? {
        val session = getOpenSession() ?: return null
        val expected = computeExpectedAmount(session.id, session.startingAmountCents)
        val overShort = actualAmountCents - expected

        val updated = session.copy(
            closedByStaffId = staffId,
            closedByName = staffName,
            closedAt = System.currentTimeMillis(),
            actualAmountCents = actualAmountCents,
            overShortCents = overShort,
            closingNote = note,
            status = CashDrawerStatus.CLOSED.name,
        )
        dao.updateSession(updated)

        // Record CLOSE event
        val event = CashDrawerEventEntity(
            id = UUID.randomUUID().toString(),
            sessionId = session.id,
            venueId = venueId,
            type = CashDrawerEventType.CLOSE.name,
            amountCents = actualAmountCents,
            note = note,
            staffId = staffId,
            staffName = staffName,
            createdAt = System.currentTimeMillis(),
        )
        dao.insertEvent(event)

        Log.d(TAG, "✅ Session closed locally: ${session.id}, actual: $actualAmountCents, over/short: $overShort")

        // Fire API call in background
        fireApiClose(actualAmountCents, note)

        return updated
    }

    private suspend fun fireApiClose(actualAmountCents: Int, note: String?) {
        try {
            val dollars = actualAmountCents / 100.0
            val requestBody = json.encodeToString(
                CloseDrawerRequest.serializer(),
                CloseDrawerRequest(actualAmount = dollars, note = note),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/close")
                .post(requestBody)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d(TAG, "✅ API close session success")
            } else {
                Log.e(TAG, "❌ API close session failed: $code - $body")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ API close session error: ${e.message}")
        }
    }

    // MARK: - Events & Computation

    suspend fun getEvents(sessionId: String): List<CashDrawerEventEntity> {
        return dao.getSessionEvents(sessionId)
    }

    suspend fun computeExpectedAmount(sessionId: String, startingAmountCents: Int): Int {
        val cashSales = dao.sumEventsByType(sessionId, CashDrawerEventType.CASH_SALE.name)
        val payIns = dao.sumEventsByType(sessionId, CashDrawerEventType.PAY_IN.name)
        val payOuts = dao.sumEventsByType(sessionId, CashDrawerEventType.PAY_OUT.name)
        return startingAmountCents + cashSales + payIns - payOuts
    }

    // MARK: - API Response Parsing Helpers

    /**
     * Visible para test: el server manda `null` explícito en los campos opcionales
     * y `jsonPrimitive.content` los convierte en la CADENA "null" — que luego se
     * pinta tal cual en pantalla. Pasó con la nota de cierre en el historial.
     */
    internal fun parseSessionFromApi(obj: JsonObject): CashDrawerSessionEntity {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
        // 🔴 `doubleOrNull`, NUNCA `double`.
        //
        // `jsonPrimitive.double` sobre un JSON null intenta convertir el TEXTO
        // "null" y revienta con NumberFormatException. En una sesión ABIERTA,
        // `actualAmount` y `overShort` son nulos por definición —el dinero aún no
        // se ha contado— así que la sesión en curso del server NUNCA se parseaba:
        // "Parse current session error: For input string: \"null\"" en el log, y
        // la caja abierta en otro dispositivo era invisible para este. El
        // historial sí entraba, porque sus sesiones están cerradas y traen cifra.
        val startingDollars = obj["startingAmount"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val actualDollars = obj["actualAmount"]?.jsonPrimitive?.doubleOrNull
        val overShortDollars = (obj["overShort"] ?: obj["overShortAmount"])?.jsonPrimitive?.doubleOrNull
        val status = obj["status"]?.jsonPrimitive?.contentOrNull ?: CashDrawerStatus.OPEN.name

        return CashDrawerSessionEntity(
            id = id,
            venueId = venueId,
            deviceName = obj["deviceName"]?.jsonPrimitive?.contentOrNull,
            openedByStaffId = obj["openedByStaffId"]?.jsonPrimitive?.contentOrNull ?: "",
            openedByName = obj["openedByName"]?.jsonPrimitive?.contentOrNull ?: "",
            openedAt = parseTimestamp(obj["openedAt"]?.jsonPrimitive?.contentOrNull),
            startingAmountCents = (startingDollars * 100).toInt(),
            closedByStaffId = obj["closedByStaffId"]?.jsonPrimitive?.contentOrNull,
            closedByName = obj["closedByName"]?.jsonPrimitive?.contentOrNull,
            closedAt = obj["closedAt"]?.jsonPrimitive?.contentOrNull?.let { parseTimestamp(it) },
            actualAmountCents = actualDollars?.let { (it * 100).toInt() },
            overShortCents = overShortDollars?.let { (it * 100).toInt() },
            closingNote = obj["closingNote"]?.jsonPrimitive?.contentOrNull,
            status = status,
        )
    }

    private fun parseEventFromApi(obj: JsonObject, sessionId: String): CashDrawerEventEntity {
        val amountDollars = obj["amount"]?.jsonPrimitive?.doubleOrNull ?: 0.0

        return CashDrawerEventEntity(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString(),
            sessionId = sessionId,
            venueId = venueId,
            type = obj["type"]?.jsonPrimitive?.contentOrNull ?: "",
            amountCents = (amountDollars * 100).toInt(),
            note = obj["note"]?.jsonPrimitive?.contentOrNull,
            staffId = obj["staffId"]?.jsonPrimitive?.contentOrNull ?: "",
            staffName = obj["staffName"]?.jsonPrimitive?.contentOrNull ?: "",
            orderId = obj["orderId"]?.jsonPrimitive?.contentOrNull,
            createdAt = parseTimestamp(obj["createdAt"]?.jsonPrimitive?.contentOrNull),
        )
    }

    companion object {
        /**
         * 🔴 Tipos que el SERVER escribe POR SU CUENTA, y de los que esta app sólo
         * guarda una copia provisional para pintar la pantalla al instante.
         *
         * - `OPEN`: lo crea `cash-drawer.mobile.service.openSession` junto con la caja.
         * - `CASH_SALE`: lo crea `shared/cashDrawerPosting.postCashSaleToDrawer` al
         *   cobrar, y el endpoint `/cash-drawer/sync` **descarta** el que manda el
         *   cliente. O sea que el del server existe siempre y el local es, por
         *   definición, la misma venta con otro id.
         *
         * Cuando el sync trae la lista confirmada, las copias locales de estos tipos
         * se borran: si no, la MISMA venta suma dos veces y el cajón inventa un
         * sobrante — el mismo defecto del reembolso duplicado (commit `3acc7bb`),
         * pero al revés.
         *
         * `PAY_IN`/`PAY_OUT` NO van aquí: los escribe el cliente y uno registrado sin
         * red todavía no existe en el server. Borrarlo le inventaría al cajero un
         * faltante. Ésos se reconcilian por identidad, en [promoteEvent].
         */
        private val SERVER_OWNED_EVENT_TYPES = listOf(
            CashDrawerEventType.OPEN.name,
            CashDrawerEventType.CASH_SALE.name,
        )
    }

    private fun parseTimestamp(value: String?): Long {
        if (value == null) return System.currentTimeMillis()
        // Server sends full ISO-8601 with millis + Z ("2026-07-17T19:50:18.274Z").
        return try {
            java.time.Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            try {
                java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.parse(value)?.time ?: System.currentTimeMillis()
            } catch (_: Exception) {
                value.toLongOrNull() ?: System.currentTimeMillis()
            }
        }
    }
}
