package com.avoqado.pos.areatickets.data

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

class AreaTicketException(
    val code: String,
    override val message: String,
    val retryable: Boolean,
) : Exception(message)

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

    suspend fun settings(): AreaTicketSettingsData {
        val venueId = venueId()
        session.ensureVenue(venueId)
        return api.getAreaTicketSettings(venueId).unwrap()
    }

    suspend fun restore(): AreaTicketCheckout? {
        val venueId = venueId()
        session.ensureVenue(venueId)
        session.current()?.let { return it }
        val checkoutId = session.persistedCheckoutId(venueId) ?: return null
        return runCatching {
            api.getAreaTicketCheckout(venueId, checkoutId).unwrap().checkout
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
        api.resolveAreaTicketScan(venueId(), ScanRequest(code.trim(), "CHECKOUT")).unwrap()

    suspend fun addTicket(code: String): AreaTicketCheckout {
        val venueId = venueId()
        session.ensureVenue(venueId)
        val checkout = session.current() ?: api.createAreaTicketCheckout(
            venueId,
            IdempotentRequest(session.createIdempotencyKey()),
        ).unwrap().checkout.also { session.update(venueId, it) }

        if (checkout.status != "OPEN") {
            throw AreaTicketException("CHECKOUT_SESSION_FROZEN", "La sesión de vales ya no admite cambios.", false)
        }
        val updated = api.addAreaTicketToCheckout(
            venueId,
            checkout.id,
            AddTicketRequest(code.trim(), UUID.randomUUID().toString()),
        ).unwrap().checkout
        session.update(venueId, updated)
        return updated
    }

    suspend fun removeTicket(ticketId: String): AreaTicketCheckout {
        val venueId = venueId()
        val checkout = session.current()
            ?: throw AreaTicketException("CHECKOUT_NOT_FOUND", "No hay una sesión de vales abierta.", false)
        val updated = api.removeAreaTicketFromCheckout(
            venueId,
            checkout.id,
            ticketId,
            IdempotentRequest(UUID.randomUUID().toString()),
        ).unwrap().checkout
        session.update(venueId, updated)
        return updated
    }

    suspend fun heartbeat(): AreaTicketCheckout? {
        val venueId = venueId()
        val checkout = session.current() ?: return null
        val updated = api.heartbeatAreaTicketCheckout(
            venueId,
            checkout.id,
            IdempotentRequest(UUID.randomUUID().toString()),
        ).unwrap().checkout
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

        val updated = api.materializeAreaTicketCheckout(
            venueId,
            checkout.id,
            MaterializeCheckoutRequest(
                idempotencyKey = session.materializeIdempotencyKey(),
                normalItems = normalItems,
                customerName = customerName,
                note = note,
            ),
        ).unwrap().checkout
        session.update(venueId, updated)
        return updated
    }

    suspend fun refresh(): AreaTicketCheckout? {
        val venueId = venueId()
        val checkout = session.current() ?: return null
        val updated = api.getAreaTicketCheckout(venueId, checkout.id).unwrap().checkout
        session.update(venueId, updated)
        if (updated.status == "PAID" || updated.status == "CANCELLED" || updated.status == "EXPIRED") {
            session.clear()
        }
        return updated
    }

    suspend fun cancel(): AreaTicketCheckout? {
        val venueId = venueId()
        val checkout = session.current() ?: return null
        val updated = api.cancelAreaTicketCheckout(
            venueId,
            checkout.id,
            IdempotentRequest(UUID.randomUUID().toString()),
        ).unwrap().checkout
        session.clear()
        return updated
    }

    suspend fun issue(
        lines: List<IssueAreaTicketLineRequest>,
        idempotencyKey: String = UUID.randomUUID().toString(),
    ): AreaTicket =
        api.issueAreaTicket(
            venueId(),
            IssueAreaTicketRequest(idempotencyKey, lines),
        ).unwrap().ticket

    suspend fun recordPrint(
        ticketId: String,
        printed: Boolean,
        reprint: Boolean = false,
        reason: String? = null,
        errorCode: String? = null,
    ) {
        val auditReason = normalizeAreaTicketPrintReason(reprint, reason)
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
        ).unwrap()
    }

    suspend fun pendingDelivery(cursor: String? = null): PendingFulfillmentData =
        api.getPendingAreaTicketFulfillment(venueId(), cursor).unwrap()

    suspend fun resolveDelivery(code: String): DeliveryResolutionData =
        api.resolveAreaTicketFulfillment(
            venueId(),
            ScanRequest(code.trim(), "AREA_DELIVERY"),
        ).unwrap()

    suspend fun fulfill(ticketId: String, scannedReceipt: Boolean) {
        api.fulfillAreaTicket(
            venueId(),
            ticketId,
            FulfillAreaTicketRequest(
                idempotencyKey = UUID.randomUUID().toString(),
                method = if (scannedReceipt) "RECEIPT_SCAN" else "PAPER_CONFIRMATION",
            ),
        ).unwrap()
    }
}

internal fun normalizeAreaTicketPrintReason(reprint: Boolean, reason: String?): String? =
    reason?.trim()?.takeIf { it.isNotEmpty() }
        ?: if (reprint) "Reimpresión solicitada por el operador desde el POS." else null
