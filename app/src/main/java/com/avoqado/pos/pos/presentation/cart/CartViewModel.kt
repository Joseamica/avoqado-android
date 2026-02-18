package com.avoqado.pos.pos.presentation.cart

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.SavedCart
import com.avoqado.pos.pos.data.model.SavedCartItem
import com.avoqado.pos.pos.data.model.SavedModifier
import com.avoqado.pos.pos.data.model.SelectedModifier
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class CartState(
    val items: List<CartItem> = emptyList(),
    val orderDiscount: Discount? = null,
    val orderNote: String? = null,
) {
    val itemCount: Int get() = items.sumOf { it.quantity }
    val subtotalCents: Int get() = items.sumOf { it.totalPrice }
    val discountCents: Int
        get() = orderDiscount?.calculateDiscount(subtotalCents) ?: 0
    val totalCents: Int get() = (subtotalCents - discountCents).coerceAtLeast(0)
    val isEmpty: Boolean get() = items.isEmpty()

    val subtotalDisplay: String get() = formatCents(subtotalCents)
    val discountDisplay: String get() = formatCents(discountCents)
    val totalDisplay: String get() = formatCents(totalCents)
}

private fun formatCents(cents: Int): String {
    return "$${String.format("%.2f", cents / 100.0)}"
}

@HiltViewModel
class CartViewModel @Inject constructor(
    val productsRepository: ProductsRepository,
    val discountsRepository: DiscountsRepository,
    private val savedCartsRepository: SavedCartsRepository,
) : ViewModel() {

    private val _cartState = MutableStateFlow(CartState())
    val cartState: StateFlow<CartState> = _cartState.asStateFlow()

    val products = productsRepository.products
    val categories = productsRepository.categories
    val isLoading = productsRepository.isLoading
    val savedCarts = savedCartsRepository.savedCarts

    private val _selectedCategoryId = MutableStateFlow<String?>(null)
    val selectedCategoryId: StateFlow<String?> = _selectedCategoryId.asStateFlow()

    val filteredProducts: StateFlow<List<Product>> = combine(
        productsRepository.products,
        _selectedCategoryId,
    ) { products, categoryId ->
        if (categoryId == null) products
        else products.filter { it.categoryId == categoryId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val searchResults: StateFlow<List<Product>> = combine(
        productsRepository.products,
        _searchQuery,
    ) { products, query ->
        if (query.isBlank()) emptyList()
        else {
            val lower = query.lowercase()
            products.filter {
                it.name.lowercase().contains(lower) ||
                    it.sku?.lowercase()?.contains(lower) == true
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            productsRepository.fetchProducts()
            discountsRepository.fetchDiscounts()
        }
    }

    fun refreshProducts() {
        viewModelScope.launch { productsRepository.fetchProducts() }
    }

    fun selectCategory(categoryId: String?) {
        _selectedCategoryId.value = categoryId
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // MARK: - Cart Operations

    fun addProduct(product: Product) {
        _cartState.update { state ->
            // Check if same product without modifiers already in cart
            val existingIndex = state.items.indexOfFirst {
                it.type is CartItemType.ProductItem &&
                    (it.type as CartItemType.ProductItem).productId == product.id &&
                    it.selectedModifiers.isEmpty() &&
                    !it.isCortesia
            }

            if (existingIndex >= 0 && !product.hasModifiers) {
                val updated = state.items.toMutableList()
                val existing = updated[existingIndex]
                updated[existingIndex] = existing.copy(quantity = existing.quantity + 1)
                state.copy(items = updated)
            } else {
                val newItem = CartItem(
                    type = CartItemType.ProductItem(product.id),
                    name = product.name,
                    unitPrice = product.priceInCents,
                    imageUrl = product.imageUrl,
                    colorHex = product.color,
                    categoryId = product.categoryId,
                )
                state.copy(items = state.items + newItem)
            }
        }
        Log.d("🛒", "Added product: ${product.name}")
    }

    fun addProductWithModifiers(
        product: Product,
        quantity: Int = 1,
        modifiers: List<SelectedModifier>,
        note: String? = null,
        isCortesia: Boolean = false,
        cortesiaReason: String? = null,
        priceAdjustment: Int? = null,
    ) {
        val newItem = CartItem(
            type = CartItemType.ProductItem(product.id),
            name = product.name,
            unitPrice = product.priceInCents,
            quantity = quantity,
            imageUrl = product.imageUrl,
            colorHex = product.color,
            categoryId = product.categoryId,
            selectedModifiers = modifiers,
            itemNote = note,
            isCortesia = isCortesia,
            cortesiaReason = cortesiaReason,
            priceAdjustment = priceAdjustment,
        )
        _cartState.update { it.copy(items = it.items + newItem) }
        Log.d("🛒", "Added product with modifiers: ${product.name} x$quantity (${modifiers.size} mods)")
    }

    fun addCustomAmount(name: String, amountCents: Int) {
        val item = CartItem(
            type = CartItemType.CustomAmount,
            name = name,
            unitPrice = amountCents,
        )
        _cartState.update { it.copy(items = it.items + item) }
    }

    fun removeItem(itemId: String) {
        _cartState.update { state ->
            state.copy(items = state.items.filter { it.id != itemId })
        }
    }

    fun updateQuantity(itemId: String, newQuantity: Int) {
        if (newQuantity <= 0) {
            removeItem(itemId)
            return
        }
        _cartState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.id == itemId) it.copy(quantity = newQuantity) else it
                },
            )
        }
    }

    fun incrementQuantity(itemId: String) {
        _cartState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.id == itemId) it.copy(quantity = it.quantity + 1) else it
                },
            )
        }
    }

    fun decrementQuantity(itemId: String) {
        _cartState.update { state ->
            val item = state.items.find { it.id == itemId } ?: return@update state
            if (item.quantity <= 1) {
                state.copy(items = state.items.filter { it.id != itemId })
            } else {
                state.copy(
                    items = state.items.map {
                        if (it.id == itemId) it.copy(quantity = it.quantity - 1) else it
                    },
                )
            }
        }
    }

    fun applyOrderDiscount(discount: Discount?) {
        _cartState.update { it.copy(orderDiscount = discount) }
    }

    fun setOrderNote(note: String?) {
        _cartState.update { it.copy(orderNote = note) }
    }

    fun markItemAsCortesia(itemId: String, reason: String?) {
        _cartState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.id == itemId) it.copy(isCortesia = true, cortesiaReason = reason)
                    else it
                },
            )
        }
        Log.d("🛒", "Marked item $itemId as cortesia")
    }

    fun updateItemNote(itemId: String, note: String?) {
        _cartState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.id == itemId) it.copy(itemNote = note) else it
                },
            )
        }
    }

    fun updateItemCortesia(itemId: String, isCortesia: Boolean, reason: String?) {
        _cartState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.id == itemId) it.copy(isCortesia = isCortesia, cortesiaReason = reason)
                    else it
                },
            )
        }
    }

    fun updateItemPriceAdjustment(itemId: String, priceCents: Int?) {
        _cartState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.id == itemId) it.copy(priceAdjustment = priceCents) else it
                },
            )
        }
    }

    fun saveCurrentCart(name: String? = null): Boolean {
        val state = _cartState.value
        if (state.isEmpty) return false

        val cartName = name ?: "Carrito ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
        val savedCart = SavedCart(
            id = UUID.randomUUID().toString(),
            name = cartName,
            items = state.items.map { item ->
                SavedCartItem(
                    productId = when (val type = item.type) {
                        is CartItemType.ProductItem -> type.productId
                        is CartItemType.CustomAmount -> null
                    },
                    name = item.name,
                    unitPrice = item.unitPrice,
                    quantity = item.quantity,
                    modifiers = item.selectedModifiers.map { mod ->
                        SavedModifier(
                            groupId = mod.groupId,
                            groupName = mod.groupName,
                            modifierId = mod.modifierId,
                            modifierName = mod.modifierName,
                            priceInCents = mod.priceInCents,
                        )
                    },
                    note = item.itemNote,
                )
            },
        )
        savedCartsRepository.saveCart(savedCart)
        clearCart()
        Log.d("🛒", "Cart saved as: $cartName")
        return true
    }

    fun clearCart() {
        _cartState.value = CartState()
        Log.d("🛒", "Cart cleared")
    }

    fun restoreSavedCart(savedCart: SavedCart) {
        val items = savedCart.items.map { savedItem ->
            CartItem(
                type = if (savedItem.productId != null) {
                    CartItemType.ProductItem(savedItem.productId)
                } else {
                    CartItemType.CustomAmount
                },
                name = savedItem.name,
                unitPrice = savedItem.unitPrice,
                quantity = savedItem.quantity,
                selectedModifiers = savedItem.modifiers.map { mod ->
                    SelectedModifier(
                        groupId = mod.groupId,
                        groupName = mod.groupName,
                        modifierId = mod.modifierId,
                        modifierName = mod.modifierName,
                        priceInCents = mod.priceInCents,
                    )
                },
                itemNote = savedItem.note,
            )
        }
        _cartState.value = CartState(items = items)
        savedCartsRepository.deleteCart(savedCart.id)
        Log.d("🛒", "Restored saved cart: ${savedCart.name}")
    }

    fun deleteSavedCart(cartId: String) {
        savedCartsRepository.deleteCart(cartId)
        Log.d("🛒", "Deleted saved cart: $cartId")
    }

    fun getCartForPayment(): CartState = _cartState.value
}
