package com.avoqado.pos.pos.presentation.cart

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.payment.PrefsEnMemoria
import com.avoqado.pos.payment.data.CortesiaPersistida
import com.avoqado.pos.payment.data.detalleDePagarDespues
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
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

    private fun createViewModel(repository: OrderRepository = ordenes, queue: PayLaterQueue = fila): CartViewModel = CartViewModel(
        productsRepository = productsRepository,
        discountsRepository = discountsRepository,
        savedCartsRepository = guardados,
        authRepository = authRepository,
        secureStorage = secureStorage,
        activeCartState = mockk<ActiveCartState>(relaxed = true),
        orderRepository = repository,
        staffRepository = staffRepository,
        classCheckoutSeed = mockk<ClassCheckoutSeed>(relaxed = true).also { every { it.consume() } returns null },
        validateReferralUseCase = mockk(relaxed = true),
        captureReferralUseCase = mockk(relaxed = true),
        planManager = PlanManager(secureStorage),
        tableSession = com.avoqado.pos.tables.data.TableSession(),
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
        areaTicketRepository = vales,
        walletScanRepository = mockk(relaxed = true),
        payLaterQueue = queue,
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

    // Task10 F3: the active writer must retain original comp evidence, not only compare the total.
    private enum class RespuestaCortesia { CANONICA, SIN_ITEMS, MOTIVO_CAMBIADO }

    /** Only the HTTP transport is replaced; both writers and the response parser remain production code. */
    private inner class CortesiaActiva(val conPremio: Boolean = false) : AutoCloseable {
        var respuesta = RespuestaCortesia.CANONICA
        var sinConexion = false
        val enviados = mutableListOf<String>()
        var filasAlEnviar = emptyList<PayLaterRow>()
        var carritoAlEnviar: CartState? = null
        var copiaAlEnviar: com.avoqado.pos.pos.data.model.SavedCart? = null
        private val http = OkHttpClient.Builder().addInterceptor { chain -> contestar(chain.request()) }.build()
        val repository = OrderRepository(secureStorage, http).apply { baseUrl = "http://127.0.0.1:9/api/v1" }
        val queue = PayLaterQueue(disco.context, repository, secureStorage, guardados, mockk(relaxed = true))
            .apply { io = Dispatchers.Unconfined }
        val vm = createViewModel(repository, queue)
        val totalCents = if (conPremio) 1500 else 2000
        var totalCanonicoCents: Int? = totalCents
        var antesDeContestar: (() -> Unit)? = null
        val lineaCortesia: String

        init {
            vm.addProduct(cafe)
            lineaCortesia = vm.cartState.value.items.single().id
            vm.markItemAsCortesia(lineaCortesia, "Invitación")
            vm.addProduct(pan)
            assertTrue(vm.saveCurrentCart("Cortesía original de Ana"))
            vm.restoreSavedCart(guardados.savedCarts.value.single())
            vm.setSelectedCustomer("cli-1", "Ana")
            if (conPremio) assertTrue(vm.setPendingStampReward(
                com.avoqado.pos.loyalty.data.PremioPorAplicar("rw1", "Descuento", "FIXED_AMOUNT", 5.0)))
            assertEquals(totalCents, vm.cartState.value.totalCents)
            assertEquals(lineaCortesia, vm.cartState.value.items.single { it.isCortesia }.id)
        }

        private fun contestar(request: Request): Response {
            val raw = Buffer().also { requireNotNull(request.body).writeTo(it) }.readUtf8()
            enviados += raw
            filasAlEnviar = queue.filas.value
            carritoAlEnviar = vm.cartState.value
            copiaAlEnviar = guardados.savedCarts.value.singleOrNull()
            if (sinConexion) throw java.io.IOException("sin red")
            antesDeContestar?.invoke()
            val order = JSONObject().put("id", "ord-1")
            totalCanonicoCents?.let { order.put("total", it / 100.0) }
            if (respuesta != RespuestaCortesia.SIN_ITEMS) {
                order.put("items", JSONArray().put(JSONObject().put("externalId", lineaCortesia)
                    .put("isCortesia", true)
                    .put("cortesiaReason", if (respuesta == RespuestaCortesia.MOTIVO_CAMBIADO) "Otro motivo" else "Invitación")))
            }
            if (conPremio) order.put("stampReward", JSONObject().put("applied", true).put("discountAmount", 5.0))
            return Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(201).message("Created")
                .body(JSONObject().put("success", true).put("order", order).toString()
                    .toResponseBody("application/json".toMediaType())).build()
        }

        fun comprobarCaptura(body: String) {
            val request = JSONObject(body)
            assertEquals(totalCents, request.getInt("total"))
            assertEquals("PAY_LATER", request.getString("paymentMethod"))
            val items = request.getJSONArray("items")
            val comp = (0 until items.length()).map { items.getJSONObject(it) }.single { it.optBoolean("isCortesia") }
            assertEquals(lineaCortesia, comp.getString("externalId"))
            assertEquals("Invitación", comp.getString("cortesiaReason"))
        }

        suspend fun comprobarFuente(original: PayLaterRow) {
            assertEquals("The original queue key must remain available for review", listOf(original.id), queue.filas.value.map { it.id })
            val actual = queue.filas.value.single()
            // Resolution metadata may change; every captured source field must remain byte exact.
            assertEquals(original, actual.copy(estado = original.estado, intentos = original.intentos,
                motivo = original.motivo, orderId = original.orderId, totalRegistradoCents = original.totalRegistradoCents))
            assertTrue("An existing account with unconfirmed comp evidence needs human review", actual.paraRevisar)
            assertEquals(1, queue.porRevisar.first())
            assertEquals("ord-1", actual.orderId)
            assertEquals("${CortesiaPersistida.AVISO} Folio: ord-1.", actual.motivo)
            assertEquals(actual.motivo, detalleDePagarDespues(actual))
        }

        override fun close() {
            vm.viewModelScope.cancel()
            http.connectionPool.evictAll()
            http.dispatcher.executorService.shutdown()
        }
    }

    private suspend fun cortesiaOnlineSinPrueba(respuesta: RespuestaCortesia) {
        CortesiaActiva().use { f ->
            f.respuesta = respuesta
            val resultado = f.vm.createPayLaterOrder("cli-1")
            val original = f.filasAlEnviar.single()
            f.comprobarCaptura(original.cuerpo)
            assertEquals(original.id, JSONObject(f.enviados.single()).getString("externalId"))
            f.comprobarFuente(original)
            assertFalse("A matching total alone must not report the original comp as created", resultado == ResultadoPagarDespues.Creada)
        }
    }

    private suspend fun cortesiaReplaySinPrueba(respuesta: RespuestaCortesia) {
        CortesiaActiva().use { f ->
            f.sinConexion = true
            assertTrue(f.vm.createPayLaterOrder("cli-1") is ResultadoPagarDespues.Aviso)
            val original = f.queue.filas.value.single()
            f.comprobarCaptura(original.cuerpo)
            assertEquals(PayLaterRow.Estado.PENDIENTE, original.estado)
            assertTrue(JSONObject(original.cuerpo).getBoolean("isOfflineReplay"))
            f.sinConexion = false; f.respuesta = respuesta
            f.queue.drenar()
            assertEquals(2, f.enviados.size)
            assertEquals(original.cuerpo, f.enviados.last())
            f.comprobarFuente(original)
        }
    }

    private suspend fun cortesiaPremioSinPrueba(respuesta: RespuestaCortesia) {
        CortesiaActiva(conPremio = true).use { f ->
            f.respuesta = respuesta
            val resultado = f.vm.createPayLaterOrder("cli-1")
            f.comprobarCaptura(f.enviados.single())
            assertEquals("rw1", JSONObject(f.enviados.single()).getString("stampRewardId"))
            assertTrue("Rewards never enter the replay queue", f.filasAlEnviar.isEmpty())
            assertTrue(f.queue.filas.value.isEmpty())
            assertTrue(disco.enDisco.isEmpty())
            assertEquals("Original reward sale must remain when its comp is not confirmed", f.carritoAlEnviar, f.vm.cartState.value)
            assertEquals(listOf(requireNotNull(f.copiaAlEnviar)), guardados.savedCarts.value)
            assertEquals(JSONObject(f.enviados.single()).getString("externalId"), f.vm.cartState.value.llavePagarDespues)
            assertFalse(resultado == ResultadoPagarDespues.Creada)
        }
    }

    @Test fun F3_online_con_total_igual_sin_items_conserva_fuente() = runTest { cortesiaOnlineSinPrueba(RespuestaCortesia.SIN_ITEMS) }
    @Test fun F3_online_con_total_igual_motivo_cambiado_conserva_fuente() = runTest { cortesiaOnlineSinPrueba(RespuestaCortesia.MOTIVO_CAMBIADO) }
    @Test fun F3_replay_con_total_igual_sin_items_conserva_fuente() = runTest { cortesiaReplaySinPrueba(RespuestaCortesia.SIN_ITEMS) }
    @Test fun F3_replay_con_total_igual_motivo_cambiado_conserva_fuente() = runTest { cortesiaReplaySinPrueba(RespuestaCortesia.MOTIVO_CAMBIADO) }
    @Test fun F3_premio_con_total_igual_sin_items_conserva_carrito_y_copia() = runTest { cortesiaPremioSinPrueba(RespuestaCortesia.SIN_ITEMS) }
    @Test fun F3_premio_con_total_igual_motivo_cambiado_conserva_carrito_y_copia() = runTest { cortesiaPremioSinPrueba(RespuestaCortesia.MOTIVO_CAMBIADO) }

    @Test fun F3_online_canonica_conserva_la_politica_de_aceptacion() = runTest {
        CortesiaActiva().use { f ->
            assertEquals(ResultadoPagarDespues.Creada, f.vm.createPayLaterOrder("cli-1"))
            f.comprobarCaptura(f.enviados.single())
            assertTrue(f.queue.filas.value.isEmpty()); assertTrue(guardados.savedCarts.value.isEmpty())
            assertTrue(f.vm.cartState.value.isEmpty)
        }
    }

    @Test fun F3_replay_canonica_retira_solo_la_misma_llave() = runTest {
        CortesiaActiva().use { f ->
            f.sinConexion = true
            assertTrue(f.vm.createPayLaterOrder("cli-1") is ResultadoPagarDespues.Aviso)
            val original = f.queue.filas.value.single()
            f.comprobarCaptura(original.cuerpo)
            val foreign = original.copy(id = "pay-later:foreign", venueId = "venue-other")
            assertTrue(f.queue.guardar(foreign))
            f.sinConexion = false
            f.queue.drenar()
            assertEquals(2, f.enviados.size)
            assertEquals(original.cuerpo, f.enviados.last())
            assertEquals(listOf(foreign), f.queue.filas.value); assertTrue(guardados.savedCarts.value.isEmpty())
        }
    }

    @Test fun F3_premio_canonica_no_encola_y_retira_fuente_y_premio_confirmados() = runTest {
        CortesiaActiva(conPremio = true).use { f ->
            assertEquals(ResultadoPagarDespues.Creada, f.vm.createPayLaterOrder("cli-1"))
            f.comprobarCaptura(f.enviados.single())
            assertTrue(f.filasAlEnviar.isEmpty()); assertTrue(f.queue.filas.value.isEmpty()); assertTrue(disco.enDisco.isEmpty())
            assertTrue(guardados.savedCarts.value.isEmpty()); assertTrue(f.vm.cartState.value.isEmpty)
            assertNull(f.vm.cartState.value.pendingStampRewardId); assertNull(f.vm.selectedCustomer.value)
            assertNull(f.vm.avisoDelPremio.value)
        }
    }

    private suspend fun cortesiaTotalSinPrueba(total: Int?, replay: Boolean = false, premio: Boolean = false) {
        CortesiaActiva(conPremio = premio).use { f ->
            if (replay) {
                f.sinConexion = true
                assertTrue(f.vm.createPayLaterOrder("cli-1") is ResultadoPagarDespues.Aviso)
                f.sinConexion = false
            }
            f.totalCanonicoCents = total
            if (replay) {
                val original = f.queue.filas.value.single()
                f.queue.drenar()
                f.comprobarFuente(original)
            } else {
                val resultado = f.vm.createPayLaterOrder("cli-1")
                if (premio) {
                    assertEquals(ResultadoPagarDespues.Error("${CortesiaPersistida.AVISO} Folio: ord-1."), resultado)
                    assertEquals(f.carritoAlEnviar, f.vm.cartState.value)
                    assertEquals(listOf(requireNotNull(f.copiaAlEnviar)), guardados.savedCarts.value)
                    assertTrue(f.queue.filas.value.isEmpty()); assertTrue(disco.enDisco.isEmpty())
                } else {
                    f.comprobarFuente(f.filasAlEnviar.single())
                    assertTrue(resultado is ResultadoPagarDespues.Aviso)
                }
            }
        }
    }

    @Test fun F3_online_canonica_mayor_no_autovalida_el_total() = runTest { cortesiaTotalSinPrueba(2100) }
    @Test fun F3_online_canonica_sin_total_conserva_fuente() = runTest { cortesiaTotalSinPrueba(null) }
    @Test fun F3_replay_canonica_mayor_conserva_fuente() = runTest { cortesiaTotalSinPrueba(2100, replay = true) }
    @Test fun F3_replay_canonica_sin_total_conserva_fuente() = runTest { cortesiaTotalSinPrueba(null, replay = true) }
    @Test fun F3_premio_canonica_mayor_conserva_carrito() = runTest { cortesiaTotalSinPrueba(1600, premio = true) }
    @Test fun F3_premio_canonica_sin_total_conserva_carrito() = runTest { cortesiaTotalSinPrueba(null, premio = true) }

    @Test fun F3_online_canonica_menor_conserva_el_aviso_anterior() = runTest {
        CortesiaActiva().use { f ->
            f.totalCanonicoCents = 1900
            assertEquals(ResultadoPagarDespues.Aviso(pagarDespuesOtroImporte("Ana", 1900, 2000)), f.vm.createPayLaterOrder("cli-1"))
            assertTrue(f.queue.filas.value.isEmpty()); assertTrue(guardados.savedCarts.value.isEmpty())
        }
    }

    @Test fun F3_replay_canonica_menor_conserva_revision_de_importe() = runTest {
        CortesiaActiva().use { f ->
            f.sinConexion = true
            assertTrue(f.vm.createPayLaterOrder("cli-1") is ResultadoPagarDespues.Aviso)
            val original = f.queue.filas.value.single()
            f.sinConexion = false; f.totalCanonicoCents = 1900
            f.queue.drenar()
            val actual = f.queue.filas.value.single()
            assertEquals(original.cuerpo, actual.cuerpo); assertEquals(original.id, actual.id)
            assertEquals(PayLaterRow.Estado.REVISAR, actual.estado)
            assertEquals(1900, actual.totalRegistradoCents); assertNull(actual.motivo)
            assertEquals("Se registró por \$19.00, no por \$20.00", detalleDePagarDespues(actual))
        }
    }

    @Test fun F3_premio_canonica_menor_conserva_el_aviso_anterior_y_no_encola() = runTest {
        CortesiaActiva(conPremio = true).use { f ->
            f.totalCanonicoCents = 1400
            assertEquals(ResultadoPagarDespues.Aviso(pagarDespuesOtroImporte("Ana", 1400, 1500)), f.vm.createPayLaterOrder("cli-1"))
            assertTrue(f.queue.filas.value.isEmpty()); assertTrue(guardados.savedCarts.value.isEmpty())
            assertTrue(disco.enDisco.isEmpty()); assertTrue(f.vm.cartState.value.isEmpty)
        }
    }

    @Test fun F3_contexto_nuevo_no_cambia_el_esperado_ni_la_sucursal_de_la_fuente_capturada() = runTest {
        CortesiaActiva().use { f ->
            f.totalCanonicoCents = 2500 // Mayor que la captura original de 2000, aunque la venta nueva muestra 10000.
            f.antesDeContestar = {
                every { secureStorage.venueId } returns "venue-otra"
                f.queue.alCambiarLaSesion()
                f.vm.addProduct(cafe)
            }
            assertTrue(f.vm.createPayLaterOrder("cli-1") is ResultadoPagarDespues.Aviso)
            val original = f.filasAlEnviar.single(); val actual = f.queue.filas.value.single()
            assertEquals(original.cuerpo, actual.cuerpo); assertEquals(original.id, actual.id)
            assertEquals("venue-1", actual.venueId); assertEquals(2000, actual.totalEsperadoCents)
            assertEquals(PayLaterRow.Estado.REVISAR, actual.estado)
            assertEquals(0, f.queue.porRevisar.first())
            assertEquals(10000, f.vm.cartState.value.totalCents)
        }
    }

    @Test fun current_PAY_LATER_preserves_captured_type_actor_and_identical_replay_body() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        vm.setOrderType("TAKEOUT")
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers {
            enviados += secondArg<String>()
            assertEquals("venue-1", firstArg<String>())
            every { secureStorage.venueId } returns "venue-2"
            every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-2"
            vm.setOrderType("DELIVERY")
            throw java.io.IOException("sin red")
        }
        assertTrue(vm.createPayLaterOrder("cli-1") is ResultadoPagarDespues.Aviso)
        val source = fila.filas.value.single()
        assertEquals("venue-1", source.venueId); assertEquals("staff-1", source.staffId)
        val online = JSONObject(enviados.single())
        assertEquals(source.id, online.getString("externalId"))
        assertEquals("staff-1", online.getString("staffId"))
        assertEquals("cli-1", online.getString("customerId"))
        assertEquals("PAY_LATER", online.getString("paymentMethod"))
        every { secureStorage.venueId } returns "venue-1"
        fila.alCambiarLaSesion()
        responde(201, creada(100.0))
        fila.drenar()
        assertEquals(2, enviados.size)
        assertEquals(source.cuerpo, enviados.last())
        assertEquals("staff-1", JSONObject(enviados.last()).getString("staffId"))
        assertEquals(source.id, JSONObject(enviados.last()).getString("externalId"))
        assertTrue(fila.filas.value.isEmpty())
        assertEquals("TAKEOUT", online.getString("orderType"))
        assertEquals("TAKEOUT", JSONObject(enviados.last()).getString("orderType"))
    }

    @Test fun current_PAY_LATER_reward_preserves_the_type_captured_before_the_response() = runTest {
        val vm = createViewModel()
        ventaDeAna(vm)
        vm.setOrderType("DELIVERY")
        assertTrue(vm.setPendingStampReward(com.avoqado.pos.loyalty.data.PremioPorAplicar("rw1", "Descuento", "FIXED_AMOUNT", 30.0)))
        coEvery { ordenes.enviarPagarDespues(any(), any()) } coAnswers {
            enviados += secondArg<String>()
            every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-2"
            vm.setOrderType("TAKEOUT")
            OrderRepository.RespuestaCruda(201, "application/json", null,
                """{"success":true,"order":{"id":"ord-1","total":70.0,"stampReward":{"applied":true,"discountAmount":30.0}}}""")
        }
        assertEquals(ResultadoPagarDespues.Creada, vm.createPayLaterOrder("cli-1"))
        assertTrue(fila.filas.value.isEmpty()); assertTrue(disco.enDisco.isEmpty())
        val captured = JSONObject(enviados.single())
        assertEquals("PAY_LATER", captured.getString("paymentMethod"))
        assertEquals("staff-1", captured.getString("staffId"))
        assertEquals("rw1", captured.getString("stampRewardId"))
        assertEquals("DELIVERY", captured.getString("orderType"))
    }
}
