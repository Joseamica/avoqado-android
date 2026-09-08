package com.avoqado.pos.orders.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
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
import kotlinx.coroutines.test.runTest
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
        return OrdersViewModel(repository, refreshGateFactory, dispatcherFalso.mock, secureStorage)
    }

    @Test
    fun `reimprimir rearma la comanda desde la orden y la manda marcada como REIMPRESION`() = runTest {
        val viewModel = createViewModel()
        viewModel.reimprimirComanda("orden-1")
        assertEquals("REIMPRESIÓN", dispatcherFalso.ultimoOrderType)
        assertEquals(3, dispatcherFalso.ultimasLineas.size)
    }

    @Test
    fun `si la reimpresion no sale, lo DICE (no canta exito como el bug de la T3)`() = runTest {
        dispatcherFalso.resultado = EstadoDeComanda.NoSalio(listOf("Cocina"), "sin conexion", "ORD-0001")
        val viewModel = createViewModel()
        viewModel.reimprimirComanda("orden-1")
        assertEquals("No salió la comanda de: Cocina · sin conexion", viewModel.mensajeDeReimpresion.value)
    }

    @Test
    fun `un pedido sin artículos con productId lo DICE en vez de cantar exito`() = runTest {
        every { repository.selectedOrder } returns MutableStateFlow(
            ordenDePrueba.copy(items = listOf(OrderDetailItem(id = "cargo-1", productId = null, productName = "Cargo por servicio"))),
        )
        val viewModel = createViewModel()
        viewModel.reimprimirComanda("orden-1")
        assertEquals("No hay artículos para reimprimir en este pedido", viewModel.mensajeDeReimpresion.value)
    }
}
