package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.payment.PrefsEnMemoria
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PayLaterQueue
import com.avoqado.pos.payment.data.PayLaterRow
import com.avoqado.pos.pos.data.ActiveCartState
import com.avoqado.pos.pos.data.ClassCheckoutSeed
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * «Pagar después» sin red (27-oct; promesa v4 en `docs/audit/2026-10-27-pagar-despues-sin-red/promesa.md`). La venta SALE del
 * carrito al mandarla (R3-2): lo que llega mientras es otra venta; si el servidor la rechaza vuelve completa (o a «Carritos
 * guardados» si ya hay otra); sin red queda en la fila y se dice; el premio nunca entra a la fila (R8); la llave es de la venta.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PagarDespuesCarritoTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val staffRepository = mockk<StaffRepository>(relaxed = true)
    private val ordenes = mockk<OrderRepository>(relaxed = true)
    private val vales = mockk<com.avoqado.pos.areatickets.data.AreaTicketRepository>(relaxed = true)
    private val disco = PrefsEnMemoria()
    private val copias = PrefsEnMemoria()
    private val guardados by lazy { SavedCartsRepository(copias.context) }
    private val fila by lazy {
        PayLaterQueue(disco.context, ordenes, secureStorage, guardados, mockk(relaxed = true)).apply { io = Dispatchers.Unconfined }
    }

    private val cafe = Product(id = "p-cafe", name = "Café", priceValue = 100.0)
    private val pan = Product(id = "p-pan", name = "Pan", priceValue = 20.0)

    @Before
    fun setup() {
        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        every { secureStorage.venueId } returns "venue-1"
        every { secureStorage.accessToken } returns "token"
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-1"
        coEvery { staffRepository.getActiveStaff() } returns Result.success(emptyList())
    }

    private fun createViewModel(copiasGuardadas: SavedCartsRepository = guardados, cola: PayLaterQueue = fila): CartViewModel = CartViewModel(
        productsRepository = productsRepository,
        discountsRepository = discountsRepository,
        savedCartsRepository = copiasGuardadas,
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
        areaTicketRepository = vales,
        walletScanRepository = mockk(relaxed = true),
        payLaterQueue = cola,
    )

    private val enviados = mutableListOf<String>()

    private fun responde(code: Int, body: String) {
        coEvery { ordenes.enviarPagarDespues(any(), any()) } answers {
            enviados += secondArg<String>()
            OrderRepository.RespuestaCruda(code, "application/json", null, body)
        }
    }

    private fun sinRed() {
        coEvery { ordenes.enviarPagarDespues(any(), any()) } answers {
            enviados += secondArg<String>()
            throw java.io.IOException("sin red")
        }
    }

    private fun creada(total: Double) = """{"success":true,"order":{"id":"ord-1","total":$total}}"""

    /** Una venta de Ana con un café ($100) que vino de un carrito guardado. */
    private fun ventaDeAna(vm: CartViewModel) {
        vm.addProduct(cafe)
        assertTrue(vm.saveCurrentCart("Mesa de Ana"))
        vm.restoreSavedCart(guardados.savedCarts.value.single())
        vm.setSelectedCustomer("cli-1", "Ana")
    }

    @Test
    fun `la venta sale del carrito al mandarla, y lo que llega mientras tanto es otra venta`() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { respuesta.await() }

        val resultado = async { vm.createPayLaterOrder("cli-1") }
        runCurrent()

        assertTrue("la venta ya no está en el carrito", vm.cartState.value.isEmpty)
        assertNull(vm.selectedCustomer.value)
        assertEquals(EnvioPagarDespues("Ana", 10000), vm.pagarDespuesEnCurso.value)
        assertEquals(PayLaterRow.Estado.EN_VUELO, fila.filas.value.single().estado)
        vm.addProduct(pan) // el lector, mientras se manda

        respuesta.complete(OrderRepository.RespuestaCruda(201, "application/json", null, creada(100.0)))

        assertEquals(ResultadoPagarDespues.Creada, resultado.await())
        assertEquals(listOf("Pan"), vm.cartState.value.items.map { it.name })
        assertTrue(fila.filas.value.isEmpty())
        assertTrue("la copia guardada se retira al aceptar la cuenta", guardados.savedCarts.value.isEmpty())
        assertNull(vm.pagarDespuesEnCurso.value)
    }

    @Test
    fun `sin red la cuenta queda guardada en el aparato, se dice, y la copia guardada se retira`() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        sinRed()

        val resultado = vm.createPayLaterOrder("cli-1")

        assertEquals(
            ResultadoPagarDespues.Aviso("Sin conexión: la cuenta de Ana quedó guardada en el aparato y se sube sola."),
            resultado,
        )
        assertTrue(vm.cartState.value.isEmpty)
        assertTrue(guardados.savedCarts.value.isEmpty())
        val guardada = fila.filas.value.single()
        assertEquals(PayLaterRow.Estado.PENDIENTE, guardada.estado)
        assertEquals(10000, guardada.totalEsperadoCents)
        assertEquals("Ana", guardada.customerName)
        // En línea va SIN isOfflineReplay (se respeta el 409 de precio); el reenvío lleva la MISMA llave, con isOfflineReplay.
        val enLinea = JSONObject(enviados.single())
        val reenvio = JSONObject(guardada.cuerpo)
        assertFalse(enLinea.has("isOfflineReplay"))
        assertTrue(reenvio.getBoolean("isOfflineReplay"))
        assertTrue(reenvio.has("createdAtLocal"))
        assertEquals(guardada.id, enLinea.getString("externalId"))
        assertEquals(guardada.id, reenvio.getString("externalId"))
        assertTrue(guardada.id.startsWith("pay-later:"))
        assertEquals("PAY_LATER", reenvio.getString("paymentMethod"))
        assertEquals("cli-1", reenvio.getString("customerId"))
    }

    @Test
    fun `un rechazo con el carrito vacio devuelve la venta completa con su cliente, y la copia se queda`() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        responde(404, """{"message":"Cliente no encontrado"}""")

        val resultado = vm.createPayLaterOrder("cli-1")

        assertEquals(ResultadoPagarDespues.Error("Cliente no encontrado"), resultado)
        assertEquals(listOf("Café"), vm.cartState.value.items.map { it.name })
        assertEquals("Ana", vm.selectedCustomer.value?.name)
        assertTrue(fila.filas.value.isEmpty())
        assertEquals("la copia guardada no se retira con un rechazo (R3-1)", 1, guardados.savedCarts.value.size)
    }

    @Test
    fun `un rechazo con otra venta en el carrito guarda la de Ana en Carritos guardados, sin mezclarlas`() = runTest {
        val vm = createViewModel()
        vm.addProduct(cafe)
        vm.setSelectedCustomer("cli-1", "Ana")
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { respuesta.await() }

        val resultado = async { vm.createPayLaterOrder("cli-1") }
        runCurrent()
        vm.addProduct(pan)
        respuesta.complete(OrderRepository.RespuestaCruda(400, "application/json", null, """{"message":"Producto inválido"}"""))

        assertEquals(
            ResultadoPagarDespues.Error("La venta de Ana no se pudo dejar para después: quedó en Carritos guardados."),
            resultado.await(),
        )
        assertEquals(listOf("Pan"), vm.cartState.value.items.map { it.name })
        val deAna = guardados.savedCarts.value.single()
        assertEquals("Ana", deAna.name)
        assertEquals("cli-1", deAna.attachedCustomerId)
        assertEquals(listOf("Café"), deAna.items.map { it.name })
    }

    @Test
    fun `la venta con premio nunca entra a la fila, y sin red se queda en el carrito`() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        vm.setPendingStampReward(com.avoqado.pos.loyalty.data.PremioPorAplicar("rw1", "Café gratis", "FIXED_AMOUNT", 30.0))
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { respuesta.await() }

        val resultado = async { vm.createPayLaterOrder("cli-1") }
        runCurrent()
        assertTrue("ni siquiera mientras se manda", disco.guardado.isEmpty())
        respuesta.completeExceptionally(java.io.IOException("sin red"))

        assertEquals(
            ResultadoPagarDespues.Error("Sin conexión: el premio de sellos necesita internet. Quita el premio o espera la red."),
            resultado.await(),
        )
        assertTrue(disco.guardado.isEmpty())
        assertEquals(listOf("Café"), vm.cartState.value.items.map { it.name })
        assertEquals("rw1", vm.cartState.value.pendingStampRewardId)
        assertEquals("Ana", vm.selectedCustomer.value?.name)
    }

    @Test
    fun `la llave es de la venta - el siguiente intento de la misma venta manda la misma`() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        responde(409, """{"message":"Algo cambió"}""")
        vm.createPayLaterOrder("cli-1")
        responde(201, creada(100.0))

        assertEquals(ResultadoPagarDespues.Creada, vm.createPayLaterOrder("cli-1"))

        val (primera, segunda) = enviados.map { JSONObject(it).getString("externalId") }
        assertEquals(primera, segunda)
        // La venta siguiente estrena la suya.
        vm.addProduct(pan)
        vm.setSelectedCustomer("cli-2", "Beto")
        vm.createPayLaterOrder("cli-2")
        assertFalse(JSONObject(enviados.last()).getString("externalId") == primera)
    }

    @Test
    fun `la cuenta creada por otro importe se dice en ese momento`() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        responde(201, creada(90.0))

        val resultado = vm.createPayLaterOrder("cli-1")

        assertEquals(
            ResultadoPagarDespues.Aviso("La cuenta de Ana quedó por \$90.00, no por \$100.00. Revísala en el panel."),
            resultado,
        )
        assertTrue(vm.cartState.value.isEmpty)
        assertTrue(fila.filas.value.isEmpty())
    }

    // MARK: - Codex ANDROID r1

    @Test
    fun `A-3 la venta no sale del carrito hasta estar en disco, y lo que entra mientras se queda`() = runTest {
        val vm = createViewModel()
        vm.addProduct(cafe)
        vm.setSelectedCustomer("cli-1", "Ana")
        responde(201, creada(100.0))
        var alEscribirEnDisco: List<Pair<String, Int>>? = null
        disco.alEscribir = {
            if (alEscribirEnDisco == null) {
                alEscribirEnDisco = vm.cartState.value.items.map { it.name to it.quantity }
                vm.addProduct(cafe) // el lector, mientras se escribe: se junta con la línea del café
                vm.addProduct(pan)
            }
        }

        assertEquals(ResultadoPagarDespues.Creada, vm.createPayLaterOrder("cli-1"))

        assertEquals("al escribirse la cuenta, la venta seguía en el carrito", listOf("Café" to 1), alEscribirEnDisco)
        assertEquals(listOf("Café" to 1, "Pan" to 1), vm.cartState.value.items.map { it.name to it.quantity })
        assertNull("el cliente era de la venta que se fue", vm.selectedCustomer.value)
        assertEquals(1, JSONObject(enviados.single()).getJSONArray("items").getJSONObject(0).getInt("quantity"))
    }

    @Test
    fun `A-2 si la cuenta no se puede escribir, la venta se queda y nada se manda`() = runTest {
        val vm = createViewModel()
        vm.addProduct(cafe)
        vm.setSelectedCustomer("cli-1", "Ana")
        disco.discoSano = false

        assertEquals(ResultadoPagarDespues.Error("No se pudo guardar la cuenta en el aparato. Intenta de nuevo."), vm.createPayLaterOrder("cli-1"))

        assertEquals(listOf("Café"), vm.cartState.value.items.map { it.name })
        assertEquals("Ana", vm.selectedCustomer.value?.name)
        assertTrue(disco.guardado.isEmpty())
        assertTrue(fila.filas.value.isEmpty())
        coVerify(exactly = 0) { ordenes.enviarPagarDespues(any(), any()) }
    }

    @Test
    fun `A-4 rechazo con otra venta y Carritos guardados que no escribe - queda por revisar y se dice`() = runTest {
        val vm = createViewModel()
        vm.addProduct(cafe)
        vm.setSelectedCustomer("cli-1", "Ana")
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { enviados += secondArg<String>(); respuesta.await() }

        val resultado = async { vm.createPayLaterOrder("cli-1") }
        runCurrent()
        vm.addProduct(pan)
        copias.discoSano = false
        respuesta.complete(OrderRepository.RespuestaCruda(409, "application/json", null, """{"message":"El precio cambió."}"""))

        assertEquals(
            ResultadoPagarDespues.Error("La venta de Ana no se pudo dejar para después ni guardar: quedó en Pendientes por revisar."),
            resultado.await(),
        )
        val porRecuperar = fila.filas.value.single()
        assertEquals(PayLaterRow.Estado.RECUPERAR, porRecuperar.estado)
        assertEquals("El precio cambió.", porRecuperar.motivo)
        // Nunca se reenvía: como venta sin red se saltaría el rechazo de precio.
        fila.drenar()
        assertEquals(1, enviados.size)
        assertEquals(listOf("Pan"), vm.cartState.value.items.map { it.name })
    }

    @Test
    fun `A-4 rechazo con otra venta - la fila se borra sólo cuando la venta ya quedó en Carritos guardados`() = runTest {
        val vm = createViewModel()
        vm.addProduct(cafe)
        vm.setSelectedCustomer("cli-1", "Ana")
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { respuesta.await() }
        val resultado = async { vm.createPayLaterOrder("cli-1") }
        runCurrent()
        vm.addProduct(pan)
        var filasAlGuardarLaCopia: List<PayLaterRow.Estado>? = null
        copias.alEscribir = { filasAlGuardarLaCopia = fila.filas.value.map { it.estado } }

        respuesta.complete(OrderRepository.RespuestaCruda(400, "application/json", null, """{"message":"Producto inválido"}"""))

        assertEquals(ResultadoPagarDespues.Error("La venta de Ana no se pudo dejar para después: quedó en Carritos guardados."), resultado.await())
        assertEquals("al guardar la copia, la cuenta seguía en disco", listOf(PayLaterRow.Estado.RECUPERAR), filasAlGuardarLaCopia)
        assertTrue(fila.filas.value.isEmpty())
    }

    @Test
    fun `A-5 la copia de una cuenta viva no se restaura, y si su retiro falla la fila espera a retirarla`() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { enviados += secondArg<String>(); respuesta.await() }
        val resultado = async { vm.createPayLaterOrder("cli-1") }
        runCurrent()
        val copia = guardados.savedCarts.value.single()

        // Mientras se manda: ligada a la fila EN_VUELO. Ni confirmando se restaura.
        assertEquals(Restauracion.YaDejadaParaDespues, vm.restoreSavedCart(copia))
        assertEquals(Restauracion.YaDejadaParaDespues, vm.restoreSavedCart(copia, confirmado = true))
        assertEquals("la copia lleva la llave de la venta", JSONObject(enviados.single()).getString("externalId"), copia.llavePagarDespues)
        assertNotNull(copia.cobroIniciadoEn)

        copias.discoSano = false // el retiro de la copia no se escribe (archivo ocupado en WINDOWS)
        respuesta.complete(OrderRepository.RespuestaCruda(201, "application/json", null, creada(100.0)))
        assertEquals(ResultadoPagarDespues.Creada, resultado.await())

        val esperando = fila.filas.value.single()
        assertEquals(PayLaterRow.Estado.RETIRO_PENDIENTE, esperando.estado)
        assertEquals(0, fila.porSubir.first())
        assertEquals(0, fila.porRevisar.first())
        assertEquals(Restauracion.YaDejadaParaDespues, vm.restoreSavedCart(guardados.savedCarts.value.single(), confirmado = true))

        copias.discoSano = true
        fila.mantenimientoLocal() // al arrancar y en cada pasada, sin red

        assertTrue(guardados.savedCarts.value.isEmpty())
        assertTrue(fila.filas.value.isEmpty())
        assertEquals("nada se volvió a mandar", 1, enviados.size)
    }

    // MARK: - Codex ANDROID r2

    /** Rechazo de la venta de Ana con un Pan ya en el carrito; el borrado de la fila falla justo después de guardar la venta. */
    private suspend fun kotlinx.coroutines.test.TestScope.rechazoConBorradoFallido(vm: CartViewModel) {
        vm.addProduct(cafe)
        vm.setSelectedCustomer("cli-1", "Ana")
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { enviados += secondArg<String>(); respuesta.await() }
        val resultado = async { vm.createPayLaterOrder("cli-1") }
        runCurrent()
        vm.addProduct(pan)
        copias.alEscribir = { copias.alEscribir = null; disco.discoSano = false }
        respuesta.complete(OrderRepository.RespuestaCruda(409, "application/json", null, """{"message":"El precio cambió."}"""))
        assertEquals(ResultadoPagarDespues.Error("La venta de Ana no se pudo dejar para después: quedó en Carritos guardados."), resultado.await())
        assertEquals("el borrado falló: queda la fila", PayLaterRow.Estado.RECUPERAR, fila.filas.value.single().estado)
        disco.discoSano = true
    }

    @Test
    fun `r2-1 si el borrado de la fila falla tras guardar la venta, la recuperacion automatica no deja dos carritos`() = runTest {
        val vm = createViewModel()
        rechazoConBorradoFallido(vm)

        fila.mantenimientoLocal()

        assertEquals(1, guardados.savedCarts.value.size)
        assertTrue(fila.filas.value.isEmpty())
    }

    @Test
    fun `r2-1 lo mismo con Pasar a carritos guardados a mano`() = runTest {
        val vm = createViewModel()
        rechazoConBorradoFallido(vm)

        assertEquals(true, fila.pasarACarritosGuardados(fila.filas.value.single().id))
        fila.mantenimientoLocal()

        assertEquals(1, guardados.savedCarts.value.size)
        assertTrue(fila.filas.value.isEmpty())
    }

    // MARK: - D3 (29-oct): el borrado de la fila falla con la venta ya puesta a salvo

    @Test
    fun `D3 rechazo con el carrito libre y el borrado de la fila fallido - la venta queda en un solo lugar`() = runTest {
        val vm = createViewModel()
        vm.addProduct(cafe)
        vm.setSelectedCustomer("cli-1", "Ana")
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { enviados += secondArg<String>(); respuesta.await() }
        val resultado = async { vm.createPayLaterOrder("cli-1") }
        runCurrent()
        // La fila pasa bien a RECUPERAR; el disco falla justo en la escritura siguiente (su borrado).
        disco.alEscribir = { disco.alEscribir = { disco.alEscribir = null; disco.discoSano = false } }
        respuesta.complete(OrderRepository.RespuestaCruda(409, "application/json", null, """{"message":"El precio cambió."}"""))
        val aviso = resultado.await()
        disco.discoSano = true

        val enElCarrito = if (vm.cartState.value.items.any { it.name == "Café" }) 1 else 0
        assertEquals("una sola copia viva de la venta de Ana", 1, enElCarrito + guardados.savedCarts.value.size)
        assertEquals(ResultadoPagarDespues.Error(pagarDespuesACarritosGuardados("Ana")), aviso)
        assertEquals("ligada a su fila: no se vende", Restauracion.YaDejadaParaDespues, vm.restoreSavedCart(guardados.savedCarts.value.single()))

        fila.mantenimientoLocal() // la pasada siguiente (o el siguiente arranque)

        assertEquals(1, guardados.savedCarts.value.size)
        assertTrue(vm.cartState.value.isEmpty)
        assertTrue(fila.filas.value.isEmpty())
        assertEquals(1, enviados.size)
    }

    @Test
    fun `D3 la venta ya guardada no se restaura mientras su fila siga viva - venderla y trasladarla la duplicaria`() = runTest {
        val vm = createViewModel()
        rechazoConBorradoFallido(vm)
        val copia = guardados.savedCarts.value.single()
        vm.clearCart()

        assertEquals(Restauracion.YaDejadaParaDespues, vm.restoreSavedCart(copia, confirmado = true))

        fila.mantenimientoLocal()
        assertTrue(fila.filas.value.isEmpty())
        assertEquals(Restauracion.Hecha, vm.restoreSavedCart(guardados.savedCarts.value.single(), confirmado = true))
    }

    /** Arranque en frío: sólo sobrevive lo que llegó al disco ([PrefsEnMemoria.enDisco]). */
    private fun PrefsEnMemoria.trasReiniciar() = PrefsEnMemoria().also { it.guardado.putAll(enDisco); it.enDisco.putAll(enDisco) }

    private class LaAppMurio : RuntimeException("la app murió aquí")

    @Test
    fun `D3 r1 la app muere antes de cada escritura del rechazo - al arrancar queda exactamente una copia`() = runTest {
        for (muereEn in 1..4) { // 3 escrituras: fila a RECUPERAR, copia guardada, borrado de la fila; la 4 = no muere
            val disco = PrefsEnMemoria()
            val copias = PrefsEnMemoria()
            val guardados = SavedCartsRepository(copias.context)
            val fila = PayLaterQueue(disco.context, ordenes, secureStorage, guardados, mockk(relaxed = true)).apply { io = Dispatchers.Unconfined }
            val vm = createViewModel(guardados, fila)
            vm.addProduct(cafe)
            vm.setSelectedCustomer("cli-1", "Ana")
            val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
            coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { respuesta.await() }
            val resultado = async { runCatching { vm.createPayLaterOrder("cli-1") } }
            runCurrent()
            var escrituras = 0
            val muere = { if (++escrituras == muereEn) throw LaAppMurio() }
            disco.alEscribir = muere
            copias.alEscribir = muere
            respuesta.complete(OrderRepository.RespuestaCruda(409, "application/json", null, """{"message":"El precio cambió."}"""))
            resultado.await()

            val guardados2 = SavedCartsRepository(copias.trasReiniciar().context)
            val fila2 = PayLaterQueue(disco.trasReiniciar().context, ordenes, secureStorage, guardados2, mockk(relaxed = true))
                .apply { io = Dispatchers.Unconfined }
            val vm2 = createViewModel(guardados2, fila2)
            fila2.mantenimientoLocal()

            assertTrue(vm2.cartState.value.isEmpty)
            assertEquals("muere antes de la escritura $muereEn: una sola copia", 1, guardados2.savedCarts.value.size + fila2.filas.value.size)
        }
    }

    @Test
    fun `D3 r1 la fila borrada y nada mas se escribe - la venta nunca se queda sin respaldo`() = runTest {
        val vm = createViewModel()
        vm.addProduct(cafe)
        vm.setSelectedCustomer("cli-1", "Ana")
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { respuesta.await() }
        val resultado = async { vm.createPayLaterOrder("cli-1") }
        runCurrent()
        // El camino de Codex r1: RECUPERAR y el borrado salen bien, entra otra venta mientras, y ya nada más se escribe.
        copias.discoSano = false
        var escrituras = 0
        disco.alEscribir = {
            escrituras++
            if (escrituras == 2) vm.addProduct(pan)
            if (escrituras >= 3) disco.discoSano = false
        }
        respuesta.complete(OrderRepository.RespuestaCruda(409, "application/json", null, """{"message":"El precio cambió."}"""))
        val aviso = resultado.await()
        disco.alEscribir = null

        assertEquals("la venta de Ana sigue en disco", PayLaterRow.Estado.RECUPERAR, fila.filas.value.singleOrNull()?.estado)
        assertEquals(ResultadoPagarDespues.Error(pagarDespuesNiGuardada("Ana")), aviso)
        assertFalse(vm.cartState.value.items.any { it.name == "Café" })
        disco.discoSano = true
        copias.discoSano = true
        fila.mantenimientoLocal()
        assertEquals(listOf("Café"), guardados.savedCarts.value.single().items.map { it.name })
    }

    @Test
    fun `D3 r1 entra otra venta mientras se borra la fila - la de Ana se queda en Carritos guardados y no se mezcla`() = runTest {
        val vm = createViewModel()
        vm.addProduct(cafe)
        vm.setSelectedCustomer("cli-1", "Ana")
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { respuesta.await() }
        val resultado = async { vm.createPayLaterOrder("cli-1") }
        runCurrent()
        var escrituras = 0
        disco.alEscribir = { if (++escrituras == 2) vm.addProduct(pan) } // la 2a de la cola es el borrado
        respuesta.complete(OrderRepository.RespuestaCruda(409, "application/json", null, """{"message":"El precio cambió."}"""))

        assertEquals(ResultadoPagarDespues.Error(pagarDespuesACarritosGuardados("Ana")), resultado.await())
        disco.alEscribir = null
        assertEquals(listOf("Pan"), vm.cartState.value.items.map { it.name })
        assertEquals(listOf("Café"), guardados.savedCarts.value.single().items.map { it.name })
        assertTrue(fila.filas.value.isEmpty())
    }

    @Test
    fun `D3 r1 con el carrito libre vuelve ligada a su copia guardada, como un carrito restaurado, y cobrarla la retira`() = runTest {
        val vm = createViewModel()
        vm.addProduct(cafe)
        vm.setSelectedCustomer("cli-1", "Ana")
        responde(409, """{"message":"El precio cambió."}""")

        assertEquals(ResultadoPagarDespues.Error("El precio cambió."), vm.createPayLaterOrder("cli-1"))

        val copia = guardados.savedCarts.value.singleOrNull()
        assertNotNull("la venta que volvió tiene respaldo en Carritos guardados", copia)
        assertEquals(listOf("Café"), vm.cartState.value.items.map { it.name })
        assertEquals(copia?.id, vm.cartState.value.carritoGuardadoId)
        assertTrue(vm.cartState.value.vinoDeCarritoGuardado)
        assertTrue(fila.filas.value.isEmpty())
        vm.aplicarCobroConfirmado(splitType = "FULLPAYMENT", paidItemIds = emptySet(), remainingBalanceCents = 0)
        assertTrue("el cobro retira la copia", guardados.savedCarts.value.isEmpty())
    }

    // MARK: - D3 r2 (30-oct): pasada exhaustiva de `ponerASalvo` (tabla en .superpowers/sdd/2026-10-29-d3-pagar-despues/tabla-de-casos.md)

    /**
     * Un rechazo con cada escritura que puede fallar (`commit()` = false) y la app muerta justo ANTES de la escritura
     * [muereAntesDe] (null = no muere; luego igual se arranca en frío). Las escrituras se reconocen por lo que cambian en disco:
     * la MARCA (la fila pasa a RECUPERAR), la COPIA («Carritos guardados») y el BORRADO de la fila; las compensaciones que no
     * cambian el disco no cuentan. Devuelve lo que rompe la regla: UNA venta vendible, nunca 0 ni 2, y una venta cuya marca
     * llegó al disco nunca se reenvía. Al arrancar corre `drenar()` (mantenimiento + reenvío, el SERVIDOR acepta).
     */
    private suspend fun kotlinx.coroutines.test.TestScope.correrCaso(
        desdeGuardado: Boolean, fallaMarca: Boolean, fallaCopia: Boolean, fallaBorrado: Boolean, ocupado: Boolean, muereAntesDe: String?,
    ): List<String> {
        val caso = "guardada=$desdeGuardado marca=${!fallaMarca} copia=${!fallaCopia} borrado=${!fallaBorrado} ocupado=$ocupado muereAntesDe=$muereAntesDe"
        val problemas = mutableListOf<String>()
        val disco = PrefsEnMemoria()
        val copias = PrefsEnMemoria()
        val guardados = SavedCartsRepository(copias.context)
        val fila = PayLaterQueue(disco.context, ordenes, secureStorage, guardados, mockk(relaxed = true)).apply { io = Dispatchers.Unconfined }
        val vm = createViewModel(guardados, fila)
        vm.addProduct(cafe)
        if (desdeGuardado) {
            assertTrue(vm.saveCurrentCart("Mesa de Ana"))
            vm.restoreSavedCart(guardados.savedCarts.value.single())
        }
        vm.setSelectedCustomer("cli-1", "Ana")
        var enLinea: String? = null
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { enLinea = secondArg(); respuesta.await() }
        val resultado = async { runCatching { vm.createPayLaterOrder("cli-1") } }
        runCurrent()
        if (ocupado) vm.addProduct(pan)
        var murio = false
        var marcaEnDisco = false
        fun escritura(prefs: PrefsEnMemoria, clase: String?) {
            if (clase == null) return
            if (!murio && clase == muereAntesDe) { murio = true; throw LaAppMurio() }
            val falla = clase == "marca" && fallaMarca || clase == "copia" && fallaCopia || clase == "borrado" && fallaBorrado
            prefs.discoSano = !falla
            if (clase == "marca" && !falla) marcaEnDisco = true
        }
        disco.alEscribir = {
            val k = (disco.guardado.keys + disco.enDisco.keys).firstOrNull { it.startsWith("fila:") }
            val nuevo = k?.let { disco.guardado[it] }
            escritura(disco, when {
                k == null || nuevo == disco.enDisco[k] -> null
                nuevo == null -> "borrado"
                nuevo.contains("RECUPERAR") -> "marca"
                else -> null
            })
        }
        copias.alEscribir = { escritura(copias, "copia".takeIf { copias.guardado["carts"] != copias.enDisco["carts"] }) }
        respuesta.complete(OrderRepository.RespuestaCruda(409, "application/json", null, """{"message":"El precio cambió."}"""))
        resultado.await()
        disco.alEscribir = null
        copias.alEscribir = null

        if (!murio) {
            val carrito = vm.cartState.value
            val vendibles = (if (carrito.items.any { it.name == "Café" }) 1 else 0) +
                guardados.savedCarts.value.count { !fila.copiaLigada(it.id) && it.id != carrito.carritoGuardadoId } +
                fila.filas.value.count { it.estado == PayLaterRow.Estado.RECUPERAR || it.porSubir }
            if (vendibles != 1) problemas += "$caso: $vendibles vendibles en el aparato"
        }

        val reenvios = mutableListOf<String>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } answers {
            reenvios += secondArg<String>()
            OrderRepository.RespuestaCruda(201, "application/json", null, creada(100.0))
        }
        val guardados2 = SavedCartsRepository(copias.trasReiniciar().context)
        val fila2 = PayLaterQueue(disco.trasReiniciar().context, ordenes, secureStorage, guardados2, mockk(relaxed = true))
            .apply { io = Dispatchers.Unconfined }
        val vm2 = createViewModel(guardados2, fila2)
        fila2.drenar()
        val alArrancar = reenvios.size + guardados2.savedCarts.value.size + fila2.filas.value.size
        if (!vm2.cartState.value.isEmpty || alArrancar != 1) problemas += "$caso: $alArrancar al arrancar"
        if (marcaEnDisco && reenvios.isNotEmpty()) problemas += "$caso: la rechazada se reenvió"
        reenvios.forEach {
            val cuerpo = JSONObject(it)
            if (cuerpo.getString("externalId") != JSONObject(enLinea!!).getString("externalId") || !cuerpo.getBoolean("isOfflineReplay")) {
                problemas += "$caso: el reenvío no lleva la misma llave como venta sin red"
            }
        }
        return problemas
    }

    @Test
    fun `D3 r2 la marca falla, el borrado saldria bien y la app muere antes de la copia - la venta no se pierde`() = runTest {
        assertEquals(emptyList<String>(), correrCaso(false, fallaMarca = true, fallaCopia = false, fallaBorrado = false, ocupado = false, muereAntesDe = "copia"))
    }

    @Test
    fun `D3 r2 la marca y la copia fallan con otra venta en el carrito - la venta no se pierde`() = runTest {
        assertEquals(emptyList<String>(), correrCaso(false, fallaMarca = true, fallaCopia = true, fallaBorrado = false, ocupado = true, muereAntesDe = null))
    }

    @Test
    fun `D3 r2 tabla completa - cualquier escritura fallida y la app muerta en cualquier punto dejan UNA venta`() = runTest {
        val problemas = mutableListOf<String>()
        val bools = listOf(false, true)
        for (desdeGuardado in bools) for (marca in bools) for (copia in bools) for (borrado in bools) for (ocupado in bools)
            for (muere in listOf("marca", "copia", "borrado", null)) {
                problemas += correrCaso(desdeGuardado, marca, copia, borrado, ocupado, muere)
            }
        assertEquals(emptyList<String>(), problemas)
    }

    @Test
    fun `r2-3 rechazo sin poder marcar ni borrar la fila - la venta no vuelve, el aviso lo dice y este proceso no la reenvia`() = runTest {
        val vm = createViewModel()
        vm.addProduct(cafe)
        vm.setSelectedCustomer("cli-1", "Ana")
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { enviados += secondArg<String>(); respuesta.await() }
        val resultado = async { vm.createPayLaterOrder("cli-1") }
        runCurrent()
        disco.discoSano = false
        respuesta.complete(OrderRepository.RespuestaCruda(409, "application/json", null, """{"message":"El precio cambió."}"""))

        assertEquals(ResultadoPagarDespues.Error("No se pudo guardar la cuenta en el aparato. Intenta de nuevo."), resultado.await())
        assertTrue("la venta sigue en la fila, no en el carrito", vm.cartState.value.isEmpty)
        assertEquals(PayLaterRow.Estado.EN_VUELO, fila.filas.value.single().estado)
        disco.discoSano = true
        fila.drenar()
        assertEquals(1, enviados.size)
    }

    @Test
    fun `r2-4 con premio la venta no sale del carrito - si falla, siguen el premio y el total con lo escaneado`() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        vm.setPendingStampReward(com.avoqado.pos.loyalty.data.PremioPorAplicar("rw1", "Descuento", "FIXED_AMOUNT", 30.0))
        val antes = vm.cartState.value.totalCents
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { enviados += secondArg<String>(); respuesta.await() }
        val resultado = async { vm.createPayLaterOrder("cli-1") }
        runCurrent()
        assertEquals("mientras se manda, la venta y su premio siguen en el carrito", listOf("Café"), vm.cartState.value.items.map { it.name })
        vm.addProduct(pan)
        respuesta.completeExceptionally(java.io.IOException("sin red"))

        assertEquals(ResultadoPagarDespues.Error("Sin conexión: el premio de sellos necesita internet. Quita el premio o espera la red."), resultado.await())
        assertEquals(listOf("Café", "Pan"), vm.cartState.value.items.map { it.name })
        assertEquals("rw1", vm.cartState.value.pendingStampRewardId)
        assertEquals(antes + 2000, vm.cartState.value.totalCents)
        assertEquals("Ana", vm.selectedCustomer.value?.name)
        assertTrue(fila.filas.value.isEmpty())
        assertTrue(disco.guardado.isEmpty())
    }

    @Test
    fun `r2-4 con premio, si la cuenta se crea sale sólo lo capturado y queda lo escaneado`() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        vm.setPendingStampReward(com.avoqado.pos.loyalty.data.PremioPorAplicar("rw1", "Descuento", "FIXED_AMOUNT", 30.0))
        val anunciado = vm.cartState.value.totalCents
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { respuesta.await() }
        val resultado = async { vm.createPayLaterOrder("cli-1") }
        runCurrent()
        vm.addProduct(pan)
        respuesta.complete(OrderRepository.RespuestaCruda(201, "application/json", null,
            """{"success":true,"order":{"id":"ord-1","total":${anunciado / 100.0},"stampReward":{"applied":true,"discountAmount":30.0}}}"""))

        assertEquals(ResultadoPagarDespues.Creada, resultado.await())
        assertEquals(listOf("Pan"), vm.cartState.value.items.map { it.name })
        assertNull(vm.cartState.value.pendingStampRewardId)
        assertNull(vm.selectedCustomer.value)
    }

    @Test
    fun `r2-5 con premio, si la cuenta se crea y su copia no se puede retirar, la copia no se vende ni confirmando`() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        vm.setPendingStampReward(com.avoqado.pos.loyalty.data.PremioPorAplicar("rw1", "Descuento", "FIXED_AMOUNT", 30.0))
        val anunciado = vm.cartState.value.totalCents
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { enviados += secondArg<String>(); respuesta.await() }
        val resultado = async { vm.createPayLaterOrder("cli-1") }
        runCurrent()
        copias.discoSano = false
        disco.discoSano = false
        respuesta.complete(OrderRepository.RespuestaCruda(201, "application/json", null,
            """{"success":true,"order":{"id":"ord-1","total":${anunciado / 100.0},"stampReward":{"applied":true,"discountAmount":30.0}}}"""))
        assertEquals(ResultadoPagarDespues.Creada, resultado.await())
        copias.discoSano = true
        disco.discoSano = true

        val copia = guardados.savedCarts.value.single()
        assertEquals(Restauracion.YaDejadaParaDespues, vm.restoreSavedCart(copia))
        assertEquals(Restauracion.YaDejadaParaDespues, vm.restoreSavedCart(copia, confirmado = true))
        fila.drenar()
        assertEquals(1, enviados.size)
    }

    @Test
    fun `r3 mientras se manda un Pagar despues con premio, cobrar, guardar y vaciar esa venta se detienen y lo dicen`() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        vm.setPendingStampReward(com.avoqado.pos.loyalty.data.PremioPorAplicar("rw1", "Descuento", "FIXED_AMOUNT", 30.0))
        val total = vm.cartState.value.totalCents
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { enviados += secondArg<String>(); respuesta.await() }
        val resultado = async { vm.createPayLaterOrder("cli-1") }
        runCurrent()

        // La entrada del cobro (botón Cobrar y F12 en WINDOWS), de Dividir y de los atajos.
        assertTrue(vm.detenerPorPagarDespues())
        assertEquals("Se está dejando esta venta para pagar después: espera a que termine.", vm.avisoDeVales.value)
        assertFalse(vm.saveCurrentCart())
        vm.vaciar()

        assertEquals(listOf("Café"), vm.cartState.value.items.map { it.name })
        assertEquals("rw1", vm.cartState.value.pendingStampRewardId)
        assertEquals(total, vm.cartState.value.totalCents)
        assertEquals("sólo la copia de antes", 1, guardados.savedCarts.value.size)
        coVerify(exactly = 0) { ordenes.createOrder(any(), any(), any(), any(), any(), any(), any(), any()) }

        respuesta.completeExceptionally(java.io.IOException("sin red"))
        resultado.await()
        assertFalse("al terminar, ya se puede cobrar", vm.detenerPorPagarDespues())
    }

    /** D10: una venta de Ana con premio cuyo «Pagar después» se queda esperando al SERVIDOR hasta [respuesta]. */
    private fun kotlinx.coroutines.test.TestScope.ventaConPremioMandandose(
        vm: CartViewModel,
        respuesta: CompletableDeferred<OrderRepository.RespuestaCruda>,
    ): kotlinx.coroutines.Deferred<ResultadoPagarDespues> {
        ventaDeAna(vm)
        vm.setPendingStampReward(com.avoqado.pos.loyalty.data.PremioPorAplicar("rw1", "Descuento", "FIXED_AMOUNT", 30.0))
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { enviados += secondArg<String>(); respuesta.await() }
        return async { vm.createPayLaterOrder("cli-1") }.also { runCurrent() }
    }

    @Test
    fun `D10 mientras se manda un Pagar despues con premio, un vale escaneado no entra ni se aparta`() = runTest {
        // La venta con premio se queda en el carrito mientras espera al SERVIDOR y al terminar sale conservando lo que entró:
        // un vale leído ahí pasaba a la venta siguiente con su apartado a nombre de la anterior (Codex, merge iOS r3 #2).
        val vm = createViewModel()
        coEvery { vales.resolveCheckoutScan("9470000015") } returns
            com.avoqado.pos.areatickets.data.AreaTicketScanData(type = "AREA_TICKET", code = "9470000015")
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        val resultado = ventaConPremioMandandose(vm, respuesta)

        val leido = vm.resolveScannedBarcode("9470000015")

        assertEquals(ScannedBarcodeResult.Error(PAGAR_DESPUES_EN_CURSO), leido)
        coVerify(exactly = 0) { vales.addTicket(any()) }
        assertEquals(listOf("Café"), vm.cartState.value.items.map { it.name })

        respuesta.completeExceptionally(java.io.IOException("sin red"))
        resultado.await()
    }

    @Test
    fun `D10 un producto con codigo de forma de vale si entra mientras se manda`() = runTest {
        // Codex D10 r1 #2: la forma del código no dice que sea un vale; el SERVIDOR también busca productos por SKU.
        val gomitas = Product(id = "p-gomitas", name = "Gomitas", priceValue = 15.0, sku = "9470000015")
        every { productsRepository.products } returns MutableStateFlow(listOf(gomitas))
        val vm = createViewModel()
        coEvery { vales.resolveCheckoutScan("9470000015") } returns
            com.avoqado.pos.areatickets.data.AreaTicketScanData(type = "PRODUCT", code = "9470000015")
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        val resultado = ventaConPremioMandandose(vm, respuesta)

        assertEquals(ScannedBarcodeResult.ProductFound(gomitas), vm.resolveScannedBarcode("9470000015"))

        respuesta.completeExceptionally(java.io.IOException("sin red"))
        resultado.await()
    }

    @Test
    fun `D10 los vales de un apartado restaurado no entran a la venta mientras se manda`() = runTest {
        // Codex D10 r1 #1: la restauración al entrar a Cobrar no pasa por el escaneo; si respondía durante el envío, sus vales
        // se proyectaban en la venta que se está yendo. Se quedan apartados y siguen las reglas de siempre (regla 8).
        val sesion = com.avoqado.pos.areatickets.data.AreaTicketSession(secureStorage)
        every { vales.session } returns sesion
        val vm = createViewModel()
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        val resultado = ventaConPremioMandandose(vm, respuesta)
        sesion.update("venue-1", apartadoConUnVale())

        vm.traerValesDeLaSesion()

        assertEquals(listOf("Café"), vm.cartState.value.items.map { it.name })

        respuesta.completeExceptionally(java.io.IOException("sin red"))
        resultado.await()
    }

    @Test
    fun `D10 el vale que ya se estaba apartando al tocar Continuar entra y Pagar despues lo rechaza como siempre`() = runTest {
        // Codex D10 r2: el freno se activaba al tocar «Continuar», antes de esperar los escaneos en curso; este vale ya no se
        // proyectaba y la cuenta se iba sin él. El freno va desde la foto de la venta: lo leído antes entra y se rechaza la venta.
        val sesion = com.avoqado.pos.areatickets.data.AreaTicketSession(secureStorage)
        every { vales.session } returns sesion
        coEvery { vales.settings() } returns com.avoqado.pos.areatickets.data.AreaTicketSettingsData(
            venueId = "venue-1",
            areaTickets = com.avoqado.pos.areatickets.data.AreaTicketModuleSettings(entitled = true, enabled = true, allowMixedCart = true),
            terminal = com.avoqado.pos.areatickets.data.AreaTicketTerminalCapabilities(id = "t1", name = "Caja"),
            scaleIntegration = com.avoqado.pos.areatickets.data.ScaleIntegrationSettings(entitled = false, enabled = false),
        )
        coEvery { vales.resolveCheckoutScan("9470000015") } returns
            com.avoqado.pos.areatickets.data.AreaTicketScanData(type = "AREA_TICKET", code = "9470000015")
        val apartando = CompletableDeferred<Unit>()
        coEvery { vales.addTicket("9470000015") } coAnswers {
            apartando.await()
            apartadoConUnVale().also { sesion.update("venue-1", it) }
        }
        val vm = createViewModel()
        ventaDeAna(vm)
        val escaneo = async { vm.resolveScannedBarcode("9470000015") }
        runCurrent()
        val resultado = async { vm.createPayLaterOrder("cli-1") }
        runCurrent()

        apartando.complete(Unit)

        assertEquals(ScannedBarcodeResult.AreaTicketsAdded(1), escaneo.await())
        assertEquals(ResultadoPagarDespues.Error(VALES_NO_PAGAR_DESPUES), resultado.await())
        assertEquals(listOf("Café", "Helado"), vm.cartState.value.items.map { it.name })
        assertTrue("nada se mandó", enviados.isEmpty())
    }

    private fun apartadoConUnVale() = com.avoqado.pos.areatickets.data.AreaTicketCheckout(
        id = "apartado-1", status = "OPEN", version = 1, expiresAt = "2999-01-01T00:00:00Z", createdAt = "2026-10-28T10:00:00Z",
        tickets = listOf(
            com.avoqado.pos.areatickets.data.AreaTicket(
                id = "t1", code = "9470000015", status = "OPEN",
                fulfillmentArea = com.avoqado.pos.areatickets.data.AreaTicketArea("area-1", "Barra", "PICKUP"),
                subtotal = "50.00", total = "50.00", issuedAt = "2026-10-28T10:00:00Z",
                lines = listOf(
                    com.avoqado.pos.areatickets.data.AreaTicketLine(
                        id = "l1", clientLineId = "c1", productNameSnapshot = "Helado", quantity = "1", unitPrice = "50.00", total = "50.00",
                    ),
                ),
            ),
        ),
        totals = com.avoqado.pos.areatickets.data.AreaTicketCheckoutTotals("50.00", "0.00", "50.00"),
    )

    @Test
    fun `A-4 la venta por recuperar pasa sola a Carritos guardados con su llave, sin mandar nada`() = runTest {
        val vm = createViewModel()
        vm.addProduct(cafe)
        vm.setSelectedCustomer("cli-1", "Ana")
        val respuesta = CompletableDeferred<OrderRepository.RespuestaCruda>()
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers { enviados += secondArg<String>(); respuesta.await() }
        val resultado = async { vm.createPayLaterOrder("cli-1") }
        runCurrent()
        vm.addProduct(pan)
        copias.discoSano = false
        respuesta.complete(OrderRepository.RespuestaCruda(409, "application/json", null, """{"message":"El precio cambió."}"""))
        resultado.await()
        val llave = fila.filas.value.single().id

        fila.mantenimientoLocal()
        assertEquals("sin disco sigue por recuperar", PayLaterRow.Estado.RECUPERAR, fila.filas.value.single().estado)
        copias.discoSano = true
        fila.mantenimientoLocal()

        assertTrue(fila.filas.value.isEmpty())
        val deAna = guardados.savedCarts.value.single()
        assertEquals(listOf("Café"), deAna.items.map { it.name })
        assertEquals("cli-1", deAna.attachedCustomerId)
        assertEquals(llave, deAna.llavePagarDespues)
        assertEquals(1, enviados.size)
    }

    @Test
    fun `A-5 si la llave no se puede escribir en la copia, no se sigue`() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        copias.discoSano = false

        assertEquals(ResultadoPagarDespues.Error("No se pudo guardar la cuenta en el aparato. Intenta de nuevo."), vm.createPayLaterOrder("cli-1"))

        assertEquals(listOf("Café"), vm.cartState.value.items.map { it.name })
        coVerify(exactly = 0) { ordenes.enviarPagarDespues(any(), any()) }
        assertTrue(fila.filas.value.isEmpty())
    }

    @Test
    fun `A-7 con premio, una cuenta por otro importe tambien se dice`() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        vm.setPendingStampReward(com.avoqado.pos.loyalty.data.PremioPorAplicar("rw1", "Descuento", "FIXED_AMOUNT", 30.0))
        val anunciado = vm.cartState.value.totalCents
        responde(201, """{"success":true,"order":{"id":"ord-1","total":80.0,"stampReward":{"applied":true,"discountAmount":30.0}}}""")

        val resultado = vm.createPayLaterOrder("cli-1")

        assertEquals(ResultadoPagarDespues.Aviso(pagarDespuesOtroImporte("Ana", 8000, anunciado)), resultado)
        assertTrue(fila.filas.value.isEmpty())
    }

    @Test
    fun `los paquetes no se dejan para despues`() = runTest {
        val vm = createViewModel()
        vm.addCreditPack(com.avoqado.pos.articles.data.model.CreditPack(id = "pack-1", name = "10 clases", price = 500.0))
        vm.addProduct(cafe)
        vm.setSelectedCustomer("cli-1", "Ana")

        val resultado = vm.createPayLaterOrder("cli-1")

        assertEquals(
            ResultadoPagarDespues.Error("Los paquetes se cobran en el momento: no se pueden dejar para pagar después."),
            resultado,
        )
        assertEquals(2, vm.cartState.value.items.size)
        coVerify(exactly = 0) { ordenes.enviarPagarDespues(any(), any()) }
        assertNotNull(vm.selectedCustomer.value)
    }

    // ── Tarjetas de regalo 1b sobre el «Pagar después» nuevo (unión con develop, 28-oct) ──

    /** Una venta de tarjeta que espera recuperar su entrega frena guardar, vaciar y diferir (1b, R4 del cobro con saldo). */
    private fun entregaDeTarjetaSinConfirmar() {
        val solicitud = com.avoqado.pos.giftcards.domain.SolicitudDeTarjeta(tipo = "GIFT_CARD_REDEEM", llave = "entrega",
            venueId = "venue-1", creadaEn = 1, cuerpo = "", nuevaCuenta = true, porRecuperar = true, foto = "{}")
        val raw = com.avoqado.pos.giftcards.domain.SolicitudesDeTarjeta.reservarEntrega(
            com.avoqado.pos.giftcards.domain.SolicitudesDeTarjeta.agregar(null, solicitud), "entrega")
        assertNotNull(raw)
        every { secureStorage.giftCardRequestsJson } returns raw
    }

    @Test
    fun `tarjetas - con una entrega de tarjeta sin confirmar, Pagar despues no manda ni saca la venta del carrito`() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        entregaDeTarjetaSinConfirmar()
        responde(201, creada(100.0))
        val antes = vm.cartState.value

        val resultado = vm.createPayLaterOrder("cli-1")

        assertEquals(ResultadoPagarDespues.Error(com.avoqado.pos.giftcards.domain.TextosDeTarjetas.NO_SE_PUDO_RECUPERAR), resultado)
        assertTrue(enviados.isEmpty())
        assertTrue(disco.guardado.isEmpty())
        assertEquals(antes, vm.cartState.value)
    }

    @Test
    fun `tarjetas - con premio y sin respuesta, la venta que se queda en el carrito queda marcada desde antes de mandarse`() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        vm.setPendingStampReward(com.avoqado.pos.loyalty.data.PremioPorAplicar("rw1", "Café gratis", "FIXED_AMOUNT", 30.0))
        var marcadaAlMandar: String? = null
        coEvery { ordenes.enviarPagarDespues(any(), any()) } answers {
            marcadaAlMandar = vm.cartState.value.creacionIncierta
            throw java.net.SocketTimeoutException("sin respuesta")
        }

        assertTrue(vm.createPayLaterOrder("cli-1") is ResultadoPagarDespues.Error)

        val llave = vm.cartState.value.llavePagarDespues
        assertNotNull(llave)
        assertEquals("se marca ANTES de mandar", llave, marcadaAlMandar)
        assertEquals("sin saber si se creó, el saldo de tarjeta no se ofrece para esta venta", llave, vm.cartState.value.creacionIncierta)
        assertEquals(listOf("Café"), vm.cartState.value.items.map { it.name })
    }

    @Test
    fun `tarjetas - con premio y rechazo definitivo, la marca se limpia`() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        vm.setPendingStampReward(com.avoqado.pos.loyalty.data.PremioPorAplicar("rw1", "Café gratis", "FIXED_AMOUNT", 30.0))
        responde(404, """{"message":"Cliente no encontrado"}""")

        assertEquals(ResultadoPagarDespues.Error("Cliente no encontrado"), vm.createPayLaterOrder("cli-1"))

        assertEquals(1, enviados.size)
        assertNull(vm.cartState.value.creacionIncierta)
        assertEquals(listOf("Café"), vm.cartState.value.items.map { it.name })
    }

    @Test
    fun `tarjetas - sin premio la venta sale del carrito antes de mandarse y el carrito no queda marcado`() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        sinRed()

        vm.createPayLaterOrder("cli-1")

        assertTrue(vm.cartState.value.isEmpty)
        assertNull(vm.cartState.value.creacionIncierta)
    }
}
