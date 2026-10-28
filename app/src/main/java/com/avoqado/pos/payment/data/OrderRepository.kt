package com.avoqado.pos.payment.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.loyalty.data.SaleStampOutcome
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.OrderData
import com.avoqado.pos.payment.domain.CancelacionDeCobro
import com.avoqado.pos.payment.domain.ResultadoDeCancelarOrden
import com.avoqado.pos.pos.data.model.CambioDePrecio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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
    private val capturedPayments: com.avoqado.pos.core.data.local.database.PendingPaymentDao? = null,
    /**
     * 🔴 D2, rediseño r9: el 2xx comprobado saca la reserva de `EN_VUELO` y anota su venta en el cajón en UNA escritura
     * ([SalidaDelApartado]). null sólo en pruebas que no miran el cajón; Hilt y Guice lo inyectan siempre.
     */
    private val salida: SalidaDelApartado? = null,
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

    /**
     * «Pagar después» (27-oct, R2-2): el cliente de [moneyClient] SIN reenvíos propios. Si OkHttp reenviara solo un POST cuya
     * respuesta se perdió, un 4xx del segundo envío «probaría» que no se creó la cuenta que el primero sí creó. Sin reenvío, un
     * corte es «sin respuesta» y la fila conserva su llave.
     *
     * 🔴 Y sin conexiones reusadas (`ConnectionPool(0, …)`): apagar el reenvío también apaga la reconexión de una conexión
     * keep-alive que el servidor ya cerró, y en un aparato real el POST fallaba casi siempre («unexpected end of stream»,
     * medido en la D3 el 21-sep; memoria `reintento-de-conexion-no-es-reenvio-de-peticion`). Una conexión nueva por cuenta
     * cuesta un saludo TLS; una rancia costaba un «Sin conexión» falso.
     */
    private val payLaterClient: OkHttpClient = moneyClient.newBuilder()
        .retryOnConnectionFailure(false)
        .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.SECONDS))
        .build()

    /**
     * POST de una cuenta de «Pagar después» con el cuerpo YA armado (la llave de la venta adentro). Lee el código y el cuerpo
     * crudos: aquí no pasa el `ServerException(409)` sintético de [createOrder] tras un 201 (Codex r1 #2). Sin respuesta ⇒
     * `IOException`. Sin sesión ⇒ un 401 sin cuerpo: es lo que diría el servidor.
     */
    suspend fun enviarPagarDespues(venueId: String, cuerpo: String): RespuestaCruda {
        val token = secureStorage.accessToken ?: return RespuestaCruda(401, null, null, "")
        val request = Request.Builder()
            .url("$baseUrl/mobile/venues/$venueId/orders")
            .header("Authorization", "Bearer $token")
            .post(cuerpo.toRequestBody("application/json".toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            payLaterClient.newCall(request).execute().use {
                RespuestaCruda(it.code, it.header("content-type"), it.header("ngrok-error-code"), it.body?.string().orEmpty())
            }
        }
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Seam de pruebas: apuntar a un MockWebServer. En producción es [ApiConstants.BASE_URL].
     *
     * Lo usan [cancelOrder], [createOrder], las ligas de cliente y la consulta del sello —el resto del repositorio arma la URL
     * con la constante—: son las llamadas cuya LECTURA de la respuesta hay que poder fijar por caso.
     */
    @androidx.annotation.VisibleForTesting
    internal var baseUrl: String = ApiConstants.BASE_URL

    /**
     * Último `stampsEnabled` que dijo el servidor, POR NEGOCIO (attach o consulta del sello): decide si una venta
     * encolada sin red puede prometer «El sello se suma al sincronizar». null = no se sabe ⇒ no se promete.
     * 🔴 También queda en las preferencias del aparato ([SecureStorage.programaDeSellosActivo]) y se lee de ahí si la
     * memoria no lo tiene: sólo en memoria, tras reiniciar la app SIN red la promesa nunca salía (spec §4.1; revisión
     * final, Minor #7).
     */
    private val programaDeSellosPorNegocio = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    fun programaDeSellosActivo(venueId: String): Boolean? =
        programaDeSellosPorNegocio[venueId] ?: secureStorage.programaDeSellosActivo(venueId)

    private fun recordarProgramaDeSellos(venueId: String, sello: SaleStampOutcome) {
        programaDeSellosPorNegocio[venueId] = sello.stampsEnabled
        secureStorage.setProgramaDeSellosActivo(venueId, sello.stampsEnabled)
    }

    // MARK: - Exception types

    /**
     * @param cambiosDePrecio sólo en el 409 `ORDER_PRICE_CHANGED` al crear una orden («se cobra el precio que vio el cliente»):
     *   qué líneas cobraría hoy el servidor a otro precio. Vacío en cualquier otro rechazo, que se lee como siempre.
     */
    class ServerException(
        val code: Int,
        message: String,
        val cambiosDePrecio: List<CambioDePrecio> = emptyList(),
    ) : Exception(message)

    /**
     * 🔴 D2 v7, punto 7 (Codex r5 #2): el servidor contestó 2xx con un cuerpo de NUESTRA API que confirma el cobro y lo LOCAL
     * falló después (escribir el resultado Q15, leerlo). No es un error cancelable: quien la recibe lo trata como cobrado y
     * encolado con su misma llave. A propósito NO es `IOException`: no es «sin red» ni pasa por [isQueueableError].
     * Un 2xx que NO confirma el cobro ([ResultadoNoComprobado]) no es esto: sigue como hoy.
     */
    class CobroRegistradoSinGuardar(
        causa: Throwable,
        /**
         * 🔴 Codex r14 A5: lo que el servidor CONFIRMÓ que entró al cajón ([importeCobradoEnEfectivo]), ya comprobado. El respaldo lo
         * usa igual que el camino normal (cajón, pantalla y cambio). null = no se pudo leer: el total local, como antes.
         */
        val importeCobradoCents: Int? = null,
    ) : Exception(causa.message, causa)

    /**
     * Un 2xx cuyo cuerpo NO confirma el cobro (`ConciliacionDelCobro.evaluar` no lo dio por aplicado): puede venir de un portal
     * cautivo o un intermediario. Sigue como hoy (guarda Q15/H2 del 11-oct): la fila queda en la cola con «resultado no
     * comprobado», la pantalla muestra el error y nada entra al cajón. NO se convierte en [CobroRegistradoSinGuardar].
     */
    class ResultadoNoComprobado(causa: Throwable? = null) :
        IllegalStateException("El resultado del cobro está por confirmar. Se conservó para revisión; no vuelvas a cobrar.", causa)

    companion object {
        private const val CODIGO_PRECIO_CAMBIADO = "ORDER_PRICE_CHANGED"

        /**
         * Los cambios de precio de un rechazo al crear la orden. PURA. Sólo un 409 ESTRUCTURADO (`code == "ORDER_PRICE_CHANGED"`,
         * `details.priceChanges`) los trae; otro código, un proxy o basura dan lista vacía y el rechazo se lee como siempre. Un
         * renglón ilegible (sin producto o sin los dos precios) se salta, igual que iOS.
         */
        fun leerCambiosDePrecio(body: String): List<CambioDePrecio> {
            val raiz = runCatching { errorParserJson.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
            if ((raiz["code"] as? JsonPrimitive)?.contentOrNull != CODIGO_PRECIO_CAMBIADO) return emptyList()
            val lista = (raiz["details"] as? JsonObject)?.get("priceChanges") as? JsonArray ?: return emptyList()
            return lista.mapNotNull { runCatching { errorParserJson.decodeFromJsonElement(CambioDePrecio.serializer(), it) }.getOrNull() }
        }

        private val idExtractorJson = Json { ignoreUnknownKeys = true }
        private val errorParserJson = Json { ignoreUnknownKeys = true }

        /**
         * Mismo molde que el `Json` compartido de la app (`NetworkModule`): SIN `coerceInputValues`, a propósito. Un
         * número que llega nulo (`"stampsEarned": null`) NO se vuelve 0 — con `stamped: true` eso pintaría «¡Completó
         * su tarjeta!» sin que fuera cierto—: la lectura entera se descarta y el renglón no sale.
         */
        private val selloJson = Json { ignoreUnknownKeys = true; isLenient = true }

        /**
         * El sello de la venta dentro de una respuesta del servidor: `data.loyalty` (attach) o `data` (consulta).
         * null si no viene (servidor viejo), si viene null, o si no se puede leer: el sello es un aviso y nunca
         * tumba la liga del cliente (la liga ya quedó en el servidor; decir que falló sería mentir).
         */
        internal fun leerSelloDeLaVenta(body: String, dentroDeLoyalty: Boolean): SaleStampOutcome? = runCatching {
            val data = selloJson.parseToJsonElement(body).jsonObject["data"] as? JsonObject
            val objeto = if (dentroDeLoyalty) data?.get("loyalty") as? JsonObject else data
            objeto?.let { selloJson.decodeFromJsonElement(SaleStampOutcome.serializer(), it) }
        }.getOrNull()

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

        private fun extractStrictNonNegativeCents(responseBody: String, field: String): Int? {
            return try {
                val root = idExtractorJson.parseToJsonElement(responseBody).jsonObject
                val value = root["payment"]?.jsonObject?.get(field)?.jsonPrimitive
                    ?: root["data"]?.jsonObject?.get(field)?.jsonPrimitive
                    ?: root[field]?.jsonPrimitive
                if (value == null || value.isString) return null
                value.doubleOrNull
                    ?.takeIf { it.isFinite() && it >= 0.0 }
                    ?.let { Math.round(it).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
            } catch (_: Exception) {
                null
            }
        }

        /** Importe base que el server persistió en Payment.amount (centavos). */
        fun extractRecordedAmountCentsFromResponse(responseBody: String): Int? =
            extractStrictNonNegativeCents(responseBody, "amount")

        /** Propina que el server persistió en Payment.tipAmount (centavos). */
        fun extractRecordedTipCentsFromResponse(responseBody: String): Int? =
            extractStrictNonNegativeCents(responseBody, "tipAmount")

        /**
         * 🔴 Codex r10 #1: lo que de verdad entró al cajón por un cobro en efectivo CONFIRMADO. Una segunda caja pudo mover la orden
         * mientras se cobraba: el servidor recorta SÓLO el efectivo al saldo fresco y devuelve el importe y la propina que registró.
         * Con un servidor viejo (sin esos campos) o un payload imposible (que desbordaría Int), el total local, igual que iOS.
         * La comparten la pantalla (cambio, ticket) y la anotación del cajón ([SalidaDelApartado]): no existe dos veces.
         * Rappi: el retenido válido incluye también dinero de terceros y manda sobre el desglose de venta y propina.
         */
        fun importeCobradoEnEfectivo(recordedAmountCents: Int?, recordedTipCents: Int?, totalLocalCents: Int, cashRetainedCents: Int? = null): Int {
            cashRetainedCents?.takeIf { it >= 0 }?.let { return it }
            if (recordedAmountCents == null || recordedTipCents == null || recordedAmountCents < 0 || recordedTipCents < 0) return totalLocalCents
            val registrado = recordedAmountCents.toLong() + recordedTipCents.toLong()
            return if (registrado <= Int.MAX_VALUE.toLong()) registrado.toInt() else totalLocalCents
        }

        /** Cambio recalculado por el server después de releer la orden (centavos). */
        fun extractChangeCentsFromResponse(responseBody: String): Int? =
            extractStrictNonNegativeCents(responseBody, "changeCents")

        /** Lo que se queda en el cajón por este cobro (centavos enteros, `payment.cashRetainedCents`); null si no viene. */
        fun extractCashRetainedCentsFromResponse(responseBody: String): Int? =
            extractStrictNonNegativeCents(responseBody, "cashRetainedCents")

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

        /**
         * El cuerpo EXACTO que viaja en `POST /mobile/venues/:id/fast`.
         *
         * 🔴 Vive aquí, y no dentro de [recordFastCashPayment], porque este JSON se
         * arma A MANO: es el único camino que de verdad se ejecuta, y por tanto el
         * único que un test puede probar. Agregar un campo a un modelo serializado
         * (que este endpoint no usa) ya dejó DOS veces un campo fuera del cobro real.
         *
         * `customerId` es ADITIVO y opcional: sin cliente el cuerpo queda **byte a
         * byte igual** al de siempre, que es el 99% de las ventas. En blanco vale
         * como venta anónima — el server también acepta `null` explícito, pero no
         * mandarlo es lo que preserva el contrato viejo intacto.
         */
        internal fun buildFastPaymentPayload(
            venueId: String,
            amount: Int,
            tip: Int,
            splitType: String,
            staffId: String,
            idempotencyKey: String,
            manualMethod: com.avoqado.pos.payment.domain.ManualPaymentMethod? = null,
            tenderType: com.avoqado.pos.payment.domain.TenderTypeOption? = null,
            customerId: String? = null,
        ): String = buildString {
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
            customerId?.trim()?.takeIf { it.isNotEmpty() }?.let { append(",\"customerId\":\"$it\"") }
            append("}")
        }

        /**
         * Qué pasó con el cliente de esta venta rápida (`data.customerLink` del server).
         *
         * Devuelve el texto a PINTAR o null. El cobro fue exitoso en todos los casos:
         * esto es un AVISO ámbar, nunca un error y nunca un motivo para volver a cobrar.
         * `LINKED` y `NOT_REQUESTED` son el camino feliz y no avisan nada; el resto
         * (`NOT_FOUND`, `CONFLICT`, `UNVERIFIED`) traen el texto ya en español.
         *
         * Se exige que el `status` NO sea uno de los dos felices además de que haya
         * texto: si algún día el server añade un status nuevo con warning, se pinta;
         * si añade un texto en el camino feliz, no molesta al cajero.
         */
        fun extractCustomerLinkWarningFromResponse(responseBody: String): String? {
            return try {
                val root = idExtractorJson.parseToJsonElement(responseBody).jsonObject
                val link = root["payment"]?.jsonObject?.get("customerLink")?.jsonObject
                    ?: root["data"]?.jsonObject?.get("customerLink")?.jsonObject
                    ?: root["customerLink"]?.jsonObject
                    ?: return null
                val status = link["status"]?.jsonPrimitive?.contentOrNull?.uppercase()
                if (status == "LINKED" || status == "NOT_REQUESTED") return null
                link["warning"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                // Tragarse la excepción es correcto —un aviso jamás puede tumbar un cobro
                // ya registrado— pero callarse NO: si el server cambia la forma de
                // `customerLink`, el POS deja de avisar y no queda ni rastro de por qué.
                Log.w("💵", "No se pudo leer customerLink de la respuesta: ${e.message}")
                null
            }
        }

        internal fun buildCreateOrderPayload(
            request: CreateOrderRequest,
            staffId: String,
            customerId: String? = null,
            source: String = "AVOQADO_ANDROID",
            orderType: String = "TAKEOUT",
            externalId: String? = null,
            /**
             * Premio de cartilla a aplicar EN LA CREACIÓN.
             *
             * 🔴 Va aquí y no en una segunda llamada porque el POS cobra el total que
             * devuelve ESTA respuesta (`adoptarTotalDelServer`). Con dos llamadas
             * quedaría una ventana con la cuenta al total completo, y si el cobro entra
             * ahí el cliente paga de más.
             *
             * ADITIVO: sin premio el cuerpo queda byte a byte igual al de siempre, que
             * es el 99% de las ventas.
             */
            stampRewardId: String? = null,
            /** Cuánto descontó la caja por el premio al cobrar (centavos). Ver `stampRewardExpectedDiscount` en el servidor. */
            stampRewardExpectedDiscount: Int? = null,
            /** Sólo la cola sin red y la reserva del efectivo: «la venta ya ocurrió», el servidor nunca la rechaza por precio. */
            isOfflineReplay: Boolean = false,
            /** Epoch ms del aparato al CAPTURAR la venta (una sola vez). Viaja sólo con [isOfflineReplay]. */
            createdAtLocal: Long? = null,
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
                                                                    selection.serviceCourse?.let { course ->
                                                                        put("serviceCourse", buildJsonObject {
                                                                            put("id", course.id)
                                                                            put("label", course.label)
                                                                            put("kind", course.kind)
                                                                            course.preparationVersion?.let { put("preparationVersion", it) }
                                                                            course.sortOrder?.let { put("sortOrder", it) }
                                                                        })
                                                                    }
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
                                        // El precio de catálogo que vio el cliente y la hora de ese catálogo: sólo juntos.
                                        val displayed = item.displayedUnitPrice
                                        val horaDelCatalogo = item.catalogAt
                                        if (displayed != null && horaDelCatalogo != null) {
                                            put("displayedUnitPrice", displayed)
                                            put("catalogAt", horaDelCatalogo)
                                        }
                                    } else {
                                        // Custom amount line item (e.g. "Otro importe")
                                        put("name", item.name)
                                        put("quantity", item.quantity)
                                        put("unitPrice", item.unitPrice)
                                    }

                                    if (promotionRef == null) {
                                        if (item.isCortesia) {
                                            put("isCortesia", true)
                                            item.cortesiaReason?.trim()?.takeIf { it.isNotEmpty() }?.let { put("cortesiaReason", it) }
                                        }
                                        item.externalId?.let { put("externalId", it) }
                                        item.serviceCourse?.let { course ->
                                            put("serviceCourse", buildJsonObject {
                                                put("id", course.id); put("label", course.label); put("kind", course.kind)
                                                course.preparationVersion?.let { put("preparationVersion", it) }
                                                course.sortOrder?.let { put("sortOrder", it) }
                                            })
                                        }
                                        item.note
                                            ?.trim()
                                            ?.takeIf { it.isNotEmpty() }
                                            ?.let { put("notes", it) }
                                        // 🔴 El descuento de la línea. MEDIDO EN HARDWARE
                                        // (D3, 2026-08-17): sin esta llave el POS cobraba
                                        // $169.00 y el server registraba la orden en $174.00
                                        // —`discountAmount` en 0 y sin `appliedDiscountId`—,
                                        // así que la orden quedaba PARTIAL, corta por $5.00,
                                        // y el arqueo del día no cerraba.
                                        //
                                        // `OrderItemRequest.discountId` se llenaba desde
                                        // `CartItem.itemDiscountId` y se tiraba AQUÍ: este
                                        // payload se arma a mano y nunca lo escribía. Afecta
                                        // igual al descuento manual del cajero — es
                                        // preexistente, no lo introdujo el upsell.
                                        //
                                        // Va sólo en líneas sin promoción: una promoción
                                        // viaja SOLA y su precio lo resuelve el motor.
                                        item.discountId
                                            ?.takeIf { !item.isCortesia && it.isNotBlank() }
                                            ?.let { put("discountId", it) }
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
                // «Pagar después» como Toast (founder 26-oct): el método con el que nace la cuenta. El SERVIDOR sólo cuenta como
                // deuda del cliente lo que llega con `PAY_LATER` (`CartViewModel.createPayLaterOrder`); con otro método, un cobro
                // con tarjeta que falla no es fiado. El modelo siempre lo tuvo y el cuerpo armado a mano lo tiraba.
                request.paymentMethod.takeIf { it.isNotBlank() }?.let { put("paymentMethod", it) }
                stampRewardId?.takeIf { it.isNotBlank() }?.let {
                    put("stampRewardId", it)
                    // 🔴 «Esta caja resta el premio de lo que cobra.» Sin esto el servidor lo
                    // ignora: es el candado que impide que una caja vieja (≤ 2.19.1, que
                    // cobraba el precio completo) queme el premio.
                    put("stampRewardAware", true)
                    // Si el servidor no lo confirma igual (pasa al reconectar una venta cobrada sin red),
                    // lo deja en la bitácora en vez de dejar la cuenta debiendo en silencio.
                    stampRewardExpectedDiscount?.takeIf { d -> d > 0 }?.let { d -> put("stampRewardExpectedDiscount", d) }
                }
                request.splitType?.let { put("splitType", it) }
                request.note?.trim()?.takeIf { it.isNotEmpty() }?.let { put("note", it) }
                request.reservationId?.takeIf { it.isNotBlank() }?.let { put("reservationId", it) }
                if (isOfflineReplay) {
                    put("isOfflineReplay", true)
                    createdAtLocal?.let { put("createdAtLocal", it) }
                }
            }.toString()
        }

        /**
         * B3 (IVA B2b): lo que ve el cajero cuando el servidor rechaza un cobro — su `message` («Esta cuenta está cancelada,
         * abre una nueva.»), igual que en iOS; si el cuerpo no lo trae (un proxy), el texto de siempre.
         */
        fun mensajeDelRechazo(code: Int, body: String): String = extractErrorMessage(body) ?: "Error al registrar pago ($code)"

        /**
         * Cómo se lee la respuesta al crear una cuenta de «Pagar después». PURA. Un 2xx sólo es la cuenta si trae `order.id`
         * (R2-1): la página 200 de un portal no prueba nada y la fila sigue con su llave. El total va en pesos y se compara en
         * centavos; ilegible ⇒ null («importe desconocido»), nunca otra llave.
         */
        fun leerPagarDespues(code: Int, contentType: String?, ngrokError: String?, body: String): DesenlacePagarDespues {
            if (code in 200..299) {
                val raiz = runCatching { errorParserJson.parseToJsonElement(body) as? JsonObject }.getOrNull()
                val orden = (raiz?.get("data") as? JsonObject) ?: (raiz?.get("order") as? JsonObject)
                val id = (orden?.get("id") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                    ?: return DesenlacePagarDespues.Pendiente("Respuesta $code sin cuenta")
                val total = (orden["total"] as? JsonPrimitive)?.content?.toBigDecimalOrNull()
                    ?.movePointRight(2)?.setScale(0, java.math.RoundingMode.HALF_UP)?.toInt()?.takeIf { it >= 0 }
                val datos = runCatching { errorParserJson.decodeFromJsonElement(OrderData.serializer(), orden) }.getOrNull()
                return DesenlacePagarDespues.Creada(id, total, datos)
            }
            val mensaje = extractErrorMessage(body) ?: "Error al crear orden ($code)"
            if (code == 401) return DesenlacePagarDespues.SesionVencida(mensaje)
            // 5xx, una redirección que no se siguió, 408/429 o un intermediario: nada de eso dice algo de la cuenta.
            if (code !in 400..499 || isTransient4xx(code, contentType, ngrokError, body)) {
                return DesenlacePagarDespues.Pendiente("Error $code")
            }
            return DesenlacePagarDespues.Rechazada(code, mensaje, leerCambiosDePrecio(body))
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

        /**
         * Cómo se lee la respuesta del DELETE de una orden. PURA, para poder fijar cada caso.
         *
         * 🔴 El 4xx de un INTERMEDIARIO (túnel caído, portal cautivo, proxy de plaza) no dice nada
         * de la orden: se lee como falta de red, nunca como «la orden ya no existe». Leerlo al
         * revés cerraría la intención de cancelar sin haber cancelado nada — el mismo defecto que
         * ya costó una venta encolada muerta con el texto «la orden ya no existe».
         */
        fun leerCancelacionDeOrden(
            code: Int,
            contentType: String?,
            ngrokError: String?,
            body: String?,
        ): ResultadoDeCancelarOrden {
            if (code in 200..299) return ResultadoDeCancelarOrden.Cancelada
            if (code >= 500) return ResultadoDeCancelarOrden.ErrorDeServidor(code)
            if (isTransient4xx(code, contentType, ngrokError, body)) return ResultadoDeCancelarOrden.SinRed

            val root = runCatching { errorParserJson.parseToJsonElement(body.orEmpty()).jsonObject }.getOrNull()
            val codigo = root?.get("code")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val mensaje = root?.get("message")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (codigo == CancelacionDeCobro.ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE) {
                val requestId = root["details"]?.jsonObject?.get("requestId")?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                return ResultadoDeCancelarOrden.BloqueadaPorCobro(requestId, mensaje)
            }
            if (code == 404) return ResultadoDeCancelarOrden.NoExiste
            return ResultadoDeCancelarOrden.RechazoDeNegocio(code, codigo, mensaje)
        }
    }

    suspend fun createOrder(
        request: CreateOrderRequest,
        staffId: String,
        customerId: String? = null,
        orderType: String = "TAKEOUT",
        externalId: String = java.util.UUID.randomUUID().toString(),
        stampRewardId: String? = null,
        stampRewardExpectedDiscount: Int? = null,
        capturedVenueId: String? = null,
    ): Result<CreateOrderResponse> {
        if (capturedVenueId != null && capturedVenueId.isBlank()) return Result.failure(Exception("No venue"))
        val venueId = capturedVenueId ?: secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))
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
                stampRewardId = stampRewardId,
                stampRewardExpectedDiscount = stampRewardExpectedDiscount,
            )
            val body = payload.toRequestBody("application/json".toMediaType())

            val httpRequest = Request.Builder()
                .url("$baseUrl/mobile/venues/$venueId/orders")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build()

            val (responseCode, responseBody) = withContext(Dispatchers.IO) {
                val response = moneyClient.newCall(httpRequest).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (responseCode in 200..299) {
                if (!CortesiaPersistida.confirmada(payload, responseBody)) {
                    return Result.failure(ServerException(409, CortesiaPersistida.AVISO))
                }
                val orderResponse = parseCreateOrderResponse(responseBody)
                Log.d("📦", "✅ Order created: ${orderResponse.data?.id}")
                Result.success(orderResponse)
            } else {
                Log.e("📦", "❌ Order creation failed: $responseCode - $responseBody")
                val message = extractErrorMessage(responseBody) ?: "Error al crear orden ($responseCode)"
                Result.failure(ServerException(responseCode, message, leerCambiosDePrecio(responseBody)))
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

    /**
     * DELETE /mobile/venues/:venueId/orders/:orderId — con RESULTADO TIPADO.
     *
     * 🔴 Antes devolvía `Result<Unit>` con un `Exception("Error al cancelar orden")` para TODO lo
     * que no fuera 2xx: el 409 `ORDER_CANCEL_BLOCKED_BY_TERMINAL_CHARGE` —que dice QUÉ cobro
     * bloquea— se leía igual que una cuenta ya pagada o que un túnel caído, y el cuerpo ni se
     * cerraba. La cancelación durable necesita distinguirlos: sólo uno de ellos significa «vuelve
     * a esperar el desenlace del cobro».
     *
     * @param venueId el venue de la ORDEN. Una cancelación pendiente sobrevive a un cambio de
     *   sucursal, así que no puede apuntar al venue activo.
     */
    suspend fun cancelOrder(
        orderId: String,
        venueId: String? = null,
        reason: String? = null,
    ): ResultadoDeCancelarOrden {
        val venue = venueId ?: secureStorage.venueId ?: return ResultadoDeCancelarOrden.SinRed
        val token = secureStorage.accessToken ?: return ResultadoDeCancelarOrden.SinRed

        return try {
            val cuerpo = buildJsonObject {
                reason?.takeIf { it.isNotBlank() }?.let { put("reason", JsonPrimitive(it)) }
            }.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/mobile/venues/$venue/orders/$orderId")
                .header("Authorization", "Bearer $token")
                // Esta cancelación presenta su propio error con contexto (la hoja de pendientes):
                // un 403 aquí no puede sacar el modal global encima de la pantalla del cajero.
                .header(ForbiddenInterceptor.LOCAL_ERROR_HEADER, "1")
                .delete(cuerpo)
                .build()

            // `moneyClient`: plazos cortos y FAIL_FAST — una llamada de la ruta del dinero no se
            // queda esperando a que una persona teclee un PIN. El cuerpo se lee y se CIERRA.
            val respuesta = withContext(Dispatchers.IO) {
                moneyClient.newCall(request).execute().use { response ->
                    RespuestaCruda(
                        code = response.code,
                        contentType = response.header("Content-Type"),
                        ngrokError = response.header("ngrok-error-code"),
                        body = response.body?.string() ?: "",
                    )
                }
            }
            leerCancelacionDeOrden(respuesta.code, respuesta.contentType, respuesta.ngrokError, respuesta.body)
                .also { Log.d("📦", "Cancelación de $orderId → ${respuesta.code} ⇒ $it") }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w("📦", "No se pudo cancelar la orden $orderId: ${e.message}")
            ResultadoDeCancelarOrden.SinRed
        }
    }

    /** Lo que contestó el servidor, tal cual (cancelar una orden, crear una de «Pagar después»). */
    data class RespuestaCruda(val code: Int, val contentType: String?, val ngrokError: String?, val body: String)


    /**
     * Guarda en la captura lo que contestó el 2xx. 🔴 D2 v7: sólo lo que falle DESPUÉS de comprobar el cobro (`aplicado`) sube como
     * excepción genérica (y quien llama la vuelve [CobroRegistradoSinGuardar]). Lo que no se pudo comprobar —sin poder leer la
     * captura, o un cuerpo que no confirma— lanza [ResultadoNoComprobado], también si además falla su escritura (doble fallo): un
     * 2xx sin comprobar nunca se pinta «cobrado».
     */
    private suspend fun guardarRespuestaDeCaptura(
        venueId: String,
        key: String,
        channel: CanalDelCobro,
        body: String,
        /** Lo que entró al cajón si el cobro queda comprobado ([importeCobradoEnEfectivo]). */
        importeCobradoCents: Int,
        importeAceptadoCents: Int? = null,
    ) {
        val dao = capturedPayments ?: return
        val result = try {
            val original = dao.findForVenue(venueId, key) ?: return // Unsupported/no-reservation flows keep their existing contract.
            if (original.syncStatus == "SYNCED") return
            // Se compara contra lo que el cajero aceptó; lo que se guarda sigue siendo la captura original (evidencia Q15).
            val contraQue = importeAceptadoCents?.let { aceptado ->
                ConciliacionDelCobro.leerCaptura(original.q15CaptureJson, venueId, key)?.let { ConciliacionDelCobro.guardarCaptura(it.copy(amountCents = aceptado)) }
            } ?: original.q15CaptureJson
            ConciliacionDelCobro.evaluar(contraQue, venueId, key, channel, body)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Ronda final (A4, regla única): sin leer la fila o la captura, o sin poder evaluar, no se comprobó nada.
            Log.e("💵", "⚠️ No se pudo leer o evaluar la captura de $key tras el 2xx: ${e.message}")
            noComprobado(dao, venueId, key, e)
        }
        val ahora = System.currentTimeMillis()
        if (!result.aplicado) {
            // Como hoy (Q15/H2, 11-oct): queda por revisar. Si su escritura falla, se dice en el log; sigue siendo «no comprobado».
            val fallo = try {
                check(dao.registrarResultadoQ15(venueId, key, false, result.motivo, result.resultadoJson, ahora) == 1)
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("💵", "⚠️ 2xx sin comprobar y sin poder guardar su resultado ($key): ${e.message}")
                e
            }
            noComprobado(dao, venueId, key, fallo, result.motivo ?: ConciliacionDelCobro.RESULTADO_NO_COMPROBADO)
        }
        // 🔴 Rediseño r9: con la reserva todavía `EN_VUELO`, el cobro queda SYNCED y su venta entra al cajón en la MISMA escritura,
        // con el importe definitivo. Si la cola o «Sí se cobró» ya la habían sacado, ya está anotada: no se anota otra vez.
        val escribir: suspend () -> Boolean = { dao.registrarResultadoQ15(venueId, key, true, result.motivo, result.resultadoJson, ahora) == 1 }
        check(salida?.salir(key, conDosXx = true, delFlujo = true, importe = { importeCobradoCents }, transicion = escribir) ?: escribir())
    }

    /**
     * 🔴 Ronda final (A4, regla única de las dos apps): todo 2xx que NO comprobó el cobro —`aplicado == false`, o no se pudo leer
     * la fila o la captura, o evaluarla— es el error de hoy ([ResultadoNoComprobado]) MÁS la retención de respaldo
     * «resultado no comprobado», como mejor esfuerzo (con log si falla). Con la marca, «Cancelar» sólo la suelta y la cola no la
     * da por cobrada. [CobroRegistradoSinGuardar] queda sólo para el 2xx que `evaluar` sí dio por aplicado.
     */
    private suspend fun noComprobado(
        dao: com.avoqado.pos.core.data.local.database.PendingPaymentDao,
        venueId: String,
        key: String,
        causa: Throwable?,
        motivo: String = ConciliacionDelCobro.RESULTADO_NO_COMPROBADO,
    ): Nothing {
        try {
            dao.retenerPagoQ15(venueId, key, motivo, System.currentTimeMillis())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("💵", "⚠️ No se pudo marcar por revisar el 2xx sin comprobar ($key); sigue EN_VUELO: ${e.message}")
        }
        throw ResultadoNoComprobado(causa)
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
        /**
         * 🔴 EL CLIENTE DE LA VENTA. El cajero puede elegirlo en el carrito y hasta hoy
         * el cobro rápido lo TIRABA: la orden `FAST-*` nacía anónima aunque la pantalla
         * dijera lo contrario — sin historial, sin CFDI y sin atribución.
         *
         * ADITIVO y opcional. El server valida contra ESTE negocio y, si el id no sirve,
         * registra la venta anónima y AVISA (`customerLink`): un cliente inválido jamás
         * rechaza dinero ya cobrado.
         */
        customerId: String? = null,
        capturedVenueId: String? = null,
    ): Result<CashPayResult> {
        if (capturedVenueId != null && capturedVenueId.isBlank()) return Result.failure(Exception("No venue"))
        val venueId = capturedVenueId ?: secureStorage.venueId ?: return Result.failure(Exception("No venue"))
        if (staffId.isBlank()) return Result.failure(Exception("No staff"))

        return try {
            val bodyJson = buildFastPaymentPayload(
                venueId = venueId,
                amount = amount,
                tip = tip,
                splitType = splitType,
                staffId = staffId,
                idempotencyKey = idempotencyKey,
                manualMethod = manualMethod,
                tenderType = tenderType,
                customerId = customerId,
            )

            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/fast")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = moneyClient.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                try {
                    guardarRespuestaDeCaptura(venueId, idempotencyKey, CanalDelCobro.FAST, body, importeCobradoCents = amount + tip)
                    Log.d("💵", "✅ Fast cash payment recorded: $amount cents, body: ${body.take(200)}")
                    val paymentId = extractPaymentIdFromResponse(body)
                    val accessKey = extractReceiptAccessKeyFromResponse(body)
                    val receiptUrl = extractReceiptUrlFromResponse(body)
                    val inventoryWarning = extractInventoryWarningMessageFromResponse(body)
                    val customerWarning = extractCustomerLinkWarningFromResponse(body)
                    Log.d("💵", "Extracted paymentId: $paymentId, receiptAccessKey: $accessKey, receiptUrl: $receiptUrl, inventoryWarning: ${inventoryWarning != null}, customerWarning: ${customerWarning != null}")
                    Result.success(
                        CashPayResult(
                            paymentId = paymentId,
                            receiptAccessKey = accessKey,
                            receiptUrl = receiptUrl,
                            inventoryWarningMessage = inventoryWarning,
                            customerLinkWarning = customerWarning,
                        ),
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: ResultadoNoComprobado) {
                    // Como hoy: el 2xx no confirma el cobro. La causa no se calla (ronda 1, M2).
                    Log.e("💵", "⚠️ 2xx sin comprobar ($idempotencyKey): ${e.cause?.message ?: "el cuerpo no confirma el cobro"}")
                    Result.failure(e)
                } catch (e: Exception) {
                    // 2xx: el servidor ya lo tiene. Lo que falló es de este aparato (D2 v7, punto 7).
                    Log.e("💵", "⚠️ Fast cash payment registrado (2xx) pero no se pudo guardar aquí: ${e.message}")
                    Result.failure(CobroRegistradoSinGuardar(e, amount + tip))
                }
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
        /**
         * Aviso del server sobre EL CLIENTE de esta venta (`customerLink`): el id que
         * mandó el POS no existe en este negocio, la venta ya tenía otro cliente, o no
         * se pudo verificar. Español, listo para el toast ámbar.
         *
         * 🔴 El cobro SÍ quedó registrado en todos esos casos. Es un aviso, jamás un
         * error, y jamás un motivo para volver a cobrar: el cliente se reasigna con
         * `attachCustomerToPayment`. null = vinculado, venta anónima, o server viejo.
         */
        val customerLinkWarning: String? = null,
        /** Importe base que el server realmente registró, en centavos. */
        val recordedAmountCents: Int? = null,
        /** Propina que el server realmente registró, en centavos. */
        val recordedTipCents: Int? = null,
        /** Cambio que el server determinó tras releer la orden, en centavos. */
        val authoritativeChangeCents: Int? = null,
        /**
         * Lo que se queda en el cajón por este cobro, en centavos enteros (`payment.cashRetainedCents`, Task S2 del
         * SERVIDOR; hoy = importe + propina registrados, y Rappi 2b le sumará su PAY_IN). Con él, cambio = recibido −
         * esto. null = servidor viejo: el cambio se calcula como siempre. Contrato acordado con Rappi 2b (21-oct).
         */
        val cashRetainedCents: Int? = null,
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
        capturedVenueId: String? = null,
        /**
         * El importe que el cajero ya aceptó en pantalla porque el servidor cambió el precio EN LÍNEA (precio que vio el
         * cliente). Sólo cambia contra qué se compara el resultado; la captura original no se toca. null = como siempre.
         */
        importeAceptadoCents: Int? = null,
        /** Rappi 2b: efectivo ya recibido con un saldo guardado ⇒ viaja con `isOfflineReplay` (el contrato del replay). */
        capturaPrevia: Boolean = false,
    ): Result<CashPayResult> {
        if (capturedVenueId != null && capturedVenueId.isBlank()) return Result.failure(Exception("No venue"))
        val venueId = capturedVenueId ?: secureStorage.venueId ?: return Result.failure(Exception("No venue"))
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
                if (capturaPrevia) append("\"isOfflineReplay\":true,")
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
                // Codex r14 A5: lo que el servidor registró, una sola vez: lo usan la captura y, si lo local falla, el respaldo.
                val cashRetained = extractCashRetainedCentsFromResponse(body)
                val importeCobrado = importeCobradoEnEfectivo(
                    extractRecordedAmountCentsFromResponse(body), extractRecordedTipCentsFromResponse(body), amount + tip,
                    cashRetainedCents = cashRetained,
                )
                try {
                    guardarRespuestaDeCaptura(
                        venueId, idempotencyKey, CanalDelCobro.ORDER, body,
                        importeCobradoCents = importeCobrado,
                        importeAceptadoCents = importeAceptadoCents,
                    )
                    Log.d("💵", "✅ Cash payment recorded for order: $orderId")
                    val paymentId = extractPaymentIdFromResponse(body)
                    val accessKey = extractReceiptAccessKeyFromResponse(body)
                    val receiptUrl = extractReceiptUrlFromResponse(body)
                    val inventoryWarning = extractInventoryWarningMessageFromResponse(body)
                    val remainingBalance = extractRemainingBalanceCentsFromResponse(body)
                    val orderPaymentStatus = extractOrderPaymentStatusFromResponse(body)
                    val recordedAmount = extractRecordedAmountCentsFromResponse(body)
                    val recordedTip = extractRecordedTipCentsFromResponse(body)
                    val authoritativeChange = extractChangeCentsFromResponse(body)
                    Log.d("💵", "   paymentId: $paymentId, receiptAccessKey: $accessKey, receiptUrl: $receiptUrl, inventoryWarning: ${inventoryWarning != null}, remainingBalance: $remainingBalance, orderPaymentStatus: $orderPaymentStatus, recordedAmount: $recordedAmount, recordedTip: $recordedTip, change: $authoritativeChange, cashRetained: $cashRetained")
                    Result.success(
                        CashPayResult(
                            paymentId = paymentId,
                            receiptAccessKey = accessKey,
                            receiptUrl = receiptUrl,
                            inventoryWarningMessage = inventoryWarning,
                            remainingBalanceCents = remainingBalance,
                            orderPaymentStatus = orderPaymentStatus,
                            recordedAmountCents = recordedAmount,
                            recordedTipCents = recordedTip,
                            authoritativeChangeCents = authoritativeChange,
                            cashRetainedCents = cashRetained,
                        ),
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: ResultadoNoComprobado) {
                    // Como hoy: el 2xx no confirma el cobro. La causa no se calla (ronda 1, M2).
                    Log.e("💵", "⚠️ 2xx sin comprobar ($idempotencyKey): ${e.cause?.message ?: "el cuerpo no confirma el cobro"}")
                    Result.failure(e)
                } catch (e: Exception) {
                    // 2xx: el servidor ya lo tiene. Lo que falló es de este aparato (D2 v7, punto 7).
                    Log.e("💵", "⚠️ Cash payment registrado (2xx) pero no se pudo guardar aquí: ${e.message}")
                    Result.failure(CobroRegistradoSinGuardar(e, importeCobrado))
                }
            } else {
                Log.e("💵", "❌ Cash payment failed ($code): $body")
                Result.failure(ServerException(code, mensajeDelRechazo(code, body)))
            }
        } catch (e: Exception) {
            Log.e("💵", "❌ Cash payment error: ${e.message}")
            Result.failure(e)
        }
    }

    // MARK: - Attach customer to completed payment

    /**
     * Liga al cliente con la venta ya cobrada. Sellos tardíos: el éxito trae el sello que el servidor sumó (o por qué no)
     * — campo `loyalty`, OPCIONAL: null con un servidor viejo o si no se puede leer, y la liga sigue siendo un éxito.
     */
    suspend fun attachCustomerToPayment(
        paymentId: String?,
        customerId: String,
    ): Result<SaleStampOutcome?> {
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
                .url("$baseUrl/mobile/venues/$venueId/payments/$normalizedPaymentId/customer")
                .header("Authorization", "Bearer $token")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("👤", "✅ Customer attached to payment: $normalizedPaymentId")
                // Sellos tardíos: el servidor dice si esta liga sumó sello (campo `loyalty`, opcional).
                Result.success(leerSelloDeLaVenta(body, dentroDeLoyalty = true)?.also { recordarProgramaDeSellos(venueId, it) })
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
    ): Result<SaleStampOutcome?> {
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
                .url("$baseUrl/mobile/venues/$venueId/payments/customer")
                .header("Authorization", "Bearer $token")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("👤", "✅ Customer attached to latest payment")
                Result.success(leerSelloDeLaVenta(body, dentroDeLoyalty = true)?.also { recordarProgramaDeSellos(venueId, it) })
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

    // MARK: - Sello de la venta (sellos tardíos)

    /** Plazo corto: la consulta corre detrás de «Cobro exitoso» y no tiene por qué colgarse 30 s. */
    private val selloClient: OkHttpClient by lazy { client.newBuilder().callTimeout(8, TimeUnit.SECONDS).build() }

    /**
     * `GET /mobile/venues/:venueId/payments/:paymentId/loyalty`: qué pasó con el sello de una venta que se cobró CON
     * cliente desde el carrito. 🔴 Nunca lanza: sin red, 403 (rol sin `loyalty:read`) o 404 (servidor viejo) son null y la
     * pantalla de éxito no cambia. Va marcada como tarea de FONDO: un 403 no puede abrir el teclado del PIN encima del
     * recibo.
     */
    suspend fun consultarSelloDeLaVenta(paymentId: String): SaleStampOutcome? {
        val venueId = secureStorage.venueId ?: return null
        val token = secureStorage.accessToken ?: return null
        if (paymentId.isBlank()) return null
        return try {
            val request = Request.Builder()
                .url("$baseUrl/mobile/venues/$venueId/payments/$paymentId/loyalty")
                .header("Authorization", "Bearer $token")
                .header(ForbiddenInterceptor.BACKGROUND_HEADER, "1")
                .get()
                .build()
            val (code, body) = withContext(Dispatchers.IO) {
                selloClient.newCall(request).execute().use { response -> response.code to (response.body?.string() ?: "") }
            }
            if (code !in 200..299) {
                Log.w("🎟️", "Sello de la venta $paymentId: HTTP $code")
                return null
            }
            leerSelloDeLaVenta(body, dentroDeLoyalty = false)?.also { recordarProgramaDeSellos(venueId, it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("🎟️", "Sello de la venta $paymentId sin respuesta: ${e.message}")
            null
        }
    }

    /** D7: 2 s por consulta como máximo — corre entre «la tarjeta cobró» y «Cobro exitoso» (Codex fase 1 código r1 #2). */
    // Sin renovación de sesión (r2): un 401 aquí es «no se pudo confirmar», nunca una espera más larga por el candado y
    // el cliente de renovación. La consulta es informativa; la siguiente petición normal renueva la sesión como siempre.
    private val saldoClient: OkHttpClient by lazy {
        moneyClient.newBuilder().authenticator(okhttp3.Authenticator.NONE).callTimeout(2, TimeUnit.SECONDS).build()
    }

    private suspend fun esperarSinBloquear(call: okhttp3.Call): Pair<Int, String> =
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resumeWith(Result.failure(e))
                }
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    val leido = runCatching { response.use { it.code to (it.body?.string() ?: "") } }
                    if (cont.isActive) cont.resumeWith(leido)
                }
            })
        }

    /** Lo que la orden debe según el SERVIDOR (`preBillBalance`) y su estado de pago, de la MISMA respuesta. */
    data class SaldoDeLaOrden(val remainingCents: Int, val paymentStatus: String?)

    /**
     * D7 (dividir por producto, fase 1, 27-oct): tras cobrar con TARJETA una parte de una venta partida, la terminal no dice
     * cuánto queda; se le pregunta al SERVIDOR (`GET .../orders/:id`, el mismo que lee Pedidos en línea). 🔴 Nunca lanza:
     * sin red, sin sesión o con una respuesta rara es null y quien llama lo DICE (nunca lo convierte en saldo cero).
     */
    suspend fun saldoDeLaOrden(orderId: String): SaldoDeLaOrden? {
        val venueId = secureStorage.venueId ?: return null
        val token = secureStorage.accessToken ?: return null
        if (orderId.isBlank()) return null
        return try {
            val request = Request.Builder()
                .url("$baseUrl/mobile/venues/$venueId/orders/$orderId")
                .header("Authorization", "Bearer $token")
                .header(ForbiddenInterceptor.BACKGROUND_HEADER, "1")
                .get()
                .build()
            // Asíncrona y cancelable (Codex fase 1 r3): al vencerse el presupuesto del cobro se suelta YA, aunque el DNS del
            // sistema siga bloqueado; la llamada se cancela y su hilo termina por su cuenta.
            val (code, body) = esperarSinBloquear(saldoClient.newCall(request))
            if (code !in 200..299) return null
            val order = Json.parseToJsonElement(body).jsonObject["order"]?.jsonObject ?: return null
            val saldo = order["preBillBalance"]?.jsonObject?.get("remainingBalanceCents")?.jsonPrimitive?.contentOrNull
                ?.toDoubleOrNull()?.let { Math.round(it).toInt() } ?: return null
            SaldoDeLaOrden(remainingCents = saldo, paymentStatus = order["paymentStatus"]?.jsonPrimitive?.contentOrNull?.uppercase())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("💵", "Saldo de la orden $orderId sin respuesta: ${e.message}")
            null
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
