package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.payment.PrefsEnMemoria
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PayLaterQueue
import com.avoqado.pos.pos.data.ActiveCartState
import com.avoqado.pos.pos.data.ClassCheckoutSeed
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import com.avoqado.pos.tables.data.CustomerLoyalty
import com.avoqado.pos.tables.data.TableServiceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** D39 · atajo «Usar puntos» → «Pagar después» + «Recompensas» en Pedidos (promesa v2, `d39/promesa-atajo.md`). */
@OptIn(ExperimentalCoroutinesApi::class)
class UsarPuntosCarritoTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val staffRepository = mockk<StaffRepository>(relaxed = true)
    private val ordenes = mockk<OrderRepository>(relaxed = true)
    private val mesas = mockk<TableServiceRepository>()
    private val puerta = PuertaDeRecompensas()
    private val disco = PrefsEnMemoria()
    private val copias = PrefsEnMemoria()
    private val guardados by lazy { SavedCartsRepository(copias.context) }
    private val fila by lazy {
        PayLaterQueue(disco.context, ordenes, secureStorage, guardados, mockk(relaxed = true)).apply { io = Dispatchers.Unconfined }
    }
    private val cafe = Product(id = "p-cafe", name = "Café", priceValue = 100.0)

    @Before fun setup() {
        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.accessToken } returns "token"
        every { secureStorage.selloYAccess() } returns (7L to "token")
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-1"
        every { secureStorage.venuePermissions } returns listOf("loyalty:read", "orders:update", "orders:create")
        coEvery { staffRepository.getActiveStaff() } returns Result.success(emptyList())
        coEvery { mesas.getCustomerLoyalty("venue-1", "cli-1", null) } returns Result.success(saldo(200))
    }

    private fun saldo(puntos: Int) = CustomerLoyalty(
        customerId = "cli-1", customerName = "Ana", active = true, balance = puntos, redemptionRate = 0.10, balanceValue = puntos * 0.10,
        minPointsRedeem = 10, maxRedeemablePoints = puntos, maxRedeemableValue = puntos * 0.10, canRedeem = puntos >= 10,
    )

    private fun vm() = CartViewModel(
        productsRepository = productsRepository,
        discountsRepository = discountsRepository,
        savedCartsRepository = guardados,
        authRepository = authRepository,
        secureStorage = secureStorage,
        activeCartState = mockk<ActiveCartState>(relaxed = true),
        orderRepository = ordenes,
        staffRepository = staffRepository,
        classCheckoutSeed = mockk<ClassCheckoutSeed>(relaxed = true).also { every { it.consume() } returns null },
        validateReferralUseCase = mockk(relaxed = true),
        captureReferralUseCase = mockk(relaxed = true),
        planManager = PlanManager(secureStorage),
        tableSession = com.avoqado.pos.tables.data.TableSession(),
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
        areaTicketRepository = mockk(relaxed = true),
        walletScanRepository = mockk(relaxed = true),
        payLaterQueue = fila,
        tableServiceRepository = mesas,
        puertaDeRecompensas = puerta,
    )

    private fun responde(code: Int, body: String) {
        coEvery { ordenes.enviarPagarDespues(any(), any()) } returns OrderRepository.RespuestaCruda(code, "application/json", null, body)
    }

    private fun ventaDeAna(vm: CartViewModel) {
        vm.addProduct(cafe)
        vm.setSelectedCustomer("cli-1", "Ana")
    }

    @Test fun `con la lectura del cliente se puede usar puntos`() = runTest {
        val vm = vm()
        ventaDeAna(vm)
        vm.leerPuntosParaElAtajo()
        advanceUntilIdle()
        assertNull(vm.motivoSinUsarPuntos())
        coVerify(exactly = 1) { mesas.getCustomerLoyalty("venue-1", "cli-1", null) }
    }

    @Test fun `creada, pide abrir ESA cuenta en Pedidos con la sesion y la sucursal`() = runTest {
        val vm = vm()
        vm.cobrarSeMonto()
        ventaDeAna(vm)
        vm.leerPuntosParaElAtajo()
        advanceUntilIdle()
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { respuesta.await() }
        vm.usarPuntos()
        runCurrent()
        assertEquals(true, vm.pagarDespuesEnCurso.value?.deUsarPuntos)
        respuesta.complete(OrderRepository.RespuestaCruda(201, "application/json", null,
            """{"success":true,"order":{"id":"ord-1","total":100.0}}"""))
        advanceUntilIdle()
        assertEquals(ResultadoPagarDespues.Creada, vm.resultadoUsarPuntos.value)
        assertNull("no es el «Pagar después» de Atajos", vm.resultadoPagarDespues.value)
        assertEquals(SolicitudDeRecompensas("ord-1", "venue-1", 7L, "Ana"), puerta.solicitud.value)
        // v3 «C»: la cuenta se abre encima de Cobrar, no en Más › Pedidos.
        assertEquals("ord-1", vm.cuentaDelAtajoDeEstaSesion()?.orderId)
        assertTrue(vm.cartState.value.isEmpty)
    }

    // v3 «C»: cerrar la ventana (X, cobrada o al salir de Cobrar) la olvida y suelta la apertura de «Recompensas».
    @Test fun `cerrar la ventana de la cuenta la olvida y suelta Recompensas`() = runTest {
        val vm = vm()
        vm.cobrarSeMonto()
        ventaDeAna(vm)
        vm.leerPuntosParaElAtajo()
        advanceUntilIdle()
        responde(201, """{"success":true,"order":{"id":"ord-1","total":100.0}}""")
        vm.usarPuntos()
        advanceUntilIdle()
        vm.cerrarCuentaDelAtajo()
        assertNull(vm.cuentaDelAtajoDeEstaSesion())
        assertNull(puerta.solicitud.value)
    }

    // Codex v3 #3: si la cajera salió de Cobrar mientras se creaba la cuenta, la ventana no aparece al volver; se dice dónde quedó.
    @Test fun `si Cobrar se desmonto mientras se creaba, no se abre la ventana`() = runTest {
        val vm = vm()
        vm.cobrarSeMonto()
        ventaDeAna(vm)
        vm.leerPuntosParaElAtajo()
        advanceUntilIdle()
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { respuesta.await() }
        vm.usarPuntos()
        runCurrent()
        vm.cobrarSeDesmonto()
        vm.cobrarSeMonto()
        respuesta.complete(OrderRepository.RespuestaCruda(201, "application/json", null, """{"success":true,"order":{"id":"ord-1","total":100.0}}"""))
        advanceUntilIdle()
        assertNull(vm.cuentaDelAtajo.value)
        assertNull(puerta.solicitud.value)
        assertEquals(ResultadoPagarDespues.Aviso(avisoCuentaEnPedidos("Ana", "")), vm.resultadoUsarPuntos.value)
    }

    // Codex v3-r2 #4: girar la pantalla no es salir de Cobrar: la ventana sigue y una cuenta que termina de crearse en el giro sí se abre.
    @Test fun `girar la pantalla no cierra la ventana ni pierde la que se esta creando`() = runTest {
        val vm = vm()
        vm.cobrarSeMonto()
        ventaDeAna(vm)
        vm.leerPuntosParaElAtajo()
        advanceUntilIdle()
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { respuesta.await() }
        vm.usarPuntos()
        runCurrent()
        vm.cobrarSeGira()
        // Codex C1-r1 #4: la cuenta se termina de crear EN el giro (desmontado, todavía sin remontar): la ventana sí se abre.
        respuesta.complete(OrderRepository.RespuestaCruda(201, "application/json", null, """{"success":true,"order":{"id":"ord-1","total":100.0}}"""))
        advanceUntilIdle()
        vm.cobrarSeMonto()
        assertEquals("ord-1", vm.cuentaDelAtajo.value?.orderId)
        vm.cobrarSeGira()
        vm.cobrarSeMonto()
        assertEquals("ord-1", vm.cuentaDelAtajo.value?.orderId)
    }

    // Codex C1-r2 #4: «ya se cobró» vive en el carrito (sobrevive a girar la pantalla), no en la pantalla.
    @Test fun `marcar la cuenta como cobrada sobrevive en el carrito`() = runTest {
        val vm = vm()
        vm.cobrarSeMonto()
        ventaDeAna(vm)
        vm.leerPuntosParaElAtajo()
        advanceUntilIdle()
        responde(201, """{"success":true,"order":{"id":"ord-1","total":100.0}}""")
        vm.usarPuntos()
        advanceUntilIdle()
        assertEquals(false, vm.cuentaDelAtajo.value?.cobrada)
        vm.cuentaDelAtajoCobrada("otra")
        assertEquals(false, vm.cuentaDelAtajo.value?.cobrada)
        vm.cuentaDelAtajoCobrada("ord-1")
        vm.cobrarSeGira()
        vm.cobrarSeMonto()
        assertEquals(true, vm.cuentaDelAtajo.value?.cobrada)
    }

    // v3 «C»: con otra sesión (cambio de usuario por PIN) o en otra sucursal, la ventana no se enseña.
    @Test fun `con otra sesion la ventana no se enseña`() = runTest {
        val vm = vm()
        vm.cobrarSeMonto()
        ventaDeAna(vm)
        vm.leerPuntosParaElAtajo()
        advanceUntilIdle()
        responde(201, """{"success":true,"order":{"id":"ord-1","total":100.0}}""")
        vm.usarPuntos()
        advanceUntilIdle()
        every { secureStorage.selloYAccess() } returns (8L to "token")
        assertNull(vm.cuentaDelAtajoDeEstaSesion())
        assertNull(puerta.solicitud.value)
    }

    // Codex r1 #4: la lectura es de Ana y el seleccionado ya es Beto: no se crea nada.
    @Test fun `si el cliente cambio despues de leer, no se crea la cuenta`() = runTest {
        val vm = vm()
        ventaDeAna(vm)
        vm.leerPuntosParaElAtajo()
        advanceUntilIdle()
        vm.setSelectedCustomer("cli-2", "Beto")
        responde(201, """{"success":true,"order":{"id":"ord-1","total":100.0}}""")
        vm.usarPuntos()
        advanceUntilIdle()
        assertEquals(ResultadoPagarDespues.Error(USAR_PUNTOS_CLIENTE_CAMBIO), vm.resultadoUsarPuntos.value)
        coVerify(exactly = 0) { ordenes.enviarPagarDespues(any(), any()) }
        assertNull(puerta.solicitud.value)
    }

    // SOBRA de Codex r1: creada por otro importe se dice como hoy, sin la coletilla de la red, y no se abre.
    @Test fun `creada por otro importe se dice como hoy y no se abre`() = runTest {
        val vm = vm()
        ventaDeAna(vm)
        vm.leerPuntosParaElAtajo()
        advanceUntilIdle()
        responde(201, """{"success":true,"order":{"id":"ord-1","total":90.0}}""")
        vm.usarPuntos()
        advanceUntilIdle()
        assertEquals(ResultadoPagarDespues.Aviso(pagarDespuesOtroImporte("Ana", 9000, 10000)), vm.resultadoUsarPuntos.value)
        assertNull(puerta.solicitud.value)
    }

    // Codex r3 R3: el cliente se captura al tocar «Continuar»; si el motivo del descuento abrió otra ventana y en ese rato el lector
    // cambió el cliente, no se crea la cuenta a nombre del nuevo.
    @Test fun `el cliente confirmado al tocar manda aunque luego cambie el seleccionado`() = runTest {
        val vm = vm()
        ventaDeAna(vm)
        vm.leerPuntosParaElAtajo()
        advanceUntilIdle()
        val confirmado = vm.selectedCustomer.value?.id
        vm.setSelectedCustomer("cli-2", "Beto")
        coEvery { mesas.getCustomerLoyalty("venue-1", "cli-2", null) } returns Result.success(saldo(500))
        vm.leerPuntosParaElAtajo()
        advanceUntilIdle()
        responde(201, """{"success":true,"order":{"id":"ord-1","total":100.0}}""")
        vm.usarPuntos(clienteConfirmado = confirmado)
        advanceUntilIdle()
        assertEquals(ResultadoPagarDespues.Error(USAR_PUNTOS_CLIENTE_CAMBIO), vm.resultadoUsarPuntos.value)
        coVerify(exactly = 0) { ordenes.enviarPagarDespues(any(), any()) }
    }

    // Codex r3 R2: una lectura vieja que termina después de la nueva no la pisa.
    @Test fun `una lectura vieja no pisa la nueva`() = runTest {
        val vm = vm()
        ventaDeAna(vm)
        val vieja = CompletableDeferred<Result<CustomerLoyalty>>()
        coEvery { mesas.getCustomerLoyalty("venue-1", "cli-1", null) } coAnswers { vieja.await() } andThen Result.success(saldo(200))
        vm.leerPuntosParaElAtajo()
        runCurrent()
        vm.leerPuntosParaElAtajo()
        advanceUntilIdle()
        assertEquals(200, vm.lecturaDelAtajo.value?.lealtad?.balance)
        vieja.complete(Result.failure(java.io.IOException("sin red")))
        advanceUntilIdle()
        assertEquals(200, vm.lecturaDelAtajo.value?.lealtad?.balance)
    }

    // Codex r3 #7: sin respuesta (sin red o 503) la cuenta queda en la fila; el aviso no afirma que fue la red.
    @Test fun `sin respuesta el aviso es neutro`() = runTest {
        val vm = vm()
        ventaDeAna(vm)
        vm.leerPuntosParaElAtajo()
        advanceUntilIdle()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } throws java.io.IOException("sin red")
        vm.usarPuntos()
        advanceUntilIdle()
        assertEquals(ResultadoPagarDespues.Aviso(usarPuntosCuentaEnLaFila("Ana")), vm.resultadoUsarPuntos.value)
    }

    @Test fun `sin lectura no se crea nada`() = runTest {
        val vm = vm()
        ventaDeAna(vm)
        responde(201, """{"success":true,"order":{"id":"ord-1","total":100.0}}""")
        vm.usarPuntos()
        advanceUntilIdle()
        assertEquals(ResultadoPagarDespues.Error(USAR_PUNTOS_LEYENDO), vm.resultadoUsarPuntos.value)
        coVerify(exactly = 0) { ordenes.enviarPagarDespues(any(), any()) }
    }
}
