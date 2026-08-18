package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.pos.data.ActiveCartState
import com.avoqado.pos.pos.data.ClassCheckoutSeed
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import com.avoqado.pos.pos.data.model.SavedCart
import com.avoqado.pos.pos.data.model.SavedCartItem
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 🔴 DINERO — La VIDA del id de orden entre las partes de un split de mostrador.
 *
 * El carrito es el dueño de la venta, así que el vínculo con la orden a medio
 * cobrar vive aquí y muere con el carrito: `clearCart()` lo borra, y como TODO
 * inicio de venta nueva pasa por `clearCart()` (cobro completo, guardar
 * carrito, pagar después, cambio de local, vaciar), el id no puede sobrevivir
 * a la venta que lo creó ni siquiera si mañana alguien agrega otro camino.
 *
 * La otra mitad: el vínculo sólo vale si el carrito SIGUE siendo la
 * continuación de esa venta. Mercancía nueva no está en la orden vieja, así que
 * cobrarla contra ella la dejaría descuadrada — se rompe el vínculo y se avisa.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CartSplitContinuidadTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
    private val savedCartsRepository = mockk<SavedCartsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val staffRepository = mockk<StaffRepository>(relaxed = true)

    private val refresco = Product(id = "prod-refresco", name = "Refresco", priceValue = 30.0)

    @Before
    fun setup() {
        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { savedCartsRepository.savedCarts } returns MutableStateFlow<List<SavedCart>>(emptyList())
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        every { secureStorage.venueId } returns "venue-1"
        coEvery { staffRepository.getActiveStaff() } returns Result.success(emptyList())
    }

    private fun createViewModel(): CartViewModel = CartViewModel(
        productsRepository = productsRepository,
        discountsRepository = discountsRepository,
        savedCartsRepository = savedCartsRepository,
        authRepository = authRepository,
        secureStorage = secureStorage,
        activeCartState = mockk<ActiveCartState>(relaxed = true),
        orderRepository = mockk<OrderRepository>(relaxed = true),
        staffRepository = staffRepository,
        classCheckoutSeed = mockk<ClassCheckoutSeed>(relaxed = true).also { every { it.consume() } returns null },
        validateReferralUseCase = mockk(relaxed = true),
        captureReferralUseCase = mockk(relaxed = true),
        planManager = PlanManager(secureStorage),
        tableSession = com.avoqado.pos.tables.data.TableSession(),
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
        areaTicketRepository = mockk(relaxed = true),
    )

    /** Deja el carrito como queda la parte 2 de un split por importe. */
    private fun CartViewModel.sembrarSaldoPendiente(orderId: String, cents: Int = 5000) {
        clearCart()
        addCustomAmount(name = "Saldo pendiente", amountCents = cents)
        markPendingSplitOrder(orderId)
    }

    // ── Sin split: nada que reusar ──────────────────────────────────────────────

    @Test
    fun `sin split pendiente no hay orden que reusar ni aviso`() = runTest {
        val vm = createViewModel()

        assertNull(vm.resolvePendingSplitOrderForCharge())
        assertNull(vm.splitWarning.value)
    }

    // ── La parte 2 reusa la orden ───────────────────────────────────────────────

    @Test
    fun `con saldo pendiente se reusa la orden de la parte 1`() = runTest {
        val vm = createViewModel()
        vm.sembrarSaldoPendiente("order-parte-1")

        assertEquals("order-parte-1", vm.resolvePendingSplitOrderForCharge())
        assertNull(vm.splitWarning.value)
    }

    @Test
    fun `cancelar el cobro y volver a intentarlo sigue reusando la misma orden`() = runTest {
        val vm = createViewModel()
        vm.sembrarSaldoPendiente("order-parte-1")

        assertEquals("order-parte-1", vm.resolvePendingSplitOrderForCharge())
        assertEquals("order-parte-1", vm.resolvePendingSplitOrderForCharge())
    }

    // ── Cuándo se borra ────────────────────────────────────────────────────────

    @Test
    fun `clearCart borra el vinculo — la venta siguiente crea su propia orden`() = runTest {
        val vm = createViewModel()
        vm.sembrarSaldoPendiente("order-parte-1")

        vm.clearCart()

        assertNull(vm.resolvePendingSplitOrderForCharge())
    }

    @Test
    fun `marcar con null borra el vinculo`() = runTest {
        val vm = createViewModel()
        vm.sembrarSaldoPendiente("order-parte-1")

        vm.markPendingSplitOrder(null)

        assertNull(vm.resolvePendingSplitOrderForCharge())
    }

    @Test
    fun `un orderId en blanco no crea vinculo`() = runTest {
        val vm = createViewModel()
        vm.clearCart()
        vm.addCustomAmount(name = "Saldo pendiente", amountCents = 5000)

        vm.markPendingSplitOrder("   ")

        assertNull(vm.resolvePendingSplitOrderForCharge())
    }

    // ── BYPRODUCT: quitar los artículos ya cobrados NO rompe la continuidad ─────

    @Test
    fun `quitar del carrito los articulos ya cobrados conserva el vinculo`() = runTest {
        val vm = createViewModel()
        vm.addProduct(refresco)
        vm.addProduct(refresco.copy(id = "prod-agua", name = "Agua"))
        val pagado = vm.cartState.value.items.first().id
        vm.markPendingSplitOrder("order-parte-1")

        vm.removeItem(pagado)

        assertEquals("order-parte-1", vm.resolvePendingSplitOrderForCharge())
    }

    // ── Sobrevivir a que se recree la Activity (girar la tablet) ───────────────

    /**
     * El cobro abierto queda guardado EN EL VIEWMODEL y leerlo no lo consume.
     *
     * ⚠️ Alcance honesto: esto prueba **la mitad del ViewModel** del arreglo de
     * rotación, no la rotación. Un unit test no tiene Activity que recrear, así
     * que lo que aquí se fija es que el dato vive fuera de la pantalla y aguanta
     * lecturas repetidas — la otra mitad (que `CheckoutScreen` lo lea de aquí y
     * no de un `remember`) se verificó leyendo el código, y girar la tablet de
     * verdad queda para el QA en device.
     */
    @Test
    fun `el cobro abierto vive en el ViewModel y leerlo no lo consume`() = runTest {
        val vm = createViewModel()
        vm.sembrarSaldoPendiente("order-parte-1")

        vm.resolvePendingSplitOrderForCharge()

        assertEquals("order-parte-1", vm.chargingAgainstOrderId)
        assertEquals("order-parte-1", vm.chargingAgainstOrderId)
    }

    @Test
    fun `marcar el siguiente saldo NO mueve el cobro abierto — el recibo sigue en pantalla`() = runTest {
        val vm = createViewModel()
        vm.sembrarSaldoPendiente("order-parte-1")
        vm.resolvePendingSplitOrderForCharge()

        // Cobró la parte 2 y quedó saldo: el checkout re-marca mientras la
        // pantalla de recibo sigue abierta. Si esto moviera el valor congelado,
        // `PaymentFlowScreen` reiniciaría su LaunchedEffect y borraría el recibo.
        vm.clearCart()
        vm.addCustomAmount(name = "Saldo pendiente", amountCents = 1000)
        vm.markPendingSplitOrder("order-parte-1")

        assertEquals("order-parte-1", vm.chargingAgainstOrderId)
    }

    @Test
    fun `cerrar el flujo de cobro suelta la orden congelada`() = runTest {
        val vm = createViewModel()
        vm.sembrarSaldoPendiente("order-parte-1")
        vm.resolvePendingSplitOrderForCharge()

        vm.releaseChargingOrder()

        assertNull(vm.chargingAgainstOrderId)
    }

    /**
     * 🔴 `clearCart()` NO suelta el cobro abierto, y es a propósito: el checkout
     * lo llama DENTRO de `onPaymentCommitted`, con el recibo en pantalla. Si el
     * valor congelado cambiara ahí, `PaymentFlowScreen` reiniciaría su
     * `LaunchedEffect` encima del recibo. Lo que sí muere es el vínculo durable.
     */
    @Test
    fun `clearCart borra el vinculo pero NO suelta el cobro abierto`() = runTest {
        val vm = createViewModel()
        vm.sembrarSaldoPendiente("order-parte-1")
        vm.resolvePendingSplitOrderForCharge()

        vm.clearCart()

        assertEquals("order-parte-1", vm.chargingAgainstOrderId)
        assertNull(vm.resolvePendingSplitOrderForCharge())
    }

    @Test
    fun `un cobro sin continuidad no deja nada congelado`() = runTest {
        val vm = createViewModel()
        vm.sembrarSaldoPendiente("order-parte-1")
        vm.resolvePendingSplitOrderForCharge()
        vm.clearCart()

        // Venta nueva: resolver otra vez SIEMPRE reescribe el congelado, así que
        // no puede quedar puesta la orden vieja aunque `clearCart` no lo toque.
        vm.addProduct(refresco)
        vm.resolvePendingSplitOrderForCharge()

        assertNull(vm.chargingAgainstOrderId)
    }

    // ── Los dos caminos que reemplazan el carrito SIN pasar por clearCart ──────

    @Test
    fun `restaurar un carrito guardado borra el vinculo`() = runTest {
        val vm = createViewModel()
        vm.sembrarSaldoPendiente("order-parte-1")
        vm.resolvePendingSplitOrderForCharge()

        vm.restoreSavedCart(
            SavedCart(
                id = "saved-1",
                name = "Mesa 4",
                items = listOf(
                    SavedCartItem(
                        productId = "prod-refresco",
                        name = "Refresco",
                        unitPrice = 3000,
                        quantity = 1,
                    ),
                ),
            ),
        )

        assertNull(vm.resolvePendingSplitOrderForCharge())
        // 🔴 La aserción que de verdad prueba la limpieza: si el vínculo siguiera
        // vivo, los renglones restaurados (UUID nuevos) lo romperían y dejarían el
        // aviso ámbar puesto — un "venta anterior a medio cobrar" FALSO sobre una
        // venta que ya terminó. Sin ella el test pasa aunque nadie limpie nada,
        // porque el guard de renglones nuevos también devuelve null.
        assertNull(vm.splitWarning.value)
    }

    /**
     * El otro reemplazo directo de `_cartState`: el staff guardado ya no existe y
     * `fetchStaff` vacía el carrito. Sin limpiar el vínculo, ese carrito VACÍO
     * pasa el guard —cero renglones nuevos, importe que bajó— y queda apuntando a
     * la orden vieja.
     *
     * ⚠️ Alcance honesto: esto es **defensa en profundidad, no una fuga que se
     * esté dando**. Por la UI no se llega: no se puede abrir un cobro con el
     * carrito vacío (el botón "Cobrar" vive en la rama no-vacía de
     * `CartPanelView`, el `IPhoneCartBar` está detrás de `!isEmpty`, y la hoja de
     * dividir también), y en cuanto el cajero agrega el primer producto ese
     * renglón trae un UUID nuevo que rompe el vínculo. El test llama al resolver
     * directo sobre el carrito vacío, que es algo que la pantalla no hace: cuida
     * el invariante del ViewModel, no un camino del usuario.
     */
    @Test
    fun `si el staff guardado ya no existe, vaciar el carrito tambien borra el vinculo`() = runTest {
        coEvery { staffRepository.getActiveStaff() } returns Result.success(emptyList())
        val vm = createViewModel()
        vm.sembrarSaldoPendiente("order-parte-1")
        // Deja un staff seleccionado que la lista ya no contiene (se fue del local).
        vm.selectStaff("staff-fantasma", "Ana")

        vm.fetchStaff()
        advanceUntilIdle()

        assertTrue(vm.cartState.value.isEmpty)
        assertNull(vm.resolvePendingSplitOrderForCharge())
    }

    // ── Membresías capturadas al abrir el cobro ────────────────────────────────

    private val membresia = com.avoqado.pos.articles.data.model.CreditPack(
        id = "pack-10-clases",
        name = "Membresía 10 clases",
        price = 500.0,
    )

    /**
     * 🔴 DINERO. Mismo defecto que el vínculo de orden y a veinte líneas de él:
     * era un `remember` pelón, así que **girar la tablet** con el cobro abierto lo
     * volvía null, se cobraban los $500 y `grantPacks` no se llamaba nunca.
     * Guardado en el ViewModel, la captura sobrevive.
     */
    @Test
    fun `las membresias capturadas sobreviven a recrear la pantalla`() = runTest {
        val vm = createViewModel()
        vm.addCreditPack(membresia)

        vm.capturePendingPackGrant("cliente-1", vm.cartState.value)

        // Leerlo no lo consume: la pantalla recreada lo encuentra igual.
        assertEquals("cliente-1", vm.pendingPackGrant?.customerId)
        assertEquals(listOf("pack-10-clases"), vm.pendingPackGrant?.packIds)
    }

    @Test
    fun `consumir las membresias las entrega UNA vez`() = runTest {
        val vm = createViewModel()
        vm.addCreditPack(membresia)
        vm.capturePendingPackGrant("cliente-1", vm.cartState.value)

        val entregadas = vm.consumePendingPackGrant()

        assertEquals("cliente-1", entregadas?.customerId)
        assertEquals(listOf("pack-10-clases"), entregadas?.packIds)
        // Un segundo commit del mismo cobro no puede otorgarlas de nuevo.
        assertNull(vm.consumePendingPackGrant())
    }

    @Test
    fun `sin cliente no hay membresias que otorgar`() = runTest {
        val vm = createViewModel()
        vm.addCreditPack(membresia)

        vm.capturePendingPackGrant(null, vm.cartState.value)

        assertNull(vm.pendingPackGrant)
    }

    @Test
    fun `un carrito sin membresias no captura nada`() = runTest {
        val vm = createViewModel()
        vm.addProduct(refresco)

        vm.capturePendingPackGrant("cliente-1", vm.cartState.value)

        assertNull(vm.pendingPackGrant)
    }

    @Test
    fun `capturar de nuevo pisa lo de la venta anterior`() = runTest {
        val vm = createViewModel()
        vm.addCreditPack(membresia)
        vm.capturePendingPackGrant("cliente-1", vm.cartState.value)

        // Venta siguiente: sin membresías. No puede heredar las de la pasada.
        vm.clearCart()
        vm.addProduct(refresco)
        vm.capturePendingPackGrant("cliente-2", vm.cartState.value)

        assertNull(vm.pendingPackGrant)
    }

    // ── El caso feo: mercancía NUEVA a media split ─────────────────────────────

    @Test
    fun `agregar mercancia nueva rompe el vinculo y AVISA — nunca se cobra en silencio`() = runTest {
        val vm = createViewModel()
        vm.sembrarSaldoPendiente("order-parte-1")

        vm.addProduct(refresco)

        // Cobrar esto contra la orden vieja la dejaría cobrando de más por algo
        // que no tiene registrado: la venta arranca de cero, con su propia orden.
        assertNull(vm.resolvePendingSplitOrderForCharge())
        assertNotNull(vm.splitWarning.value)
        assertTrue(vm.splitWarning.value!!.contains("saldo"))

        vm.consumeSplitWarning()
        assertNull(vm.splitWarning.value)
    }

    @Test
    fun `subir la cantidad de un articulo que YA estaba tambien rompe el vinculo`() = runTest {
        val vm = createViewModel()
        // Parte 2 de un BYPRODUCT: en el carrito quedó el refresco sin cobrar.
        vm.addProduct(refresco)
        vm.markPendingSplitOrder("order-parte-1")

        // 🔴 `addProduct` FUSIONA en la línea que ya existe: mismo id, cantidad 2.
        // Mirar sólo los ids no lo vería, y cobraríamos dos refrescos contra una
        // orden que sólo tiene uno.
        vm.addProduct(refresco)

        assertEquals(2, vm.cartState.value.items.first().quantity)
        assertNull(vm.resolvePendingSplitOrderForCharge())
        assertNotNull(vm.splitWarning.value)
    }

    @Test
    fun `una vez roto, el vinculo NO revive aunque se quite el producto nuevo`() = runTest {
        val vm = createViewModel()
        vm.sembrarSaldoPendiente("order-parte-1")
        vm.addProduct(refresco)

        assertNull(vm.resolvePendingSplitOrderForCharge())

        val nuevo = vm.cartState.value.items.last().id
        vm.removeItem(nuevo)

        assertNull(vm.resolvePendingSplitOrderForCharge())
    }

    @Test
    fun `agregar y quitar ANTES de cobrar no rompe nada — el toque accidental se deshace`() = runTest {
        val vm = createViewModel()
        vm.sembrarSaldoPendiente("order-parte-1")

        vm.addProduct(refresco)
        val nuevo = vm.cartState.value.items.last().id
        vm.removeItem(nuevo)

        // El vínculo se valida contra el carrito REAL en el momento de cobrar,
        // no en cada toque: si el producto ya no está, la venta sigue siendo la
        // continuación de la anterior.
        assertEquals("order-parte-1", vm.resolvePendingSplitOrderForCharge())
        assertNull(vm.splitWarning.value)
    }

    // ── El CLIENTE de la venta ──────────────────────────────────────────────────
    //
    // Mismo filo que el id de orden, y por el mismo motivo: `clearCart()` corre A
    // MEDIA VENTA en un split, así que el cliente NO puede soltarse ahí. Se suelta
    // cuando la venta de verdad termina — `aplicarCobroConfirmado` (si cerró) o
    // `finalizarVenta()` (vaciar, guardar, cambio de local, vale, pagar después).
    //
    // Perder al cliente deja un dato FALTANTE; arrastrarlo a la venta siguiente deja
    // uno INCORRECTO: lealtad, CFDI e historial de quien no compró.

    @Test
    fun `clearCart NO suelta al cliente — es lo que lo mantiene vivo entre partes`() = runTest {
        val vm = createViewModel()
        vm.setSelectedCustomer("cust-ana", "Ana")
        vm.addProduct(refresco)

        vm.clearCart()

        // 🔴 El día que alguien "arregle" esto soltando al cliente dentro de
        // `clearCart()`, la parte 2 de TODO split de mostrador nace sin cliente.
        // Este test es el que lo caza.
        assertEquals("cust-ana", vm.selectedCustomer.value?.id)
    }

    @Test
    fun `parte 1 de un split deja saldo y CONSERVA al cliente`() = runTest {
        val vm = createViewModel()
        vm.setSelectedCustomer("cust-ana", "Ana")
        vm.addProduct(refresco)

        val cobro = vm.aplicarCobroConfirmado(
            splitType = null,
            paidItemIds = emptySet(),
            remainingBalanceCents = 5000,
        )

        assertEquals(CartViewModel.RamaCobro.QUEDA_SALDO, cobro.rama)
        assertFalse(cobro.ventaTerminada)
        assertEquals("cust-ana", vm.selectedCustomer.value?.id)
        assertEquals("Saldo pendiente", vm.cartState.value.items.single().name)
    }

    @Test
    fun `parte 2 cierra la venta y suelta al cliente`() = runTest {
        val vm = createViewModel()
        vm.setSelectedCustomer("cust-ana", "Ana")
        vm.addProduct(refresco)
        vm.aplicarCobroConfirmado(splitType = null, paidItemIds = emptySet(), remainingBalanceCents = 5000)

        val cierre = vm.aplicarCobroConfirmado(
            splitType = null,
            paidItemIds = emptySet(),
            remainingBalanceCents = 0,
        )

        assertEquals(CartViewModel.RamaCobro.PAGO_COMPLETO, cierre.rama)
        assertTrue(cierre.ventaTerminada)
        assertNull(vm.selectedCustomer.value)
    }

    @Test
    fun `un carrito que queda vacio CON saldo pendiente NO cierra la venta`() = runTest {
        val vm = createViewModel()
        vm.setSelectedCustomer("cust-ana", "Ana")
        vm.addProduct(refresco)
        vm.addProduct(refresco.copy(id = "prod-agua", name = "Agua"))
        val todos = vm.cartState.value.items.map { it.id }.toSet()

        // El otro brazo de la condición: quitar renglones vació el carrito, pero el
        // server dice que todavía se debe dinero. Es el caso del combo, que borra
        // varias líneas de golpe. Cerrar aquí soltaría al cliente a media venta.
        val cobro = vm.aplicarCobroConfirmado(
            splitType = "BYPRODUCT",
            paidItemIds = todos,
            remainingBalanceCents = 2500,
        )

        assertTrue(vm.cartState.value.isEmpty)
        assertFalse(cobro.ventaTerminada)
        assertEquals("cust-ana", vm.selectedCustomer.value?.id)
    }

    @Test
    fun `guardar el carrito suelta al cliente — se fue con el carrito guardado`() = runTest {
        val vm = createViewModel()
        vm.setSelectedCustomer("cust-ana", "Ana")
        vm.addProduct(refresco)

        assertTrue(vm.saveCurrentCart("Para llevar"))

        assertTrue(vm.cartState.value.isEmpty)
        assertNull(vm.selectedCustomer.value)
    }

    @Test
    fun `cobro POR PRODUCTO que vacia el carrito cierra la venta y suelta al cliente`() = runTest {
        val vm = createViewModel()
        vm.setSelectedCustomer("cust-ana", "Ana")
        vm.addProduct(refresco)
        vm.addProduct(refresco.copy(id = "prod-agua", name = "Agua"))
        val todos = vm.cartState.value.items.map { it.id }.toSet()

        val cobro = vm.aplicarCobroConfirmado(
            splitType = "BYPRODUCT",
            paidItemIds = todos,
            remainingBalanceCents = 0,
        )

        // 🔴 Cae en la rama de PRODUCTO, no en la de pago completo: el `when` evalúa
        // BYPRODUCT primero, aunque el saldo sea 0. Soltar al cliente sólo en "pago
        // completo" dejaba a Ana pegada en CUALQUIER venta cobrada por producto que
        // vaciara el carrito —incluida la primera y única— y la orden siguiente,
        // de otro cliente, nacía con el id de Ana.
        assertEquals(CartViewModel.RamaCobro.RENGLONES_PAGADOS, cobro.rama)
        assertTrue(cobro.ventaTerminada)
        assertTrue(vm.cartState.value.isEmpty)
        assertNull(vm.selectedCustomer.value)
    }

    @Test
    fun `cobro por producto que deja renglones NO suelta al cliente`() = runTest {
        val vm = createViewModel()
        vm.setSelectedCustomer("cust-ana", "Ana")
        vm.addProduct(refresco)
        vm.addProduct(refresco.copy(id = "prod-agua", name = "Agua"))
        val primero = vm.cartState.value.items.first().id

        val cobro = vm.aplicarCobroConfirmado(
            splitType = "BYPRODUCT",
            paidItemIds = setOf(primero),
            remainingBalanceCents = 3000,
        )

        assertEquals(CartViewModel.RamaCobro.RENGLONES_PAGADOS, cobro.rama)
        assertFalse(cobro.ventaTerminada)
        assertEquals("cust-ana", vm.selectedCustomer.value?.id)
    }

    @Test
    fun `finalizarVenta vacia el carrito Y suelta al cliente`() = runTest {
        val vm = createViewModel()
        vm.setSelectedCustomer("cust-ana", "Ana")
        vm.addProduct(refresco)

        vm.finalizarVenta()

        assertTrue(vm.cartState.value.isEmpty)
        assertNull(vm.selectedCustomer.value)
    }

    @Test
    fun `cambiar de local suelta al cliente — su id es de OTRO negocio`() = runTest {
        val cambiosDeLocal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        every { authRepository.venueSwitched } returns cambiosDeLocal
        val vm = createViewModel()
        vm.setSelectedCustomer("cust-ana", "Ana")

        cambiosDeLocal.emit(Unit)
        advanceUntilIdle()

        // 🔴 Lo más grave de la familia: sin esto la primera orden del local B nace
        // con un `customerId` del local A — un id de otro tenant dentro de la orden.
        assertNull(vm.selectedCustomer.value)
    }

    @Test
    fun `restaurar un carrito guardado recupera al cliente aunque no sepa su nombre`() = runTest {
        val vm = createViewModel()

        vm.restoreSavedCart(
            SavedCart(id = "saved-9", name = "Para llevar", items = emptyList(), attachedCustomerId = "cust-ana"),
        )

        assertEquals("cust-ana", vm.selectedCustomer.value?.id)
        // NO "Agregar cliente": el carrito guardado sólo persiste el id, así que el
        // encabezado cae a "Cliente" en vez de decir que no hay nadie mientras la
        // venta sí lo lleva.
        assertEquals("Cliente", vm.selectedCustomer.value?.name)
    }
}
