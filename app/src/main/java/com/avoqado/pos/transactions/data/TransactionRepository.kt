package com.avoqado.pos.transactions.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.transactions.data.model.PaginationMeta
import com.avoqado.pos.transactions.data.model.Transaction
import com.avoqado.pos.transactions.data.model.TransactionDetailResponse
import com.avoqado.pos.transactions.data.model.TransactionsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
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
                "${ApiConstants.BASE_URL}/mobile/venues/$venueId/transactions?page=$page&pageSize=$pageSize",
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

    suspend fun fetchTransactionDetail(paymentId: String): Transaction? {
        val venueId = secureStorage.venueId ?: return null
        val token = secureStorage.accessToken ?: return null

        return try {
            val url = "${ApiConstants.BASE_URL}/mobile/venues/$venueId/transactions/$paymentId"
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .build()

            val (responseCode, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (responseCode in 200..299 && body.isNotEmpty()) {
                val result = json.decodeFromString<TransactionDetailResponse>(body)
                Log.d("📦", "✅ Loaded transaction detail: $paymentId")
                result.transaction
            } else {
                Log.e("📦", "❌ Transaction detail fetch failed: $responseCode")
                null
            }
        } catch (e: Exception) {
            Log.e("📦", "❌ Transaction detail fetch error: ${e.message}")
            null
        }
    }

    fun canLoadMore(): Boolean = hasMore && !_isLoadingMore.value

    fun getNextPage(): Int = currentPage + 1

    fun clearCache() {
        _transactions.value = emptyList()
        currentPage = 1
        hasMore = true
    }
}
