package com.avoqado.pos.payment.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.payment.domain.CancelacionDeCobro
import com.avoqado.pos.payment.domain.CardChargeDecision
import com.avoqado.pos.payment.domain.CardChargeOutcome
import com.avoqado.pos.payment.domain.ChargeStatusProbe
import com.avoqado.pos.payment.domain.ChargeWaitEnding
import com.avoqado.pos.payment.domain.CreationRejection
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

        /**
         * Cuántas veces se repite el POST cuando el servidor contesta que la ADMISIÓN está ocupada
         * (503 `TERMINAL_PAYMENT_ADMISSION_RETRY`). Acotado: si no cede, se cae al camino de
         * siempre —consultar el estado— y la llave durable se conserva.
         */
        const val MAX_REINTENTOS_DE_ADMISION = 2
        const val ESPERA_DE_ADMISION_MS = 400L
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    /** El `code` del cuerpo de un rechazo, si el cuerpo es nuestro y lo trae. */
    private fun codigoDeRechazo(body: String): String? =
        runCatching { json.decodeFromString(TerminalPaymentRejectionDto.serializer(), body).code }.getOrNull()

    // Track current request for cancellation
    private var currentRequestId: String? = null
    private var currentTerminalId: String? = null
    private var currentVenueId: String? = null

    /**
     * El intento que TODAVÍA puede estar moviendo dinero: vive desde antes del POST hasta que el
     * intento entero termina (re-consulta incluida).
     *
     * 🔴 No es lo mismo que [currentRequestId], que se limpia en cuanto el POST vuelve: entre esa
     * limpieza y el final de la reconciliación pasan hasta 35 s en los que la pantalla sigue en
     * «Procesando pago…» y el cajero puede tocar Cancelar. Con `currentRequestId` esa cancelación
     * se quedaba sin solicitud a la que apuntar.
     */
    private var intentoVivo: IntentoDeCobroEnVuelo? = null
    private val attemptLock = Any()
    // Retained only while the bounded POST or recovery cycle is alive.
    private val activePosts = mutableSetOf<String>()
    private val recoveredPosts = mutableMapOf<String, TerminalPaymentResult>()
    private class RecoveryFlight {
        val result = kotlinx.coroutines.CompletableDeferred<TerminalPaymentResult>()
        @Volatile var hasRecoveryConsumer = false
    }
    private val recoveryFlights = mutableMapOf<String, RecoveryFlight>()

    private fun recoveredPost(requestId: String): TerminalPaymentResult? = synchronized(attemptLock) {
        recoveredPosts[requestId]?.let { if (it is TerminalPaymentResult.Success) it.copy(alreadyRecovered = true) else it }
    }

    private fun clearCurrent(requestId: String) = synchronized(attemptLock) {
        if (currentRequestId == requestId) {
            currentRequestId = null
            currentTerminalId = null
            currentVenueId = null
        }
    }

    /**
     * El cobro que sigue vivo en este aparato (POST o re-consulta), si lo hay. Es a quien apunta
     * «Cancelar» desde «Procesando pago…».
     */
    fun intentoEnVuelo(): IntentoDeCobroEnVuelo? = synchronized(attemptLock) { intentoVivo }

    private fun terminarIntento(requestId: String) = synchronized(attemptLock) {
        if (intentoVivo?.requestId == requestId) intentoVivo = null
    }

    /**
     * Contexto guardado del cobro (la llave durable), si corresponde a ESA solicitud.
     *
     * Es lo que permite cancelar desde «Cobro sin confirmar» o desde «Error», cuando el POST ya
     * terminó y el servicio no conserva nada en memoria: terminal, venue y orden salen del disco.
     */
    fun contextoDe(requestId: String): ContextoDeCobro? {
        val crudo = secureStorage.pendingCardChargeContext ?: return null
        val datos = runCatching { JSONObject(crudo) }.getOrNull() ?: return null
        if (datos.optString("requestId") != requestId) return null
        fun texto(llave: String): String? = datos.optString(llave).takeIf { it.isNotBlank() }
        return ContextoDeCobro(
            requestId = requestId,
            venueId = texto("venueId"),
            terminalId = texto("terminalId"),
            orderId = texto("orderId"),
            amountCents = if (datos.has("amountCents")) datos.optInt("amountCents") else null,
            tipCents = if (datos.has("tipCents")) datos.optInt("tipCents") else null,
        )
    }

    /**
     * Marca, SÍNCRONO, que el cajero pidió cancelar ESTE cobro.
     *
     * 🔴 Se escribe antes de cualquier red a propósito: mientras el POST sigue en vuelo, un 409/504
     * posterior NO se puede leer como «no se cobró» — es exactamente el doble cobro del 2026-08-10.
     */
    fun marcarCancelacionPedida(requestId: String) {
        cancelRequestedFor = requestId
    }

    /** Suelta la llave durable SÓLO si es la de esta solicitud. */
    fun soltarLlaveSiEs(requestId: String) = clearMatching(requestId)

    /**
     * Deja este cobro cargado en la llave durable, para que la próxima venta lo muestre.
     *
     * @return `false` si la llave la tiene OTRO cobro vivo — ése es más nuevo y todavía puede tener
     *   dinero encima, así que jamás se pisa (misma regla que [CardChargeDecision.unresolvedKeyAfterStaleResult]).
     */
    fun armarLlaveSiLibre(requestId: String): Boolean = synchronized(attemptLock) {
        val armada = unresolvedRequestId
        if (armada != null && armada != requestId) return false
        if (armada == null) unresolvedRequestId = requestId
        true
    }

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
     * Se limpia SÓLO cuando el desenlace consta para la misma identidad.
     */
    var unresolvedRequestId: String?
        get() = secureStorage.pendingCardChargeRequestId
        private set(value) { secureStorage.pendingCardChargeRequestId = value }

    /** Compatibilidad con callers antiguos: una advertencia no resuelve el dinero. */
    fun forgetUnresolvedCharge() { /* Financial uncertainty cannot be dismissed. */ }

    private fun clearMatching(requestId: String) {
        if (unresolvedRequestId == requestId) unresolvedRequestId = null
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
        /**
         * Cliente CONGELADO de la venta (nunca el vivo del carrito). En el cobro rápido
         * —importe suelto, sin productos— no nace orden, así que éste es el único
         * camino por el que el cliente puede llegar a la venta `FAST-*`.
         */
        customerId: String? = null,
    ): TerminalPaymentResult {
        unresolvedRequestId?.let { return TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, it, inherited = true) }
        val venueId = secureStorage.venueId ?: return TerminalPaymentResult.Error("No venue selected")
        val token = secureStorage.accessToken ?: return TerminalPaymentResult.Error("Not authenticated")

        val requestId = UUID.randomUUID().toString()
        // Desde este instante la tarjeta PUEDE cobrarse. Hasta que el desenlace conste,
        // este id es lo único que permite preguntar "¿cómo quedó?" en vez de cobrar de nuevo.
        val context = JSONObject().put("requestId", requestId).put("venueId", venueId)
            .put("terminalId", terminalId).put("orderId", orderId)
            .put("amountCents", amountCents).put("tipCents", tipCents)
        if (!secureStorage.persistPendingCardCharge(requestId, context.toString())) {
            return unresolvedRequestId?.let { TerminalPaymentResult.Undetermined(CardChargeDecision.UNDETERMINED_MESSAGE, it, inherited = true) }
                ?: TerminalPaymentResult.Error("No se pudo guardar el intento. No se envió el cobro.")
        }
        synchronized(attemptLock) {
            activePosts.add(requestId)
            currentRequestId = requestId
            currentVenueId = venueId
            currentTerminalId = terminalId
            intentoVivo = IntentoDeCobroEnVuelo(requestId, terminalId, venueId)
        }
        // Cobro nuevo, carrera nueva: el cancel del anterior no gobierna a éste.
        cancelRequestedFor = null
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
                    // Un id en blanco NO es un cliente: se descarta aquí para que el cuerpo
                    // quede idéntico al de una venta anónima.
                    customerId = customerId?.trim()?.takeIf { it.isNotEmpty() },
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
            suspend fun enviar(): Pair<Int, String> = withContext(Dispatchers.IO) {
                val call = client.newCall(request)
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

            var (responseCode, body) = enviar()
            // 🔴 El servidor pidió reintentar la ADMISIÓN (503): no pudo decidir ahora y NO creó
            // la solicitud. Se reintenta con el MISMO `requestId` —la unicidad del id es el candado
            // del servidor, así que una copia nunca crea un segundo cobro— y la llave durable se
            // conserva. Estrenar identidad aquí sería pedir un cobro nuevo sobre el mismo carrito.
            var reintentosDeAdmision = 0
            while (
                responseCode == 503 &&
                codigoDeRechazo(body) == CancelacionDeCobro.ADMISSION_RETRY &&
                reintentosDeAdmision < MAX_REINTENTOS_DE_ADMISION &&
                cancelRequestedFor != requestId
            ) {
                reintentosDeAdmision++
                Log.w("💳", "⏳ Admisión ocupada (503): reintento $reintentosDeAdmision con el MISMO requestId $requestId")
                delay(ESPERA_DE_ADMISION_MS * reintentosDeAdmision)
                val reintento = enviar()
                responseCode = reintento.first
                body = reintento.second
            }

            clearCurrent(requestId)
            recoveredPost(requestId)?.let { return it }

            when (responseCode) {
                in 200..299 -> {
                    val response = json.decodeFromString(TerminalPaymentResponse.serializer(), body)
                    if (response.status != "success" || response.paymentId.isNullOrBlank() || (response.requestId != null && response.requestId != requestId)) {
                        return resolveOutcome(requestId, fromPost = true)
                    }
                    clearMatching(requestId)
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
                    ).also { result ->
                        synchronized(attemptLock) {
                            if (requestId in recoveryFlights) recoveredPosts[requestId] = result
                        }
                    }
                }
                else -> {
                    // 🔴 Un fallo de TRANSPORTE (5xx, 408) no es un fallo de COBRO: la terminal
                    // pudo haber cobrado y sólo se perdió el aviso. Fue exactamente el 503 de
                    // ngrok reiniciando el backend lo que produjo el doble cobro del 2026-08-10.
                    // Los 4xx sí son respuestas de negocio y se propagan tal cual.
                    val rejectionDto = try {
                        json.decodeFromString(TerminalPaymentRejectionDto.serializer(), body)
                    } catch (_: Exception) { null }
                    val errorMsg = rejectionDto?.errorMessage ?: rejectionDto?.message
                    val rejection = rejectionDto?.let {
                        CreationRejection(
                            code = it.code,
                            blockingRequestId = it.blockingRequest?.requestId,
                            amountCents = it.blockingRequest?.amountCents,
                            ageSeconds = it.blockingRequest?.ageSeconds,
                            senderDevice = it.blockingRequest?.senderDevice,
                        )
                    }
                    // 🔴 La UNA excepción del 409: el server se negó a CREAR este intento porque
                    // OTRA solicitud ocupa la terminal. Nada viajó a la terminal, así que no hay
                    // nada que preguntar ni llave que conservar — ver CardChargeDecision.
                    val busyByAnother = rejection?.takeIf {
                        responseCode == 409 && CardChargeDecision.refusedForAnotherRequest(it, requestId)
                    }
                    if (busyByAnother != null) {
                        Log.w("💳", "🔒 Terminal ocupada por OTRA solicitud (${busyByAnother.blockingRequestId}): este intento nunca se creó — se libera la llave")
                        clearMatching(requestId)
                        return TerminalPaymentResult.Error(
                            CardChargeDecision.busyMessage(busyByAnother, errorMsg),
                            requestId = requestId,
                            noSeCreo = true,
                        )
                    }
                    // 🔴 Misma familia que el 409 de arriba y la MISMA garantía: la CORRELACIÓN.
                    // El servidor rechazó la admisión nombrando a ESTA solicitud (deja lápida por
                    // `requestId`), así que consta que no se creó el cobro: se suelta la llave y se
                    // dice, con todas sus letras, que este cobro no se envió.
                    val rechazoQueNoSeCreo = rejectionDto
                        ?.takeIf { responseCode in 400..499 }
                        ?.let { CancelacionDeCobro.rechazoQueProbaNoSeCreo(it.code, it.details?.requestId, requestId) }
                    if (rechazoQueNoSeCreo != null) {
                        Log.w("💳", "🚫 Admisión rechazada (${rejectionDto?.code}) para $requestId: este cobro NO se envió")
                        clearMatching(requestId)
                        return TerminalPaymentResult.Error(rechazoQueNoSeCreo, requestId = requestId, noSeCreo = true)
                    }
                    if (CardChargeDecision.mustReconcile(
                            ChargeWaitEnding.Http(responseCode),
                            cancelRequested = cancelRequestedFor == requestId,
                        )
                    ) {
                        Log.e("💳", "⏳ Desenlace no consta ($responseCode): se consulta el estado durable")
                        resolveOutcome(requestId, fromPost = true)
                    } else {
                        Log.e("💳", "❌ Terminal payment failed: $responseCode - $body")
                        clearMatching(requestId)
                        TerminalPaymentResult.Error(errorMsg ?: "Error al procesar pago ($responseCode)", requestId = requestId)
                    }
                }
            }
        } catch (e: Exception) {
            clearCurrent(requestId)
            recoveredPost(requestId)?.let { return it }
            // Corte de red / timeout del cliente / plazo vencido = desenlace DESCONOCIDO.
            // Se le pregunta al server qué pasó de verdad antes de rendirse — es lo que evita
            // el falso "falló" (y el doble cobro que provocaría un reintento a ciegas).
            val ending = if (ceilingExceeded.get()) ChargeWaitEnding.CeilingExceeded else ChargeWaitEnding.NetworkError
            Log.e("💳", "⚠️ Espera terminada sin resultado ($ending): se consulta el estado durable: ${e.message}")
            if (CardChargeDecision.mustReconcile(ending, cancelRequested = cancelRequestedFor == requestId)) {
                resolveOutcome(requestId, fromPost = true)
            } else {
                clearMatching(requestId)
                TerminalPaymentResult.Error(e.message ?: "Error al procesar pago", requestId = requestId)
            }
        } finally {
            clearCurrent(requestId)
            terminarIntento(requestId)
            synchronized(attemptLock) {
                activePosts.remove(requestId)
                if (requestId !in recoveryFlights) recoveredPosts.remove(requestId)
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
    suspend fun getPaymentStatus(requestId: String): ChargeStatusProbe = consultarEstado(requestId, venueId = null).probe

    /**
     * La misma consulta, diciendo además si HUBO respuesta del servidor.
     *
     * 🔴 «No se pudo preguntar» tiene dos causas distintas y la pantalla no las puede confundir: un
     * 5xx o un 403 son una respuesta (el servidor está ahí), y sólo la ausencia de respuesta es
     * falta de red — que es lo único que autoriza a decirle al cajero «sin conexión en esta tablet».
     *
     * @param venueId el venue del COBRO. La cancelación durable sobrevive a un cambio de sucursal,
     *   así que no puede preguntar por el venue activo.
     * @param enSegundoPlano la consulta corre sola (el coordinador): un 403 no puede sacar el modal
     *   de permisos encima de la pantalla en la que esté el cajero.
     */
    suspend fun consultarEstado(
        requestId: String,
        venueId: String?,
        enSegundoPlano: Boolean = false,
    ): ConsultaDeEstado {
        val storedVenue = contextoDe(requestId)?.venueId
        val venue = venueId ?: storedVenue ?: secureStorage.venueId
            ?: return ConsultaDeEstado(ChargeStatusProbe.Unreachable, sinRespuesta = true)
        val token = secureStorage.accessToken
            ?: return ConsultaDeEstado(ChargeStatusProbe.Unreachable, sinRespuesta = true)

        return try {
            val request = Request.Builder()
                .url("$baseUrl/mobile/venues/$venue/terminal-payment/$requestId")
                .header("Authorization", "Bearer $token")
                .apply {
                    if (enSegundoPlano) header(com.avoqado.pos.core.data.network.ForbiddenInterceptor.BACKGROUND_HEADER, "1")
                }
                .get()
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = statusClient.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            val probe = when {
                responseCode in 200..299 -> {
                    val dto = json.decodeFromString(TerminalPaymentStatusDto.serializer(), body)
                    Log.d("💳", "Payment status $requestId → ${dto.status} (inProgress=${dto.inProgress}, outcome=${dto.outcome})")
                    dto.aProbe()
                }
                responseCode == 404 -> {
                    Log.d("💳", "Payment status $requestId → 404 NOT_FOUND (no acredita ausencia de cargo)")
                    ChargeStatusProbe.NotFound
                }
                else -> {
                    // 5xx / 401 / lo que sea: NO se sabe nada. Nunca asumir que no se cobró.
                    Log.d("💳", "Payment status $requestId → $responseCode (no se pudo determinar)")
                    ChargeStatusProbe.Unreachable
                }
            }
            ConsultaDeEstado(probe, sinRespuesta = false)
        } catch (e: Exception) {
            Log.e("💳", "Error fetching payment status: ${e.message}")
            ConsultaDeEstado(ChargeStatusProbe.Unreachable, sinRespuesta = true)
        }
    }

    /**
     * Averigua cómo terminó de verdad un cobro cuyo desenlace no consta, consultando el estado
     * durable hasta 3 veces. La decisión (cobró / no cobró / no se sabe) es de
     * [CardChargeDecision] — aquí sólo se hace la red y se traduce al resultado del flujo.
     *
     * Público a propósito: `retry()` lo usa para re-consultar ANTES de ofrecer cobrar otra vez.
     */
    suspend fun resolveOutcome(requestId: String): TerminalPaymentResult = resolveOutcome(requestId, fromPost = false)

    private suspend fun resolveOutcome(requestId: String, fromPost: Boolean): TerminalPaymentResult {
        val (flight, owner) = synchronized(attemptLock) {
            val selected = recoveryFlights[requestId]?.let { it to false } ?: RecoveryFlight().let {
                recoveryFlights[requestId] = it
                it to true
            }
            if (!fromPost) selected.first.hasRecoveryConsumer = true
            selected
        }
        fun forConsumer(result: TerminalPaymentResult): TerminalPaymentResult =
            if (fromPost && flight.hasRecoveryConsumer && result is TerminalPaymentResult.Success) result.copy(alreadyRecovered = true) else result
        if (!owner) return forConsumer(flight.result.await())
        try {
            val result = resolveOutcomeOnce(requestId)
            synchronized(attemptLock) {
                if (requestId in activePosts && result !is TerminalPaymentResult.Undetermined) recoveredPosts[requestId] = result
            }
            flight.result.complete(result)
            return forConsumer(result)
        } catch (error: Throwable) {
            flight.result.completeExceptionally(error)
            throw error
        } finally {
            synchronized(attemptLock) {
                if (recoveryFlights[requestId] === flight) recoveryFlights.remove(requestId)
                if (requestId !in activePosts) recoveredPosts.remove(requestId)
            }
        }
    }

    private suspend fun resolveOutcomeOnce(requestId: String): TerminalPaymentResult {
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
        return synchronized(attemptLock) {
            recoveredPosts[requestId] ?: (resolved ?: CardChargeDecision.exhausted()).toResult(requestId)
        }
    }

    /** El desenlace, traducido al resultado que consume el flujo de pago. */
    private fun CardChargeOutcome.toResult(requestId: String): TerminalPaymentResult = when (this) {
        is CardChargeOutcome.Charged -> {
            // Consta que se cobró: el desenlace ya no está pendiente.
            clearMatching(requestId)
            Log.d("💳", "✅ Cobro confirmado por estado durable (paymentId=$paymentId)")
            TerminalPaymentResult.Success(paymentId = paymentId, requestId = requestId).also {
                if (requestId in activePosts) recoveredPosts[requestId] = it
            }
        }
        is CardChargeOutcome.NotCharged -> {
            // Consta que NO se cobró: reintentar es seguro.
            clearMatching(requestId)
            Log.d("💳", "🚫 Consta que no se cobró: $message")
            TerminalPaymentResult.Error(message, requestId = requestId).also {
                if (requestId in activePosts) recoveredPosts[requestId] = it
            }
        }
        is CardChargeOutcome.Undetermined -> {
            // Sigue sin saberse: se conserva el requestId para poder volver a preguntar.
            if (unresolvedRequestId == null || unresolvedRequestId == requestId) unresolvedRequestId = requestId
            Log.w("💳", "❓ Desenlace indeterminado — el cajero debe revisar la terminal")
            TerminalPaymentResult.Undetermined(message, requestId)
        }
    }

    /**
     * POST /mobile/venues/{venueId}/terminal-payment/cancel — y se LEE la respuesta.
     *
     * 🔴 Antes esto era un `Thread` que disparaba el POST con el cliente de 310 s y tiraba lo que
     * contestara el servidor. Nadie podía saber si la cancelación quedó registrada, y el cajero se
     * iba de la pantalla creyendo que sí. Ahora va por el cliente CORTO (10 s, `FAIL_FAST`: una
     * llamada de este plazo no se queda esperando a que alguien teclee un PIN) y su respuesta
     * gobierna el paso siguiente de la cancelación durable.
     *
     * `cancelRequestedFor` se escribe ANTES de suspender: el cobro sigue en vuelo y, en cuanto el
     * servidor procese este cancel, le contestará 409/504 — leer eso como «no se cobró» es
     * exactamente el doble cobro medido con tarjeta real el 2026-08-10.
     */
    suspend fun pedirCancelacion(
        requestId: String,
        terminalId: String,
        venueId: String,
        reason: String? = null,
    ): RespuestaDeCancelacion {
        cancelRequestedFor = requestId
        val token = secureStorage.accessToken ?: return RespuestaDeCancelacion(http = null)

        return try {
            val cancelBody = json.encodeToString(
                CancelPaymentRequest.serializer(),
                CancelPaymentRequest(terminalId = terminalId, requestId = requestId, reason = reason),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/mobile/venues/$venueId/terminal-payment/cancel")
                .header("Authorization", "Bearer $token")
                .header(com.avoqado.pos.core.data.network.ForbiddenInterceptor.LOCAL_ERROR_HEADER, "1")
                .post(cancelBody)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                statusClient.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
            }
            val dto = runCatching { json.decodeFromString(CancelPaymentResponseDto.serializer(), body) }.getOrNull()
            Log.d("💳", "Cancel payment response: $code (intent=${dto?.cancelIntent}, emitido=${dto?.cancelEmitted})")
            RespuestaDeCancelacion(
                http = code,
                success = dto?.success,
                cancelIntent = dto?.cancelIntent,
                cancelEmitted = dto?.cancelEmitted,
                // El servidor nuevo devuelve el estado ya releído: una consulta menos.
                estado = dto?.payment?.aProbe(),
                mensaje = dto?.message,
            )
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("💳", "Cancel payment error: ${e.message}")
            RespuestaDeCancelacion(http = null)
        }
    }

    /** Cancela el cobro que sigue vivo en este aparato, si lo hay. */
    suspend fun cancelCurrentPayment(): RespuestaDeCancelacion? {
        val intento = intentoEnVuelo() ?: return null
        return pedirCancelacion(intento.requestId, intento.terminalId, intento.venueId)
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
                // 🔴 La terminal dibuja el QR con `receiptUrl` — la llave sola no le sirve, porque
                // ella no arma la URL. Sin estos dos campos el ticket impreso EN la terminal desde
                // esta app salía sin QR de facturación, aunque la propia terminal sepa dibujarlo.
                receipt.receiptUrl?.takeIf { it.isNotBlank() }?.let { put("receiptUrl", it) }
                put("autofacturaAvailable", receipt.autofacturaAvailable)
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
        val alreadyRecovered: Boolean = false,
    ) : TerminalPaymentResult()

    /**
     * Consta que NO se cobró (rechazo, cancelación, terminal desconectada): reintentar es seguro.
     *
     * @param requestId la solicitud que produjo este error, cuando llegó a existir. La cancelación
     *   durable la necesita para preguntar por ella en vez de borrar la orden a ciegas.
     * @param noSeCreo el servidor rechazó la ADMISIÓN nombrando a esta solicitud: consta que el
     *   cobro no se envió a la terminal, así que no hay nada que preguntarle.
     */
    data class Error(
        val message: String,
        val requestId: String? = null,
        val noSeCreo: Boolean = false,
    ) : TerminalPaymentResult()

    /**
     * 🔴 No se pudo determinar si la tarjeta se cobró. NI éxito NI fracaso — es el tercer
     * desenlace, el que faltaba. Nunca se pinta como pantalla de Error, y nunca habilita un
     * reintento a ciegas: `requestId` es la llave para volver a preguntar.
     */
    data class Undetermined(val message: String, val requestId: String, val inherited: Boolean = false) : TerminalPaymentResult()
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
    /**
     * 🔴 EL CLIENTE DE LA VENTA, congelado al abrir el cobro.
     *
     * Es ADITIVO: el `Json` de este archivo va con `explicitNulls = false`, así que una
     * venta anónima produce **el mismo cuerpo byte a byte** de siempre — la llave ni
     * aparece. Sin él, el cobro rápido con tarjeta nacía anónimo aunque el cajero sí
     * hubiera elegido cliente (el de efectivo sí lo mandaba desde su propio fix).
     */
    val customerId: String? = null,
)

@Serializable
private data class CancelPaymentRequest(
    val terminalId: String,
    val requestId: String? = null,
    /** Por qué se canceló. ADITIVO: el servidor lo guarda en la bitácora; un server viejo lo ignora. */
    val reason: String? = null,
)

/**
 * Respuesta del POST de cancelación. `requestId`, `cancelIntent`, `cancelEmitted` y `payment` son
 * del contrato nuevo (§C.2) y todavía no los manda producción: se leen si vienen y el cliente
 * funciona igual sin ellos.
 *
 * 🔴 `cancelEmitted` NO prueba recepción — sólo que el servidor lo emitió a un socket del registro.
 */
@Serializable
private data class CancelPaymentResponseDto(
    val success: Boolean? = null,
    val message: String? = null,
    val requestId: String? = null,
    val cancelIntent: String? = null,
    val cancelEmitted: Boolean? = null,
    val payment: TerminalPaymentStatusDto? = null,
)

/** El cobro que todavía puede estar moviendo dinero en este aparato. */
data class IntentoDeCobroEnVuelo(
    val requestId: String,
    val terminalId: String,
    val venueId: String,
)

/** El contexto con el que salió un cobro, releído de la llave durable. */
data class ContextoDeCobro(
    val requestId: String,
    val venueId: String?,
    val terminalId: String?,
    val orderId: String?,
    val amountCents: Int? = null,
    val tipCents: Int? = null,
)

/**
 * Lo que contestó el POST de cancelación.
 *
 * @param http `null` = no hubo respuesta (sin red). Es lo único que autoriza a decir «sin conexión».
 */
data class RespuestaDeCancelacion(
    val http: Int?,
    val success: Boolean? = null,
    val cancelIntent: String? = null,
    val cancelEmitted: Boolean? = null,
    val estado: ChargeStatusProbe.Known? = null,
    val mensaje: String? = null,
) {
    /**
     * Ni aceptada ni rechazada: no dice NADA del cobro y se vuelve a intentar.
     *
     * Un 401 entra aquí a propósito (token vencido: transitorio, se resuelve al refrescar la
     * sesión), igual que los 5xx y los plazos agotados. Un 4xx de negocio NO: ahí el servidor
     * contestó, y quien decide es el estado durable del cobro.
     */
    val esTransitoria: Boolean get() = http == null || http >= 500 || http == 408 || http == 429 || http == 401
}

/**
 * El estado del cobro y si HUBO respuesta del servidor.
 *
 * @param sinRespuesta `true` sólo cuando no llegó nada (sin red). Un 5xx o un 403 SON respuesta:
 *   confundirlos hacía que la pantalla dijera «sin conexión» con la red perfecta.
 */
data class ConsultaDeEstado(
    val probe: ChargeStatusProbe,
    val sinRespuesta: Boolean,
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

/**
 * Cuerpo con el que el server RECHAZA crear el intento (4xx del POST). Sólo lo que el cliente usa.
 * Espejo del 409 de `terminal-payment.mobile.controller.ts` (`code` + `blockingRequest` en la raíz).
 */
@Serializable
data class TerminalPaymentRejectionDto(
    val code: String? = null,
    val message: String? = null,
    val errorMessage: String? = null,
    val blockingRequest: BlockingRequestDto? = null,
    /**
     * Datos del rechazo. `details.requestId` es la CORRELACIÓN: sin ella, un rechazo de admisión
     * no prueba nada de ESTA solicitud (el servidor lo manda desde la pieza §C.3/H.5).
     */
    val details: RejectionDetailsDto? = null,
)

@Serializable
data class RejectionDetailsDto(
    val requestId: String? = null,
)

@Serializable
data class BlockingRequestDto(
    val requestId: String? = null,
    val amountCents: Int? = null,
    val senderDevice: String? = null,
    val ageSeconds: Int? = null,
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
    val cancelDisposition: String? = null,
    val status: String = "",
    val inProgress: Boolean = false,
    val paymentId: String? = null,
    /**
     * Desenlace CANÓNICO del servidor (§C.1). Opcionales: un servidor que no los manda deja `null`
     * y el cliente decide con lo de siempre. Se leen como texto libre, nunca como enum estricto —
     * un valor nuevo no puede tumbar la lectura del estado de un cobro.
     */
    val outcome: String? = null,
    val outcomeEvidence: String? = null,
    val evidenceClass: String? = null,
    val failureCode: String? = null,
    val reconciliationRequired: Boolean? = null,
    val orderId: String? = null,
    val terminalId: String? = null,
) {
    fun aProbe(): ChargeStatusProbe.Known = ChargeStatusProbe.Known(
        status = status,
        inProgress = inProgress,
        paymentId = paymentId,
        cancelDisposition = cancelDisposition,
        outcome = outcome,
        outcomeEvidence = outcomeEvidence,
        evidenceClass = evidenceClass,
        failureCode = failureCode,
        reconciliationRequired = reconciliationRequired,
        orderId = orderId,
        terminalId = terminalId,
    )
}

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
