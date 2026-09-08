package com.avoqado.pos.orders.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.orders.data.OrdersRepository
import com.avoqado.pos.orders.data.model.OrderDetail
import com.avoqado.pos.orders.data.model.OrderDetailItem
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.routing.RoutableItem
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration

/**
 * Fake sobre un mock de [ComandaDispatcher]: captura lo último que se mandó a rutear (las
 * líneas y el `orderType`) y deja fijar qué contesta `dispatch`, sin repartir la configuración
 * de mockk por cada test. `resultado` es un `var` leído en cada llamada (vía `coAnswers`), así
 * que fijarlo ANTES de invocar `reimprimirComanda` cambia lo que el despachador "responde".
 */
private class DispatcherFalso {
    var resultado: EstadoDeComanda? = EstadoDeComanda.Salio

    private val lineasSlot = slot<List<RoutableItem>>()
    private val orderTypeSlot = slot<String>()

    val mock: ComandaDispatcher = mockk {
        coEvery {
            dispatch(any(), capture(lineasSlot), any(), capture(orderTypeSlot), any(), any(), any(), any(), any())
        } coAnswers { resultado }
    }

    val ultimoOrderType: String? get() = if (orderTypeSlot.isCaptured) orderTypeSlot.captured else null
    val ultimasLineas: List<RoutableItem> get() = if (lineasSlot.isCaptured) lineasSlot.captured else emptyList()
}

/**
 * Task 6: en mostrador (sin mesas) una comanda que no sale no tenía NINGÚN botón — el
 * equivalente de "Volver a imprimir" sólo vivía en `TableOrderViewModel.reprintComandas`. Este
 * archivo cubre `OrdersViewModel.reimprimirComanda`, su marca `REIMPRESIÓN` y que el resultado
 * se DIGA en vez de asumirse (el bug ya medido en este repo: T3 cantaba éxito sin haber
 * impreso nada).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrdersReimprimirComandaTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val dispatcherFalso = DispatcherFalso()
    private val repository = mockk<OrdersRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)

    /**
     * El catálogo local — la MISMA fuente de la que el carrito saca `categoryId` al imprimir la
     * primera vez. Sin él, reimprimir mandaba todo a la estación por defecto (P1 #8 de Codex).
     */
    private val catalogo = MutableStateFlow(
        listOf(
            Product(id = "prod-1", name = "Café", categoryId = "cat-bebidas"),
            Product(id = "prod-2", name = "Muffin", categoryId = "cat-panaderia"),
            Product(id = "prod-3", name = "Té", categoryId = "cat-bebidas"),
        ),
    )
    private val productsRepository = mockk<ProductsRepository>(relaxed = true).also {
        every { it.products } returns catalogo
    }

    // 3 artículos, los tres con productId — ninguno se queda fuera del filtro que reusa
    // TableOrderViewModel.reprintComandas (sólo PRODUCTOS reales van a cocina).
    private val ordenDePrueba = OrderDetail(
        id = "orden-1",
        orderNumber = "ORD-0001",
        items = listOf(
            OrderDetailItem(id = "item-1", productId = "prod-1", productName = "Café", quantity = 1),
            OrderDetailItem(id = "item-2", productId = "prod-2", productName = "Muffin", quantity = 2),
            OrderDetailItem(id = "item-3", productId = "prod-3", productName = "Té", quantity = 1),
        ),
    )

    @Before
    fun setup() {
        every { secureStorage.venueId } returns "venue-1"
        every { repository.selectedOrder } returns MutableStateFlow(ordenDePrueba)
    }

    private fun createViewModel(): OrdersViewModel {
        val refreshGateFactory = mockk<RefreshGateFactory>()
        every { refreshGateFactory.create(any(), any()) } returns RefreshGate(clock = { Duration.ZERO })
        return OrdersViewModel(repository, refreshGateFactory, dispatcherFalso.mock, secureStorage, productsRepository)
    }

    @Test
    fun `reimprimir rearma la comanda desde la orden y la manda marcada como REIMPRESION`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectOrder("orden-1")
        viewModel.reimprimirComanda("orden-1")
        assertEquals("REIMPRESIÓN", dispatcherFalso.ultimoOrderType)
        assertEquals(3, dispatcherFalso.ultimasLineas.size)
    }

    @Test
    fun `si la reimpresion no sale, lo DICE (no canta exito como el bug de la T3)`() = runTest {
        dispatcherFalso.resultado = EstadoDeComanda.NoSalio(listOf("Cocina"), "sin conexion", "ORD-0001")
        val viewModel = createViewModel()
        viewModel.selectOrder("orden-1")
        viewModel.reimprimirComanda("orden-1")
        assertEquals("No salió la comanda de: Cocina · sin conexion", viewModel.mensajeDeReimpresion.value)
    }

    @Test
    fun `un pedido sin artículos con productId lo DICE en vez de cantar exito`() = runTest {
        every { repository.selectedOrder } returns MutableStateFlow(
            ordenDePrueba.copy(items = listOf(OrderDetailItem(id = "cargo-1", productId = null, productName = "Cargo por servicio"))),
        )
        val viewModel = createViewModel()
        viewModel.selectOrder("orden-1")
        viewModel.reimprimirComanda("orden-1")
        assertEquals("No hay artículos para reimprimir en este pedido", viewModel.mensajeDeReimpresion.value)
    }

    /**
     * P1 #8 de la auditoría de Codex (2026-09-07). `RoutableItem.categoryId` iba en `null`, y
     * la categoría es lo que decide a qué estación va cada producto: un café que se imprime en
     * Barra por una regla de categoría se iba a Cocina —la estación por defecto— en silencio.
     *
     * Se resuelve del catálogo local por `productId`, igual que hace el carrito.
     */
    @Test
    fun `P1 reimprimir resuelve la CATEGORIA de cada producto, no la manda en null`() = runTest {
        val viewModel = createViewModel()

        viewModel.selectOrder("orden-1")
        viewModel.reimprimirComanda("orden-1")
        advanceUntilIdle()

        val categorias = dispatcherFalso.ultimasLineas.associate { it.productId to it.categoryId }
        assertEquals("sin categoría, el ruteo por categoría no aplica y todo cae en la default", "cat-bebidas", categorias["prod-1"])
        assertEquals("cat-panaderia", categorias["prod-2"])
        assertEquals("cat-bebidas", categorias["prod-3"])
    }

    /**
     * P1 #9 de la auditoría de Codex (2026-09-07). En tablet, el cajero puede seleccionar OTRO
     * pedido mientras una reimpresión sigue en vuelo. El resultado se publicaba a secas, así que
     * el pedido B mostraba «Comanda reimpresa» — de una reimpresión que fue de A y que B nunca
     * pidió. Atarlo al id no lo PIERDE: reaparece al volver a A.
     */
    @Test
    fun `P1 el resultado de reimprimir A no aparece dentro de B`() = runTest {
        val viewModel = createViewModel()
        viewModel.selectOrder("orden-1")
        viewModel.reimprimirComanda("orden-1")
        advanceUntilIdle()
        assertNotNull("la reimpresión de A no dijo nada", viewModel.mensajeDeReimpresion.value)

        // El cajero abre otro pedido.
        viewModel.selectOrder("orden-2")
        assertNull(
            "el pedido B enseñó el resultado de una reimpresión que fue de A",
            viewModel.mensajeDeReimpresion.value,
        )

        // Y al volver a A, su resultado sigue ahí — no se perdió, sólo estaba en su sitio.
        viewModel.selectOrder("orden-1")
        assertNotNull("el resultado de A se perdió al ir y volver", viewModel.mensajeDeReimpresion.value)
    }
}
