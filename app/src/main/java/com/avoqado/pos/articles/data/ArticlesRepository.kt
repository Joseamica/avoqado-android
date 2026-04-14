package com.avoqado.pos.articles.data

import android.util.Log
import com.avoqado.pos.articles.data.model.AdminCoupon
import com.avoqado.pos.articles.data.model.AdminDiscount
import com.avoqado.pos.articles.data.model.ArticleCategory
import com.avoqado.pos.articles.data.model.ArticleProduct
import com.avoqado.pos.articles.data.model.CreditPack
import com.avoqado.pos.articles.data.model.ModifierGroup
import com.avoqado.pos.articles.data.model.ProductOption
import com.avoqado.pos.articles.data.model.ProductOptionsListResponse
import com.avoqado.pos.articles.data.model.RawMaterial
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

    private val _productOptions = MutableStateFlow<List<ProductOption>>(emptyList())
    val productOptions: StateFlow<List<ProductOption>> = _productOptions.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

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

    // MARK: - Products

    suspend fun fetchProducts() {
        val url = baseUrl() ?: return
        _isLoading.value = true

        try {
            val request = Request.Builder()
                .url("$url/products")
                .get()
                .build()

            val (code, body) = executeRequest(request)
            if (code in 200..299 && body.isNotEmpty()) {
                val list = json.decodeFromString<List<ArticleProduct>>(body)
                _products.value = list
                Log.d(TAG, "✅ Loaded ${list.size} products")
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
        val url = baseUrl() ?: return false
        return try {
            val body = productJson.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$url/products")
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
        val url = baseUrl() ?: return false
        return try {
            val body = productJson.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$url/products/$productId")
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
        val url = baseUrl() ?: return false
        return try {
            val request = Request.Builder()
                .url("$url/products/$productId")
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
        val url = baseUrl() ?: return
        _isLoading.value = true

        try {
            val request = Request.Builder()
                .url("$url/menucategories")
                .get()
                .build()

            val (code, body) = executeRequest(request)
            if (code in 200..299 && body.isNotEmpty()) {
                val list = json.decodeFromString<List<ArticleCategory>>(body)
                _categories.value = list
                Log.d(TAG, "✅ Loaded ${list.size} categories")
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
        val url = baseUrl() ?: return false
        return try {
            val body = categoryJson.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$url/menucategories")
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
        val url = baseUrl() ?: return false
        return try {
            val body = categoryJson.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$url/menucategories/$categoryId")
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
        val url = baseUrl() ?: return false
        return try {
            val request = Request.Builder()
                .url("$url/menucategories/$categoryId")
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

    // MARK: - Modifier Groups

    suspend fun fetchModifierGroups() {
        val url = baseUrl() ?: return
        _isLoading.value = true

        try {
            val request = Request.Builder()
                .url("$url/modifier-groups")
                .get()
                .build()

            val (code, body) = executeRequest(request)
            if (code in 200..299 && body.isNotEmpty()) {
                val list = json.decodeFromString<List<ModifierGroup>>(body)
                _modifierGroups.value = list
                Log.d(TAG, "✅ Loaded ${list.size} modifier groups")
            } else {
                Log.e(TAG, "❌ fetchModifierGroups error: HTTP $code")
                _errorMessage.value = "Error al cargar grupos de modificadores"
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchModifierGroups exception: ${e.message}", e)
            _errorMessage.value = "Error al cargar grupos de modificadores"
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun createModifierGroup(payload: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val body = payload.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$url/modifier-groups")
                .post(body)
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Modifier group created")
                fetchModifierGroups()
                true
            } else {
                Log.e(TAG, "❌ createModifierGroup error: HTTP $code")
                _errorMessage.value = "Error al crear grupo de modificadores"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ createModifierGroup exception: ${e.message}", e)
            _errorMessage.value = "Error al crear grupo de modificadores"
            false
        }
    }

    suspend fun updateModifierGroup(groupId: String, payload: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val body = payload.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$url/modifier-groups/$groupId")
                .patch(body)
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Modifier group $groupId updated")
                fetchModifierGroups()
                true
            } else {
                Log.e(TAG, "❌ updateModifierGroup error: HTTP $code")
                _errorMessage.value = "Error al actualizar grupo de modificadores"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ updateModifierGroup exception: ${e.message}", e)
            _errorMessage.value = "Error al actualizar grupo de modificadores"
            false
        }
    }

    suspend fun deleteModifierGroup(groupId: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val request = Request.Builder()
                .url("$url/modifier-groups/$groupId")
                .delete()
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Modifier group $groupId deleted")
                fetchModifierGroups()
                true
            } else {
                Log.e(TAG, "❌ deleteModifierGroup error: HTTP $code")
                _errorMessage.value = "Error al eliminar grupo de modificadores"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ deleteModifierGroup exception: ${e.message}", e)
            _errorMessage.value = "Error al eliminar grupo de modificadores"
            false
        }
    }

    // MARK: - Individual Modifiers

    suspend fun addModifierToGroup(groupId: String, payload: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val body = payload.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$url/modifier-groups/$groupId/modifiers")
                .post(body)
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Modifier added to group $groupId")
                fetchModifierGroups()
                true
            } else {
                Log.e(TAG, "❌ addModifierToGroup error: HTTP $code")
                _errorMessage.value = "Error al agregar modificador"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ addModifierToGroup exception: ${e.message}", e)
            _errorMessage.value = "Error al agregar modificador"
            false
        }
    }

    suspend fun updateModifier(groupId: String, modifierId: String, payload: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val body = payload.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$url/modifier-groups/$groupId/modifiers/$modifierId")
                .patch(body)
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Modifier $modifierId updated in group $groupId")
                fetchModifierGroups()
                true
            } else {
                Log.e(TAG, "❌ updateModifier error: HTTP $code")
                _errorMessage.value = "Error al actualizar modificador"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ updateModifier exception: ${e.message}", e)
            _errorMessage.value = "Error al actualizar modificador"
            false
        }
    }

    suspend fun deleteModifier(groupId: String, modifierId: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val request = Request.Builder()
                .url("$url/modifier-groups/$groupId/modifiers/$modifierId")
                .delete()
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Modifier $modifierId deleted from group $groupId")
                fetchModifierGroups()
                true
            } else {
                Log.e(TAG, "❌ deleteModifier error: HTTP $code")
                _errorMessage.value = "Error al eliminar modificador"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ deleteModifier exception: ${e.message}", e)
            _errorMessage.value = "Error al eliminar modificador"
            false
        }
    }

    // MARK: - Discounts

    suspend fun fetchDiscounts() {
        val url = baseUrl() ?: return
        _isLoading.value = true

        try {
            val request = Request.Builder()
                .url("$url/discounts")
                .get()
                .build()

            val (code, body) = executeRequest(request)
            if (code in 200..299 && body.isNotEmpty()) {
                val list = json.decodeFromString<List<AdminDiscount>>(body)
                _discounts.value = list
                Log.d(TAG, "✅ Loaded ${list.size} discounts")
            } else {
                Log.e(TAG, "❌ fetchDiscounts error: HTTP $code")
                _errorMessage.value = "Error al cargar descuentos"
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchDiscounts exception: ${e.message}", e)
            _errorMessage.value = "Error al cargar descuentos"
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun createDiscount(payload: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val body = payload.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$url/discounts")
                .post(body)
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Discount created")
                fetchDiscounts()
                true
            } else {
                Log.e(TAG, "❌ createDiscount error: HTTP $code")
                _errorMessage.value = "Error al crear descuento"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ createDiscount exception: ${e.message}", e)
            _errorMessage.value = "Error al crear descuento"
            false
        }
    }

    suspend fun updateDiscount(discountId: String, payload: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val body = payload.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$url/discounts/$discountId")
                .put(body)
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Discount $discountId updated")
                fetchDiscounts()
                true
            } else {
                Log.e(TAG, "❌ updateDiscount error: HTTP $code")
                _errorMessage.value = "Error al actualizar descuento"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ updateDiscount exception: ${e.message}", e)
            _errorMessage.value = "Error al actualizar descuento"
            false
        }
    }

    suspend fun deleteDiscount(discountId: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val request = Request.Builder()
                .url("$url/discounts/$discountId")
                .delete()
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Discount $discountId deleted")
                fetchDiscounts()
                true
            } else {
                Log.e(TAG, "❌ deleteDiscount error: HTTP $code")
                _errorMessage.value = "Error al eliminar descuento"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ deleteDiscount exception: ${e.message}", e)
            _errorMessage.value = "Error al eliminar descuento"
            false
        }
    }

    // MARK: - Coupons

    suspend fun fetchCoupons() {
        val url = baseUrl() ?: return
        _isLoading.value = true

        try {
            val request = Request.Builder()
                .url("$url/coupons")
                .get()
                .build()

            val (code, body) = executeRequest(request)
            if (code in 200..299 && body.isNotEmpty()) {
                val list = json.decodeFromString<List<AdminCoupon>>(body)
                _coupons.value = list
                Log.d(TAG, "✅ Loaded ${list.size} coupons")
            } else {
                Log.e(TAG, "❌ fetchCoupons error: HTTP $code")
                _errorMessage.value = "Error al cargar cupones"
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchCoupons exception: ${e.message}", e)
            _errorMessage.value = "Error al cargar cupones"
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun createCoupon(payload: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val body = payload.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$url/coupons")
                .post(body)
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Coupon created")
                fetchCoupons()
                true
            } else {
                Log.e(TAG, "❌ createCoupon error: HTTP $code")
                _errorMessage.value = "Error al crear cupón"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ createCoupon exception: ${e.message}", e)
            _errorMessage.value = "Error al crear cupón"
            false
        }
    }

    suspend fun updateCoupon(couponId: String, payload: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val body = payload.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$url/coupons/$couponId")
                .put(body)
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Coupon $couponId updated")
                fetchCoupons()
                true
            } else {
                Log.e(TAG, "❌ updateCoupon error: HTTP $code")
                _errorMessage.value = "Error al actualizar cupón"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ updateCoupon exception: ${e.message}", e)
            _errorMessage.value = "Error al actualizar cupón"
            false
        }
    }

    suspend fun deleteCoupon(couponId: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val request = Request.Builder()
                .url("$url/coupons/$couponId")
                .delete()
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Coupon $couponId deleted")
                fetchCoupons()
                true
            } else {
                Log.e(TAG, "❌ deleteCoupon error: HTTP $code")
                _errorMessage.value = "Error al eliminar cupón"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ deleteCoupon exception: ${e.message}", e)
            _errorMessage.value = "Error al eliminar cupón"
            false
        }
    }

    // MARK: - Credit Packs

    suspend fun fetchCreditPacks() {
        val url = baseUrl() ?: return
        _isLoading.value = true

        try {
            val request = Request.Builder()
                .url("$url/credit-packs")
                .get()
                .build()

            val (code, body) = executeRequest(request)
            if (code in 200..299 && body.isNotEmpty()) {
                val list = json.decodeFromString<List<CreditPack>>(body)
                _creditPacks.value = list
                Log.d(TAG, "✅ Loaded ${list.size} credit packs")
            } else {
                Log.e(TAG, "❌ fetchCreditPacks error: HTTP $code")
                _errorMessage.value = "Error al cargar paquetes de crédito"
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchCreditPacks exception: ${e.message}", e)
            _errorMessage.value = "Error al cargar paquetes de crédito"
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun createCreditPack(payload: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val body = payload.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$url/credit-packs")
                .post(body)
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Credit pack created")
                fetchCreditPacks()
                true
            } else {
                Log.e(TAG, "❌ createCreditPack error: HTTP $code")
                _errorMessage.value = "Error al crear paquete de crédito"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ createCreditPack exception: ${e.message}", e)
            _errorMessage.value = "Error al crear paquete de crédito"
            false
        }
    }

    suspend fun updateCreditPack(packId: String, payload: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val body = payload.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url("$url/credit-packs/$packId")
                .patch(body)
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Credit pack $packId updated")
                fetchCreditPacks()
                true
            } else {
                Log.e(TAG, "❌ updateCreditPack error: HTTP $code")
                _errorMessage.value = "Error al actualizar paquete de crédito"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ updateCreditPack exception: ${e.message}", e)
            _errorMessage.value = "Error al actualizar paquete de crédito"
            false
        }
    }

    suspend fun deleteCreditPack(packId: String): Boolean {
        val url = baseUrl() ?: return false
        return try {
            val request = Request.Builder()
                .url("$url/credit-packs/$packId")
                .delete()
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Credit pack $packId deleted")
                fetchCreditPacks()
                true
            } else {
                Log.e(TAG, "❌ deleteCreditPack error: HTTP $code")
                _errorMessage.value = "Error al eliminar paquete de crédito"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ deleteCreditPack exception: ${e.message}", e)
            _errorMessage.value = "Error al eliminar paquete de crédito"
            false
        }
    }

    // MARK: - Product Options

    suspend fun fetchProductOptions() {
        val venueId = secureStorage.venueId ?: return
        val url = "${ApiConstants.BASE_URL}/mobile/venues/$venueId/product-options"
        _isLoading.value = true

        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val (code, body) = executeRequest(request)
            if (code in 200..299 && body.isNotEmpty()) {
                val wrapper = json.decodeFromString<ProductOptionsListResponse>(body)
                val list = wrapper.data
                _productOptions.value = list
                Log.d(TAG, "✅ Loaded ${list.size} product options")
            } else {
                Log.e(TAG, "❌ fetchProductOptions error: HTTP $code")
                _errorMessage.value = "Error al cargar opciones de producto"
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ fetchProductOptions exception: ${e.message}", e)
            _errorMessage.value = "Error al cargar opciones de producto"
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun createProductOption(payload: String): Boolean {
        val venueId = secureStorage.venueId ?: return false
        val url = "${ApiConstants.BASE_URL}/mobile/venues/$venueId/product-options"
        return try {
            val body = payload.toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Product option created")
                fetchProductOptions()
                true
            } else {
                Log.e(TAG, "❌ createProductOption error: HTTP $code")
                _errorMessage.value = "Error al crear opcion de producto"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ createProductOption exception: ${e.message}", e)
            _errorMessage.value = "Error al crear opcion de producto"
            false
        }
    }

    suspend fun deleteProductOption(optionId: String): Boolean {
        val venueId = secureStorage.venueId ?: return false
        val url = "${ApiConstants.BASE_URL}/mobile/venues/$venueId/product-options/$optionId"
        return try {
            val request = Request.Builder()
                .url(url)
                .delete()
                .build()

            val (code, _) = executeRequest(request)
            if (code in 200..299) {
                Log.d(TAG, "✅ Product option $optionId deleted")
                fetchProductOptions()
                true
            } else {
                Log.e(TAG, "❌ deleteProductOption error: HTTP $code")
                _errorMessage.value = "Error al eliminar opcion de producto"
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ deleteProductOption exception: ${e.message}", e)
            _errorMessage.value = "Error al eliminar opcion de producto"
            false
        }
    }

    // MARK: - Raw Materials Search

    suspend fun searchRawMaterials(query: String): List<RawMaterial> {
        val url = baseUrl() ?: return emptyList()
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("$url/inventory/raw-materials?active=true&search=$encodedQuery")
                .get()
                .build()

            val (code, body) = executeRequest(request)
            if (code in 200..299 && body.isNotEmpty()) {
                val list = json.decodeFromString<List<RawMaterial>>(body)
                Log.d(TAG, "✅ Found ${list.size} raw materials for query '$query'")
                list
            } else {
                Log.e(TAG, "❌ searchRawMaterials error: HTTP $code")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ searchRawMaterials exception: ${e.message}", e)
            emptyList()
        }
    }
}
