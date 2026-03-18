package com.avoqado.pos.articles.data

import android.util.Log
import com.avoqado.pos.articles.data.model.AdminCoupon
import com.avoqado.pos.articles.data.model.AdminDiscount
import com.avoqado.pos.articles.data.model.ArticleCategory
import com.avoqado.pos.articles.data.model.ArticleProduct
import com.avoqado.pos.articles.data.model.CreditPack
import com.avoqado.pos.articles.data.model.ModifierGroup
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "📦 ArticlesRepo"
private val JSON_MEDIA = "application/json".toMediaType()

@Singleton
class ArticlesRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    // MARK: - State Flows

    private val _products = MutableStateFlow<List<ArticleProduct>>(emptyList())
    val products: StateFlow<List<ArticleProduct>> = _products.asStateFlow()

    private val _categories = MutableStateFlow<List<ArticleCategory>>(emptyList())
    val categories: StateFlow<List<ArticleCategory>> = _categories.asStateFlow()

    private val _modifierGroups = MutableStateFlow<List<ModifierGroup>>(emptyList())
    val modifierGroups: StateFlow<List<ModifierGroup>> = _modifierGroups.asStateFlow()

    private val _discounts = MutableStateFlow<List<AdminDiscount>>(emptyList())
    val discounts: StateFlow<List<AdminDiscount>> = _discounts.asStateFlow()

    private val _coupons = MutableStateFlow<List<AdminCoupon>>(emptyList())
    val coupons: StateFlow<List<AdminCoupon>> = _coupons.asStateFlow()

    private val _creditPacks = MutableStateFlow<List<CreditPack>>(emptyList())
    val creditPacks: StateFlow<List<CreditPack>> = _creditPacks.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // MARK: - Helpers

    private fun baseUrl(): String {
        val venueId = secureStorage.venueId ?: ""
        return "${ApiConstants.BASE_URL}/dashboard/venues/$venueId"
    }

    private suspend fun executeRequest(request: Request): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            response.code to (response.body?.string() ?: "")
        }

    // MARK: - Products

    suspend fun fetchProducts() {
        val venueId = secureStorage.venueId ?: return
        _isLoading.value = true

        try {
            val request = Request.Builder()
                .url("${baseUrl()}/products")
                .get()
                .build()

            val (code, body) = executeRequest(request)
            if (code in 200..299 && body.isNotEmpty()) {
                val list = json.decodeFromString<List<ArticleProduct>>(body)
                _products.value = list
                Log.d(TAG, "✅ Loaded ${list.size} products for venue $venueId")
            } else {
                Log.e(TAG, "❌ fetchProducts error: HTTP $code")
                _errorMessage.value = "Error al cargar productos"
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchProducts exception: ${e.message}", e)
            _errorMessage.value = "Error al cargar productos"
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun createProduct(productJson: String): Boolean {
        return try {
            val body = productJson.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("${baseUrl()}/products")
                .post(body)
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Product created")
                fetchProducts()
                true
            } else {
                Log.e(TAG, "❌ createProduct error: HTTP $code")
                _errorMessage.value = "Error al crear producto"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ createProduct exception: ${e.message}", e)
            _errorMessage.value = "Error al crear producto"
            false
        }
    }

    suspend fun updateProduct(productId: String, productJson: String): Boolean {
        return try {
            val body = productJson.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("${baseUrl()}/products/$productId")
                .put(body)
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Product $productId updated")
                fetchProducts()
                true
            } else {
                Log.e(TAG, "❌ updateProduct error: HTTP $code")
                _errorMessage.value = "Error al actualizar producto"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ updateProduct exception: ${e.message}", e)
            _errorMessage.value = "Error al actualizar producto"
            false
        }
    }

    suspend fun deleteProduct(productId: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/products/$productId")
                .delete()
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Product $productId deleted")
                fetchProducts()
                true
            } else {
                Log.e(TAG, "❌ deleteProduct error: HTTP $code")
                _errorMessage.value = "Error al eliminar producto"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ deleteProduct exception: ${e.message}", e)
            _errorMessage.value = "Error al eliminar producto"
            false
        }
    }

    // MARK: - Categories

    suspend fun fetchCategories() {
        val venueId = secureStorage.venueId ?: return
        _isLoading.value = true

        try {
            val request = Request.Builder()
                .url("${baseUrl()}/menucategories")
                .get()
                .build()

            val (code, body) = executeRequest(request)
            if (code in 200..299 && body.isNotEmpty()) {
                val list = json.decodeFromString<List<ArticleCategory>>(body)
                _categories.value = list
                Log.d(TAG, "✅ Loaded ${list.size} categories for venue $venueId")
            } else {
                Log.e(TAG, "❌ fetchCategories error: HTTP $code")
                _errorMessage.value = "Error al cargar categorías"
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchCategories exception: ${e.message}", e)
            _errorMessage.value = "Error al cargar categorías"
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun createCategory(categoryJson: String): Boolean {
        return try {
            val body = categoryJson.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("${baseUrl()}/menucategories")
                .post(body)
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Category created")
                fetchCategories()
                true
            } else {
                Log.e(TAG, "❌ createCategory error: HTTP $code")
                _errorMessage.value = "Error al crear categoría"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ createCategory exception: ${e.message}", e)
            _errorMessage.value = "Error al crear categoría"
            false
        }
    }

    suspend fun updateCategory(categoryId: String, categoryJson: String): Boolean {
        return try {
            val body = categoryJson.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("${baseUrl()}/menucategories/$categoryId")
                .patch(body)
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Category $categoryId updated")
                fetchCategories()
                true
            } else {
                Log.e(TAG, "❌ updateCategory error: HTTP $code")
                _errorMessage.value = "Error al actualizar categoría"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ updateCategory exception: ${e.message}", e)
            _errorMessage.value = "Error al actualizar categoría"
            false
        }
    }

    suspend fun deleteCategory(categoryId: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/menucategories/$categoryId")
                .delete()
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Category $categoryId deleted")
                fetchCategories()
                true
            } else {
                Log.e(TAG, "❌ deleteCategory error: HTTP $code")
                _errorMessage.value = "Error al eliminar categoría"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ deleteCategory exception: ${e.message}", e)
            _errorMessage.value = "Error al eliminar categoría"
            false
        }
    }
}
