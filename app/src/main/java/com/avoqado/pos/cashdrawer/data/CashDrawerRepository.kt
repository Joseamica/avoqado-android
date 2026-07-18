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
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "💰 CashDrawerRepo"

// MARK: - API Request/Response Models

@Serializable
private data class OpenDrawerRequest(val startingAmount: Double)

@Serializable
private data class PayInRequest(val amount: Double, val note: String? = null)

@Serializable
private data class PayOutRequest(val amount: Double, val note: String? = null)

@Serializable
private data class CloseDrawerRequest(val actualAmount: Double, val note: String? = null)

@Singleton
class CashDrawerRepository @Inject constructor(
    private val dao: CashDrawerDao,
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val venueId: String
        get() = secureStorage.venueId ?: ""

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
    data class TenderRow(val method: String, val totalCents: Int)

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
                val method = obj["method"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val dollars = obj["total"]?.jsonPrimitive?.double ?: 0.0
                TenderRow(method = method, totalCents = (dollars * 100).toInt())
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
                // Server envelope is {success, data: session} with the events
                // ARRAY EMBEDDED in the session; accept legacy top-level keys too.
                val sessionObj = (root["data"] as? JsonObject)
                    ?: root["data"]?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonObject }
                    ?: root["session"]?.jsonObject
                if (sessionObj != null) {
                    val session = parseSessionFromApi(sessionObj)
                    dao.insertSession(session)

                    // Events live inside the session payload (fallback: top-level).
                    val eventsArray = sessionObj["events"]?.jsonArray ?: root["events"]?.jsonArray
                    eventsArray?.forEach { eventJson ->
                        val event = parseEventFromApi(eventJson.jsonObject, session.id)
                        dao.insertEvent(event)
                    }
                    Log.d(TAG, "✅ Synced current session from API: ${session.id} (${eventsArray?.size ?: 0} events)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Parse current session error: ${e.message}")
            }
        }
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

        // Fire API call in background
        fireApiOpen(startingAmountCents)

        return session
    }

    private suspend fun fireApiOpen(startingAmountCents: Int) {
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
            } else {
                Log.e(TAG, "❌ API open session failed: $code - $body")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ API open session error: ${e.message}")
        }
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

        // Fire API call in background
        fireApiPayIn(amountCents, note)

        return event
    }

    private suspend fun fireApiPayIn(amountCents: Int, note: String?) {
        try {
            val dollars = amountCents / 100.0
            val requestBody = json.encodeToString(
                PayInRequest.serializer(),
                PayInRequest(amount = dollars, note = note),
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
            } else {
                Log.e(TAG, "❌ API pay-in failed: $code - $body")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ API pay-in error: ${e.message}")
        }
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

        // Fire API call in background
        fireApiPayOut(amountCents, note)

        return event
    }

    private suspend fun fireApiPayOut(amountCents: Int, note: String?) {
        try {
            val dollars = amountCents / 100.0
            val requestBody = json.encodeToString(
                PayOutRequest.serializer(),
                PayOutRequest(amount = dollars, note = note),
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
            } else {
                Log.e(TAG, "❌ API pay-out failed: $code - $body")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ API pay-out error: ${e.message}")
        }
    }

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

    private fun parseSessionFromApi(obj: JsonObject): CashDrawerSessionEntity {
        val id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
        val startingDollars = obj["startingAmount"]?.jsonPrimitive?.double ?: 0.0
        val actualDollars = obj["actualAmount"]?.jsonPrimitive?.double
        val overShortDollars = (obj["overShort"] ?: obj["overShortAmount"])?.jsonPrimitive?.double
        val status = obj["status"]?.jsonPrimitive?.content ?: CashDrawerStatus.OPEN.name

        return CashDrawerSessionEntity(
            id = id,
            venueId = venueId,
            deviceName = obj["deviceName"]?.jsonPrimitive?.content,
            openedByStaffId = obj["openedByStaffId"]?.jsonPrimitive?.content ?: "",
            openedByName = obj["openedByName"]?.jsonPrimitive?.content ?: "",
            openedAt = parseTimestamp(obj["openedAt"]?.jsonPrimitive?.content),
            startingAmountCents = (startingDollars * 100).toInt(),
            closedByStaffId = obj["closedByStaffId"]?.jsonPrimitive?.content,
            closedByName = obj["closedByName"]?.jsonPrimitive?.content,
            closedAt = obj["closedAt"]?.jsonPrimitive?.content?.let { parseTimestamp(it) },
            actualAmountCents = actualDollars?.let { (it * 100).toInt() },
            overShortCents = overShortDollars?.let { (it * 100).toInt() },
            closingNote = obj["closingNote"]?.jsonPrimitive?.content,
            status = status,
        )
    }

    private fun parseEventFromApi(obj: JsonObject, sessionId: String): CashDrawerEventEntity {
        val amountDollars = obj["amount"]?.jsonPrimitive?.double ?: 0.0

        return CashDrawerEventEntity(
            id = obj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString(),
            sessionId = sessionId,
            venueId = venueId,
            type = obj["type"]?.jsonPrimitive?.content ?: "",
            amountCents = (amountDollars * 100).toInt(),
            note = obj["note"]?.jsonPrimitive?.content,
            staffId = obj["staffId"]?.jsonPrimitive?.content ?: "",
            staffName = obj["staffName"]?.jsonPrimitive?.content ?: "",
            orderId = obj["orderId"]?.jsonPrimitive?.content,
            createdAt = parseTimestamp(obj["createdAt"]?.jsonPrimitive?.content),
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
