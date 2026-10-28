package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.printing.data.AlmacenDeTexto
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.auth.data.AuthRepository
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.KDSRepository
import com.avoqado.pos.kds.domain.KDSOrderBus
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.CashPaymentResult
import com.avoqado.pos.payment.data.OnlineTerminal
import com.avoqado.pos.payment.data.OrderRepository
import com.avoqado.pos.payment.data.PaymentSyncService
import com.avoqado.pos.payment.data.TerminalListResult
import com.avoqado.pos.payment.data.TerminalPaymentResult
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.data.model.CreateOrderRequest
import com.avoqado.pos.payment.data.model.CreateOrderResponse
import com.avoqado.pos.payment.data.model.OrderData
import com.avoqado.pos.payment.data.model.PaymentMethod
import com.avoqado.pos.payment.presentation.PaymentFlowViewModel
import com.avoqado.pos.pos.data.ActiveCartState
import com.avoqado.pos.pos.data.ClassCheckoutSeed
import com.avoqado.pos.pos.data.DiscountsRepository
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.SavedCartsRepository
import com.avoqado.pos.pos.data.StaffMember
import com.avoqado.pos.pos.data.StaffRepository
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.pos.data.model.Discount
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.ProductCategory
import com.avoqado.pos.pos.data.model.SavedCart
import com.avoqado.pos.pos.data.model.SavedCartItem
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.pos.data.model.buildOrderItemRequests
import com.avoqado.pos.pos.presentation.cart.CartState
import com.avoqado.pos.pos.presentation.cart.CartViewModel
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.data.ReporteDeComandas
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.referrals.domain.usecase.CaptureReferralUseCase
import com.avoqado.pos.referrals.domain.usecase.ValidateReferralUseCase
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import com.avoqado.pos.transactions.presentation.toReceiptData
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Task 8 — la promoción viaja al server.
 *
 * 🔴 Esto es DINERO y es el contrato con el server: lo que se manda aquí decide
 * qué se cobra y qué queda registrado. Tres reglas que el server hace cumplir
 * con un 400, y que por eso se prueban a nivel del JSON que sale por el cable
 * (`buildCreateOrderPayload`), no sólo del objeto de Kotlin:
 *
 *  1. Una línea de promoción viaja SOLA: sin `productId`, sin `name` y sin
 *     `unitPrice` — ni siquiera en 0, que el server lee como línea normal.
 *  2. Las N líneas de producto del combo NO viajan además como productos
 *     sueltos: se colapsan en una. Mandarlas las dos veces cobra el combo dos
 *     veces.
 *  3. 3 combos son 3 líneas con 3 `promotionInstanceId`, nunca `quantity: 3`.
 *
 * Y la cuarta, que no es del server sino nuestra: **los dos sitios que arman la
 * orden tienen que producir lo mismo**. Cobrar y "pagar después" son dos
 * caminos distintos hacia el mismo `POST /orders`.
 *
 * Plan: .superpowers/sdd/2026-08-15-promociones-pos-cliente/task-8-brief.md
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OrderRequestPromotionTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // MARK: - Fixtures del carrito

    /** Combo del día: 2 líneas (hamburguesa + refresco) de UNA instancia. */
    private fun lineasDelCombo(instanceId: String = "inst-1"): List<CartItem> = listOf(
        CartItem(
            id = "line-$instanceId-1",
            type = CartItemType.ProductItem("p-hamburguesa"),
            name = "Hamburguesa",
            subtitle = "Combo del día",
            unitPrice = 7425,
            promotionInstanceId = instanceId,
            promotionName = "Combo del día",
            promotionId = "promo-combo",
            promotionGroupId = "g-plato",
            promotionOptionId = "o-hamburguesa",
        ),
        CartItem(
            id = "line-$instanceId-2",
            type = CartItemType.ProductItem("p-refresco"),
            name = "Refresco",
            subtitle = "Combo del día",
            unitPrice = 2475,
            promotionInstanceId = instanceId,
            promotionName = "Combo del día",
            promotionId = "promo-combo",
            promotionGroupId = "g-bebida",
            promotionOptionId = "o-refresco",
        ),
    )

    private val lineaNormal = CartItem(
        id = "line-suelta",
        type = CartItemType.ProductItem("p-cafe"),
        name = "Café",
        unitPrice = 4000,
        quantity = 2,
        selectedModifiers = listOf(
            SelectedModifier(
                groupId = "g-leche",
                groupName = "Leche",
                modifierId = "m-deslactosada",
                modifierName = "Deslactosada",
                priceInCents = 500,
            ),
        ),
        itemNote = "Sin azúcar",
        itemDiscountId = "disc-1",
    )

    // MARK: - 1. La forma del item

    @Test
    fun `una promocion viaja como UN item con promotionRef y sin precios`() {
        val items = buildOrderItemRequests(lineasDelCombo())

        assertEquals("las 2 líneas del combo se colapsan en UNA", 1, items.size)
        val promo = items.single()
        assertNotNull("la línea trae promotionRef", promo.promotionRef)
        val ref = promo.promotionRef!!

        assertEquals("promo-combo", ref.promotionId)
        assertEquals("inst-1", ref.promotionInstanceId)
        assertNull("sin productId: el server lo leería como venta normal", promo.productId)
        assertEquals("una promoción NUNCA lleva cantidad propia", 1, promo.quantity)
    }

    @Test
    fun `el payload que sale por el cable no lleva ni name ni unitPrice en la promocion`() {
        // 🔴 Carrito MIXTO a propósito: sobre uno de puras promociones, un
        // `!payload.contains("name")` pasa por accidente y no cazaría una fuga
        // cuando conviven las dos clases de línea. Se inspecciona el OBJETO de
        // la promoción, no el texto suelto.
        val request = CreateOrderRequest(
            items = buildOrderItemRequests(lineasDelCombo() + lineaNormal),
            subtotal = 18900,
            total = 18900,
            paymentMethod = "CASH",
        )

        val payload = OrderRepository.buildCreateOrderPayload(request = request, staffId = "staff-1")
        val items = kotlinx.serialization.json.Json.parseToJsonElement(payload)
            .jsonObject.getValue("items").jsonArray.map { it.jsonObject }
        val promo = items.single { it.containsKey("promotionRef") }
        val normal = items.single { !it.containsKey("promotionRef") }

        // El server rechaza con 400 un item que traiga promotionRef Y (productId |
        // name | unitPrice). `unitPrice: 0` cuenta: `typeof 0 === 'number'`.
        assertEquals(
            "la línea de promoción viaja SOLA: sólo promotionRef",
            setOf("promotionRef"),
            promo.keys,
        )
        val ref = promo.getValue("promotionRef").jsonObject
        assertEquals(setOf("promotionId", "promotionInstanceId", "selections"), ref.keys)
        assertTrue(payload.contains("\"promotionId\":\"promo-combo\""))
        assertTrue(payload.contains("\"promotionInstanceId\":\"inst-1\""))

        // Y la línea normal del MISMO payload sigue llevando lo suyo: la
        // aserción de arriba no puede pasar por "aquí no hay líneas normales".
        assertTrue(normal.containsKey("productId"))
        assertTrue(normal.containsKey("quantity"))
    }

    @Test
    fun `las selections se reconstruyen con un par groupId optionId por grupo`() {
        val ref = buildOrderItemRequests(lineasDelCombo()).single().promotionRef!!

        assertEquals(2, ref.selections.size)
        assertEquals("g-plato", ref.selections[0].groupId)
        assertEquals("o-hamburguesa", ref.selections[0].optionId)
        assertEquals("g-bebida", ref.selections[1].groupId)
        assertEquals("o-refresco", ref.selections[1].optionId)
    }

    // MARK: - 2. Las líneas normales no cambian

    @Test
    fun `las lineas normales viajan exactamente igual que antes`() {
        val items = buildOrderItemRequests(listOf(lineaNormal))

        assertEquals(1, items.size)
        val item = items.single()
        assertEquals("p-cafe", item.productId)
        assertEquals("Café", item.name)
        assertEquals(2, item.quantity)
        assertEquals(4000, item.unitPrice)
        assertEquals("Sin azúcar", item.note)
        assertEquals("disc-1", item.discountId)
        assertEquals(1, item.modifiers.size)
        assertEquals("m-deslactosada", item.modifiers.single().modifierId)
        assertNull("una línea normal NUNCA lleva promotionRef", item.promotionRef)
    }

    @Test
    fun `un carrito mixto conserva el orden y no duplica el combo`() {
        val items = buildOrderItemRequests(lineasDelCombo() + lineaNormal)

        assertEquals("1 promoción + 1 producto suelto", 2, items.size)
        assertNotNull(items[0].promotionRef)
        assertNull(items[1].promotionRef)
        assertEquals("p-cafe", items[1].productId)
        assertTrue(
            "los productos del combo NO pueden viajar además como líneas sueltas",
            items.none { it.productId == "p-hamburguesa" || it.productId == "p-refresco" },
        )
    }

    // MARK: - 3. Tres combos, tres instancias

    @Test
    fun `3 combos viajan como 3 items con instanceId distintos, nunca quantity 3`() {
        val carrito = lineasDelCombo("inst-1") + lineasDelCombo("inst-2") + lineasDelCombo("inst-3")

        val items = buildOrderItemRequests(carrito)

        assertEquals(3, items.size)
        assertEquals(
            listOf("inst-1", "inst-2", "inst-3"),
            items.map { it.promotionRef?.promotionInstanceId },
        )
        assertTrue("`quantity` distinto de 1 junto a promotionRef es un 400", items.all { it.quantity == 1 })
    }

    // MARK: - 4. Degradación: una promoción sin ids no se pierde

    @Test
    fun `una promocion sin promotionId degrada a lineas normales y no regala mercancia`() {
        // Carrito corrupto/heredado: agrupa pero no se puede armar el ref.
        val rotas = lineasDelCombo().map { it.copy(promotionId = null) }

        val items = buildOrderItemRequests(rotas)

        assertEquals("las 2 líneas siguen viajando: nada se regala", 2, items.size)
        assertTrue(items.all { it.promotionRef == null })
        assertEquals(listOf("p-hamburguesa", "p-refresco"), items.map { it.productId })
    }

    // MARK: - 5. Una venta de PURAS promociones sí crea orden

    @Test
    fun `una venta de puras promociones cuenta como orden con productos`() {
        val request = CreateOrderRequest(
            items = buildOrderItemRequests(lineasDelCombo()),
            subtotal = 9900,
            total = 9900,
            paymentMethod = "CASH",
        )

        assertTrue(
            "el combo ES la venta: sin esto la orden ni se intenta crear",
            OrderRepository.hasProductItems(request),
        )
    }

    // MARK: - 6. Los DOS sitios arman lo mismo

    @Test
    fun `pagar despues arma el mismo request que cobrar`() = runTest {
        val carrito = lineasDelCombo() + lineaNormal
        val cart = CartState(items = carrito)

        val cobrarSlot = slot<CreateOrderRequest>()
        coEvery {
            orderRepository.createOrder(capture(cobrarSlot), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1")))
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        } returns TerminalPaymentResult.Error("Terminal timeout")

        paymentViewModel.startPaymentFlow(cart)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CARD)
        paymentViewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        // 27-oct: «pagar después» manda su cuerpo ya armado (con la llave de la venta) y lee la respuesta cruda.
        val diferido = slot<String>()
        coEvery { orderRepository.enviarPagarDespues(any(), capture(diferido)) } returns
            OrderRepository.RespuestaCruda(201, "application/json", null, """{"success":true,"order":{"id":"order-2"}}""")

        val cartViewModel = createCartViewModel()
        cartViewModel.restoreSavedCart(comoCarritoGuardado(carrito))
        cartViewModel.createPayLaterOrder("cust-1")
        advanceUntilIdle()

        val itemsDeCobrar = org.json.JSONObject(OrderRepository.buildCreateOrderPayload(cobrarSlot.captured, staffId = "s"))
            .getJSONArray("items")
        val itemsDiferidos = org.json.JSONObject(diferido.captured).getJSONArray("items")
        assertEquals("cobrar y pagar-después tienen que mandar los MISMOS items", itemsDeCobrar.toString(), itemsDiferidos.toString())
        assertEquals(2, itemsDiferidos.length())
        assertTrue(itemsDiferidos.getJSONObject(0).has("promotionRef"))
    }

    @Test
    fun `P1 pagar despues con premio rechazado lo dice en vez de celebrar en silencio`() = runTest {
        // El carrito anunció $70 a diferir, pero el servidor rechazó el premio y la cuenta
        // abierta quedó en el precio completo (hallazgo P2 de Codex, 27-sep).
        val diferido = slot<String>()
        coEvery { orderRepository.enviarPagarDespues(any(), capture(diferido)) } returns OrderRepository.RespuestaCruda(
            201, "application/json", null,
            """{"success":true,"order":{"id":"order-2","stampReward":{"applied":false,"reason":"Este premio ya venció."}}}""",
        )

        val cartViewModel = createCartViewModel()
        cartViewModel.restoreSavedCart(comoCarritoGuardado(listOf(lineaNormal)))
        cartViewModel.setSelectedCustomer("cust-1")
        cartViewModel.setPendingStampReward(
            com.avoqado.pos.loyalty.data.PremioPorAplicar("rw1", "Premio", "FIXED_AMOUNT", 30.0),
        )
        cartViewModel.createPayLaterOrder("cust-1")
        advanceUntilIdle()

        assertEquals("rw1", org.json.JSONObject(diferido.captured).getString("stampRewardId"))
        val aviso = cartViewModel.avisoDelPremio.value
        assertNotNull(aviso)
        assertTrue(aviso!!.contains("Este premio ya venció."))
    }

    // MARK: - 7. El total autoritativo es el del server

    @Test
    fun `con promocion el POS cobra el total que devolvio el server`() = runTest {
        val amountSlot = slot<Int>()
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.success(
            CreateOrderResponse(
                success = true,
                // El server resolvió el combo en 99.00; el carrito estimaba 99.00 - 1¢.
                data = OrderData(id = "order-1", total = 99.0),
            ),
        )
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), capture(amountSlot), any(), any(), any(), any())
        } returns TerminalPaymentResult.Error("Terminal timeout")

        val carrito = lineasDelCombo().mapIndexed { index, item ->
            if (index == 0) item.copy(unitPrice = 7424) else item // total local: 9899
        }
        paymentViewModel.startPaymentFlow(CartState(items = carrito))
        paymentViewModel.selectPaymentMethod(PaymentMethod.CARD)
        paymentViewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals("se cobra el total del server, no el estimado del carrito", 9900, amountSlot.captured)
    }

    @Test
    fun `sin promocion el total del server no cambia lo que se cobra`() = runTest {
        val amountSlot = slot<Int>()
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.success(
            CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 120.0)),
        )
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), capture(amountSlot), any(), any(), any(), any())
        } returns TerminalPaymentResult.Error("Terminal timeout")

        paymentViewModel.startPaymentFlow(CartState(items = listOf(lineaNormal)))
        paymentViewModel.selectPaymentMethod(PaymentMethod.CARD)
        paymentViewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals(
            "una venta sin promoción se comporta EXACTAMENTE igual que antes",
            CartState(items = listOf(lineaNormal)).totalCents,
            amountSlot.captured,
        )
    }

    @Test
    fun `un pago parcial nunca adopta el total de la orden completa`() = runTest {
        val amountSlot = slot<Int>()
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.success(
            CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 139.0)),
        )
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), capture(amountSlot), any(), any(), any(), any())
        } returns TerminalPaymentResult.Error("Terminal timeout")

        val cart = CartState(items = lineasDelCombo() + lineaNormal)
        paymentViewModel.setSplitConfig(type = "CUSTOMAMOUNT", customAmountCents = 5000)
        paymentViewModel.startPaymentFlow(cart)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CARD)
        paymentViewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals("el total del server es de la ORDEN, no de esta parte", 5000, amountSlot.captured)
    }

    @Test
    fun `en efectivo el cambio se recalcula contra el total del server`() = runTest {
        val carrito = lineasDelCombo().mapIndexed { index, item ->
            if (index == 0) item.copy(unitPrice = 7424) else item // total local: 9899
        }
        // El cajero recibió $100 sobre un estimado de 98.99 → 1 peso de cambio.
        every { cashPaymentRepository.processCashPayment(9899, 10000) } returns
            CashPaymentResult.Success(changeCents = 101)
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.success(
            CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 99.0)),
        )
        val cobrado = slot<Int>()
        coEvery {
            orderRepository.recordCashPayment(any(), capture(cobrado), any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))

        paymentViewModel.startPaymentFlow(CartState(items = carrito))
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(10000)
        advanceUntilIdle()

        assertEquals("se registra el total del server", 9900, cobrado.captured)
        val exito = paymentViewModel.state.value as com.avoqado.pos.payment.data.model.PaymentFlowState.Success
        assertEquals("el cambio sale del total real, no del estimado", 100, exito.changeAmount)
        assertEquals(9900, exito.totalAmount)
    }

    @Test
    fun `en efectivo no se adopta un total que el dinero recibido ya no cubre`() = runTest {
        val carrito = lineasDelCombo() // total local: 9900
        every { cashPaymentRepository.processCashPayment(9900, 9900) } returns
            CashPaymentResult.Success(changeCents = 0)
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.success(
            // El server pide 1 centavo más del que el cliente puso sobre el mostrador.
            CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 99.01)),
        )
        val cobrado = slot<Int>()
        coEvery {
            orderRepository.recordCashPayment(any(), capture(cobrado), any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))

        paymentViewModel.startPaymentFlow(CartState(items = carrito))
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(9900)
        advanceUntilIdle()

        assertEquals(
            "el efectivo ya está en la mano: no se le piden centavos de vuelta al cliente",
            9900,
            cobrado.captured,
        )
    }

    /**
     * Sin red, una venta de mostrador en efectivo SÍ tiene cola en Android
     * (`CashPaymentRepository.queueCashPayment` → `PaymentSyncService`, que
     * reproduce el mismo `POST /orders`). El combo tiene que viajar en ese
     * pedido guardado, o al reconectar la venta entra a precio de lista.
     */
    @Test
    fun `sin red la venta encolada conserva la promocion`() = runTest {
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.failure(java.net.UnknownHostException())
        val encolado = slot<CreateOrderRequest>()
        coEvery {
            cashPaymentRepository.queueCashPayment(
                capture(encolado), any(), any(), any(), any(), any(), any(), any(), any(), any(), tenderType = any(), amountCents = any(), tipCents = any(), splitType = any(), venueId = any()
            )
        } returns "queued-1"

        paymentViewModel.startPaymentFlow(CartState(items = lineasDelCombo()))
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(10000)
        advanceUntilIdle()

        val item = encolado.captured.items.single()
        assertNotNull("el combo tiene que llegar al server al reconectar", item.promotionRef)
        assertEquals("promo-combo", item.promotionRef!!.promotionId)
    }

    /**
     * 🔴 Todo el "cobra el total del server" cuelga de leer bien UN campo. El
     * server manda `total` en PESOS con decimales dentro de `order` (nunca
     * `totalAmount`, que por eso siempre llegó null). Si el nombre o la unidad
     * cambian, la adopción deja de pasar EN SILENCIO y se vuelve a cobrar el
     * estimado. Este test fija la respuesta real.
     */
    @Test
    fun `el total del server se lee en pesos y se convierte a centavos`() {
        val cuerpoReal = """
            {"success":true,"order":{"id":"ord_1","orderNumber":"ORD-123","status":"CONFIRMED",
            "paymentStatus":"PENDING","subtotal":99,"taxAmount":0,"discountAmount":0,"total":99.01,
            "items":[{"id":"it_1","productId":"p-hamburguesa","quantity":1,"unitPrice":120,
            "total":74.25,"discountAmount":0,"appliedDiscountId":null,"orderPromotionId":"op_1",
            "modifiers":[]}],
            "promotions":[{"id":"op_1","instanceId":"inst-1","name":"Combo del día","netCents":9901,
            "discountCents":5599,"needsReview":false}],"createdAt":"2026-08-16T10:00:00.000Z"}}
        """.trimIndent()

        val parser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
        val orden = parser.decodeFromJsonElement(
            OrderData.serializer(),
            parser.parseToJsonElement(cuerpoReal).jsonObject.getValue("order"),
        )

        assertEquals(9901, orden.totalCents)
        assertEquals("ORD-123", orden.orderNumber)
        assertEquals("inst-1", orden.promotions.single().instanceId)
    }

    // MARK: - 7b. Pago dividido: la suma de las partes vale la venta

    /**
     * 🔴 El resto de un pago dividido salía del estimado del carrito, así que
     * `parte1 + parte2` quedaba hasta ±11¢ del total real de la orden: el
     * cliente pagaba otra cosa y la cuenta no cerraba
     * (`remainingAfterPayment <= 0.01` es la tolerancia del server).
     *
     * La parte que se cobra AHORA no se toca —ese importe lo eligió el cajero y
     * ya se lo dijo al cliente—; la que absorbe la diferencia es la última.
     */
    @Test
    fun `en pago dividido el resto sale del total del server, no del estimado`() = runTest {
        val carrito = lineasDelCombo().mapIndexed { index, item ->
            if (index == 0) item.copy(unitPrice = 7424) else item // estimado local: 9899
        }
        every { cashPaymentRepository.processCashPayment(5000, 5000) } returns
            CashPaymentResult.Success(changeCents = 0)
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.success(
            CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 99.0)),
        )
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))

        paymentViewModel.setSplitConfig(type = "CUSTOMAMOUNT", customAmountCents = 5000)
        paymentViewModel.startPaymentFlow(CartState(items = carrito))
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(5000)
        advanceUntilIdle()

        val completion = paymentViewModel.consumeCompletion()!!
        assertEquals("el resto cierra contra el total del server", 4900, completion.remainingBalanceCents)
        assertEquals(
            "la suma de las partes tiene que valer lo que el server le puso a la orden",
            9900,
            5000 + completion.remainingBalanceCents,
        )
    }

    @Test
    fun `sin promocion el resto de un pago dividido sigue saliendo del carrito`() = runTest {
        every { cashPaymentRepository.processCashPayment(5000, 5000) } returns
            CashPaymentResult.Success(changeCents = 0)
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.success(
            CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 120.0)),
        )
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any())
        } returns Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))

        val cart = CartState(items = listOf(lineaNormal)) // 9000
        paymentViewModel.setSplitConfig(type = "CUSTOMAMOUNT", customAmountCents = 5000)
        paymentViewModel.startPaymentFlow(cart)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(5000)
        advanceUntilIdle()

        val completion = paymentViewModel.consumeCompletion()!!
        assertEquals(
            "una venta sin promoción se comporta EXACTAMENTE igual que antes",
            cart.totalCents - 5000,
            completion.remainingBalanceCents,
        )
    }

    // MARK: - 8. Reintento tras un 4xx

    @Test
    fun `un 4xx al crear la orden estrena externalId en el reintento`() = runTest {
        val externalIds = mutableListOf<String>()
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), capture(externalIds), capturedVenueId = any())
        } returns Result.failure(OrderRepository.ServerException(400, "promotionRef requiere selections"))

        val cart = CartState(items = lineasDelCombo())
        paymentViewModel.startPaymentFlow(cart)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CARD)
        paymentViewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()
        paymentViewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals(2, externalIds.size)
        assertFalse(
            "la orden pudo crearse y anularse: el reintento estrena llave",
            externalIds[0] == externalIds[1],
        )
    }

    @Test
    fun `un fallo de red conserva el externalId para que el server deduplique`() = runTest {
        val externalIds = mutableListOf<String>()
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), capture(externalIds), capturedVenueId = any())
        } returns Result.failure(java.net.SocketTimeoutException())

        val cart = CartState(items = lineasDelCombo())
        paymentViewModel.startPaymentFlow(cart)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CARD)
        paymentViewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()
        paymentViewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        assertEquals(2, externalIds.size)
        assertEquals(
            "si el intento lento SÍ aterrizó, el reintento tiene que deduplicar",
            externalIds[0],
            externalIds[1],
        )
    }

    // MARK: - El ticket del cobro cuadra con lo cobrado (IVA por producto, plan 7a, Tarea 8: sólo el cobro)

    private fun cuadra(r: ReceiptData) =
        r.total == r.subtotal - (r.discountAmount ?: 0) + (r.serviceChargeAmount ?: 0) + r.taxAmount + (r.tipAmount ?: 0)

    @Test
    fun `P1 el ticket del cobro cuadra con el total que confirmo el servidor (Codex r1 #3)`() = runTest {
        val carrito = lineasDelCombo().mapIndexed { index, item ->
            if (index == 0) item.copy(unitPrice = 7424) else item // total local: 9899; el servidor cobra 99
        }
        every { cashPaymentRepository.processCashPayment(9899, 10000) } returns CashPaymentResult.Success(changeCents = 101)
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 99.0)))
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))
        val impreso = slot<ReceiptData>()
        coEvery { printerService.autoPrintReceipt(capture(impreso)) } returns Unit

        paymentViewModel.startPaymentFlow(CartState(items = carrito))
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(10000)
        advanceUntilIdle()

        val r = impreso.captured
        assertEquals("TOTAL = lo que cobró el servidor", 9900, r.total)
        assertEquals("subtotal − descuento + IVA + propina = TOTAL", r.total, r.subtotal - (r.discountAmount ?: 0) + r.taxAmount + (r.tipAmount ?: 0))
        // Venta nueva: IVA incluido ⇒ la leyenda (taxCents 0), sin cargo y nunca una devolución.
        assertEquals(0, r.taxAmount)
        assertNull(r.serviceChargeAmount)
        assertFalse(r.isRefund)
    }

    @Test
    fun `P1 con propina el ticket cuadra con lo que cobro el servidor y la propina va en su renglon`() = runTest {
        every { tpvSettingsRepository.getCurrentSettings() } returns TpvSettings(showReviewScreen = false, showTipScreen = true)
        val carrito = lineasDelCombo().mapIndexed { index, item ->
            if (index == 0) item.copy(unitPrice = 7424) else item // 9899 local; el servidor cobra 99 + 5 de propina
        }
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 104.0)))
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))
        val impreso = slot<ReceiptData>()
        coEvery { printerService.autoPrintReceipt(capture(impreso)) } returns Unit

        paymentViewModel.startPaymentFlow(CartState(items = carrito))
        assertTrue("montaje: pide propina", paymentViewModel.state.value is com.avoqado.pos.payment.data.model.PaymentFlowState.CollectingTip)
        paymentViewModel.submitTip(500)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(11000)
        advanceUntilIdle()

        val r = impreso.captured
        assertEquals("TOTAL = lo que cobró el servidor, con la propina", 10400, r.total)
        assertEquals(500, r.tipAmount)
        assertEquals("lo cobrado supera el bruto: el subtotal es lo cobrado sin propina", 9900, r.subtotal)
        assertNull(r.discountAmount)
        assertTrue("subtotal − descuento + IVA + propina = TOTAL", cuadra(r))
    }

    @Test
    fun `control - el cobro completo de una mesa con cargo imprime el renglon unico de hoy y el mismo total (7a no empeora)`() = runTest {
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))
        val impreso = slot<ReceiptData>()
        coEvery { printerService.autoPrintReceipt(capture(impreso)) } returns Unit
        // La mesa en PAYING (como la deja `TablesViewModel`): el cobro paga la orden EXISTENTE.
        tableSession.start(
            com.avoqado.pos.tables.data.TableSession.Active(
                tableId = "mesa-5", tableNumber = "5", areaName = null, orderId = "order-mesa-5", orderNumber = "55",
                version = 1, totalCents = 11500, mode = com.avoqado.pos.tables.data.TableSession.Mode.PAYING,
            ),
        )

        // Así siembra el cobro de mesa el carrito: UN renglón con el saldo, cargo adentro (CartViewModel.consumePendingTableCobrar).
        paymentViewModel.startPaymentFlow(CartState(items = listOf(CartItem(type = CartItemType.CustomAmount, name = "Cuenta Mesa 5", unitPrice = 11500, quantity = 1))))
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(11500)
        advanceUntilIdle()

        val r = impreso.captured
        assertNull(r.serviceChargeAmount)
        assertEquals(11500, r.subtotal)
        assertEquals(11500, r.total)
        assertEquals(r.total, r.subtotal - (r.discountAmount ?: 0) + r.taxAmount + (r.tipAmount ?: 0))
    }

    @Test
    fun `control - un pago parcial imprime solo lo cobrado, sin descuento ni IVA prorrateado`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 99.0)))
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))
        val impreso = slot<ReceiptData>()
        coEvery { printerService.autoPrintReceipt(capture(impreso)) } returns Unit

        // Cuenta de $99 con un descuento de orden de $10; se cobran $50 de ella.
        paymentViewModel.setSplitConfig(type = "CUSTOMAMOUNT", customAmountCents = 5000)
        paymentViewModel.startPaymentFlow(
            CartState(items = lineasDelCombo(), orderDiscount = Discount(id = "d-10", name = "Diez", type = "FIXED_AMOUNT", value = 10.0)),
        )
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(5000)
        advanceUntilIdle()

        val r = impreso.captured
        assertEquals(listOf(5000, 0, 5000), listOf(r.subtotal, r.taxAmount, r.total))
        assertNull("el descuento de la cuenta no se reparte en un pago parcial", r.discountAmount)
        assertNull(r.serviceChargeAmount)
    }

    @Test
    fun `control - una venta vieja con IVA aparte imprime su IVA agregado una sola vez`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 116.0)))
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))
        val impreso = slot<ReceiptData>()
        coEvery { printerService.autoPrintReceipt(capture(impreso)) } returns Unit
        val producto = CartItem(id = "line-1", type = CartItemType.ProductItem("p-1"), name = "Producto", unitPrice = 10000)

        // Un carrito con impuesto aparte de antes de D13 (hoy ya no se puede armar desde la caja).
        paymentViewModel.startPaymentFlow(CartState(items = listOf(producto), orderTaxPercent = 16))
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(11600)
        advanceUntilIdle()

        val r = impreso.captured
        assertEquals(listOf(10000, 1600, 11600), listOf(r.subtotal, r.taxAmount, r.total))
        assertNull(r.discountAmount)
        assertTrue(cuadra(r))
    }

    /** Los renglones de dinero tal como salen en el PAPEL (intérprete real, bloque de totales). */
    private fun renglonesDelPapel(r: ReceiptData): List<String> =
        com.avoqado.pos.printing.receiptlayout.ReceiptLayoutInterpreter.interpret(
            listOf(com.avoqado.pos.printing.receiptlayout.ReceiptBlock.Totals()),
            com.avoqado.pos.printing.receiptlayout.ReceiptInputMapper.map(
                r, info = null, hasLogo = false, timezone = "America/Mexico_City", appVersion = null,
            ),
            com.avoqado.pos.printing.data.model.PaperWidth.MM80.charsPerLine,
        ).filterIsInstance<com.avoqado.pos.printing.receiptlayout.LogicalLine.Text>().map { it.text }

    @Test
    fun `P1 el servidor cobra menos que el bruto - subtotal bruto y descuento, y la reimpresion imprime el mismo papel (A2 m2, A5 m4)`() = runTest {
        // Combo de 99 en el carrito; el servidor lo resuelve en 89 (la promoción que confirmó él). Los $100 recibidos lo cubren.
        every { cashPaymentRepository.processCashPayment(9900, 10000) } returns CashPaymentResult.Success(changeCents = 100)
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 89.0)))
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(OrderRepository.CashPayResult(paymentId = "pay-1", receiptAccessKey = null))
        val impreso = slot<ReceiptData>()
        coEvery { printerService.autoPrintReceipt(capture(impreso)) } returns Unit

        paymentViewModel.startPaymentFlow(CartState(items = lineasDelCombo()))
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(10000)
        advanceUntilIdle()

        val r = impreso.captured
        assertEquals("TOTAL = lo que cobró el servidor", 8900, r.total)
        assertEquals("el subtotal es el BRUTO de los renglones", 9900, r.subtotal)
        assertEquals("el descuento es lo que falta del bruto a lo cobrado", 1000, r.discountAmount)
        assertTrue("subtotal − descuento + IVA + propina = TOTAL", cuadra(r))

        // La reimpresión de esa venta, con el comprobante TAL COMO lo manda el servidor (A7-R6: bruto + discount): el papel del
        // cobro de arriba sale del productor real (`buildReceiptSnapshot`), no de una copia de su mapeo.
        val reimpreso = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
            .decodeFromString(
                com.avoqado.pos.transactions.data.model.Transaction.serializer(),
                """{"id":"pay-1","method":"CASH","amount":89.0,"ticketTotals":{"kind":"COBRO","desglosado":true,"subtotal":99,"discount":10,"serviceCharge":0,"addedTax":0,"tip":0,"total":89}}""",
            )
        assertEquals("mismos renglones de dinero y en el mismo orden", renglonesDelPapel(r), renglonesDelPapel(reimpreso.toReceiptData(venueName = "T")))
    }

    // MARK: - Andamiaje

    private val orderRepository = mockk<OrderRepository>(relaxed = true)
    private val cashPaymentRepository = mockk<CashPaymentRepository>(relaxed = true)
    private val terminalPaymentService = mockk<TerminalPaymentService>(relaxed = true)
    private val tpvSettingsRepository = mockk<TpvSettingsRepository>(relaxed = true)
    private val paymentSyncService = mockk<PaymentSyncService>(relaxed = true)
    private val cashDrawerRepository = mockk<CashDrawerRepository>(relaxed = true)
    private val kdsRepository = mockk<KDSRepository>(relaxed = true)
    private val kdsOrderBus = mockk<KDSOrderBus>(relaxed = true)
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val secureStorage = mockk<SecureStorage>(relaxed = true)
    private val printConfigRepository = mockk<PrintConfigRepository>(relaxed = true)
    private val comandaPrinter = mockk<ComandaPrinter>(relaxed = true)
    private val areaTicketRepository = mockk<AreaTicketRepository>(relaxed = true)
    /** La del cobro: la prueba de la mesa la abre en PAYING, como `TablesViewModel` antes de mandar a Cobrar. */
    private val tableSession = com.avoqado.pos.tables.data.TableSession()

    private val productsRepository = mockk<ProductsRepository>(relaxed = true)
    private val discountsRepository = mockk<DiscountsRepository>(relaxed = true)
    private val savedCartsRepository = mockk<SavedCartsRepository>(relaxed = true)
    private val authRepository = mockk<AuthRepository>(relaxed = true)
    private val activeCartState = mockk<ActiveCartState>(relaxed = true)
    private val staffRepository = mockk<StaffRepository>(relaxed = true)
    private val classCheckoutSeed = mockk<ClassCheckoutSeed>(relaxed = true)
    private val validateReferralUseCase = mockk<ValidateReferralUseCase>(relaxed = true)
    private val captureReferralUseCase = mockk<CaptureReferralUseCase>(relaxed = true)
    private val venueSwitchedFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private lateinit var paymentViewModel: PaymentFlowViewModel

    @Before
    fun setup() {
        every { tpvSettingsRepository.getCurrentSettings() } returns
            TpvSettings(showReviewScreen = false, showTipScreen = false)
        coEvery { terminalPaymentService.fetchOnlineTerminals(any()) } returns
            TerminalListResult.Success(
                listOf(OnlineTerminal(terminalId = "t1", name = "Terminal 1", isOnline = true, hasSocket = true)),
            )
        every { cashPaymentRepository.processCashPayment(any(), any()) } returns
            CashPaymentResult.Success(changeCents = 0)
        coEvery { cashDrawerRepository.addCashSale(any(), any(), any(), any(), any(), llaveDelCobro = any()) } returns null
        coEvery { kdsRepository.createOrder(any(), any(), any(), any()) } returns Result.success(Unit)
        every { secureStorage.venueName } returns "Avoqado Test"
        every { secureStorage.userId } returns "user-1"
        every { secureStorage.venueId } returns "venue-1"
        every { areaTicketRepository.session.current() } returns null
        coEvery { printConfigRepository.refresh(any()) } returns Unit
        every { printConfigRepository.getCurrentConfig() } returns PrintConfig()
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 0, printed = 0, skippedNoPrinter = 0, lastError = null)

        every { productsRepository.products } returns MutableStateFlow<List<Product>>(emptyList())
        every { productsRepository.categories } returns MutableStateFlow<List<ProductCategory>>(emptyList())
        every { productsRepository.isLoading } returns MutableStateFlow(false)
        every { savedCartsRepository.savedCarts } returns MutableStateFlow<List<SavedCart>>(emptyList())
        // «Pagar después» de un carrito restaurado deja su llave en la copia antes de mandar (Codex A-5).
        every { savedCartsRepository.prepararPagarDespues(any(), any(), any(), any()) } returns true
        every { discountsRepository.discounts } returns MutableStateFlow<List<Discount>>(emptyList())
        every { authRepository.venueSwitched } returns venueSwitchedFlow
        every { classCheckoutSeed.consume() } returns null
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-99"
        every { secureStorage.selectedStaffNameForCurrentVenue } returns "Jose Tester"
        coEvery { staffRepository.getActiveStaff() } returns Result.success(
            listOf(StaffMember(id = "staff-99", firstName = "Jose", lastName = "Tester")),
        )

        paymentViewModel = PaymentFlowViewModel(
            orderRepository = orderRepository,
            cashPaymentRepository = cashPaymentRepository,
            tenderTypeRepository = mockk(relaxed = true),
            terminalPaymentService = terminalPaymentService,
            tpvSettingsRepository = tpvSettingsRepository,
            paymentSyncService = paymentSyncService,
            cashDrawerRepository = cashDrawerRepository,
            kdsRepository = kdsRepository,
            kdsOrderBus = kdsOrderBus,
            printerService = printerService,
            secureStorage = secureStorage,
            comandasPendientesStore = ComandasPendientesStore(AlmacenEnMemoria()),
            replayDeComandas = mockk(relaxed = true),
            // Ronda de arreglo 1 (Task 4): `ComandaDispatcher` recibe `ReintentoDeComanda` por
            // Hilt ahora — construido aquí con el mismo `comandaPrinter` mockeado de siempre.
            comandaDispatcher = ComandaDispatcher(
                printConfigRepository,
                ReintentoDeComanda(comandaPrinter, reporteDeComandas = mockk<ReporteDeComandas>(relaxed = true)),
                printerService,
            ),
            tableSession = tableSession,
            syncOutbox = mockk(relaxed = true),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = areaTicketRepository,
            // Esta prueba no cancela: el coordinador de cancelaciones va relajado.
            cancelacionDeCobro = mockk(relaxed = true),
            savedStateHandle = androidx.lifecycle.SavedStateHandle(),
        )
    }

    /**
     * El MISMO carrito, entrando por la puerta pública de `CartViewModel`.
     * Guardar/restaurar ya conserva los 5 campos de promoción (Task 6), así que
     * es la vía honesta de sembrarlo sin abrir el estado interno para el test.
     */
    private fun comoCarritoGuardado(items: List<CartItem>) = SavedCart(
        id = "saved-1",
        name = "Carrito de prueba",
        items = items.map { item ->
            SavedCartItem(
                productId = (item.type as? CartItemType.ProductItem)?.productId,
                name = item.name,
                unitPrice = item.unitPrice,
                quantity = item.quantity,
                modifiers = item.selectedModifiers.map { modifier ->
                    com.avoqado.pos.pos.data.model.SavedModifier(
                        groupId = modifier.groupId,
                        groupName = modifier.groupName,
                        modifierId = modifier.modifierId,
                        modifierName = modifier.modifierName,
                        priceInCents = modifier.priceInCents,
                    )
                },
                note = item.itemNote,
                itemDiscountId = item.itemDiscountId,
                promotionInstanceId = item.promotionInstanceId,
                promotionName = item.promotionName,
                promotionId = item.promotionId,
                promotionGroupId = item.promotionGroupId,
                promotionOptionId = item.promotionOptionId,
            )
        },
    )

    private fun createCartViewModel(tableContext: com.avoqado.pos.tables.data.TableSession = com.avoqado.pos.tables.data.TableSession()): CartViewModel = CartViewModel(
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
        tableSession = tableContext,
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
        areaTicketRepository = areaTicketRepository,
        walletScanRepository = mockk(relaxed = true),
        payLaterQueue = com.avoqado.pos.payment.data.PayLaterQueue(
            PrefsEnMemoria().context, orderRepository, secureStorage, savedCartsRepository, mockk(relaxed = true),
        ).apply { io = kotlinx.coroutines.Dispatchers.Unconfined },
    )


    // T4: helpers de fixture. El receipt se obtiene del VM y de su impresora reales, sin llamar al builder privado.
    private fun mesaT4(cargo: Int = 1500, id: String = "order-mesa-A", total: Int = 11500) =
        com.avoqado.pos.tables.data.TableSession.Active(
            "mesa-A", "5", "Patio A", id, "55", 1, total,
            com.avoqado.pos.tables.data.TableSession.Mode.PAYING, serviceChargeCents = cargo,
        )

    private fun carritoT4(): CartState {
        val subject = createCartViewModel(tableContext = tableSession)
        return try {
            subject.consumePendingTableCobrar()
            subject.cartState.value
        } finally { subject.viewModelScope.cancel() }
    }

    private suspend fun TestScope.cobrarMesaT4(
        split: String = "FULLPAYMENT",
        importe: Int = 11500,
        mesa: com.avoqado.pos.tables.data.TableSession.Active = mesaT4(),
    ): ReceiptData {
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(OrderRepository.CashPayResult(paymentId = "pay-A", receiptAccessKey = null))
        val impreso = slot<ReceiptData>()
        coEvery { printerService.autoPrintReceipt(capture(impreso)) } returns Unit
        tableSession.start(mesa)
        val cart = carritoT4()
        paymentViewModel.setSplitConfig(split, selectedItemIds = cart.items.map { it.id },
            numberOfParts = if (split == "EQUALPARTS") 2 else null,
            customAmountCents = if (split == "CUSTOMAMOUNT") importe else null)
        paymentViewModel.startPaymentFlow(cart)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
        paymentViewModel.processCashPayment(importe)
        advanceUntilIdle()
        return impreso.captured
    }

    @Test fun `T4 cobro completo separa 1500 de cargo y no los inventa como descuento`() = runTest {
        try {
            val r = cobrarMesaT4()
            assertEquals(1500, r.serviceChargeAmount)
            assertEquals(10000, r.subtotal)
            assertEquals(10000, r.items.single().unitPrice)
            assertEquals(10000, r.items.single().totalPrice)
            assertEquals(11500, r.total)
            assertNull(r.discountAmount)
            assertEquals(0, r.taxAmount)
            assertTrue(cuadra(r))
        } finally { paymentViewModel.viewModelScope.cancel() }
    }

    @Test fun `T4 una cuenta pagada solo con cargo conserva productos cero y cargo completo`() = runTest {
        try {
            coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
                Result.success(OrderRepository.CashPayResult(paymentId = "pay-A", receiptAccessKey = null))
            val impreso = slot<ReceiptData>()
            coEvery { printerService.autoPrintReceipt(capture(impreso)) } returns Unit
            tableSession.start(mesaT4(cargo = 1500, total = 1500))
            val cart = carritoT4()
            assertEquals(1500, cart.subtotalCents)
            paymentViewModel.setSplitConfig("FULLPAYMENT")
            paymentViewModel.startPaymentFlow(cart)
            paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
            paymentViewModel.processCashPayment(1500)
            advanceUntilIdle()
            val r = impreso.captured
            assertEquals(0, r.subtotal)
            assertEquals(0, r.items.single().unitPrice)
            assertEquals(0, r.items.single().totalPrice)
            assertEquals(1500, r.serviceChargeAmount)
            assertEquals(1500, r.total)
            assertNull(r.discountAmount)
            assertEquals(0, r.taxAmount)
            assertTrue(cuadra(r))
            coVerify(exactly = 1) { orderRepository.recordCashPayment("order-mesa-A", 1500, any(), 0, any(), any(), any(), any(), capturedVenueId = any()) }
        } finally { paymentViewModel.viewModelScope.cancel() }
    }

    @Test fun `T4 importe personalizado no prorratea el cargo de la cuenta`() = runTest {
        try {
            val r = cobrarMesaT4("CUSTOMAMOUNT", 5000)
            assertNull(r.serviceChargeAmount); assertNull(r.discountAmount)
            assertEquals(5000, r.subtotal); assertEquals(5000, r.items.single().totalPrice)
            assertEquals(5000, r.total); assertTrue(cuadra(r))
        } finally { paymentViewModel.viewModelScope.cancel() }
    }

    @Test fun `T4 partes iguales no prorratean el cargo de la cuenta`() = runTest {
        try {
            val r = cobrarMesaT4("EQUALPARTS", 5750)
            assertNull(r.serviceChargeAmount); assertNull(r.discountAmount)
            assertEquals(5750, r.subtotal); assertEquals(5750, r.items.single().totalPrice)
            assertEquals(5750, r.total); assertTrue(cuadra(r))
        } finally { paymentViewModel.viewModelScope.cancel() }
    }

    @Test fun `T4 dividir por producto tampoco separa el cargo aunque se elija el unico renglon`() = runTest {
        try {
            val r = cobrarMesaT4("BYPRODUCT", 11500)
            assertNull(r.serviceChargeAmount); assertNull(r.discountAmount)
            assertEquals(11500, r.subtotal); assertEquals(11500, r.items.single().totalPrice)
            assertEquals(11500, r.total); assertTrue(cuadra(r))
        } finally { paymentViewModel.viewModelScope.cancel() }
    }

    @Test fun `T4 suspender el POST y cambiar a B conserva orden cargo y encabezado de A`() = runTest {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Result<OrderRepository.CashPayResult>>()
        val impreso = slot<ReceiptData>()
        coEvery { printerService.autoPrintReceipt(capture(impreso)) } returns Unit
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } coAnswers {
            started.complete(Unit); response.await()
        }
        every { secureStorage.venueName } returns "Negocio A"
        tableSession.start(mesaT4())
        try {
            paymentViewModel.startPaymentFlow(carritoT4(), apertura = "apertura-A")
            paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
            paymentViewModel.processCashPayment(11500)
            runCurrent(); assertTrue("El POST real del VM ya se suspendió", started.isCompleted)
            every { secureStorage.venueId } returns "venue-B"
            every { secureStorage.venueName } returns "Negocio B"
            tableSession.start(mesaT4(cargo = 3000, id = "order-mesa-B", total = 20000))
            response.complete(Result.success(OrderRepository.CashPayResult(paymentId = "pay-A", receiptAccessKey = null)))
            advanceUntilIdle()
            coVerify(exactly = 1) { orderRepository.recordCashPayment("order-mesa-A", 11500, any(), any(), any(), any(), any(), capturedVenueId = "venue-1") }
            val r = impreso.captured
            assertEquals("Negocio A", r.venueName)
            assertEquals(1500, r.serviceChargeAmount); assertEquals(10000, r.subtotal)
            assertEquals(10000, r.items.single().totalPrice); assertEquals(11500, r.total)
            assertNull(r.discountAmount); assertTrue(cuadra(r))
            assertEquals("El snapshot no cambia la mesa viva B", "order-mesa-B", tableSession.current()!!.orderId)
            assertEquals(3000, tableSession.current()!!.serviceChargeCents)
        } finally {
            response.complete(Result.failure(IllegalStateException("cleanup")))
            paymentViewModel.viewModelScope.cancel()
        }
    }

    @Test fun `T4 la misma apertura recreada con B no sustituye el snapshot de cargo y negocio A`() = runTest {
        val response = CompletableDeferred<Result<OrderRepository.CashPayResult>>()
        val started = CompletableDeferred<Unit>()
        val impreso = slot<ReceiptData>()
        coEvery { printerService.autoPrintReceipt(capture(impreso)) } returns Unit
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } coAnswers {
            started.complete(Unit); response.await()
        }
        every { secureStorage.venueName } returns "Negocio A"
        tableSession.start(mesaT4())
        try {
            paymentViewModel.startPaymentFlow(carritoT4(), apertura = "same-A")
            paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
            paymentViewModel.processCashPayment(11500); runCurrent(); assertTrue(started.isCompleted)
            every { secureStorage.venueName } returns "Negocio B"
            tableSession.start(mesaT4(3000, "order-mesa-B", 20000))
            paymentViewModel.startPaymentFlow(CartState(items = listOf(CartItem(
                type = CartItemType.CustomAmount, name = "Cuenta B", unitPrice = 20000, quantity = 1,
            ))), apertura = "same-A")
            response.complete(Result.success(OrderRepository.CashPayResult("pay-A", null)))
            advanceUntilIdle()
            val r = impreso.captured
            assertEquals("Negocio A", r.venueName); assertEquals(1500, r.serviceChargeAmount)
            assertEquals("Cuenta Mesa 5", r.items.single().name); assertEquals(10000, r.items.single().totalPrice)
            assertEquals(11500, r.total); assertNull(r.discountAmount); assertTrue(cuadra(r))
        } finally { response.complete(Result.failure(IllegalStateException("cleanup"))); paymentViewModel.viewModelScope.cancel() }
    }

    @Test fun `T4 una apertura nueva sin mesa no hereda el cargo ni el negocio anterior`() = runTest {
        coEvery { orderRepository.createOrder(any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-nueva", total = 115.0)))
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(OrderRepository.CashPayResult("pay-nueva", null))
        val impreso = slot<ReceiptData>()
        coEvery { printerService.autoPrintReceipt(capture(impreso)) } returns Unit
        tableSession.start(mesaT4()); every { secureStorage.venueName } returns "Negocio A"
        try {
            paymentViewModel.startPaymentFlow(carritoT4(), apertura = "old-A")
            tableSession.clear(); every { secureStorage.venueName } returns "Negocio B"
            paymentViewModel.startPaymentFlow(CartState(items = listOf(CartItem(
                type = CartItemType.ProductItem("p-nueva"), name = "Producto B", unitPrice = 11500, quantity = 1,
            ))), apertura = "new-B")
            paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
            paymentViewModel.processCashPayment(11500); advanceUntilIdle()
            val r = impreso.captured
            assertEquals("Negocio B", r.venueName); assertNull(r.serviceChargeAmount)
            assertEquals(11500, r.subtotal); assertEquals(11500, r.items.single().totalPrice)
            assertEquals(11500, r.total); assertTrue(cuadra(r))
        } finally { paymentViewModel.viewModelScope.cancel() }
    }

    // MARK: - D4 (mesas40, 14-oct): el ticket del cobro de una MESA

    @Test fun `D4 mesero Ana, cobra Luis - Atendio Ana, el pago sigue a nombre de Luis y el tipo es Mesa 5`() = runTest {
        // Luis es quien tiene la caja: el carrito lo trae como vendedor (lo que el pago registra como staffId).
        every { secureStorage.selectedStaffIdForCurrentVenue } returns "staff-luis"
        every { secureStorage.selectedStaffNameForCurrentVenue } returns "Luis"
        try {
            val r = cobrarMesaT4(mesa = mesaT4().copy(meseroNombre = "Ana"))
            assertEquals("Atendió = el mesero dueño de la cuenta", "Ana", r.cashierName)
            assertEquals("Mesa 5", r.orderType)
            coVerify(exactly = 1) {
                orderRepository.recordCashPayment("order-mesa-A", 11500, "staff-luis", any(), any(), any(), any(), any(), capturedVenueId = any())
            }
        } finally { paymentViewModel.viewModelScope.cancel() }
    }

    @Test fun `D4 cuenta sin mesero - Atendio es la sesion, nunca el literal Staff`() = runTest {
        every { secureStorage.userFirstName } returns "Luis"
        every { secureStorage.userLastName } returns "Cajero"
        coEvery { orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any(), capturedVenueId = any()) } returns
            Result.success(OrderRepository.CashPayResult(paymentId = "pay-A", receiptAccessKey = null))
        val impreso = slot<ReceiptData>()
        coEvery { printerService.autoPrintReceipt(capture(impreso)) } returns Unit
        tableSession.start(mesaT4(cargo = 0))
        try {
            // Así siembra «Pagar» de la pantalla de la mesa (TableOrderScreen): un CartState armado a mano, sin vendedor.
            paymentViewModel.startPaymentFlow(CartState(items = listOf(CartItem(type = CartItemType.CustomAmount, name = "Cuenta Mesa 5", unitPrice = 11500, quantity = 1))))
            paymentViewModel.selectPaymentMethod(PaymentMethod.CASH)
            paymentViewModel.processCashPayment(11500)
            advanceUntilIdle()
            assertEquals("Luis Cajero", impreso.captured.cashierName)
            assertEquals("Mesa 5", impreso.captured.orderType)
        } finally { paymentViewModel.viewModelScope.cancel() }
    }

    /**
     * D4, ronda 2 de Codex (P2): «Pagar» capturaba los platillos (taco $50 + agua $50). Otra caja hace cortesía del taco y
     * agrega un postre de $50: la cuenta sigue en $100 y se cobran $100 exactos. El cobro no devuelve la versión de la
     * cuenta, así que nada en el aparato prueba que la foto sigue vigente: el ticket dice «Cuenta Mesa 5», nunca taco y agua
     * (los platillos con certeza los trae la reimpresión, del servidor). Rojo con la foto capturada: CI 38794433857.
     */
    @Test fun `D4 cuenta cambiada en otra caja con el mismo importe - el ticket dice Cuenta Mesa N, nunca la foto de platillos`() = runTest {
        try {
            val r = cobrarMesaT4(importe = 10000, mesa = mesaT4(cargo = 0, total = 10000))
            assertEquals("Cuenta Mesa 5", r.items.single().name)
            assertEquals(10000, r.total); assertTrue(cuadra(r))
        } finally { paymentViewModel.viewModelScope.cancel() }
    }

}
