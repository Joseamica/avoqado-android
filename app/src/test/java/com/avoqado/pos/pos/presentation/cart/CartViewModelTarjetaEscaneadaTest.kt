package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.areatickets.data.AreaTicketModuleSettings
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.areatickets.data.AreaTicketSettingsData
import com.avoqado.pos.areatickets.data.AreaTicketTerminalCapabilities
import com.avoqado.pos.areatickets.data.ScaleIntegrationSettings
import com.avoqado.pos.areatickets.data.VariableWeightBarcodeSettings
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.pos.data.ActiveCartState
import com.avoqado.pos.pos.data.ClassCheckoutSeed
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.StaffMember
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import com.avoqado.pos.pos.data.model.SavedCart
import com.avoqado.pos.referrals.domain.usecase.CaptureReferralUseCase
import com.avoqado.pos.referrals.domain.usecase.ValidateReferralUseCase
import com.avoqado.pos.pos.data.model.parecidoACodigo
import io.mockk.coEvery
import org.junit.Assert.assertFalse
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertTrue
import io.mockk.verify
import io.mockk.coVerify
import com.avoqado.pos.areatickets.data.AreaTicket
import com.avoqado.pos.areatickets.data.AreaTicketArea
import com.avoqado.pos.areatickets.data.AreaTicketCheckout
import com.avoqado.pos.areatickets.data.AreaTicketCheckoutOrder
import com.avoqado.pos.areatickets.data.AreaTicketCheckoutTotals
import com.avoqado.pos.areatickets.data.AreaTicketLine
import com.avoqado.pos.areatickets.data.AreaTicketScanData
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Venta por peso en [CartViewModel]: D9 — cada pesada agrega una LÍNEA NUEVA (nunca se fusiona),
 * mientras que un producto normal sigue fusionando cantidad (regresión).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CartViewModelTarjetaEscaneadaTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
    private val savedCartsRepository = mockk<SavedCartsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val activeCartState = mockk<ActiveCartState>(relaxed = true)
    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val staffRepository = mockk<StaffRepository>(relaxed = true)
    private val classCheckoutSeed = mockk<ClassCheckoutSeed>(relaxed = true)
    private val validateReferralUseCase = mockk<ValidateReferralUseCase>(relaxed = true)
    private val captureReferralUseCase = mockk<CaptureReferralUseCase>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)

    private val wallet = mockk<com.avoqado.pos.loyalty.data.WalletScanRepository>(relaxed = true)
    private val venueSwitchedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @Before
    fun setup() {
        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { savedCartsRepository.savedCarts } returns MutableStateFlow<List<SavedCart>>(emptyList())
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns venueSwitchedFlow
        every { classCheckoutSeed.consume() } returns null
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.userId } returns "user-1"
        every { secureStorage.userFirstName } returns "Jose"
        every { secureStorage.userLastName } returns "Tester"
        every { secureStorage.userEmail } returns "jose@example.com"
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-99"
        every { secureStorage.selectedStaffNameForCurrentVenue } returns "Jose Tester"
        coEvery { staffRepository.getActiveStaff() } returns Result.success(
            listOf(StaffMember(id = "staff-99", firstName = "Jose", lastName = "Tester")),
        )
        coEvery { areaTicketRepository.settings() } returns AreaTicketSettingsData(
            venueId = "venue-1",
            areaTickets = AreaTicketModuleSettings(entitled = true, enabled = true),
            terminal = AreaTicketTerminalCapabilities(id = "terminal-1", name = "Caja"),
            scaleIntegration = ScaleIntegrationSettings(entitled = false, enabled = false),
            variableWeightBarcode = VariableWeightBarcodeSettings(
                entitled = true,
                enabled = true,
                prefix = "20",
            ),
        )
    }

    private fun createViewModel(lector: com.avoqado.pos.pos.data.LectorHidBus = com.avoqado.pos.pos.data.LectorHidBus()): CartViewModel = CartViewModel(
        productsRepository = productsRepository,
        discountsRepository = discountsRepository,
        savedCartsRepository = savedCartsRepository,
        authRepository = authRepository,
        secureStorage = secureStorage,
        activeCartState = activeCartState,
        orderRepository = orderRepository,
        staffRepository = staffRepository,
        classCheckoutSeed = classCheckoutSeed,
        validateReferralUseCase = validateReferralUseCase,
        captureReferralUseCase = captureReferralUseCase,
        planManager = PlanManager(secureStorage),
        tableSession = com.avoqado.pos.tables.data.TableSession(),
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
        areaTicketRepository = areaTicketRepository,
        walletScanRepository = wallet,
        lectorHidBus = lector,
    )

    @Test fun `producto de 17 caracteres gana sin viajes de tarjeta o vale`() = runTest {
        val product = Product(id="p", name="Blusa", priceValue=150.0, sku="K7QZABCDEFGHJKMNP")
        every { productsRepository.products } returns MutableStateFlow(listOf(product))
        val vm=createViewModel()
        assertEquals(ScannedBarcodeResult.ProductFound(product), vm.resolveScannedBarcode("K7QZABCDEFGHJKMNP"))
        coVerify(exactly=0) { wallet.escanear(any()) }; coVerify(exactly=0) { areaTicketRepository.resolveCheckoutScan(any()) }
    }
    @Test fun `liga da GiftCardFound sin viajes de producto o vale`() = runTest {
        val vm=createViewModel()
        assertEquals("GiftCardFound", vm.resolveScannedBarcode("https://pay.avoqado.io/tarjeta/Xk3pQ9vLabcdefghij12").javaClass.simpleName)
        coVerify(exactly=0) { wallet.escanear(any()) }; coVerify(exactly=0) { areaTicketRepository.resolveCheckoutScan(any()) }
        assertTrue(vm.cartState.value.isEmpty)
    }
    @Test fun `pase de 48 hex conserva repositorio y desconocido sigue desconocido`() = runTest {
        val vm=createViewModel(); val pase="a1".repeat(24)
        vm.resolveScannedBarcode(pase); coVerify(exactly=1) { wallet.escanear(pase) }
        assertEquals(ScannedBarcodeResult.Unknown("sin-alta"),vm.resolveScannedBarcode("sin-alta"))
    }
    @Test fun `sin red un pase de 48 hex no se vuelve el producto que se le parece`() = runTest {
        // Codex r2: la misma lectura identifica al cliente con red; sin red no puede volverse mercancía.
        val pase="a1".repeat(24)
        every { productsRepository.products } returns MutableStateFlow(listOf(Product(id="p",name="Blusa",priceValue=150.0,sku=pase.uppercase())))
        coEvery { wallet.escanear(pase) } returns null
        assertEquals(ScannedBarcodeResult.Unknown(pase),createViewModel().resolveScannedBarcode(pase))
    }
    @Test fun `con forma de tarjeta nunca hay parecido, tampoco en Mesas ni donde no se consulta la tarjeta`() {
        // Codex r3: Mesas llama directo a parecidoACodigo. La guardia vive ahí para que ningún camino se la salte.
        val pase="a1".repeat(24)
        val regalo="abcd2345efgh6789j"
        val productos=listOf(Product(id="p",name="Blusa",priceValue=150.0,sku=pase.uppercase()),Product(id="q",name="Gorra",priceValue=90.0,sku=regalo.uppercase()),Product(id="r",name="Taza",priceValue=50.0,sku="TAZA-1"))
        assertNull(productos.parecidoACodigo(pase))
        assertNull(productos.parecidoACodigo(regalo))
        assertEquals("r",productos.parecidoACodigo("taza-1")?.id)
    }
    @Test fun `peso conserva precedencia y vale se resuelve primero`() = runTest {
        every { productsRepository.products } returns MutableStateFlow(listOf(Product(id="p",name="Jamón",priceValue=420.0,sku="00123",soldByWeight=true,unit="KILOGRAM")))
        coEvery { areaTicketRepository.resolveCheckoutScan("9470000015") } returns AreaTicketScanData(type="PAID_AREA_TICKET",code="9470000015")
        val vm=createViewModel()
        val peso=vm.resolveScannedBarcode("2000123004358") as ScannedBarcodeResult.WeightedProductFound
        assertEquals(0.435,peso.weightKg,0.0)
        assertTrue(vm.resolveScannedBarcode("9470000015") is ScannedBarcodeResult.Error)
        coVerify(exactly=1) { areaTicketRepository.resolveCheckoutScan("9470000015") }
    }
    @Test fun `HID no resuelve carrito mientras la hoja esta abierta y vuelve al cerrar`() = runTest {
        val vm=createViewModel()
        com.avoqado.pos.giftcards.domain.LectorParaTarjetas.abrir()
        try {
            assertTrue(vm.resolveScannedBarcode("a1".repeat(24)) is ScannedBarcodeResult.Error)
            coVerify(exactly=0) { wallet.escanear(any()) }
        } finally { com.avoqado.pos.giftcards.domain.LectorParaTarjetas.cerrar() }
        vm.resolveScannedBarcode("a1".repeat(24)); coVerify(exactly=1) { wallet.escanear(any()) }
    }

    @Test fun `R3 listener de ventana consulta una vez no procesa carrito y al cerrar lo devuelve`() = runTest {
        val bus = com.avoqado.pos.pos.data.LectorHidBus()
        val cart = createViewModel(bus)
        val peticiones = java.util.concurrent.CopyOnWriteArrayList<String>()
        val cliente = okhttp3.OkHttpClient.Builder().addInterceptor { cadena ->
            val req = cadena.request()
            peticiones += req.url.encodedPath
            okhttp3.Response.Builder().request(req).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("fixture")
                .body("""{"success":true,"data":{"usableBalance":0,"cards":[]}}""".toResponseBody("application/json".toMediaType())).build()
        }.build()
        val online = MutableStateFlow(true)
        val red = mockk<com.avoqado.pos.core.util.ConnectivityMonitor> {
            every { isConnected } returns online; every { isServerReachable } returns online
        }
        val roles = mockk<com.avoqado.pos.core.domain.RoleManager> { every { canReadGiftCards } returns true }
        every { secureStorage.leerAjustesDeTarjetas("venue-1") } returns com.avoqado.pos.giftcards.FijasDeTarjetas.ajustes
        val vm = com.avoqado.pos.giftcards.presentation.IdentificarTarjetaViewModel(
            com.avoqado.pos.giftcards.data.GiftCardsRepository(cliente,secureStorage),secureStorage,red,bus,roles)
        val dialogo = mockk<android.app.Dialog>()
        var listener: android.content.DialogInterface.OnKeyListener? = null
        every { dialogo.setOnKeyListener(any()) } answers { listener = firstArg() }
        var procesados = 0
        val receptor = backgroundScope.launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            cart.codigosEscaneados.collect { leido ->
                // Misma guardia de CheckoutScreen.manejarCodigo; se invoca el carrito real sólo si está libre.
                if (!com.avoqado.pos.giftcards.domain.LectorParaTarjetas.abierta.value) {
                    procesados++; cart.resolveScannedBarcode(leido.texto, leido.lecturaUs)
                }
            }
        }
        fun tecla(c: Char, ms: Long, arriba: Boolean = false): android.view.KeyEvent = mockk {
            every { action } returns if (arriba) android.view.KeyEvent.ACTION_UP else android.view.KeyEvent.ACTION_DOWN
            every { keyCode } returns when {
                c=='\n' -> android.view.KeyEvent.KEYCODE_ENTER
                c.isDigit() -> android.view.KeyEvent.KEYCODE_0+(c-'0')
                else -> android.view.KeyEvent.KEYCODE_A+(c.lowercaseChar()-'a')
            }
            every { unicodeChar } returns c.code; every { deviceId } returns 7
            every { eventTime } returns ms; every { repeatCount } returns 0
        }
        vm.abrir("venue-1",null)
        val soltar = com.avoqado.pos.giftcards.presentation.escucharLectorEnDialogo(dialogo,vm::procesarTecla)
        try {
            "abcd".forEachIndexed { n,c ->
                val e=tecla(c,1000L+n*250);assertFalse(listener!!.onKey(dialogo,e.keyCode,e))
                val up=tecla(c,1001L+n*250,true);assertFalse(listener!!.onKey(dialogo,up.keyCode,up))
            }
            val texto="K7QZABCDEFGHJKMNP"
            (texto+"\n").forEachIndexed { n,c ->
                val e=tecla(c,5000L+n*10);listener!!.onKey(dialogo,e.keyCode,e)
                val up=tecla(c,5001L+n*10,true);listener!!.onKey(dialogo,up.keyCode,up)
            }
            runCurrent()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(2000) {
                    while(peticiones.isEmpty()) kotlinx.coroutines.delay(10)
                }
            }
            assertEquals("La ventana debe consultar UNA vez",1,peticiones.size)
            assertTrue(peticiones.single().endsWith("/gift-cards/lookup"))
            assertEquals(0,procesados);assertTrue(cart.cartState.value.isEmpty)
            coVerify(exactly=0) { wallet.escanear(any()) }
            vm.cerrar();soltar();assertNull(listener)
            (texto+"\n").forEachIndexed { n,c -> bus.procesar(tecla(c,9000L+n*10)) }
            runCurrent();assertEquals(1,procesados);assertEquals(1,peticiones.size)
        } finally { vm.cerrar();soltar();receptor.cancel();cliente.dispatcher.executorService.shutdown();cliente.connectionPool.evictAll() }
    }
    @Test fun `R3 desmontar libera el listener de la ventana`() {
        val dialogo = mockk<android.app.Dialog>()
        var listener: android.content.DialogInterface.OnKeyListener? = null
        every { dialogo.setOnKeyListener(any()) } answers { listener=firstArg() }
        val soltar = com.avoqado.pos.giftcards.presentation.escucharLectorEnDialogo(dialogo) { false }
        assertNotNull(listener);soltar();assertNull(listener)
    }

    @Test fun `D14 pistola en ingles con el aparato en espanol encuentra el producto`() = runTest {
        val product = Product(id = "p", name = "Pizza", priceValue = 169.0, sku = "PIZZAS-003")
        every { productsRepository.products } returns MutableStateFlow(listOf(product))
        val vm = createViewModel()
        assertEquals(ScannedBarcodeResult.ProductFound(product), vm.resolveScannedBarcode("PIZZAS'003", lecturaUs = "PIZZAS-003"))
    }

    @Test fun `D14 la lectura del aparato gana si la de EE UU no existe`() = runTest {
        val delAparato = Product(id = "a", name = "Etiqueta rara", priceValue = 10.0, sku = "PIZZAS'003")
        every { productsRepository.products } returns MutableStateFlow(listOf(delAparato))
        val vm = createViewModel()
        assertEquals(ScannedBarcodeResult.ProductFound(delAparato), vm.resolveScannedBarcode("PIZZAS'003", lecturaUs = "PIZZAS-003"))
    }

    @Test fun `D14 si las dos lecturas son productos distintos no se agrega ninguno`() = runTest {
        // Codex r3: con la pistola en inglés «AB-123» y con la pistola en español «AB'123» son las MISMAS teclas.
        val conApostrofo = Product(id = "a", name = "Etiqueta rara", priceValue = 10.0, sku = "PIZZAS'003")
        val conGuion = Product(id = "b", name = "Pizza", priceValue = 169.0, sku = "PIZZAS-003")
        every { productsRepository.products } returns MutableStateFlow(listOf(conApostrofo, conGuion))
        val vm = createViewModel()
        assertEquals(
            ScannedBarcodeResult.CodigoDoble(avisoDeCodigoDoble("PIZZAS'003", "PIZZAS-003")),
            vm.resolveScannedBarcode("PIZZAS'003", lecturaUs = "PIZZAS-003"),
        )
    }

    @Test fun `D14 un codigo que difiere solo en mayusculas tambien cuenta como el otro producto`() = runTest {
        // Codex r4 #1: B tiene «pizzas'003» (búsqueda sin mayúsculas) y A «PIZZAS-003» exacto.
        val conGuion = Product(id = "a", name = "Pizza", priceValue = 169.0, sku = "PIZZAS-003")
        val minusculas = Product(id = "b", name = "Etiqueta rara", priceValue = 10.0, barcode = "pizzas'003")
        every { productsRepository.products } returns MutableStateFlow(listOf(minusculas, conGuion))
        val vm = createViewModel()
        assertEquals(
            ScannedBarcodeResult.CodigoDoble(avisoDeCodigoDoble("PIZZAS'003", "PIZZAS-003")),
            vm.resolveScannedBarcode("PIZZAS'003", lecturaUs = "PIZZAS-003"),
        )
    }

    @Test fun `D14 un producto con las dos lecturas no esconde al otro que tiene una`() = runTest {
        // Codex r4 #2: A tiene SKU «AB-123» y GTIN «AB'123»; B tiene GTIN «AB-123».
        val a = Product(id = "a", name = "A", priceValue = 10.0, sku = "AB-123", gtin = "AB'123")
        val b = Product(id = "b", name = "B", priceValue = 20.0, gtin = "AB-123")
        every { productsRepository.products } returns MutableStateFlow(listOf(a, b))
        val vm = createViewModel()
        assertEquals(
            ScannedBarcodeResult.CodigoDoble(avisoDeCodigoDoble("AB'123", "AB-123")),
            vm.resolveScannedBarcode("AB'123", lecturaUs = "AB-123"),
        )
    }

    @Test fun `D14 un solo producto con las dos lecturas entra normal`() = runTest {
        val a = Product(id = "a", name = "A", priceValue = 10.0, sku = "AB-123", gtin = "AB'123")
        every { productsRepository.products } returns MutableStateFlow(listOf(a))
        val vm = createViewModel()
        assertEquals(ScannedBarcodeResult.ProductFound(a), vm.resolveScannedBarcode("AB'123", lecturaUs = "AB-123"))
    }

    @Test fun `D14 si ninguna de las dos lecturas existe se avisa con la del aparato`() = runTest {
        every { productsRepository.products } returns MutableStateFlow(emptyList())
        val vm = createViewModel()
        assertEquals(ScannedBarcodeResult.Unknown("ZZZ'9"), vm.resolveScannedBarcode("ZZZ'9", lecturaUs = "ZZZ-9"))
    }

    @Test fun `D14 la camara sin lectura en ingles se queda como hoy`() = runTest {
        val product = Product(id = "p", name = "Pizza", priceValue = 169.0, sku = "PIZZAS-003")
        every { productsRepository.products } returns MutableStateFlow(listOf(product))
        val vm = createViewModel()
        assertEquals(ScannedBarcodeResult.Unknown("PIZZAS'003"), vm.resolveScannedBarcode("PIZZAS'003"))
    }

    @Test fun `D14 un SKU desconocido con guiones no se vuelve consulta de tarjeta`() = runTest {
        // Codex r5 #3: 17 letras y números al quitar los guiones, pero con U y L, que el servidor nunca emite.
        every { productsRepository.products } returns MutableStateFlow(emptyList())
        val vm = createViewModel()
        assertEquals(
            ScannedBarcodeResult.Unknown("CAFE'REGULAR'000001"),
            vm.resolveScannedBarcode("CAFE'REGULAR'000001", lecturaUs = "CAFE-REGULAR-000001"),
        )
    }

    @Test fun `D14 en MOSTRADOR la segunda lectura no adivina tarjetas de regalo`() = runTest {
        // Codex r7 #1: con el teclado en español la tarjeta se lee en su hoja (su propio lector), como en develop.
        every { productsRepository.products } returns MutableStateFlow(emptyList())
        val vm = createViewModel()
        assertEquals(
            ScannedBarcodeResult.Unknown("CAFE'VERD'E000'0039'9"),
            vm.resolveScannedBarcode("CAFE'VERD'E000'0039'9", lecturaUs = "CAFE-VERD-E000-0039-9"),
        )
    }

    @Test fun `D14 un SKU guardado en minusculas se encuentra con la etiqueta en mayusculas`() = runTest {
        // Codex r6 #2 y r7 #2: 17 letras y números, pero con U y L no es tarjeta: sí se compara sin mayúsculas.
        val p = Product(id = "p", name = "Café", priceValue = 50.0, sku = "cafe-regular-000001")
        every { productsRepository.products } returns MutableStateFlow(listOf(p))
        val vm = createViewModel()
        assertEquals(ScannedBarcodeResult.ProductFound(p), vm.resolveScannedBarcode("CAFE'REGULAR'000001", lecturaUs = "CAFE-REGULAR-000001"))
    }

    @Test fun `D14 un producto encontrado sin mayusculas por el aparato no gana al exacto de la otra lectura`() = runTest {
        // Codex r6 #1: A tiene el SKU exacto; B guarda «cafe'regular'000001» en GTIN.
        val a = Product(id = "a", name = "A", priceValue = 10.0, sku = "CAFE-REGULAR-000001")
        val b = Product(id = "b", name = "B", priceValue = 20.0, gtin = "cafe'regular'000001")
        every { productsRepository.products } returns MutableStateFlow(listOf(a, b))
        val vm = createViewModel()
        assertTrue(vm.resolveScannedBarcode("CAFE'REGULAR'000001", lecturaUs = "CAFE-REGULAR-000001") is ScannedBarcodeResult.CodigoDoble)
    }

    @Test fun `D14 un SKU desconocido con forma de codigo valido pero sin el formato impreso no abre la tarjeta`() = runTest {
        // Codex r6 #3: pasa el alfabeto y el control del servidor, pero no es XXXX-XXXX-XXXX-XXXX-X.
        every { productsRepository.products } returns MutableStateFlow(emptyList())
        val vm = createViewModel()
        assertEquals(
            ScannedBarcodeResult.Unknown("CAFE'VERDE'00000399"),
            vm.resolveScannedBarcode("CAFE'VERDE'00000399", lecturaUs = "CAFE-VERDE-00000399"),
        )
    }

    @Test fun `D14 si la lectura del aparato empato entre productos no se elige uno de la otra lectura`() = runTest {
        // Codex r8 #2: pistola en español que de verdad leyó «PIZZAS'003»; dos productos empatan sin mayúsculas.
        val a = Product(id = "a", name = "A", priceValue = 10.0, barcode = "pizzas'003")
        val b = Product(id = "b", name = "B", priceValue = 20.0, barcode = "Pizzas'003")
        val c = Product(id = "c", name = "C", priceValue = 30.0, sku = "PIZZAS-003")
        every { productsRepository.products } returns MutableStateFlow(listOf(a, b, c))
        val vm = createViewModel()
        assertEquals(ScannedBarcodeResult.Unknown("PIZZAS'003"), vm.resolveScannedBarcode("PIZZAS'003", lecturaUs = "PIZZAS-003"))
    }
}
