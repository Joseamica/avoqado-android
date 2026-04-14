package com.avoqado.pos.articles.presentation

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.articles.data.ArticlesRepository
import com.avoqado.pos.articles.data.model.ArticleModifier
import com.avoqado.pos.articles.data.model.ArticleSection
import com.avoqado.pos.articles.data.model.DiscountScope
import com.avoqado.pos.articles.data.model.DiscountType
import com.avoqado.pos.articles.data.model.PriceType
import com.avoqado.pos.articles.data.model.ProductOption
import com.avoqado.pos.articles.data.model.ProductType
import com.avoqado.pos.articles.data.model.RawMaterial
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.UUID
import javax.inject.Inject

private const val TAG = "🗂️ ArticlesVM"

@HiltViewModel
class ArticlesViewModel @Inject constructor(
    private val repository: ArticlesRepository,
) : ViewModel() {

    // MARK: - Expose repository flows

    val products = repository.products
    val categories = repository.categories
    val modifierGroups = repository.modifierGroups
    val discounts = repository.discounts
    val coupons = repository.coupons
    val creditPacks = repository.creditPacks
    val productOptions = repository.productOptions
    val isLoading = repository.isLoading
    val errorMessage = repository.errorMessage

    // MARK: - Local state

    private val _selectedSection = MutableStateFlow(ArticleSection.PRODUCTS)
    val selectedSection: StateFlow<ArticleSection> = _selectedSection.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _rawMaterialResults = MutableStateFlow<List<RawMaterial>>(emptyList())
    val rawMaterialResults: StateFlow<List<RawMaterial>> = _rawMaterialResults.asStateFlow()

    private val _customUnits = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val customUnits: StateFlow<List<Pair<String, String>>> = _customUnits.asStateFlow()

    // MARK: - Inner data classes

    data class InlineModifier(
        val id: String? = null,
        val name: String,
        val price: Double,
        val isDeleted: Boolean = false,
    )

    data class CreditPackItemInput(
        val productId: String,
        val quantity: Int = 1,
    )

    // MARK: - Init

    init {
        loadSectionData(ArticleSection.PRODUCTS)
    }

    // MARK: - Navigation

    fun selectSection(section: ArticleSection) {
        _selectedSection.value = section
        loadSectionData(section)
    }

    fun loadSectionData(section: ArticleSection) {
        viewModelScope.launch {
            when (section) {
                ArticleSection.PRODUCTS -> {
                    repository.fetchProducts()
                    repository.fetchCategories()
                }
                ArticleSection.CATEGORIES -> {
                    repository.fetchCategories()
                }
                ArticleSection.MODIFIERS -> {
                    repository.fetchModifierGroups()
                }
                ArticleSection.DISCOUNTS -> {
                    repository.fetchDiscounts()
                }
                ArticleSection.COUPONS -> {
                    repository.fetchCoupons()
                    repository.fetchDiscounts()
                }
                ArticleSection.OPTIONS -> {
                    repository.fetchProductOptions()
                }
                ArticleSection.CREDIT_PACKS -> {
                    repository.fetchCreditPacks()
                    repository.fetchProducts()
                }
                ArticleSection.UNITS -> {
                    // Built-in units are always available; no fetch needed
                }
            }
        }
    }

    fun refresh() {
        loadSectionData(_selectedSection.value)
    }

    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    // MARK: - Products CRUD

    fun createProduct(
        name: String,
        description: String? = null,
        type: ProductType,
        categoryId: String? = null,
        sku: String? = null,
        gtin: String? = null,
        priceType: PriceType,
        price: Double? = null,
        cost: Double? = null,
        taxRate: Double,
        isActive: Boolean,
        trackInventory: Boolean,
        inventoryMethod: String? = null,
        unit: String? = null,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                // Generate SKU if not provided (mobile route may require it)
                val resolvedSku = sku ?: UUID.randomUUID().toString().take(8).uppercase()
                val payload = buildJsonObject {
                    put("name", name)
                    if (description != null) put("description", description)
                    put("type", type.name)
                    if (categoryId != null) put("categoryId", categoryId)
                    put("sku", resolvedSku)
                    if (gtin != null) put("gtin", gtin)
                    put("priceType", priceType.name)
                    if (price != null) put("price", price)
                    if (cost != null) put("cost", cost)
                    put("taxRate", taxRate)
                    put("active", isActive)
                    put("trackInventory", trackInventory)
                    if (inventoryMethod != null) put("inventoryMethod", inventoryMethod)
                    if (unit != null) put("unit", unit)
                }.toString()
                val success = repository.createProduct(payload)
                Log.d(TAG, if (success) "✅ Product created" else "❌ Product creation failed")
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun updateProduct(
        productId: String,
        name: String,
        description: String? = null,
        type: ProductType,
        categoryId: String? = null,
        sku: String? = null,
        gtin: String? = null,
        priceType: PriceType,
        price: Double? = null,
        cost: Double? = null,
        taxRate: Double,
        isActive: Boolean,
        trackInventory: Boolean,
        inventoryMethod: String? = null,
        unit: String? = null,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val payload = buildJsonObject {
                    put("name", name)
                    if (description != null) put("description", description)
                    put("type", type.name)
                    if (categoryId != null) put("categoryId", categoryId)
                    if (sku != null) put("sku", sku)
                    if (gtin != null) put("gtin", gtin)
                    put("priceType", priceType.name)
                    if (price != null) put("price", price)
                    if (cost != null) put("cost", cost)
                    put("taxRate", taxRate)
                    put("active", isActive)
                    put("trackInventory", trackInventory)
                    if (inventoryMethod != null) put("inventoryMethod", inventoryMethod)
                    if (unit != null) put("unit", unit)
                }.toString()
                val success = repository.updateProduct(productId, payload)
                Log.d(TAG, if (success) "✅ Product $productId updated" else "❌ Product update failed")
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteProduct(productId: String) {
        viewModelScope.launch {
            repository.deleteProduct(productId)
        }
    }

    // MARK: - Categories CRUD

    fun createCategory(
        name: String,
        description: String? = null,
        color: String? = null,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val payload = buildJsonObject {
                    put("name", name)
                    if (description != null) put("description", description)
                    if (color != null) put("color", color)
                }.toString()
                val success = repository.createCategory(payload)
                Log.d(TAG, if (success) "✅ Category created" else "❌ Category creation failed")
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun updateCategory(
        categoryId: String,
        name: String,
        description: String? = null,
        color: String? = null,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val payload = buildJsonObject {
                    put("name", name)
                    if (description != null) put("description", description)
                    if (color != null) put("color", color)
                }.toString()
                val success = repository.updateCategory(categoryId, payload)
                Log.d(TAG, if (success) "✅ Category $categoryId updated" else "❌ Category update failed")
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteCategory(categoryId: String) {
        viewModelScope.launch {
            repository.deleteCategory(categoryId)
        }
    }

    // MARK: - Modifier Groups CRUD

    fun createModifierGroup(
        name: String,
        required: Boolean,
        allowMultiple: Boolean,
        minSelections: Int? = null,
        maxSelections: Int? = null,
        modifiers: List<InlineModifier>,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val payload = buildJsonObject {
                    put("name", name)
                    put("required", required)
                    put("allowMultiple", allowMultiple)
                    if (minSelections != null) put("minSelections", minSelections)
                    if (maxSelections != null) put("maxSelections", maxSelections)
                    putJsonArray("modifiers") {
                        modifiers.forEach { m ->
                            add(buildJsonObject {
                                put("name", m.name)
                                put("price", m.price)
                            })
                        }
                    }
                }.toString()
                val success = repository.createModifierGroup(payload)
                Log.d(TAG, if (success) "✅ Modifier group created" else "❌ Modifier group creation failed")
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun updateModifierGroup(
        groupId: String,
        name: String,
        required: Boolean,
        allowMultiple: Boolean,
        minSelections: Int? = null,
        maxSelections: Int? = null,
        originalModifiers: List<ArticleModifier>,
        currentModifiers: List<InlineModifier>,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                // PATCH group metadata
                val groupPayload = buildJsonObject {
                    put("name", name)
                    put("required", required)
                    put("allowMultiple", allowMultiple)
                    if (minSelections != null) put("minSelections", minSelections)
                    if (maxSelections != null) put("maxSelections", maxSelections)
                }.toString()
                repository.updateModifierGroup(groupId, groupPayload)

                // DELETE removed modifiers (exist in original but not in current)
                val currentIds = currentModifiers.mapNotNull { it.id }.toSet()
                val deletedModifiers = originalModifiers.filter { it.id !in currentIds }
                for (deleted in deletedModifiers) {
                    repository.deleteModifier(groupId, deleted.id)
                }

                // Process current modifiers
                for (modifier in currentModifiers) {
                    if (modifier.isDeleted) continue

                    if (modifier.id != null) {
                        // PATCH existing modifier
                        val modPayload = buildJsonObject {
                            put("name", modifier.name)
                            put("price", modifier.price)
                        }.toString()
                        repository.updateModifier(groupId, modifier.id, modPayload)
                    } else {
                        // POST new modifier
                        val newModPayload = buildJsonObject {
                            put("name", modifier.name)
                            put("price", modifier.price)
                        }.toString()
                        repository.addModifierToGroup(groupId, newModPayload)
                    }
                }

                Log.d(TAG, "✅ Modifier group $groupId fully updated")
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteModifierGroup(groupId: String) {
        viewModelScope.launch {
            repository.deleteModifierGroup(groupId)
        }
    }

    // MARK: - Individual Modifier

    fun updateModifier(
        groupId: String,
        modifierId: String,
        name: String,
        price: Double,
        active: Boolean,
        rawMaterialId: String? = null,
        inventoryMode: String? = null,
        quantityPerUnit: Double? = null,
        unit: String? = null,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val payload = buildJsonObject {
                    put("name", name)
                    put("price", price)
                    put("active", active)
                    if (rawMaterialId != null) {
                        put("rawMaterialId", rawMaterialId)
                        if (inventoryMode != null) put("inventoryMode", inventoryMode)
                        if (quantityPerUnit != null) put("quantityPerUnit", quantityPerUnit)
                        if (unit != null) put("unit", unit)
                    } else {
                        put("rawMaterialId", JsonNull)
                        put("inventoryMode", JsonNull)
                        put("quantityPerUnit", JsonNull)
                        put("unit", JsonNull)
                    }
                }.toString()
                val success = repository.updateModifier(groupId, modifierId, payload)
                Log.d(TAG, if (success) "✅ Modifier $modifierId updated" else "❌ Modifier update failed")
            } finally {
                _isSaving.value = false
            }
        }
    }

    // MARK: - Raw Material Search

    fun searchRawMaterials(query: String) {
        if (query.length < 2) return
        viewModelScope.launch {
            val results = repository.searchRawMaterials(query)
            _rawMaterialResults.value = results
        }
    }

    fun clearRawMaterialResults() {
        _rawMaterialResults.value = emptyList()
    }

    // MARK: - Discounts CRUD

    fun createDiscount(
        name: String,
        type: DiscountType,
        value: Double,
        scope: DiscountScope,
        active: Boolean,
        requiresApproval: Boolean,
        targetItemIds: List<String> = emptyList(),
        targetCategoryIds: List<String> = emptyList(),
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val typeString = when (type) {
                    DiscountType.FIXED -> "FIXED_AMOUNT"
                    DiscountType.COMP -> "COMP"
                    DiscountType.PERCENTAGE -> "PERCENTAGE"
                }
                val resolvedValue = if (type == DiscountType.COMP) 100.0 else value
                val payload = buildJsonObject {
                    put("name", name)
                    put("type", typeString)
                    put("value", resolvedValue)
                    put("scope", scope.name)
                    put("active", active)
                    put("requiresApproval", requiresApproval)
                    if (scope == DiscountScope.ITEM) {
                        putJsonArray("targetItemIds") {
                            targetItemIds.forEach { add(it) }
                        }
                    }
                    if (scope == DiscountScope.CATEGORY) {
                        putJsonArray("targetCategoryIds") {
                            targetCategoryIds.forEach { add(it) }
                        }
                    }
                }.toString()
                val success = repository.createDiscount(payload)
                Log.d(TAG, if (success) "✅ Discount created" else "❌ Discount creation failed")
            } finally {
                _isSaving.value = false
            }
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
        targetItemIds: List<String> = emptyList(),
        targetCategoryIds: List<String> = emptyList(),
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val typeString = when (type) {
                    DiscountType.FIXED -> "FIXED_AMOUNT"
                    DiscountType.COMP -> "COMP"
                    DiscountType.PERCENTAGE -> "PERCENTAGE"
                }
                val resolvedValue = if (type == DiscountType.COMP) 100.0 else value
                val payload = buildJsonObject {
                    put("name", name)
                    put("type", typeString)
                    put("value", resolvedValue)
                    put("scope", scope.name)
                    put("active", active)
                    put("requiresApproval", requiresApproval)
                    if (scope == DiscountScope.ITEM) {
                        putJsonArray("targetItemIds") {
                            targetItemIds.forEach { add(it) }
                        }
                    }
                    if (scope == DiscountScope.CATEGORY) {
                        putJsonArray("targetCategoryIds") {
                            targetCategoryIds.forEach { add(it) }
                        }
                    }
                }.toString()
                val success = repository.updateDiscount(discountId, payload)
                Log.d(TAG, if (success) "✅ Discount $discountId updated" else "❌ Discount update failed")
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteDiscount(discountId: String) {
        viewModelScope.launch {
            repository.deleteDiscount(discountId)
        }
    }

    // MARK: - Coupons CRUD

    fun createCoupon(
        code: String,
        discountId: String,
        maxUses: Int? = null,
        maxUsesPerCustomer: Int? = null,
        active: Boolean,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val payload = buildJsonObject {
                    put("code", code.uppercase())
                    put("discountId", discountId)
                    if (maxUses != null) put("maxUses", maxUses)
                    if (maxUsesPerCustomer != null) put("maxUsesPerCustomer", maxUsesPerCustomer)
                    put("active", active)
                }.toString()
                val success = repository.createCoupon(payload)
                Log.d(TAG, if (success) "✅ Coupon created" else "❌ Coupon creation failed")
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun updateCoupon(
        couponId: String,
        code: String,
        discountId: String,
        maxUses: Int? = null,
        maxUsesPerCustomer: Int? = null,
        active: Boolean,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val payload = buildJsonObject {
                    put("code", code.uppercase())
                    put("discountId", discountId)
                    if (maxUses != null) put("maxUses", maxUses)
                    if (maxUsesPerCustomer != null) put("maxUsesPerCustomer", maxUsesPerCustomer)
                    put("active", active)
                }.toString()
                val success = repository.updateCoupon(couponId, payload)
                Log.d(TAG, if (success) "✅ Coupon $couponId updated" else "❌ Coupon update failed")
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteCoupon(couponId: String) {
        viewModelScope.launch {
            repository.deleteCoupon(couponId)
        }
    }

    // MARK: - Credit Packs CRUD

    fun createCreditPack(
        name: String,
        description: String? = null,
        price: Double,
        validityDays: Int? = null,
        maxPerCustomer: Int? = null,
        active: Boolean,
        items: List<CreditPackItemInput>,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val filteredItems = items.filter { it.productId.isNotEmpty() }
                val payload = buildJsonObject {
                    put("name", name)
                    if (description != null) put("description", description)
                    put("price", price)
                    if (validityDays != null) put("validityDays", validityDays)
                    if (maxPerCustomer != null) put("maxPerCustomer", maxPerCustomer)
                    put("active", active)
                    putJsonArray("items") {
                        filteredItems.forEach { item ->
                            add(buildJsonObject {
                                put("productId", item.productId)
                                put("quantity", item.quantity)
                            })
                        }
                    }
                }.toString()
                val success = repository.createCreditPack(payload)
                Log.d(TAG, if (success) "✅ Credit pack created" else "❌ Credit pack creation failed")
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun updateCreditPack(
        packId: String,
        name: String,
        description: String? = null,
        price: Double,
        validityDays: Int? = null,
        maxPerCustomer: Int? = null,
        active: Boolean,
        items: List<CreditPackItemInput>,
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val filteredItems = items.filter { it.productId.isNotEmpty() }
                val payload = buildJsonObject {
                    put("name", name)
                    if (description != null) put("description", description)
                    put("price", price)
                    if (validityDays != null) put("validityDays", validityDays)
                    if (maxPerCustomer != null) put("maxPerCustomer", maxPerCustomer)
                    put("active", active)
                    putJsonArray("items") {
                        filteredItems.forEach { item ->
                            add(buildJsonObject {
                                put("productId", item.productId)
                                put("quantity", item.quantity)
                            })
                        }
                    }
                }.toString()
                val success = repository.updateCreditPack(packId, payload)
                Log.d(TAG, if (success) "✅ Credit pack $packId updated" else "❌ Credit pack update failed")
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteCreditPack(packId: String) {
        viewModelScope.launch {
            repository.deleteCreditPack(packId)
        }
    }

    // MARK: - Product Options CRUD

    fun createProductOption(
        name: String,
        values: List<String>,
        onSuccess: () -> Unit = {},
    ) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val payload = buildJsonObject {
                    put("name", name)
                    putJsonArray("values") {
                        values.forEach { v ->
                            add(buildJsonObject { put("name", v) })
                        }
                    }
                }.toString()
                val success = repository.createProductOption(payload)
                if (success) {
                    Log.d(TAG, "✅ Product option created")
                    onSuccess()
                } else {
                    Log.d(TAG, "❌ Product option creation failed")
                }
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun deleteProductOption(optionId: String) {
        viewModelScope.launch {
            repository.deleteProductOption(optionId)
        }
    }

    // MARK: - Custom Units (local)

    fun addCustomUnit(name: String, abbreviation: String) {
        val current = _customUnits.value.toMutableList()
        current.add(name to abbreviation)
        _customUnits.value = current
    }

    fun updateCustomUnit(index: Int, name: String, abbreviation: String) {
        val current = _customUnits.value.toMutableList()
        if (index in current.indices) {
            current[index] = name to abbreviation
            _customUnits.value = current
        }
    }
}
