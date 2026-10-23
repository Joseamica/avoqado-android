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

/** Estados en los que una sesión de cobro ya no admite más movimientos. */
private val AREA_TICKET_TERMINAL_STATUSES = setOf("PAID", "CANCELLED", "EXPIRED")

/**
 * Si una sesión de cobro todavía sirve para cobrar.
 *
 * Mira DOS cosas, no una: el estado y la caducidad. El venue caduca las sesiones
 * por edad (`checkoutSessionMaxAgeMinutes`) y eso NO se refleja en `status`, que
 * se queda en OPEN. El server manda `expiresAt` justamente para esto.
 *
 * Ante una fecha ilegible devuelve `true`: el server sigue siendo la autoridad y
 * rechazará si de verdad venció. Tirar aquí una sesión buena sería peor.
 */
internal fun isAreaTicketCheckoutUsable(
    status: String,
    expiresAt: String?,
    nowMillis: Long = System.currentTimeMillis(),
): Boolean {
    if (status in AREA_TICKET_TERMINAL_STATUSES) return false
    val expiry = expiresAt?.takeIf { it.isNotBlank() }
        ?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
        ?: return true
    return expiry > nowMillis
}

/** Una venta de vales que ya recibió un abono: el servidor no la deja cancelar (409 `CHECKOUT_HAS_PAYMENT`). */
internal fun AreaTicketCheckout.tieneAbono(): Boolean =
    status == "PARTIALLY_PAID" || (order?.paidAmount?.moneyToCents() ?: 0) > 0

/** La sesión cambió debajo de esta respuesta (se soltó y se abrió otra): no se aplica a la nueva (regla 3). */
private fun sesionReemplazada() = AreaTicketException(
    "CHECKOUT_SUPERSEDED",
    "La venta de vales cambió mientras se consultaba. Vuelve a intentarlo.",
    true,
)

internal fun parseAreaTicketHttpError(body: String?, statusCode: Int): AreaTicketException {
    val apiError = body?.let { raw ->
        runCatching {
            areaTicketErrorJson.decodeFromString<AreaTicketEnvelope<JsonObject>>(raw).error
        }.getOrNull()
    }
    val code = apiError?.code ?: "HTTP_$statusCode"
    val message = when (code) {
        "CHECKOUT_TERMINAL_MISMATCH" ->
            "Esta terminal no funciona como Caja de vales. Escanea el vale en la terminal de Caja o configúrala en Dashboard → Configuración → Vales por área → Terminales."
        // No es un fallo del cobro: la sesión se quedó abierta demasiado tiempo. Lo
        // único que hay que hacer es volver a escanear, y el vale sigue vivo.
        "CHECKOUT_SESSION_STALE" ->
            "El cobro de estos vales quedó abierto demasiado tiempo y venció. Vuelve a escanear el vale para cobrarlo — no se cobró nada."
        else -> apiError?.message ?: when (statusCode) {
            401 -> "La sesión venció. Inicia sesión de nuevo."
            403 -> "Esta terminal no tiene permiso para realizar esta operación."
            404 -> "No encontramos ese vale o comprobante en este local."
            else -> "No se pudo completar la operación de vales."
        }
    }
    return AreaTicketException(
        code = code,
        message = message,
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

    /**
     * Otra sucursal (cambio de local, o entrar a la app y que el login elija otra): se olvida la memoria, pero NO la
     * referencia guardada del apartado de la otra sucursal (Codex r7 #2): va marcada con su sucursal y la restauración filtra
     * por ella, así que una venta con abono vuelve a aparecer al regresar. Una sesión nueva aquí sí la reemplaza.
     */
    fun ensureVenue(currentVenueId: String) {
        if (venueId != null && venueId != currentVenueId) {
            cobroQueLaArmo = null
            ventaDuena = null
            _checkout.value = null
            createKey = UUID.randomUUID().toString()
            materializeKey = UUID.randomUUID().toString()
            secureStorage.areaTicketCreateKey = createKey
            secureStorage.areaTicketMaterializeKey = materializeKey
        }
        venueId = currentVenueId
    }

    /**
     * 🔴 DINERO. La orden [orderId] de esta sesión ya tiene un abono, aunque esta copia no lo traiga (su relectura tras cobrar
     * pudo fallar). Así el carrito ya no le proyecta los vales (D3, Codex r1). La siguiente respuesta del servidor la pone al día.
     */
    fun marcarConAbono(orderId: String, saldoCents: Int? = null) {
        val actual = _checkout.value ?: return
        val orden = actual.order ?: return
        if (orden.id != orderId) return
        // El saldo que dejó el cobro (el «Saldo pendiente» del carrito): el aviso «Cobrar saldo» lo enseña aunque esta copia
        // sea de antes del abono (regla 10).
        // Nunca sube un saldo que ya se sabía menor (la respuesta tardía de un abono tras otro abono, Codex r8 #2).
        val saldoConocido = orden.remainingBalance.moneyToCents().takeIf { actual.tieneAbono() && it > 0 }
        val conSaldo = saldoCents?.takeIf { it > 0 && (saldoConocido == null || it < saldoConocido) }?.let { saldo ->
            val total = orden.total.moneyToCents()
            orden.copy(remainingBalance = centsToMoney(saldo), paidAmount = centsToMoney((total - saldo).coerceAtLeast(0)))
        } ?: orden
        _checkout.value = actual.copy(status = "PARTIALLY_PAID", order = conSaldo)
    }

    /**
     * 🔴 DINERO (regla 8, rediseño r7, 21-oct). La venta del carrito (`CartState.identidadDeVenta`) que abrió este apartado.
     * Sus vales sólo se proyectan a esa venta: si el carrito empezó otra (vaciar, cobrar, guardar, cualquier reemplazo), el
     * apartado sin abono quedó soltado y se cancela en cuanto se pueda; nunca vuelve a aparecer en la venta siguiente. Sólo en
     * memoria: tras reiniciar no se sabe y lo adopta el carrito al restaurar.
     */
    var ventaDuena: String? = null
        private set

    fun anotarVentaDuena(venta: String) { ventaDuena = venta }

    /**
     * Regla 8 (Codex r7 #3): la venta [venta] va a pedir un vale sin apartado en memoria. Si la última que pidió fue OTRA, su
     * creación pudo quedar en el servidor sin respuesta: se pide con llave nueva para no recibir aquel apartado (que el
     * servidor suelta solo al vencer: nunca se armó). Desde aquí el apartado es de [venta].
     */
    fun prepararCreacionPara(venta: String) {
        if (_checkout.value == null && ventaDuena != null && ventaDuena != venta) {
            createKey = UUID.randomUUID().toString()
            materializeKey = UUID.randomUUID().toString()
            secureStorage.areaTicketCreateKey = createKey
            secureStorage.areaTicketMaterializeKey = materializeKey
        }
        if (ventaDuena == null || _checkout.value == null) ventaDuena = venta
    }

    /**
     * 🔴 DINERO. El cobro (cada vez que se abre la pantalla de cobro) que pidió armar la orden de esta sesión. Se anota ANTES
     * de pedirlo: si la respuesta se pierde, el servidor ya pudo armarla. Una orden armada sin abono sólo sirve dentro de ESE
     * cobro (reintentar tras una tarjeta rechazada); en otro, el carrito pudo cambiar de cualquier forma (un producto, un
     * importe libre, la nota) y se suelta. Sólo en memoria a propósito: tras reiniciar no se sabe y también se suelta
     * (regla 4, rediseño tras Codex r3 #1, 20-oct: comparar la cuenta dejaba fuera los importes libres).
     */
    var cobroQueLaArmo: String? = null
        private set

    fun anotarCobroQueLaArmo(cobroId: String) { cobroQueLaArmo = cobroId }

    /**
     * Adopta la sesión que devolvió una restauración sólo si nadie puso otra mientras tanto y sigue siendo la persistida
     * (Codex r3 #4): la restauración tardía de A no pisa la sesión B.
     */
    fun adoptarRestaurada(currentVenueId: String, checkout: AreaTicketCheckout): Boolean {
        val actual = _checkout.value
        if (actual != null) return actual.id == checkout.id && updateIf(checkout.id, currentVenueId, checkout)
        if (persistedCheckoutId(currentVenueId) != checkout.id) return false
        update(currentVenueId, checkout)
        return true
    }

    fun createIdempotencyKey(): String = createKey
    fun materializeIdempotencyKey(): String = materializeKey
    fun persistedCheckoutId(currentVenueId: String): String? =
        secureStorage.areaTicketCheckoutId.takeIf {
            secureStorage.areaTicketCheckoutVenueId == currentVenueId
        }

    /** Olvida la sesión sólo si sigue siendo [checkoutId]: la respuesta tardía de la sesión A nunca borra la B (regla 3). */
    fun clearIf(checkoutId: String) {
        if (_checkout.value?.id == checkoutId) clear()
    }

    /**
     * Pone al día la sesión sólo si sigue siendo la misma; `false` si ya es otra (regla 3) o si la respuesta es más vieja que
     * la copia (regla 7, Codex r6 #6): una versión menor, o una copia «sin abono» sobre una que ya lo tiene. El SERVIDOR nunca
     * quita un abono (devolverlo deja el pago COMPLETED), así que sólo un estado final la reemplaza.
     */
    fun updateIf(checkoutId: String, currentVenueId: String, checkout: AreaTicketCheckout): Boolean {
        val actual = _checkout.value
        if (actual?.id != checkoutId) return false
        if (checkout.version < actual.version) return false
        if (actual.tieneAbono() && !checkout.tieneAbono() && checkout.status !in AREA_TICKET_TERMINAL_STATUSES) return false
        // Un abono manual del panel no sube la versión (Codex r7 #7): con la misma versión, lo pagado sólo crece.
        if (checkout.version == actual.version && checkout.status !in AREA_TICKET_TERMINAL_STATUSES &&
            (checkout.order?.paidAmount?.moneyToCents() ?: 0) < (actual.order?.paidAmount?.moneyToCents() ?: 0)
        ) return false
        update(currentVenueId, checkout)
        return true
    }

    fun clear() {
        cobroQueLaArmo = null
        ventaDuena = null
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
        // También la que ya está en memoria: una sesión puede vencer con la app
        // abierta y el carrito a la vista. Devolverla sin mirar la caducidad era
        // el mismo error, sólo que sin reinicio de por medio.
        session.current()?.let { current ->
            if (isAreaTicketCheckoutUsable(current.status, current.expiresAt)) return current
            return descartarVencida(current)
        }
        val checkoutId = session.persistedCheckoutId(venueId) ?: return null
        return runCatching {
            request { api.getAreaTicketCheckout(venueId, checkoutId) }.checkout
        }.getOrElse {
            return null
        }.also { checkout ->
            // Antes esto sólo miraba `status`, y la caducidad del venue NO se refleja
            // ahí: una sesión de la madrugada seguía en OPEN y volvía como carrito
            // cobrable. El cajero se enteraba al pulsar Cobrar —con el cliente
            // enfrente— porque el server la rechazaba con CHECKOUT_SESSION_STALE.
            if (!session.adoptarRestaurada(venueId, checkout)) return session.current()
        }.let { checkout ->
            if (isAreaTicketCheckoutUsable(checkout.status, checkout.expiresAt)) checkout else descartarVencida(checkout)
        }
    }

    /**
     * 🔴 DINERO (regla 2 de la pasada, Codex r2 #2, 20-oct). Una sesión se olvida sólo si el servidor dice que murió. Una
     * vencida por reloj SIN orden la libera el servidor solo; pero CON orden armada el servidor nunca la libera por fecha
     * (`releaseExpiredClaims` sólo mira OPEN): olvidarla dejaba los vales atorados. Se suelta, y sin red se conserva para
     * reintentar. Con abono el servidor no la suelta (409) y también se conserva.
     */
    private suspend fun descartarVencida(checkout: AreaTicketCheckout): AreaTicketCheckout? {
        // Sin orden conocida PERO pedida en este proceso (Codex r3 #3): la respuesta pudo perderse con la orden ya armada en el
        // servidor, que no la libera por fecha. Se suelta igual que una armada.
        val pudoArmarse = checkout.order != null || session.cobroQueLaArmo != null
        if (checkout.status in AREA_TICKET_TERMINAL_STATUSES || !pudoArmarse) {
            session.clearIf(checkout.id)
            return null
        }
        runCatching { cancel(soloSi = checkout.id) }
            .onFailure { Log.w(TAG, "No se pudo soltar el apartado vencido ${checkout.id}: ${it.message}") }
        return null
    }

    suspend fun resolveCheckoutScan(code: String): AreaTicketScanData =
        request { api.resolveAreaTicketScan(venueId(), ScanRequest(code.trim(), "CHECKOUT")) }

    suspend fun addTicket(code: String): AreaTicketCheckout {
        val venueId = venueId()
        session.ensureVenue(venueId)
        val checkout = session.current() ?: run {
            val llave = session.createIdempotencyKey()
            request { api.createAreaTicketCheckout(venueId, IdempotentRequest(llave)) }.checkout.also { creada ->
                // Regla 3 (Codex r4 #5): una creación que llega tarde (su sesión ya se soltó: la llave cambió, o ya hay otra)
                // no reemplaza a la vigente.
                if (session.createIdempotencyKey() != llave || session.current()?.let { it.id != creada.id } == true) {
                    throw sesionReemplazada()
                }
                session.update(venueId, creada)
            }
        }

        if (checkout.status != "OPEN") {
            val mensaje = if (checkout.tieneAbono()) {
                "Hay una venta de vales con un abono: cobra su saldo pendiente antes de escanear otro vale."
            } else {
                "La sesión de vales ya no admite cambios."
            }
            throw AreaTicketException("CHECKOUT_SESSION_FROZEN", mensaje, false)
        }
        val updated = request {
            api.addAreaTicketToCheckout(
                venueId,
                checkout.id,
                AddTicketRequest(code.trim(), UUID.randomUUID().toString()),
            )
        }.checkout
        if (!session.updateIf(checkout.id, venueId, updated)) throw sesionReemplazada()
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
        if (!session.updateIf(checkout.id, venueId, updated)) throw sesionReemplazada()
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
        // Terminada (pagada, cancelada o vencida): se olvida y el carrito saca sus vales; proyectarla los volvía a meter.
        if (updated.status in AREA_TICKET_TERMINAL_STATUSES) {
            session.clearIf(checkout.id)
            return null
        }
        return updated.takeIf { session.updateIf(checkout.id, venueId, it) }
    }

    suspend fun materialize(
        normalItems: List<NormalCheckoutItem>,
        customerName: String?,
        note: String?,
        cobroId: String,
    ): AreaTicketCheckout {
        val venueId = venueId()
        val checkout = session.current()
            ?: throw AreaTicketException("CHECKOUT_NOT_FOUND", "No hay una sesión de vales abierta.", false)
        // 🔴 DINERO (regla 4, Codex r2 #1 y r3 #1, 20-oct). Una orden ya armada sólo se reusa dentro del cobro que la armó. Si
        // el cajero salió sin red (no se pudo soltar) y luego cambió el carrito, reusarla cobraba la orden vieja y lo nuevo se
        // quedaba fuera. Otro cobro, o desconocido (tras reiniciar) ⇒ se suelta y se vuelve a escanear. Con abono no: la venta
        // sigue por su saldo (quien llama exige que el carrito continúe esa orden). Con un pago en confirmación tampoco.
        val armadaOPedida = checkout.order != null || session.cobroQueLaArmo != null
        if (armadaOPedida && session.cobroQueLaArmo != cobroId && !checkout.tieneAbono() &&
            checkout.status in setOf("OPEN", "MATERIALIZED")
        ) {
            cancel(soloSi = checkout.id)
            throw AreaTicketException(
                "CHECKOUT_CART_CHANGED",
                "La cuenta cambió después de preparar el cobro de los vales. Vuelve a escanear el vale; no se cobró nada.",
                false,
            )
        }
        if (checkout.order != null) return checkout

        session.anotarCobroQueLaArmo(cobroId)
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
        if (!session.updateIf(checkout.id, venueId, updated)) throw sesionReemplazada()
        return updated
    }

    suspend fun refresh(): AreaTicketCheckout? {
        val venueId = venueId()
        val checkout = session.current() ?: return null
        val updated = request { api.getAreaTicketCheckout(venueId, checkout.id) }.checkout
        if (updated.status in AREA_TICKET_TERMINAL_STATUSES) {
            session.clearIf(checkout.id)
            return updated
        }
        return updated.takeIf { session.updateIf(checkout.id, venueId, it) }
    }

    /** [soloSi]: suelta sólo si el apartado vigente sigue siendo ése (regla 9: un cobro nunca suelta el apartado de otro). */
    suspend fun cancel(soloSi: String? = null): AreaTicketCheckout? {
        val venueId = venueId()
        val checkout = session.current() ?: return null
        if (soloSi != null && checkout.id != soloSi) return null
        val updated = request {
            api.cancelAreaTicketCheckout(
                venueId,
                checkout.id,
                IdempotentRequest(UUID.randomUUID().toString()),
            )
        }.checkout
        session.clearIf(checkout.id)
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
        idempotencyKey: String = UUID.randomUUID().toString(),
    ) {
        val auditReason = normalizeAreaTicketPrintReason(reprint, reason)
        request {
            api.recordAreaTicketPrintAttempt(
                venueId(),
                ticketId,
                PrintAttemptRequest(
                    idempotencyKey = idempotencyKey,
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

    suspend fun fulfill(ticketId: String, scannedReceipt: Boolean): AreaTicketFulfillmentResult =
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

    private companion object {
        const val TAG = "AreaTicketRepository"
    }
}

internal fun normalizeAreaTicketPrintReason(reprint: Boolean, reason: String?): String? =
    reason?.trim()?.takeIf { it.isNotEmpty() }
        ?: if (reprint) "Reimpresión solicitada por el operador desde el POS." else null
