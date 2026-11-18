package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaTicket
import com.avoqado.pos.areatickets.data.AreaTicketArea
import com.avoqado.pos.areatickets.data.AreaTicketCheckout
import com.avoqado.pos.areatickets.data.AreaTicketCheckoutTotals
import com.avoqado.pos.areatickets.data.AreaTicketLine
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.areatickets.data.AreaTicketSession
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.customerdisplay.renglonesParaElCliente
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.presentation.cobraLosValesDeLaSesion
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 🔴 DINERO (hz:nube0000:nabcea1a, 18-nov). Un vale V7 con descuento por producto entraba al carrito con su precio BRUTO
 * (`AreaTicketLine.total`) y sin su descuento (`discountAmount`): la caja pedía $150 por una venta que el SERVIDOR arma en $136.50.
 * El caso es el que vio la nube con la D3: el vale 9683124676 de «Cremería» (Café $30 −10 %, Té $30 −20 %, Café Mediano $45 −10 %;
 * subtotal $105, descuento $13.50, total $91.50) más un café suelto de $60 con su descuento de $15.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CartValesConDescuentoTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val session = AreaTicketSession(secureStorage)
    private val vales = AreaTicketRepository(api = mockk(), secureStorage = secureStorage, session = session, payloadCache = mockk(relaxed = true))
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

    private fun renglon(id: String, nombre: String, bruto: String, descuento: String? = null) = AreaTicketLine(
        // "1.000": así manda la cantidad el SERVIDOR (`quantity(...).toFixed(3)`).
        id = id, clientLineId = "c-$id", productNameSnapshot = nombre, quantity = "1.000", unitPrice = bruto, total = bruto,
    ).let { if (descuento == null) it else it.copy(discountAmount = descuento) }

    private fun vale(vararg renglones: AreaTicketLine) = AreaTicket(
        id = "vale-cremeria", code = "9683124676", status = "CLAIMED", fulfillmentArea = AreaTicketArea("area-cremeria", "Cremería", "PICKUP"),
        subtotal = "105.00", discountAmount = "13.50", total = "91.50", issuedAt = "2026-11-18T10:00:00Z", lines = renglones.toList(),
    )

    private val valeDeLaNube = vale(
        renglon("l-cafe", "Café", "30.00", "3.00"),
        renglon("l-te", "Té", "30.00", "6.00"),
        renglon("l-mediano", "Café Mediano", "45.00", "4.50"),
    )

    private fun sesion(vararg tickets: AreaTicket) = AreaTicketCheckout(
        id = "sesion-cremeria", status = "OPEN", version = 1, expiresAt = "2999-01-01T00:00:00Z", createdAt = "2026-11-18T10:00:00Z",
        tickets = tickets.toList(), totals = AreaTicketCheckoutTotals("105.00", "13.50", "91.50"),
    )

    private val cafeSuelto = Product(id = "p-cafe", name = "Café de olla", priceValue = 60.0)
    private val menos15 = Discount(id = "d-15", name = "Promo café", value = 15.0, type = "FIXED_AMOUNT", scope = "ITEM")

    private fun CartViewModel.conElVale(s: AreaTicketCheckout = sesion(valeDeLaNube)) = apply {
        session.update(VENUE, s)
        traerValesDeLaSesion()
    }

    @Test
    fun `P1 el vale con descuento por producto y un cafe suelto cobran lo mismo que el servidor`() {
        val vm = carrito().conElVale()
        vm.addProductWithModifiers(cafeSuelto, modifiers = emptyList(), discount = menos15)

        val cart = vm.cartState.value
        assertEquals("subtotal BRUTO, como la venta del servidor", 16500, cart.subtotalCents)
        assertEquals("descuento: $13.50 del vale + $15 del suelto", 2850, cart.discountCents)
        assertEquals("lo que se cobra: el total de la venta del servidor", 13650, cart.totalCents)
    }

    @Test
    fun `P1 solo el vale cobra su total del papel`() {
        val cart = carrito().conElVale().cartState.value

        assertEquals(10500, cart.subtotalCents)
        assertEquals(1350, cart.itemDiscountCents)
        assertEquals("el total que dice el vale", 9150, cart.totalCents)
    }

    @Test
    fun `cada renglon del vale queda bruto y la segunda pantalla dice su descuento`() {
        val cart = carrito().conElVale().cartState.value
        val cafe = cart.items.single { it.areaTicketLineId == "l-cafe" }

        assertEquals("el precio del renglón sigue bruto", 3000, cafe.grossPrice)
        assertEquals(2700, cafe.totalPrice)
        // El papel imprime el subtítulo y cambia «−» por «?» (Codex r3 #2): el descuento no va ahí, va en su fila y en la segunda pantalla.
        assertFalse(cafe.subtitle.orEmpty(), cafe.subtitle.orEmpty().contains("Descuento"))
        val enLaSegundaPantalla = renglonesParaElCliente(cart.items).single { it.key == cafe.id }.detalle.orEmpty()
        assertTrue(enLaSegundaPantalla, enLaSegundaPantalla.startsWith("Descuento del vale −$3"))
    }

    @Test
    fun `el descuento del vale no pide motivo ni PIN ni viaja como descuento del catalogo`() {
        val cart = carrito().conElVale().cartState.value

        assertFalse(cart.pideAprobacionDeGerente)
        assertFalse(cart.regalaUnProducto)
        assertTrue(cart.items.none { it.descuentoPideMotivo || it.itemDiscountId != null || it.hasCustomizations })
    }

    @Test
    fun `P1 con vales un descuento de cuenta no baja lo que se cobra`() {
        // Codex r2 #1: el referido entraba como descuento de cuenta en un carrito de vales; la venta del SERVIDOR no lo lleva.
        val cart = carrito().conElVale().cartState.value.copy(
            orderDiscount = Discount(id = "referral_Ana", name = "Referido por Ana", value = 25.0, source = "REFERRAL_NEW_CUSTOMER"),
        )

        assertEquals(0, cart.orderDiscountCents)
        assertEquals("el total que dice el vale", 9150, cart.totalCents)
    }

    @Test
    fun `sin vales el descuento de cuenta sigue igual`() {
        val vm = carrito()
        vm.addProductWithModifiers(cafeSuelto, modifiers = emptyList())
        val cart = vm.cartState.value.copy(orderDiscount = Discount(id = "d-25", name = "25 %", value = 25.0))

        assertEquals(1500, cart.orderDiscountCents)
        assertEquals(4500, cart.totalCents)
    }

    @Test
    fun `P1 los vales con descuento coinciden con su sesion y con otro descuento ya no`() {
        val cart = carrito().conElVale().cartState.value
        val otroDescuento = sesion(
            vale(
                renglon("l-cafe", "Café", "30.00", "3.00"),
                renglon("l-te", "Té", "30.00", "3.00"),
                renglon("l-mediano", "Café Mediano", "45.00", "4.50"),
            ),
        )

        assertTrue(cobraLosValesDeLaSesion(cart.items, sesion(valeDeLaNube)))
        assertFalse("mismo bruto, otro descuento: se cobraría otro neto", cobraLosValesDeLaSesion(cart.items, otroDescuento))
    }

    @Test
    fun `un vale sin descuento o de un servidor que no lo manda entra igual que antes`() {
        val sinDescuento = vale(renglon("l-torta", "Torta", "50.00", "0.00"), renglon("l-agua", "Agua", "20.00"))
        val s = sesion(sinDescuento)
        val cart = carrito().conElVale(s).cartState.value

        assertEquals(7000, cart.subtotalCents)
        assertEquals(0, cart.discountCents)
        assertEquals(7000, cart.totalCents)
        assertTrue(cart.items.none { it.subtitle.orEmpty().contains("Descuento") })
        assertTrue(cobraLosValesDeLaSesion(cart.items, s))
    }

    private companion object { const val VENUE = "venue-vales" }
}
