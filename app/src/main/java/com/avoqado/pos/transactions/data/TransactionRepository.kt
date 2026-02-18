package com.avoqado.pos.transactions.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.data.model.TransactionsResponse
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
class TransactionRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _transactions = MutableStateFlow<List<Transaction>>(emptyList())
    val transactions: StateFlow<List<Transaction>> = _transactions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    suspend fun fetchTransactions(
        page: Int = 1,
        startDate: String? = null,
        endDate: String? = null,
    ) {
        val venueId = secureStorage.venueId ?: return
        val token = secureStorage.accessToken ?: return
        _isLoading.value = true

        try {
            var url = "${ApiConstants.BASE_URL}/mobile/venues/$venueId/orders?page=$page&pageSize=50"
            if (startDate != null) url += "&startDate=$startDate"
            if (endDate != null) url += "&endDate=$endDate"

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }
            if (responseCode in 200..299 && body.isNotEmpty()) {
                val result = json.decodeFromString<TransactionsResponse>(body)
                _transactions.value = if (page == 1) result.data else _transactions.value + result.data
                Log.d("📦", "✅ Loaded ${result.data.size} transactions (page $page)")
            } else {
                Log.e("📦", "❌ Transactions fetch failed: $responseCode")
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Transactions fetch error: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }

    fun clearCache() {
        _transactions.value = emptyList()
    }
}
