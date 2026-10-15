package com.avoqado.pos.pos.presentation.cart

import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.model.SavedCart
import com.avoqado.pos.pos.data.model.SavedCartItem
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.advanceUntilIdle

/** Plan 7b (respuestas 9 y 12; r1 #7, r2 R2-1/R2-2, r3 R3-2/R3-4): la copia guardada nunca se pierde ni se cobra dos veces en silencio. */
class CarritoGuardadoDurableTest {
    @get:org.junit.Rule
    val mainDispatcherRule = com.avoqado.pos.MainDispatcherRule()

    private fun createViewModel(repo: SavedCartsRepository): CartViewModel =
        CartPromotionTest().apply { setup() }.createViewModel(repo)

    private fun raizDelRepo(): java.io.File = generateSequence(java.io.File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .first { java.io.File(it, "app/src/main/java").isDirectory }

    private fun carrito(id: String = "s1", cantidad: Int = 1) = SavedCart(id = id, name = "Mesa 5",
        items = listOf(SavedCartItem(productId = "p-cafe", name = "Café", unitPrice = 5000, quantity = cantidad)))

    @Test
    fun `P1 restaurar y matar la app - la copia sigue en disco`() {
        val prefs = PrefsDeDosCapas()
        val repo = SavedCartsRepository(prefs.context)
        assertTrue(repo.saveCart(carrito()))
        createViewModel(repo).restoreSavedCart(repo.savedCarts.value.single())
        assertEquals(listOf("s1"), SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.map { it.id })
    }

    @Test
    fun `P1 re-guardar reemplaza bajo el mismo id - una sola copia, la nueva`() {
        val prefs = PrefsDeDosCapas(); val repo = SavedCartsRepository(prefs.context)
        repo.saveCart(carrito())
        val vm = createViewModel(repo)
        vm.restoreSavedCart(repo.savedCarts.value.single())
        vm.addCustomAmount(name = "Agua", amountCents = 2500)
        assertTrue(vm.saveCurrentCart())
        val trasReiniciar = SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value
        assertEquals(listOf("s1"), trasReiniciar.map { it.id })
        assertEquals(2, trasReiniciar.single().items.size)
    }

    @Test
    fun `P1 si el disco no confirma el guardado, el carrito sigue en pantalla y la lista no cambia`() {
        val prefs = PrefsDeDosCapas(); val repo = SavedCartsRepository(prefs.context)
        repo.saveCart(carrito())
        prefs.commitFalla = true
        val vm = createViewModel(repo)
        vm.restoreSavedCart(repo.savedCarts.value.single())
        vm.addCustomAmount(name = "Agua", amountCents = 2500)
        assertFalse(vm.saveCurrentCart())
        assertEquals(7500, vm.cartState.value.totalCents)
        assertEquals(1, repo.savedCarts.value.single().items.size)
    }

    @Test
    fun `P1 antes de cobrar se marca en disco - si muere entre el cobro y el retiro, reaparece MARCADA y pide confirmacion`() {
        val prefs = PrefsDeDosCapas(); val repo = SavedCartsRepository(prefs.context)
        repo.saveCart(carrito())
        val vm = createViewModel(repo)
        vm.restoreSavedCart(repo.savedCarts.value.single())
        assertTrue(vm.prepararCobroDelCarritoGuardado())
        // el cobro se registró… y el proceso muere antes de aplicarCobroConfirmado
        val tras = SavedCartsRepository(prefs.reiniciarProceso().context)
        val copia = tras.savedCarts.value.single()
        assertNotNull(copia.cobroIniciadoEn)
        assertTrue(createViewModel(tras).restoreSavedCart(copia) is Restauracion.PideConfirmacion)
    }

    @Test
    fun `P1 si el disco no confirma la marca, el cobro no arranca`() {
        val prefs = PrefsDeDosCapas(); val repo = SavedCartsRepository(prefs.context)
        repo.saveCart(carrito())
        val vm = createViewModel(repo)
        vm.restoreSavedCart(repo.savedCarts.value.single())
        prefs.commitFalla = true
        assertFalse(vm.prepararCobroDelCarritoGuardado())
    }

    @Test
    fun `P1 el primer cobro confirmado la retira aunque sea parcial y un segundo parcial no hace nada (R2-2)`() {
        val prefs = PrefsDeDosCapas(); val repo = SavedCartsRepository(prefs.context)
        repo.saveCart(carrito())
        val vm = createViewModel(repo)
        vm.restoreSavedCart(repo.savedCarts.value.single())
        vm.prepararCobroDelCarritoGuardado()
        vm.aplicarCobroConfirmado(splitType = "CUSTOMAMOUNT", paidItemIds = emptySet(), remainingBalanceCents = 2500)
        assertTrue(SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.isEmpty())
        vm.aplicarCobroConfirmado(splitType = "CUSTOMAMOUNT", paidItemIds = emptySet(), remainingBalanceCents = 0)
    }

    @Test
    fun `P1 por producto con saldo 0 y pago completo tambien la retiran`() {
        for (rama in listOf("BYPRODUCT", "FULLPAYMENT")) {
            val prefs = PrefsDeDosCapas(); val repo = SavedCartsRepository(prefs.context)
            repo.saveCart(carrito())
            val vm = createViewModel(repo)
            vm.restoreSavedCart(repo.savedCarts.value.single())
            vm.prepararCobroDelCarritoGuardado()
            val pagados = if (rama == "BYPRODUCT") setOf(vm.cartState.value.items.single().id) else emptySet()
            vm.aplicarCobroConfirmado(splitType = rama, paidItemIds = pagados, remainingBalanceCents = 0)
            assertTrue(rama, SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.isEmpty())
        }
    }

    @Test
    fun `P1 si el disco no confirma el retiro, la copia queda MARCADA en disco - nunca limpia`() {
        val prefs = PrefsDeDosCapas(); val repo = SavedCartsRepository(prefs.context)
        repo.saveCart(carrito())
        val vm = createViewModel(repo)
        vm.restoreSavedCart(repo.savedCarts.value.single())
        vm.prepararCobroDelCarritoGuardado()
        prefs.commitFalla = true
        vm.aplicarCobroConfirmado(splitType = "FULLPAYMENT", paidItemIds = emptySet(), remainingBalanceCents = 0)
        assertNotNull(SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.single().cobroIniciadoEn)
    }

    @Test
    fun `P1 el retiro usa commit - con apply se perderia al morir el proceso`() {
        val prefs = PrefsDeDosCapas(); val repo = SavedCartsRepository(prefs.context)
        repo.saveCart(carrito())
        assertTrue(repo.deleteCart("s1"))
        // sin escribirPendientes(): lo de apply() no llegó a disco
        assertTrue(SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.isEmpty())
    }

    @Test
    fun `restaurar una copia marcada con confirmacion quita la marca en disco`() {
        val prefs = PrefsDeDosCapas(); val repo = SavedCartsRepository(prefs.context)
        repo.saveCart(carrito())
        repo.marcarCobroIniciado("s1", ahora = 1_000L)
        assertTrue(createViewModel(repo).restoreSavedCart(repo.savedCarts.value.single(), confirmado = true) is Restauracion.Hecha)
        assertNull(SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.single().cobroIniciadoEn)
    }

    /** Estática a propósito (`runPrimaryAction` vive en un composable): la marca se escribe ANTES de armar el cobro. */
    @Test
    fun `el cobro marca la copia antes de armarse`() {
        val fuente = java.io.File(raizDelRepo(), "app/src/main/java/com/avoqado/pos/pos/presentation/checkout/CheckoutScreen.kt").readText()
        val cuerpo = fuente.substringAfter("fun proceedToPayment(")
        val marca = cuerpo.indexOf("prepararCobroDelCarritoGuardado()")
        assertTrue("falta la marca en la puerta común proceedToPayment", marca >= 0)
        assertTrue(marca < cuerpo.indexOf("paymentCartSnapshot = cart.paymentSnapshot()"))
    }

    @Test
    fun `vaciar a mano no la borra`() {
        val prefs = PrefsDeDosCapas(); val repo = SavedCartsRepository(prefs.context)
        repo.saveCart(carrito())
        val vm = createViewModel(repo)
        vm.restoreSavedCart(repo.savedCarts.value.single())
        vm.clearCart()
        assertEquals(1, SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.size)
    }

    @Test
    fun `confirmar no restaura ni limpia la marca si el disco falla`() {
        val prefs = PrefsDeDosCapas(); val repo = SavedCartsRepository(prefs.context)
        assertTrue(repo.saveCart(carrito()))
        assertTrue(repo.marcarCobroIniciado("s1", ahora = 1_000L))
        val vm = createViewModel(repo)
        vm.addCustomAmount(name = "Venta actual", amountCents = 1200)
        prefs.commitFalla = true
        assertTrue(vm.restoreSavedCart(repo.savedCarts.value.single(), confirmado = true) is Restauracion.PideConfirmacion)
        assertEquals(1200, vm.cartState.value.totalCents)
        assertEquals(1_000L, SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.single().cobroIniciadoEn)
    }

    @Test
    fun `commit fallido cambia memoria del proceso pero no publica ni borra la copia en disco`() {
        val prefs = PrefsDeDosCapas(); val repo = SavedCartsRepository(prefs.context)
        assertTrue(repo.saveCart(carrito()))
        prefs.commitFalla = true
        assertFalse(repo.saveCart(carrito(cantidad = 2)))
        assertEquals(1, repo.savedCarts.value.single().items.single().quantity)
        assertEquals(1, SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.single().items.single().quantity)
        val memoria = prefs.context.getSharedPreferences("saved_carts", android.content.Context.MODE_PRIVATE).getString("carts", null)
        assertTrue(memoria!!.contains("\"quantity\":2"))
    }

    @Test
    fun `JSON viejo sin marca conserva cliente y precio`() {
        val prefs = PrefsDeDosCapas()
        assertTrue(prefs.context.getSharedPreferences("saved_carts", android.content.Context.MODE_PRIVATE).edit()
            .putString("carts", """[{"id":"viejo","name":"Venta","attachedCustomerId":"cliente","items":[{"productId":"p","name":"Artículo","unitPrice":5000,"quantity":1}]}]""").commit())
        val repo = SavedCartsRepository(prefs.reiniciarProceso().context)
        val copia = repo.savedCarts.value.single()
        assertNull(copia.cobroIniciadoEn)
        val vm = createViewModel(repo)
        assertEquals(Restauracion.Hecha, vm.restoreSavedCart(copia))
        assertEquals(5000, vm.cartState.value.totalCents)
        assertEquals("cliente", vm.selectedCustomer.value?.id)
        assertEquals("viejo", repo.savedCarts.value.single().id)
    }

    @Test
    fun `la respuesta de A retira solo A y no toca la venta B`() {
        val prefs = PrefsDeDosCapas(); val repo = SavedCartsRepository(prefs.context)
        assertTrue(repo.saveCart(carrito("A")))
        assertTrue(repo.saveCart(carrito("B", cantidad = 2)))
        val vm = createViewModel(repo)
        vm.restoreSavedCart(repo.savedCarts.value.first { it.id == "A" })
        assertTrue(vm.prepararCobroDelCarritoGuardado())
        val cobrado = vm.cartState.value
        assertEquals("A", cobrado.carritoGuardadoId)
        vm.restoreSavedCart(repo.savedCarts.value.first { it.id == "B" })
        val b = vm.cartState.value
        val resultado = vm.aplicarCobroConfirmado("CUSTOMAMOUNT", emptySet(), 2500,
            carritoGuardadoDelCobro = cobrado.carritoGuardadoId)
        assertFalse(resultado.aplicadoAlCarritoActual)
        assertEquals(b, vm.cartState.value)
        assertEquals(listOf("B"), SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.map { it.id })
        vm.aplicarCobroConfirmado("FULLPAYMENT", emptySet(), 0, carritoGuardadoDelCobro = cobrado.carritoGuardadoId)
        assertEquals(b, vm.cartState.value)
        assertEquals(listOf("B"), SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value.map { it.id })
    }

    @Test
    fun `la baja del vendedor no vincula la venta siguiente a la copia anterior`() = kotlinx.coroutines.test.runTest {
        val prefs = PrefsDeDosCapas(); val repo = SavedCartsRepository(prefs.context)
        assertTrue(repo.saveCart(carrito("A")))
        val staff = io.mockk.mockk<com.avoqado.pos.pos.data.StaffRepository>()
        io.mockk.coEvery { staff.getActiveStaff() } returns Result.success(listOf(
            com.avoqado.pos.pos.data.StaffMember(id = "staff-99", firstName = "Jose", lastName = "Tester")))
        val vm = CartPromotionTest().apply { setup() }.createViewModel(repo, staff)
        advanceUntilIdle()
        vm.restoreSavedCart(repo.savedCarts.value.single())
        io.mockk.coEvery { staff.getActiveStaff() } returns Result.success(emptyList())
        vm.fetchStaff()
        advanceUntilIdle()
        assertTrue(vm.cartState.value.isEmpty)
        assertNull(vm.cartState.value.carritoGuardadoId)
        vm.addCustomAmount(name = "Venta nueva", amountCents = 700)
        assertTrue(vm.saveCurrentCart())
        val despues = SavedCartsRepository(prefs.reiniciarProceso().context).savedCarts.value
        assertEquals(2, despues.size)
        assertEquals(5000, despues.single { it.id == "A" }.items.single().unitPrice)
        assertEquals(700, despues.single { it.id != "A" }.items.single().unitPrice)
    }

    @Test
    fun `dividir entra por la misma puerta durable y el callback usa el snapshot`() {
        val fuente = java.io.File(raizDelRepo(), "app/src/main/java/com/avoqado/pos/pos/presentation/checkout/CheckoutScreen.kt").readText()
        val split = fuente.substringAfter("// Split payment sheet").substringBefore("// Save cart success toast")
        assertTrue(split.contains("proceedToPayment()"))
        assertFalse(split.contains("showPaymentFlow = true"))
        assertTrue(fuente.contains("carritoGuardadoDelCobro = completion.carritoGuardadoDelCobro"))
        assertTrue(fuente.contains("if (!cobro.aplicadoAlCarritoActual) return@PaymentFlowScreen"))
    }
    @Test fun `venta sin guardar A no puede cerrar ni tocar B aunque ambas copias sean null`() {
        val repo = SavedCartsRepository(PrefsDeDosCapas().context)
        val vm = createViewModel(repo)
        vm.addCustomAmount(name = "A", amountCents = 10000)
        val a = vm.cartState.value
        vm.clearCart()
        vm.addCustomAmount(name = "B", amountCents = 2500)
        val b = vm.cartState.value
        assertNotEquals(a.identidadDeVenta, b.identidadDeVenta)
        val result = vm.aplicarCobroConfirmado("FULLPAYMENT", emptySet(), 0,
            carritoGuardadoDelCobro = a.carritoGuardadoId, identidadDeVentaDelCobro = a.identidadDeVenta)
        assertFalse(result.aplicadoAlCarritoActual)
        assertNull(result.referralPendiente)
        assertEquals(b, vm.cartState.value)
    }

    @Test fun `retirar copia A no cambia identidad del saldo ni impide la segunda parte real de A`() {
        val repo = SavedCartsRepository(PrefsDeDosCapas().context)
        assertTrue(repo.saveCart(carrito("A", cantidad = 2)))
        val vm = createViewModel(repo)
        vm.restoreSavedCart(repo.savedCarts.value.single())
        val a = vm.cartState.value
        assertTrue(vm.prepararCobroDelCarritoGuardado())
        val primera = vm.aplicarCobroConfirmado("CUSTOMAMOUNT", emptySet(), 6000,
            carritoGuardadoDelCobro = "A", identidadDeVentaDelCobro = a.identidadDeVenta)
        assertTrue(primera.aplicadoAlCarritoActual)
        assertEquals(6000, vm.cartState.value.totalCents)
        assertEquals(a.identidadDeVenta, vm.cartState.value.identidadDeVenta)
        assertNull(vm.cartState.value.carritoGuardadoId)
        assertTrue(repo.savedCarts.value.isEmpty())
        val segunda = vm.aplicarCobroConfirmado("FULLPAYMENT", emptySet(), 0,
            carritoGuardadoDelCobro = null, identidadDeVentaDelCobro = a.identidadDeVenta)
        assertTrue(segunda.ventaTerminada)
        assertNotEquals(a.identidadDeVenta, vm.cartState.value.identidadDeVenta)
        vm.addCustomAmount(name = "B", amountCents = 3000)
        val b = vm.cartState.value
        assertFalse(vm.aplicarCobroConfirmado("FULLPAYMENT", emptySet(), 0,
            carritoGuardadoDelCobro = null, identidadDeVentaDelCobro = a.identidadDeVenta).aplicadoAlCarritoActual)
        assertEquals(b, vm.cartState.value)
    }

    @Test fun `restaurar otra copia renueva identidad incluso si se restaura el mismo id`() {
        val repo = SavedCartsRepository(PrefsDeDosCapas().context)
        assertTrue(repo.saveCart(carrito("A")))
        val vm = createViewModel(repo)
        vm.restoreSavedCart(repo.savedCarts.value.single())
        val a = vm.cartState.value.identidadDeVenta
        vm.restoreSavedCart(repo.savedCarts.value.single())
        assertNotEquals(a, vm.cartState.value.identidadDeVenta)
    }

}
