package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
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
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.referrals.domain.usecase.CaptureReferralUseCase
import com.avoqado.pos.referrals.domain.usecase.ValidateReferralUseCase
import com.avoqado.pos.tpvsettings.data.TpvSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
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
            orderRepository.createOrder(capture(cobrarSlot), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-1")))
        coEvery {
            terminalPaymentService.sendPaymentToTerminal(any(), any(), any(), any(), any(), any())
        } returns TerminalPaymentResult.Error("Terminal timeout")

        paymentViewModel.startPaymentFlow(cart)
        paymentViewModel.selectPaymentMethod(PaymentMethod.CARD)
        paymentViewModel.selectTerminalAndPay("t1")
        advanceUntilIdle()

        val diferirSlot = slot<CreateOrderRequest>()
        coEvery {
            orderRepository.createOrder(capture(diferirSlot), any(), any(), any(), any())
        } returns Result.success(CreateOrderResponse(success = true, data = OrderData(id = "order-2")))

        val cartViewModel = createCartViewModel()
        cartViewModel.restoreSavedCart(comoCarritoGuardado(carrito))
        cartViewModel.createPayLaterOrder("cust-1")
        advanceUntilIdle()

        assertEquals(
            "cobrar y pagar-después tienen que mandar los MISMOS items",
            cobrarSlot.captured.items,
            diferirSlot.captured.items,
        )
        assertEquals(2, diferirSlot.captured.items.size)
        assertNotNull(diferirSlot.captured.items.first().promotionRef)
    }

    // MARK: - 7. El total autoritativo es el del server

    @Test
    fun `con promocion el POS cobra el total que devolvio el server`() = runTest {
        val amountSlot = slot<Int>()
        coEvery {
            orderRepository.createOrder(any(), any(), any(), any(), any())
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
            orderRepository.createOrder(any(), any(), any(), any(), any())
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
            orderRepository.createOrder(any(), any(), any(), any(), any())
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
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(
            CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 99.0)),
        )
        val cobrado = slot<Int>()
        coEvery {
            orderRepository.recordCashPayment(any(), capture(cobrado), any(), any(), any(), any(), any())
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
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(
            // El server pide 1 centavo más del que el cliente puso sobre el mostrador.
            CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 99.01)),
        )
        val cobrado = slot<Int>()
        coEvery {
            orderRepository.recordCashPayment(any(), capture(cobrado), any(), any(), any(), any(), any())
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
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.failure(java.net.UnknownHostException())
        val encolado = slot<CreateOrderRequest>()
        coEvery {
            cashPaymentRepository.queueCashPayment(
                capture(encolado), any(), any(), any(), any(), any(), any(), any(), any(), any(),
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
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(
            CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 99.0)),
        )
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any())
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
            orderRepository.createOrder(any(), any(), any(), any(), any())
        } returns Result.success(
            CreateOrderResponse(success = true, data = OrderData(id = "order-1", total = 120.0)),
        )
        coEvery {
            orderRepository.recordCashPayment(any(), any(), any(), any(), any(), any(), any())
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
            orderRepository.createOrder(any(), any(), any(), any(), capture(externalIds))
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
            orderRepository.createOrder(any(), any(), any(), any(), capture(externalIds))
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
        coEvery { cashDrawerRepository.addCashSale(any(), any()) } returns null
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
            comandaDispatcher = ComandaDispatcher(printConfigRepository, comandaPrinter, printerService),
            tableSession = com.avoqado.pos.tables.data.TableSession(),
            syncOutbox = mockk(relaxed = true),
            customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
            areaTicketRepository = areaTicketRepository,
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

    private fun createCartViewModel(): CartViewModel = CartViewModel(
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
        tableSession = com.avoqado.pos.tables.data.TableSession(),
        customerDisplay = com.avoqado.pos.customerdisplay.CustomerDisplayState(),
        areaTicketRepository = areaTicketRepository,
        walletScanRepository = mockk(relaxed = true),
    )
}
