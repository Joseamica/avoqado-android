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
                val sessionObj = root["session"]?.jsonObject
                if (sessionObj != null) {
                    val session = parseSessionFromApi(sessionObj)
                    dao.insertSession(session)

                    // Parse events if present
                    val eventsArray = root["events"]?.jsonArray
                    eventsArray?.forEach { eventJson ->
                        val event = parseEventFromApi(eventJson.jsonObject, session.id)
                        dao.insertEvent(event)
                    }
                    Log.d(TAG, "✅ Synced current session from API: ${session.id}")
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
        return event
    }

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
        val overShortDollars = obj["overShortAmount"]?.jsonPrimitive?.double
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
        // Try parsing as ISO-8601
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.parse(value)?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            // Try as millis
            value.toLongOrNull() ?: System.currentTimeMillis()
        }
    }
}
