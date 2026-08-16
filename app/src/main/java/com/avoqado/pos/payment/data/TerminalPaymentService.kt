package com.avoqado.pos.payment.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.payment.domain.CardChargeDecision
import com.avoqado.pos.payment.domain.CardChargeOutcome
import com.avoqado.pos.payment.domain.ChargeStatusProbe
import com.avoqado.pos.payment.domain.ChargeWaitEnding
import com.avoqado.pos.payment.domain.ProbeDecision
import com.avoqado.pos.printing.data.model.ReceiptData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TerminalPaymentService @Inject constructor(
    private val secureStorage: SecureStorage,
    baseClient: OkHttpClient,
) {
    // Terminal payments need extended timeout since the server waits through TPV retries.
    private val client = baseClient.newBuilder()
        .readTimeout(310, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    /**
     * Cliente APARTE para consultar el estado, con plazos cortos.
     *
     * 🔴 No reusar el de 310 s: ese plazo largo existe porque alguien tiene que llegar a
     * pasar la tarjeta — una CONSULTA no espera a nadie. Con el cliente largo, tres sondeos
     * contra un proxy que acepta la conexión y nunca contesta dan hasta ~15 min de
     * "Consultando…", que es exactamente el modo de falla que el tope de espera vino a matar.
     * `callTimeout` acota la llamada COMPLETA (conexión + cuerpo), no sólo el hueco entre bytes.
     */
    private val statusClient = baseClient.newBuilder()
        .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .apply {
            // 🔴 Misma regla que `OrderRepository.moneyClient`: NINGUNA llamada
            // corta de la ruta del dinero se queda esperando a que una persona
            // teclee un PIN. La consulta de recuperación pega a una ruta con
            // `payments:read`, así que un 403 overridable abriría el teclado
            // dentro de estos 10 s — reventando el plazo y dejando el cobro en
            // desenlace indeterminado. Posición 0 para que el
            // `ForbiddenInterceptor` copiado la vea ya marcada.
            interceptors().add(0, okhttp3.Interceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header(com.avoqado.pos.core.data.network.ForbiddenInterceptor.FAIL_FAST_HEADER, "1")
                        .build(),
                )
            })
        }
        .build()

    /** Seam de pruebas: apuntar a un MockWebServer. En producción es [ApiConstants.BASE_URL]. */
    @androidx.annotation.VisibleForTesting
    internal var baseUrl: String = ApiConstants.BASE_URL

    private companion object {
        /**
         * Tope de reloj de pared del ciclo de re-consulta. Con `statusClient` cada llamada ya
         * está acotada a 10 s; esto cierra el caso patológico (3 llamadas lentas + esperas)
         * para que "Consultando…" nunca se vuelva otro cuelgue.
         */
        const val RECONCILE_CEILING_MS = 35_000L
    }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    // Track current request for cancellation
    private var currentRequestId: String? = null
    private var currentTerminalId: String? = null

    /**
     * `requestId` del cobro con tarjeta que quedó SIN resolver. Es la llave para volver a
     * preguntarle al server cómo terminó, en vez de cobrar otra vez a ciegas.
     *
     * 🔴 **Vive en DISCO** (`SecureStorage`), no en memoria. La pantalla "Cobro sin confirmar"
     * no basta: el cajero que la ve se va a Transacciones a comprobar si el pago entró, y ese
     * solo cambio de pestaña —o la muerte del proceso— evaporaba toda la ceremonia de la
     * advertencia. Con la llave en disco, el siguiente "Cobrar" la encuentra y obliga a
     * resolver el cobro viejo antes de ofrecer uno nuevo.
     *
     * Se limpia SÓLO cuando el desenlace consta (cobró / no cobró) o cuando el cajero asume
     * el riesgo explícitamente.
     */
    var unresolvedRequestId: String?
        get() = secureStorage.pendingCardChargeRequestId
        private set(value) { secureStorage.pendingCardChargeRequestId = value }

    /** El cajero vio la advertencia y decidió cobrar de nuevo: la llave deja de gobernar. */
    fun forgetUnresolvedCharge() {
        unresolvedRequestId = null
    }

    /**
     * `requestId` para el que el cajero pidió cancelar. Lo lee el hilo del cobro, que sigue en
     * vuelo, para no concluir nada por su cuenta — ver [CardChargeDecision.mustReconcile].
     *
     * 🔴 `@Volatile` y escrito ANTES de disparar el cancel: el cancel es fire-and-forget en otro
     * hilo, así que si se marcara desde ahí llegaría tarde justo en la carrera que importa.
     * Se guarda el id, no un booleano, porque un cancel viejo no debe contaminar el cobro
     * siguiente — la bandera sólo aplica al cobro que se canceló.
     */
    @Volatile
    private var cancelRequestedFor: String? = null

    /**
     * Vuelve a poner (o suelta, con `null`) la llave durable tras un desenlace que llegó TARDE.
     *
     * Existe porque el éxito limpia la llave al llegar, y si ese éxito era de un cobro que el
     * cajero YA canceló, la venta se quedaba sin nadie que supiera del cargo. Quién decide qué
     * llave queda es [CardChargeDecision.unresolvedKeyAfterStaleResult]; aquí sólo se escribe.
     */
    fun rearmUnresolvedCharge(requestId: String?) {
        unresolvedRequestId = requestId
    }

    /**
     * GET /mobile/venues/{venueId}/terminals/online
     * Returns terminals currently connected via Socket.IO.
     *
     * @param background la consulta corre SOLA (la sonda que precalienta la
     * pantalla de cobro), nadie la pidió. Esa ruta exige `tpv:read` —el permiso
     * de ADMINISTRAR terminales— que un CASHIER no tiene aunque su trabajo sea
     * cobrar: medido el 2026-08-16, el modal global "no tienes permiso" saltaba
     * encima de la pantalla de PROPINA a media venta. Marcada así, el 403 cae en
     * el mismo camino de siempre ([TerminalListResult.Error] → fail-open) sin
     * interrumpir a nadie. En `false` —el usuario eligió "Cobrar con terminal"—
     * el "no" SÍ tiene que verse: es justo lo que pidió.
     */
    suspend fun fetchOnlineTerminals(background: Boolean = false): TerminalListResult {
        val venueId = secureStorage.venueId ?: return TerminalListResult.Error("No venue selected")
        val token = secureStorage.accessToken ?: return TerminalListResult.Error("Not authenticated")

        return try {
            val request = Request.Builder()
                .url("$baseUrl/mobile/venues/$venueId/terminals/online")
                .header("Authorization", "Bearer $token")
                .apply {
                    if (background) {
                        header(com.avoqado.pos.core.data.network.ForbiddenInterceptor.BACKGROUND_HEADER, "1")
                    }
                }
                .get()
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (responseCode in 200..299) {
                val response = json.decodeFromString(OnlineTerminalsResponse.serializer(), body)
                Log.d("💳", "Found ${response.terminals.size} online terminals")
                TerminalListResult.Success(response.terminals)
            } else {
                Log.e("💳", "Failed to fetch terminals: $responseCode - $body")
                TerminalListResult.Error("Error al buscar terminales ($responseCode)")
            }
        } catch (e: Exception) {
            Log.e("💳", "Error fetching terminals: ${e.message}")
            TerminalListResult.Error("Error de conexión")
        }
    }

    /**
     * POST /mobile/venues/{venueId}/terminal-payment
     * Sends payment to a specific terminal. Server long-polls until terminal succeeds, is cancelled, or times out.
     */
    suspend fun sendPaymentToTerminal(
        terminalId: String,
        amountCents: Int,
        tipCents: Int = 0,
        rating: Int? = null,
        orderId: String? = null,
        processedByStaffId: String? = null,
    ): TerminalPaymentResult {
        val venueId = secureStorage.venueId ?: return TerminalPaymentResult.Error("No venue selected")
        val token = secureStorage.accessToken ?: return TerminalPaymentResult.Error("Not authenticated")

        val requestId = UUID.randomUUID().toString()
        currentRequestId = requestId
        currentTerminalId = terminalId
        // Cobro nuevo, carrera nueva: el cancel del anterior no gobierna a éste.
        cancelRequestedFor = null
        // Desde este instante la tarjeta PUEDE cobrarse. Hasta que el desenlace conste,
        // este id es lo único que permite preguntar "¿cómo quedó?" en vez de cobrar de nuevo.
        unresolvedRequestId = requestId
        // El watchdog corre en OTRO hilo: un `var` local capturado no garantiza visibilidad.
        val ceilingExceeded = java.util.concurrent.atomic.AtomicBoolean(false)

        Log.d("💳", "Sending payment to terminal: $terminalId, amount: $amountCents, tip: $tipCents, requestId: $requestId")

        return try {
            val requestBody = json.encodeToString(
                TerminalPaymentRequest.serializer(),
                TerminalPaymentRequest(
                    terminalId = terminalId,
                    amountCents = amountCents,
                    tipCents = tipCents,
                    rating = rating,
                    skipReview = true, // Android already collected tip/rating
                    orderId = orderId,
                    processedByStaffId = processedByStaffId,
                    requestId = requestId,
                ),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/mobile/venues/$venueId/terminal-payment")
                .header("Authorization", "Bearer $token")
                .post(requestBody)
                .build()

            // Tope de reloj de pared sobre la espera. Sin esto, un aviso que NUNCA llega
            // (terminal apagada, sin batería, cancelada desde su propia pantalla) deja al
            // cajero en "Procesando pago…" para siempre, sin salida y con fila enfrente.
            val call = client.newCall(request)
            val (responseCode, body) = withContext(Dispatchers.IO) {
                val watchdog = launch {
                    delay(CardChargeDecision.WAIT_CEILING_MS)
                    ceilingExceeded.set(true)
                    Log.w("💳", "⏱️ Plazo máximo de espera vencido — se corta la espera y se consulta el estado")
                    call.cancel() // cierra el socket → execute() sale de inmediato
                }
                try {
                    val response = call.execute()
                    response.code to (response.body?.string() ?: "")
                } finally {
                    watchdog.cancel()
                }
            }

            currentRequestId = null
            currentTerminalId = null

            when (responseCode) {
                in 200..299 -> {
                    unresolvedRequestId = null
                    val response = json.decodeFromString(TerminalPaymentResponse.serializer(), body)
                    Log.d("💳", "✅ Terminal payment success: ${response.status}")
                    TerminalPaymentResult.Success(
                        transactionId = response.transactionId,
                        cardLastFour = response.cardDetails?.lastFour,
                        cardBrand = response.cardDetails?.brand,
                        paymentId = response.paymentId ?: response.transactionId,
                        receiptAccessKey = response.receipt?.receiptAccessKey,
                        // El backend ya manda la URL del recibo ARMADA y apuntando al
                        // dashboard (la página con calificación + autofactura). Antes se
                        // descartaba aquí y más adelante se reconstruía desde la base del
                        // API → todo ticket salía con el QR viejo, sin facturación.
                        receiptUrl = response.receipt?.receiptUrl,
                        requestId = requestId,
                    )
                }
                404 -> {
                    val errorMsg = try {
                        val errorResp = json.decodeFromString(TerminalPaymentResponse.serializer(), body)
                        errorResp.errorMessage ?: errorResp.message
                    } catch (_: Exception) { null }
                    Log.e("💳", "❌ Terminal not connected (404): $body")
                    // El server contestó: nunca despachó nada. Consta que no se cobró.
                    unresolvedRequestId = null
                    TerminalPaymentResult.Error(errorMsg ?: "La terminal no está conectada")
                }
                422 -> {
                    val errorMsg = try {
                        val errorResp = json.decodeFromString(TerminalPaymentResponse.serializer(), body)
                        errorResp.errorMessage ?: errorResp.message
                    } catch (_: Exception) { null }
                    Log.e("💳", "❌ Terminal payment rejected (422): $body")
                    unresolvedRequestId = null
                    TerminalPaymentResult.Error(errorMsg ?: "La terminal no tiene conexión activa")
                }
                else -> {
                    // 🔴 Un fallo de TRANSPORTE (5xx, 408) no es un fallo de COBRO: la terminal
                    // pudo haber cobrado y sólo se perdió el aviso. Fue exactamente el 503 de
                    // ngrok reiniciando el backend lo que produjo el doble cobro del 2026-08-10.
                    // Los 4xx sí son respuestas de negocio y se propagan tal cual.
                    val errorMsg = try {
                        val errorResp = json.decodeFromString(TerminalPaymentResponse.serializer(), body)
                        errorResp.errorMessage ?: errorResp.message
                    } catch (_: Exception) { null }
                    if (CardChargeDecision.mustReconcile(
                            ChargeWaitEnding.Http(responseCode),
                            cancelRequested = cancelRequestedFor == requestId,
                        )
                    ) {
                        Log.e("💳", "⏳ Desenlace no consta ($responseCode): se consulta el estado durable")
                        resolveOutcome(requestId)
                    } else {
                        Log.e("💳", "❌ Terminal payment failed: $responseCode - $body")
                        unresolvedRequestId = null
                        TerminalPaymentResult.Error(errorMsg ?: "Error al procesar pago ($responseCode)")
                    }
                }
            }
        } catch (e: Exception) {
            currentRequestId = null
            currentTerminalId = null
            // Corte de red / timeout del cliente / plazo vencido = desenlace DESCONOCIDO.
            // Se le pregunta al server qué pasó de verdad antes de rendirse — es lo que evita
            // el falso "falló" (y el doble cobro que provocaría un reintento a ciegas).
            val ending = if (ceilingExceeded.get()) ChargeWaitEnding.CeilingExceeded else ChargeWaitEnding.NetworkError
            Log.e("💳", "⚠️ Espera terminada sin resultado ($ending): se consulta el estado durable: ${e.message}")
            if (CardChargeDecision.mustReconcile(ending, cancelRequested = cancelRequestedFor == requestId)) {
                resolveOutcome(requestId)
            } else {
                unresolvedRequestId = null
                TerminalPaymentResult.Error(e.message ?: "Error al procesar pago")
            }
        }
    }

    // MARK: - Status Recovery

    /**
     * GET /mobile/venues/{venueId}/terminal-payment/{requestId}
     * Estado durable de una solicitud de cobro — es lo que permite recuperar el desenlace REAL
     * después de una espera larga que murió, un plazo vencido o un corte de red.
     *
     * 🔴 Distingue `NotFound` (404: nunca se persistió → nadie pasó una tarjeta) de `Unreachable`
     * (no se pudo preguntar). Colapsarlos en un solo "null" era lo que hacía que un server
     * inalcanzable pareciera un "no se cobró" y habilitara un reintento a ciegas.
     */
    suspend fun getPaymentStatus(requestId: String): ChargeStatusProbe {
        val venueId = secureStorage.venueId ?: return ChargeStatusProbe.Unreachable
        val token = secureStorage.accessToken ?: return ChargeStatusProbe.Unreachable

        return try {
            val request = Request.Builder()
                .url("$baseUrl/mobile/venues/$venueId/terminal-payment/$requestId")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = statusClient.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            when {
                responseCode in 200..299 -> {
                    val dto = json.decodeFromString(TerminalPaymentStatusDto.serializer(), body)
                    Log.d("💳", "Payment status $requestId → ${dto.status} (inProgress=${dto.inProgress})")
                    ChargeStatusProbe.Known(
                        status = dto.status,
                        inProgress = dto.inProgress,
                        paymentId = dto.paymentId,
                    )
                }
                responseCode == 404 -> {
                    Log.d("💳", "Payment status $requestId → 404 NOT_FOUND (la solicitud nunca existió)")
                    ChargeStatusProbe.NotFound
                }
                else -> {
                    // 5xx / 401 / lo que sea: NO se sabe nada. Nunca asumir que no se cobró.
                    Log.d("💳", "Payment status $requestId → $responseCode (no se pudo determinar)")
                    ChargeStatusProbe.Unreachable
                }
            }
        } catch (e: Exception) {
            Log.e("💳", "Error fetching payment status: ${e.message}")
            ChargeStatusProbe.Unreachable
        }
    }

    /**
     * Averigua cómo terminó de verdad un cobro cuyo desenlace no consta, consultando el estado
     * durable hasta 3 veces. La decisión (cobró / no cobró / no se sabe) es de
     * [CardChargeDecision] — aquí sólo se hace la red y se traduce al resultado del flujo.
     *
     * Público a propósito: `retry()` lo usa para re-consultar ANTES de ofrecer cobrar otra vez.
     */
    suspend fun resolveOutcome(requestId: String): TerminalPaymentResult {
        // Tope de reloj de pared también AQUÍ: `statusClient` acota cada llamada, pero un
        // "Consultando…" que nunca termina es el mismo pecado que el "Procesando pago…" eterno.
        val resolved = kotlinx.coroutines.withTimeoutOrNull(RECONCILE_CEILING_MS) {
            val attempts = 3
            repeat(attempts) { attempt ->
                // Respiro entre consultas (500ms → 2s): darle un momento a la terminal para asentarse.
                if (attempt > 0) delay(if (attempt == 1) 500L else 2000L)

                val probe = getPaymentStatus(requestId)
                when (val decision = CardChargeDecision.decide(probe, isFinalAttempt = attempt == attempts - 1)) {
                    is ProbeDecision.Resolved -> return@withTimeoutOrNull decision.outcome
                    ProbeDecision.KeepPolling -> Unit // seguir preguntando
                }
            }
            // Se agotaron las consultas y seguía en curso: indeterminado, NUNCA "falló".
            CardChargeDecision.exhausted()
        }
        return (resolved ?: CardChargeDecision.exhausted()).toResult(requestId)
    }

    /** El desenlace, traducido al resultado que consume el flujo de pago. */
    private fun CardChargeOutcome.toResult(requestId: String): TerminalPaymentResult = when (this) {
        is CardChargeOutcome.Charged -> {
            // Consta que se cobró: el desenlace ya no está pendiente.
            unresolvedRequestId = null
            Log.d("💳", "✅ Cobro confirmado por estado durable (paymentId=$paymentId)")
            TerminalPaymentResult.Success(paymentId = paymentId, requestId = requestId)
        }
        is CardChargeOutcome.NotCharged -> {
            // Consta que NO se cobró: reintentar es seguro.
            unresolvedRequestId = null
            Log.d("💳", "🚫 Consta que no se cobró: $message")
            TerminalPaymentResult.Error(message)
        }
        is CardChargeOutcome.Undetermined -> {
            // Sigue sin saberse: se conserva el requestId para poder volver a preguntar.
            unresolvedRequestId = requestId
            Log.w("💳", "❓ Desenlace indeterminado — el cajero debe revisar la terminal")
            TerminalPaymentResult.Undetermined(message, requestId)
        }
    }

    /**
     * POST /mobile/venues/{venueId}/terminal-payment/cancel
     * Cancel a pending terminal payment.
     */
    fun cancelCurrentPayment() {
        val terminalId = currentTerminalId ?: return
        val requestId = currentRequestId
        val venueId = secureStorage.venueId ?: return
        val token = secureStorage.accessToken ?: return

        // 🔴 Marcar ANTES de disparar el cancel, no dentro del hilo: el cobro sigue en vuelo y
        // en cuanto el server procese este cancel le contestará 409. Si la marca llegara tarde,
        // ese 409 se leería como "no se cobró" — que es exactamente el doble cobro medido con
        // tarjeta real el 2026-08-10.
        cancelRequestedFor = requestId

        // Fire-and-forget cancel
        Thread {
            try {
                val cancelBody = json.encodeToString(
                    CancelPaymentRequest.serializer(),
                    CancelPaymentRequest(
                        terminalId = terminalId,
                        requestId = requestId,
                    ),
                ).toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$baseUrl/mobile/venues/$venueId/terminal-payment/cancel")
                    .header("Authorization", "Bearer $token")
                    .post(cancelBody)
                    .build()

                val response = client.newCall(request).execute()
                Log.d("💳", "Cancel payment response: ${response.code}")
            } catch (e: Exception) {
                Log.e("💳", "Cancel payment error: ${e.message}")
            }
        }.start()

        currentRequestId = null
        currentTerminalId = null
    }

    suspend fun printReceiptOnTerminal(
        terminalId: String,
        receipt: ReceiptData,
        paymentId: String? = null,
        receiptAccessKey: String? = null,
    ): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))
        val requestId = UUID.randomUUID().toString()

        return try {
            val receiptJson = JSONObject().apply {
                put("orderNumber", receipt.orderNumber)
                put("orderType", receipt.orderType)
                put("items", JSONArray().apply {
                    receipt.items.forEach { item ->
                        put(JSONObject().apply {
                            put("name", item.name)
                            put("quantity", item.quantity)
                            put("unitPrice", item.unitPrice)
                            put("totalPrice", item.totalPrice)
                            item.modifiers?.let { put("modifiers", JSONArray(it)) }
                            item.note?.let { put("note", it) }
                        })
                    }
                })
                put("subtotal", receipt.subtotal)
                put("taxAmount", receipt.taxAmount)
                receipt.tipAmount?.let { put("tipAmount", it) }
                receipt.discountAmount?.let { put("discountAmount", it) }
                put("total", receipt.total)
                receipt.paymentMethod?.let { put("paymentMethod", it) }
                receipt.cardLastFour?.let { put("cardLastFour", it) }
                put("venueName", receipt.venueName)
                receipt.venueAddress?.let { put("venueAddress", it) }
                receipt.venuePhone?.let { put("venuePhone", it) }
                receipt.cashierName?.let { put("cashierName", it) }
                receipt.customerName?.let { put("customerName", it) }
                receipt.transactionId?.let { put("transactionId", it) }
                receipt.cashTendered?.let { put("cashTendered", it) }
                receipt.changeAmount?.let { put("changeAmount", it) }
                paymentId?.takeIf { it.isNotBlank() }?.let { put("paymentId", it) }
                receiptAccessKey?.takeIf { it.isNotBlank() }?.let { put("receiptAccessKey", it) }
            }

            val bodyJson = JSONObject()
                .put("requestId", requestId)
                .put("receipt", receiptJson)
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/mobile/venues/$venueId/terminals/$terminalId/print-receipt")
                .header("Authorization", "Bearer $token")
                .post(bodyJson)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("🖨️", "✅ Receipt printed on TPV $terminalId")
                Result.success(Unit)
            } else {
                Log.e("🖨️", "❌ TPV receipt print failed ($code): $body")
                val message = runCatching {
                    JSONObject(body).optString("errorMessage")
                        .ifBlank { JSONObject(body).optString("message") }
                }.getOrNull()?.takeIf { it.isNotBlank() }
                Result.failure(Exception(message ?: "Error al imprimir en TPV ($code)"))
            }
        } catch (e: Exception) {
            Log.e("🖨️", "❌ TPV receipt print error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Abrir en una terminal la devolución de un cobro con tarjeta.
     *
     * 🔴 Devolver éxito significa "la terminal ABRIÓ la pantalla", NUNCA "el
     * dinero se devolvió": eso lo confirma una persona en el aparato —en Blumon
     * hay que volver a pasar la tarjeta— y la propia TPV registra el reembolso
     * cuando ocurre. Por eso quien llama a esto NO debe registrar además un
     * reembolso en Avoqado: sería contarlo dos veces.
     */
    suspend fun requestRefundOnTerminal(
        terminalId: String,
        paymentId: String,
        reason: String? = null,
    ): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))
        val requestId = UUID.randomUUID().toString()

        return try {
            val bodyJson = JSONObject()
                .put("requestId", requestId)
                .put("paymentId", paymentId)
                .apply { reason?.takeIf { it.isNotBlank() }?.let { put("reason", it) } }
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/mobile/venues/$venueId/terminals/$terminalId/refund-request")
                .header("Authorization", "Bearer $token")
                .post(bodyJson)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("↩️", "✅ Devolución abierta en la TPV $terminalId (pago $paymentId)")
                Result.success(Unit)
            } else {
                Log.e("↩️", "❌ No se pudo abrir la devolución en la TPV ($code): $body")
                val message = runCatching {
                    JSONObject(body).optString("message")
                        .ifBlank { JSONObject(body).optString("errorMessage") }
                }.getOrNull()?.takeIf { it.isNotBlank() }
                Result.failure(Exception(message ?: "No se pudo abrir la devolución en la terminal ($code)"))
            }
        } catch (e: Exception) {
            Log.e("↩️", "❌ Error abriendo devolución en TPV: ${e.message}")
            Result.failure(e)
        }
    }
}

// MARK: - Results

sealed class TerminalPaymentResult {
    data class Success(
        val transactionId: String? = null,
        val cardLastFour: String? = null,
        val cardBrand: String? = null,
        val paymentId: String? = null,
        val receiptAccessKey: String? = null,
        /** URL del recibo ya armada por el backend (dashboard). Preferirla sobre armarla a mano. */
        val receiptUrl: String? = null,
        /**
         * La solicitud que produjo este cobro. Viaja incluso en el ÉXITO porque un éxito que
         * llega TARDE —después de que el cajero canceló— tiene que poder re-armarse como
         * pendiente: sin esta llave, un cobro real desaparecía sin dejar rastro.
         */
        val requestId: String? = null,
    ) : TerminalPaymentResult()

    /** Consta que NO se cobró (rechazo, cancelación, terminal desconectada): reintentar es seguro. */
    data class Error(val message: String) : TerminalPaymentResult()

    /**
     * 🔴 No se pudo determinar si la tarjeta se cobró. NI éxito NI fracaso — es el tercer
     * desenlace, el que faltaba. Nunca se pinta como pantalla de Error, y nunca habilita un
     * reintento a ciegas: `requestId` es la llave para volver a preguntar.
     */
    data class Undetermined(val message: String, val requestId: String) : TerminalPaymentResult()
}

sealed class TerminalListResult {
    data class Success(val terminals: List<OnlineTerminal>) : TerminalListResult()
    data class Error(val message: String) : TerminalListResult()
}

// MARK: - Request/Response models

@Serializable
private data class TerminalPaymentRequest(
    val terminalId: String,
    val amountCents: Int,
    val tipCents: Int = 0,
    val rating: Int? = null,
    val skipReview: Boolean = true,
    val orderId: String? = null,
    val processedByStaffId: String? = null,
    val requestId: String,
)

@Serializable
private data class CancelPaymentRequest(
    val terminalId: String,
    val requestId: String? = null,
)

@Serializable
data class TerminalPaymentResponse(
    val success: Boolean = false,
    val status: String? = null,
    val requestId: String? = null,
    val transactionId: String? = null,
    val paymentId: String? = null,
    val cardDetails: CardDetails? = null,
    val errorMessage: String? = null,
    val message: String? = null,
    val receipt: ReceiptInfo? = null,
)

@Serializable
data class CardDetails(
    val lastFour: String? = null,
    val brand: String? = null,
    val entryMode: String? = null,
)

@Serializable
data class ReceiptInfo(
    val receiptUrl: String? = null,
    val receiptAccessKey: String? = null,
)

/**
 * Response of GET /mobile/venues/:venueId/terminal-payment/:requestId — the durable status of a
 * charge request, used to recover the real outcome after a dropped long-poll / timeout / network
 * error. `inProgress` is true for PENDING/SENT/CANCEL_REQUESTED; terminal (final) otherwise.
 */
@Serializable
data class TerminalPaymentStatusDto(
    val status: String = "",
    val inProgress: Boolean = false,
    val paymentId: String? = null,
)

@Serializable
data class OnlineTerminalsResponse(
    val success: Boolean = true,
    val terminals: List<OnlineTerminal> = emptyList(),
)

@Serializable
data class OnlineTerminal(
    val terminalId: String,
    val name: String = "",
    val isOnline: Boolean = true,
    val hasSocket: Boolean = false,
)
