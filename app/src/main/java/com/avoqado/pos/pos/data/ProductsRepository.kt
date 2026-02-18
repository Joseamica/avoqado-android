package com.avoqado.pos.pos.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.pos.data.model.CategoriesResponse
import com.avoqado.pos.pos.data.model.CreateProductRequest
import com.avoqado.pos.pos.data.model.CreateProductResponse
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import com.avoqado.pos.pos.data.model.ProductsResponse
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

@Singleton
class ProductsRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products.asStateFlow()

    private val _categories = MutableStateFlow<List<ProductCategory>>(emptyList())
    val categories: StateFlow<List<ProductCategory>> = _categories.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    suspend fun fetchProducts(venueId: String? = null) {
        val venue = venueId ?: secureStorage.venueId ?: return
        _isLoading.value = true
        _error.value = null
        Log.d("📦", "Fetching products for venue: $venue")

        try {
            val token = secureStorage.accessToken ?: return
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/dashboard/venues/$venue/products")
                .header("Authorization", "Bearer $token")
                .build()

            val response = withContext(Dispatchers.IO) {
                client.newCall(request).execute()
            }
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return
                val result = json.decodeFromString<ProductsResponse>(body)
                val activeProducts = result.data.filter { it.active != false }
                _products.value = activeProducts

                // Extract unique categories
                val cats = activeProducts
                    .mapNotNull { it.category }
                    .distinctBy { it.id }
                    .sortedBy { it.sortOrder ?: 0 }
                _categories.value = cats

                Log.d("📦", "✅ Loaded ${activeProducts.size} products, ${cats.size} categories")
            } else {
                _error.value = "Error al cargar productos (${response.code})"
                Log.e("📦", "❌ Products fetch failed: ${response.code}")
            }
        } catch (e: Exception) {
            _error.value = "Error de conexión al cargar productos"
            Log.e("📦", "❌ Products fetch error: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }

    fun getProduct(productId: String): Product? {
        return _products.value.find { it.id == productId }
    }

    fun getProductsByCategory(categoryId: String): List<Product> {
        return _products.value.filter { it.categoryId == categoryId }
    }

    fun searchProducts(query: String): List<Product> {
        if (query.isBlank()) return _products.value
        val lower = query.lowercase()
        return _products.value.filter {
            it.name.lowercase().contains(lower) ||
                it.sku?.lowercase()?.contains(lower) == true ||
                it.description?.lowercase()?.contains(lower) == true
        }
    }

    // MARK: - Fetch menu categories (GET /dashboard/venues/{id}/menucategories)

    suspend fun fetchMenuCategories(): List<ProductCategory> {
        val venue = secureStorage.venueId ?: return emptyList()

        return try {
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/dashboard/venues/$venue/menucategories")
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                val result = json.decodeFromString<CategoriesResponse>(body)
                Log.d("📦", "Fetched ${result.data.size} menu categories")
                result.data
            } else {
                Log.e("📦", "Menu categories fetch failed: $code")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("📦", "Menu categories error: ${e.message}")
            emptyList()
        }
    }

    // MARK: - Create product (POST /dashboard/venues/{id}/products)

    suspend fun createProduct(request: CreateProductRequest): Result<Product> {
        val venue = secureStorage.venueId ?: return Result.failure(Exception("No venue selected"))

        return try {
            val requestBody = json.encodeToString(CreateProductRequest.serializer(), request)
                .toRequestBody("application/json".toMediaType())

            val httpRequest = Request.Builder()
                .url("${ApiConstants.BASE_URL}/dashboard/venues/$venue/products")
                .post(requestBody)
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(httpRequest).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                val result = json.decodeFromString<CreateProductResponse>(body)
                val product = result.data
                if (product != null) {
                    // Add to local products list
                    _products.value = _products.value + product
                    Log.d("📦", "Product created: ${product.name}")
                    Result.success(product)
                } else {
                    Result.failure(Exception("Respuesta invalida del servidor"))
                }
            } else {
                Log.e("📦", "Create product failed: $code - $body")
                Result.failure(Exception("Error al crear producto ($code)"))
            }
        } catch (e: Exception) {
            Log.e("📦", "Create product error: ${e.message}")
            Result.failure(e)
        }
    }

    fun clearCache() {
        _products.value = emptyList()
        _categories.value = emptyList()
    }
}
