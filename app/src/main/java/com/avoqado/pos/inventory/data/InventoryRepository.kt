package com.avoqado.pos.inventory.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.inventory.data.local.InventoryTransferDao
import com.avoqado.pos.inventory.data.local.InventoryTransferEntity
import com.avoqado.pos.inventory.data.local.PurchaseOrderDao
import com.avoqado.pos.inventory.data.local.PurchaseOrderEntity
import com.avoqado.pos.inventory.data.model.InventoryTransfer
import com.avoqado.pos.inventory.data.model.PurchaseOrder
import com.avoqado.pos.inventory.data.model.PurchaseOrderItem
import com.avoqado.pos.inventory.data.model.PurchaseOrdersResponse
import com.avoqado.pos.inventory.data.model.CreateStockCountResponse
import com.avoqado.pos.inventory.data.model.StockCount
import com.avoqado.pos.inventory.data.model.StockCountItem
import com.avoqado.pos.inventory.data.model.StockCountType
import com.avoqado.pos.inventory.data.model.StockCountsResponse
import com.avoqado.pos.inventory.data.model.StockItem
import com.avoqado.pos.inventory.data.model.StockOverviewResponse
import com.avoqado.pos.inventory.data.model.Supplier
import com.avoqado.pos.inventory.data.model.SuppliersResponse
import com.avoqado.pos.inventory.data.model.TransferItem
import com.avoqado.pos.inventory.data.model.TransfersResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

// MARK: - API Request Models

@Serializable
data class CreatePurchaseOrderRequest(
    val supplierName: String,
    val items: List<CreatePOItemRequest>,
    val notes: String? = null,
    val expectedDeliveryDate: String? = null,
)

@Serializable
data class CreatePOItemRequest(
    val productId: String,
    val productName: String,
    val orderedQuantity: Int,
    val unitCost: Double? = null,
)

@Serializable
private data class UpdateStatusRequest(
    val status: String,
)

@Serializable
data class ReceiveItemRequest(
    val purchaseOrderItemId: String,
    val receivedQuantity: Int,
)

@Serializable
private data class ReceivePORequest(
    val items: List<ReceiveItemRequest>,
)

@Serializable
data class CreateTransferRequest(
    val fromLocationName: String,
    val toLocationName: String,
    val items: List<CreateTransferItemRequest>,
    val notes: String? = null,
)

@Serializable
data class CreateTransferItemRequest(
    val productId: String,
    val productName: String,
    val quantity: Int,
    val unit: String? = null,
)

@Singleton
class InventoryRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
    private val purchaseOrderDao: PurchaseOrderDao,
    private val inventoryTransferDao: InventoryTransferDao,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private val _stockItems = MutableStateFlow<List<StockItem>>(emptyList())
    val stockItems: StateFlow<List<StockItem>> = _stockItems.asStateFlow()

    private val _stockCounts = MutableStateFlow<List<StockCount>>(emptyList())
    val stockCounts: StateFlow<List<StockCount>> = _stockCounts.asStateFlow()

    private val _purchaseOrders = MutableStateFlow<List<PurchaseOrder>>(emptyList())
    val purchaseOrders: StateFlow<List<PurchaseOrder>> = _purchaseOrders.asStateFlow()

    private val _transfers = MutableStateFlow<List<InventoryTransfer>>(emptyList())
    val transfers: StateFlow<List<InventoryTransfer>> = _transfers.asStateFlow()

    private val _suppliers = MutableStateFlow<List<Supplier>>(emptyList())
    val suppliers: StateFlow<List<Supplier>> = _suppliers.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun clearCache() {
        _stockItems.value = emptyList()
        _stockCounts.value = emptyList()
        _purchaseOrders.value = emptyList()
        _transfers.value = emptyList()
        _suppliers.value = emptyList()
        _isLoading.value = false
    }

    private fun venueBaseUrl(): String? {
        val venueId = secureStorage.venueId ?: return null
        return "${ApiConstants.BASE_URL}/mobile/venues/$venueId"
    }

    // MARK: - Stock Overview

    suspend fun fetchStockOverview() {
        val base = venueBaseUrl() ?: return
        _isLoading.value = true

        try {
            val request = Request.Builder()
                .url("$base/inventory/stock-overview")
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }
            if (responseCode in 200..299 && body.isNotEmpty()) {
                val result = json.decodeFromString<StockOverviewResponse>(body)
                _stockItems.value = result.items
                Log.d("📦", "✅ Loaded ${result.items.size} stock items")
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Stock overview fetch error: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }

    // MARK: - Stock Counts

    suspend fun fetchStockCounts() {
        val base = venueBaseUrl() ?: return

        try {
            val request = Request.Builder()
                .url("$base/inventory/stock-counts")
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }
            if (responseCode in 200..299 && body.isNotEmpty()) {
                val result = json.decodeFromString<StockCountsResponse>(body)
                _stockCounts.value = result.counts
                Log.d("📦", "✅ Loaded ${result.counts.size} stock counts")
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Stock counts fetch error: ${e.message}")
        }
    }

    // MARK: - Create Stock Count

    suspend fun createStockCount(type: StockCountType, productIds: List<String>? = null): Result<StockCount> {
        val base = venueBaseUrl() ?: return Result.failure(Exception("No venue"))

        return try {
            val bodyJson = buildString {
                append("{\"type\":\"${type.name}\"")
                if (productIds != null && productIds.isNotEmpty()) {
                    append(",\"productIds\":[${productIds.joinToString(",") { "\"$it\"" }}]")
                }
                // Opt-in: FULL counts also list ingredients (raw materials).
                // Old servers ignore the flag.
                if (type == StockCountType.FULL) {
                    append(",\"includeRawMaterials\":true")
                }
                append("}")
            }

            val request = Request.Builder()
                .url("$base/inventory/stock-counts")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299 && body.isNotEmpty()) {
                val result = json.decodeFromString<CreateStockCountResponse>(body)
                val count = result.count ?: return Result.failure(Exception("No count in response"))
                Log.d("📦", "✅ Stock count created: ${count.id} (${count.items.size} items)")
                Result.success(count)
            } else {
                Log.e("📦", "❌ Create stock count failed ($code): $body")
                Result.failure(Exception("Error al crear conteo ($code)"))
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Create stock count error: ${e.message}")
            Result.failure(e)
        }
    }

    // MARK: - Update Stock Count

    suspend fun updateStockCount(
        countId: String,
        items: List<StockCountItem>,
        note: String? = null,
    ): Result<Unit> {
        val base = venueBaseUrl() ?: return Result.failure(Exception("No venue"))

        return try {
            val itemsJson = items.joinToString(",") { item ->
                "{\"id\":\"${item.id}\",\"counted\":${item.counted}}"
            }
            val bodyJson = buildString {
                append("{\"items\":[$itemsJson]")
                if (!note.isNullOrBlank()) {
                    append(",\"note\":\"${note.replace("\"", "\\\"")}\"")
                }
                append("}")
            }

            val request = Request.Builder()
                .url("$base/inventory/stock-counts/$countId")
                .put(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("📦", "✅ Stock count updated: $countId")
                Result.success(Unit)
            } else {
                Log.e("📦", "❌ Update stock count failed ($code): $body")
                Result.failure(Exception("Error al actualizar conteo ($code)"))
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Update stock count error: ${e.message}")
            Result.failure(e)
        }
    }

    // MARK: - Confirm Stock Count

    suspend fun confirmStockCount(countId: String): Result<Unit> {
        val base = venueBaseUrl() ?: return Result.failure(Exception("No venue"))

        return try {
            val request = Request.Builder()
                .url("$base/inventory/stock-counts/$countId/confirm")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("📦", "✅ Stock count confirmed: $countId")
                Result.success(Unit)
            } else {
                Log.e("📦", "❌ Confirm stock count failed ($code): $body")
                Result.failure(Exception("Error al confirmar conteo ($code)"))
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Confirm stock count error: ${e.message}")
            Result.failure(e)
        }
    }

    // MARK: - Purchase Orders

    suspend fun fetchPurchaseOrders() {
        val base = venueBaseUrl() ?: return
        val venueId = secureStorage.venueId ?: return

        try {
            val request = Request.Builder()
                .url("$base/purchase-orders")
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }
            Log.d("📦", "PO response: HTTP $responseCode, body length=${body.length}")
            if (responseCode in 200..299 && body.isNotEmpty()) {
                val result = json.decodeFromString<PurchaseOrdersResponse>(body)
                val orders = result.resolved
                _purchaseOrders.value = orders

                // Cache to Room
                val entities = orders.map { it.toEntity() }
                purchaseOrderDao.deleteForVenue(venueId)
                purchaseOrderDao.insertAll(entities)
                Log.d("📦", "✅ Loaded ${orders.size} purchase orders")
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Purchase orders fetch error: ${e.message}")
            // Fall back to cached data
            loadCachedPurchaseOrders()
        }
    }

    /**
     * Create a new purchase order.
     * @param supplierName Supplier name
     * @param items List of items with productId, productName, orderedQuantity, unitCost
     * @param notes Optional notes
     * @param expectedDeliveryDate Optional expected delivery date (ISO-8601 string)
     * @return The created PurchaseOrder, or null on failure
     */
    suspend fun createPurchaseOrder(
        supplierName: String,
        items: List<CreatePOItemRequest>,
        notes: String? = null,
        expectedDeliveryDate: String? = null,
    ): Result<PurchaseOrder> {
        val base = venueBaseUrl()
            ?: return Result.failure(Exception("No venue selected"))

        return try {
            val requestBody = json.encodeToString(
                CreatePurchaseOrderRequest.serializer(),
                CreatePurchaseOrderRequest(
                    supplierName = supplierName,
                    items = items,
                    notes = notes,
                    expectedDeliveryDate = expectedDeliveryDate,
                ),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$base/purchase-orders")
                .post(requestBody)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299 && body.isNotEmpty()) {
                // Response is wrapped: { success: true, data: { ... } }
                val jsonObj = org.json.JSONObject(body)
                val dataObj = jsonObj.optJSONObject("data") ?: jsonObj

                val notes = if (dataObj.has("notes") && !dataObj.isNull("notes")) {
                    dataObj.getString("notes")
                } else {
                    null
                }
                val expectedDate = if (dataObj.has("expectedDeliveryDate") && !dataObj.isNull("expectedDeliveryDate")) {
                    dataObj.getString("expectedDeliveryDate")
                } else {
                    null
                }

                val po = PurchaseOrder(
                    id = dataObj.getString("id"),
                    venueId = dataObj.getString("venueId"),
                    supplierName = dataObj.optJSONObject("supplier")?.optString("name")
                        ?: dataObj.optString("supplierName", ""),
                    status = dataObj.optString("status", "DRAFT"),
                    notes = notes,
                    expectedDate = expectedDate,
                    createdAt = dataObj.optString("orderDate", dataObj.optString("createdAt", "")),
                    createdByName = dataObj.optString("createdByName", ""),
                )

                // Cache to Room
                purchaseOrderDao.insert(po.toEntity())
                // Refresh the list
                fetchPurchaseOrders()
                Log.d("📦", "✅ Purchase order created: ${po.id}")
                Result.success(po)
            } else {
                Log.e("📦", "❌ Create PO failed: $code - $body")
                Result.failure(Exception("Error al crear orden de compra ($code)"))
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Create PO error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Update the status of a purchase order.
     * @param poId Purchase order ID
     * @param status New status (DRAFT, SENT, PARTIALLY_RECEIVED, RECEIVED, CANCELLED)
     */
    suspend fun updatePurchaseOrderStatus(poId: String, status: String): Result<Unit> {
        val base = venueBaseUrl()
            ?: return Result.failure(Exception("No venue selected"))

        return try {
            val requestBody = json.encodeToString(
                UpdateStatusRequest.serializer(),
                UpdateStatusRequest(status = status),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$base/purchase-orders/$poId/status")
                .put(requestBody)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("📦", "✅ PO status updated: $poId -> $status")
                // Refresh the list to get the latest state
                fetchPurchaseOrders()
                Result.success(Unit)
            } else {
                Log.e("📦", "❌ Update PO status failed: $code - $body")
                Result.failure(Exception("Error al actualizar estado ($code)"))
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Update PO status error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Receive items for a purchase order.
     * @param poId Purchase order ID
     * @param items List of items with purchaseOrderItemId and receivedQuantity
     */
    suspend fun receivePurchaseOrder(
        poId: String,
        items: List<ReceiveItemRequest>,
    ): Result<Unit> {
        val base = venueBaseUrl()
            ?: return Result.failure(Exception("No venue selected"))

        return try {
            val requestBody = json.encodeToString(
                ReceivePORequest.serializer(),
                ReceivePORequest(items = items),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$base/purchase-orders/$poId/receive")
                .post(requestBody)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("📦", "✅ PO items received: $poId")
                // Refresh the list to get the latest state
                fetchPurchaseOrders()
                Result.success(Unit)
            } else {
                Log.e("📦", "❌ Receive PO failed: $code - $body")
                val message = extractApiMessage(
                    body = body,
                    fallback = "Error al recibir mercancía ($code)",
                )
                Result.failure(Exception(message))
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Receive PO error: ${e.message}")
            Result.failure(e)
        }
    }

    private suspend fun loadCachedPurchaseOrders() {
        val venueId = secureStorage.venueId ?: return
        try {
            purchaseOrderDao.getByVenue(venueId).collect { entities ->
                _purchaseOrders.value = entities.map { it.toDomain() }
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Cache read error: ${e.message}")
        }
    }

    // MARK: - Inventory Transfers

    suspend fun fetchTransfers() {
        val base = venueBaseUrl() ?: return
        val venueId = secureStorage.venueId ?: return

        try {
            val request = Request.Builder()
                .url("$base/transfers")
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }
            Log.d("📦", "Transfer response: HTTP $responseCode, body length=${body.length}")
            if (responseCode in 200..299 && body.isNotEmpty()) {
                val result = json.decodeFromString<TransfersResponse>(body)
                val transferList = result.resolved
                _transfers.value = transferList

                // Cache to Room
                val entities = transferList.map { it.toEntity() }
                inventoryTransferDao.deleteForVenue(venueId)
                inventoryTransferDao.insertAll(entities)
                Log.d("📦", "✅ Loaded ${transferList.size} transfers")
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Transfers fetch error: ${e.message}")
            // Fall back to cached data
            loadCachedTransfers()
        }
    }

    /**
     * Create a new inventory transfer.
     * @param fromLocationName Source location name
     * @param toLocationName Destination location name
     * @param items List of transfer items
     * @param notes Optional notes
     * @return The created InventoryTransfer, or failure
     */
    suspend fun createTransfer(
        fromLocationName: String,
        toLocationName: String,
        items: List<CreateTransferItemRequest>,
        notes: String? = null,
    ): Result<InventoryTransfer> {
        val base = venueBaseUrl()
            ?: return Result.failure(Exception("No venue selected"))

        return try {
            val requestBody = json.encodeToString(
                CreateTransferRequest.serializer(),
                CreateTransferRequest(
                    fromLocationName = fromLocationName,
                    toLocationName = toLocationName,
                    items = items,
                    notes = notes,
                ),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$base/transfers")
                .post(requestBody)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299 && body.isNotEmpty()) {
                val transfer = json.decodeFromString<InventoryTransfer>(body)
                // Cache to Room
                inventoryTransferDao.insert(transfer.toEntity())
                // Refresh the list
                fetchTransfers()
                Log.d("📦", "✅ Transfer created: ${transfer.id}")
                Result.success(transfer)
            } else {
                Log.e("📦", "❌ Create transfer failed: $code - $body")
                Result.failure(Exception("Error al crear transferencia ($code)"))
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Create transfer error: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Update the status of an inventory transfer.
     * @param transferId Transfer ID
     * @param status New status (DRAFT, IN_TRANSIT, COMPLETED, CANCELLED)
     */
    suspend fun updateTransferStatus(transferId: String, status: String): Result<Unit> {
        val base = venueBaseUrl()
            ?: return Result.failure(Exception("No venue selected"))

        return try {
            val requestBody = json.encodeToString(
                UpdateStatusRequest.serializer(),
                UpdateStatusRequest(status = status),
            ).toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$base/transfers/$transferId/status")
                .put(requestBody)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                Log.d("📦", "✅ Transfer status updated: $transferId -> $status")
                // Refresh the list to get the latest state
                fetchTransfers()
                Result.success(Unit)
            } else {
                Log.e("📦", "❌ Update transfer status failed: $code - $body")
                Result.failure(Exception("Error al actualizar estado ($code)"))
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Update transfer status error: ${e.message}")
            Result.failure(e)
        }
    }

    private suspend fun loadCachedTransfers() {
        val venueId = secureStorage.venueId ?: return
        try {
            inventoryTransferDao.getByVenue(venueId).collect { entities ->
                _transfers.value = entities.map { it.toDomain() }
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Cache read error: ${e.message}")
        }
    }

    // MARK: - Suppliers

    suspend fun fetchSuppliers() {
        val venueId = secureStorage.venueId ?: return
        val token = secureStorage.accessToken ?: return

        try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/suppliers?active=true")
                .header("Authorization", "Bearer $token")
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }
            Log.d("📦", "Suppliers response: HTTP $responseCode, body length=${body.length}")
            if (responseCode == 404) {
                // Some venues/environments may not expose suppliers yet.
                _suppliers.value = emptyList()
                Log.d("📦", "ℹ️ Suppliers endpoint not available for this venue")
                return
            }
            if (responseCode in 200..299 && body.isNotEmpty()) {
                // Backend may return {"data": [...]} or bare array [...]
                val suppliers = try {
                    json.decodeFromString<SuppliersResponse>(body).data
                } catch (_: Exception) {
                    json.decodeFromString<List<Supplier>>(body)
                }
                _suppliers.value = suppliers
                Log.d("📦", "✅ Loaded ${suppliers.size} suppliers")
            } else if (responseCode !in 200..299) {
                Log.w("📦", "⚠️ Suppliers fetch failed: HTTP $responseCode")
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Suppliers fetch error: ${e.message}")
        }
    }

    private fun extractApiMessage(body: String, fallback: String): String {
        if (body.isBlank()) return fallback
        return try {
            val obj = org.json.JSONObject(body)
            obj.optString("message").takeIf { it.isNotBlank() }
                ?: obj.optString("error").takeIf { it.isNotBlank() }
                ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }

    // MARK: - Entity Mapping

    private fun PurchaseOrder.toEntity(): PurchaseOrderEntity {
        return PurchaseOrderEntity(
            id = id,
            venueId = venueId,
            supplierName = supplierName,
            status = status,
            notes = notes,
            expectedDate = expectedDate,
            itemsJson = json.encodeToString(items),
            createdAt = createdAt,
            createdByName = createdByName,
        )
    }

    private fun PurchaseOrderEntity.toDomain(): PurchaseOrder {
        val items: List<PurchaseOrderItem> = itemsJson?.let {
            try {
                json.decodeFromString(it)
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()

        return PurchaseOrder(
            id = id,
            venueId = venueId,
            supplierName = supplierName,
            status = status,
            notes = notes,
            expectedDate = expectedDate,
            items = items,
            createdAt = createdAt,
            createdByName = createdByName,
        )
    }

    private fun InventoryTransfer.toEntity(): InventoryTransferEntity {
        return InventoryTransferEntity(
            id = id,
            venueId = venueId,
            fromLocationName = fromLocationName,
            toLocationName = toLocationName,
            status = status,
            notes = notes,
            itemsJson = json.encodeToString(items),
            createdAt = createdAt,
            createdByName = createdByName,
        )
    }

    private fun InventoryTransferEntity.toDomain(): InventoryTransfer {
        val items: List<TransferItem> = itemsJson?.let {
            try {
                json.decodeFromString(it)
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()

        return InventoryTransfer(
            id = id,
            venueId = venueId,
            fromLocationName = fromLocationName,
            toLocationName = toLocationName,
            status = status,
            notes = notes,
            items = items,
            createdAt = createdAt,
            createdByName = createdByName,
        )
    }
}
