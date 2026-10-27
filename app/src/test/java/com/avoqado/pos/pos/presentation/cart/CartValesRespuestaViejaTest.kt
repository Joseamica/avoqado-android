package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicket
import com.avoqado.pos.areatickets.data.AreaTicketArea
import com.avoqado.pos.areatickets.data.AreaTicketCheckout
import com.avoqado.pos.areatickets.data.AreaTicketCheckoutTotals
import com.avoqado.pos.areatickets.data.AreaTicketEnvelope
import com.avoqado.pos.areatickets.data.AreaTicketLine
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.areatickets.data.AreaTicketSession
import com.avoqado.pos.areatickets.data.CheckoutData
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiService
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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 🔴 Codex r12 #2 (paridad con el llamador de vales de iOS). El repositorio ya no deja que una respuesta vieja escriba la sesión de
 * otra venta (r11 #3), pero se la DEVUELVE a quien llamó. El carrito la aplicaba sin mirar: el latido, quitar un vale, restaurar o
 * agregar uno de la venta A, si volvían cuando ya estaba B, ponían los vales de A en el carrito de B. Ahora se aplica sólo si siguen el
 * MISMO carrito y la MISMA sesión que había antes de la red. Sin cruce, todo sigue igual.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CartValesRespuestaViejaTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val api = mockk<ApiService>()
    private val session = AreaTicketSession(secureStorage)
    private val vales = AreaTicketRepository(api = api, secureStorage = secureStorage, session = session, payloadCache = mockk(relaxed = true))
    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
    private val savedCartsRepository = mockk<SavedCartsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val staffRepository = mockk<StaffRepository>(relaxed = true)

    @Before
    fun setup() {
        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { savedCartsRepository.savedCarts } returns MutableStateFlow<List<SavedCart>>(emptyList())
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        every { secureStorage.venueId } returns VENUE
        coEvery { staffRepository.getActiveStaff() } returns Result.success(emptyList())
    }

    private fun carrito() = CartViewModel(
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
        areaTicketRepository = vales,
        walletScanRepository = mockk(relaxed = true),
    )

    private fun vale(id: String) = AreaTicket(
        id = id, code = "V-$id", status = "OPEN", fulfillmentArea = AreaTicketArea("area-1", "Barra", "PICKUP"),
        subtotal = "50.00", total = "50.00", issuedAt = "2026-10-25T10:00:00Z",
        lines = listOf(AreaTicketLine(id = "l-$id", clientLineId = "c-$id", productNameSnapshot = "Café", quantity = "1", unitPrice = "50.00", total = "50.00")),
    )

    private fun sesion(id: String, vararg vales: String) = AreaTicketCheckout(
        id = id, status = "OPEN", version = 1, expiresAt = "2999-01-01T00:00:00Z", createdAt = "2026-10-25T10:00:00Z",
        tickets = vales.map(::vale), totals = AreaTicketCheckoutTotals("100.00", "0.00", "100.00"),
    )

    private fun responde(c: AreaTicketCheckout) = AreaTicketEnvelope(success = true, data = CheckoutData(c))

    private fun CartViewModel.valesEnElCarrito() = cartState.value.items.filter { it.locked }.map { it.areaTicketId }

    /** A terminó (se vació el carrito y se soltó su sesión) y B ya trae su vale en el carrito. */
    private fun CartViewModel.empiezaB() {
        session.clear()
        clearCart()
        session.update(VENUE, sesion("sesion-B", "b1"))
        traerValesDeLaSesion()
    }

    @Test
    fun `el latido de A que vuelve con B en el carrito no pone los vales de A`() = runTest {
        val vm = carrito()
        session.update(VENUE, sesion("sesion-A", "a1")); vm.traerValesDeLaSesion()
        val respuesta = CompletableDeferred<AreaTicketEnvelope<CheckoutData>>()
        coEvery { api.heartbeatAreaTicketCheckout(VENUE, "sesion-A", any()) } coAnswers { respuesta.await() }

        val latido = async { vm.heartbeatAreaTicketSession() }
        runCurrent()
        vm.empiezaB()
        respuesta.complete(responde(sesion("sesion-A", "a1", "a2")))
        latido.await()

        assertEquals("el carrito de B sigue con su vale", listOf("b1"), vm.valesEnElCarrito())
        assertEquals("sesion-B", session.current()?.id)
    }

    @Test
    fun `quitar un vale de A que vuelve con B en el carrito no pone los vales de A`() = runTest {
        val vm = carrito()
        session.update(VENUE, sesion("sesion-A", "a1", "a2")); vm.traerValesDeLaSesion()
        val respuesta = CompletableDeferred<AreaTicketEnvelope<CheckoutData>>()
        coEvery { api.removeAreaTicketFromCheckout(VENUE, "sesion-A", "a1", any()) } coAnswers { respuesta.await() }

        vm.removeItem("area-ticket:a1:l-a1")
        runCurrent()
        vm.empiezaB()
        respuesta.complete(responde(sesion("sesion-A", "a2")))
        runCurrent()

        assertEquals(listOf("b1"), vm.valesEnElCarrito())
    }

    @Test
    fun `el latido de A que vuelve con el carrito ya vaciado no repone los vales`() = runTest {
        val vm = carrito()
        session.update(VENUE, sesion("sesion-A", "a1")); vm.traerValesDeLaSesion()
        val respuesta = CompletableDeferred<AreaTicketEnvelope<CheckoutData>>()
        coEvery { api.heartbeatAreaTicketCheckout(VENUE, "sesion-A", any()) } coAnswers { respuesta.await() }
        // «Vaciar» suelta la sesión con un cancel que todavía viaja: la sesión sigue siendo A, pero el carrito ya es otro.
        coEvery { api.cancelAreaTicketCheckout(VENUE, "sesion-A", any()) } coAnswers { CompletableDeferred<AreaTicketEnvelope<CheckoutData>>().await() }

        val latido = async { vm.heartbeatAreaTicketSession() }
        runCurrent()
        vm.clearCart()
        respuesta.complete(responde(sesion("sesion-A", "a1", "a2")))
        latido.await()

        assertEquals("el carrito vaciado no recibe los vales de A", emptyList<String>(), vm.valesEnElCarrito())
    }

    @Test
    fun `el latido de A que vuelve cuando el mismo carrito ya trae la sesion B no pone los vales de A`() = runTest {
        val vm = carrito()
        session.update(VENUE, sesion("sesion-A", "a1")); vm.traerValesDeLaSesion()
        val respuesta = CompletableDeferred<AreaTicketEnvelope<CheckoutData>>()
        coEvery { api.heartbeatAreaTicketCheckout(VENUE, "sesion-A", any()) } coAnswers { respuesta.await() }

        val latido = async { vm.heartbeatAreaTicketSession() }
        runCurrent()
        // Sin vaciar el carrito: A venció y se tiró, y el siguiente vale abrió B en el MISMO carrito.
        session.clear()
        session.update(VENUE, sesion("sesion-B", "b1")); vm.traerValesDeLaSesion()
        respuesta.complete(responde(sesion("sesion-A", "a1", "a2")))
        latido.await()

        assertEquals(listOf("b1"), vm.valesEnElCarrito())
    }

    @Test
    fun `sin cruce el latido y quitar un vale actualizan el carrito como siempre`() = runTest {
        val vm = carrito()
        session.update(VENUE, sesion("sesion-A", "a1")); vm.traerValesDeLaSesion()
        coEvery { api.heartbeatAreaTicketCheckout(VENUE, "sesion-A", any()) } returns responde(sesion("sesion-A", "a1", "a2"))
        coEvery { api.removeAreaTicketFromCheckout(VENUE, "sesion-A", "a1", any()) } returns responde(sesion("sesion-A", "a2"))

        vm.heartbeatAreaTicketSession()
        assertEquals(listOf("a1", "a2"), vm.valesEnElCarrito())
        vm.removeItem("area-ticket:a1:l-a1")
        runCurrent()
        assertEquals(listOf("a2"), vm.valesEnElCarrito())
    }

    private companion object { const val VENUE = "venue-vales" }
}
