package com.avoqado.pos.pos.presentation.cart

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.areatickets.data.AreaTicketCheckout
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.areatickets.data.moneyToCents
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.model.CreateOrderRequest
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
import com.avoqado.pos.pos.data.model.Promotion
import com.avoqado.pos.pos.data.model.SavedCart
import com.avoqado.pos.pos.data.model.SavedCartItem
import com.avoqado.pos.pos.data.model.SavedModifier
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.pos.data.model.buildOrderItemRequests
import com.avoqado.pos.pos.presentation.promotions.opcionesElegidas
import com.avoqado.pos.pos.presentation.promotions.preciosUnitariosDePromocion
import com.avoqado.pos.referrals.domain.model.ValidationResult as ReferralValidationResult
import com.avoqado.pos.referrals.domain.repository.ReferralValidationException
import com.avoqado.pos.referrals.domain.usecase.CaptureReferralUseCase
import com.avoqado.pos.referrals.domain.usecase.ValidateReferralUseCase
import com.avoqado.pos.referrals.presentation.ReferralCaptureUiState
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

sealed interface ScannedBarcodeResult {
    data class ProductFound(val product: Product) : ScannedBarcodeResult
    data class WeightedProductFound(val product: Product, val weightKg: Double) : ScannedBarcodeResult
    data class AreaTicketsAdded(val ticketCount: Int) : ScannedBarcodeResult
    data class Unknown(val code: String) : ScannedBarcodeResult
    data class Error(val message: String) : ScannedBarcodeResult
}

data class CartState(
    val items: List<CartItem> = emptyList(),
    val orderDiscount: Discount? = null,
    val orderNote: String? = null,
    val orderTaxPercent: Int? = null,
    /** Cumplimiento (Square): DINE_IN | TAKEOUT | DELIVERY | PICKUP. La venta
     *  rápida siempre fue "En tienda" en la UI — ahora el dato lo respeta. */
    val orderType: String = "DINE_IN",
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
    private val validateReferralUseCase: ValidateReferralUseCase,
    private val captureReferralUseCase: CaptureReferralUseCase,
    private val planManager: PlanManager,
    private val tableSession: com.avoqado.pos.tables.data.TableSession,
    private val customerDisplay: com.avoqado.pos.customerdisplay.CustomerDisplayState,
    private val areaTicketRepository: AreaTicketRepository,
) : ViewModel() {

    private val _cartState = MutableStateFlow(defaultCartState())
    val cartState: StateFlow<CartState> = _cartState.asStateFlow()

    private val _staffOptions = MutableStateFlow<List<StaffMember>>(emptyList())
    val staffOptions: StateFlow<List<StaffMember>> = _staffOptions.asStateFlow()

    private val _isStaffLoading = MutableStateFlow(false)
    val isStaffLoading: StateFlow<Boolean> = _isStaffLoading.asStateFlow()

    // Aviso NO bloqueante al agregar un producto que marca 0 o menos: la venta
    // procede y el inventario quedará en negativo (señal de descuadre). La UI lo
    // pinta como toast ámbar y lo consume con consumeStockWarning().
    private val _stockWarning = MutableStateFlow<String?>(null)
    val stockWarning: StateFlow<String?> = _stockWarning.asStateFlow()

    fun consumeStockWarning() {
        _stockWarning.value = null
    }

    private fun avisarSiAgotado(product: Product) {
        if (product.isOutOfStock) {
            _stockWarning.value = "\"${product.name}\" marcaba 0 en inventario. Se agregó a la venta: revisa tus existencias."
        }
    }

    private val _staffError = MutableStateFlow<String?>(null)
    val staffError: StateFlow<String?> = _staffError.asStateFlow()

    // MARK: - Referral capture (Plan 5B)

    /**
     * Plan gate (REFERRAL_PROGRAM, Pro): when false the cart's referral
     * section renders a compact teaser instead of the capture input.
     * Fail-open when the plan is unknown.
     */
    val referralPlanAllowed: Boolean
        get() = planManager.hasFeature("REFERRAL_PROGRAM")

    /**
     * Currently selected customer id, mirrored from the CheckoutScreen so
     * the referral use cases can read it. The screen calls
     * [setSelectedCustomer] when the cashier picks/clears a customer.
     */
    private val _selectedCustomerId = MutableStateFlow<String?>(null)

    /// True when a class-seed was skipped because the cart already links a
    /// different reservation — the UI shows a message so the class isn't
    /// silently dropped.
    private val _seedConflict = MutableStateFlow(false)
    val seedConflict: StateFlow<Boolean> = _seedConflict.asStateFlow()
    fun clearSeedConflict() { _seedConflict.value = false }
    val selectedCustomerId: StateFlow<String?> = _selectedCustomerId.asStateFlow()

    private val _referralCode = MutableStateFlow("")
    val referralCode: StateFlow<String> = _referralCode.asStateFlow()

    private val _referralValidation = MutableStateFlow<ReferralCaptureUiState>(ReferralCaptureUiState.Idle)
    val referralValidation: StateFlow<ReferralCaptureUiState> = _referralValidation.asStateFlow()

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
                // Espejo a la pantalla del cliente (POS de doble pantalla): el
                // cliente ve su carrito en vivo mientras el cajero teclea.
                // No-op en equipos de una sola pantalla.
                customerDisplay.showCart(state)
            }
        }
    }

    /**
     * Re-sincroniza la pantalla del cliente con el carrito ACTUAL. Se llama al
     * cerrar el pago: si el carrito quedó vacío vuelve al logo del negocio, si
     * quedó saldo muestra ese carrito. Sin esto "Gracias" se quedaba pegado —
     * el StateFlow del carrito deduplica y no re-emite un vacío que ya era vacío.
     */
    fun refreshCustomerDisplay() {
        customerDisplay.showCart(_cartState.value)
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
    /**
     * TABLE_SERVICE — when arriving at Cobrar with a PAYING table session, seed
     * the cart with ONE "Cuenta Mesa N" line for the order total so the NORMAL
     * payment flow (tips, cash/terminal, split) charges the right amount. The
     * PaymentFlowViewModel seam then pays the EXISTING table order instead of
     * creating a new one. Idempotent: re-arriving with the same session doesn't
     * duplicate the line.
     */
    fun consumePendingTableCobrar() {
        val session = tableSession.current() ?: return
        if (session.mode != com.avoqado.pos.tables.data.TableSession.Mode.PAYING) return
        val label = "Cuenta Mesa ${session.tableNumber}"
        val items = _cartState.value.items
        if (items.any { it.name == label }) return
        // Mid-split re-entry: the cart already carries the live remainder line
        // ("Saldo pendiente") — never wipe it and re-seed the original total.
        if (items.any { it.name == "Saldo pendiente" }) return
        clearCart()
        // session.totalCents tracks the REMAINING balance after partial
        // payments (updateRemaining), so an empty-cart re-seed charges exactly
        // what's still owed.
        addCustomAmount(name = label, amountCents = session.totalCents)
    }

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
            // Don't clobber a different reservation already linked to the cart:
            // seeding class B into a cart that already carries class A's
            // reservationId would leave the sale linked to only one of them.
            val existingResId = _cartState.value.reservationId
            if (existingResId != null && seed.reservationId != null && existingResId != seed.reservationId) {
                Log.w("🛒", "Cart already linked to reservation $existingResId — skipping seed for ${seed.reservationId}")
                _seedConflict.value = true
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
        // Agotado AVISA, nunca bloquea (Square-parity 2026-08-12): el registro
        // del sistema puede estar desfasado y el producto sí existir en el
        // anaquel. El stock queda en negativo como señal de descuadre.
        avisarSiAgotado(product)
        _cartState.update { state ->
            // Check if same product without modifiers already in cart
            // 🔴 Una línea de promoción JAMÁS se fusiona: si el cliente pide una
            // cerveza suelta después de aplicar el 2x1, fusionarla subiría la
            // línea de la PROMOCIÓN a cantidad 3 — se cobraría $75 en vez de
            // $100 (el local pierde $25), la instancia viajaría con
            // `quantity = 3` (que el server rechaza junto a `promotionRef`) y el
            // inventario descontaría 3 unidades bajo un 2x1. Los guards de
            // `esLineaFija` no cubren esta puerta: el merge no pasa por ellos.
            val existingIndex = state.items.indexOfFirst {
                it.type is CartItemType.ProductItem &&
                    (it.type as CartItemType.ProductItem).productId == product.id &&
                    it.selectedModifiers.isEmpty() &&
                    !it.isCortesia &&
                    it.promotionInstanceId == null
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
        discountId: String? = null,
    ) {
        // Ver addProduct: agotado avisa, nunca bloquea.
        avisarSiAgotado(product)
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
            itemDiscountId = discountId,
        )
        _cartState.update { it.copy(items = it.items + newItem) }
        Log.d("🛒", "Added product with modifiers: ${product.name} x$quantity (${modifiers.size} mods)")
    }

    /**
     * Venta por peso (báscula): agrega una línea pesada. Cada pesada es SIEMPRE una línea NUEVA
     * — jamás se fusiona con otra (D9): dos pesadas del mismo jamón (0.435 y 0.512 kg) son ventas
     * distintas. quantity queda fija en 1; [unitPrice] guarda el precio POR KG y el total de línea
     * lo calcula [CartItem.totalPrice] (round(weightKg × precio/kg)). El aviso de stock lo da el
     * panel de captura (no bloquea aquí — el backend es la autoridad al cobrar).
     */
    fun addProductByWeight(product: Product, weightKg: Double) {
        if (weightKg <= 0) return
        val newItem = CartItem(
            type = CartItemType.ProductItem(product.id),
            name = product.name,
            unitPrice = product.priceInCents, // precio POR KG
            quantity = 1,
            imageUrl = product.imageUrl,
            colorHex = product.color,
            categoryId = product.categoryId,
            weightKg = weightKg,
        )
        _cartState.update { it.copy(items = it.items + newItem) }
        Log.d("🛒", "Added weighted product: ${product.name} (${weightKg} kg)")
    }

    /** Cumplimiento de la venta rápida (selector en el header del carrito). */
    fun setOrderType(orderType: String) {
        _cartState.update { it.copy(orderType = orderType) }
    }

    fun addCustomAmount(name: String, amountCents: Int) {
        val item = CartItem(
            type = CartItemType.CustomAmount,
            name = name,
            unitPrice = amountCents,
        )
        _cartState.update { it.copy(items = it.items + item) }
    }

    /** Add a prepaid credit pack (membresía) to the cart. Charges like a line item; on
     *  payment success the credits are granted to the attached customer. A customer is
     *  required to complete the charge (enforced at checkout). */
    private var lastPackAddId: String? = null
    private var lastPackAddAt: Long = 0

    fun addCreditPack(pack: com.avoqado.pos.articles.data.model.CreditPack) {
        // Debounce accidental double-taps on the grid tile: two identical pack
        // lines within 1.5s is a mis-tap (and would double-charge + double-grant).
        val now = System.currentTimeMillis()
        if (lastPackAddId == pack.id && now - lastPackAddAt < 1500) return
        lastPackAddId = pack.id
        lastPackAddAt = now
        val item = CartItem(
            type = CartItemType.CreditPack(pack.id),
            name = pack.name,
            subtitle = if (pack.creditCount > 0) "${pack.creditCount} créditos" else "Membresía",
            unitPrice = (pack.price * 100).toInt(),
        )
        _cartState.update { it.copy(items = it.items + item) }
    }

    /** True when the cart contains a credit-pack line (needs a customer to charge). */
    val hasCreditPack: Boolean get() = _cartState.value.items.any { it.type is CartItemType.CreditPack }

    // MARK: - Promociones (combos, paquetes, 2x1)

    /**
     * Mete una promoción al carrito: UNA línea por opción elegida, todas atadas
     * por el mismo `promotionInstanceId`.
     *
     * Reglas que el server impone y que aquí se respetan por construcción:
     * - **Un 2x1 entra como UNA línea de cantidad 2** (la `quantity` de la
     *   opción), porque la deducción de inventario del server multiplica por
     *   ella. No son dos líneas de 1.
     * - 🔴 **3 combos = 3 instancias distintas, NUNCA `quantity: 3`**: el server
     *   responde 400 con `quantity ≠ 1` junto a `promotionRef`. Por eso cada
     *   toque genera su propio UUID y la cantidad de una línea de promoción no
     *   se puede editar desde el carrito.
     * - **Media promoción no entra**: si a un grupo le falta su elección, no se
     *   agrega nada y se devuelve `false`.
     *
     * El precio de las líneas es el estimado local
     * ([preciosUnitariosDePromocion]) — que además es lo que se cobra en la
     * venta rápida. El precio del PEDIDO lo calcula el server al aplicar.
     *
     * @return `true` sólo si de verdad entró — la UI celebra con eso, así que un
     *   "¡Combo agregado!" nunca puede mentir.
     */
    fun aplicarPromocion(promotion: Promotion, selecciones: Map<String, String> = emptyMap()): Boolean {
        val elegidas = opcionesElegidas(promotion, selecciones)
        if (elegidas.isNullOrEmpty()) {
            Log.w("🎁", "Promoción sin elección completa, no se agrega: ${promotion.name}")
            return false
        }
        val precios = preciosUnitariosDePromocion(promotion, elegidas)
        val instanceId = UUID.randomUUID().toString()
        val nuevas = elegidas.mapIndexed { index, elegida ->
            CartItem(
                type = CartItemType.ProductItem(elegida.opcion.productId),
                name = elegida.opcion.productName.ifBlank { promotion.name },
                // El carrito ya pinta `subtitle` bajo el nombre: es donde se lee
                // "Combo del día" sin volver al catálogo.
                subtitle = promotion.name,
                unitPrice = precios.getOrElse(index) { elegida.opcion.productPriceCents },
                quantity = elegida.opcion.quantity.coerceAtLeast(1),
                promotionInstanceId = instanceId,
                promotionName = promotion.name,
                promotionId = promotion.id,
                promotionGroupId = elegida.grupo.id,
                promotionOptionId = elegida.opcion.id,
            )
        }
        _cartState.update { it.copy(items = it.items + nuevas) }
        Log.d("🎁", "Promoción aplicada: ${promotion.name} (${nuevas.size} líneas, instancia $instanceId)")
        return true
    }

    /**
     * Una promoción se quita COMPLETA: quitar una línea quita a todas sus
     * hermanas. Dejar media promoción en el carrito cobraría un combo a medias,
     * y el cajero no tendría cómo notarlo.
     */
    fun quitarPromocion(instanceId: String) {
        _cartState.update { state ->
            val restantes = state.items.filterNot { it.promotionInstanceId == instanceId }
            state.copy(
                items = restantes,
                reservationId = if (restantes.isEmpty()) null else state.reservationId,
            )
        }
        Log.d("🎁", "Promoción quitada: instancia $instanceId")
    }

    fun removeItem(itemId: String) {
        // 🔴 La promoción se quita completa venga de donde venga el borrado
        // (deslizar, anular artículos, panel de detalle). El aviso previo
        // ("Se quitará el combo completo") lo da la UI; esta red de seguridad
        // impide que quede media promoción aunque alguien no lo pregunte.
        val promotionInstanceId = _cartState.value.items.firstOrNull { it.id == itemId }?.promotionInstanceId
        if (promotionInstanceId != null) {
            quitarPromocion(promotionInstanceId)
            return
        }
        val areaTicketId = _cartState.value.items.firstOrNull { it.id == itemId }?.areaTicketId
        if (areaTicketId != null) {
            viewModelScope.launch {
                runCatching { areaTicketRepository.removeTicket(areaTicketId) }
                    .onSuccess(::replaceAreaTicketLines)
                    .onFailure { Log.e("🎟️", "No se pudo quitar el vale: ${it.message}") }
            }
            return
        }
        _cartState.update { state ->
            val updatedItems = state.items.filter { it.id != itemId }
            // When an item removal leaves the cart empty, clear the reservationId.
            // The cart doesn't track which line was the seeded class, so clearing
            // on empty prevents a stale link to an unrelated order; a partially-emptied
            // cart preserves the link.
            state.copy(
                items = updatedItems,
                reservationId = if (updatedItems.isEmpty()) null else state.reservationId
            )
        }
    }

    /**
     * Líneas que no se editan sueltas: las de un vale (precio del server) y las
     * de una promoción. En una promoción la cantidad es parte del contrato —
     * el 2x1 ES una línea de 2— y subirla mandaría `quantity ≠ 1` junto a
     * `promotionRef`, que el server rechaza. Para vender 3 combos se tocan 3
     * veces las tarjetas: 3 instancias.
     */
    private fun esLineaFija(itemId: String): Boolean =
        _cartState.value.items.any { it.id == itemId && (it.locked || it.isPromotionLine) }

    fun updateQuantity(itemId: String, newQuantity: Int) {
        if (esLineaFija(itemId)) return
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
        if (esLineaFija(itemId)) return
        _cartState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.id == itemId) it.copy(quantity = it.quantity + 1) else it
                },
            )
        }
    }

    fun decrementQuantity(itemId: String) {
        if (esLineaFija(itemId)) return
        _cartState.update { state ->
            val item = state.items.find { it.id == itemId } ?: return@update state
            if (item.quantity <= 1) {
                val updatedItems = state.items.filter { it.id != itemId }
                // When an item removal leaves the cart empty, clear the reservationId.
                // The cart doesn't track which line was the seeded class, so clearing
                // on empty prevents a stale link to an unrelated order; a partially-emptied
                // cart preserves the link.
                state.copy(
                    items = updatedItems,
                    reservationId = if (updatedItems.isEmpty()) null else state.reservationId
                )
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
        if (_cartState.value.items.any { it.locked }) return
        _cartState.update { it.copy(orderDiscount = discount) }
    }

    fun applyOrderTaxPercent(taxPercent: Int?) {
        if (_cartState.value.items.any { it.locked }) return
        val normalized = taxPercent?.coerceIn(0, 100)?.takeIf { it > 0 }
        _cartState.update { it.copy(orderTaxPercent = normalized) }
    }

    fun setOrderNote(note: String?) {
        _cartState.update { it.copy(orderNote = note) }
    }

    fun markItemAsCortesia(itemId: String, reason: String?) {
        // Una promoción no se regala línea por línea: el server cobra la
        // promoción completa, así que poner la línea en $0 aquí cobraría algo
        // distinto de lo que queda en la orden. Se quita el combo y se da el
        // producto suelto de cortesía. (El guard es sólo de promoción: el de
        // `locked` no estaba aquí y no es de esta task cambiarlo.)
        if (_cartState.value.items.any { it.id == itemId && it.isPromotionLine }) return
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
        if (_cartState.value.items.any { it.id == itemId && it.locked }) return
        _cartState.update { state ->
            state.copy(
                items = state.items.map {
                    if (it.id == itemId) it.copy(itemNote = note) else it
                },
            )
        }
    }

    fun updateItemCortesia(itemId: String, isCortesia: Boolean, reason: String?) {
        if (esLineaFija(itemId)) return
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
        if (esLineaFija(itemId)) return
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
        if (state.isEmpty || state.items.any { it.locked }) return false

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
                        is CartItemType.CreditPack -> null
                    },
                    packId = (item.type as? CartItemType.CreditPack)?.packId,
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
                    promotionInstanceId = item.promotionInstanceId,
                    promotionName = item.promotionName,
                    promotionId = item.promotionId,
                    promotionGroupId = item.promotionGroupId,
                    promotionOptionId = item.promotionOptionId,
                )
            },
            orderDiscount = state.orderDiscount,
            orderNote = state.orderNote,
            orderTaxPercent = state.orderTaxPercent,
            reservationId = state.reservationId,
            attachedCustomerId = _selectedCustomerId.value,
        )
        savedCartsRepository.saveCart(savedCart)
        clearCart()
        Log.d("🛒", "Cart saved as: $cartName")
        return true
    }

    fun clearCart() {
        if (areaTicketRepository.session.current() != null) {
            viewModelScope.launch {
                runCatching { areaTicketRepository.cancel() }
                    .onFailure { Log.e("🎟️", "No se pudo liberar la sesión al limpiar el carrito: ${it.message}") }
            }
        }
        _cartState.value = defaultCartState()
        // Referral capture state is per-order, so wiping the cart must wipe
        // the code + validation too. The discount referenced by the validation
        // already lives on cartState.orderDiscount and is dropped above.
        _referralCode.value = ""
        _referralValidation.value = ReferralCaptureUiState.Idle
        Log.d("🛒", "Cart cleared")
    }

    /**
     * Conserva el checkout normal para cualquier SKU/GTIN. Sólo los códigos del
     * namespace de vales se resuelven en servidor para evitar colisiones y dobles claims.
     */
    fun restoreAreaTicketSession() {
        viewModelScope.launch {
            // 🔴 Restaurar es BEST-EFFORT: corre solo al entrar a Cobrar y no
            // puede tumbar la app.
            //
            // `restore()` empieza por `venueId()`, que LANZA si no hay local
            // seleccionado — y eso pasa de verdad: con la sesión vencida el
            // refresh devuelve 401, el venue se limpia, y al abrir Cobrar la
            // excepción subía sin nadie que la atrapara. Crash al arranque en
            // vez de mandar al login. Reproducido en la D3 el 2026-08-10 con la
            // sesión expirada (AreaTicketException VENUE_REQUIRED).
            //
            // iOS ya lo hacía bien con `try?` (CheckoutView.swift): esto es el
            // espejo, con log para que el fallo no quede invisible.
            runCatching { areaTicketRepository.restore() }
                .onSuccess { checkout -> checkout?.let(::replaceAreaTicketLines) }
                .onFailure { Log.w("🎟️", "No se pudo restaurar la sesión de vales: ${it.message}") }
        }
    }

    /**
     * Renueva la sesión de cobro de vales para que no venza mientras el cajero
     * trabaja. Espejo de `maintainAreaTicketCheckout` en iOS.
     *
     * Lanza si falla: el llamador decide. Un bache de red no debe tumbar el cobro,
     * pero tampoco queremos tragarnos el error en silencio aquí.
     */
    suspend fun heartbeatAreaTicketSession() {
        areaTicketRepository.heartbeat()?.let(::replaceAreaTicketLines)
    }

    suspend fun resolveScannedBarcode(rawCode: String): ScannedBarcodeResult {
        val code = rawCode.trim()
        val localProduct = products.value.firstOrNull {
            it.sku == code || it.barcode == code || it.gtin == code
        }
        if (!com.avoqado.pos.pos.data.isAreaTicketCode(code)) {
            localProduct?.let { return ScannedBarcodeResult.ProductFound(it) }

            if (code.length == 13 && code.all(Char::isDigit)) {
                val barcodeSettings = runCatching { areaTicketRepository.settings().variableWeightBarcode }.getOrNull()
                if (barcodeSettings?.enabled == true && barcodeSettings.entitled) {
                    val decoded = com.avoqado.pos.pos.data.decodeVariableWeightBarcode(code, barcodeSettings.prefix)
                    if (decoded != null) {
                        val exactMatches = products.value.filter { product ->
                            product.sku == decoded.plu || product.barcode == decoded.plu || product.gtin == decoded.plu
                        }
                        val matches = if (exactMatches.isNotEmpty()) {
                            exactMatches
                        } else {
                            val normalizedPlu = decoded.plu.trimStart('0').ifEmpty { "0" }
                            products.value.filter { product ->
                                listOfNotNull(product.sku, product.barcode, product.gtin).any { candidate ->
                                    candidate.all(Char::isDigit) && candidate.trimStart('0').ifEmpty { "0" } == normalizedPlu
                                }
                            }
                        }
                        if (matches.size > 1) {
                            return ScannedBarcodeResult.Error("El PLU ${decoded.plu} coincide con más de un producto. Corrige el catálogo.")
                        }
                        val weightedProduct = matches.singleOrNull()
                            ?: return ScannedBarcodeResult.Error("El PLU ${decoded.plu} de la báscula no existe en el catálogo.")
                        if (!weightedProduct.soldByWeight) {
                            return ScannedBarcodeResult.Error("El producto ${weightedProduct.name} no está configurado para venta por peso.")
                        }
                        return ScannedBarcodeResult.WeightedProductFound(weightedProduct, decoded.weightKg)
                    }
                }
            }
            return ScannedBarcodeResult.Unknown(code)
        }

        return runCatching {
            val resolved = areaTicketRepository.resolveCheckoutScan(code)
            when (resolved.type) {
                "PRODUCT" -> localProduct?.let(ScannedBarcodeResult::ProductFound)
                    ?: ScannedBarcodeResult.Unknown(code)
                "AREA_TICKET" -> {
                    val settings = areaTicketRepository.settings()
                    val hasNormalItems = _cartState.value.items.any { !it.locked }
                    if (!settings.areaTickets.allowMixedCart && hasNormalItems) {
                        ScannedBarcodeResult.Error("Este local cobra los vales en un carrito separado.")
                    } else {
                        val checkout = areaTicketRepository.addTicket(code)
                        replaceAreaTicketLines(checkout)
                        ScannedBarcodeResult.AreaTicketsAdded(checkout.tickets.size)
                    }
                }
                "PAID_AREA_TICKET" -> ScannedBarcodeResult.Error("Ese vale ya está pagado; úsalo en la pantalla de entrega.")
                "AMBIGUOUS" -> ScannedBarcodeResult.Error("El código coincide con un producto y un vale. Revisa la configuración.")
                else -> ScannedBarcodeResult.Error("Vale no encontrado en este local.")
            }
        }.getOrElse { error ->
            ScannedBarcodeResult.Error(error.message ?: "No se pudo consultar el vale. Revisa la conexión.")
        }
    }

    private fun replaceAreaTicketLines(checkout: AreaTicketCheckout) {
        val ticketRows = checkout.tickets.flatMap { ticket ->
            ticket.lines.map { line ->
                val detail = buildList {
                    add("${ticket.fulfillmentArea.name} · Vale ${ticket.code}")
                    line.weightKg?.let { add("$it kg") }
                    if (line.quantity != "1" && line.weightKg == null) add("Cantidad ${line.quantity}")
                }.joinToString(" · ")
                CartItem(
                    id = "area-ticket:${ticket.id}:${line.id}",
                    type = CartItemType.CustomAmount,
                    name = line.productNameSnapshot,
                    subtitle = detail,
                    unitPrice = line.total.moneyToCents(),
                    quantity = 1,
                    areaTicketId = ticket.id,
                    areaTicketLineId = line.id,
                    locked = true,
                )
            }
        }
        _cartState.update { state ->
            state.copy(
                items = state.items.filterNot { it.locked } + ticketRows,
                orderDiscount = null,
                orderTaxPercent = null,
            )
        }
    }

    // MARK: - Referral capture (Plan 5B)

    /** Constant tag for the order discount when it came from a referral. */
    private val REFERRAL_DISCOUNT_SOURCE = "REFERRAL_NEW_CUSTOMER"

    /**
     * Mirrors the customer selection from the CheckoutScreen so the referral
     * use cases have a customer id to validate against. Switching customers
     * (or clearing the customer) invalidates the cached validation — the
     * EXISTING_CUSTOMER rule is per-customer, so a stale Valid would be wrong.
     */
    fun setSelectedCustomer(customerId: String?) {
        val previous = _selectedCustomerId.value
        if (previous == customerId) return
        _selectedCustomerId.value = customerId
        if (previous != null) {
            // Customer changed (incl. switch-to-null). Any cached referral
            // state is for the previous customer, drop it.
            clearReferral()
        }
    }

    fun onReferralCodeChange(code: String) {
        _referralCode.value = code
        // Editing the code after a result invalidates the result so the
        // banner doesn't lie about the new (untested) code.
        val current = _referralValidation.value
        if (current !is ReferralCaptureUiState.Idle &&
            current !is ReferralCaptureUiState.Validating
        ) {
            _referralValidation.value = ReferralCaptureUiState.Idle
        }
    }

    /**
     * Validates the cached [referralCode] against the backend for the
     * cached [selectedCustomerId]. No-ops when either is missing. On
     * [ReferralValidationResult.Valid] applies the discount to the cart; on
     * [ReferralValidationResult.Invalid] or network failure clears any
     * discount that the previous validation may have applied.
     */
    fun validateReferralCode() {
        val venueId = secureStorage.venueId
        if (venueId.isNullOrBlank()) {
            Log.w("🎁", "validateReferralCode skipped: no venueId")
            return
        }
        val customerId = _selectedCustomerId.value
        if (customerId.isNullOrBlank()) {
            Log.w("🎁", "validateReferralCode skipped: no customer selected")
            return
        }
        val code = _referralCode.value.trim()
        if (code.isBlank()) return

        viewModelScope.launch {
            _referralValidation.value = ReferralCaptureUiState.Validating
            validateReferralUseCase(
                venueId = venueId,
                referralCode = code,
                newCustomerId = customerId,
            ).fold(
                onSuccess = { result ->
                    when (result) {
                        is ReferralValidationResult.Valid -> {
                            _referralValidation.value = ReferralCaptureUiState.Valid(
                                referrerName = result.referrerName,
                                discountPercent = result.discountPercent,
                            )
                            applyReferralDiscount(
                                discountPercent = result.discountPercent,
                                referrerName = result.referrerName,
                            )
                        }
                        is ReferralValidationResult.Invalid -> {
                            _referralValidation.value = ReferralCaptureUiState.Invalid(result.reason)
                            clearReferralDiscountOnly()
                        }
                    }
                },
                onFailure = { e ->
                    Log.e("🎁", "Referral validate request failed", e)
                    _referralValidation.value = ReferralCaptureUiState.Invalid(
                        ReferralValidationResult.Reason.UNKNOWN,
                    )
                    clearReferralDiscountOnly()
                },
            )
        }
    }

    /** Resets referral state and any discount it had applied. */
    fun clearReferral() {
        _referralCode.value = ""
        _referralValidation.value = ReferralCaptureUiState.Idle
        clearReferralDiscountOnly()
    }

    /**
     * Clears the cart's orderDiscount only when it originated from a referral.
     * Leaves a manual/coupon discount alone — the cashier may have applied
     * one independently before the referral validation kicked in.
     */
    private fun clearReferralDiscountOnly() {
        _cartState.update { state ->
            if (state.orderDiscount?.source == REFERRAL_DISCOUNT_SOURCE) {
                state.copy(orderDiscount = null)
            } else {
                state
            }
        }
    }

    /**
     * Applies the referral percent discount as an order-level discount
     * tagged with [REFERRAL_DISCOUNT_SOURCE]. The cart's existing
     * [CartState.discountCents] formula computes it against the subtotal
     * automatically — no manual math here.
     */
    private fun applyReferralDiscount(discountPercent: Int, referrerName: String) {
        if (discountPercent <= 0) {
            clearReferralDiscountOnly()
            return
        }
        val discount = Discount(
            id = "referral_$referrerName",
            name = "Referido por $referrerName",
            value = discountPercent.toDouble(),
            type = "PERCENTAGE",
            scope = "ORDER",
            source = REFERRAL_DISCOUNT_SOURCE,
        )
        _cartState.update { it.copy(orderDiscount = discount) }
    }

    /**
     * Persists the PENDING Referral row right before the payment is sent.
     * No-op unless [referralValidation] is currently
     * [ReferralCaptureUiState.Valid]. Returns true on a happy-path capture
     * (or no-op), false on transport failure or validation reject — the
     * caller should NOT block the payment on a false result, since the
     * cashier already saw a Valid banner and the discount was visible.
     */
    suspend fun captureReferralOnPayment(orderId: String? = null): Boolean {
        val state = _referralValidation.value
        if (state !is ReferralCaptureUiState.Valid) return true

        val venueId = secureStorage.venueId ?: return false
        val customerId = _selectedCustomerId.value ?: return false
        // NOTE: The backend field is `capturedByStaffVenueId` — server-side
        // it's expected to be a StaffVenue id. The Android POS stores the
        // user-level userId in SecureStorage, and the cashier-selected staff
        // id (cartState.selectedStaffId) under `selectedStaffIdForCurrentVenue`.
        // Following the TPV pattern (Plan 5A) which sends `secureStorage
        // .getStaffId()` here — see CONCERNS in the report.
        val staffId = cartState.value.selectedStaffId.takeIf { it.isNotBlank() }
            ?: secureStorage.userId
            ?: return false
        val code = _referralCode.value.trim()
        if (code.isBlank()) return false

        val result = captureReferralUseCase(
            venueId = venueId,
            referralCode = code,
            newCustomerId = customerId,
            capturedByStaffVenueId = staffId,
            intendedOrderId = orderId,
        )

        return result.fold(
            onSuccess = { true },
            onFailure = { e ->
                (e as? ReferralValidationException)?.let { ex ->
                    _referralValidation.value = ReferralCaptureUiState.Invalid(ex.reason)
                    clearReferralDiscountOnly()
                }
                Log.e("🎁", "captureReferralOnPayment failed", e)
                false
            },
        )
    }

    fun restoreSavedCart(savedCart: SavedCart) {
        val items = savedCart.items.map { savedItem ->
            CartItem(
                type = when {
                    savedItem.packId != null -> CartItemType.CreditPack(savedItem.packId)
                    savedItem.productId != null -> CartItemType.ProductItem(savedItem.productId)
                    else -> CartItemType.CustomAmount
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
                // El combo vuelve como combo: mismo `promotionInstanceId` y las
                // mismas elecciones, o el carrito restaurado cobraría el 2x1
                // como dos productos a precio de lista.
                promotionInstanceId = savedItem.promotionInstanceId,
                promotionName = savedItem.promotionName,
                promotionId = savedItem.promotionId,
                promotionGroupId = savedItem.promotionGroupId,
                promotionOptionId = savedItem.promotionOptionId,
                subtitle = savedItem.promotionName,
            )
        }
        // Referral-sourced discounts must NOT survive a restore without a live
        // validation — the referrer would never be credited for the new sale.
        val restoredDiscount = savedCart.orderDiscount?.takeIf { it.source != REFERRAL_DISCOUNT_SOURCE }
        clearReferral()
        _cartState.value = CartState(
            items = items,
            orderDiscount = restoredDiscount,
            orderNote = savedCart.orderNote,
            orderTaxPercent = savedCart.orderTaxPercent,
            reservationId = savedCart.reservationId,
            selectedStaffId = _cartState.value.selectedStaffId,
            selectedStaffName = _cartState.value.selectedStaffName,
        )
        _selectedCustomerId.value = savedCart.attachedCustomerId
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
            return Result.failure(Exception("No hay artículos en el carrito"))
        }
        if (customerId.isBlank()) {
            return Result.failure(Exception("Selecciona un cliente para diferir el pago"))
        }

        // El MISMO mapeo que usa el cobro (`PaymentFlowViewModel.buildOrderRequest`).
        // Estaba copiado, y una copia que se olvida deja "pagar después" cobrando
        // el combo a precio de lista mientras el cobro normal sí lo manda.
        val items = buildOrderItemRequests(currentCart.items)

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
