package com.avoqado.pos.pos

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicketModuleSettings
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.areatickets.data.AreaTicketSettingsData
import com.avoqado.pos.areatickets.data.AreaTicketTerminalCapabilities
import com.avoqado.pos.areatickets.data.ScaleIntegrationSettings
import com.avoqado.pos.areatickets.data.VariableWeightBarcodeSettings
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.PayloadCache
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
import com.avoqado.pos.pos.data.model.ProductCategory
import com.avoqado.pos.pos.data.model.SavedCart
import com.avoqado.pos.pos.data.model.ServiceCourseSnapshot
import com.avoqado.pos.pos.data.model.buildOrderItemRequests
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import com.avoqado.pos.referrals.domain.usecase.CaptureReferralUseCase
import com.avoqado.pos.referrals.domain.usecase.ValidateReferralUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * «Se cobra el precio que vio el cliente» (Task 7): cada línea que nació del catálogo lleva al pedido el precio de
 * catálogo que la caja mostró, la hora (`catalogAt`) del catálogo con que se agregó y su identidad (`externalId`
 * = `CartItem.id`). Lo que no viene del catálogo viaja como siempre. Gemela de iOS (Task 8), mismos nombres.
 */
class PrecioMostradoEnElPedidoTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val cat = "2026-10-17T15:00:00.000Z"
    private val cat2 = "2026-10-17T15:05:00.000Z"
    /** 73 h antes de [cat]: fuera de la ventana de 72 h. */
    private val catViejo = "2026-10-14T14:00:00.000Z"

    // MARK: - El pedido

    private fun pedido(vararg lineas: CartItem, isOfflineReplay: Boolean = false, createdAtLocal: Long? = null): JsonObject {
        val request = CreateOrderRequest(items = buildOrderItemRequests(lineas.toList()), subtotal = 0, total = 0, paymentMethod = "CASH")
        return Json.parseToJsonElement(
            OrderRepository.buildCreateOrderPayload(request, "staff", isOfflineReplay = isOfflineReplay, createdAtLocal = createdAtLocal),
        ).jsonObject
    }

    private fun JsonObject.linea(i: Int = 0): JsonObject = getValue("items").jsonArray[i].jsonObject

    @Test
    fun `una línea del catálogo manda su precio, su hora y su identidad`() {
        val l = pedido(CartItem(id = "e1", type = CartItemType.ProductItem("p1"), name = "Café", unitPrice = 4500, quantity = 2, catalogAt = cat)).linea()
        assertEquals(4500, l.getValue("displayedUnitPrice").jsonPrimitive.int)
        assertEquals(cat, l.getValue("catalogAt").jsonPrimitive.content)
        assertEquals("e1", l.getValue("externalId").jsonPrimitive.content)
        assertEquals(2, l.getValue("quantity").jsonPrimitive.int)
        // Como hoy: una línea de producto no manda `unitPrice` (el servidor usa su catálogo).
        assertFalse(l.containsKey("unitPrice"))
    }

    @Test
    fun `con precio manual viaja el del catálogo`() {
        val l = pedido(CartItem(id = "e1", type = CartItemType.ProductItem("p1"), name = "Café", unitPrice = 4500, priceAdjustment = 4000, catalogAt = cat)).linea()
        assertEquals(4500, l.getValue("displayedUnitPrice").jsonPrimitive.int)
    }

    @Test
    fun `venta por peso manda el precio por kilo`() {
        val l = pedido(CartItem(id = "w1", type = CartItemType.ProductItem("jamon"), name = "Jamón", unitPrice = 42000, weightKg = 0.435, catalogAt = cat)).linea()
        assertEquals(42000, l.getValue("displayedUnitPrice").jsonPrimitive.int)
        assertEquals(0.435, l.getValue("weightQuantity").jsonPrimitive.double, 0.0)
        assertEquals(cat, l.getValue("catalogAt").jsonPrimitive.content)
    }

    @Test
    fun `sin hora de catálogo no viaja nada nuevo`() {
        val p = pedido(
            // Promoción degradada (sin `promotionId`): viaja como producto suelto, con el precio estimado del combo.
            CartItem(id = "promo", type = CartItemType.ProductItem("cerveza"), name = "Cerveza", unitPrice = 2500, quantity = 2,
                promotionInstanceId = "inst-1", promotionName = "2x1"),
            CartItem(id = "libre", type = CartItemType.CustomAmount, name = "Importe libre", unitPrice = 1234),
            CartItem(id = "area-ticket:t1:l1", type = CartItemType.CustomAmount, name = "Pan", unitPrice = 3000,
                areaTicketId = "t1", areaTicketLineId = "l1", locked = true),
            // Un carrito guardado por una versión vieja (sin hora) con preparación v1: su identidad viaja como hoy.
            CartItem(id = "v1", type = CartItemType.ProductItem("p3"), name = "Sopa", unitPrice = 9000,
                serviceCourse = ServiceCourseSnapshot("immediate", "Inmediato", "IMMEDIATE", preparationVersion = 1)),
        )
        for (i in 0..3) {
            val l = p.linea(i)
            assertFalse("línea $i", l.containsKey("displayedUnitPrice"))
            assertFalse("línea $i", l.containsKey("catalogAt"))
        }
        assertFalse(p.linea(0).containsKey("externalId"))
        assertFalse(p.linea(1).containsKey("externalId"))
        assertFalse(p.linea(2).containsKey("externalId"))
        assertEquals("v1", p.linea(3).getValue("externalId").jsonPrimitive.content)
    }

    @Test
    fun `isOfflineReplay y createdAtLocal sólo cuando se piden`() {
        val linea = CartItem(id = "e1", type = CartItemType.ProductItem("p1"), name = "Café", unitPrice = 4500, catalogAt = cat)
        val enLinea = pedido(linea)
        assertFalse(enLinea.containsKey("isOfflineReplay"))
        assertFalse(enLinea.containsKey("createdAtLocal"))
        // La hora sola, sin `isOfflineReplay`, no viaja (contrato: sólo con la cola).
        val soloHora = pedido(linea, createdAtLocal = 1_760_000_000_000L)
        assertFalse(soloHora.containsKey("isOfflineReplay"))
        assertFalse(soloHora.containsKey("createdAtLocal"))
        val cola = pedido(linea, isOfflineReplay = true, createdAtLocal = 1_760_000_000_000L)
        assertTrue(cola.getValue("isOfflineReplay").jsonPrimitive.boolean)
        assertEquals(1_760_000_000_000L, cola.getValue("createdAtLocal").jsonPrimitive.long)
    }

    // MARK: - El carrito (fixture de CartViewModelWeightTest)

    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
    private val savedCartsRepository = mockk<SavedCartsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val staffRepository = mockk<StaffRepository>(relaxed = true)
    private val classCheckoutSeed = mockk<ClassCheckoutSeed>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)

    @Before
    fun setup() {
        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { savedCartsRepository.savedCarts } returns MutableStateFlow<List<SavedCart>>(emptyList())
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns MutableSharedFlow(extraBufferCapacity = 1)
        every { classCheckoutSeed.consume() } returns null
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.userId } returns "user-1"
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-99"
        coEvery { staffRepository.getActiveStaff() } returns Result.success(listOf(StaffMember(id = "staff-99", firstName = "Jose", lastName = "Tester")))
        coEvery { areaTicketRepository.settings() } returns AreaTicketSettingsData(
            venueId = "venue-1",
            areaTickets = AreaTicketModuleSettings(entitled = true, enabled = true),
            terminal = AreaTicketTerminalCapabilities(id = "terminal-1", name = "Caja"),
            scaleIntegration = ScaleIntegrationSettings(entitled = false, enabled = false),
            variableWeightBarcode = VariableWeightBarcodeSettings(entitled = false, enabled = false, prefix = "20"),
        )
    }

    private fun carrito(): CartViewModel = CartViewModel(
        productsRepository = productsRepository,
        discountsRepository = discountsRepository,
        savedCartsRepository = savedCartsRepository,
        authRepository = authRepository,
        secureStorage = secureStorage,
        activeCartState = mockk<ActiveCartState>(relaxed = true),
        orderRepository = mockk<OrderRepository>(relaxed = true),
        staffRepository = staffRepository,
        classCheckoutSeed = classCheckoutSeed,
        validateReferralUseCase = mockk<ValidateReferralUseCase>(relaxed = true),
        captureReferralUseCase = mockk<CaptureReferralUseCase>(relaxed = true),
        planManager = PlanManager(secureStorage),
        tableSession = com.avoqado.pos.tables.data.TableSession(),
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
        areaTicketRepository = areaTicketRepository,
        walletScanRepository = mockk(relaxed = true),
    )

    private val cafe45 = Product(id = "cafe", name = "Café", priceValue = 45.0, catalogAt = cat)

    @Test
    fun `otra unidad tras un refresco no hereda lo viejo`() {
        val vm = carrito()
        val cafe50 = cafe45.copy(priceValue = 50.0, catalogAt = cat2)
        vm.addProduct(cafe45)
        vm.addProduct(cafe50)
        vm.addProduct(cafe50)
        assertEquals(
            listOf(Triple(4500, cat, 1), Triple(5000, cat2, 2)),
            vm.cartState.value.items.map { Triple(it.unitPrice, it.catalogAt, it.quantity) },
        )
    }

    /**
     * Ajuste del controlador (17-oct): con el refresco cada 5 min, exigir la MISMA hora partiría «Café ×1, Café ×1» en
     * carrito, ticket y cocina. Mismo precio de catálogo ⇒ un renglón con la hora MÁS NUEVA: ese precio era real en la
     * lista más nueva (y la unidad nueva no hereda una fecha vieja que cruzó las 72 h).
     */
    @Test
    fun `mismo precio con otra fecha (73 h contra ahora) es un renglón con la hora más nueva`() {
        val vm = carrito()
        vm.addProduct(cafe45.copy(catalogAt = catViejo))
        vm.addProduct(cafe45)
        assertEquals(listOf(Triple(4500, cat, 2)), vm.cartState.value.items.map { Triple(it.unitPrice, it.catalogAt, it.quantity) })
        // Una unidad que llega con la hora vieja no le quita la nueva.
        vm.addProduct(cafe45.copy(catalogAt = catViejo))
        assertEquals(listOf(Triple(4500, cat, 3)), vm.cartState.value.items.map { Triple(it.unitPrice, it.catalogAt, it.quantity) })
    }

    @Test
    fun `al fusionar, una hora nula pierde contra la que sí hay`() {
        val sinHora = cafe45.copy(catalogAt = null)
        val vm = carrito()
        vm.addProduct(sinHora)
        vm.addProduct(cafe45)
        assertEquals(listOf(Triple(4500, cat, 2)), vm.cartState.value.items.map { Triple(it.unitPrice, it.catalogAt, it.quantity) })
        vm.addProduct(sinHora)
        assertEquals(listOf(Triple(4500, cat, 3)), vm.cartState.value.items.map { Triple(it.unitPrice, it.catalogAt, it.quantity) })
        val soloNulas = carrito()
        soloNulas.addProduct(sinHora)
        soloNulas.addProduct(sinHora)
        assertEquals(listOf(Triple(4500, null, 2)), soloNulas.cartState.value.items.map { Triple(it.unitPrice, it.catalogAt, it.quantity) })
    }

    /**
     * Regla de fusión de la hora (controlador, 17-oct; igual en iOS): una hora válida le gana a nula o ilegible; entre dos
     * válidas, la más nueva (como `Instant`); si ninguna es válida, se queda la que YA estaba. Nunca se inventa una hora
     * a partir de basura: una `catalogAt` ilegible haría que el servidor no reconozca el precio mostrado.
     */
    @Test
    fun `al fusionar, una hora ilegible nunca gana ni inventa una`() {
        fun fusion(existente: String?, nueva: String?): String? {
            val vm = carrito()
            vm.addProduct(cafe45.copy(catalogAt = existente))
            vm.addProduct(cafe45.copy(catalogAt = nueva))
            val renglon = vm.cartState.value.items.single()
            assertEquals(2, renglon.quantity)
            return renglon.catalogAt
        }
        assertEquals(cat, fusion(cat, "basura"))
        assertEquals(null, fusion(null, "basura"))
        assertEquals(cat2, fusion(cat, cat2))
        assertEquals(cat2, fusion(cat2, cat))
        assertEquals(cat2, fusion("basura", cat2))
        assertEquals("basura", fusion("basura", "otra basura"))
    }

    @Test
    fun `las tres altas copian la hora del catálogo del producto`() {
        val vm = carrito()
        vm.addProduct(cafe45)
        vm.addProductWithModifiers(cafe45.copy(catalogAt = cat2), modifiers = emptyList())
        vm.addProductByWeight(Product(id = "jamon", name = "Jamón", priceValue = 420.0, soldByWeight = true, catalogAt = catViejo), 0.435)
        assertEquals(listOf(cat, cat2, catViejo), vm.cartState.value.items.map { it.catalogAt })
    }

    @Test
    fun `el carrito guardado conserva la hora`() {
        val guardados = mutableListOf<SavedCart>()
        every { savedCartsRepository.saveCart(any()) } answers { guardados += firstArg<SavedCart>(); true }
        val vm = carrito()
        vm.addProduct(cafe45)
        assertTrue(vm.saveCurrentCart("Mesa 1"))
        assertEquals(cat, guardados.single().items.single().catalogAt)
        vm.restoreSavedCart(guardados.single())
        assertEquals(cat, vm.cartState.value.items.single().catalogAt)
    }

    // MARK: - El catálogo (arreglo de ProductsRepositoryCambioDeNegocioTest)

    private val almacen = mockk<SecureStorage> {
        every { venueId } returns "venue-a"
        every { accessToken } returns "token"
    }

    private fun cliente(body: String?): OkHttpClient {
        val call = mockk<Call>()
        every { call.execute() } answers {
            body ?: throw java.io.IOException("sin red")
            Response.Builder()
                .request(Request.Builder().url("https://api.avoqado.io/test").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        return mockk { every { newCall(any()) } returns call }
    }

    @Test
    fun `el catálogo sellado llega a cada producto y sobrevive al caché`() = runTest {
        val cache = mockk<PayloadCache>(relaxed = true) { coEvery { load(any(), any()) } returns null }
        val repo = ProductsRepository(almacen, cliente("""{"catalogAt":"$cat","data":[{"id":"pa","name":"Pan","price":12.5},{"id":"pb","name":"Bolillo","price":3}]}"""), cache)
        repo.fetchProducts()
        assertEquals(listOf(cat, cat), repo.products.value.map { it.catalogAt })

        val enDisco = slot<String>()
        coVerify { cache.save(PayloadCache.TYPE_PRODUCTS, "venue-a", capture(enDisco)) }
        // Otro arranque, sin red: el caché trae la hora con la que se mostraron esos precios.
        val cacheDelArranque = mockk<PayloadCache>(relaxed = true) {
            coEvery { load(PayloadCache.TYPE_PRODUCTS, "venue-a") } returns PayloadCache.Cached(enDisco.captured, 0L)
        }
        val sinRed = ProductsRepository(almacen, cliente(null), cacheDelArranque)
        sinRed.fetchProducts()
        assertEquals(listOf("pa", "pb"), sinRed.products.value.map { it.id })
        assertEquals(listOf(cat, cat), sinRed.products.value.map { it.catalogAt })
    }
}
