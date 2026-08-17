package com.avoqado.pos.payment.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.OrderData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OrderRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    /**
     * Cliente SOLO para la ruta del dinero (crear orden, cobrar efectivo, fast).
     *
     * Rendirse rápido y ENCOLAR es el diseño (patrón Square: intenta, falla
     * rápido, guarda local): un timeout ya es un error encolable
     * (`isQueueableError`), así que esto no abre ningún camino nuevo — solo
     * llega en ~15 s al mismo código que antes tardaba 30–60 en alcanzarse,
     * que era lo que congelaba al cajero con fila.
     *
     * Es seguro reintentar después gracias a las llaves de idempotencia
     * (`externalId` en la orden, `idempotencyKey` en el pago): si el intento
     * lento SÍ aterrizó en el server, el replay deduplica en vez de duplicar.
     *
     * 🔴 NO usar para: terminal (310 s a propósito — espera a que pasen la
     * tarjeta), catálogo/reportes (en red lenta sí tardan y ahí no hay cola
     * que los salve), ni adjuntar cliente/recibos (no bloquean el cobro).
     */
    private val moneyClient: OkHttpClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .apply {
            // 🔴 El plazo de 15 s y el teclado del PIN son incompatibles: el
            // teclado se abre DENTRO de la llamada, así que un gerente que
            // tarda en llegar hacía que OkHttp cancelara con
            // `InterruptedIOException` — un fallo de RED a ojos de
            // `isQueueableError`. Resultado medido en el review: una venta que
            // el server rechazó por permisos quedaba encolada, pintada como
            // cobrada y con comanda impresa.
            //
            // Se marca en el CLIENTE y no en cada llamada para que ningún call
            // site nuevo pueda olvidarlo. Va en la posición 0 porque el
            // `ForbiddenInterceptor` viene copiado de `client` y tiene que ver
            // la petición YA marcada.
            interceptors().add(0, okhttp3.Interceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header(ForbiddenInterceptor.FAIL_FAST_HEADER, "1")
                        .build(),
                )
            })
        }
        .build()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // MARK: - Exception types

    class ServerException(val code: Int, message: String) : Exception(message)

    companion object {
        private val idExtractorJson = Json { ignoreUnknownKeys = true }
        private val errorParserJson = Json { ignoreUnknownKeys = true }

        fun isQueueableError(e: Throwable): Boolean {
            return e is java.net.UnknownHostException ||
                e is java.net.ConnectException ||
                e is java.net.SocketTimeoutException ||
                e is java.io.IOException
        }

        fun isQueueableHttpCode(code: Int): Boolean {
            return code >= 500
        }

        /**
         * ¿Este 4xx lo dijo NUESTRA API, o un intermediario del camino?
         *
         * 🔴 Un 4xx de la API es un rechazo de NEGOCIO y va a cuarentena. Pero un
         * 4xx de un portal cautivo, un proxy de plaza o un túnel caído NO dice
         * nada de la venta — y mandarlo a cuarentena marca un cobro legítimo como
         * fallido PERMANENTE, con un mensaje inventado ("la orden ya no existe").
         * El efectivo ya está en el cajón; la venta nunca llega al server y el
         * corte no cuadra al cierre.
         *
         * Reproducido en la T3 el 2026-08-09: con el túnel abajo, ngrok contestó
         * 404 y el cobro encolado murió con ese texto.
         *
         * `ConnectivityInterceptor.isServerDown` ya usaba este criterio para el
         * letrero de "sin conexión"; la cola de pagos no lo reusaba. Señales de
         * que la respuesta NO viene de la API:
         *  - trae `ngrok-error-code` (túnel de desarrollo caído)
         *  - el cuerpo es HTML (página de error de proxy/CDN/portal cautivo)
         *  - el cuerpo no es el JSON de error de la API
         * Más 408/429, que son transitorios por definición.
         */
        fun isTransient4xx(code: Int, contentType: String?, ngrokError: String?, body: String?): Boolean {
            if (code !in 400..499) return false
            if (code == 408 || code == 429) return true
            if (!ngrokError.isNullOrBlank()) return true
            val ct = contentType?.lowercase().orEmpty()
            if (ct.startsWith("text/html")) return true
            // Sin JSON parseable no hay forma de que sea un rechazo de la API.
            val trimmed = body?.trim().orEmpty()
            if (trimmed.isEmpty()) return true
            if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return true
            return false
        }

        /**
         * Extracts paymentId from known mobile payment response shapes.
         *
         * Supported examples:
         * - { "data": { "id": "cuid..." } }                 // /mobile/.../fast
         * - { "payment": { "paymentId": "cuid..." } }       // /mobile/.../orders/:id/pay
         * - { "data": { "paymentId": "cuid..." } }          // backward-compatible
         */
        fun extractPaymentIdFromResponse(responseBody: String): String? {
            return try {
                val root = idExtractorJson.parseToJsonElement(responseBody).jsonObject
                val id = root["data"]?.jsonObject?.get("paymentId")?.jsonPrimitive?.contentOrNull
                    ?: root["data"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: root["data"]?.jsonObject?.get("payment")?.jsonObject?.get("paymentId")?.jsonPrimitive?.contentOrNull
                    ?: root["payment"]?.jsonObject?.get("paymentId")?.jsonPrimitive?.contentOrNull
                    ?: root["payment"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: root["paymentId"]?.jsonPrimitive?.contentOrNull
                id?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * accessKey del recibo digital que ahora devuelve el pago en efectivo
         * (payCashOrder → payment.digitalReceipt.accessKey). Con él el cliente
         * arma la URL pública del recibo y dibuja el QR (pantalla del cliente +
         * recibo impreso), igual que en tarjeta. Busca en payment y data para
         * cubrir el pay de orden y el fast, sin romper si el server no lo manda.
         */
        fun extractReceiptAccessKeyFromResponse(responseBody: String): String? {
            return try {
                val root = idExtractorJson.parseToJsonElement(responseBody).jsonObject
                val key = root["payment"]?.jsonObject?.get("digitalReceipt")?.jsonObject?.get("accessKey")?.jsonPrimitive?.contentOrNull
                    ?: root["data"]?.jsonObject?.get("digitalReceipt")?.jsonObject?.get("accessKey")?.jsonPrimitive?.contentOrNull
                    ?: root["digitalReceipt"]?.jsonObject?.get("accessKey")?.jsonPrimitive?.contentOrNull
                key?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * URL del recibo digital YA ARMADA por el backend
         * (payment.digitalReceipt.receiptUrl). Apunta al dashboard, que es la página
         * con calificación **y autofactura (CFDI)**.
         *
         * Antes sólo se leía el accessKey de arriba y la URL se reconstruía a mano
         * concatenando la base del API — y de una base de API sólo sale una URL de API,
         * así que el QR de todos los tickets llevaba a la página vieja, sin facturación.
         * Preferir SIEMPRE ésta; el accessKey queda como respaldo (ver `resolveReceiptUrl`).
         *
         * Mismos tres lugares que el accessKey, por el pay de orden y el fast.
         */
        fun extractReceiptUrlFromResponse(responseBody: String): String? {
            return try {
                val root = idExtractorJson.parseToJsonElement(responseBody).jsonObject
                val url = root["payment"]?.jsonObject?.get("digitalReceipt")?.jsonObject?.get("receiptUrl")?.jsonPrimitive?.contentOrNull
                    ?: root["data"]?.jsonObject?.get("digitalReceipt")?.jsonObject?.get("receiptUrl")?.jsonPrimitive?.contentOrNull
                    ?: root["digitalReceipt"]?.jsonObject?.get("receiptUrl")?.jsonPrimitive?.contentOrNull
                url?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Mensaje del aviso de inventario post-cobro (payment.inventoryWarning.message,
         * Square-parity). La primera frase del server SIEMPRE confirma que el cobro
         * quedó registrado; el resto dice qué stock quedó en negativo o sin descontar.
         * Español, listo para el toast ámbar. Mismos tres lugares que el recibo;
         * tolerante a que la respuesta no lo traiga (server viejo o sin faltantes).
         */
        fun extractInventoryWarningMessageFromResponse(responseBody: String): String? {
            return try {
                val root = idExtractorJson.parseToJsonElement(responseBody).jsonObject
                val message = root["payment"]?.jsonObject?.get("inventoryWarning")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: root["data"]?.jsonObject?.get("inventoryWarning")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: root["inventoryWarning"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                message?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 🔴 DINERO. Saldo que le queda por cobrar a la ORDEN después de este
         * pago, tal como lo calculó el server (`payCashOrder`:
         * `remainingBalance = max(0, total - paidAmount)`, con la propina ya
         * neteada de los dos lados). Es la única fuente que conoce los pagos
         * que ESTE dispositivo no vio: otra caja, un link, un abono anterior.
         *
         * 🔴 **Llega en CENTAVOS, entero** — igual que `amount` y `tipAmount` de
         * esta misma respuesta, que también son centavos. **NO se multiplica por
         * 100.** (El primer corte del server mandó pesos; ese contrato nunca
         * llegó a producción, así que tampoco hay fallback al nombre viejo.)
         *
         * `null` = el server no lo mandó (versión vieja, o un camino que no
         * pasa por una orden): el cliente se queda con su aritmética local. `0`
         * NO es null: significa "ya no se debe nada" y es lo que cierra la venta.
         *
         * 🔴 Un valor NEGATIVO —sobrepago— se acota a 0, **no se descarta**:
         * descartarlo caería a la aritmética local del carrito, que es la fuente
         * MENOS autoritativa de las dos. "Ya no se debe nada" es la lectura
         * correcta de un saldo negativo.
         */
        fun extractRemainingBalanceCentsFromResponse(responseBody: String): Int? {
            return try {
                val root = idExtractorJson.parseToJsonElement(responseBody).jsonObject
                val centavos = root["payment"]?.jsonObject?.get("remainingBalanceCents")?.jsonPrimitive
                    ?: root["data"]?.jsonObject?.get("remainingBalanceCents")?.jsonPrimitive
                    ?: root["remainingBalanceCents"]?.jsonPrimitive
                // `doubleOrNull` sobre un string ("42") lo aceptaría; el saldo
                // tiene que llegar como NÚMERO o no se cree.
                if (centavos == null || centavos.isString) return null
                centavos.doubleOrNull
                    ?.takeIf { it.isFinite() }
                    ?.let { Math.round(it).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt() }
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 🔴 DINERO. Estado de pago de la ORDEN después de este cobro
         * (`PENDING` · `PARTIAL` · `PAID`), tal como quedó en el server.
         *
         * Va de la mano de [extractRemainingBalanceCentsFromResponse] y existe
         * por una trampa concreta: el server tolera hasta un centavo antes de
         * dejar una orden PARTIAL, así que puede marcarla **PAID con
         * `remainingBalance` = 0.01**. Sin este dato, el cliente arrastraría un
         * "Saldo pendiente" de un centavo que ya nadie puede cobrar —la orden
         * está cerrada— y el cajero se quedaría atrapado con él.
         *
         * null = el server no lo mandó (versión vieja).
         */
        fun extractOrderPaymentStatusFromResponse(responseBody: String): String? {
            return try {
                val root = idExtractorJson.parseToJsonElement(responseBody).jsonObject
                val status = root["payment"]?.jsonObject?.get("orderPaymentStatus")?.jsonPrimitive?.contentOrNull
                    ?: root["data"]?.jsonObject?.get("orderPaymentStatus")?.jsonPrimitive?.contentOrNull
                    ?: root["orderPaymentStatus"]?.jsonPrimitive?.contentOrNull
                status?.takeIf { it.isNotBlank() }?.uppercase()
            } catch (_: Exception) {
                null
            }
        }

        /**
         * ¿Esta venta tiene que crear una ORDEN (y no un cobro suelto "fast")?
         *
         * 🔴 Una línea de promoción NO trae `productId` —la promoción es la
         * línea— pero sí es mercancía. Sin contarla, una venta de puras
         * promociones se rechazaba con "No hay productos válidos" y, sin red,
         * se encolaba como cobro FAST: el importe llegaba pero el combo no,
         * dejando la venta sin artículos y el inventario sin descontar.
         */
        fun hasProductItems(request: CreateOrderRequest): Boolean {
            return request.items.any { !it.productId.isNullOrBlank() || it.promotionRef != null }
        }

        internal fun buildCreateOrderPayload(
            request: CreateOrderRequest,
            staffId: String,
            customerId: String? = null,
            source: String = "AVOQADO_ANDROID",
            orderType: String = "TAKEOUT",
            externalId: String? = null,
        ): String {
            return buildJsonObject {
                put(
                    "items",
                    buildJsonArray {
                        request.items.forEach { item ->
                            add(
                                buildJsonObject {
                                    val promotionRef = item.promotionRef
                                    val productId = item.productId?.takeIf { it.isNotBlank() }
                                    if (promotionRef != null) {
                                        // 🔴 Una promoción viaja SOLA. El server rechaza con 400
                                        // ("un item no puede ser producto y promoción a la vez")
                                        // cualquier item que traiga promotionRef Y productId, name
                                        // o unitPrice — y `unitPrice: 0` cuenta como precio. Ni la
                                        // cantidad se manda: el motor cobra UNA instancia por ref.
                                        put(
                                            "promotionRef",
                                            buildJsonObject {
                                                put("promotionId", promotionRef.promotionId)
                                                put("promotionInstanceId", promotionRef.promotionInstanceId)
                                                put(
                                                    "selections",
                                                    buildJsonArray {
                                                        promotionRef.selections.forEach { selection ->
                                                            add(
                                                                buildJsonObject {
                                                                    put("groupId", selection.groupId)
                                                                    put("optionId", selection.optionId)
                                                                },
                                                            )
                                                        }
                                                    },
                                                )
                                            },
                                        )
                                    } else if (productId != null) {
                                        put("productId", productId)
                                        // Venta por peso: quantity SIEMPRE 1 y el peso (kg) viaja en
                                        // weightQuantity; el server recalcula el total desde el
                                        // precio/kg. Se omite weightQuantity en líneas normales.
                                        val weightQuantity = item.weightQuantity
                                        if (weightQuantity != null) {
                                            put("quantity", 1)
                                            put("weightQuantity", weightQuantity)
                                        } else {
                                            put("quantity", item.quantity)
                                        }

                                        val modifierIds = item.modifiers
                                            .map { it.modifierId }
                                            .filter { it.isNotBlank() }
                                        if (modifierIds.isNotEmpty()) {
                                            put(
                                                "modifierIds",
                                                buildJsonArray {
                                                    modifierIds.forEach { add(JsonPrimitive(it)) }
                                                },
                                            )
                                        }
                                    } else {
                                        // Custom amount line item (e.g. "Otro importe")
                                        put("name", item.name)
                                        put("quantity", item.quantity)
                                        put("unitPrice", item.unitPrice)
                                    }

                                    if (promotionRef == null) {
                                        item.note
                                            ?.trim()
                                            ?.takeIf { it.isNotEmpty() }
                                            ?.let { put("notes", it) }
                                    }
                                },
                            )
                        }
                    },
                )
                put("staffId", staffId)
                put("orderType", orderType)
                put("source", source)
                externalId?.trim()?.takeIf { it.isNotEmpty() }?.let { put("externalId", it) }
                put("subtotal", request.subtotal)
                put("tip", request.tip)
                put("total", request.total)
                if (request.discount > 0) put("discount", request.discount)
                customerId?.takeIf { it.isNotBlank() }?.let { put("customerId", it) }
                request.splitType?.let { put("splitType", it) }
                request.note?.trim()?.takeIf { it.isNotEmpty() }?.let { put("note", it) }
                request.reservationId?.takeIf { it.isNotBlank() }?.let { put("reservationId", it) }
            }.toString()
        }

        private fun extractErrorMessage(responseBody: String): String? {
            return try {
                val root = errorParserJson.parseToJsonElement(responseBody).jsonObject
                root["message"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun createOrder(
        request: CreateOrderRequest,
        staffId: String,
        customerId: String? = null,
        orderType: String = "TAKEOUT",
        externalId: String = java.util.UUID.randomUUID().toString(),
    ): Result<CreateOrderResponse> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))
        if (staffId.isBlank()) return Result.failure(Exception("No staff"))

        if (!hasProductItems(request)) {
            return Result.failure(Exception("No hay productos válidos para crear la orden"))
        }

        Log.d("📦", "Creating order for venue: $venueId, total: ${request.total}")

        return try {
            val payload = buildCreateOrderPayload(
                request = request,
                staffId = staffId,
                customerId = customerId,
                orderType = orderType,
                externalId = externalId,
            )
            val body = payload.toRequestBody("application/json".toMediaType())

            val httpRequest = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/orders")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()

            val (responseCode, responseBody) = withContext(Dispatchers.IO) {
                val response = moneyClient.newCall(httpRequest).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (responseCode in 200..299) {
                val orderResponse = parseCreateOrderResponse(responseBody)
                Log.d("📦", "✅ Order created: ${orderResponse.data?.id}")
                Result.success(orderResponse)
            } else {
                Log.e("📦", "❌ Order creation failed: $responseCode - $responseBody")
                val message = extractErrorMessage(responseBody) ?: "Error al crear orden ($responseCode)"
                Result.failure(ServerException(responseCode, message))
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Order creation error: ${e.message}")
            Result.failure(e)
        }
    }

    private fun parseCreateOrderResponse(responseBody: String): CreateOrderResponse {
        // Backend can return either {"data": {...}} or {"order": {...}}.
        val decoded = json.decodeFromString<CreateOrderResponse>(responseBody)
        if (decoded.data != null) return decoded

        return try {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val orderElement = root["order"] ?: root["data"]
            if (orderElement != null) {
                val orderData = json.decodeFromJsonElement(OrderData.serializer(), orderElement)
                decoded.copy(data = orderData)
            } else {
                decoded
            }
        } catch (_: Exception) {
            decoded
        }
    }

    suspend fun cancelOrder(orderId: String): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))

        return try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/orders/$orderId")
                .header("Authorization", "Bearer $token")
                .delete()
                .build()

            val responseCode = withContext(Dispatchers.IO) {
                client.newCall(request).execute().code
            }
            if (responseCode in 200..299) {
                Log.d("📦", "✅ Order cancelled: $orderId")
                Result.success(Unit)
            } else {
                Result.failure(Exception("Error al cancelar orden"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // MARK: - Fast Cash Payment (no products)

    suspend fun recordFastCashPayment(
        amount: Int,
        staffId: String,
        tip: Int = 0,
        splitType: String = "FULLPAYMENT",
        idempotencyKey: String = java.util.UUID.randomUUID().toString(),
        /**
         * Cobro registrado a mano (tarjeta de una terminal ajena,
         * transferencia). null = efectivo, que es como se comportaba antes.
         * ADITIVO: el server usa CASH cuando no llega.
         */
        manualMethod: com.avoqado.pos.payment.domain.ManualPaymentMethod? = null,
        /**
         * Tipo de pago del catálogo. EXCLUYENTE con `manualMethod`: el server rechaza
         * `method` + `tenderTypeId` juntos a propósito (ambigüedad de dinero) y resuelve
         * él la comisión/cajón/forma SAT desde su historial.
         */
        tenderType: com.avoqado.pos.payment.domain.TenderTypeOption? = null,
    ): Result<CashPayResult> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue"))
        if (staffId.isBlank()) return Result.failure(Exception("No staff"))

        return try {
            val bodyJson = buildString {
                append("{")
                append("\"venueId\":\"$venueId\",")
                append("\"amount\":$amount,")
                append("\"tip\":$tip,")
                append("\"status\":\"COMPLETED\",")
                // Con un tipo del catálogo viaja la REFERENCIA {id, revision} y NO
                // `method`: el server los rechaza juntos y resuelve él la semántica.
                if (tenderType != null) {
                    append("\"tenderTypeId\":\"${tenderType.id}\",")
                    append("\"tenderRevision\":${tenderType.revision},")
                } else {
                    append("\"method\":\"${manualMethod?.serverMethod ?: "CASH"}\",")
                    manualMethod?.externalSource?.let { append("\"externalSource\":\"$it\",") }
                }
                append("\"splitType\":\"$splitType\",")
                append("\"staffId\":\"$staffId\",")
                append("\"source\":\"AVOQADO_ANDROID\",")
                append("\"idempotencyKey\":\"$idempotencyKey\"")
                append("}")
            }

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/fast")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = moneyClient.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("💵", "✅ Fast cash payment recorded: $amount cents, body: ${body.take(200)}")
                val paymentId = extractPaymentIdFromResponse(body)
                val accessKey = extractReceiptAccessKeyFromResponse(body)
                val receiptUrl = extractReceiptUrlFromResponse(body)
                val inventoryWarning = extractInventoryWarningMessageFromResponse(body)
                Log.d("💵", "Extracted paymentId: $paymentId, receiptAccessKey: $accessKey, receiptUrl: $receiptUrl, inventoryWarning: ${inventoryWarning != null}")
                Result.success(CashPayResult(paymentId, accessKey, receiptUrl, inventoryWarning))
            } else {
                Log.e("💵", "❌ Fast cash payment failed ($code): $body")
                Result.failure(ServerException(code, "Error al registrar pago rápido ($code)"))
            }
        } catch (e: Exception) {
            Log.e("💵", "❌ Fast cash payment error: ${e.message}")
            Result.failure(e)
        }
    }

    // MARK: - Record Cash Payment

    /**
     * paymentId + recibo digital (para el QR en efectivo).
     *
     * `receiptUrl` es la que arma el backend y apunta al dashboard (calificación + autofactura);
     * `receiptAccessKey` queda de respaldo para armarla si la respuesta no la trae.
     */
    data class CashPayResult(
        val paymentId: String?,
        val receiptAccessKey: String?,
        val receiptUrl: String? = null,
        /**
         * Mensaje del aviso de inventario post-cobro (payment.inventoryWarning.message).
         * El cobro SIEMPRE quedó registrado; esto avisa si el stock quedó en negativo
         * o no se pudo descontar. null = sin faltantes o versión vieja del server.
         */
        val inventoryWarningMessage: String? = null,
        /**
         * 🔴 DINERO. Saldo (centavos) que le queda a la ORDEN según el server.
         * Manda sobre la aritmética local del carrito en pago dividido — ver
         * [extractRemainingBalanceCentsFromResponse]. null = no vino.
         */
        val remainingBalanceCents: Int? = null,
        /**
         * 🔴 DINERO. `PENDING` · `PARTIAL` · `PAID` de la ORDEN tras este cobro.
         * Manda sobre [remainingBalanceCents] cuando dice PAID — ver
         * [extractOrderPaymentStatusFromResponse]. null = no vino.
         */
        val orderPaymentStatus: String? = null,
    )

    suspend fun recordCashPayment(
        orderId: String,
        amount: Int,
        staffId: String,
        tip: Int = 0,
        splitType: String = "FULLPAYMENT",
        idempotencyKey: String = java.util.UUID.randomUUID().toString(),
        /**
         * Cobro registrado a mano (tarjeta de una terminal ajena,
         * transferencia). null = efectivo, que es como se comportaba antes.
         * ADITIVO: el server usa CASH cuando no llega.
         */
        manualMethod: com.avoqado.pos.payment.domain.ManualPaymentMethod? = null,
        /**
         * Tipo de pago del catálogo. EXCLUYENTE con `manualMethod`: el server rechaza
         * `method` + `tenderTypeId` juntos a propósito (ambigüedad de dinero) y resuelve
         * él la comisión/cajón/forma SAT desde su historial.
         */
        tenderType: com.avoqado.pos.payment.domain.TenderTypeOption? = null,
    ): Result<CashPayResult> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue"))
        if (staffId.isBlank()) return Result.failure(Exception("No staff"))

        return try {
            val bodyJson = buildString {
                append("{")
                append("\"venueId\":\"$venueId\",")
                append("\"amount\":$amount,")
                append("\"tip\":$tip,")
                append("\"status\":\"COMPLETED\",")
                // Con un tipo del catálogo viaja la REFERENCIA {id, revision} y NO
                // `method`: el server los rechaza juntos y resuelve él la semántica.
                if (tenderType != null) {
                    append("\"tenderTypeId\":\"${tenderType.id}\",")
                    append("\"tenderRevision\":${tenderType.revision},")
                } else {
                    append("\"method\":\"${manualMethod?.serverMethod ?: "CASH"}\",")
                    manualMethod?.externalSource?.let { append("\"externalSource\":\"$it\",") }
                }
                append("\"splitType\":\"$splitType\",")
                append("\"staffId\":\"$staffId\",")
                append("\"source\":\"AVOQADO_ANDROID\",")
                append("\"idempotencyKey\":\"$idempotencyKey\"")
                append("}")
            }

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/orders/$orderId/pay")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = moneyClient.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("💵", "✅ Cash payment recorded for order: $orderId")
                val paymentId = extractPaymentIdFromResponse(body)
                val accessKey = extractReceiptAccessKeyFromResponse(body)
                val receiptUrl = extractReceiptUrlFromResponse(body)
                val inventoryWarning = extractInventoryWarningMessageFromResponse(body)
                val remainingBalance = extractRemainingBalanceCentsFromResponse(body)
                val orderPaymentStatus = extractOrderPaymentStatusFromResponse(body)
                Log.d("💵", "   paymentId: $paymentId, receiptAccessKey: $accessKey, receiptUrl: $receiptUrl, inventoryWarning: ${inventoryWarning != null}, remainingBalance: $remainingBalance, orderPaymentStatus: $orderPaymentStatus")
                Result.success(
                    CashPayResult(paymentId, accessKey, receiptUrl, inventoryWarning, remainingBalance, orderPaymentStatus),
                )
            } else {
                Log.e("💵", "❌ Cash payment failed ($code): $body")
                Result.failure(ServerException(code, "Error al registrar pago ($code)"))
            }
        } catch (e: Exception) {
            Log.e("💵", "❌ Cash payment error: ${e.message}")
            Result.failure(e)
        }
    }

    // MARK: - Attach customer to completed payment

    suspend fun attachCustomerToPayment(
        paymentId: String?,
        customerId: String,
    ): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))
        val normalizedPaymentId = paymentId?.takeIf { it.isNotBlank() }
            ?: return Result.failure(Exception("No payment selected"))
        val normalizedCustomerId = customerId.takeIf { it.isNotBlank() }
            ?: return Result.failure(Exception("No customer selected"))

        return try {
            val bodyJson = buildJsonObject {
                put("customerId", normalizedCustomerId)
            }.toString()

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/payments/$normalizedPaymentId/customer")
                .header("Authorization", "Bearer $token")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("👤", "✅ Customer attached to payment: $normalizedPaymentId")
                Result.success(Unit)
            } else {
                Log.e("👤", "❌ Attach customer failed ($code): $body")
                val message = extractErrorMessage(body) ?: "Error al agregar cliente ($code)"
                Result.failure(ServerException(code, message))
            }
        } catch (e: Exception) {
            Log.e("👤", "❌ Attach customer error: ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun attachCustomerToLatestPayment(
        customerId: String,
        amountCents: Int,
        tipCents: Int,
        staffId: String,
    ): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))
        val normalizedCustomerId = customerId.takeIf { it.isNotBlank() }
            ?: return Result.failure(Exception("No customer selected"))
        if (amountCents <= 0) return Result.failure(Exception("No payment amount"))

        return try {
            val bodyJson = buildJsonObject {
                put("customerId", normalizedCustomerId)
                put("amountCents", amountCents)
                put("tipCents", tipCents)
                if (staffId.isNotBlank()) put("staffId", staffId)
            }.toString()

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/payments/customer")
                .header("Authorization", "Bearer $token")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("👤", "✅ Customer attached to latest payment")
                Result.success(Unit)
            } else {
                Log.e("👤", "❌ Attach customer to latest payment failed ($code): $body")
                val message = extractErrorMessage(body) ?: "Error al agregar cliente ($code)"
                Result.failure(ServerException(code, message))
            }
        } catch (e: Exception) {
            Log.e("👤", "❌ Attach customer to latest payment error: ${e.message}")
            Result.failure(e)
        }
    }

    // MARK: - Send Receipt via Email

    suspend fun sendReceiptEmail(
        paymentId: String?,
        email: String,
        receiptAccessKey: String? = null,
    ): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))
        val normalizedPaymentId = paymentId?.takeIf { it.isNotBlank() }
        val normalizedReceiptAccessKey = receiptAccessKey?.takeIf { it.isNotBlank() }

        if (normalizedPaymentId == null && normalizedReceiptAccessKey == null) {
            return Result.failure(Exception("No receipt identifier"))
        }

        return try {
            val bodyJson = buildJsonObject {
                put("email", email)
                normalizedPaymentId?.let { put("paymentId", it) }
                normalizedReceiptAccessKey?.let { put("receiptAccessKey", it) }
            }.toString()

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/receipts/send-email")
                .header("Authorization", "Bearer $token")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("📧", "✅ Email receipt sent to $email")
                Result.success(Unit)
            } else {
                Log.e("📧", "❌ Email receipt failed ($code): $body")
                Result.failure(ServerException(code, "Error al enviar recibo ($code)"))
            }
        } catch (e: Exception) {
            Log.e("📧", "❌ Email receipt error: ${e.message}")
            Result.failure(e)
        }
    }

    // MARK: - Send Receipt via WhatsApp

    suspend fun sendReceiptWhatsApp(
        paymentId: String?,
        phone: String,
        receiptAccessKey: String? = null,
    ): Result<Unit> {
        val venueId = secureStorage.venueId ?: return Result.failure(Exception("No venue"))
        val token = secureStorage.accessToken ?: return Result.failure(Exception("Not authenticated"))
        val normalizedPaymentId = paymentId?.takeIf { it.isNotBlank() }
        val normalizedReceiptAccessKey = receiptAccessKey?.takeIf { it.isNotBlank() }

        if (normalizedPaymentId == null && normalizedReceiptAccessKey == null) {
            return Result.failure(Exception("No receipt identifier"))
        }

        return try {
            val bodyJson = buildJsonObject {
                put("phone", phone)
                normalizedPaymentId?.let { put("paymentId", it) }
                normalizedReceiptAccessKey?.let { put("receiptAccessKey", it) }
            }.toString()

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/receipts/send-whatsapp")
                .header("Authorization", "Bearer $token")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("📨", "✅ WhatsApp receipt sent to $phone")
                Result.success(Unit)
            } else {
                Log.e("📨", "❌ WhatsApp receipt failed ($code): $body")
                Result.failure(ServerException(code, "Error al enviar recibo ($code)"))
            }
        } catch (e: Exception) {
            Log.e("📨", "❌ WhatsApp receipt error: ${e.message}")
            Result.failure(e)
        }
    }
}
