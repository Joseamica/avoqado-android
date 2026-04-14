# Articles Feature Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a full CRUD admin section for managing Products, Categories, Modifier Groups, Modifiers, Discounts, Coupons, and Credit Packs — accessed from More > Articles (MANAGER+ role gate).

**Architecture:** New `articles/` package following existing MVVM pattern: `ArticlesRepository` (singleton, OkHttpClient + manual URL building like InventoryRepository), `ArticlesViewModel` (HiltViewModel coordinator), and 13+ Compose screens/sheets. Shell uses adaptive layout: sidebar on tablet, NavigationView stack on phone. All API calls use `/dashboard/venues/{venueId}/...` routes.

**Tech Stack:** Kotlin, Jetpack Compose + Material3, Hilt DI, OkHttp (manual requests), kotlinx-serialization, StateFlow, WindowSizeClass

---

## File Structure

```
app/src/main/java/com/avoqado/pos/articles/
├── data/
│   ├── ArticlesRepository.kt              ← All API calls (@Singleton, OkHttpClient)
│   └── model/
│       └── ArticlesModels.kt              ← All models + enums
├── presentation/
│   ├── ArticlesViewModel.kt               ← Coordinator ViewModel (@HiltViewModel)
│   ├── ArticlesScreen.kt                  ← Shell: sidebar (tablet) / nav (phone)
│   ├── products/
│   │   ├── ProductListView.kt             ← Product list + search + FAB
│   │   └── ProductDetailView.kt           ← Create/edit product form
│   ├── categories/
│   │   ├── CategoryListView.kt            ← Category list + FAB
│   │   └── CategoryFormSheet.kt           ← Create/edit category sheet
│   ├── modifiers/
│   │   ├── ModifierGroupListView.kt       ← Expandable card list + FAB
│   │   ├── ModifierGroupFormSheet.kt      ← Create/edit group + inline modifiers
│   │   └── ModifierDetailSheet.kt         ← Single modifier edit + inventory
│   ├── discounts/
│   │   ├── DiscountListView.kt            ← Discount list + FAB
│   │   └── DiscountFormSheet.kt           ← Create/edit discount
│   ├── coupons/
│   │   ├── CouponListView.kt              ← Coupon list + FAB
│   │   └── CouponFormSheet.kt             ← Create/edit coupon
│   └── creditpacks/
│       ├── CreditPackListView.kt          ← Credit pack card list + FAB
│       └── CreditPackFormSheet.kt         ← Create/edit credit pack
```

**Modified existing files:**
- `core/domain/RoleManager.kt` — verify `canCreateProducts` exists (already added)
- `settings/MoreMenuScreen.kt` — add "Articulos" entry + fullscreen overlay
- `settings/MoreMenuViewModel.kt` — inject RoleManager, expose `canCreateProducts`

**Notes:**
- `ArticlesRepository` uses `@Singleton` + `@Inject constructor` so Hilt auto-provides it — no NetworkModule change needed.
- Articles is launched as a fullscreen overlay from MoreMenuScreen, not a navigation route — no AvoqadoNavGraph change needed.
- `AuthInterceptor` automatically adds `Authorization: Bearer` headers to all OkHttpClient requests. The repository does NOT add auth headers manually (unlike older repositories that redundantly add them). This is correct.
- API uses different HTTP methods per entity: Products (PUT), Categories (PATCH), ModifierGroups (PATCH), Discounts (PUT), Coupons (PUT), CreditPacks (PATCH). This matches the server API spec.

---

## Phase 1: Data Layer

### Task 1: Articles Models and Enums

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/articles/data/model/ArticlesModels.kt`

All data classes and enums needed for the Articles feature.

- [ ] **Step 1: Create the models file**

Create `ArticlesModels.kt` with all `@Serializable` data classes:

```kotlin
package com.avoqado.pos.articles.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.util.Locale

// ── Enums ──────────────────────────────────────────────

enum class ArticleSection(val label: String, val iconName: String) {
    PRODUCTS("Articulos", "tag"),
    CATEGORIES("Categorias", "folder"),
    MODIFIERS("Modificadores", "sliders"),
    DISCOUNTS("Descuentos", "percent"),
    COUPONS("Cupones", "confirmation_number"),
    CREDIT_PACKS("Paquetes de creditos", "credit_card"),
}

enum class ProductType(val label: String) {
    REGULAR("Regular"),
    FOOD_AND_BEV("Alimentos y bebidas"),
    APPOINTMENTS_SERVICE("Servicio"),
    OTHER("Otro"),
}

enum class PriceType { FIXED, VARIABLE }

enum class InventoryMethod(val label: String, val description: String) {
    QUANTITY("Por cantidad", "Rastrea unidades y puntos de reorden"),
    RECIPE("Por receta", "Basado en ingredientes y recetas"),
}

enum class ModifierInventoryMode(val label: String, val description: String) {
    ADDITION("Adicion", "Agrega ingrediente extra"),
    SUBSTITUTION("Sustitucion", "Reemplaza un ingrediente"),
}

enum class MeasurementUnit(val label: String, val abbreviation: String) {
    UNIT("Unidad", "u"),
    PIECE("Pieza", "pz"),
    KILOGRAM("Kilogramo", "kg"),
    GRAM("Gramo", "g"),
    LITER("Litro", "L"),
    MILLILITER("Mililitro", "ml"),
    OUNCE("Onza", "oz"),
    POUND("Libra", "lb"),
    DOZEN("Docena", "doc"),
}

enum class DiscountType(val label: String) {
    PERCENTAGE("Porcentaje"),
    FIXED("Monto fijo"),
    COMP("Cortesia"),
}

enum class DiscountScope(val label: String) {
    ORDER("Orden completa"),
    ITEM("Articulos especificos"),
    CATEGORY("Categoria"),
    MODIFIER("Modificador"),
    MODIFIER_GROUP("Grupo de modificadores"),
    CUSTOMER_GROUP("Grupo de clientes"),
    QUANTITY("Cantidad"),
}

// ── Category ───────────────────────────────────────────

@Serializable
data class ArticleCategory(
    val id: String = "",
    val name: String = "",
    val color: String? = null,
    val description: String? = null,
    val displayOrder: Int? = null,
    val active: Boolean? = true,
    @SerialName("_count")
    val count: CategoryCount? = null,
) {
    @Transient
    val productCount: Int = count?.products ?: 0
}

@Serializable
data class CategoryCount(
    val products: Int = 0,
)

// ── Product ────────────────────────────────────────────

@Serializable
data class ArticleProduct(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val type: String? = null,
    val sku: String? = null,
    val gtin: String? = null,
    val price: Double? = null,
    val cost: Double? = null,
    val priceType: String? = null,
    val taxRate: Double? = null,
    val active: Boolean? = true,
    val trackInventory: Boolean? = false,
    val inventoryMethod: String? = null,
    val unit: String? = null,
    val categoryId: String? = null,
    val category: ArticleCategory? = null,
    val modifierGroups: List<ProductModifierGroupJoin>? = null,
) {
    @Transient
    val initials: String = name.take(2).uppercase()

    @Transient
    val displayPrice: String = when (priceType) {
        "VARIABLE" -> "Variable"
        else -> price?.let { "$${String.format(Locale.US, "%.2f", it)}" } ?: "$0.00"
    }

    @Transient
    val productType: ProductType = when (type) {
        "FOOD_AND_BEV", "FOOD", "BEVERAGE", "ALCOHOL" -> ProductType.FOOD_AND_BEV
        "APPOINTMENTS_SERVICE", "SERVICE" -> ProductType.APPOINTMENTS_SERVICE
        "OTHER" -> ProductType.OTHER
        else -> ProductType.REGULAR
    }
}

@Serializable
data class ProductModifierGroupJoin(
    val id: String = "",
    val groupId: String? = null,
    val displayOrder: Int? = null,
    val group: ModifierGroup? = null,
)

// ── Modifier Group & Modifier ──────────────────────────

@Serializable
data class ModifierGroup(
    val id: String = "",
    val name: String = "",
    val required: Boolean = false,
    val allowMultiple: Boolean = false,
    val minSelections: Int? = null,
    val maxSelections: Int? = null,
    val active: Boolean? = true,
    val modifiers: List<ArticleModifier>? = null,
) {
    @Transient
    val modifierCount: Int = modifiers?.size ?: 0
}

// Named ArticleModifier (not Modifier) to avoid collision with
// androidx.compose.ui.Modifier in Compose files.
@Serializable
data class ArticleModifier(
    val id: String = "",
    val name: String = "",
    val price: Double? = null,
    val active: Boolean? = true,
    val rawMaterialId: String? = null,
    val inventoryMode: String? = null,
    val quantityPerUnit: Double? = null,
    val unit: String? = null,
) {
    @Transient
    val displayPrice: String = price?.let {
        if (it > 0) "+$${String.format(Locale.US, "%.2f", it)}" else ""
    } ?: ""
}

// ── Discount ───────────────────────────────────────────

@Serializable
data class AdminDiscount(
    val id: String = "",
    val name: String = "",
    val type: String = "PERCENTAGE",
    val value: Double = 0.0,
    val scope: String = "ORDER",
    val active: Boolean? = true,
    val requiresApproval: Boolean? = false,
) {
    @Transient
    val discountType: DiscountType = when (type) {
        "FIXED", "FIXED_AMOUNT" -> DiscountType.FIXED
        "COMP" -> DiscountType.COMP
        else -> DiscountType.PERCENTAGE
    }

    @Transient
    val discountScope: DiscountScope = when (scope) {
        "ITEM" -> DiscountScope.ITEM
        "CATEGORY" -> DiscountScope.CATEGORY
        "MODIFIER" -> DiscountScope.MODIFIER
        "MODIFIER_GROUP" -> DiscountScope.MODIFIER_GROUP
        "CUSTOMER_GROUP" -> DiscountScope.CUSTOMER_GROUP
        "QUANTITY" -> DiscountScope.QUANTITY
        else -> DiscountScope.ORDER
    }

    @Transient
    val formattedValue: String = when (discountType) {
        DiscountType.PERCENTAGE -> "${value.toInt()}%"
        DiscountType.FIXED -> "$${String.format("%.2f", value)}"
        DiscountType.COMP -> "Cortesia"
    }

    @Transient
    val emoji: String = run {
        val lower = name.lowercase()
        when {
            lower.contains("empleado") -> "\uD83D\uDC54" // necktie
            lower.contains("happy") -> "\uD83C\uDF7B"    // beer
            lower.contains("cumple") -> "\uD83C\uDF82"   // cake
            lower.contains("vip") -> "\uD83D\uDC8E"      // gem
            lower.contains("promo") -> "\u2B50"           // star
            lower.contains("comp") || lower.contains("cortesia") -> "\uD83C\uDF81" // gift
            else -> "\uD83C\uDFF7\uFE0F"                 // label
        }
    }
}

// ── Coupon ──────────────────────────────────────────────

@Serializable
data class AdminCoupon(
    val id: String = "",
    val discountId: String = "",
    val code: String = "",
    val maxUses: Int? = null,
    val maxUsesPerCustomer: Int? = null,
    val currentUses: Int? = null,
    val minPurchaseAmount: Double? = null,
    val validFrom: String? = null,
    val validUntil: String? = null,
    val active: Boolean? = true,
    val discount: AdminDiscount? = null,
) {
    @Transient
    val isExpired: Boolean = validUntil?.let {
        try {
            java.time.OffsetDateTime.parse(it).isBefore(java.time.OffsetDateTime.now())
        } catch (_: Exception) { false }
    } ?: false

    @Transient
    val usageText: String = run {
        val current = currentUses ?: 0
        maxUses?.let { "$current/$it usos" } ?: "$current usos"
    }
}

// ── Credit Pack ────────────────────────────────────────

@Serializable
data class CreditPack(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val price: Double = 0.0,
    val currency: String? = "MXN",
    val validityDays: Int? = null,
    val maxPerCustomer: Int? = null,
    val active: Boolean? = true,
    val displayOrder: Int? = null,
    val items: List<CreditPackItem>? = null,
) {
    @Transient
    val displayPrice: String = "$${String.format("%.2f", price)}"

    @Transient
    val itemCount: Int = items?.sumOf { it.quantity } ?: 0
}

@Serializable
data class CreditPackItem(
    val id: String = "",
    val productId: String = "",
    val quantity: Int = 1,
    val product: CreditPackItemProduct? = null,
)

@Serializable
data class CreditPackItemProduct(
    val id: String = "",
    val name: String = "",
)

// ── Raw Material (for modifier inventory) ──────────────

@Serializable
data class RawMaterial(
    val id: String = "",
    val name: String = "",
    val sku: String? = null,
    val category: String? = null,
    val currentStock: Double? = null,
    val unit: String? = null,
    val costPerUnit: Double? = null,
    val active: Boolean? = true,
)

// ── Recipe ──────────────────────────────────────────────

@Serializable
data class Recipe(
    val id: String = "",
    val productId: String = "",
    val portionYield: Double? = null,
    val totalCost: Double? = null,
    val prepTime: Int? = null,
    val cookTime: Int? = null,
    val notes: String? = null,
    val lines: List<RecipeLine>? = null,
)

@Serializable
data class RecipeLine(
    val id: String = "",
    val rawMaterialId: String = "",
    val quantity: Double = 0.0,
    val unit: String? = null,
    val isOptional: Boolean? = false,
    val substituteNotes: String? = null,
    val rawMaterial: RawMaterial? = null,
)
```

- [ ] **Step 2: Verify the file compiles**

Run: `cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/articles/data/model/ArticlesModels.kt
git commit -m "feat(articles): add data models and enums for Articles feature"
```

---

### Task 2: Articles Repository — Products & Categories CRUD

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/articles/data/ArticlesRepository.kt`

Repository follows existing pattern from `InventoryRepository.kt`: `@Singleton`, `OkHttpClient` injection, manual URL building, `withContext(Dispatchers.IO)`, `StateFlow` for reactive data.

- [ ] **Step 1: Create the repository with Products + Categories CRUD**

```kotlin
package com.avoqado.pos.articles.data

import android.util.Log
import com.avoqado.pos.articles.data.model.*
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

@Singleton
class ArticlesRepository @Inject constructor(
    private val secureStorage: SecureStorage,
    private val client: OkHttpClient,
) {
    companion object {
        private const val TAG = "📦 ArticlesRepo"
        private val JSON_MEDIA = "application/json".toMediaType()
    }

    // Local Json instance (matches existing repository pattern)
    private val json = Json { ignoreUnknownKeys = true }

    // ── State ──────────────────────────────────────────

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

    fun clearError() { _errorMessage.value = null }

    private fun baseUrl(): String {
        val venueId = secureStorage.venueId ?: ""
        return "${ApiConstants.BASE_URL}/dashboard/venues/$venueId"
    }

    // ── Products ───────────────────────────────────────

    suspend fun fetchProducts() {
        _isLoading.value = true
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/products")
                .get()
                .build()
            val body = executeRequest(request)
            val items = json.decodeFromString<List<ArticleProduct>>(body)
            _products.value = items
            Log.d(TAG, "Fetched ${items.size} products")
        } catch (e: Exception) {
            Log.e(TAG, "fetchProducts error: ${e.message}")
            _errorMessage.value = "Error al cargar articulos"
        } finally {
            _isLoading.value = false
        }
    }

    suspend fun createProduct(payload: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/products")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            executeRequest(request)
            fetchProducts()
            true
        } catch (e: Exception) {
            Log.e(TAG, "createProduct error: ${e.message}")
            _errorMessage.value = "Error al crear articulo"
            false
        }
    }

    suspend fun updateProduct(productId: String, payload: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/products/$productId")
                .put(payload.toRequestBody(JSON_MEDIA))
                .build()
            executeRequest(request)
            fetchProducts()
            true
        } catch (e: Exception) {
            Log.e(TAG, "updateProduct error: ${e.message}")
            _errorMessage.value = "Error al actualizar articulo"
            false
        }
    }

    suspend fun deleteProduct(productId: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/products/$productId")
                .delete()
                .build()
            executeRequest(request)
            fetchProducts()
            true
        } catch (e: Exception) {
            Log.e(TAG, "deleteProduct error: ${e.message}")
            _errorMessage.value = "Error al eliminar articulo"
            false
        }
    }

    // ── Categories ─────────────────────────────────────

    suspend fun fetchCategories() {
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/menucategories")
                .get()
                .build()
            val body = executeRequest(request)
            val items = json.decodeFromString<List<ArticleCategory>>(body)
            _categories.value = items
            Log.d(TAG, "Fetched ${items.size} categories")
        } catch (e: Exception) {
            Log.e(TAG, "fetchCategories error: ${e.message}")
            _errorMessage.value = "Error al cargar categorias"
        }
    }

    suspend fun createCategory(payload: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/menucategories")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            executeRequest(request)
            fetchCategories()
            true
        } catch (e: Exception) {
            Log.e(TAG, "createCategory error: ${e.message}")
            _errorMessage.value = "Error al crear categoria"
            false
        }
    }

    suspend fun updateCategory(categoryId: String, payload: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/menucategories/$categoryId")
                .patch(payload.toRequestBody(JSON_MEDIA))
                .build()
            executeRequest(request)
            fetchCategories()
            true
        } catch (e: Exception) {
            Log.e(TAG, "updateCategory error: ${e.message}")
            _errorMessage.value = "Error al actualizar categoria"
            false
        }
    }

    suspend fun deleteCategory(categoryId: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/menucategories/$categoryId")
                .delete()
                .build()
            executeRequest(request)
            fetchCategories()
            true
        } catch (e: Exception) {
            Log.e(TAG, "deleteCategory error: ${e.message}")
            _errorMessage.value = "Error al eliminar categoria"
            false
        }
    }

    // ── Helper ─────────────────────────────────────────

    private suspend fun executeRequest(request: Request): String {
        return withContext(Dispatchers.IO) {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: $body")
            }
            body
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/articles/data/ArticlesRepository.kt
git commit -m "feat(articles): add ArticlesRepository with products and categories CRUD"
```

---

### Task 3: Articles Repository — Modifier Groups, Discounts, Coupons, Credit Packs

**Files:**
- Modify: `app/src/main/java/com/avoqado/pos/articles/data/ArticlesRepository.kt`

Add remaining CRUD methods to the repository.

- [ ] **Step 1: Add modifier group methods**

Append to `ArticlesRepository.kt` (before the `executeRequest` helper):

```kotlin
    // ── Modifier Groups ────────────────────────────────

    suspend fun fetchModifierGroups() {
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/modifier-groups")
                .get()
                .build()
            val body = executeRequest(request)
            val items = json.decodeFromString<List<ModifierGroup>>(body)
            _modifierGroups.value = items
            Log.d(TAG, "Fetched ${items.size} modifier groups")
        } catch (e: Exception) {
            Log.e(TAG, "fetchModifierGroups error: ${e.message}")
            _errorMessage.value = "Error al cargar modificadores"
        }
    }

    suspend fun createModifierGroup(payload: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/modifier-groups")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            executeRequest(request)
            fetchModifierGroups()
            true
        } catch (e: Exception) {
            Log.e(TAG, "createModifierGroup error: ${e.message}")
            _errorMessage.value = "Error al crear grupo de modificadores"
            false
        }
    }

    suspend fun updateModifierGroup(groupId: String, payload: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/modifier-groups/$groupId")
                .patch(payload.toRequestBody(JSON_MEDIA))
                .build()
            executeRequest(request)
            fetchModifierGroups()
            true
        } catch (e: Exception) {
            Log.e(TAG, "updateModifierGroup error: ${e.message}")
            _errorMessage.value = "Error al actualizar grupo"
            false
        }
    }

    suspend fun deleteModifierGroup(groupId: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/modifier-groups/$groupId")
                .delete()
                .build()
            executeRequest(request)
            fetchModifierGroups()
            true
        } catch (e: Exception) {
            Log.e(TAG, "deleteModifierGroup error: ${e.message}")
            _errorMessage.value = "Error al eliminar grupo"
            false
        }
    }

    // ── Individual Modifiers ───────────────────────────

    suspend fun addModifierToGroup(groupId: String, payload: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/modifier-groups/$groupId/modifiers")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            executeRequest(request)
            fetchModifierGroups()
            true
        } catch (e: Exception) {
            Log.e(TAG, "addModifier error: ${e.message}")
            _errorMessage.value = "Error al agregar modificador"
            false
        }
    }

    suspend fun updateModifier(groupId: String, modifierId: String, payload: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/modifier-groups/$groupId/modifiers/$modifierId")
                .patch(payload.toRequestBody(JSON_MEDIA))
                .build()
            executeRequest(request)
            fetchModifierGroups()
            true
        } catch (e: Exception) {
            Log.e(TAG, "updateModifier error: ${e.message}")
            _errorMessage.value = "Error al actualizar modificador"
            false
        }
    }

    suspend fun deleteModifier(groupId: String, modifierId: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/modifier-groups/$groupId/modifiers/$modifierId")
                .delete()
                .build()
            executeRequest(request)
            fetchModifierGroups()
            true
        } catch (e: Exception) {
            Log.e(TAG, "deleteModifier error: ${e.message}")
            _errorMessage.value = "Error al eliminar modificador"
            false
        }
    }
```

- [ ] **Step 2: Add discount methods**

```kotlin
    // ── Discounts ──────────────────────────────────────

    suspend fun fetchDiscounts() {
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/discounts")
                .get()
                .build()
            val body = executeRequest(request)
            val items = json.decodeFromString<List<AdminDiscount>>(body)
            _discounts.value = items
            Log.d(TAG, "Fetched ${items.size} discounts")
        } catch (e: Exception) {
            Log.e(TAG, "fetchDiscounts error: ${e.message}")
            _errorMessage.value = "Error al cargar descuentos"
        }
    }

    suspend fun createDiscount(payload: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/discounts")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            executeRequest(request)
            fetchDiscounts()
            true
        } catch (e: Exception) {
            Log.e(TAG, "createDiscount error: ${e.message}")
            _errorMessage.value = "Error al crear descuento"
            false
        }
    }

    suspend fun updateDiscount(discountId: String, payload: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/discounts/$discountId")
                .put(payload.toRequestBody(JSON_MEDIA))
                .build()
            executeRequest(request)
            fetchDiscounts()
            true
        } catch (e: Exception) {
            Log.e(TAG, "updateDiscount error: ${e.message}")
            _errorMessage.value = "Error al actualizar descuento"
            false
        }
    }

    suspend fun deleteDiscount(discountId: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/discounts/$discountId")
                .delete()
                .build()
            executeRequest(request)
            fetchDiscounts()
            true
        } catch (e: Exception) {
            Log.e(TAG, "deleteDiscount error: ${e.message}")
            _errorMessage.value = "Error al eliminar descuento"
            false
        }
    }
```

- [ ] **Step 3: Add coupon methods**

```kotlin
    // ── Coupons ────────────────────────────────────────

    suspend fun fetchCoupons() {
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/coupons")
                .get()
                .build()
            val body = executeRequest(request)
            val items = json.decodeFromString<List<AdminCoupon>>(body)
            _coupons.value = items
            Log.d(TAG, "Fetched ${items.size} coupons")
        } catch (e: Exception) {
            Log.e(TAG, "fetchCoupons error: ${e.message}")
            _errorMessage.value = "Error al cargar cupones"
        }
    }

    suspend fun createCoupon(payload: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/coupons")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            executeRequest(request)
            fetchCoupons()
            true
        } catch (e: Exception) {
            Log.e(TAG, "createCoupon error: ${e.message}")
            _errorMessage.value = "Error al crear cupon"
            false
        }
    }

    suspend fun updateCoupon(couponId: String, payload: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/coupons/$couponId")
                .put(payload.toRequestBody(JSON_MEDIA))
                .build()
            executeRequest(request)
            fetchCoupons()
            true
        } catch (e: Exception) {
            Log.e(TAG, "updateCoupon error: ${e.message}")
            _errorMessage.value = "Error al actualizar cupon"
            false
        }
    }

    suspend fun deleteCoupon(couponId: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/coupons/$couponId")
                .delete()
                .build()
            executeRequest(request)
            fetchCoupons()
            true
        } catch (e: Exception) {
            Log.e(TAG, "deleteCoupon error: ${e.message}")
            _errorMessage.value = "Error al eliminar cupon"
            false
        }
    }
```

- [ ] **Step 4: Add credit pack methods**

```kotlin
    // ── Credit Packs ───────────────────────────────────

    suspend fun fetchCreditPacks() {
        try {
            val request = Request.Builder()
                .url("${baseUrl()}/credit-packs")
                .get()
                .build()
            val body = executeRequest(request)
            val items = json.decodeFromString<List<CreditPack>>(body)
            _creditPacks.value = items
            Log.d(TAG, "Fetched ${items.size} credit packs")
        } catch (e: Exception) {
            Log.e(TAG, "fetchCreditPacks error: ${e.message}")
            _errorMessage.value = "Error al cargar paquetes"
        }
    }

    suspend fun createCreditPack(payload: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/credit-packs")
                .post(payload.toRequestBody(JSON_MEDIA))
                .build()
            executeRequest(request)
            fetchCreditPacks()
            true
        } catch (e: Exception) {
            Log.e(TAG, "createCreditPack error: ${e.message}")
            _errorMessage.value = "Error al crear paquete"
            false
        }
    }

    suspend fun updateCreditPack(packId: String, payload: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/credit-packs/$packId")
                .patch(payload.toRequestBody(JSON_MEDIA))
                .build()
            executeRequest(request)
            fetchCreditPacks()
            true
        } catch (e: Exception) {
            Log.e(TAG, "updateCreditPack error: ${e.message}")
            _errorMessage.value = "Error al actualizar paquete"
            false
        }
    }

    suspend fun deleteCreditPack(packId: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("${baseUrl()}/credit-packs/$packId")
                .delete()
                .build()
            executeRequest(request)
            fetchCreditPacks()
            true
        } catch (e: Exception) {
            Log.e(TAG, "deleteCreditPack error: ${e.message}")
            _errorMessage.value = "Error al eliminar paquete"
            false
        }
    }
```

- [ ] **Step 5: Add raw material search method**

```kotlin
    // ── Raw Materials (inventory search) ───────────────

    suspend fun searchRawMaterials(query: String): List<RawMaterial> {
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val request = Request.Builder()
                .url("${baseUrl()}/inventory/raw-materials?active=true&search=$encodedQuery")
                .get()
                .build()
            val body = executeRequest(request)
            json.decodeFromString<List<RawMaterial>>(body)
        } catch (e: Exception) {
            Log.e(TAG, "searchRawMaterials error: ${e.message}")
            emptyList()
        }
    }
```

- [ ] **Step 6: Verify compilation**

Run: `cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/articles/data/ArticlesRepository.kt
git commit -m "feat(articles): add modifier groups, discounts, coupons, credit packs CRUD to repository"
```

---

### Task 4: Verify Hilt Wiring + Role Manager

**Files:**
- Verify: `app/src/main/java/com/avoqado/pos/core/domain/RoleManager.kt`

- [ ] **Step 1: Verify Hilt auto-provides ArticlesRepository**

The repository uses constructor injection (`@Singleton` + `@Inject constructor`), so Hilt auto-provides it. **No changes needed to NetworkModule** — Hilt will create the singleton automatically from the `@Inject constructor`.

Verify `ArticlesRepository` has `@Singleton` and `@Inject constructor` (already done in Task 2).

- [ ] **Step 2: Verify `canCreateProducts` exists in RoleManager**

Read `RoleManager.kt` and confirm it already has:

```kotlin
val canCreateProducts: Boolean
    get() = role in setOf("MANAGER", "ADMIN", "OWNER", "SUPERADMIN")
```

This property should already exist alongside `canAccessPOS`, `canAccessInventory`, etc. If NOT present, add it.

- [ ] **Step 3: Verify compilation**

Run: `cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit (only if changes were made)**

```bash
git add app/src/main/java/com/avoqado/pos/core/domain/RoleManager.kt
git commit -m "feat(articles): verify canCreateProducts role permission"
```

---

## Phase 2: ViewModel + Navigation Shell

### Task 5: ArticlesViewModel

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/articles/presentation/ArticlesViewModel.kt`

Coordinator ViewModel that manages all section state, selection, and delegates CRUD to repository.

- [ ] **Step 1: Create ArticlesViewModel**

```kotlin
package com.avoqado.pos.articles.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.articles.data.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject

@HiltViewModel
class ArticlesViewModel @Inject constructor(
    private val repository: ArticlesRepository,
) : ViewModel() {

    // ── Section navigation ─────────────────────────────

    private val _selectedSection = MutableStateFlow(ArticleSection.PRODUCTS)
    val selectedSection: StateFlow<ArticleSection> = _selectedSection.asStateFlow()

    fun selectSection(section: ArticleSection) {
        _selectedSection.value = section
        loadSectionData(section)
    }

    // ── Repository data (exposed directly) ─────────────

    val products = repository.products
    val categories = repository.categories
    val modifierGroups = repository.modifierGroups
    val discounts = repository.discounts
    val coupons = repository.coupons
    val creditPacks = repository.creditPacks
    val isLoading = repository.isLoading
    val errorMessage = repository.errorMessage

    fun clearError() = repository.clearError()

    // ── Search ─────────────────────────────────────────

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun updateSearch(query: String) { _searchQuery.value = query }

    // ── Saving state ───────────────────────────────────

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    // ── Init ───────────────────────────────────────────

    init {
        loadSectionData(ArticleSection.PRODUCTS)
    }

    fun loadSectionData(section: ArticleSection) {
        viewModelScope.launch {
            when (section) {
                ArticleSection.PRODUCTS -> {
                    repository.fetchProducts()
                    repository.fetchCategories() // needed for product forms
                }
                ArticleSection.CATEGORIES -> repository.fetchCategories()
                ArticleSection.MODIFIERS -> repository.fetchModifierGroups()
                ArticleSection.DISCOUNTS -> repository.fetchDiscounts()
                ArticleSection.COUPONS -> {
                    repository.fetchCoupons()
                    repository.fetchDiscounts() // needed for coupon forms
                }
                ArticleSection.CREDIT_PACKS -> {
                    repository.fetchCreditPacks()
                    repository.fetchProducts() // needed for credit pack forms
                }
            }
        }
    }

    fun refresh() { loadSectionData(_selectedSection.value) }

    // ── Products CRUD ──────────────────────────────────

    fun createProduct(
        name: String,
        description: String?,
        type: ProductType,
        categoryId: String?,
        sku: String?,
        gtin: String?,
        priceType: PriceType,
        price: Double?,
        cost: Double?,
        taxRate: Double,
        isActive: Boolean,
        trackInventory: Boolean,
        inventoryMethod: InventoryMethod?,
        unit: MeasurementUnit?,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val payload = buildJsonObject {
                put("name", name)
                description?.let { put("description", it) }
                put("type", type.name)
                categoryId?.let { put("categoryId", it) }
                sku?.takeIf { it.isNotBlank() }?.let { put("sku", it) }
                gtin?.takeIf { it.isNotBlank() }?.let { put("gtin", it) }
                put("priceType", priceType.name)
                if (priceType == PriceType.FIXED) price?.let { put("price", it) }
                cost?.let { put("cost", it) }
                put("taxRate", taxRate)
                put("isActive", isActive)
                put("trackInventory", trackInventory)
                if (trackInventory) {
                    inventoryMethod?.let { put("inventoryMethod", it.name) }
                    unit?.let { put("unit", it.name) }
                }
            }.toString()
            repository.createProduct(payload)
            _isSaving.value = false
        }
    }

    fun updateProduct(
        productId: String,
        name: String,
        description: String?,
        type: ProductType,
        categoryId: String?,
        sku: String?,
        gtin: String?,
        priceType: PriceType,
        price: Double?,
        cost: Double?,
        taxRate: Double,
        isActive: Boolean,
        trackInventory: Boolean,
        inventoryMethod: InventoryMethod?,
        unit: MeasurementUnit?,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val payload = buildJsonObject {
                put("name", name)
                description?.let { put("description", it) }
                put("type", type.name)
                categoryId?.let { put("categoryId", it) }
                sku?.takeIf { it.isNotBlank() }?.let { put("sku", it) }
                gtin?.takeIf { it.isNotBlank() }?.let { put("gtin", it) }
                put("priceType", priceType.name)
                if (priceType == PriceType.FIXED) price?.let { put("price", it) }
                cost?.let { put("cost", it) }
                put("taxRate", taxRate)
                put("isActive", isActive)
                put("trackInventory", trackInventory)
                if (trackInventory) {
                    inventoryMethod?.let { put("inventoryMethod", it.name) }
                    unit?.let { put("unit", it.name) }
                }
            }.toString()
            repository.updateProduct(productId, payload)
            _isSaving.value = false
        }
    }

    fun deleteProduct(productId: String) {
        viewModelScope.launch {
            repository.deleteProduct(productId)
        }
    }

    // ── Categories CRUD ────────────────────────────────

    fun createCategory(name: String, description: String?, color: String?) {
        viewModelScope.launch {
            _isSaving.value = true
            val payload = buildJsonObject {
                put("name", name)
                description?.let { put("description", it) }
                color?.let { put("color", it) }
            }.toString()
            repository.createCategory(payload)
            _isSaving.value = false
        }
    }

    fun updateCategory(categoryId: String, name: String, description: String?, color: String?) {
        viewModelScope.launch {
            _isSaving.value = true
            val payload = buildJsonObject {
                put("name", name)
                description?.let { put("description", it) }
                color?.let { put("color", it) }
            }.toString()
            repository.updateCategory(categoryId, payload)
            _isSaving.value = false
        }
    }

    fun deleteCategory(categoryId: String) {
        viewModelScope.launch {
            repository.deleteCategory(categoryId)
        }
    }

    // ── Modifier Groups CRUD ───────────────────────────

    data class InlineModifier(
        val id: String? = null, // null = new
        val name: String = "",
        val price: Double = 0.0,
        val isDeleted: Boolean = false,
    )

    fun createModifierGroup(
        name: String,
        required: Boolean,
        allowMultiple: Boolean,
        minSelections: Int?,
        maxSelections: Int?,
        modifiers: List<InlineModifier>,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val payload = buildJsonObject {
                put("name", name)
                put("required", required)
                put("allowMultiple", allowMultiple)
                minSelections?.let { put("minSelections", it) }
                maxSelections?.let { put("maxSelections", it) }
                putJsonArray("modifiers") {
                    modifiers.filter { !it.isDeleted }.forEach { mod ->
                        add(buildJsonObject {
                            put("name", mod.name)
                            put("price", mod.price)
                        })
                    }
                }
            }.toString()
            repository.createModifierGroup(payload)
            _isSaving.value = false
        }
    }

    fun updateModifierGroup(
        groupId: String,
        name: String,
        required: Boolean,
        allowMultiple: Boolean,
        minSelections: Int?,
        maxSelections: Int?,
        originalModifiers: List<ArticleModifier>,
        currentModifiers: List<InlineModifier>,
    ) {
        viewModelScope.launch {
            _isSaving.value = true

            // 1. PATCH group metadata
            val groupPayload = buildJsonObject {
                put("name", name)
                put("required", required)
                put("allowMultiple", allowMultiple)
                minSelections?.let { put("minSelections", it) }
                maxSelections?.let { put("maxSelections", it) }
            }.toString()
            repository.updateModifierGroup(groupId, groupPayload)

            // 2. DELETE removed modifiers
            val deletedIds = currentModifiers.filter { it.isDeleted && it.id != null }.map { it.id!! }
            deletedIds.forEach { modId ->
                repository.deleteModifier(groupId, modId)
            }

            // 3. PATCH changed existing modifiers
            currentModifiers.filter { it.id != null && !it.isDeleted }.forEach { mod ->
                val original = originalModifiers.find { it.id == mod.id }
                if (original != null && (original.name != mod.name || original.price != mod.price)) {
                    val modPayload = buildJsonObject {
                        put("name", mod.name)
                        put("price", mod.price)
                    }.toString()
                    repository.updateModifier(groupId, mod.id!!, modPayload)
                }
            }

            // 4. POST new modifiers
            currentModifiers.filter { it.id == null && !it.isDeleted }.forEach { mod ->
                val modPayload = buildJsonObject {
                    put("name", mod.name)
                    put("price", mod.price)
                }.toString()
                repository.addModifierToGroup(groupId, modPayload)
            }

            repository.fetchModifierGroups()
            _isSaving.value = false
        }
    }

    fun deleteModifierGroup(groupId: String) {
        viewModelScope.launch {
            repository.deleteModifierGroup(groupId)
        }
    }

    // ── Individual Modifier CRUD ───────────────────────

    fun updateModifier(
        groupId: String,
        modifierId: String,
        name: String,
        price: Double,
        active: Boolean,
        rawMaterialId: String?,
        inventoryMode: ModifierInventoryMode?,
        quantityPerUnit: Double?,
        unit: MeasurementUnit?,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val payload = buildJsonObject {
                put("name", name)
                put("price", price)
                put("active", active)
                if (rawMaterialId != null) {
                    put("rawMaterialId", rawMaterialId)
                    inventoryMode?.let { put("inventoryMode", it.name) }
                    quantityPerUnit?.let { put("quantityPerUnit", it) }
                    unit?.let { put("unit", it.name) }
                } else {
                    put("rawMaterialId", JsonNull)
                    put("quantityPerUnit", JsonNull)
                    put("unit", JsonNull)
                }
            }.toString()
            repository.updateModifier(groupId, modifierId, payload)
            _isSaving.value = false
        }
    }

    // ── Raw Material Search ────────────────────────────

    private val _rawMaterialResults = MutableStateFlow<List<RawMaterial>>(emptyList())
    val rawMaterialResults: StateFlow<List<RawMaterial>> = _rawMaterialResults.asStateFlow()

    fun searchRawMaterials(query: String) {
        viewModelScope.launch {
            _rawMaterialResults.value = if (query.length >= 2) {
                repository.searchRawMaterials(query)
            } else {
                emptyList()
            }
        }
    }

    fun clearRawMaterialResults() { _rawMaterialResults.value = emptyList() }

    // ── Discounts CRUD ─────────────────────────────────

    fun createDiscount(
        name: String,
        type: DiscountType,
        value: Double,
        scope: DiscountScope,
        active: Boolean,
        requiresApproval: Boolean,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val apiType = when (type) {
                DiscountType.FIXED -> "FIXED_AMOUNT"
                else -> type.name
            }
            val payload = buildJsonObject {
                put("name", name)
                put("type", apiType)
                put("value", if (type == DiscountType.COMP) 100.0 else value)
                put("scope", scope.name)
                put("active", active)
                put("requiresApproval", requiresApproval)
            }.toString()
            repository.createDiscount(payload)
            _isSaving.value = false
        }
    }

    fun updateDiscount(
        discountId: String,
        name: String,
        type: DiscountType,
        value: Double,
        scope: DiscountScope,
        active: Boolean,
        requiresApproval: Boolean,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val apiType = when (type) {
                DiscountType.FIXED -> "FIXED_AMOUNT"
                else -> type.name
            }
            val payload = buildJsonObject {
                put("name", name)
                put("type", apiType)
                put("value", if (type == DiscountType.COMP) 100.0 else value)
                put("scope", scope.name)
                put("active", active)
                put("requiresApproval", requiresApproval)
            }.toString()
            repository.updateDiscount(discountId, payload)
            _isSaving.value = false
        }
    }

    fun deleteDiscount(discountId: String) {
        viewModelScope.launch {
            repository.deleteDiscount(discountId)
        }
    }

    // ── Coupons CRUD ───────────────────────────────────

    fun createCoupon(
        code: String,
        discountId: String,
        maxUses: Int?,
        maxUsesPerCustomer: Int?,
        active: Boolean,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val payload = buildJsonObject {
                put("code", code.uppercase())
                put("discountId", discountId)
                maxUses?.let { put("maxUses", it) }
                maxUsesPerCustomer?.let { put("maxUsesPerCustomer", it) }
                put("active", active)
            }.toString()
            repository.createCoupon(payload)
            _isSaving.value = false
        }
    }

    fun updateCoupon(
        couponId: String,
        code: String,
        discountId: String,
        maxUses: Int?,
        maxUsesPerCustomer: Int?,
        active: Boolean,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val payload = buildJsonObject {
                put("code", code.uppercase())
                put("discountId", discountId)
                maxUses?.let { put("maxUses", it) }
                maxUsesPerCustomer?.let { put("maxUsesPerCustomer", it) }
                put("active", active)
            }.toString()
            repository.updateCoupon(couponId, payload)
            _isSaving.value = false
        }
    }

    fun deleteCoupon(couponId: String) {
        viewModelScope.launch {
            repository.deleteCoupon(couponId)
        }
    }

    // ── Credit Packs CRUD ──────────────────────────────

    data class CreditPackItemInput(
        val productId: String = "",
        val quantity: Int = 1,
    )

    fun createCreditPack(
        name: String,
        description: String?,
        price: Double,
        validityDays: Int?,
        maxPerCustomer: Int?,
        active: Boolean,
        items: List<CreditPackItemInput>,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val payload = buildJsonObject {
                put("name", name)
                description?.let { put("description", it) }
                put("price", price)
                validityDays?.let { put("validityDays", it) }
                maxPerCustomer?.let { put("maxPerCustomer", it) }
                put("active", active)
                putJsonArray("items") {
                    items.filter { it.productId.isNotBlank() }.forEach { item ->
                        add(buildJsonObject {
                            put("productId", item.productId)
                            put("quantity", item.quantity)
                        })
                    }
                }
            }.toString()
            repository.createCreditPack(payload)
            _isSaving.value = false
        }
    }

    fun updateCreditPack(
        packId: String,
        name: String,
        description: String?,
        price: Double,
        validityDays: Int?,
        maxPerCustomer: Int?,
        active: Boolean,
        items: List<CreditPackItemInput>,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            val payload = buildJsonObject {
                put("name", name)
                description?.let { put("description", it) }
                put("price", price)
                validityDays?.let { put("validityDays", it) }
                maxPerCustomer?.let { put("maxPerCustomer", it) }
                put("active", active)
                putJsonArray("items") {
                    items.filter { it.productId.isNotBlank() }.forEach { item ->
                        add(buildJsonObject {
                            put("productId", item.productId)
                            put("quantity", item.quantity)
                        })
                    }
                }
            }.toString()
            repository.updateCreditPack(packId, payload)
            _isSaving.value = false
        }
    }

    fun deleteCreditPack(packId: String) {
        viewModelScope.launch {
            repository.deleteCreditPack(packId)
        }
    }
}
```

- [ ] **Step 2: Verify compilation**

Run: `cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/articles/presentation/ArticlesViewModel.kt
git commit -m "feat(articles): add ArticlesViewModel coordinator with all CRUD operations"
```

---

### Task 6: ArticlesScreen Shell (Sidebar/Nav)

**Files:**
- Create: `app/src/main/java/com/avoqado/pos/articles/presentation/ArticlesScreen.kt`

Adaptive layout: fixed sidebar (280dp) + content on tablet, NavigationView stack on phone. Back button to dismiss, section list with icons.

- [ ] **Step 1: Create ArticlesScreen**

```kotlin
package com.avoqado.pos.articles.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.articles.data.model.ArticleSection
import com.avoqado.pos.articles.presentation.categories.CategoryListView
import com.avoqado.pos.articles.presentation.coupons.CouponListView
import com.avoqado.pos.articles.presentation.creditpacks.CreditPackListView
import com.avoqado.pos.articles.presentation.discounts.DiscountListView
import com.avoqado.pos.articles.presentation.modifiers.ModifierGroupListView
import com.avoqado.pos.articles.presentation.products.ProductListView
import com.avoqado.pos.designsystem.theme.AvoqadoTheme

@Composable
fun ArticlesScreen(
    isTablet: Boolean,
    onDismiss: () -> Unit,
    viewModel: ArticlesViewModel = hiltViewModel(),
) {
    val selectedSection by viewModel.selectedSection.collectAsState()

    if (isTablet) {
        TabletArticlesLayout(
            selectedSection = selectedSection,
            onSectionSelected = { viewModel.selectSection(it) },
            onDismiss = onDismiss,
            viewModel = viewModel,
        )
    } else {
        PhoneArticlesLayout(
            selectedSection = selectedSection,
            onSectionSelected = { viewModel.selectSection(it) },
            onDismiss = onDismiss,
            viewModel = viewModel,
        )
    }
}

@Composable
private fun TabletArticlesLayout(
    selectedSection: ArticleSection,
    onSectionSelected: (ArticleSection) -> Unit,
    onDismiss: () -> Unit,
    viewModel: ArticlesViewModel,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Sidebar
        Column(
            modifier = Modifier
                .width(280.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                }
                Text(
                    "Articulos",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = AvoqadoTheme.spacing.sm),
                )
            }

            HorizontalDivider()

            // Section list
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(ArticleSection.entries) { section ->
                    SectionRow(
                        section = section,
                        isSelected = section == selectedSection,
                        onClick = { onSectionSelected(section) },
                    )
                }
            }
        }

        // Vertical divider between sidebar and content
        VerticalDivider()

        // Content
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            SectionContent(section = selectedSection, viewModel = viewModel)
        }
    }
}

@Composable
private fun PhoneArticlesLayout(
    selectedSection: ArticleSection?,
    onSectionSelected: (ArticleSection) -> Unit,
    onDismiss: () -> Unit,
    viewModel: ArticlesViewModel,
) {
    var showingSection by remember { mutableStateOf<ArticleSection?>(null) }

    if (showingSection != null) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top bar with back
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { showingSection = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                }
                Text(
                    showingSection!!.label,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = AvoqadoTheme.spacing.sm),
                )
            }
            HorizontalDivider()
            SectionContent(
                section = showingSection!!,
                viewModel = viewModel,
            )
        }
    } else {
        // Section list
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AvoqadoTheme.spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Volver")
                }
                Text(
                    "Articulos",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = AvoqadoTheme.spacing.sm),
                )
            }
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(ArticleSection.entries) { section ->
                    SectionRow(
                        section = section,
                        isSelected = false,
                        onClick = {
                            onSectionSelected(section)
                            showingSection = section
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionRow(
    section: ArticleSection,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val icon = sectionIcon(section)
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(backgroundColor)
            .padding(horizontal = AvoqadoTheme.spacing.lg, vertical = AvoqadoTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            section.label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun SectionContent(
    section: ArticleSection,
    viewModel: ArticlesViewModel,
) {
    when (section) {
        ArticleSection.PRODUCTS -> ProductListView(viewModel = viewModel)
        ArticleSection.CATEGORIES -> CategoryListView(viewModel = viewModel)
        ArticleSection.MODIFIERS -> ModifierGroupListView(viewModel = viewModel)
        ArticleSection.DISCOUNTS -> DiscountListView(viewModel = viewModel)
        ArticleSection.COUPONS -> CouponListView(viewModel = viewModel)
        ArticleSection.CREDIT_PACKS -> CreditPackListView(viewModel = viewModel)
    }
}

private fun sectionIcon(section: ArticleSection): ImageVector = when (section) {
    ArticleSection.PRODUCTS -> Icons.Filled.LocalOffer
    ArticleSection.CATEGORIES -> Icons.Filled.Folder
    ArticleSection.MODIFIERS -> Icons.Filled.Tune
    ArticleSection.DISCOUNTS -> Icons.Filled.Percent
    ArticleSection.COUPONS -> Icons.Filled.ConfirmationNumber
    ArticleSection.CREDIT_PACKS -> Icons.Filled.CreditCard
}
```

- [ ] **Step 2: Create stub views for all 6 sections**

Create minimal placeholder composables so `ArticlesScreen` compiles. Each will be replaced in later tasks. Files to create with placeholder `@Composable fun <Name>(viewModel: ArticlesViewModel) { Text("TODO") }`:

- `articles/presentation/products/ProductListView.kt`
- `articles/presentation/categories/CategoryListView.kt`
- `articles/presentation/modifiers/ModifierGroupListView.kt`
- `articles/presentation/discounts/DiscountListView.kt`
- `articles/presentation/coupons/CouponListView.kt`
- `articles/presentation/creditpacks/CreditPackListView.kt`

Each stub follows this pattern (adjust package and name):
```kotlin
package com.avoqado.pos.articles.presentation.products

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.avoqado.pos.articles.presentation.ArticlesViewModel

@Composable
fun ProductListView(viewModel: ArticlesViewModel) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Articulos - TODO")
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/articles/presentation/
git commit -m "feat(articles): add ArticlesScreen shell with sidebar/nav and section stubs"
```

---

### Task 7: More Menu Entry Point + Navigation

**Files:**
- Modify: `app/src/main/java/com/avoqado/pos/settings/MoreMenuScreen.kt`
- Modify: `app/src/main/java/com/avoqado/pos/settings/MoreMenuViewModel.kt`

Add "Articulos" row to More menu, gated by `canCreateProducts`. Opens as fullscreen overlay.

- [ ] **Step 1: Read current MoreMenuScreen.kt and MoreMenuViewModel.kt**

Read both files to find the exact insertion points for the new menu entry.

- [ ] **Step 2: Inject RoleManager into MoreMenuViewModel**

The current `MoreMenuViewModel` constructor does NOT have `RoleManager`. Add it:

```kotlin
// Before:
class MoreMenuViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val authRepository: AuthRepository,
    val timeEntryRepository: TimeEntryRepository,
    val printerService: PrinterService,
) : ViewModel()

// After:
class MoreMenuViewModel @Inject constructor(
    private val secureStorage: SecureStorage,
    private val authRepository: AuthRepository,
    private val roleManager: RoleManager,
    val timeEntryRepository: TimeEntryRepository,
    val printerService: PrinterService,
) : ViewModel()
```

Add import: `import com.avoqado.pos.core.domain.RoleManager`

Then add the computed property:

```kotlin
val canCreateProducts: Boolean get() = roleManager.canCreateProducts
```

- [ ] **Step 3: Add "Articulos" row to MoreMenuScreen**

In `MoreMenuScreen.kt`, add state and UI:

```kotlin
// State
var showArticles by remember { mutableStateOf(false) }

// In the General section, add row (role-gated):
if (viewModel.canCreateProducts) {
    MenuRow(
        icon = Icons.Filled.LocalOffer,
        label = "Articulos",
        onClick = { showArticles = true },
    )
}

// Fullscreen overlay (must detect tablet via BoxWithConstraints):
if (showArticles) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        val isTablet = maxWidth >= 600.dp
        ArticlesScreen(
            isTablet = isTablet,
            onDismiss = { showArticles = false },
        )
    }
}
```

**Imports to add:**
```kotlin
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.filled.LocalOffer
import com.avoqado.pos.articles.presentation.ArticlesScreen
```

- [ ] **Step 4: Add import**

```kotlin
import com.avoqado.pos.articles.presentation.ArticlesScreen
```

- [ ] **Step 5: Verify compilation**

Run: `cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/settings/MoreMenuScreen.kt app/src/main/java/com/avoqado/pos/settings/MoreMenuViewModel.kt
git commit -m "feat(articles): add Articles entry point to More menu (role-gated)"
```

---

## Phase 3: Product Views

### Task 8: ProductListView

**Files:**
- Replace stub: `app/src/main/java/com/avoqado/pos/articles/presentation/products/ProductListView.kt`

Product list with search bar, rows (avatar + name + category + price), FAB, empty state, pull-to-refresh. Context menu for edit/delete.

- [ ] **Step 1: Implement ProductListView**

Key UI elements:
- **Header:** "Todos los articulos" + FAB circle (36x36, primary bg, white "+" icon)
- **Search bar:** Row with magnifying glass + TextField + clear X button, gray background (surfaceVariant), cornerRadius.md
- **List:** LazyColumn with pull-to-refresh (`pullRefresh` modifier or `PullToRefreshBox`)
- **Product row:** Row with:
  - Avatar: 44x44 Box, RoundedCornerShape(sm), category color at 20% opacity (or surfaceVariant if no category color), Text with 2-letter initials
  - Column: name (bodyMedium, primary), category name (bodySmall, secondary)
  - Price: bodyMedium, right-aligned. Show "Variable" if priceType == "VARIABLE"
- **Empty state:** Center Column with LocalOffer icon (48dp), "No hay articulos", "Crea tu primer articulo para empezar"
- **Tap row:** Set state to show ProductDetailView in edit mode
- **Long press / context menu (DropdownMenu):** "Editar", "Eliminar" (with confirmation AlertDialog)

State management:
- `var showDetail by remember { mutableStateOf<ArticleProduct?>(null) }` (null = list, non-null = detail, new() = create)
- `var showCreateForm by remember { mutableStateOf(false) }`
- Filter products by search query (client-side on name)

```kotlin
// ProductDetailView import needed — create stub first if not yet implemented
if (showCreateForm) {
    ProductDetailView(
        product = null,
        viewModel = viewModel,
        onDismiss = { showCreateForm = false },
    )
} else if (showDetail != null) {
    ProductDetailView(
        product = showDetail,
        viewModel = viewModel,
        onDismiss = { showDetail = null },
    )
} else {
    // Show list UI
}
```

- [ ] **Step 2: Create ProductDetailView stub**

Create `articles/presentation/products/ProductDetailView.kt` with minimal placeholder:
```kotlin
@Composable
fun ProductDetailView(
    product: ArticleProduct?,
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize(), Alignment.Center) { Text("Product form - TODO") }
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/articles/presentation/products/
git commit -m "feat(articles): implement ProductListView with search, rows, FAB, empty state"
```

---

### Task 9: ProductDetailView (Create/Edit Form)

**Files:**
- Replace stub: `app/src/main/java/com/avoqado/pos/articles/presentation/products/ProductDetailView.kt`

Full scrollable form with grouped sections. Gray background (`surfaceVariant`), white cards for each section.

- [ ] **Step 1: Implement ProductDetailView**

**Note:** Throughout modifier view files, `ArticleModifier` (from `com.avoqado.pos.articles.data.model`) must be imported explicitly — do NOT confuse with `androidx.compose.ui.Modifier`.

Parameters:
- `product: ArticleProduct?` — null = create mode, non-null = edit mode
- `viewModel: ArticlesViewModel`
- `onDismiss: () -> Unit`

Form state (all `remember { mutableStateOf(...) }` initialized from `product`):
- `name`, `description`, `type` (ProductType), `categoryId`, `sku`, `gtin`
- `priceType` (PriceType), `price` (String for TextField), `cost` (String)
- `taxRate` (Double, default 0.16), `isActive` (Boolean)
- `trackInventory`, `inventoryMethod` (InventoryMethod), `unit` (MeasurementUnit)

Layout:
```
Scaffold(
    topBar = {
        TopAppBar(
            title = { if creating: "Nuevo articulo" else: "Editar articulo" },
            navigationIcon = { TextButton("Cancelar") { onDismiss() } },
            actions = { TextButton("Crear"/"Guardar", enabled = name.isNotBlank(), loading = isSaving) }
        )
    }
) {
    LazyColumn(Modifier.background(surfaceVariant)) {
        // Avatar section (120x120 rounded rect, gray bg, initials)

        // DETALLES section card
        SectionCard("DETALLES") {
            OutlinedTextField("Nombre", required)
            OutlinedTextField("Descripcion (opcional)", multiline)
        }

        // TIPO Y CATEGORIA section card
        SectionCard("TIPO Y CATEGORIA") {
            DropdownPicker("Tipo", ProductType.entries)
            DropdownPicker("Categoria", categories + "Sin categoria")
        }

        // PRECIO Y CODIGOS section card
        SectionCard("PRECIO Y CODIGOS") {
            OutlinedTextField("SKU", right-aligned)
            OutlinedTextField("Codigo de barras (GTIN)", right-aligned)
            DropdownPicker("Tipo de precio", [Fijo, Variable])
            OutlinedTextField("Precio", prefix="$", disabled when Variable)
            OutlinedTextField("Costo", prefix="$")
        }

        // IMPUESTOS section card
        SectionCard("IMPUESTOS") {
            DropdownPicker("Tasa de impuesto", ["IVA 16%" -> 0.16, "Exento" -> 0.0])
        }

        // INVENTARIO section card
        SectionCard("INVENTARIO") {
            SwitchRow("Rastrear inventario", subtitle)
            if (trackInventory) {
                RadioGroup([QUANTITY, RECIPE], each with icon + label + description)
                if (QUANTITY) { UnitPicker, StockField, ReorderField }
                if (RECIPE) { InfoMessage about recipe config }
            }
        }

        // MODIFICADORES section (edit only, read-only)
        if (product != null && product.modifierGroups.isNotEmpty()) {
            SectionCard("MODIFICADORES") {
                product.modifierGroups.forEach { group info }
            }
        }

        // Active toggle
        SwitchRow("Activo", green tint)

        // Delete button (edit mode only)
        if (product != null) {
            TextButton("Eliminar articulo", color = error) { showDeleteDialog = true }
        }
    }
}
```

Save action calls `viewModel.createProduct(...)` or `viewModel.updateProduct(...)`, then `onDismiss()`.

Delete action shows `AlertDialog` confirmation, then calls `viewModel.deleteProduct(product.id)`, then `onDismiss()`.

**Helper composables** (define inline in same file):
- `SectionCard(title: String, content: @Composable ColumnScope.() -> Unit)` — labeled card
- `SwitchRow(label: String, subtitle: String?, checked: Boolean, onCheckedChange: (Boolean) -> Unit)` — labeled switch

- [ ] **Step 2: Verify compilation**

Run: `cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/articles/presentation/products/ProductDetailView.kt
git commit -m "feat(articles): implement ProductDetailView create/edit form"
```

---

## Phase 4: Category Views

### Task 10: CategoryListView + CategoryFormSheet

**Files:**
- Replace stub: `app/src/main/java/com/avoqado/pos/articles/presentation/categories/CategoryListView.kt`
- Create: `app/src/main/java/com/avoqado/pos/articles/presentation/categories/CategoryFormSheet.kt`

- [ ] **Step 1: Implement CategoryListView**

Rows:
```
[color circle 32x32] Category Name          3 articulos  >
```
- Color circle from hex (parse `Color(android.graphics.Color.parseColor(hex))`, fallback to `outlineVariant`)
- Name: bodyMedium
- Product count: bodySmall, secondary, right-aligned
- Tap → show CategoryFormSheet (edit mode)
- Context menu → Edit / Delete
- FAB → CategoryFormSheet (create mode)
- Empty state: Folder icon + "No hay categorias" + "Crea tu primera categoria"

State: `var editingCategory by remember { mutableStateOf<ArticleCategory?>(null) }` + `var showCreateForm by remember { mutableStateOf(false) }`

- [ ] **Step 2: Implement CategoryFormSheet**

`ModalBottomSheet` or overlay with:
- TextField "Nombre de la categoria" (required)
- TextField "Descripcion (opcional)" (multiline)
- Color picker: 6-column grid of 12 color circles (40x40)
  - Selected = checkmark overlay
  - Colors: `#6B7280, #EF4444, #F97316, #EAB308, #22C55E, #10B981, #06B6D4, #3B82F6, #6366F1, #8B5CF6, #D946EF, #EC4899`
- Cancel + Save buttons

Parameters:
```kotlin
@Composable
fun CategoryFormSheet(
    category: ArticleCategory?, // null = create
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
)
```

- [ ] **Step 3: Verify compilation**

Run: `cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android && ./gradlew compileDebugKotlin 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/avoqado/pos/articles/presentation/categories/
git commit -m "feat(articles): implement CategoryListView and CategoryFormSheet with color picker"
```

---

## Phase 5: Modifier Views

### Task 11: ModifierGroupListView

**Files:**
- Replace stub: `app/src/main/java/com/avoqado/pos/articles/presentation/modifiers/ModifierGroupListView.kt`

Card layout (not rows). Each group is a card with expandable modifiers.

- [ ] **Step 1: Implement ModifierGroupListView**

Card structure:
```
┌──────────────────────────────────────────────┐
│ v  Group Name        [Requerido]  3 opts  >  │  ← header row (two tap targets)
│ ─────────────────────────────────────────── │
│    Modifier Name               +$15.00    >  │  ← expanded modifiers
│    Another Modifier            +$10.00    >  │
└──────────────────────────────────────────────┘
```

- **Chevron button** (left): toggles expand/collapse
- **Group name + right arrow**: taps to open ModifierGroupFormSheet
- **Badge:** "Requerido" (blue capsule) or "Opcional" (gray capsule)
- **Modifier count:** right side
- **Expanded modifiers:** each is a clickable row → opens ModifierDetailSheet
- FAB → ModifierGroupFormSheet (create mode)
- Context menu on group → Edit / Delete

State:
```kotlin
var expandedGroupIds by remember { mutableStateOf(setOf<String>()) }
var editingGroup by remember { mutableStateOf<ModifierGroup?>(null) }
var editingModifier by remember { mutableStateOf<Pair<String, ArticleModifier>?>(null) } // (groupId, modifier)
var showCreateForm by remember { mutableStateOf(false) }
```

- [ ] **Step 2: Create stubs for ModifierGroupFormSheet and ModifierDetailSheet**

```kotlin
// ModifierGroupFormSheet.kt
@Composable
fun ModifierGroupFormSheet(
    group: ModifierGroup?,
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) { /* TODO */ }

// ModifierDetailSheet.kt
@Composable
fun ModifierDetailSheet(
    groupId: String,
    modifier: ArticleModifier,
    viewModel: ArticlesViewModel,
    onDismiss: () -> Unit,
) { /* TODO */ }
```

- [ ] **Step 3: Verify compilation and commit**

```bash
git add app/src/main/java/com/avoqado/pos/articles/presentation/modifiers/
git commit -m "feat(articles): implement ModifierGroupListView with expandable cards"
```

---

### Task 12: ModifierGroupFormSheet

**Files:**
- Replace stub: `app/src/main/java/com/avoqado/pos/articles/presentation/modifiers/ModifierGroupFormSheet.kt`

- [ ] **Step 1: Implement ModifierGroupFormSheet**

`ModalBottomSheet` or overlay with sections:

**Group Details:**
- TextField "Nombre del grupo (ej: Tamano)"

**Selection Rules:**
- SwitchRow "Requerido"
- SwitchRow "Permitir multiples"
- When multiple ON: NumberField "Min selecciones" + NumberField "Max selecciones"

**Options (inline modifiers):**
- "Agregar" TextButton to add empty row
- Each row: `TextField(name) + "$" + TextField(price) + X IconButton`
  - New modifiers (no id): gray X, instant removal from list
  - Existing modifiers (has id): red X, confirmation dialog before marking deleted
- Use `mutableStateListOf<InlineModifierState>()` for the modifier list
  - `InlineModifierState(id: String? = null, name: String, price: String, isDeleted: Boolean)`

**IMPORTANT:** For inline modifier editing, use `key(index)` or `key(item.hashCode())` on the `forEach`/`LazyColumn` to avoid recomposition issues. Use direct state mutation, NOT search-by-index patterns.

**Save:**
- Create mode: `viewModel.createModifierGroup(name, required, allowMultiple, min, max, modifiers)`
- Edit mode: `viewModel.updateModifierGroup(groupId, name, required, allowMultiple, min, max, originalModifiers, currentModifiers)`

- [ ] **Step 2: Verify compilation and commit**

```bash
git add app/src/main/java/com/avoqado/pos/articles/presentation/modifiers/ModifierGroupFormSheet.kt
git commit -m "feat(articles): implement ModifierGroupFormSheet with inline modifier editing"
```

---

### Task 13: ModifierDetailSheet

**Files:**
- Replace stub: `app/src/main/java/com/avoqado/pos/articles/presentation/modifiers/ModifierDetailSheet.kt`

- [ ] **Step 1: Implement ModifierDetailSheet**

`ModalBottomSheet` for editing a single modifier with inventory tracking.

**DETALLES:**
- TextField "Nombre"
- TextField "Precio" with "$" prefix
- SwitchRow "Activo"

**INVENTARIO:**
- SwitchRow "Rastrear inventario" with subtitle "Controla existencias de este modificador"
- When ON:
  - **Modo** radio group: Adicion / Sustitucion (each with icon + description)
  - **Materia prima search:** TextField with magnifying glass, live search (debounce 400ms) via `viewModel.searchRawMaterials(query)`. Results in scrollable Box (max 180.dp height). Tap result to select.
  - **Selected material card:** name, SKU, stock info, X to clear
  - **Cantidad por uso:** number field (appears after selecting)
  - **Unidad:** dropdown picker (auto-populated from material's unit)

State:
```kotlin
var name by remember { mutableStateOf(modifier.name) }
var price by remember { mutableStateOf(modifier.price?.toString() ?: "0") }
var active by remember { mutableStateOf(modifier.active ?: true) }
var trackInventory by remember { mutableStateOf(modifier.rawMaterialId != null) }
var inventoryMode by remember { mutableStateOf(
    modifier.inventoryMode?.let { ModifierInventoryMode.valueOf(it) } ?: ModifierInventoryMode.ADDITION
) }
var selectedMaterial by remember { mutableStateOf<RawMaterial?>(null) }
var quantityPerUnit by remember { mutableStateOf(modifier.quantityPerUnit?.toString() ?: "") }
var unit by remember { mutableStateOf(
    modifier.unit?.let { try { MeasurementUnit.valueOf(it) } catch (_: Exception) { null } }
) }
var materialSearchQuery by remember { mutableStateOf("") }
```

Save calls `viewModel.updateModifier(groupId, modifier.id, name, price, active, rawMaterialId, inventoryMode, quantityPerUnit, unit)`.

When `trackInventory` OFF: send nulls for inventory fields.

- [ ] **Step 2: Verify compilation and commit**

```bash
git add app/src/main/java/com/avoqado/pos/articles/presentation/modifiers/ModifierDetailSheet.kt
git commit -m "feat(articles): implement ModifierDetailSheet with inventory tracking"
```

---

## Phase 6: Discount Views

### Task 14: DiscountListView + DiscountFormSheet

**Files:**
- Replace stub: `app/src/main/java/com/avoqado/pos/articles/presentation/discounts/DiscountListView.kt`
- Create: `app/src/main/java/com/avoqado/pos/articles/presentation/discounts/DiscountFormSheet.kt`

- [ ] **Step 1: Implement DiscountListView**

Rows:
```
[emoji 44x44] Descuento empleado
              20% · Orden                ● Activo
```
- Emoji avatar: 44x44 Box with surfaceContainerHigh background, rounded corners, centered emoji text (from `AdminDiscount.emoji`)
- Name: bodyMedium, primary
- Info line: formattedValue + " · " + scope label (bodySmall, secondary)
- Status capsule: green "Activo" / gray "Inactivo" (small Surface with rounded shape)
- Tap → DiscountFormSheet (edit)
- FAB → DiscountFormSheet (create)
- Context menu → Edit / Delete

- [ ] **Step 2: Implement DiscountFormSheet**

`ModalBottomSheet` with:

**DETALLES:** TextField "Nombre del descuento"

**TIPO Y VALOR:**
- Picker/SegmentedButton: Porcentaje / Monto fijo / Cortesia
- Value field (hidden for Cortesia): "$" prefix for fixed, "%" suffix for percentage

**ALCANCE:** Picker: Orden completa / Articulos especificos / Categoria

**OPCIONES:**
- SwitchRow "Activo" (green tint)
- SwitchRow "Requiere aprobacion" (blue tint)

Save: type sent as "PERCENTAGE", "FIXED_AMOUNT", "COMP". For COMP, value = 100.

- [ ] **Step 3: Verify compilation and commit**

```bash
git add app/src/main/java/com/avoqado/pos/articles/presentation/discounts/
git commit -m "feat(articles): implement DiscountListView and DiscountFormSheet"
```

---

## Phase 7: Coupon Views

### Task 15: CouponListView + CouponFormSheet

**Files:**
- Replace stub: `app/src/main/java/com/avoqado/pos/articles/presentation/coupons/CouponListView.kt`
- Create: `app/src/main/java/com/avoqado/pos/articles/presentation/coupons/CouponFormSheet.kt`

- [ ] **Step 1: Implement CouponListView**

Rows:
```
[ticket icon 44x44] SAVE20                    Activo
                     3/10 usos               Expirado
```
- Ticket icon avatar: 44x44, ConfirmationNumber icon in surfaceContainerHigh box
- Code: headlineSmall, semibold
- Usage text: bodySmall, secondary
- Status capsule: green "Activo" / gray "Inactivo"
- Expired badge: if `isExpired`, red capsule "Expirado"
- Tap → CouponFormSheet (edit)
- FAB → CouponFormSheet (create)

- [ ] **Step 2: Implement CouponFormSheet**

`ModalBottomSheet` with:

**CODIGO:** TextField with uppercase capitalization (`KeyboardOptions(capitalization = KeyboardCapitalization.Characters)`). Helper text: "3-30 caracteres, letras y numeros"

**DESCUENTO ASOCIADO:** Dropdown picker from `viewModel.discounts` showing "name (formattedValue)"

**LIMITES DE USO:**
- TextField "Usos totales" (number keyboard, placeholder "Ilimitado")
- TextField "Usos por cliente" (number keyboard, placeholder "Ilimitado")

**SwitchRow "Activo"**

Save: `viewModel.createCoupon(code, discountId, maxUses, maxUsesPerCustomer, active)`

- [ ] **Step 3: Verify compilation and commit**

```bash
git add app/src/main/java/com/avoqado/pos/articles/presentation/coupons/
git commit -m "feat(articles): implement CouponListView and CouponFormSheet"
```

---

## Phase 8: Credit Pack Views

### Task 16: CreditPackListView + CreditPackFormSheet

**Files:**
- Replace stub: `app/src/main/java/com/avoqado/pos/articles/presentation/creditpacks/CreditPackListView.kt`
- Create: `app/src/main/java/com/avoqado/pos/articles/presentation/creditpacks/CreditPackFormSheet.kt`

- [ ] **Step 1: Implement CreditPackListView**

Card layout (not rows):
```
┌──────────────────────────────────────────────┐
│ Paquete Premium                     $150.00  │
│ Descripcion del paquete...                   │
│ 📦 5 articulos  🕐 30 dias         ● Activo │
│ ──────────────────────────────────────────── │
│ Cafe Americano                          x3   │
│ Pan dulce                               x2   │
└──────────────────────────────────────────────┘
```
- Card with border (outlineVariant, 1dp, cornerRadius.md)
- Name: headlineSmall + price right-aligned
- Description: bodySmall, 2 lines max
- Icons row: Inventory2 icon + itemCount + " articulos", Schedule icon + validityDays + " dias"
- Status capsule
- Divider
- Items list: product name + "x" + quantity
- Tap → CreditPackFormSheet (edit)
- FAB → CreditPackFormSheet (create)

- [ ] **Step 2: Implement CreditPackFormSheet**

`ModalBottomSheet` with:

**DETALLES:** TextField "Nombre" + TextField "Descripcion (opcional)" multiline

**PRECIO Y VALIDEZ:**
- TextField "Precio" with "$" prefix
- TextField "Dias de validez" (number, placeholder "Sin limite")
- TextField "Max por cliente" (number, placeholder "Ilimitado")

**ARTICULOS INCLUIDOS:**
- "Agregar" TextButton
- Each row: Dropdown picker (from `viewModel.products`) + "x" + NumberField quantity + X delete button
- Use `mutableStateListOf<CreditPackItemState>()` for the items
  - `CreditPackItemState(productId: String, quantity: String)`

**SwitchRow "Activo"**

Save: `viewModel.createCreditPack(name, description, price, validityDays, maxPerCustomer, active, items)`

- [ ] **Step 3: Verify compilation and commit**

```bash
git add app/src/main/java/com/avoqado/pos/articles/presentation/creditpacks/
git commit -m "feat(articles): implement CreditPackListView and CreditPackFormSheet"
```

---

## Phase 9: Final Integration + Polish

### Task 17: Error Handling UI + Full Integration Verification

**Files:** Potentially modify any screen that doesn't yet consume error state.

**Error handling pattern:** Each list/form screen should observe `viewModel.errorMessage` and show a `Snackbar` when non-null:

```kotlin
val errorMessage by viewModel.errorMessage.collectAsState()
val snackbarHostState = remember { SnackbarHostState() }

LaunchedEffect(errorMessage) {
    errorMessage?.let {
        snackbarHostState.showSnackbar(it)
        viewModel.clearError()
    }
}

// Use Scaffold with snackbarHost = { SnackbarHost(snackbarHostState) }
```

- [ ] **Step 1: Full build**

Run: `cd /Users/amieva/Documents/Programming/Avoqado/avoqado-android && ./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Verify navigation flow**

Install on device/emulator and manually test:
1. Login as MANAGER+ role
2. Go to More tab
3. Tap "Articulos"
4. Verify sidebar appears on tablet, section list on phone
5. Navigate through all 6 sections
6. Verify each list loads (may be empty)
7. Try creating one product, one category
8. Verify back navigation works

- [ ] **Step 3: Fix any compilation or runtime issues found**

Address any issues discovered during testing.

- [ ] **Step 4: Final commit**

```bash
git add -A
git commit -m "feat(articles): final integration and polish"
```

---

## Summary

| Phase | Tasks | Files Created | Files Modified |
|-------|-------|--------------|----------------|
| 1. Data Layer | 1-4 | ArticlesModels.kt, ArticlesRepository.kt | (verify RoleManager.kt) |
| 2. ViewModel + Nav | 5-7 | ArticlesViewModel.kt, ArticlesScreen.kt, 6 stubs | MoreMenuScreen.kt, MoreMenuViewModel.kt |
| 3. Products | 8-9 | ProductListView.kt, ProductDetailView.kt | — |
| 4. Categories | 10 | CategoryListView.kt, CategoryFormSheet.kt | — |
| 5. Modifiers | 11-13 | ModifierGroupListView.kt, ModifierGroupFormSheet.kt, ModifierDetailSheet.kt | — |
| 6. Discounts | 14 | DiscountListView.kt, DiscountFormSheet.kt | — |
| 7. Coupons | 15 | CouponListView.kt, CouponFormSheet.kt | — |
| 8. Credit Packs | 16 | CreditPackListView.kt, CreditPackFormSheet.kt | — |
| 9. Integration | 17 | — | — |

**Total: 17 tasks, ~15 new files, ~2 modified files**

**Key naming convention:** The data model uses `ArticleModifier` (not `Modifier`) to avoid collision with `androidx.compose.ui.Modifier` in Compose files.
