package com.avoqado.pos.pos.presentation.checkout

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.avoqado.pos.areatickets.presentation.AreaTicketOperationsViewModel
import com.avoqado.pos.core.util.formatMoney

import com.avoqado.pos.customers.presentation.CreateCustomerView
import com.avoqado.pos.customers.presentation.CustomersView
import com.avoqado.pos.customers.presentation.CustomersViewModel
import com.avoqado.pos.designsystem.components.AvoqadoDialog
import com.avoqado.pos.designsystem.components.AvoqadoPillTextField
import com.avoqado.pos.designsystem.components.AvoqadoSuccessToast
import com.avoqado.pos.designsystem.components.AvoqadoWarningToast
import com.avoqado.pos.designsystem.components.PrimaryButton
import com.avoqado.pos.designsystem.theme.AvoqadoTheme
import com.avoqado.pos.payment.presentation.PaymentFlowScreen
import com.avoqado.pos.payment.presentation.SplitConfig
import com.avoqado.pos.payment.presentation.SplitPaymentSheet
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.Promotion
import com.avoqado.pos.pos.presentation.cart.CartPanelView
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import com.avoqado.pos.pos.presentation.cart.ScannedBarcodeResult
import com.avoqado.pos.pos.presentation.cart.StaffSelectorSheet
import com.avoqado.pos.pos.presentation.product.CreateProductView
import com.avoqado.pos.pos.presentation.product.NoteSubView
import com.avoqado.pos.pos.presentation.product.ProductDetailPanel
import com.avoqado.pos.pos.presentation.product.ProductGridView
import com.avoqado.pos.pos.presentation.product.WeightCapturePanel
import com.avoqado.pos.pos.presentation.promotions.PromotionSheet
import com.avoqado.pos.pos.presentation.promotions.PromotionsPanel
import com.avoqado.pos.pos.presentation.promotions.PromotionsPanelViewModel
import com.avoqado.pos.pos.presentation.promotions.pestanasVisibles
import com.avoqado.pos.pos.presentation.promotions.resolverModoPanel
import com.avoqado.pos.tpvsettings.data.PanelMode
import com.avoqado.pos.pos.presentation.scanner.BarcodeScannerView
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.pos.presentation.search.SearchOverlayView
import com.avoqado.pos.scale.ScaleCaptureViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class InputTab(val label: String) {
    KEYPAD("Teclado"),
    SHORTCUTS("Shortcuts"),
    PRODUCTS("Todos los productos"),
    /**
     * Promociones. 🔴 Sólo aparece cuando el modo resuelto del panel es `TAB`:
     * con el panel lateral el cajero tendría DOS entradas a lo mismo, y con
     * `HIDDEN` el local lo apagó a propósito. Ver `resolverModoPanel`.
     */
    PROMOS("Promociones"),
    MOSAIC("Configurar"),
}

private enum class CustomerSelectionContext {
    GENERAL,
    PAY_LATER,
}

@Composable
fun CheckoutScreen(
    isTablet: Boolean,
    roleManager: RoleManager? = null,
    cartViewModel: CartViewModel = hiltViewModel(),
) {
    val creditsViewModel: com.avoqado.pos.customers.presentation.CustomerCreditsViewModel = hiltViewModel()
    // Membresías cobradas que NO se entregaron: dinero adentro sin su
    // contraparte. Se avisa aquí, en el mostrador, no sólo en el log.
    val membresiasSinEntregar by creditsViewModel.undeliveredGrants.collectAsState()
    val cartState by cartViewModel.cartState.collectAsState()
    val isLoading by cartViewModel.isLoading.collectAsState()
    val staffOptions by cartViewModel.staffOptions.collectAsState()
    val isStaffLoading by cartViewModel.isStaffLoading.collectAsState()
    val staffError by cartViewModel.staffError.collectAsState()
    val referralCodeState by cartViewModel.referralCode.collectAsState()
    val referralUiState by cartViewModel.referralValidation.collectAsState()
    val areaTicketOperations: AreaTicketOperationsViewModel = hiltViewModel()
    val areaOperationsState by areaTicketOperations.state.collectAsState()
    val scaleCaptureViewModel: ScaleCaptureViewModel = hiltViewModel()
    val scaleState by scaleCaptureViewModel.state.collectAsState()

    // Walk-in class flow: if a class was just reserved on the class screen,
    // drop it into the cart on arrival (Square-style: service enters the sale).
    LaunchedEffect(Unit) {
        cartViewModel.consumePendingClassSeed()
        cartViewModel.restoreAreaTicketSession()
    }

    // Mantener viva la sesión de cobro mientras haya vales en el carrito.
    // iOS ya lo hacía (`maintainAreaTicketCheckout`) y Android tenía el método
    // en el repositorio SIN un solo llamador: la sesión moría a los 30 minutos
    // aunque el cajero estuviera trabajando en ella, y el error salía al cobrar.
    val hasAreaTicketLines = cartState.items.any { it.locked }
    LaunchedEffect(hasAreaTicketLines) {
        if (!hasAreaTicketLines) return@LaunchedEffect
        val ttl = areaOperationsState.settings?.areaTickets?.claimTtlSeconds ?: 120
        val intervalMs = (ttl / 2).coerceIn(15, 60) * 1_000L
        while (true) {
            // Un bache de red no debe sacar al cajero del cobro: el siguiente
            // latido reintenta y materializar sigue exigiendo servidor.
            runCatching { cartViewModel.heartbeatAreaTicketSession() }
            kotlinx.coroutines.delay(intervalMs)
        }
    }

    // TABLE_SERVICE (PRO) — table ORDERING lives on the dedicated
    // TableOrderScreen now; the register only keeps the PAYING seam: the
    // session seeds the cart with the check total so the NORMAL payment flow
    // (tips, terminal, split) charges the EXISTING table order. With no
    // session active everything below is inert.
    val tablesViewModel: com.avoqado.pos.tables.presentation.TablesViewModel = hiltViewModel()
    val tableSessionActive by tablesViewModel.tableSession.active.collectAsState()

    // Upsell "¿Algo más?" — el momento previo al cobro. Con la perilla apagada o
    // sin reglas, todo esto es inerte y el cobro no se entera de que existe.
    val upsellViewModel: com.avoqado.pos.pos.presentation.upsell.UpsellViewModel = hiltViewModel()
    val upsellMoment by upsellViewModel.moment.collectAsState()
    /**
     * 🔴 COBRAR UNA MESA NO OFRECE UPSELL — todavía. No es un olvido, es un candado.
     *
     * Al cobrar una mesa, `consumePendingTableCobrar` limpia el carrito y siembra UNA
     * línea de monto libre ("Cuenta Mesa 5" = el saldo). El pago se registra contra la
     * ORDEN que ya vive en el server, no contra este carrito. Si el upsell metiera un
     * postre aquí, el cliente lo PAGARÍA y la orden real nunca lo tendría: sin
     * descuento de inventario, fuera de ventas por producto, y con $0 atribuido en el
     * reporte (el ingreso sale de las líneas reales de la orden, a propósito).
     *
     * Cobrado sin registrar. Para habilitarlo hace falta el acomodador de mesa, que
     * agrega vía ADD_ITEMS con comparación de versión contra el server — está en el
     * spec y no está construido. Hasta entonces, mostrador y nada más.
     */
    val isTablePaying = tableSessionActive?.mode == com.avoqado.pos.tables.data.TableSession.Mode.PAYING
    val upsellContext = com.avoqado.pos.pos.presentation.upsell.UpsellContext.COUNTER
    LaunchedEffect(Unit) { upsellViewModel.refresh() }

    // Promociones (PRO) — combos, paquetes y 2x1 a la vista del cajero. Dónde se
    // pinta lo decide el ajuste del local corregido por el ancho REAL de la
    // superficie (ver `resolverModoPanel`); `HIDDEN` lo apaga y es lo único que
    // puede hacerlo desaparecer, porque lo eligió el propio dueño.
    val promotionsViewModel: PromotionsPanelViewModel = hiltViewModel()
    val promociones by promotionsViewModel.promociones.collectAsState()
    val estadoPromociones by promotionsViewModel.estado.collectAsState()
    val ajustePanelPromos by promotionsViewModel.ajustePanelCajero.collectAsState()
    LaunchedEffect(Unit) { promotionsViewModel.refresh() }
    // 🔴 `remember`, no lectura directa: `puedeAplicar` desemboca en
    // `venuePermissions`, que descifra EncryptedSharedPreferences y decodifica
    // JSON. Leerlo en cada recomposición de la pantalla más caliente de la app es
    // caro y no aporta nada.
    // La llave incluye `estadoPromociones` a propósito: ese estado se mueve en
    // CADA ciclo de refresco —incluido el que dispara un cambio de local—, así
    // que un upgrade de plan o un cambio de permisos a media sesión se recogen en
    // el siguiente refresco en vez de quedarse congelados hasta reiniciar la app.
    // (El juez sigue siendo el server al aplicar; esto sólo decide qué se pinta.)
    val promosPlanPermitido = remember(promociones, estadoPromociones) {
        promotionsViewModel.planPermitido
    }
    val promosPuedeAplicar = remember(promociones, estadoPromociones) {
        promotionsViewModel.puedeAplicar
    }
    // Al tocar una promoción: si algún grupo tiene más de una opción se abre la
    // hoja para elegir; si no, entra directo al carrito. La lógica vive en
    // `PromotionSheet.kt` y en `CartViewModel`, no aquí.
    var promocionEnEleccion by remember { mutableStateOf<Promotion?>(null) }
    var promocionAgregada by remember { mutableStateOf<String?>(null) }
    var promocionNoAgregada by remember { mutableStateOf<String?>(null) }
    // Se celebra SÓLO si de verdad entró (`aplicarPromocion` devuelve false
    // cuando falta elegir un grupo): un "¡Combo agregado!" que miente es peor
    // que no celebrar.
    val agregarPromocion: (Promotion, Map<String, String>) -> Unit = { promo, selecciones ->
        if (cartViewModel.aplicarPromocion(promo, selecciones)) {
            promocionAgregada = promo.name
        } else {
            // Un toque que no hace NADA es peor que un error: pasa cuando la
            // promoción llega sin opciones que resolver (dato incompleto del
            // catálogo), y el cajero se queda picando la tarjeta.
            promocionNoAgregada = promo.name
        }
    }
    val onPromotionTap: (Promotion) -> Unit = { promo ->
        if (promo.requiereEleccion) promocionEnEleccion = promo else agregarPromocion(promo, emptyMap())
    }
    // Quitar una línea de promoción quita el combo COMPLETO — se avisa antes.
    // El `CartViewModel` lo garantiza igual venga de donde venga el borrado;
    // esto es el aviso, no la regla.
    var promocionAQuitar by remember { mutableStateOf<CartItem?>(null) }
    val quitarLineaDelCarrito: (String) -> Unit = { itemId ->
        val linea = cartState.items.firstOrNull { it.id == itemId }
        if (linea?.promotionInstanceId != null) promocionAQuitar = linea else cartViewModel.removeItem(itemId)
    }
    LaunchedEffect(tableSessionActive?.orderId, tableSessionActive?.mode) {
        cartViewModel.consumePendingTableCobrar()
    }
    // Class-seed conflict: the walk-in class wasn't added because the cart
    // already links a different reservation — tell the cashier instead of
    // silently dropping it.
    val seedConflict by cartViewModel.seedConflict.collectAsState()
    var seedConflictDialog by remember { mutableStateOf(false) }
    val seedCtx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(seedConflict) {
        if (seedConflict) {
            // En diálogo, no en Toast: el mesero acaba de intentar inscribir una
            // clase y no pasó nada. Necesita entender que el carrito ya está ligado
            // a otra reserva y qué hacer — un aviso que se desvanece (y que en la
            // Sunmi puede ni salir en esta pantalla) lo deja atorado.
            seedConflictDialog = true
            cartViewModel.clearSeedConflict()
        }
    }

    if (seedConflictDialog) {
        AvoqadoDialog(
            title = "El carrito ya tiene una reserva",
            description = "Este carrito está ligado a otra reserva. Cóbralo o vacíalo " +
                "antes de inscribir otra clase.",
            onDismiss = { seedConflictDialog = false },
            actionButton = {
                PrimaryButton(text = "Entendido", onClick = { seedConflictDialog = false })
            },
            content = {},
        )
    }
    var selectedProduct by remember { mutableStateOf<Product?>(null) }
    // Venta por peso (báscula): producto por peso pendiente de capturar peso.
    var weightProduct by remember { mutableStateOf<Product?>(null) }
    // Cómo se ve el mostrador en ESTE aparato: densidad de la cuadrícula y orden
    // de las pestañas. Es estado y no una lectura suelta porque la pestaña
    // "Configurar" lo cambia sin salir de la pantalla — el cajero tiene que ver
    // el efecto al instante, no al reabrir la app.
    val layoutCtx = androidx.compose.ui.platform.LocalContext.current
    var tamanoTiles by remember { mutableStateOf(CheckoutLayoutPrefs.tileSize(layoutCtx)) }
    var ordenGuardado by remember { mutableStateOf(CheckoutLayoutPrefs.ordenGuardado(layoutCtx)) }
    val recargarLayout: () -> Unit = {
        tamanoTiles = CheckoutLayoutPrefs.tileSize(layoutCtx)
        ordenGuardado = CheckoutLayoutPrefs.ordenGuardado(layoutCtx)
    }
    // Abre en la PRIMERA pestaña del orden del aparato, no en el teclado a fuerza:
    // un local que vende por producto no arranca tecleando importes.
    var selectedTab by remember {
        mutableStateOf(ordenarPestanas(ordenGuardado, InputTab.entries).first())
    }
    var showSearch by remember { mutableStateOf(false) }
    var amountCents by remember { mutableIntStateOf(0) }
    // `rememberSaveable`: con `remember` a secas, cambiar de pestaña desmontaba el flujo de
    // cobro y la pantalla "Cobro sin confirmar" desaparecía sin dejar rastro. La llave del
    // cobro vive en disco (ver SecureStorage.pendingCardChargeRequestId), pero además la
    // pantalla debe seguir ahí al volver.
    var showPaymentFlow by rememberSaveable { mutableStateOf(false) }
    // Desenlace de un cobro que quedó pendiente de OTRA venta. Vive aquí —y no en el flujo
    // de pago— porque el mensaje tiene que sobrevivir al cierre de ese flujo.
    var previousChargeNotice by rememberSaveable { mutableStateOf<String?>(null) }
    var paymentCartSnapshot by remember { mutableStateOf<CartState?>(null) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var currentNote by remember { mutableStateOf("") }
    var showIPhoneCart by remember { mutableStateOf(false) }
    var selectedCartItem by remember { mutableStateOf<CartItem?>(null) }
    // 🔴 El cliente de la venta vive en el CARRITO, no en un `remember` de aquí:
    // girar la tablet (o el modo oscuro al anochecer, o un cambio de idioma o de
    // tamaño de fuente) recrea la Activity, el carrito sobrevive y un `remember`
    // pelón volvía a null — la orden nacía SIN cliente y se perdían lealtad, CFDI
    // e historial de esa persona. Tercer caso del mismo patrón en esta pantalla,
    // después de `chargingAgainstOrderId` y `pendingPackGrant`. Ver
    // `CartViewModel.selectedCustomer`.
    val selectedCustomer by cartViewModel.selectedCustomer.collectAsState()
    // Membresías: a credit-pack sale needs a customer; grant captured at charge time.
    var showPackCustomerRequired by remember { mutableStateOf(false) }
    var showPackNoSplitAlert by remember { mutableStateOf(false) }
    var showClearCartConfirm by remember { mutableStateOf(false) }
    // Descuento de la CUENTA COMPLETA, abierto desde el carrito (founder,
    // 2026-09-01). El estado vive aquí y no en el carrito porque las dos
    // variantes —panel de tablet y hoja de teléfono— disparan la misma hoja.
    var showOrderDiscountSheet by remember { mutableStateOf(false) }
    // 🔴 DINERO. Las membresías capturadas viven en el CARRITO, no en un `remember`
    // de aquí: girar la tablet recrea la Activity, `showPaymentFlow` revive
    // (`rememberSaveable`) y un `remember` pelón volvía a null — se cobraban los
    // $500 y el cliente se quedaba sin una sola clase. Ver
    // `CartViewModel.pendingPackGrant`.
    var showCustomersSheet by remember { mutableStateOf(false) }
    var showCreateCustomer by remember { mutableStateOf(false) }
    var createCustomerSearchText by remember { mutableStateOf("") }
    var showSavedSnackbar by remember { mutableStateOf(false) }
    var showBarcodeScanner by remember { mutableStateOf(false) }
    var showCreateProduct by remember { mutableStateOf(false) }
    var createProductInitialName by remember { mutableStateOf("") }
    var createProductInitialGtin by remember { mutableStateOf("") }
    var unknownBarcode by remember { mutableStateOf<String?>(null) }
    var barcodeError by remember { mutableStateOf<String?>(null) }
    var areaTicketAddedCount by remember { mutableStateOf<Int?>(null) }
    // La tarjeta digital que el cajero acaba de escanear, si escaneó una.
    var scannedCustomerCard by remember { mutableStateOf<com.avoqado.pos.loyalty.data.WalletScanResponse?>(null) }
    var showSplitPayment by remember { mutableStateOf(false) }
    // "Dividir la cuenta" from the table panel: auto-open the split sheet once
    // on arrival (flag consumed so re-compositions don't re-open it).
    LaunchedEffect(tableSessionActive?.openSplitOnArrival) {
        tableSessionActive?.takeIf { it.openSplitOnArrival }?.let { session ->
            tablesViewModel.tableSession.start(session.copy(openSplitOnArrival = false))
            showSplitPayment = true
        }
    }
    var showStaffSelector by remember { mutableStateOf(false) }
    var pendingSplitConfig by remember { mutableStateOf(SplitConfig()) }
    var customerSelectionContext by remember { mutableStateOf(CustomerSelectionContext.GENERAL) }
    var isSubmittingPayLater by remember { mutableStateOf(false) }
    var payLaterError by remember { mutableStateOf<String?>(null) }
    var showPayLaterSuccessToast by remember { mutableStateOf(false) }
    var reopenPayLaterToken by remember { mutableIntStateOf(0) }
    val checkoutScope = rememberCoroutineScope()
    val context = LocalContext.current
    val customersViewModel: CustomersViewModel = hiltViewModel()
    val pdfDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val export = areaTicketOperations.state.value.pdfExport
        if (uri == null || export == null) {
            areaTicketOperations.cancelPendingPdfExport()
        } else {
            checkoutScope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                            output.write(export.bytes)
                        } ?: error("No se pudo abrir el archivo seleccionado.")
                    }
                }
                result
                    .onSuccess {
                        areaTicketOperations.confirmPendingPdfSaved {
                            // Fin de venta: el vale quedó emitido y guardado.
                            cartViewModel.finalizarVenta()
                        }
                    }
                    .onFailure { error ->
                        areaTicketOperations.failPendingPdfExport(
                            error.message ?: "No se pudo guardar el PDF.",
                        )
                    }
            }
        }
    }

    LaunchedEffect(areaOperationsState.pdfExport?.code) {
        areaOperationsState.pdfExport?.let { export ->
            runCatching { pdfDocumentLauncher.launch(export.fileName) }
                .onFailure { error ->
                    areaTicketOperations.failPendingPdfExport(
                        error.message ?: "Este dispositivo no tiene un selector de archivos disponible.",
                    )
                }
        }
    }

    // 🔴 Aquí vivía el espejo `LaunchedEffect(selectedCustomer?.id) {
    // setSelectedCustomer(...) }` que copiaba el `remember` de la pantalla hacia
    // el carrito. Se borró, y no sólo por redundante: al recrearse la Activity el
    // `remember` nacía en null y este efecto **borraba también la copia buena del
    // ViewModel** antes de que nadie la leyera. Con dos copias, la frágil ganaba.
    // Ahora hay una sola, la del carrito, y la pantalla sólo la lee.

    val openGeneralCustomerPicker: () -> Unit = {
        customerSelectionContext = CustomerSelectionContext.GENERAL
        showCustomersSheet = true
    }
    val openPayLaterCustomerPicker: () -> Unit = {
        customerSelectionContext = CustomerSelectionContext.PAY_LATER
        showCustomersSheet = true
    }
    fun confirmPayLaterOrder() {
        if (isSubmittingPayLater) return
        // Del FLUJO, no de la variable capturada por `collectAsState()`: es el
        // mismo criterio que `proceedToPayment` con el carrito — en la ruta que
        // manda un id al server se lee el valor de AHORA, no el de la última
        // recomposición.
        val customerId = cartViewModel.selectedCustomer.value?.id
        if (customerId.isNullOrBlank()) {
            openPayLaterCustomerPicker()
            return
        }
        checkoutScope.launch {
            isSubmittingPayLater = true
            val payLaterResult = cartViewModel.createPayLaterOrder(customerId)
            isSubmittingPayLater = false
            payLaterResult.fold(
                onSuccess = {
                    // La venta se fue a "pagar después" a nombre de este cliente:
                    // aquí termina, así que el cliente se suelta con el carrito.
                    cartViewModel.finalizarVenta()
                    customerSelectionContext = CustomerSelectionContext.GENERAL
                    showPayLaterSuccessToast = true
                },
                onFailure = { error ->
                    payLaterError = error.message ?: "No se pudo registrar la venta como pagar después"
                },
            )
        }
    }

    /**
     * Congela el total y abre el cobro.
     *
     * 🔴 El carrito se lee del FLUJO (`cartViewModel.cartState.value`), NO de la
     * variable `cartState` capturada por `collectAsState()`. Si el upsell acaba de
     * meter un producto, esa variable todavía trae el carrito VIEJO hasta la
     * siguiente recomposición: se cobraría sin el producto aceptado y `clearCart()`
     * lo borraría después — el negocio regala el producto y la orden ni lo registra.
     */
    fun proceedToPayment(cart: CartState = cartViewModel.cartState.value) {
        // 🔴 DINERO. Del FLUJO, no de la variable capturada: quién recibe las
        // membresías se decide con el cliente de AHORA. Mismo criterio que el
        // carrito en la firma de esta función.
        cartViewModel.capturePendingPackGrant(cartViewModel.selectedCustomer.value?.id, cart)
        paymentCartSnapshot = cart.paymentSnapshot()
        // 🔴 DINERO. ¿Esta venta ya tiene una orden abierta de una parte anterior?
        // Se resuelve AQUÍ, en el momento del cobro, contra el carrito real: si el
        // cajero agregó mercancía nueva el vínculo se rompe (y avisa) en vez de
        // cobrarle en silencio a la orden vieja algo que no tiene registrado.
        // En mesa manda la sesión, que ya siembra su propia orden.
        //
        // El resultado se congela DENTRO del carrito (`chargingAgainstOrderId`),
        // no en un `remember` de esta pantalla: así sobrevive a que se recree la
        // Activity —girar la tablet— que antes borraba el vínculo y revivía el bug.
        if (isTablePaying) cartViewModel.releaseChargingOrder() else cartViewModel.resolvePendingSplitOrderForCharge()
        showPaymentFlow = true
    }

    /**
     * Cierra el momento de upsell y sigue al cobro — por CUALQUIERA de las dos
     * superficies (la tira del cajero o la pantalla del cliente).
     *
     * 🔴 Pase lo que pase, esto termina en `proceedToPayment()`. Un fallo al
     * agregar un postre no puede dejar al mostrador sin poder cobrar.
     */
    fun resolveUpsell(accept: Boolean) {
        // Del FLUJO, no de `upsellMoment` capturado: el toque pudo llegar de la
        // pantalla del cliente, fuera de esta recomposición.
        val moment = upsellViewModel.moment.value ?: return
        if (!accept) {
            upsellViewModel.finish(emptyList(), 0)
            proceedToPayment()
            return
        }
        checkoutScope.launch {
            // 🔴 El acomodador re-valida contra el catálogo VIVO y DEVUELVE el
            // carrito resultante. De ahí sale el snapshot, de ningún otro lado:
            // leer `cartState` aquí daría el carrito de ANTES de agregar.
            val result = runCatching {
                com.avoqado.pos.pos.domain.CounterUpsellAcceptor(cartViewModel)
                    .accept(moment.selectedCards, upsellViewModel.catalog)
            }.getOrNull()

            if (result == null) {
                upsellViewModel.finish(emptyList(), 0)
                proceedToPayment()
                return@launch
            }

            val addedCents = result.cart.subtotalCents - moment.cartSubtotalBefore
            upsellViewModel.finish(result.added, addedCents)
            // El cliente ve su carrito ya actualizado antes de pagar.
            cartViewModel.refreshCustomerDisplay()
            proceedToPayment(result.cart)
        }
    }

    fun runPrimaryAction(closePhoneCart: Boolean = false) {
        if (areaOperationsState.issueWorkspace) {
            areaTicketOperations.issue(cartState) {
                // Fin de venta: el vale se emitió.
                cartViewModel.finalizarVenta()
                if (closePhoneCart) showIPhoneCart = false
            }
            return
        }
        // Del flujo, igual que `capturePendingPackGrant` unas líneas abajo: el
        // guard que exige cliente y la captura que lo usa no pueden leer copias
        // distintas, o se cobraría un paquete que nadie recibe.
        if (cartViewModel.hasCreditPack && cartViewModel.selectedCustomer.value == null) {
            showPackCustomerRequired = true
            return
        }
        if (closePhoneCart) showIPhoneCart = false
        pendingSplitConfig = SplitConfig()

        // Upsell "¿Algo más?" — va AQUÍ, antes de congelar el total. Si hay algo
        // que ofrecer, el cobro espera a que se resuelva la tira; si no (sin
        // reglas, perilla apagada, grupo de control, o cualquier error), sigue
        // de largo. Ofrecer un postre jamás puede impedir un cobro.
        // `!isTablePaying`: ver la nota del candado arriba — en una mesa se cobraría
        // un producto que la orden del server nunca registra.
        if (!isTablePaying && upsellViewModel.offer(cartViewModel.cartState.value, upsellContext) != null) return

        proceedToPayment()
    }

    if (isTablet) {
        // iPad-style: 50/50 split con entrada + carrito — o 50/25/25 cuando el
        // panel de promociones entra como TERCERA columna.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 🔴 Manda el ancho REAL de esta superficie, no el booleano `isTablet`:
            // ese colapsa "tablet chica" y "tablet grande" en el mismo valor, y aquí
            // hay que decidir si cabe una tercera columna. Además `maxWidth` ya viene
            // sin lo que se come la barra de navegación lateral.
            val modoPanelPromos = resolverModoPanel(ajustePanelPromos, maxWidth.value.toInt())
            val pestanasTablet = ordenarPestanas(ordenGuardado, pestanasVisibles(modoPanelPromos))
            // La pestaña de promociones puede desaparecer al girar la tablet o al
            // cambiar el ajuste. Si el cajero estaba parado en ella se le devuelve a la
            // primera de su orden, en vez de dejarlo mirando una pestaña que ya no existe.
            LaunchedEffect(pestanasTablet) {
                if (selectedTab !in pestanasTablet) selectedTab = pestanasTablet.first()
            }
            Row(modifier = Modifier.fillMaxSize()) {
                // Left panel - Input area
                Box(
                    modifier = Modifier
                        .weight(0.5f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.surface),
                ) {
                    if (showSearch) {
                        SearchOverlayView(
                            viewModel = cartViewModel,
                            onProductTap = { product ->
                                handleProductTap(product, cartViewModel, { selectedProduct = it }, { weightProduct = it })
                                showSearch = false
                            },
                            onCreateProduct = if (roleManager?.canCreateProducts != false) {
                                { searchName ->
                                    showSearch = false
                                    createProductInitialName = searchName
                                    showCreateProduct = true
                                }
                            } else null,
                            onDismiss = { showSearch = false },
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // Search bar + refresh + barcode
                            SearchBarView(
                                isLoading = isLoading,
                                onSearchTap = { showSearch = true },
                                onRefresh = { cartViewModel.refreshProducts() },
                                onBarcodeScan = { showBarcodeScanner = true },
                            )

                            // Tab selector
                            TabSelectorView(
                                tabs = pestanasTablet,
                                selectedTab = selectedTab,
                                onTabSelected = { selectedTab = it },
                            )

                            // Tab content
                            when (selectedTab) {
                                InputTab.KEYPAD -> {
                                    NumericKeypadView(
                                        amountCents = amountCents,
                                        onAmountChange = { amountCents = it },
                                        onAddToCart = {
                                            if (amountCents > 0) {
                                                cartViewModel.addCustomAmount(
                                                    name = currentNote.ifBlank { "Importe personalizado" },
                                                    amountCents = amountCents,
                                                )
                                                amountCents = 0
                                                currentNote = ""
                                            }
                                        },
                                        onNoteTap = { showNoteDialog = true },
                                        noteText = currentNote,
                                        useCompactSizing = true,
                                    )
                                }
                                InputTab.SHORTCUTS -> {
                                    ShortcutsGridView(
                                        cartViewModel = cartViewModel,
                                        discountsRepository = cartViewModel.discountsRepository,
                                        onCustomerSearch = openPayLaterCustomerPicker,
                                        reopenPayLaterToken = reopenPayLaterToken,
                                        selectedPayLaterCustomerName = selectedCustomer?.name,
                                        onConfirmPayLater = ::confirmPayLaterOrder,
                                        isConfirmingPayLater = isSubmittingPayLater,
                                        onCreateItem = { showCreateProduct = true },
                                        onProductTap = { product ->
                                            handleProductTap(product, cartViewModel, { selectedProduct = it }, { weightProduct = it })
                                        },
                                        canCreateProducts = roleManager?.canCreateProducts ?: true,
                                    )
                                }
                                InputTab.PRODUCTS -> {
                                    ProductGridView(
                                        viewModel = cartViewModel,
                                        onProductTap = { product ->
                                            handleProductTap(
                                                product,
                                                cartViewModel,
                                                { selectedProduct = it },
                                                { weightProduct = it },
                                            )
                                        },
                                        onPackTap = if (roleManager?.canReadCreditPacks == true) {
                                            { cartViewModel.addCreditPack(it) }
                                        } else {
                                            null
                                        },
                                        tileSize = tamanoTiles,
                                    )
                                }
                                InputTab.PROMOS -> {
                                    PromotionsPanel(
                                        vigentes = promociones.active,
                                        proximas = promociones.upcoming,
                                        estado = estadoPromociones,
                                        planPermitido = promosPlanPermitido,
                                        puedeAplicar = promosPuedeAplicar,
                                        onPromotionTap = onPromotionTap,
                                    )
                                }
                                InputTab.MOSAIC -> {
                                    MosaicConfigView(
                                        cartViewModel = cartViewModel,
                                        pestanasVisiblesHoy = pestanasTablet,
                                        onLayoutChanged = recargarLayout,
                                    )
                                }
                            }
                        }
                    }
                }

                // Vertical divider
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )

                // Middle panel - Promociones (sólo en modo columna). La entrada se
                // queda con su 50% y el 50% restante se parte entre promociones y
                // carrito: quien paga la tercera columna es el CARRITO, no la
                // cuadrícula de productos.
                // Por eso el piso estricto de la cuadrícula es 720dp
                // (ANCHO_ESTRICTO_PANEL_LATERAL_DP: 3 celdas de 120dp dentro del
                // 50%), y el umbral que usamos —960— es ese piso MÁS un margen
                // elegido a mano: a 720 cada columna lateral cae a ~180dp y la
                // tarjeta se ve apretada. Ver ANCHO_MINIMO_PANEL_LATERAL_DP.
                if (modoPanelPromos == PanelMode.SIDE_PANEL) {
                    Box(
                        modifier = Modifier
                            .weight(0.25f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surface),
                    ) {
                        PromotionsPanel(
                            vigentes = promociones.active,
                            proximas = promociones.upcoming,
                            estado = estadoPromociones,
                            planPermitido = promosPlanPermitido,
                            puedeAplicar = promosPuedeAplicar,
                            onPromotionTap = onPromotionTap,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }

                // Right panel - Cart
                Box(
                    modifier = Modifier
                        .weight(if (modoPanelPromos == PanelMode.SIDE_PANEL) 0.25f else 0.5f)
                        .fillMaxHeight(),
                ) {
                    CartPanelView(
                        cartState = cartState,
                        onItemTap = { item -> selectedCartItem = item },
                        onOrderTypeChange = { cartViewModel.setOrderType(it) },
                        onCharge = {
                            runPrimaryAction()
                        },
                        onClearCart = { showClearCartConfirm = true },
                        onSaveCart = {
                            if (cartViewModel.saveCurrentCart()) {
                                showSavedSnackbar = true
                            }
                        },
                        onAddCustomAmount = { selectedTab = InputTab.KEYPAD },
                        onRemoveItem = quitarLineaDelCarrito,
                        onApplyTaxPercent = { cartViewModel.applyOrderTaxPercent(it) },
                        onDiscountTap = { showOrderDiscountSheet = true },
                        customerName = selectedCustomer?.name,
                        customerId = selectedCustomer?.id,
                        onCustomerTap = openGeneralCustomerPicker,
                        staffName = cartState.selectedStaffName,
                        onStaffTap = {
                            cartViewModel.fetchStaff()
                            showStaffSelector = true
                        },
                        onSplitPayment = {
                            // Membresías grant only on FULL payment — a split sale
                            // charged the pack without ever granting credits.
                            if (cartViewModel.hasCreditPack) showPackNoSplitAlert = true
                            else showSplitPayment = true
                        },
                        referralCode = referralCodeState,
                        referralUiState = referralUiState,
                        customerSelectedForReferral = selectedCustomer != null,
                        onReferralCodeChange = { cartViewModel.onReferralCodeChange(it) },
                        onValidateReferral = { cartViewModel.validateReferralCode() },
                        onClearReferral = { cartViewModel.clearReferral() },
                        onForceOverrideReferral = {
                            // v1: placeholder hook — the manager-PIN dialog lands in v2.
                            // Disabled at the button level, but keep the lambda wired
                            // so future work just flips the `enabled` flag.
                        },
                        referralPlanAllowed = cartViewModel.referralPlanAllowed,
                        primaryActionLabel = if (areaOperationsState.issueWorkspace) {
                            "Emitir vale ${cartState.totalDisplay}"
                        } else {
                            null
                        },
                    )
                }
            }
        }
    } else {
        // iPhone-style: full-screen with bottom cart bar
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // Un teléfono es siempre Compact (<600dp), o sea que el lateral SIEMPRE
            // cae a pestaña aquí. Se calcula con el ancho real igual, para no tener
            // dos reglas; y la pestaña se muestra con cualquier modo que no sea
            // HIDDEN, para que un valor inesperado no la haga desaparecer en
            // silencio en el único layout que no tiene tercera columna.
            val modoPanelPromos = resolverModoPanel(ajustePanelPromos, maxWidth.value.toInt())
            val pestanasTelefono = ordenarPestanas(
                ordenGuardado,
                pestanasVisibles(modoPanelPromos, siempreComoPestana = true),
            )
            LaunchedEffect(pestanasTelefono) {
                if (selectedTab !in pestanasTelefono) selectedTab = pestanasTelefono.first()
            }
            Column(modifier = Modifier.fillMaxSize()) {
                if (showSearch) {
                    SearchOverlayView(
                        viewModel = cartViewModel,
                        onProductTap = { product ->
                            handleProductTap(product, cartViewModel, { selectedProduct = it }, { weightProduct = it })
                            showSearch = false
                        },
                        onCreateProduct = if (roleManager?.canCreateProducts != false) {
                            { searchName ->
                                showSearch = false
                                createProductInitialName = searchName
                                showCreateProduct = true
                            }
                        } else null,
                        onDismiss = { showSearch = false },
                    )
                } else {
                    // Search bar
                    SearchBarView(
                        isLoading = isLoading,
                        onSearchTap = { showSearch = true },
                        onRefresh = { cartViewModel.refreshProducts() },
                        onBarcodeScan = { showBarcodeScanner = true },
                    )

                    // Tab selector
                    TabSelectorView(
                        tabs = pestanasTelefono,
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it },
                    )

                    // Tab content
                    Box(modifier = Modifier.weight(1f)) {
                        when (selectedTab) {
                            InputTab.KEYPAD -> {
                                NumericKeypadView(
                                    amountCents = amountCents,
                                    onAmountChange = { amountCents = it },
                                    onAddToCart = {
                                        if (amountCents > 0) {
                                            cartViewModel.addCustomAmount(
                                                name = currentNote.ifBlank { "Importe personalizado" },
                                                amountCents = amountCents,
                                            )
                                            amountCents = 0
                                            currentNote = ""
                                        }
                                    },
                                    onNoteTap = { showNoteDialog = true },
                                    noteText = currentNote,
                                    useCompactSizing = false,
                                )
                            }
                            InputTab.SHORTCUTS -> {
                                ShortcutsGridView(
                                    cartViewModel = cartViewModel,
                                    discountsRepository = cartViewModel.discountsRepository,
                                    onCustomerSearch = openPayLaterCustomerPicker,
                                    reopenPayLaterToken = reopenPayLaterToken,
                                    selectedPayLaterCustomerName = selectedCustomer?.name,
                                    onConfirmPayLater = ::confirmPayLaterOrder,
                                    isConfirmingPayLater = isSubmittingPayLater,
                                    onCreateItem = { showCreateProduct = true },
                                    onProductTap = { product ->
                                        handleProductTap(product, cartViewModel, { selectedProduct = it }, { weightProduct = it })
                                    },
                                    canCreateProducts = roleManager?.canCreateProducts ?: true,
                                )
                            }
                            InputTab.PRODUCTS -> {
                                ProductGridView(
                                    viewModel = cartViewModel,
                                    onProductTap = { product ->
                                        handleProductTap(
                                            product,
                                            cartViewModel,
                                            { selectedProduct = it },
                                            { weightProduct = it },
                                        )
                                    },
                                    onPackTap = { cartViewModel.addCreditPack(it) },
                                    tileSize = tamanoTiles,
                                )
                            }
                            InputTab.PROMOS -> {
                                PromotionsPanel(
                                    vigentes = promociones.active,
                                    proximas = promociones.upcoming,
                                    estado = estadoPromociones,
                                    planPermitido = promosPlanPermitido,
                                    puedeAplicar = promosPuedeAplicar,
                                    onPromotionTap = onPromotionTap,
                                )
                            }
                            InputTab.MOSAIC -> {
                                MosaicConfigView(
                                    cartViewModel = cartViewModel,
                                    pestanasVisiblesHoy = pestanasTelefono,
                                    onLayoutChanged = recargarLayout,
                                )
                            }
                        }
                    }

                    // Bottom cart bar (iPhone only)
                    if (!cartState.isEmpty) {
                        IPhoneCartBar(
                            itemCount = cartState.itemCount,
                            total = cartState.subtotalDisplay,
                            onClick = { showIPhoneCart = true },
                        )
                    }
                }
            }
        }
    }

    // Product detail panel overlay
    selectedProduct?.let { product ->
        val discounts by cartViewModel.discountsRepository.discounts.collectAsState()
        ProductDetailPanel(
            product = product,
            isTablet = isTablet,
            discounts = discounts,
            onAddToCart = { quantity, modifiers, note, isCortesia, cortesiaReason, priceAdj, discountId ->
                cartViewModel.addProductWithModifiers(
                    product = product,
                    quantity = quantity,
                    modifiers = modifiers,
                    note = note,
                    isCortesia = isCortesia,
                    cortesiaReason = cortesiaReason,
                    priceAdjustment = priceAdj,
                    // El descuento COMPLETO, no su id: la línea congela tipo y
                    // valor para cobrar lo mismo que el server va a registrar.
                    // Antes sólo viajaba el id y el carrito cobraba precio de
                    // lista mientras la orden quedaba rebajada.
                    discount = discountId?.let { id -> discounts.firstOrNull { it.id == id } },
                )
                selectedProduct = null
            },
            onDismiss = { selectedProduct = null },
        )
    }

    // Venta por peso: panel de captura de peso (báscula/manual) para productos soldByWeight.
    weightProduct?.let { product ->
        LaunchedEffect(product.id, areaOperationsState.settings?.scaleIntegration) {
            scaleCaptureViewModel.start(areaOperationsState.settings?.scaleIntegration)
        }
        WeightCapturePanel(
            product = product,
            isTablet = isTablet,
            scaleState = scaleState,
            onRetryScale = scaleCaptureViewModel::retry,
            onAdd = { weightKg ->
                cartViewModel.addProductByWeight(product, weightKg)
            },
            onDismiss = {
                scaleCaptureViewModel.stop()
                weightProduct = null
            },
        )
    }

    // Promociones: hoja de elección, aviso al quitar y celebración al agregar.
    promocionEnEleccion?.let { promo ->
        PromotionSheet(
            promocion = promo,
            onDismiss = { promocionEnEleccion = null },
            onConfirm = { selecciones ->
                agregarPromocion(promo, selecciones)
                promocionEnEleccion = null
            },
        )
    }

    promocionAQuitar?.let { linea ->
        AvoqadoDialog(
            title = "¿Quitar ${linea.promotionName ?: "la promoción"}?",
            description = "Se quitará el combo completo: todos sus productos salen del carrito.",
            onDismiss = { promocionAQuitar = null },
            actionButton = {
                PrimaryButton(
                    text = "Quitar combo",
                    destructive = true,
                    fullWidth = true,
                    onClick = {
                        linea.promotionInstanceId?.let { cartViewModel.quitarPromocion(it) }
                        promocionAQuitar = null
                        selectedCartItem = null
                    },
                )
            },
            content = {},
        )
    }

    promocionAgregada?.let { nombre ->
        AvoqadoSuccessToast(
            message = "¡Combo agregado!",
            subtitle = nombre,
            onDismiss = { promocionAgregada = null },
        )
    }

    promocionNoAgregada?.let { nombre ->
        AvoqadoWarningToast(
            message = "No se pudo agregar la promoción",
            subtitle = "$nombre no trae opciones que aplicar. Revísala en el dashboard.",
            onDismiss = { promocionNoAgregada = null },
        )
    }

    // Cart item detail overlay (matching iOS: side panel on tablet, sheet on phone)
    selectedCartItem?.let { item ->
        CartItemDetailPanel(
            item = item,
            isTablet = isTablet,
            onUpdateQuantity = { newQty ->
                cartViewModel.updateQuantity(item.id, newQty)
                // Refresh state
                selectedCartItem = cartViewModel.cartState.value.items.find { it.id == item.id }
            },
            onUpdateNote = { note ->
                cartViewModel.updateItemNote(item.id, note)
                selectedCartItem = cartViewModel.cartState.value.items.find { it.id == item.id }
            },
            onToggleCortesia = { isCortesia, reason ->
                cartViewModel.updateItemCortesia(item.id, isCortesia, reason)
                selectedCartItem = cartViewModel.cartState.value.items.find { it.id == item.id }
            },
            onUpdatePriceAdjustment = { priceCents ->
                cartViewModel.updateItemPriceAdjustment(item.id, priceCents)
                selectedCartItem = cartViewModel.cartState.value.items.find { it.id == item.id }
            },
            onDelete = {
                quitarLineaDelCarrito(item.id)
                selectedCartItem = null
            },
            onDismiss = { selectedCartItem = null },
        )
    }

    // iPhone full-screen cart sheet (matching iOS fullScreenCover)
    if (showIPhoneCart) {
        IPhoneCartSheet(
            cartState = cartState,
            onItemTap = { item -> selectedCartItem = item },
            onOrderTypeChange = { cartViewModel.setOrderType(it) },
            onCharge = {
                runPrimaryAction(closePhoneCart = true)
            },
            onClearCart = { showClearCartConfirm = true },
            onSaveCart = {
                if (cartViewModel.saveCurrentCart()) {
                    showSavedSnackbar = true
                }
            },
            onAddCustomAmount = {
                showIPhoneCart = false
                selectedTab = InputTab.KEYPAD
            },
            onRemoveItem = quitarLineaDelCarrito,
            onApplyTaxPercent = { cartViewModel.applyOrderTaxPercent(it) },
            onDiscountTap = {
                // Mismo patrón que dividir cuenta: el carrito de teléfono ocupa
                // la pantalla completa, así que se cierra ANTES o la hoja del
                // descuento queda detrás y el renglón parece no hacer nada.
                showIPhoneCart = false
                showOrderDiscountSheet = true
            },
            staffName = cartState.selectedStaffName,
            onStaffTap = {
                cartViewModel.fetchStaff()
                showStaffSelector = true
            },
            onSplitPayment = {
                // Membresías grant only on FULL payment — mismo guard que en tablet.
                if (cartViewModel.hasCreditPack) {
                    showPackNoSplitAlert = true
                } else {
                    // 🔴 El carrito de teléfono ocupa la pantalla completa: hay que
                    // cerrarlo ANTES de abrir la hoja de dividir, o la hoja queda
                    // detrás y el renglón parece no hacer nada. Mismo patrón que
                    // `onAddCustomAmount` y `onCharge(closePhoneCart = true)`.
                    showIPhoneCart = false
                    showSplitPayment = true
                }
            },
            customerName = selectedCustomer?.name,
            customerId = selectedCustomer?.id,
            onCustomerTap = openGeneralCustomerPicker,
            referralCode = referralCodeState,
            referralUiState = referralUiState,
            customerSelectedForReferral = selectedCustomer != null,
            onReferralCodeChange = { cartViewModel.onReferralCodeChange(it) },
            onValidateReferral = { cartViewModel.validateReferralCode() },
            onClearReferral = { cartViewModel.clearReferral() },
            onForceOverrideReferral = { /* v1 placeholder */ },
            referralPlanAllowed = cartViewModel.referralPlanAllowed,
            onDismiss = { showIPhoneCart = false },
            primaryActionLabel = if (areaOperationsState.issueWorkspace) {
                "Emitir vale ${cartState.totalDisplay}"
            } else {
                null
            },
        )
    }

    if (showStaffSelector) {
        StaffSelectorSheet(
            staff = staffOptions,
            selectedStaffId = cartState.selectedStaffId,
            isLoading = isStaffLoading,
            error = staffError,
            onStaffSelected = { staff ->
                cartViewModel.selectStaff(staff.id, staff.fullName)
            },
            onDismiss = { showStaffSelector = false },
        )
    }

    if (showOrderDiscountSheet) {
        com.avoqado.pos.pos.presentation.cart.OrderDiscountSheet(
            cartViewModel = cartViewModel,
            discountsRepository = cartViewModel.discountsRepository,
            onDismiss = { showOrderDiscountSheet = false },
        )
    }

    // 🔴 UN solo manejador para la cámara y para el lector de pistola: el `when` sobre
    // `ScannedBarcodeResult` vive aquí una vez. Duplicarlo por canal de entrada es
    // exactamente el defecto que tuvo el servidor con los sellos (tarjeta sí, efectivo no).
    val manejarCodigo: (String) -> Unit = { barcode ->
        showBarcodeScanner = false
        checkoutScope.launch {
            when (val result = cartViewModel.resolveScannedBarcode(barcode)) {
                is ScannedBarcodeResult.ProductFound ->
                    handleProductTap(
                        result.product,
                        cartViewModel,
                        { selectedProduct = it },
                        { weightProduct = it },
                    )
                is ScannedBarcodeResult.WeightedProductFound ->
                    cartViewModel.addProductByWeight(result.product, result.weightKg)
                is ScannedBarcodeResult.AreaTicketsAdded ->
                    areaTicketAddedCount = result.ticketCount
                is ScannedBarcodeResult.CustomerCardFound -> {
                    // 🔴 Escanear la tarjeta LIGA la venta al cliente, y eso es lo
                    // que hace que el sello suba solo al cobrar. Sin esta línea el
                    // cajero vería el nombre pero la compra no contaría para su
                    // cartilla, que es justo lo que el cliente vino a buscar.
                    val c = result.card.customer
                    if (c != null) {
                        val nombre = listOfNotNull(c.firstName, c.lastName)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                            .ifBlank { null }
                        cartViewModel.setSelectedCustomer(c.id, nombre)
                    }
                    scannedCustomerCard = result.card
                }
                is ScannedBarcodeResult.Unknown ->
                    unknownBarcode = result.code
                is ScannedBarcodeResult.Error ->
                    barcodeError = result.message
            }
        }
    }

    // Lector de pistola (USB/Bluetooth): teclea el código y cierra con Enter. Llega por
    // `LectorHidBus` desde la Activity y entra por el MISMO camino que la cámara.
    LaunchedEffect(Unit) {
        cartViewModel.codigosEscaneados.collect { manejarCodigo(it) }
    }

    // Barcode scanner overlay
    if (showBarcodeScanner) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            BarcodeScannerView(
                onBarcodeScanned = manejarCodigo,
                onDismiss = { showBarcodeScanner = false },
            )
        }
    }

    // Create product overlay
    if (showCreateProduct) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
            )
            CreateProductView(
                productsRepository = cartViewModel.productsRepository,
                initialName = createProductInitialName,
                initialGtin = createProductInitialGtin,
                onProductCreated = { product ->
                    showCreateProduct = false
                    createProductInitialName = ""
                    createProductInitialGtin = ""
                    handleProductTap(product, cartViewModel, { selectedProduct = it }, { weightProduct = it })
                },
                onDismiss = {
                    showCreateProduct = false
                    createProductInitialName = ""
                    createProductInitialGtin = ""
                },
            )
        }
    }

    // Unknown barcode confirmation dialog
    // La tarjeta digital de un cliente, recién escaneada.
    //
    // 🔴 Se usa el MISMO `AvoqadoDialog` que los otros avisos del escáner, a propósito:
    // un componente propio para esto haría que el cajero tenga que aprender otra cosa
    // en el momento de más prisa del mostrador.
    scannedCustomerCard?.let { tarjeta ->
        val cliente = tarjeta.customer
        val nombre = listOfNotNull(cliente?.firstName, cliente?.lastName)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Cliente identificado" }

        val premio = tarjeta.rewardsToClaim.firstOrNull()
        val avance = "${tarjeta.stampsEarned} de ${tarjeta.stampsRequired} sellos"
        val detalle = if (premio != null) {
            // El premio se dice PRIMERO: es lo único que el cajero tiene que accionar,
            // y si va después del avance se lo salta.
            "🎁 Tiene un premio por cobrar: ${premio.rewardLabel}\n\n$avance · esta compra le suma otro"
        } else {
            "$avance · esta compra le suma otro"
        }

        AvoqadoDialog(
            title = nombre,
            description = detalle,
            onDismiss = { scannedCustomerCard = null },
            actionButton = {
                // 🔴 Con premio, el botón principal es APLICARLO. El cajero tiene al
                // cliente enfrente y una fila detrás: si la acción que importa está
                // escondida detrás de un "Listo", no ocurre.
                //
                // No se descuenta nada aquí: la orden todavía no existe. Se marca, y el
                // servidor lo aplica al crearla — por eso el total que se cobra ya
                // viene descontado.
                if (premio != null) {
                    PrimaryButton(
                        text = "Aplicar ${premio.rewardLabel}",
                        onClick = {
                            cartViewModel.setPendingStampReward(premio.id)
                            scannedCustomerCard = null
                        },
                        fullWidth = true,
                    )
                } else {
                    PrimaryButton(
                        text = "Listo",
                        onClick = { scannedCustomerCard = null },
                        fullWidth = true,
                    )
                }
            },
        ) {}
    }

    unknownBarcode?.let { scannedCode ->
        AvoqadoDialog(
            title = "Producto no encontrado",
            description = "No existe un producto con el código $scannedCode. ¿Quieres crear uno nuevo?",
            onDismiss = { unknownBarcode = null },
            actionButton = {
                PrimaryButton(
                    text = "Crear nuevo",
                    onClick = {
                        createProductInitialGtin = scannedCode
                        unknownBarcode = null
                        showCreateProduct = true
                    },
                    fullWidth = true,
                )
            },
        ) {}
    }

    barcodeError?.let { message ->
        AvoqadoDialog(
            title = "No se pudo agregar el vale",
            description = message,
            onDismiss = { barcodeError = null },
            actionButton = {
                PrimaryButton(
                    text = "Entendido",
                    onClick = { barcodeError = null },
                    fullWidth = true,
                )
            },
        ) {}
    }

    areaTicketAddedCount?.let { count ->
        AvoqadoSuccessToast(
            message = "¡Vale agregado!",
            subtitle = "$count ${if (count == 1) "vale" else "vales"} en esta venta",
            onDismiss = { areaTicketAddedCount = null },
        )
    }

    // Producto agotado agregado a la venta: aviso ámbar, nunca bloqueo — el
    // stock quedará en negativo como señal de descuadre (Square-parity).
    val stockWarning by cartViewModel.stockWarning.collectAsState()
    stockWarning?.let { aviso ->
        AvoqadoWarningToast(
            message = "Producto sin existencias",
            subtitle = aviso,
            onDismiss = cartViewModel::consumeStockWarning,
        )
    }

    // 🔴 Dinero de una venta ANTERIOR: no es una celebración, es algo que el cajero tiene que
    // atender (buscar esa venta, dar el recibo). Por eso NO usa el toast verde de palomita —
    // se queda hasta que lo descarte a conciencia.
    previousChargeNotice?.let { message ->
        AvoqadoDialog(
            title = "Cobro anterior resuelto",
            description = "$message. Búscala en Ventas si necesitas dar el recibo.",
            onDismiss = { previousChargeNotice = null },
            actionButton = {
                PrimaryButton(
                    text = "Entendido",
                    onClick = { previousChargeNotice = null },
                    fullWidth = true,
                )
            },
        ) {}
    }

    // 🔴 El cliente PAGÓ su membresía y el paquete no se le entregó. La entrega
    // quedó encolada y se reintenta sola, pero eso no basta: si el motivo es
    // permanente (un 403 de permiso, p. ej.) la cola reintenta para siempre sin
    // éxito, y sin este aviso NADIE en el mostrador se entera de que hay dinero
    // cobrado sin su contraparte. Mismo trato que el cobro anterior resuelto: se
    // acusa de recibo, no se desvanece como un toast. Y espera a que cierre el
    // cobro para no secuestrarle al cliente la propina/el recibo.
    if (membresiasSinEntregar > 0 && !showPaymentFlow) {
        val varias = membresiasSinEntregar > 1
        AvoqadoDialog(
            title = if (varias) "Membresías pendientes de entregar" else "Membresía pendiente de entregar",
            description = if (varias) {
                "El cobro sí se hizo, pero los $membresiasSinEntregar paquetes no se le " +
                    "entregaron al cliente. La app lo sigue intentando. Avísale a un encargado."
            } else {
                "El cobro sí se hizo, pero el paquete no se le entregó al cliente. " +
                    "La app lo sigue intentando. Avísale a un encargado."
            },
            onDismiss = { creditsViewModel.clearUndeliveredGrants() },
            actionButton = {
                PrimaryButton(
                    text = "Entendido",
                    onClick = { creditsViewModel.clearUndeliveredGrants() },
                    fullWidth = true,
                )
            },
        ) {}
    }

    areaOperationsState.message?.let { message ->
        AvoqadoSuccessToast(
            message = message,
            onDismiss = areaTicketOperations::dismissFeedback,
        )
    }

    areaOperationsState.checkoutBlockingError?.let { message ->
        AvoqadoDialog(
            title = "Vale por área",
            description = message,
            onDismiss = areaTicketOperations::dismissFeedback,
            actionButton = {
                PrimaryButton(
                    text = "Entendido",
                    onClick = areaTicketOperations::dismissFeedback,
                    fullWidth = true,
                )
            },
        ) {}
    }

    if (areaOperationsState.error == null && areaOperationsState.pendingReprintCode != null) {
        AvoqadoDialog(
            title = "Vale pendiente de impresión",
            description = "El vale ${areaOperationsState.pendingReprintCode} ya existe y no se emitirá otro. Reimprímelo o guárdalo como PDF con el mismo código.",
            onDismiss = {
                if (!areaOperationsState.submitting && !areaOperationsState.preparingPdf) {
                    areaTicketOperations.dismissPendingReprint()
                }
            },
            actionButton = {
                PrimaryButton(
                    text = if (areaOperationsState.submitting) "Reimprimiendo…" else "Reimprimir",
                    onClick = {
                        areaTicketOperations.reprintPending {
                            // Fin de venta: el vale se reimprimió y queda emitido.
                            cartViewModel.finalizarVenta()
                        }
                    },
                    enabled = !areaOperationsState.submitting && !areaOperationsState.preparingPdf,
                    fullWidth = true,
                )
            },
        ) {
            PrimaryButton(
                text = if (areaOperationsState.preparingPdf) {
                    "Preparando PDF…"
                } else {
                    "Guardar PDF y continuar"
                },
                onClick = areaTicketOperations::preparePendingPdf,
                enabled = !areaOperationsState.submitting && !areaOperationsState.preparingPdf,
                fullWidth = true,
            )
        }
    }

    // Payment flow overlay (full screen, matching iOS fullScreenCover)
    if (showPaymentFlow) {
        val paymentCart = paymentCartSnapshot ?: cartState.paymentSnapshot()
        // 🔴 DINERO. La orden que esta venta ya tiene abierta de una parte anterior.
        // Sale del CARRITO —que sobrevive a que se recree la Activity, cosa que un
        // `remember` no hace— y el `remember` la CONGELA mientras el cobro está
        // abierto: al rotar, la composición es nueva y vuelve a leer del ViewModel.
        //
        // Congelarla hace LOCAL un invariante que si no es global: hoy funciona
        // porque toda escritura al valor va acompañada de una transición de
        // `showPaymentFlow`, pero `releaseChargingOrder()` es público y
        // `TableOrderScreen` ya monta su propio `PaymentFlowScreen`. El día que
        // otra pantalla lo toque con el cobro abierto, esto lo absorbe en vez de
        // reiniciar el flujo encima del recibo.
        val resumeOrderId = remember(showPaymentFlow) { cartViewModel.chargingAgainstOrderId }
        // 🔴 DINERO. El cliente del cobro se CONGELA igual que la orden de arriba, y
        // por una razón que ya costó un P0: `preselectedCustomerId` es LLAVE del
        // `LaunchedEffect` de `PaymentFlowScreen`. Al cerrarse la venta se suelta al
        // cliente (`aplicarCobroConfirmado`), el flujo emite, esta pantalla recompone,
        // la llave pasa de "cust_x" a null y el efecto **se relanza**: `startPaymentFlow`
        // borra recibo, `paymentId` y `createdOrderId`, y deja al cajero frente a un
        // cobro armado por el importe completo ENCIMA del recibo — con la orden en
        // null, tocar un método creaba una SEGUNDA orden. Doble cobro.
        //
        // Congelarlo lo cierra de raíz: mientras el cobro está abierto el valor no se
        // mueve. La llave es `showPaymentFlow`, así que al recrearse la Activity la
        // composición es nueva y lo RELEE del carrito — conserva intacta la
        // supervivencia a la rotación, que es el punto de todo este trabajo.
        //
        // El encabezado del carrito NO usa esto: sigue leyendo el flujo vivo, así que
        // vuelve a "Agregar cliente" en cuanto la venta cierra.
        //
        // 🔴🔴 NO LO "SIMPLIFIQUES" A LEER EL FLUJO VIVO. Esto NO TIENE TEST — vive en
        // un Composable y lo único que lo protege es este comentario. Si alguien
        // cambia esta línea por `selectedCustomer` (el flujo), **nada truena**: la
        // suite pasa verde, el APK compila, y el bug vuelve entero — recibo
        // irrecuperable y un cobro cobrable encima de una venta ya pagada. Se descubre
        // en el mostrador, con un cliente enfrente.
        val clienteDelCobro = remember(showPaymentFlow) { cartViewModel.selectedCustomer.value }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
            )
            PaymentFlowScreen(
                cartState = paymentCart,
                // El cliente del encabezado del carrito se lleva al cobro: la
                // orden nace con `customerId` y la pantalla de recibo ya lo
                // muestra puesto en vez de pedirlo de nuevo.
                //
                // 🔴 Éste es el id que TERMINA EN LA ORDEN, y sale del valor
                // CONGELADO de arriba (`clienteDelCobro`), no del flujo vivo:
                // es llave de un `LaunchedEffect`, así que moverlo a media venta
                // reinicia el cobro encima del recibo. Ver el comentario de
                // `clienteDelCobro`.
                preselectedCustomerId = clienteDelCobro?.id,
                preselectedCustomerName = clienteDelCobro?.name,
                onPaymentCommitted = { completion ->
                    // 🔴 DINERO. Quedó saldo ⇒ la venta SIGUE VIVA contra la MISMA
                    // orden, y la parte que falta se cobra ahí. Antes esto se
                    // perdía: el carrito se sustituía por "Saldo pendiente" y la
                    // parte 2 nacía sin orden, partiendo la venta en dos y dejando
                    // la primera PARTIAL para siempre — con el stock sin descontar.
                    //
                    // En mesa NO: ese camino ya re-siembra su orden desde la sesión
                    // (`TableSession` en PAYING) y funciona; es el modelo a igualar.
                    val ordenQueSigueViva = completion.orderId
                        ?.takeIf { !isTablePaying && completion.remainingBalanceCents > 0 }
                    // 🔴 DINERO. Las tres ramas —y la decisión de si la venta
                    // terminó, que es la que suelta al cliente— viven en el
                    // `CartViewModel`: es lógica de negocio, y aquí arriba no había
                    // forma de probarla. Ver `aplicarCobroConfirmado`.
                    val cobro = cartViewModel.aplicarCobroConfirmado(
                        splitType = completion.splitType,
                        paidItemIds = completion.paidItemIds,
                        remainingBalanceCents = completion.remainingBalanceCents,
                    )
                    when (cobro.rama) {
                        CartViewModel.RamaCobro.RENGLONES_PAGADOS -> Unit
                        CartViewModel.RamaCobro.QUEDA_SALDO -> {
                            // TABLE_SERVICE (PAYING): the session's charge target
                            // becomes the remainder so a re-entry never re-seeds
                            // the original (already partially paid) total.
                            if (tableSessionActive?.mode == com.avoqado.pos.tables.data.TableSession.Mode.PAYING) {
                                tablesViewModel.updateTableSessionRemaining(completion.remainingBalanceCents)
                            }
                        }
                        CartViewModel.RamaCobro.PAGO_COMPLETO -> {
                            // Full payment — grant any captured membership credits.
                            // Consumir = entregar y limpiar en el mismo acto, para
                            // que un segundo commit no las otorgue por duplicado.
                            // Va DESPUÉS de tocar el carrito sin problema: el grant
                            // está congelado en el ViewModel con su propio cliente.
                            cartViewModel.consumePendingPackGrant()?.let {
                                creditsViewModel.grantPacks(it.packIds, it.customerId)
                            }
                            // TABLE_SERVICE (PAYING): the table's order was just
                            // fully paid through the normal flow — release it.
                            if (tableSessionActive?.mode == com.avoqado.pos.tables.data.TableSession.Mode.PAYING) {
                                tablesViewModel.finishTableAfterPayment()
                            }
                        }
                    }
                    // 🔴 Va DESPUÉS del `when`, nunca antes: las tres ramas tocan el
                    // carrito (`clearCart` incluido, que borra el vínculo a propósito)
                    // y el marcado tiene que fotografiar el carrito ya en su forma
                    // final. Con `null` cierra la venta: el cobro completo no deja
                    // nada vivo y el siguiente arranca su propia orden.
                    cartViewModel.markPendingSplitOrder(ordenQueSigueViva)
                    // Referral is real now: capture on actual payment success
                    // (a cancelled payment no longer leaves a dangling referral).
                    //
                    // 🔴 Se le pasa el referido CONGELADO antes de tocar el carrito.
                    // Esto corre en un `launch` asíncrono, y para cuando el cuerpo
                    // corre, cerrar la venta ya borró la validación, el código y el
                    // staff — la captura salía sin llamar al server y el que refirió
                    // no recibía su crédito, sin que nadie se enterara.
                    //
                    // 🔴 Congelar sólo el `customerId` NO alcanzaba y era un adorno:
                    // `setSelectedCustomer(null)` llama a `clearReferral()`, que deja
                    // la validación en `Idle`, y la función salía en su PRIMERA línea
                    // sin llegar nunca a leer ese id. El problema no era el cliente,
                    // era la validación. Ver `CartViewModel.ReferralPendiente`.
                    // 🔴 El `orderId` VA, en las tres ramas — antes iba `null` y eso
                    // apagaba dos candados del server a la vez:
                    //
                    // 1. El índice único parcial de `Referral` está declarado sobre
                    //    `qualifyingOrderId` ("an Order can qualify at most ONE active
                    //    Referral"). Con `null` el índice NO aplica y `captureReferral`
                    //    hace un `create()` pelón. Y sí hay captura doble: el split POR
                    //    PRODUCTO no pasa por `clearCart()`, así que la validación sigue
                    //    `Valid` y cada parte vuelve a capturar — 3 productos cobrados
                    //    uno por uno son 3 intentos por UNA venta.
                    // 2. Sin `qualifyingOrderId` la fila PENDING **nunca puede pasar a
                    //    QUALIFIED** por el camino de la orden pagada: quedaría inerte
                    //    aunque se creara.
                    //
                    // En las tres ramas es la orden de ESTA venta; no hay ambigüedad.
                    //
                    // ⚠️ OJO — esto NO basta para que el referido funcione hoy, y el
                    // dato es fácil de celebrar de más: **el server RECHAZA toda captura
                    // post-cobro con `EXISTING_CUSTOMER`.** Su regla 4 exige que el
                    // cliente no tenga ninguna orden previa en el venue, y esto corre en
                    // `onPaymentCommitted`, o sea DESPUÉS de que la orden de esta venta
                    // ya existe con ese cliente. Es una contradicción preexistente entre
                    // tres documentos: el KDoc de la función dice "right before the
                    // payment is sent", el del server dice "before payment", y este
                    // sitio dispara en el éxito a propósito, para que un cobro cancelado
                    // no deje un referido colgado. Moverla o relajar la regla es
                    // decisión de producto y está anotada aparte. Congelar el referido y
                    // mandar el `orderId` son **prerequisito** de cualquier solución —
                    // no son la solución.
                    checkoutScope.launch {
                        cartViewModel.captureReferralOnPayment(
                            orderId = completion.orderId,
                            pendiente = cobro.referralPendiente,
                        )
                    }
                    // Upsell: el momento terminó en venta PAGADA. Sólo aquí, en el
                    // éxito real — una impresión sin esto aporta $0 al reporte, y
                    // marcarla antes contaría como venta algo que se canceló.
                    completion.orderId?.let { upsellViewModel.onOrderPaid(it) }
                },
                onDone = {
                    showPaymentFlow = false
                    paymentCartSnapshot = null
                    // Se congela al abrir el cobro y se suelta al cerrarlo: el
                    // próximo lo vuelve a resolver contra el carrito de entonces,
                    // nunca contra el de la venta que acaba de cerrarse.
                    cartViewModel.releaseChargingOrder()
                    pendingSplitConfig = SplitConfig()
                    // El "Gracias" del cliente vuelve al logo del negocio (o al
                    // carrito si quedó saldo) en cuanto se cierra el pago.
                    cartViewModel.refreshCustomerDisplay()
                },
                onCancel = {
                    showPaymentFlow = false
                    paymentCartSnapshot = null
                    // Se congela al abrir el cobro y se suelta al cerrarlo: el
                    // próximo lo vuelve a resolver contra el carrito de entonces,
                    // nunca contra el de la venta que acaba de cerrarse.
                    cartViewModel.releaseChargingOrder()
                    pendingSplitConfig = SplitConfig()
                    // El cobro se canceló: la impresión se queda SIN convertir (aporta
                    // $0 y no cuenta para el promedio). Lo aceptado sigue en el carrito,
                    // que es lo correcto — el cliente lo pidió; lo que no ocurrió es la venta.
                    upsellViewModel.cancelPendingConversion()
                    cartViewModel.refreshCustomerDisplay()
                },
                // Se resolvió un cobro que había quedado de OTRA venta: el flujo se cierra y
                // el cajero vuelve a su carrito intacto. El aviso lo pinta ESTA pantalla,
                // que es la que se queda — antes lo mostraba la que se iba, y se desvanecía
                // mientras ya le pedían la calificación de la venta siguiente.
                onPreviousChargeResolved = { message ->
                    showPaymentFlow = false
                    paymentCartSnapshot = null
                    // Se congela al abrir el cobro y se suelta al cerrarlo: el
                    // próximo lo vuelve a resolver contra el carrito de entonces,
                    // nunca contra el de la venta que acaba de cerrarse.
                    cartViewModel.releaseChargingOrder()
                    pendingSplitConfig = SplitConfig()
                    upsellViewModel.cancelPendingConversion()
                    cartViewModel.refreshCustomerDisplay()
                    previousChargeNotice = message
                },
                splitConfig = pendingSplitConfig,
                resumeOrderId = resumeOrderId,
            )
        }
    }

    // 🔴 DINERO. Se agregó mercancía a una venta que quedó a medio cobrar: NO se
    // bloquea (un POS jamás impide vender), pero tampoco se cobra en silencio
    // contra la orden vieja. Va DESPUÉS del overlay de pago a propósito — lo que
    // se compone después se dibuja encima, y este aviso sale justo al abrirse el
    // cobro; detrás del overlay no lo vería nadie.
    val splitWarning by cartViewModel.splitWarning.collectAsState()
    splitWarning?.let { aviso ->
        AvoqadoWarningToast(
            message = "Venta anterior a medio cobrar",
            subtitle = aviso,
            // Más que el default de 2.6 s: esto tiene consecuencia en el dinero
            // (una orden queda abierta) y hay que darle tiempo de leerlo.
            durationMs = 6000L,
            onDismiss = cartViewModel::consumeSplitWarning,
        )
    }

    // ── Upsell "¿Algo más?" — la tira del cajero ──────────────────────────────
    // Se pinta ENCIMA, anclada abajo: el cobro es el acto principal y esto no
    // puede secuestrar la pantalla. Sale sola cuando el momento se resuelve.
    upsellMoment?.let { moment ->
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            com.avoqado.pos.pos.presentation.upsell.UpsellCashierStrip(
                cards = moment.cards,
                selected = moment.selected,
                onToggle = { upsellViewModel.toggle(it) },
                onSkip = { resolveUpsell(accept = false) },
                onConfirm = { resolveUpsell(accept = true) },
            )
        }
    }

    // El cliente resuelve desde SU pantalla con exactamente las mismas acciones.
    // Registrarlo una vez y no por momento: son las mismas dos funciones siempre.
    LaunchedEffect(Unit) {
        upsellViewModel.bindCustomerActions(
            onConfirm = { resolveUpsell(accept = true) },
            onDismiss = { resolveUpsell(accept = false) },
        )
    }

    // Split payment sheet
    if (showSplitPayment && !cartState.isEmpty) {
        SplitPaymentSheet(
            totalCents = cartState.totalCents,
            items = cartState.items,
            // Con UN SOLO renglón, "Por producto" no reparte nada: seleccionarlo es
            // cobrar todo (da igual la cantidad — una línea es una sola casilla).
            // Se oculta, pero dividir SÍ se ofrece: "Partes iguales" y "Monto
            // personalizado" funcionan con un artículo. Espejo de iOS
            // (`allowPerProduct`), que exigía >1 renglón para ofrecer dividir
            // siquiera — corregido el mismo día.
            allowByProduct = cartState.items.none { it.locked } && cartState.items.size > 1,
            onDismiss = { showSplitPayment = false },
            onConfirm = { splitConfig ->
                showSplitPayment = false
                pendingSplitConfig = splitConfig
                paymentCartSnapshot = cartState.paymentSnapshot()
                // Misma resolución que en `proceedToPayment`: dividir la cuenta
                // es otra puerta al MISMO cobro, y sin esto la parte 2 hecha
                // desde aquí volvería a nacer sin orden.
                if (isTablePaying) cartViewModel.releaseChargingOrder() else cartViewModel.resolvePendingSplitOrderForCharge()
                showPaymentFlow = true
            },
        )
    }

    // Save cart success toast
    if (showSavedSnackbar) {
        AvoqadoSuccessToast(
            message = "¡Carrito guardado!",
            onDismiss = { showSavedSnackbar = false },
        )
    }

    if (showPayLaterSuccessToast) {
        AvoqadoSuccessToast(
            message = "¡Venta enviada a pagar después!",
            onDismiss = { showPayLaterSuccessToast = false },
        )
    }

    // Customers sheet (full screen overlay matching iOS)
    if (showCustomersSheet) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() },
                        onClick = {},
                    ),
            )
            if (showCreateCustomer) {
                CreateCustomerView(
                    viewModel = customersViewModel,
                    initialPhone = createCustomerSearchText.takeIf { it.all { c -> c.isDigit() || c == '+' } },
                    initialName = createCustomerSearchText.takeIf { !it.all { c -> c.isDigit() || c == '+' } },
                    onCustomerCreated = { customer ->
                        val fromPayLater = customerSelectionContext == CustomerSelectionContext.PAY_LATER
                        cartViewModel.setSelectedCustomer(customer.id, customer.fullName)
                        showCreateCustomer = false
                        showCustomersSheet = false
                        if (fromPayLater) {
                            selectedTab = InputTab.SHORTCUTS
                            reopenPayLaterToken += 1
                        }
                        customerSelectionContext = CustomerSelectionContext.GENERAL
                    },
                    onBack = { showCreateCustomer = false },
                )
            } else {
                CustomersView(
                    viewModel = customersViewModel,
                    onCustomerSelected = { customer ->
                        val fromPayLater = customerSelectionContext == CustomerSelectionContext.PAY_LATER
                        cartViewModel.setSelectedCustomer(customer.id, customer.fullName)
                        showCustomersSheet = false
                        showCreateCustomer = false
                        if (fromPayLater) {
                            selectedTab = InputTab.SHORTCUTS
                            reopenPayLaterToken += 1
                        }
                        customerSelectionContext = CustomerSelectionContext.GENERAL
                    },
                    onDismiss = {
                        showCustomersSheet = false
                        showCreateCustomer = false
                        customerSelectionContext = CustomerSelectionContext.GENERAL
                    },
                    onCreateCustomer = { searchText ->
                        createCustomerSearchText = searchText
                        showCreateCustomer = true
                    },
                    canCreateCustomer = roleManager?.canManageCustomers ?: true,
                )
            }
        }
    }

    payLaterError?.let { message ->
        AvoqadoDialog(
            title = "No se pudo diferir el pago",
            description = message,
            onDismiss = { payLaterError = null },
            actionButton = {
                PrimaryButton(
                    text = "Entendido",
                    onClick = { payLaterError = null },
                    fullWidth = true,
                )
            },
        ) {}
    }

    // Note dialog for keypad custom amount
    if (showClearCartConfirm) {
        AvoqadoDialog(
            title = "¿Vaciar carrito?",
            description = if (cartState.reservationId != null)
                "Este carrito tiene una clase con inscripción activa. Al vaciarlo, la inscripción se mantiene SIN cobro — cóbrala después o cancélala desde el calendario."
            else "Se quitarán todos los artículos del carrito.",
            onDismiss = { showClearCartConfirm = false },
            actionButton = {
                PrimaryButton(text = "Vaciar", onClick = {
                    // 🔴 Vaciar a mano ES terminar la venta: si sólo se vacía el
                    // carrito, el cliente se queda en el encabezado y la venta
                    // siguiente se le atribuye a él.
                    cartViewModel.finalizarVenta()
                    showClearCartConfirm = false
                })
            },
            content = {},
        )
    }

    if (showPackNoSplitAlert) {
        AvoqadoDialog(
            title = "Membresía en el carrito",
            description = "Las membresías se cobran en un solo pago. Cobra la membresía por separado o quítala del carrito para dividir el pago.",
            onDismiss = { showPackNoSplitAlert = false },
            actionButton = {
                PrimaryButton(
                    text = "Entendido",
                    onClick = { showPackNoSplitAlert = false },
                )
            },
            content = {},
        )
    }

    if (showPackCustomerRequired) {
        AvoqadoDialog(
            title = "Asigna un cliente",
            description = "Este cobro incluye una membresía. Asigna un cliente al carrito para poder otorgarle los créditos.",
            onDismiss = { showPackCustomerRequired = false },
            actionButton = {
                PrimaryButton(
                    text = "Entendido",
                    onClick = { showPackCustomerRequired = false },
                )
            },
            content = {},
        )
    }

    if (showNoteDialog) {
        var noteInput by remember { mutableStateOf(currentNote) }
        AvoqadoDialog(
            title = "Agregar nota",
            description = "Escribe una nota para el importe personalizado",
            onDismiss = { showNoteDialog = false },
            actionButton = {
                PrimaryButton(
                    text = "Guardar",
                    onClick = {
                        currentNote = noteInput
                        showNoteDialog = false
                    },
                    fullWidth = true,
                )
            },
        ) {
            AvoqadoPillTextField(
                value = noteInput,
                onValueChange = { noteInput = it },
                placeholder = "Nota para el importe",
            )
            if (currentNote.isNotBlank()) {
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.md))
                Text(
                    text = "Quitar nota",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable {
                        currentNote = ""
                        showNoteDialog = false
                    },
                )
            }
        }
    }
}

private fun CartState.paymentSnapshot(): CartState = copy(
    items = items.map { item ->
        item.copy(selectedModifiers = item.selectedModifiers.toList())
    },
)

private fun handleProductTap(
    product: Product,
    cartViewModel: CartViewModel,
    onSelectForDetail: (Product) -> Unit,
    onSelectForWeight: (Product) -> Unit,
) {
    when {
        // Venta por peso MANDA sobre los modificadores (MVP): el tap SIEMPRE abre la captura de
        // peso, nunca el panel de modificadores, aunque el producto tenga grupos.
        product.soldByWeight -> onSelectForWeight(product)
        product.hasModifiers -> onSelectForDetail(product)
        else -> cartViewModel.addProduct(product)
    }
}

// MARK: - iPhone Cart Sheet (full screen, matching iOS fullScreenCover)

@Composable
private fun IPhoneCartSheet(
    cartState: com.avoqado.pos.pos.presentation.cart.CartState,
    onItemTap: (CartItem) -> Unit,
    onCharge: () -> Unit,
    onOrderTypeChange: (String) -> Unit = {},
    onClearCart: () -> Unit,
    onSaveCart: () -> Unit,
    onAddCustomAmount: () -> Unit,
    onRemoveItem: (String) -> Unit,
    onApplyTaxPercent: (Int?) -> Unit,
    /** Descuento de la cuenta completa. 🔴 SIN default, misma lección que `onSplitPayment`. */
    onDiscountTap: () -> Unit,
    staffName: String,
    onStaffTap: () -> Unit,
    /**
     * "Dividir cuenta" del menú de sección. 🔴 SIN default a propósito: cuando lo
     * tenía, esta hoja simplemente no lo pasaba y el renglón se veía en el menú
     * sin hacer NADA en pantallas de teléfono. Obligar a pasarlo hace que el
     * compilador cace el próximo olvido.
     */
    onSplitPayment: () -> Unit,
    customerName: String? = null,
    customerId: String? = null,
    onCustomerTap: () -> Unit = {},
    referralCode: String = "",
    referralUiState: com.avoqado.pos.referrals.presentation.ReferralCaptureUiState =
        com.avoqado.pos.referrals.presentation.ReferralCaptureUiState.Idle,
    customerSelectedForReferral: Boolean = customerName != null,
    onReferralCodeChange: (String) -> Unit = {},
    onValidateReferral: () -> Unit = {},
    onClearReferral: () -> Unit = {},
    onForceOverrideReferral: () -> Unit = {},
    referralPlanAllowed: Boolean = true,
    onDismiss: () -> Unit,
    primaryActionLabel: String? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            ),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header: "Cerrar" | "Carrito" | invisible spacer (matching iOS)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "Cerrar",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "Carrito",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.weight(1f))
                // Invisible spacer to balance "Cerrar" button
                Text(
                    text = "Cerrar",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Transparent,
                )
            }

            HorizontalDivider()

            // Reuse CartPanelView
            CartPanelView(
                cartState = cartState,
                onItemTap = onItemTap,
                onCharge = onCharge,
                onOrderTypeChange = onOrderTypeChange,
                onClearCart = onClearCart,
                onSaveCart = onSaveCart,
                onAddCustomAmount = onAddCustomAmount,
                onRemoveItem = onRemoveItem,
                onApplyTaxPercent = onApplyTaxPercent,
                onDiscountTap = onDiscountTap,
                customerName = customerName,
                customerId = customerId,
                onCustomerTap = onCustomerTap,
                staffName = staffName,
                onStaffTap = onStaffTap,
                onSplitPayment = onSplitPayment,
                referralCode = referralCode,
                referralUiState = referralUiState,
                customerSelectedForReferral = customerSelectedForReferral,
                onReferralCodeChange = onReferralCodeChange,
                onValidateReferral = onValidateReferral,
                onClearReferral = onClearReferral,
                onForceOverrideReferral = onForceOverrideReferral,
                referralPlanAllowed = referralPlanAllowed,
                primaryActionLabel = primaryActionLabel,
            )
        }
    }
}

// MARK: - Cart Item Detail Panel (matching iOS: side panel on tablet, sheet on phone)

@Composable
private fun CartItemDetailPanel(
    item: CartItem,
    isTablet: Boolean,
    onUpdateQuantity: (Int) -> Unit,
    onUpdateNote: (String?) -> Unit,
    onToggleCortesia: (Boolean, String?) -> Unit,
    onUpdatePriceAdjustment: (Int?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (isTablet) {
        // Side panel overlay from right (matching iOS)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.3f))
                .clickable(onClick = onDismiss),
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(400.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(enabled = false, onClick = {}),
            ) {
                CartItemDetailContent(
                    item = item,
                    onUpdateQuantity = onUpdateQuantity,
                    onUpdateNote = onUpdateNote,
                    onToggleCortesia = onToggleCortesia,
                    onUpdatePriceAdjustment = onUpdatePriceAdjustment,
                    onDelete = onDelete,
                    onDismiss = onDismiss,
                )
            }
        }
    } else {
        // Phone: full-screen overlay (matching iOS sheet)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                ),
        ) {
            CartItemDetailContent(
                item = item,
                onUpdateQuantity = onUpdateQuantity,
                onUpdateNote = onUpdateNote,
                onToggleCortesia = onToggleCortesia,
                onUpdatePriceAdjustment = onUpdatePriceAdjustment,
                onDelete = onDelete,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun CartItemDetailContent(
    item: CartItem,
    onUpdateQuantity: (Int) -> Unit,
    onUpdateNote: (String?) -> Unit,
    onToggleCortesia: (Boolean, String?) -> Unit,
    onUpdatePriceAdjustment: (Int?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Se reinicia al cambiar de articulo: sin la llave, tocar otra linea del
    // carrito con la nota abierta editaria la nota del articulo equivocado.
    var showNote by remember(item.id) { mutableStateOf(false) }

    if (showNote) {
        NoteSubView(
            currentNote = item.itemNote ?: "",
            onSave = { nueva ->
                onUpdateNote(nueva.ifBlank { null })
                showNote = false
            },
            onClear = {
                onUpdateNote(null)
                showNote = false
            },
            onBack = { showNote = false },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.xl),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = formatMoney(item.totalPrice / 100.0),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (item.isCortesia) Color(0xFF34C759)
                    else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.titleLarge,
                )
                // Venta por peso: subtítulo "0.435 kg × $420.00/kg" en vez del resumen de modificadores.
                item.weightSummary?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                item.modifiersSummary?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Close button
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Cerrar",
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        HorizontalDivider()

        // Quantity selector — oculto en líneas por peso (D9: cada pesada es 1 línea con cantidad 1)
        // y en líneas de promoción (la cantidad es parte del combo: un 2x1 ES
        // una línea de 2, y `quantity ≠ 1` junto a `promotionRef` lo rechaza el
        // server; 3 combos son 3 instancias).
        if (item.weightKg == null && !item.isPromotionLine) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Cantidad",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(AvoqadoTheme.cornerRadius.md),
                    )
                    .padding(horizontal = AvoqadoTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable(enabled = item.quantity > 1) {
                            onUpdateQuantity(item.quantity - 1)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Remove,
                        contentDescription = "Menos",
                        modifier = Modifier.size(18.dp),
                        tint = if (item.quantity > 1) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
                Text(
                    text = "${item.quantity}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.width(40.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { onUpdateQuantity(item.quantity + 1) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = "Mas",
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }

        HorizontalDivider()
        } // fin del bloque de cantidad (oculto para líneas por peso)

        // Note section
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showNote = true }
                .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Nota del artículo",
                    style = MaterialTheme.typography.bodyMedium,
                )
                item.itemNote?.let { note ->
                    if (note.isNotEmpty()) {
                        Text(
                            text = note,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))

        // Cortesia toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (item.isCortesia) {
                        onToggleCortesia(false, null)
                    } else {
                        onToggleCortesia(true, "Cortesia del administrador")
                    }
                }
                .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.CardGiftcard,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Cortesia",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (item.isCortesia) {
                    Text(
                        text = item.cortesiaReason ?: "Activado",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF34C759),
                    )
                }
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Delete button at bottom
        HorizontalDivider()
        TextButton(
            onClick = onDelete,
            modifier = Modifier
                .fillMaxWidth()
                .padding(AvoqadoTheme.spacing.xl),
        ) {
            Text(
                text = "Eliminar artículo",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// MARK: - Search Bar (matching iOS: pill search + refresh + barcode buttons)

@Composable
private fun SearchBarView(
    isLoading: Boolean,
    onSearchTap: () -> Unit,
    onRefresh: () -> Unit,
    onBarcodeScan: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AvoqadoTheme.spacing.xl)
            .padding(top = AvoqadoTheme.spacing.xxl, bottom = AvoqadoTheme.spacing.xxl),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.md),
    ) {
        // Search field (tappable pill)
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onSearchTap)
                .padding(horizontal = AvoqadoTheme.spacing.md, vertical = AvoqadoTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(AvoqadoTheme.spacing.sm))
            Text(
                text = "Buscar",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Refresh button
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onRefresh),
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = "Actualizar productos",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        // Barcode scanner button
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(AvoqadoTheme.cornerRadius.lg))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onBarcodeScan),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.QrCodeScanner,
                contentDescription = "Escanear código de barras",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// MARK: - Tab Selector (matching iOS: underline style tabs)

@Composable
private fun TabSelectorView(
    tabs: List<InputTab>,
    selectedTab: InputTab,
    onTabSelected: (InputTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = AvoqadoTheme.spacing.xl)
            .padding(bottom = AvoqadoTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(AvoqadoTheme.spacing.lg),
    ) {
        tabs.forEach { tab ->
            val isSelected = selectedTab == tab
            Column(
                modifier = Modifier
                    .width(IntrinsicSize.Max)
                    .clickable { onTabSelected(tab) },
            ) {
                Text(
                    text = tab.label,
                    style = if (isSelected) {
                        MaterialTheme.typography.titleSmall
                    } else {
                        MaterialTheme.typography.bodyMedium
                    },
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    softWrap = false,
                )
                Spacer(modifier = Modifier.height(AvoqadoTheme.spacing.sm))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                Color.Transparent
                            },
                        ),
                )
            }
        }
    }
}

// MARK: - iPhone Cart Bar (black bar at bottom)

@Composable
private fun IPhoneCartBar(
    itemCount: Int,
    total: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.inverseSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = AvoqadoTheme.spacing.xl, vertical = AvoqadoTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Cart badge
        Text(
            text = "$itemCount",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.inverseSurface,
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.error,
                    RoundedCornerShape(50),
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = "Ver carrito",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = total,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}
