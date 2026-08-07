package com.avoqado.pos.areatickets.data

import android.util.Log
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.HttpException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

class AreaTicketException(
    val code: String,
    override val message: String,
    val retryable: Boolean,
) : Exception(message)

private val areaTicketErrorJson = Json { ignoreUnknownKeys = true }

internal fun parseAreaTicketHttpError(body: String?, statusCode: Int): AreaTicketException {
    val apiError = body?.let { raw ->
        runCatching {
            areaTicketErrorJson.decodeFromString<AreaTicketEnvelope<JsonObject>>(raw).error
        }.getOrNull()
    }
    return AreaTicketException(
        code = apiError?.code ?: "HTTP_$statusCode",
        message = apiError?.message ?: when (statusCode) {
            401 -> "La sesión venció. Inicia sesión de nuevo."
            403 -> "Esta terminal no tiene permiso para realizar esta operación."
            404 -> "No encontramos ese vale o comprobante en este local."
            else -> "No se pudo completar la operación de vales."
        },
        retryable = apiError?.retryable == true || statusCode >= 500,
    )
}

@Singleton
class AreaTicketSession @Inject constructor(
    private val secureStorage: SecureStorage,
) {
    private val _checkout = MutableStateFlow<AreaTicketCheckout?>(null)
    val checkout: StateFlow<AreaTicketCheckout?> = _checkout.asStateFlow()

    private var venueId: String? = secureStorage.areaTicketCheckoutVenueId
    private var createKey: String = secureStorage.areaTicketCreateKey
        ?: UUID.randomUUID().toString().also { secureStorage.areaTicketCreateKey = it }
    private var materializeKey: String = secureStorage.areaTicketMaterializeKey
        ?: UUID.randomUUID().toString().also { secureStorage.areaTicketMaterializeKey = it }

    fun current(): AreaTicketCheckout? = _checkout.value

    fun update(currentVenueId: String, checkout: AreaTicketCheckout) {
        if (venueId != null && venueId != currentVenueId) clear()
        venueId = currentVenueId
        _checkout.value = checkout
        secureStorage.areaTicketCheckoutVenueId = currentVenueId
        secureStorage.areaTicketCheckoutId = checkout.id
    }

    fun ensureVenue(currentVenueId: String) {
        if (venueId != null && venueId != currentVenueId) clear()
        venueId = currentVenueId
    }

    fun createIdempotencyKey(): String = createKey
    fun materializeIdempotencyKey(): String = materializeKey
    fun persistedCheckoutId(currentVenueId: String): String? =
        secureStorage.areaTicketCheckoutId.takeIf {
            secureStorage.areaTicketCheckoutVenueId == currentVenueId
        }

    fun clear() {
        venueId = null
        _checkout.value = null
        createKey = UUID.randomUUID().toString()
        materializeKey = UUID.randomUUID().toString()
        secureStorage.areaTicketCheckoutId = null
        secureStorage.areaTicketCheckoutVenueId = null
        secureStorage.areaTicketCreateKey = createKey
        secureStorage.areaTicketMaterializeKey = materializeKey
    }
}

@Singleton
class AreaTicketRepository @Inject constructor(
    private val api: ApiService,
    private val secureStorage: SecureStorage,
    val session: AreaTicketSession,
    private val payloadCache: PayloadCache,
) {
    private fun venueId(): String =
        secureStorage.venueId ?: throw AreaTicketException("VENUE_REQUIRED", "Selecciona un local antes de continuar.", false)

    private fun <T> AreaTicketEnvelope<T>.unwrap(): T {
        if (success && data != null) return data
        val failure = error
        throw AreaTicketException(
            code = failure?.code ?: "AREA_TICKET_REQUEST_FAILED",
            message = failure?.message ?: "No se pudo completar la operación de vales.",
            retryable = failure?.retryable == true,
        )
    }

    private suspend fun <T> request(block: suspend () -> AreaTicketEnvelope<T>): T =
        try {
            block().unwrap()
        } catch (error: HttpException) {
            throw parseAreaTicketHttpError(
                body = error.response()?.errorBody()?.string(),
                statusCode = error.code(),
            )
        } catch (error: java.io.IOException) {
            // V7 mantiene los vales multi-dispositivo online por diseño. No se
            // finge una emisión local que la caja de otra tablet no podría
            // resolver ni cobrar de forma autoritativa.
            throw AreaTicketException(
                code = "AREA_TICKETS_REQUIRE_CONNECTION",
                message = "Los vales por área requieren conexión con Avoqado. El POS normal sigue disponible; reintenta el vale cuando vuelva el servidor.",
                retryable = true,
            )
        }

    suspend fun settings(): AreaTicketSettingsData {
        val venueId = venueId()
        session.ensureVenue(venueId)
        return try {
            request { api.getAreaTicketSettings(venueId) }.also { settings ->
                payloadCache.save(
                    PayloadCache.TYPE_AREA_TICKET_SETTINGS,
                    venueId,
                    areaTicketErrorJson.encodeToString(AreaTicketSettingsData.serializer(), settings),
                )
            }
        } catch (error: Exception) {
            if (error is AreaTicketException && !error.retryable) throw error

            val cached = payloadCache.load(PayloadCache.TYPE_AREA_TICKET_SETTINGS, venueId)
                ?: throw error
            runCatching {
                areaTicketErrorJson.decodeFromString<AreaTicketSettingsData>(cached.json)
            }.onSuccess {
                Log.d(
                    TAG,
                    "🗂️ Configuración de vales hidratada del cache (hace ${cached.ageMinutes} min)",
                )
            }.getOrElse { throw error }
        }
    }

    suspend fun restore(): AreaTicketCheckout? {
        val venueId = venueId()
        session.ensureVenue(venueId)
        session.current()?.let { return it }
        val checkoutId = session.persistedCheckoutId(venueId) ?: return null
        return runCatching {
            request { api.getAreaTicketCheckout(venueId, checkoutId) }.checkout
        }.getOrElse {
            return null
        }.also { checkout ->
            if (checkout.status in setOf("PAID", "CANCELLED", "EXPIRED")) {
                session.clear()
            } else {
                session.update(venueId, checkout)
            }
        }.takeUnless { it.status in setOf("PAID", "CANCELLED", "EXPIRED") }
    }

    suspend fun resolveCheckoutScan(code: String): AreaTicketScanData =
        request { api.resolveAreaTicketScan(venueId(), ScanRequest(code.trim(), "CHECKOUT")) }

    suspend fun addTicket(code: String): AreaTicketCheckout {
        val venueId = venueId()
        session.ensureVenue(venueId)
        val checkout = session.current() ?: request {
            api.createAreaTicketCheckout(
                venueId,
                IdempotentRequest(session.createIdempotencyKey()),
            )
        }.checkout.also { session.update(venueId, it) }

        if (checkout.status != "OPEN") {
            throw AreaTicketException("CHECKOUT_SESSION_FROZEN", "La sesión de vales ya no admite cambios.", false)
        }
        val updated = request {
            api.addAreaTicketToCheckout(
                venueId,
                checkout.id,
                AddTicketRequest(code.trim(), UUID.randomUUID().toString()),
            )
        }.checkout
        session.update(venueId, updated)
        return updated
    }

    suspend fun removeTicket(ticketId: String): AreaTicketCheckout {
        val venueId = venueId()
        val checkout = session.current()
            ?: throw AreaTicketException("CHECKOUT_NOT_FOUND", "No hay una sesión de vales abierta.", false)
        val updated = request {
            api.removeAreaTicketFromCheckout(
                venueId,
                checkout.id,
                ticketId,
                IdempotentRequest(UUID.randomUUID().toString()),
            )
        }.checkout
        session.update(venueId, updated)
        return updated
    }

    suspend fun heartbeat(): AreaTicketCheckout? {
        val venueId = venueId()
        val checkout = session.current() ?: return null
        val updated = request {
            api.heartbeatAreaTicketCheckout(
                venueId,
                checkout.id,
                IdempotentRequest(UUID.randomUUID().toString()),
            )
        }.checkout
        session.update(venueId, updated)
        return updated
    }

    suspend fun materialize(
        normalItems: List<NormalCheckoutItem>,
        customerName: String?,
        note: String?,
    ): AreaTicketCheckout {
        val venueId = venueId()
        val checkout = session.current()
            ?: throw AreaTicketException("CHECKOUT_NOT_FOUND", "No hay una sesión de vales abierta.", false)
        if (checkout.order != null) return checkout

        val updated = request {
            api.materializeAreaTicketCheckout(
                venueId,
                checkout.id,
                MaterializeCheckoutRequest(
                    idempotencyKey = session.materializeIdempotencyKey(),
                    normalItems = normalItems,
                    customerName = customerName,
                    note = note,
                ),
            )
        }.checkout
        session.update(venueId, updated)
        return updated
    }

    suspend fun refresh(): AreaTicketCheckout? {
        val venueId = venueId()
        val checkout = session.current() ?: return null
        val updated = request { api.getAreaTicketCheckout(venueId, checkout.id) }.checkout
        session.update(venueId, updated)
        if (updated.status == "PAID" || updated.status == "CANCELLED" || updated.status == "EXPIRED") {
            session.clear()
        }
        return updated
    }

    suspend fun cancel(): AreaTicketCheckout? {
        val venueId = venueId()
        val checkout = session.current() ?: return null
        val updated = request {
            api.cancelAreaTicketCheckout(
                venueId,
                checkout.id,
                IdempotentRequest(UUID.randomUUID().toString()),
            )
        }.checkout
        session.clear()
        return updated
    }

    suspend fun issue(
        lines: List<IssueAreaTicketLineRequest>,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): AreaTicket =
        request {
            api.issueAreaTicket(
                venueId(),
                IssueAreaTicketRequest(idempotencyKey, lines),
            )
        }.ticket

    suspend fun recordPrint(
        ticketId: String,
        printed: Boolean,
        reprint: Boolean = false,
        reason: String? = null,
        errorCode: String? = null,
    ) {
        val auditReason = normalizeAreaTicketPrintReason(reprint, reason)
        request {
            api.recordAreaTicketPrintAttempt(
                venueId(),
                ticketId,
                PrintAttemptRequest(
                    idempotencyKey = UUID.randomUUID().toString(),
                    status = if (printed) "PRINTED" else "FAILED",
                    kind = if (reprint) "REPRINT" else "ORIGINAL",
                    reason = auditReason,
                    errorCode = errorCode,
                ),
            )
        }
    }

    suspend fun pendingDelivery(cursor: String? = null): PendingFulfillmentData =
        request { api.getPendingAreaTicketFulfillment(venueId(), cursor) }

    suspend fun resolveDelivery(code: String): DeliveryResolutionData =
        request {
            api.resolveAreaTicketFulfillment(
                venueId(),
                ScanRequest(code.trim(), "AREA_DELIVERY"),
            )
        }

    suspend fun fulfill(ticketId: String, scannedReceipt: Boolean) {
        request {
            api.fulfillAreaTicket(
                venueId(),
                ticketId,
                FulfillAreaTicketRequest(
                    idempotencyKey = UUID.randomUUID().toString(),
                    method = if (scannedReceipt) "RECEIPT_SCAN" else "PAPER_CONFIRMATION",
                ),
            )
        }
    }

    private companion object {
        const val TAG = "AreaTicketRepository"
    }
}

internal fun normalizeAreaTicketPrintReason(reprint: Boolean, reason: String?): String? =
    reason?.trim()?.takeIf { it.isNotEmpty() }
        ?: if (reprint) "Reimpresión solicitada por el operador desde el POS." else null
