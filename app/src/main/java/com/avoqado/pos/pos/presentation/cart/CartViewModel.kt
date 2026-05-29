package com.avoqado.pos.pos.presentation.cart

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.OrderItemRequest
import com.avoqado.pos.payment.data.model.OrderModifierRequest
import com.avoqado.pos.pos.data.ActiveCartState
import com.avoqado.pos.pos.data.ClassCheckoutSeed
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.StaffMember
import com.avoqado.pos.pos.data.StaffRepository
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
    val orderTaxPercent: Int? = null,
    val selectedStaffId: String = "",
    val selectedStaffName: String = "Staff",
    /** Set when the cart was seeded from a walk-in class reservation; flows
     *  through to the order so the sale links back to the reservation. */
    val reservationId: String? = null,
) {
    val itemCount: Int get() = items.sumOf { it.quantity }
    val subtotalCents: Int get() = items.sumOf { it.totalPrice }
    val taxableSubtotalCents: Int get() = items.filter { it.type is CartItemType.ProductItem }.sumOf { it.totalPrice }
    val discountCents: Int
        get() = orderDiscount?.calculateDiscount(subtotalCents) ?: 0
    val taxableDiscountCents: Int
        get() = if (subtotalCents <= 0 || discountCents <= 0 || taxableSubtotalCents <= 0) {
            0
        } else {
            ((discountCents.toDouble() * taxableSubtotalCents.toDouble()) / subtotalCents.toDouble()).toInt()
                .coerceAtMost(taxableSubtotalCents)
        }
    val taxableAmountAfterDiscountCents: Int
        get() = (taxableSubtotalCents - taxableDiscountCents).coerceAtLeast(0)
    val taxCents: Int
        get() {
            val percent = orderTaxPercent ?: return 0
            if (percent <= 0 || taxableAmountAfterDiscountCents <= 0) return 0
            return ((taxableAmountAfterDiscountCents * percent) / 100.0).toInt()
        }
    val totalCents: Int get() = (subtotalCents - discountCents + taxCents).coerceAtLeast(0)
    val isEmpty: Boolean get() = items.isEmpty()

    val subtotalDisplay: String get() = formatCents(subtotalCents)
    val discountDisplay: String get() = formatCents(discountCents)
    val taxDisplay: String get() = formatCents(taxCents)
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
    private val authRepository: AuthRepository,
    private val secureStorage: SecureStorage,
    private val activeCartState: ActiveCartState,
    private val orderRepository: OrderRepository,
    private val staffRepository: StaffRepository,
    private val classCheckoutSeed: ClassCheckoutSeed,
) : ViewModel() {

    private val _cartState = MutableStateFlow(defaultCartState())
    val cartState: StateFlow<CartState> = _cartState.asStateFlow()

    private val _staffOptions = MutableStateFlow<List<StaffMember>>(emptyList())
    val staffOptions: StateFlow<List<StaffMember>> = _staffOptions.asStateFlow()

    private val _isStaffLoading = MutableStateFlow(false)
    val isStaffLoading: StateFlow<Boolean> = _isStaffLoading.asStateFlow()

    private val _staffError = MutableStateFlow<String?>(null)
    val staffError: StateFlow<String?> = _staffError.asStateFlow()

    init {
        // Clear cart when venue changes (like iOS)
        viewModelScope.launch {
            authRepository.venueSwitched.collect {
                Log.d("🛒", "Venue switched — clearing cart")
                clearCart()
                fetchStaff()
            }
        }

        // Keep ActiveCartState in sync so other screens can check if cart has items
        viewModelScope.launch {
            _cartState.collect { state ->
                activeCartState.update(state.itemCount, state.totalDisplay)
            }
        }
    }

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
            fetchStaff()
        }
    }

    private fun defaultCartState(): CartState {
        val loggedInStaffName = listOfNotNull(secureStorage.userFirstName, secureStorage.userLastName)
            .joinToString(" ")
            .trim()
            .ifEmpty { secureStorage.userEmail ?: "Staff" }
        val storedStaffId = secureStorage.selectedStaffIdForCurrentVenue
        val storedStaffName = secureStorage.selectedStaffNameForCurrentVenue

        return CartState(
            selectedStaffId = storedStaffId ?: secureStorage.userId.orEmpty(),
            selectedStaffName = storedStaffName ?: loggedInStaffName,
        )
    }

    fun fetchStaff() {
        viewModelScope.launch {
            _isStaffLoading.value = true
            _staffError.value = null
            staffRepository.getActiveStaff().fold(
                onSuccess = { staff ->
                    _staffOptions.value = staff
                    val selectedId = _cartState.value.selectedStaffId
                    val selected = staff.firstOrNull { it.id == selectedId }
                    if (selected != null && selected.fullName != _cartState.value.selectedStaffName) {
                        selectStaff(selected.id, selected.fullName)
                    } else if (selectedId.isNotBlank() && selected == null) {
                        secureStorage.clearSelectedStaffForCurrentVenue()
                        _cartState.value = defaultCartState()
                    }
                },
                onFailure = { error ->
                    _staffError.value = error.message ?: "No se pudo cargar staff"
                },
            )
            _isStaffLoading.value = false
        }
    }

    fun selectStaff(staffId: String, staffName: String) {
        val normalizedStaffName = staffName.ifBlank { "Staff" }
        secureStorage.saveSelectedStaffForCurrentVenue(staffId, normalizedStaffName)
        _cartState.update { state ->
            state.copy(
                selectedStaffId = staffId,
                selectedStaffName = normalizedStaffName,
            )
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

    // MARK: - Walk-in class seed

    /**
     * Consumes a pending walk-in class seed (set by ClassSessionDetailViewModel)
     * and drops the class product into the current sale — mirroring how Square's
     * register loads a service into the open sale.
     *
     * One-shot (the seed clears itself on read), so it's safe to call on every
     * Checkout entry. Adds to the existing cart rather than clearing it, like
     * Square adds a service to the current sale. The reservationId is stored on
     * the cart so the resulting order links back to the reservation.
     */
    fun consumePendingClassSeed() {
        val seed = classCheckoutSeed.consume() ?: return
        viewModelScope.launch {
            if (productsRepository.products.value.isEmpty()) {
                productsRepository.fetchProducts()
            }
            val product = productsRepository.products.value.firstOrNull { it.id == seed.productId }
            if (product == null) {
                Log.w("🛒", "Class seed product ${seed.productId} not in catalog — cannot seed cart")
                return@launch
            }
            addProductWithModifiers(product, quantity = seed.quantity, modifiers = emptyList())
            seed.reservationId?.let { resId ->
                _cartState.update { it.copy(reservationId = resId) }
            }
            Log.d("🛒", "Seeded class ${product.name} x${seed.quantity} from walk-in flow")
        }
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

    fun applyOrderTaxPercent(taxPercent: Int?) {
        val normalized = taxPercent?.coerceIn(0, 100)?.takeIf { it > 0 }
        _cartState.update { it.copy(orderTaxPercent = normalized) }
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

        val cartName = name ?: "Carrito ${
            java.time.ZonedDateTime.now(com.avoqado.pos.core.util.VenueTimeZone.zoneId())
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        }"
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
                    isCortesia = item.isCortesia,
                    cortesiaReason = item.cortesiaReason,
                    priceAdjustment = item.priceAdjustment,
                    itemDiscountId = item.itemDiscountId,
                )
            },
            orderDiscount = state.orderDiscount,
            orderNote = state.orderNote,
            orderTaxPercent = state.orderTaxPercent,
        )
        savedCartsRepository.saveCart(savedCart)
        clearCart()
        Log.d("🛒", "Cart saved as: $cartName")
        return true
    }

    fun clearCart() {
        _cartState.value = defaultCartState()
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
                isCortesia = savedItem.isCortesia,
                cortesiaReason = savedItem.cortesiaReason,
                priceAdjustment = savedItem.priceAdjustment,
                itemDiscountId = savedItem.itemDiscountId,
            )
        }
        _cartState.value = CartState(
            items = items,
            orderDiscount = savedCart.orderDiscount,
            orderNote = savedCart.orderNote,
            orderTaxPercent = savedCart.orderTaxPercent,
            selectedStaffId = _cartState.value.selectedStaffId,
            selectedStaffName = _cartState.value.selectedStaffName,
        )
        savedCartsRepository.deleteCart(savedCart.id)
        Log.d("🛒", "Restored saved cart: ${savedCart.name}")
    }

    fun deleteSavedCart(cartId: String) {
        savedCartsRepository.deleteCart(cartId)
        Log.d("🛒", "Deleted saved cart: $cartId")
    }

    suspend fun createPayLaterOrder(customerId: String): Result<String> {
        val currentCart = _cartState.value
        if (currentCart.isEmpty) {
            return Result.failure(Exception("No hay articulos en el carrito"))
        }
        if (customerId.isBlank()) {
            return Result.failure(Exception("Selecciona un cliente para diferir el pago"))
        }

        val items = currentCart.items.map { item ->
            OrderItemRequest(
                productId = when (val type = item.type) {
                    is CartItemType.ProductItem -> type.productId
                    CartItemType.CustomAmount -> null
                },
                name = item.name,
                quantity = item.quantity,
                unitPrice = item.effectiveUnitPrice,
                modifiers = item.selectedModifiers.map { modifier ->
                    OrderModifierRequest(
                        modifierId = modifier.modifierId,
                        name = modifier.modifierName,
                        price = modifier.priceInCents,
                    )
                },
                note = item.itemNote,
                isCortesia = item.isCortesia,
            )
        }

        val orderRequest = CreateOrderRequest(
            items = items,
            subtotal = currentCart.subtotalCents,
            discount = currentCart.discountCents,
            tip = 0,
            total = currentCart.totalCents,
            paymentMethod = "PAY_LATER",
            note = currentCart.orderNote,
            splitType = "FULLPAYMENT",
        )

        return orderRepository
            .createOrder(
                request = orderRequest,
                customerId = customerId,
                orderType = "DINE_IN",
                staffId = currentCart.selectedStaffId,
            )
            .fold(
                onSuccess = { response ->
                    val orderId = response.data?.id
                    if (orderId.isNullOrBlank()) {
                        Result.failure(Exception("No se pudo obtener la orden creada"))
                    } else {
                        Result.success(orderId)
                    }
                },
                onFailure = { error -> Result.failure(error) },
            )
    }

    fun getCartForPayment(): CartState = _cartState.value
}
