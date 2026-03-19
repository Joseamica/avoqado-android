package com.avoqado.pos.orders.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.orders.data.model.OrderDetail
import com.avoqado.pos.orders.data.model.OrderPage
import com.avoqado.pos.orders.data.model.OrderSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "🧾 OrdersRepo"

@Singleton
class OrdersRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // MARK: - State Flows

    private val _orders = MutableStateFlow<List<OrderSummary>>(emptyList())
    val orders: StateFlow<List<OrderSummary>> = _orders.asStateFlow()

    private val _selectedOrder = MutableStateFlow<OrderDetail?>(null)
    val selectedOrder: StateFlow<OrderDetail?> = _selectedOrder.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _isLoadingDetail = MutableStateFlow(false)
    val isLoadingDetail: StateFlow<Boolean> = _isLoadingDetail.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _hasMore = MutableStateFlow(false)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    // MARK: - Helpers

    private fun baseUrl(): String? {
        val venueId = secureStorage.venueId ?: return null
        return "${ApiConstants.BASE_URL}/mobile/venues/$venueId"
    }

    private suspend fun executeRequest(request: Request): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            response.code to (response.body?.string() ?: "")
        }

    // MARK: - Fetch Orders (paginated)

    suspend fun fetchOrders(
        page: Int = 1,
        pageSize: Int = 20,
        search: String? = null,
        status: String? = null,
    ): OrderPage {
        val base = baseUrl() ?: return OrderPage()

        val urlBuilder = StringBuilder("$base/orders?page=$page&pageSize=$pageSize")
        if (!search.isNullOrBlank()) {
            val encoded = java.net.URLEncoder.encode(search.trim(), "UTF-8")
            urlBuilder.append("&search=$encoded")
        }
        if (!status.isNullOrBlank()) {
            urlBuilder.append("&status=$status")
        }

        return try {
            val request = Request.Builder()
                .url(urlBuilder.toString())
                .get()
                .build()

            val (code, body) = executeRequest(request)
            if (code in 200..299 && body.isNotEmpty()) {
                val root = json.parseToJsonElement(body).jsonObject
                val dataArray = root["data"]?.jsonArray ?: return OrderPage()
                val meta = root["meta"]?.jsonObject

                val orders = dataArray.map {
                    json.decodeFromJsonElement<OrderSummary>(it)
                }
                val total = meta?.get("total")?.jsonPrimitive?.int ?: orders.size
                val currentPage = meta?.get("page")?.jsonPrimitive?.int ?: page
                val pageCount = meta?.get("pageCount")?.jsonPrimitive?.int ?: 1

                Log.d(TAG, "✅ Loaded ${orders.size} orders (page $currentPage/$pageCount)")
                OrderPage(
                    orders = orders,
                    total = total,
                    page = currentPage,
                    pageCount = pageCount,
                )
            } else {
                Log.e(TAG, "❌ fetchOrders error: HTTP $code")
                OrderPage()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchOrders exception: ${e.message}", e)
            OrderPage()
        }
    }

    // MARK: - Fetch Order Detail

    suspend fun fetchOrderDetail(orderId: String): OrderDetail? {
        val base = baseUrl() ?: return null

        return try {
            val request = Request.Builder()
                .url("$base/orders/$orderId")
                .get()
                .build()

            val (code, body) = executeRequest(request)
            if (code in 200..299 && body.isNotEmpty()) {
                val root = json.parseToJsonElement(body).jsonObject
                val orderObj = root["order"] ?: return null
                val detail = json.decodeFromJsonElement<OrderDetail>(orderObj)
                Log.d(TAG, "✅ Loaded order detail ${detail.id}")
                detail
            } else {
                Log.e(TAG, "❌ fetchOrderDetail error: HTTP $code")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchOrderDetail exception: ${e.message}", e)
            null
        }
    }

    // MARK: - State-Mutating Methods

    suspend fun loadOrders(
        page: Int,
        search: String?,
        status: String?,
        append: Boolean = false,
    ) {
        if (append) {
            _isLoadingMore.value = true
        } else {
            _isLoading.value = true
        }
        _errorMessage.value = null

        try {
            val result = fetchOrders(page = page, search = search, status = status)
            if (append) {
                _orders.value = _orders.value + result.orders
            } else {
                _orders.value = result.orders
            }
            _currentPage.value = result.page
            _hasMore.value = result.hasMore
        } catch (e: Exception) {
            Log.e(TAG, "❌ loadOrders exception: ${e.message}", e)
            _errorMessage.value = "Error al cargar órdenes"
        } finally {
            _isLoading.value = false
            _isLoadingMore.value = false
        }
    }

    suspend fun loadOrderDetail(orderId: String) {
        _isLoadingDetail.value = true
        _errorMessage.value = null

        try {
            val detail = fetchOrderDetail(orderId)
            _selectedOrder.value = detail
            if (detail == null) {
                _errorMessage.value = "No se encontró la orden"
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ loadOrderDetail exception: ${e.message}", e)
            _errorMessage.value = "Error al cargar detalle de orden"
        } finally {
            _isLoadingDetail.value = false
        }
    }

    fun clearSelectedOrder() {
        _selectedOrder.value = null
    }
}
