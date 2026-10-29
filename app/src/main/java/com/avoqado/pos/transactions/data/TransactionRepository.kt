package com.avoqado.pos.transactions.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ForbiddenInterceptor
import com.avoqado.pos.core.data.network.TokenRefreshAuthenticator
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.transactions.data.model.PaginationMeta
import com.avoqado.pos.transactions.data.model.ReceiptLinkResponse
import com.avoqado.pos.transactions.data.model.ResultadoLigaRecibo
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.data.model.TransactionDetailResponse
import com.avoqado.pos.transactions.data.model.TransactionsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.avoqado.pos.core.data.network.ServerErrorText
import com.avoqado.pos.transactions.data.model.PendingCustomerAssignment
import okhttp3.Authenticator
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** El SERVIDOR contestó y la venta nueva de un cambio no tiene pago cobrado: espera la diferencia o se canceló (F4, 28-oct). */
class ExchangeOrderNotCharged(val cancelled: Boolean) : Exception()

@Singleton
class TransactionRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    // 🔴 AQUÍ NO VA `coerceInputValues`, y la razón es dinero.
    //
    // Se puso el 2026-09-11 para que un `"modifiers":[{"name":null}]` no tumbara la venta
    // entera, y una auditoría (Codex gpt-6-astra) lo marcó P1 el mismo día: la coerción NO
    // distingue el nombre de un modificador del IMPORTE de la venta. Un `"amount": null`
    // caería a `0.0`, el total pasaría de $110 a $10 y la pantalla lo presentaría como
    // **«Cortesía»** (`TransactionDetailSheet.kt`), con una explicación falsa y sin que
    // nada falle. Convierte un error de contrato en información financiera creíble.
    //
    // El defecto real se arregla en el campo, no en el parser: `TransactionItemModifier.name`
    // es anulable y con eso basta — lo prueba `TransactionDecodeNullsTest`, que pasa SIN
    // coerción. Tolerar nulos se hace campo por campo, nunca en bloque sobre dinero.
    private val json = Json { ignoreUnknownKeys = true }
    internal var baseUrl = ApiConstants.BASE_URL

    private val _transactions = MutableStateFlow<List<Transaction>>(emptyList())
    val transactions: StateFlow<List<Transaction>> = _transactions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private var currentPage = 1
    private var hasMore = true
    private val pageSize = 20

    suspend fun fetchTransactions(
        page: Int = 1,
        search: String? = null,
    ): Result<Unit> {
        val venueId = secureStorage.venueId
            ?: return Result.failure(IllegalStateException("Sin venue activo"))
        val token = secureStorage.accessToken
            ?: return Result.failure(IllegalStateException("Sin sesión"))

        if (page == 1) {
            // Refresh de fondo silencioso: sin skeleton encima de datos buenos (spec §6).
            _isLoading.value = _transactions.value.isEmpty()
            currentPage = 1
            hasMore = true
        } else {
            _isLoadingMore.value = true
        }

        return try {
            val urlBuilder = StringBuilder(
                "$baseUrl/mobile/venues/$venueId/transactions?page=$page&pageSize=$pageSize",
            )
            if (!search.isNullOrBlank()) {
                urlBuilder.append("&search=${URLEncoder.encode(search, "UTF-8")}")
            }

            val request = Request.Builder()
                .url(urlBuilder.toString())
                .header("Authorization", "Bearer $token")
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (responseCode in 200..299 && body.isNotEmpty()) {
                val result = json.decodeFromString<TransactionsResponse>(body)
                _transactions.value = if (page == 1) result.data else _transactions.value + result.data
                currentPage = page
                hasMore = result.meta?.let { page < it.pageCount } ?: false
                Log.d("📦", "✅ Loaded ${result.data.size} transactions (page $page)")
                Result.success(Unit)
            } else {
                Log.e("📦", "❌ Transactions fetch failed: $responseCode")
                Result.failure(Exception("Transactions HTTP $responseCode"))
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Transactions fetch error: ${e.message}")
            Result.failure(e)
        } finally {
            _isLoading.value = false
            _isLoadingMore.value = false
        }
    }

    suspend fun fetchTransactionDetail(paymentId: String): Transaction? =
        fetchTransactionDetail(paymentId, secureStorage.userId, secureStorage.venueId)

    /** Exchange links contain order ids; the transaction detail route requires a payment id. */
    suspend fun paymentIdForExchangeOrder(orderId: String, actorId: String?, venueId: String?): String? {
        if (actorId == null || venueId == null || !sameIdentity(actorId, venueId)) return null
        return try {
            val request = Request.Builder()
                .url("$baseUrl/mobile/venues/$venueId/orders/${URLEncoder.encode(orderId, "UTF-8")}")
                .header(ForbiddenInterceptor.LOCAL_ERROR_HEADER, "1")
                .header(TokenRefreshAuthenticator.NO_AUTO_RETRY_HEADER, "1")
                .build()
            val (code, body) = leerComo(actorId, venueId, request) ?: return null
            if (!sameIdentity(actorId, venueId)) return null
            if (code == 403) throw SecurityException("orders:read")
            if (code !in 200..299) return null
            val order = json.decodeFromString<com.avoqado.pos.tables.data.OrderDetailResponse>(body).order ?: return null
            if (order.id != orderId) return null
            order.payments.firstOrNull { it.status == "COMPLETED" && it.id.isNotBlank() }?.id
                ?: throw ExchangeOrderNotCharged(cancelled = order.status == "CANCELLED")
        } catch (e: CancellationException) { throw e }
        catch (e: SecurityException) { throw e }
        catch (e: ExchangeOrderNotCharged) { throw e }
        catch (_: Exception) { null }
    }

    suspend fun fetchTransactionDetail(paymentId: String, actorId: String?, venueId: String?): Transaction? {
        if (actorId == null || venueId == null || !sameIdentity(actorId, venueId)) return null
        return try {
            val request = Request.Builder()
                .url("$baseUrl/mobile/venues/$venueId/transactions/$paymentId")
                .header(ForbiddenInterceptor.LOCAL_ERROR_HEADER, "1")
                .header(TokenRefreshAuthenticator.NO_AUTO_RETRY_HEADER, "1")
                .build()
            val (responseCode, body) = leerComo(actorId, venueId, request) ?: return null
            if (!sameIdentity(actorId, venueId)) return null
            if (responseCode in 200..299 && body.isNotEmpty()) {
                json.decodeFromString<TransactionDetailResponse>(body).transaction?.takeIf { it.id == paymentId }
            } else {
                Log.e("📦", "Transaction detail fetch failed: $responseCode")
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("📦", "Transaction detail fetch error: ${e.message}")
            null
        }
    }

    /**
     * La liga del recibo digital de una venta ya cobrada, para dibujar el QR al REIMPRIMIR.
     *
     * 🔴 Con TOPE DE 5 s propio: esto cuelga de una impresión que debe salir igual si el servidor
     * tarda. Sin el tope, un backend lento retrasaría el papel — y el papel es lo que el cajero
     * tiene enfrente con el cliente esperando.
     *
     * Nunca lanza: devuelve cuál de los tres desenlaces ocurrió para que la pantalla pueda decir
     * la verdad (sin red ≠ el servidor falló).
     */
    suspend fun fetchReceiptLink(paymentId: String): ResultadoLigaRecibo {
        val venueId = secureStorage.venueId ?: return ResultadoLigaRecibo.FalloDelServidor(0)
        val token = secureStorage.accessToken ?: return ResultadoLigaRecibo.FalloDelServidor(0)

        val url = "${ApiConstants.BASE_URL}/mobile/venues/$venueId/payments/$paymentId/receipt"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()

        val acotado = client.newBuilder()
            .callTimeout(5, TimeUnit.SECONDS)
            .build()

        return try {
            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = acotado.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (responseCode in 200..299 && body.isNotEmpty()) {
                val liga = json.decodeFromString<ReceiptLinkResponse>(body).receipt
                if (liga != null && liga.receiptUrl.isNotBlank()) {
                    ResultadoLigaRecibo.Obtenida(liga)
                } else {
                    // 200 con cuerpo inservible: el servidor SÍ respondió, así que no es falta de red.
                    Log.e("🧾", "❌ Liga del recibo vacía para $paymentId")
                    ResultadoLigaRecibo.FalloDelServidor(responseCode)
                }
            } else {
                Log.e("🧾", "❌ Liga del recibo: HTTP $responseCode")
                ResultadoLigaRecibo.FalloDelServidor(responseCode)
            }
        } catch (e: java.io.IOException) {
            // IOException cubre sin red, DNS y el timeout de arriba: nunca se llegó al servidor.
            Log.e("🧾", "❌ Liga del recibo sin conexión: ${e.message}")
            ResultadoLigaRecibo.SinRed
        } catch (e: Exception) {
            Log.e("🧾", "❌ Liga del recibo ilegible: ${e.message}")
            ResultadoLigaRecibo.FalloDelServidor(0)
        }
    }

    suspend fun assignCustomer(pending: PendingCustomerAssignment): Result<Unit> {
        if (!sameIdentity(pending.actorId, pending.venueId)) return Result.failure(IllegalStateException("La sesión o la sucursal cambió. Abre de nuevo esta venta."))
        val token = secureStorage.accessToken
            ?: return Result.failure(IllegalStateException("Inicia sesión para asignar un cliente"))
        return try {
            val request = Request.Builder()
                .url("$baseUrl/mobile/venues/${pending.venueId}/transactions/${pending.paymentId}/customer")
                .header("Authorization", "Bearer $token")
                .header(ForbiddenInterceptor.LOCAL_ERROR_HEADER, "1")
                .header(TokenRefreshAuthenticator.NO_AUTO_RETRY_HEADER, "1")
                .post(soloUnEnvio(buildJsonObject { put("customerId", pending.customerId) }.toString().toRequestBody("application/json".toMediaType())))
                .build()
            val scopedClient = scopedCustomerClient(pending.actorId, pending.venueId, token, isRead = false)
            withContext(Dispatchers.IO) {
                scopedClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) Result.success(Unit)
                    else {
                        val message = runCatching {
                            (json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonObject)
                                ?.get("message")?.jsonPrimitive?.content
                        }.getOrNull()
                        Result.failure(IllegalStateException(when (response.code) {
                            401 -> "Tu sesión necesita renovarse. Abre de nuevo esta venta e inténtalo de nuevo."
                            403 -> "No tienes permiso para asignar un cliente a esta venta"
                            404 -> "Esta venta o el cliente ya no están disponibles"
                            409 -> ServerErrorText.humanize(message, "La venta cambió y ya no permite esta asignación. Vuelve a abrirla.")
                            else -> "No se pudo confirmar la asignación. Revisa la conexión y reintenta."
                        }))
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** OkHttp repite un POST ante «503 Retry-After: 0» aunque no reintente conexiones; un cuerpo de un solo envío lo impide. */
    private fun soloUnEnvio(cuerpo: RequestBody): RequestBody = object : RequestBody() {
        override fun contentType() = cuerpo.contentType()
        override fun contentLength() = cuerpo.contentLength()
        override fun writeTo(sink: okio.BufferedSink) = cuerpo.writeTo(sink)
        override fun isOneShot() = true
    }

    private fun sameIdentity(actorId: String, venueId: String): Boolean =
        secureStorage.userId == actorId && secureStorage.venueId == venueId

    /**
     * GET con la credencial de [actorId]. La sesión del POS dura 10 min (D9, 28-oct): el autenticador de la app la renueva
     * pero no reenvía (NO_AUTO_RETRY). Se repite UNA vez con la nueva, y sólo si sigue siendo la misma persona en la misma
     * sucursal. Sin token, null.
     */
    private suspend fun leerComo(actorId: String, venueId: String, request: Request): Pair<Int, String>? {
        var token = secureStorage.accessToken ?: return null
        for (intento in 0..1) {
            val (code, body) = withContext(Dispatchers.IO) {
                scopedCustomerClient(actorId, venueId, token, isRead = true).newCall(request).execute().use {
                    it.code to it.body?.string().orEmpty()
                }
            }
            val renovado = secureStorage.accessToken
            if (code != 401 || intento == 1 || renovado == null || renovado == token || !sameIdentity(actorId, venueId)) {
                return code to body
            }
            token = renovado
        }
        return null
    }

    /**
     * GET y POST históricos comparten autoridad: sin PIN, replay ni salto a otro origen. El POST tampoco renueva la sesión;
     * el GET sí la deja renovar (D9) y [leerComo] decide si repite.
     * Una conexión guardada que el servidor ya cerró tumbaba el detalle (D3, 23-oct): el GET puede
     * reintentar (leer dos veces no cambia nada); el POST no reintenta y por eso usa una conexión nueva.
     */
    private fun scopedCustomerClient(actorId: String, venueId: String, token: String, isRead: Boolean): OkHttpClient =
        client.newBuilder()
            .apply { if (!isRead) authenticator(Authenticator.NONE) }
            .retryOnConnectionFailure(isRead)
            .apply { if (!isRead) connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS)) }
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(20, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                check(sameIdentity(actorId, venueId)) { "La sesión o la sucursal cambió. Abre de nuevo esta venta." }
                chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer $token").build())
            }.build()

    fun canLoadMore(): Boolean = hasMore && !_isLoadingMore.value

    fun getNextPage(): Int = currentPage + 1

    fun clearCache() {
        _transactions.value = emptyList()
        currentPage = 1
        hasMore = true
    }
}
