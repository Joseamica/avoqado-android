package com.avoqado.pos.inventory.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.inventory.data.model.StockCount
import com.avoqado.pos.inventory.data.model.StockCountsResponse
import com.avoqado.pos.inventory.data.model.StockItem
import com.avoqado.pos.inventory.data.model.StockOverviewResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InventoryRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _stockItems = MutableStateFlow<List<StockItem>>(emptyList())
    val stockItems: StateFlow<List<StockItem>> = _stockItems.asStateFlow()

    private val _stockCounts = MutableStateFlow<List<StockCount>>(emptyList())
    val stockCounts: StateFlow<List<StockCount>> = _stockCounts.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    suspend fun fetchStockOverview() {
        val venueId = secureStorage.venueId ?: return
        val token = secureStorage.accessToken ?: return
        _isLoading.value = true

        try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/inventory")
                .header("Authorization", "Bearer $token")
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }
            if (responseCode in 200..299 && body.isNotEmpty()) {
                val result = json.decodeFromString<StockOverviewResponse>(body)
                _stockItems.value = result.data
                Log.d("📦", "✅ Loaded ${result.data.size} stock items")
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Stock overview fetch error: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun fetchStockCounts() {
        val venueId = secureStorage.venueId ?: return
        val token = secureStorage.accessToken ?: return

        try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venueId/inventory/counts")
                .header("Authorization", "Bearer $token")
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }
            if (responseCode in 200..299 && body.isNotEmpty()) {
                val result = json.decodeFromString<StockCountsResponse>(body)
                _stockCounts.value = result.data
                Log.d("📦", "✅ Loaded ${result.data.size} stock counts")
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Stock counts fetch error: ${e.message}")
        }
    }
}
