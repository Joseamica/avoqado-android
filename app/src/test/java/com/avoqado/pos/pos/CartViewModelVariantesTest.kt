package com.avoqado.pos.pos

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
import com.avoqado.pos.pos.data.model.VariantOption
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import com.avoqado.pos.pos.presentation.cart.ScannedBarcodeResult
import com.avoqado.pos.pos.presentation.product.variantes.ResultadoDeBusqueda
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Variantes de producto, bloque 3 (T4): `CartViewModel.familias` y la búsqueda con familias, más el escaneo de una variante.
 * Catálogo = `catalogo()` de `casos-compartidos.md` (el mismo de `FamiliasTest`). La lógica vive en `Familias.kt`; aquí se
 * prueba que el ViewModel la cablea y que el lector entrega la VARIANTE (nunca abre la hoja).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CartViewModelVariantesTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
    private val savedCartsRepository = mockk<SavedCartsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val staffRepository = mockk<StaffRepository>(relaxed = true)
    private val productos = MutableStateFlow<List<Product>>(emptyList())

    // ---- Catálogo compartido ----

    private fun op(nombre: String, valor: String, np: Int, vp: Int) = VariantOption(nombre, valor, np, vp)

    private val agua = Product(id = "agua", name = "Agua", priceValue = 20.0)
    private val gorra = Product(id = "gorra", name = "Gorra", priceValue = 150.0, sku = "GO-1")

    private fun playera(id: String, talla: Pair<String, Int>, color: Pair<String, Int>, precio: Double, sku: String,
                        gtin: String? = null, agotado: Boolean = false) = Product(
        id = id, name = "Playera · ${talla.first} · ${color.first}", priceValue = precio, sku = sku, gtin = gtin,
        trackInventory = if (agotado) true else null, availableQuantity = if (agotado) 0 else null,
        productGroupId = "g1", productGroupName = "Playera",
        variantOptions = listOf(op("Talla", talla.first, 0, talla.second), op("Color", color.first, 1, color.second)),
    )

    private val plSAz = playera("pl-s-az", "S" to 0, "Azul" to 0, 199.0, "PL-S-AZ")
    private val plMAz = playera("pl-m-az", "M" to 1, "Azul" to 0, 199.0, "PL-M-AZ")
    private val plSRo = playera("pl-s-ro", "S" to 0, "Rojo" to 1, 219.0, "PL-S-RO", gtin = "7501000000017", agotado = true)
    private val taza = Product(
        id = "taza", name = "Taza · Grande", priceValue = 99.0, productGroupId = "g2", productGroupName = "Taza",
        variantOptions = listOf(op("Tamaño", "Grande", 0, 1)),
    )

    private fun catalogo() = listOf(agua, plSAz, gorra, plMAz, plSRo, taza)

    // ---- Montaje (molde: CartViewModelOutOfStockTest) ----

    @Before
    fun setup() {
        every { productsRepository.products } returns productos
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { savedCartsRepository.savedCarts } returns MutableStateFlow<List<SavedCart>>(emptyList())
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        every { secureStorage.venueId } returns "venue-1"
        io.mockk.coEvery { staffRepository.getActiveStaff() } returns Result.success(emptyList())
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
        walletScanRepository = mockk(relaxed = true),
    )

    /**
     * El ViewModel con las familias y la búsqueda calculándose en el planificador de la PRUEBA y con la vista suscrita. Nunca en
     * `Dispatchers.Default` real: un cálculo que terminaba en otro hilo DESPUÉS de la prueba volvía a `Main` ya sin `setMain` y
     * tumbaba la prueba siguiente («The main looper is not available»; Mac, ronda de arreglo 1).
     */
    private fun TestScope.viewModelSuscrito(): CartViewModel = createViewModel().also { vm ->
        vm.calculoDeCatalogo = StandardTestDispatcher(testScheduler)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.familias.collect {} }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.searchResults.collect {} }
    }

    /** `fam:g1` para una familia, el id para un producto: así un fallo enseña la lista entera. */
    private fun List<ResultadoDeBusqueda>.ids() = map {
        when (it) {
            is ResultadoDeBusqueda.DeFamilia -> "fam:${it.familia.groupId}"
            is ResultadoDeBusqueda.DeProducto -> it.producto.id
        }
    }

    private fun TestScope.buscar(vm: CartViewModel, texto: String): List<String> {
        vm.updateSearchQuery(texto)
        advanceUntilIdle()
        return vm.searchResults.value.ids()
    }

    // ---- Búsqueda ----

    @Test
    fun `por nombre sale la familia una vez`() = runTest {
        productos.value = catalogo()
        val vm = viewModelSuscrito()
        assertEquals(listOf("fam:g1"), buscar(vm, "play"))
    }

    @Test
    fun `por SKU salen las variantes, no la familia`() = runTest {
        productos.value = catalogo()
        val vm = viewModelSuscrito()
        assertEquals(listOf("pl-s-az", "pl-s-ro"), buscar(vm, "PL-S"))
    }

    @Test
    fun `por GTIN sale la variante`() = runTest {
        productos.value = catalogo()
        val vm = viewModelSuscrito()
        assertEquals(listOf("pl-s-ro"), buscar(vm, "7501000000017"))
    }

    @Test
    fun `P1 sin familias la busqueda es la de hoy`() = runTest {
        productos.value = listOf(agua, gorra)
        val vm = viewModelSuscrito()
        assertEquals(listOf("gorra"), buscar(vm, "gor"))
    }

    @Test
    fun `sin familias un texto con espacios no se recorta, como hoy`() = runTest {
        productos.value = listOf(agua, gorra)
        val vm = viewModelSuscrito()
        assertEquals(listOf("gorra"), buscar(vm, "gor"))   // primero algo que SÍ da resultado: el vacío de abajo es un recálculo
        assertEquals(emptyList<String>(), buscar(vm, " gor "))
    }

    /**
     * Ronda de arreglo 1, #2: la búsqueda se calcula fuera del hilo principal, así que el resultado puede ir un paso atrás del campo.
     * Mientras el cálculo de un texto nuevo no termina, no se dice «Sin resultados» para ese texto (antes salía un destello de
     * «Sin resultados» / «Crear producto» en la primera tecla), y al borrar el campo no se enseñan los resultados de antes.
     */
    @Test
    fun `P1 mientras el texto nuevo se calcula no se dice sin resultados, y al borrar no quedan los de antes`() = runTest {
        productos.value = catalogo()
        val vm = viewModelSuscrito()   // el cálculo corre sólo cuando la prueba lo deja

        vm.updateSearchQuery("play")
        assertFalse(vm.busqueda.value.sinResultadosPara("play"))   // la primera tecla: su cálculo todavía no corre
        advanceUntilIdle()
        assertEquals(listOf("fam:g1"), vm.busqueda.value.resultadosPara("play").ids())

        vm.updateSearchQuery("zzz")   // el cálculo de «zzz» todavía no corre
        assertFalse(vm.busqueda.value.sinResultadosPara("zzz"))
        advanceUntilIdle()
        assertTrue(vm.busqueda.value.sinResultadosPara("zzz"))

        vm.updateSearchQuery("play")
        advanceUntilIdle()
        vm.updateSearchQuery("")      // borrar: sin esperar el cálculo, la lista ya no enseña los de «play»
        assertEquals(emptyList<String>(), vm.busqueda.value.resultadosPara("").ids())
        assertFalse(vm.busqueda.value.sinResultadosPara(""))
    }

    /**
     * Codex P2-3: mientras el cálculo de la consulta nueva está pendiente, la lista NO enseña (ni deja tocar) los resultados de la
     * anterior, también en un negocio sin familias; tampoco se dice «Sin resultados» (eso exige el texto vigente y resultados vacíos).
     */
    @Test
    fun `P2 con otra consulta pendiente no queda nada tocable de la anterior`() = runTest {
        productos.value = listOf(agua.copy(sku = "AG-1"), gorra)   // sin familias
        val vm = viewModelSuscrito()

        vm.updateSearchQuery("GO-1")
        advanceUntilIdle()
        assertEquals(listOf("gorra"), vm.busqueda.value.resultadosPara("GO-1").ids())

        vm.updateSearchQuery("AG-1")   // el cálculo de «AG-1» todavía no corre: sigue en el estado la «Gorra» de «GO-1»
        assertEquals(emptyList<String>(), vm.busqueda.value.resultadosPara("AG-1").ids())
        assertFalse(vm.busqueda.value.sinResultadosPara("AG-1"))

        advanceUntilIdle()
        assertEquals(listOf("agua"), vm.busqueda.value.resultadosPara("AG-1").ids())
        assertFalse(vm.busqueda.value.sinResultadosPara("AG-1"))
    }

    // ---- Familias del catálogo ----

    @Test
    fun `familias se calcula del catalogo y se recalcula al cambiar`() = runTest {
        productos.value = catalogo()
        val vm = viewModelSuscrito()
        advanceUntilIdle()
        assertEquals(setOf("g1"), vm.familias.value.keys)
        assertEquals(listOf("pl-s-az", "pl-m-az", "pl-s-ro"), vm.familias.value.getValue("g1").variantes.map { it.id })

        productos.value = listOf(agua, gorra)
        advanceUntilIdle()
        assertEquals(emptySet<String>(), vm.familias.value.keys)
    }

    // ---- El lector entrega la VARIANTE (nunca abre la hoja) ----

    @Test
    fun `familias ya trae la familia al crear el ViewModel sin esperar el calculo`() = runTest {
        // Cada mesa y el cobro crean un hiltViewModel() nuevo: el primer cuadro no puede pintar las variantes sueltas.
        productos.value = catalogo()
        val vm = createViewModel()       // sin suscribir y sin avanzar el planificador
        assertEquals(setOf("g1"), vm.familias.value.keys)
    }

    @Test
    fun `P1 escanear el SKU o el GTIN de una variante entrega esa variante`() = runTest {
        productos.value = catalogo()
        val vm = createViewModel()
        assertEquals(ScannedBarcodeResult.ProductFound(plMAz), vm.resolveScannedBarcode("PL-M-AZ"))
        assertEquals(ScannedBarcodeResult.ProductFound(plSRo), vm.resolveScannedBarcode("7501000000017"))
    }
}
