package com.avoqado.pos.pos.data

import android.util.Log
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiConstants
import com.avoqado.pos.core.data.network.ServerErrorText
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
    private val payloadCache: com.avoqado.pos.core.data.local.PayloadCache,
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

    /**
     * Offline-first (Corte A): hidrata productos/categorías desde el espejo en
     * disco. Instantáneo y sin red — el fetch de red lo refresca después. Solo
     * pisa el estado si está vacío (nunca degrada datos frescos a cache viejo).
     */
    private suspend fun hydrateFromCache(venue: String) {
        if (_products.value.isNotEmpty()) return
        val cached = payloadCache.load(com.avoqado.pos.core.data.local.PayloadCache.TYPE_PRODUCTS, venue) ?: return
        runCatching {
            val products = json.decodeFromString<List<Product>>(cached.json)
            if (products.isNotEmpty() && _products.value.isEmpty()) {
                _products.value = products
                _categories.value = products
                    .mapNotNull { it.category }
                    .distinctBy { it.id }
                    .sortedBy { it.sortOrder ?: 0 }
                Log.d("📦", "🗂️ Catálogo hidratado del cache: ${products.size} productos (hace ${cached.ageMinutes} min)")
            }
        }.onFailure { Log.e("📦", "❌ Cache de productos corrupto: ${it.message}") }
    }

    suspend fun fetchProducts(venueId: String? = null) {
        val venue = venueId ?: secureStorage.venueId ?: return
        // Cache primero: la UI pinta al instante aunque no haya red.
        hydrateFromCache(venue)
        _isLoading.value = true
        _error.value = null
        Log.d("📦", "Fetching products for venue: $venue")

        try {
            val token = secureStorage.accessToken ?: return
            val request = Request.Builder()
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venue/products")
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

                // Espejo en disco: el próximo arranque sin red carga esto.
                payloadCache.save(
                    com.avoqado.pos.core.data.local.PayloadCache.TYPE_PRODUCTS,
                    venue,
                    json.encodeToString(
                        kotlinx.serialization.builtins.ListSerializer(Product.serializer()),
                        activeProducts,
                    ),
                )

                Log.d("📦", "✅ Loaded ${activeProducts.size} products, ${cats.size} categories")
            } else {
                _error.value = "Error al cargar productos (${response.code})"
                Log.e("📦", "❌ Products fetch failed: ${response.code}")
            }
        } catch (e: Exception) {
            // Sin red: si el cache ya hidrató, el catálogo sigue completo y NO
            // se muestra error de pantalla — solo queda el log.
            if (_products.value.isNotEmpty()) {
                Log.w("📦", "⚠️ Sin red, operando con catálogo cacheado (${_products.value.size} productos)")
            } else {
                _error.value = "Error de conexión al cargar productos"
            }
            Log.e("📦", "❌ Products fetch error: ${e.message}")
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Offline-first Corte E: una venta SIN red descuenta el stock local de los
     * productos con seguimiento — la aproximación "~X restantes" se mantiene
     * honesta entre syncs (el conteo EXACTO lo recalcula el server al
     * reconciliar; el próximo fetch pisa esta aproximación). También se
     * persiste al cache para que un reinicio offline no la pierda.
     */
    suspend fun applyLocalSale(items: List<Pair<String, Int>>) {
        if (items.isEmpty()) return
        val sold = items.groupBy({ it.first }, { it.second }).mapValues { (_, v) -> v.sum() }
        _products.value = _products.value.map { p ->
            val qty = sold[p.id] ?: return@map p
            if (p.trackInventory == true && p.availableQuantity != null) {
                p.copy(availableQuantity = p.availableQuantity - qty)
            } else {
                p
            }
        }
        secureStorage.venueId?.let { venue ->
            payloadCache.save(
                com.avoqado.pos.core.data.local.PayloadCache.TYPE_PRODUCTS,
                venue,
                json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(Product.serializer()),
                    _products.value,
                ),
            )
        }
        Log.d("📦", "📴 Stock local descontado: ${sold.size} producto(s)")
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
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venue/categories")
                .build()

            val (code, body) = withContext(Dispatchers.IO) {
                val response = client.newCall(request).execute()
                response.code to (response.body?.string() ?: "")
            }

            if (code in 200..299) {
                // Backend may return either {"data": [...]} or a bare array [...]
                val categories = try {
                    val result = json.decodeFromString<CategoriesResponse>(body)
                    result.data
                } catch (_: Exception) {
                    json.decodeFromString<List<ProductCategory>>(body)
                }
                Log.d("📦", "Fetched ${categories.size} menu categories")
                categories
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
                .url("${ApiConstants.BASE_URL}/mobile/venues/$venue/products")
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
                Result.failure(
                    Exception(
                        ServerErrorText.fromResponseBody(body, "Error al crear producto ($code)"),
                    ),
                )
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
