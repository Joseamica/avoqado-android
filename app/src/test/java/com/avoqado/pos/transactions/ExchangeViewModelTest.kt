package com.avoqado.pos.transactions

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.pos.data.ProductsRepository
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.VariantOption
import com.avoqado.pos.transactions.data.model.*
import com.avoqado.pos.transactions.presentation.exchange.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExchangeViewModelTest {
    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())
    private val connected = MutableStateFlow(true)
    private val reachable = MutableStateFlow(true)
    private val catalog = MutableStateFlow<List<Product>>(emptyList())
    private val products = mockk<ProductsRepository> {
        every { products } returns catalog
        every { isLoading } returns MutableStateFlow(false)
        every { error } returns MutableStateFlow(null)
        coEvery { fetchProducts(any(), any()) } returns Unit
        every { searchProducts(any()) } answers { catalog.value.filter { it.name.contains(firstArg<String>(), true) } }
    }
    private val monitor = mockk<ConnectivityMonitor> {
        every { isConnected } returns connected
        every { isServerReachable } returns reachable
        every { isOnline() } answers { connected.value && reachable.value }
    }
    private fun item(id: String = "line", remaining: Int = 1) = TransactionItem(id = id, productId = id,
        productName = "Falda M", quantity = 3, chargedTotalCents = 1000, returnedQtyOrder = 3 - remaining,
        returnedCentsOrder = if (remaining == 1) 667 else 0, remainingQtyOrder = remaining,
        isPromotionLine = false, restockable = true, productType = "RETAIL", currentListPriceCents = 500,
        productGroupId = "skirt", variantOptions = listOf(VariantOption("Talla", "M", 0, 0)))
    private fun sale(vararg items: TransactionItem) = Transaction(id = "payment", items = items.toList(),
        exchangeable = true, payoutOptions = listOf(PayoutOption("CASH", 99999, 99999, false)),
        exchange = ExchangeLinks(0, emptyList()))
    private fun vm(vararg rows: TransactionItem) = ExchangeViewModel(products, monitor).also { it.start(sale(*rows)) }

    @Test fun `opening loads the catalog once without resetting selections on recomposition`() {
        val vm = vm(item())
        vm.toggleReturned("line")
        vm.start(sale(item()))
        assertEquals(1, vm.state.value.returned.single().quantity)
        coVerify(exactly = 1) { products.fetchProducts(null, true) }
    }

    @Test fun `barcode search uses the shared catalog without requiring CartViewModel`() {
        catalog.value = listOf(Product("scan", "Shampoo", barcode = "750123456"))
        val vm = vm(item())
        assertEquals(listOf("scan"), vm.searchProducts("750123456").map { it.id })
    }

    @Test fun `same product without a family can replace a defective item at its paid price`() {
        val product = Product("line", "Shampoo", priceValue = 5.0)
        catalog.value = listOf(product)
        val vm = vm(item().copy(productGroupId = null))
        vm.toggleReturned("line")
        vm.addTaken(product)
        assertEquals(333L, vm.state.value.calculation.takenCents)
    }

    @Test fun `returned counter uses the order remainder and trimmed cents`() {
        val vm = vm(item())
        vm.toggleReturned("line")
        vm.setReturnedQuantity("line", 100)
        assertEquals(1, vm.state.value.returned.single().quantity)
        assertEquals(333L, vm.state.value.calculation.returnedCents)
        vm.setReturnedQuantity("line", 0)
        assertEquals(1, vm.state.value.returned.single().quantity)
    }

    @Test fun `two remaining pieces cap the counter and trim the second piece`() {
        val vm = vm(item(remaining = 2).copy(returnedCentsOrder = 667))
        vm.toggleReturned("line")
        assertEquals(333L, vm.state.value.calculation.returnedCents)
        vm.setReturnedQuantity("line", 3)
        assertEquals(2, vm.state.value.returned.single().quantity)
        assertEquals(333L, vm.returnedValue(vm.state.value.returned.single()))
        assertEquals(333L, vm.state.value.calculation.returnedCents)
        vm.setReturnedQuantity("line", 1)
        assertEquals(333L, vm.state.value.calculation.returnedCents)
    }

    @Test fun `encoded draft preserves explicit pointers and recalculates the expected difference`() {
        val automatic = Product("large", "Falda L", priceValue = 5.0, productGroupId = "skirt")
        val explicit = Product("extra-large", "Falda XL", priceValue = 10.0, productGroupId = "skirt")
        catalog.value = listOf(automatic, explicit)
        val original = item(remaining = 2).copy(returnedCentsOrder = 667)
        val vm = vm(original)
        vm.toggleReturned("line")
        vm.setReturnedQuantity("line", 2)
        vm.setReason("line", ExchangeReason.SIZE)
        vm.addTaken(automatic)
        vm.addTaken(explicit, "line")
        assertEquals(listOf(0L, 666L), vm.state.value.calculation.takenPieces.map { it.priceCents })
        assertEquals(333L, vm.state.value.calculation.differenceCents)
        val json = kotlinx.serialization.json.Json
        val draft = json.decodeFromString(ExchangeDraft.serializer(),
            json.encodeToString(ExchangeDraft.serializer(), vm.prepareExchange()!!))
        val returned = draft.returned.flatMap { row ->
            pieceValuesCents(original.chargedTotalCents!!.toInt(), original.quantity,
                original.returnedQtyOrder!!, original.returnedCentsOrder!!.toInt(), row.quantity).map {
                ReturnedPiece(row.orderItemId, original.productId, original.productGroupId,
                    it.toLong(), original.currentListPriceCents)
            }
        }
        assertEquals(listOf(333L, 0L), returned.map { it.paidCents })
        val recalculated = calculateExchange(returned, draft.taken.map { row ->
            val product = catalog.value.single { it.id == row.productId }
            TakenLine(row.productId, product.productGroupId, catalogPriceCents(product)!!,
                row.quantity, row.inheritsFromOrderItemId)
        })
        assertEquals(draft.settlement.expectedDifferenceCents, recalculated.differenceCents)
        assertEquals(333L, draft.settlement.expectedDifferenceCents)
        assertEquals(listOf(null, "line"), draft.taken.map { it.inheritsFromOrderItemId })
        assertEquals("conserva la rebaja de la talla M", vm.inheritedLabel(0))
    }

    @Test fun `promotion selection is atomic per orderPromotionId and quantity is fixed`() {
        val a = item("a", 3).copy(isPromotionLine = true, orderPromotionId = "promo1")
        val b = item("b", 3).copy(isPromotionLine = true, orderPromotionId = "promo1")
        val c = item("c", 3).copy(isPromotionLine = true, orderPromotionId = "promo2")
        val missing = item("missing").copy(isPromotionLine = true)
        val vm = vm(a, b, c, missing)
        vm.toggleReturned("a")
        vm.setReturnedQuantity("b", 1)
        assertEquals(listOf(3, 3, 0, 0), vm.state.value.returned.map { it.quantity })
        vm.toggleReturned("missing")
        assertEquals(TextosCambio.PROMOCION_COMPLETA, vm.state.value.returned.last().blockedReason)
        vm.toggleReturned("b")
        assertTrue(vm.state.value.returned.all { it.quantity == 0 })
    }

    @Test fun `promotion selection ignores unrelated rows sharing its id`() {
        val vm = vm(
            item("promo", 3).copy(isPromotionLine = true, orderPromotionId = "p"),
            item("other").copy(orderPromotionId = "p"),
            item("blocked").copy(orderPromotionId = "p", remainingQtyOrder = null)
        )
        vm.toggleReturned("promo")
        assertEquals(listOf(3, 0, 0), vm.state.value.returned.map { it.quantity })
        assertEquals(1000L, vm.state.value.calculation.returnedCents)
        assertEquals(TextosCambio.NOT_ATTRIBUTABLE, vm.state.value.returned[2].blockedReason)
        vm.toggleReturned("promo")
        assertEquals(listOf(0, 0, 0), vm.state.value.returned.map { it.quantity })
    }

    @Test fun `a blocked member prevents selecting its entire promotion`() {
        val a = item("a").copy(isPromotionLine = true, orderPromotionId = "promo")
        val b = item("b").copy(isPromotionLine = true, orderPromotionId = "promo", notExchangeableReason = "NOT_ATTRIBUTABLE")
        val vm = vm(a, b)
        vm.toggleReturned("a")
        assertTrue(vm.state.value.returned.all { it.quantity == 0 && it.blockedReason != null })
    }

    @Test fun `DEFECT unchecks restock and it can be checked again only if restockable`() {
        val vm = vm(item(), item("no-stock").copy(restockable = false))
        vm.toggleReturned("line")
        vm.setReason("line", ExchangeReason.DEFECT)
        assertFalse(vm.state.value.returned.first().restock)
        vm.setRestock("line", true)
        assertTrue(vm.state.value.returned.first().restock)
        vm.setRestock("no-stock", true)
        assertFalse(vm.state.value.returned.last().restock)
    }

    @Test fun `same family taken piece inherits and exposes its source label`() {
        val p = Product("large", "Falda L", priceValue = 5.0, productGroupId = "skirt", active = true)
        catalog.value = listOf(p)
        val vm = vm(item())
        vm.toggleReturned("line")
        vm.addTaken(p)
        assertEquals(333L, vm.state.value.calculation.takenCents)
        assertEquals("conserva la rebaja de la talla M", vm.inheritedLabel(0))
    }

    @Test fun `bar shows the three exact cases`() {
        assertEquals("Diferencia $0.00 · no se cobra ni se devuelve nada", exchangeBar(0).label)
        assertEquals("Hacer cambio parejo", exchangeBar(0).action)
        assertEquals("La clienta paga $1.25", exchangeBar(125).label)
        assertEquals("Cobrar $1.25", exchangeBar(125).action)
        assertEquals("A favor de la clienta $1.25", exchangeBar(-125).label)
        assertEquals("Devolver $1.25", exchangeBar(-125).action)
        assertTrue(exchangeBar(-125).isPayout)
    }

    @Test fun `prepare exposes only validated selection and does not queue offline`() = runTest {
        val p = Product("other", "Blusa", priceValue = 5.0, active = true, catalogAt = "2026-10-26T10:00:00Z")
        catalog.value = listOf(p)
        val vm = vm(item())
        vm.toggleReturned("line")
        vm.addTaken(p)
        assertNull(vm.prepareExchange())
        vm.setReason("line", ExchangeReason.SIZE)
        val body = vm.prepareExchange()!!
        assertEquals("payment", body.originalPaymentId)
        assertEquals("SIZE", body.returned.single().reason)
        assertEquals(500L, body.taken.single().displayedUnitPriceCents)
        assertEquals(167L, body.settlement.expectedDifferenceCents)
        assertEquals("""{"originalPaymentId":"payment","returned":[{"orderItemId":"line","quantity":1,"restock":true,"reason":"SIZE"}],"taken":[{"productId":"other","quantity":1,"displayedUnitPriceCents":500,"catalogAt":"2026-10-26T10:00:00Z"}],"settlement":{"kind":"COLLECT","expectedDifferenceCents":167}}""",
            kotlinx.serialization.json.Json.encodeToString(ExchangeDraft.serializer(), body))
        connected.value = false
        assertNull(vm.prepareExchange())
        assertNull(vm.preparedExchange.value)
        assertEquals(TextosCambio.SIN_CONEXION, vm.state.value.blockedReason)
        coVerify(exactly = 0) { products.applyLocalSale(any()) }
    }

    @Test fun `payout without capacity is blocked but an even change remains available`() {
        val p = Product("other", "Blusa", priceValue = 1.0, active = true)
        catalog.value = listOf(p)
        val vm = ExchangeViewModel(products, monitor)
        vm.start(sale(item()).copy(payoutOptions = emptyList()))
        vm.toggleReturned("line")
        vm.setReason("line", ExchangeReason.SIZE)
        vm.addTaken(p)
        assertEquals(TextosCambio.SIN_OPCIONES_DEVOLUCION, vm.state.value.blockedReason)
        val even = p.copy(productGroupId = "skirt", priceValue = 5.0)
        catalog.value = listOf(even)
        vm.removeTaken(0)
        vm.addTaken(even)
        assertEquals(0L, vm.state.value.calculation.differenceCents)
        assertNull(vm.state.value.blockedReason)
    }

    @Test fun `overflow is blocked instead of silently narrowing Long cents`() {
        val vm = vm(item().copy(chargedTotalCents = Int.MAX_VALUE.toLong() + 1))
        vm.toggleReturned("line")
        assertEquals(0, vm.state.value.returned.single().quantity)
        assertNotNull(vm.state.value.returned.single().blockedReason)
    }

    @Test fun `missing order quantities are unknown rather than already returned`() {
        val vm = vm(item().copy(remainingQtyOrder = null))
        assertEquals(TextosCambio.NOT_ATTRIBUTABLE, vm.state.value.returned.single().blockedReason)
        vm.toggleReturned("line")
        assertEquals(0, vm.state.value.returned.single().quantity)
    }

    @Test fun `catalog price above Int cents remains exact in calculation and draft`() {
        val p = Product("expensive", "Producto", priceValue = 22_000_000.0)
        catalog.value = listOf(p)
        val vm = vm(item())
        vm.toggleReturned("line")
        vm.setReason("line", ExchangeReason.SIZE)
        vm.addTaken(p)
        assertEquals(2_200_000_000L, vm.state.value.calculation.takenCents)
        assertEquals(2_200_000_000L, vm.prepareExchange()!!.taken.single().displayedUnitPriceCents)
    }

    @Test fun `invalid catalog prices are not offered or silently rounded to zero`() {
        val bad = Product("bad", "Producto", priceValue = Double.NaN)
        catalog.value = listOf(bad)
        val vm = vm(item())
        assertTrue(vm.searchProducts("").isEmpty())
        vm.addTaken(bad)
        assertTrue(vm.state.value.taken.isEmpty())
    }

    @Test fun `unrepresentable inherited price blocks confirmation instead of crashing`() {
        val p = Product("large", "Falda L", priceValue = 10_000_000_000_000.0, productGroupId = "skirt")
        val valid = Product("other", "Blusa", priceValue = 5.0)
        catalog.value = listOf(valid, p)
        val vm = vm(item().copy(currentListPriceCents = 1))
        vm.toggleReturned("line")
        vm.setReason("line", ExchangeReason.SIZE)
        vm.addTaken(valid)
        assertEquals(167L, vm.state.value.calculation.differenceCents)
        vm.addTaken(p)
        assertEquals(TextosCambio.NOT_ATTRIBUTABLE, vm.state.value.blockedReason)
        assertEquals(2, vm.state.value.taken.size)
        assertTrue(vm.state.value.calculation.takenPieces.isEmpty())
        assertEquals(0L, vm.state.value.calculation.differenceCents)
        assertEquals(TextosCambio.NOT_ATTRIBUTABLE, vm.state.value.bar.label)
        assertEquals(TextosCambio.CAMBIO, vm.state.value.bar.action)
        assertNull(vm.inheritedLabel(1))
        assertNull(vm.prepareExchange())
        assertNull(vm.preparedExchange.value)
        vm.removeTaken(1)
        assertEquals(167L, vm.state.value.calculation.differenceCents)
        assertNull(vm.state.value.blockedReason)
    }

    @Test fun `choosing a return after taking the same variant blocks confirmation`() {
        val p = Product("line", "Falda M", priceValue = 5.0, productGroupId = "skirt")
        catalog.value = listOf(p)
        val vm = vm(item())
        vm.addTaken(p)
        vm.toggleReturned("line")
        vm.setReason("line", ExchangeReason.SIZE)
        assertEquals(TextosCambio.TALLA_QUE_REGRESA, vm.state.value.blockedReason)
        assertNull(vm.prepareExchange())
    }
    @Test fun `server recalculation changes sign and a new confirmation preserves selection pointers`() {
        val product=Product("other", "Falda L", priceValue=5.0)
        catalog.value=listOf(product)
        val vm=vm(item())
        vm.toggleReturned("line"); vm.setReason("line",ExchangeReason.SIZE); vm.addTaken(product)
        assertEquals(167L,vm.prepareExchange()!!.settlement.expectedDifferenceCents)
        vm.applyServerCalculation(com.avoqado.pos.transactions.data.ExchangeServerCalculation("PAYOUT",-50,333,283,
            com.avoqado.pos.transactions.data.ExchangeResultLines(emptyList(),listOf(
                com.avoqado.pos.transactions.data.ExchangeTakenLine("other","Falda L",1,500,283,
                    listOf(com.avoqado.pos.transactions.data.ExchangeTakenPiece(283)),emptyList())))))
        assertEquals(-50L,vm.state.value.calculation.differenceCents)
        assertEquals("PAYOUT",vm.prepareExchange()!!.settlement.kind)
        assertEquals(-50L,vm.prepareExchange()!!.settlement.expectedDifferenceCents)
        vm.removeTaken(0)
        assertNull(vm.prepareExchange())
    }

    @Test fun `locked submission preserves selection and cannot prepare another exchange`() {
        val product=Product("other","Falda L",priceValue=5.0); catalog.value=listOf(product)
        val vm=vm(item()); vm.toggleReturned("line"); vm.setReason("line",ExchangeReason.SIZE); vm.addTaken(product)
        val before=vm.state.value
        vm.lockSubmission(true)
        vm.toggleReturned("line"); vm.setReturnedQuantity("line",2); vm.setReason("line",ExchangeReason.DEFECT)
        vm.setRestock("line",false); vm.removeTaken(0); vm.addTaken(product)
        assertEquals(before,vm.state.value); assertNull(vm.prepareExchange())
        vm.lockSubmission(false); assertNotNull(vm.prepareExchange())
    }

    @Test fun `unknown server calculation kind blocks confirmation without crashing`() {
        val product=Product("other","Falda L",priceValue=5.0); catalog.value=listOf(product)
        val vm=vm(item()); vm.toggleReturned("line"); vm.setReason("line",ExchangeReason.SIZE); vm.addTaken(product)
        vm.applyServerCalculation(com.avoqado.pos.transactions.data.ExchangeServerCalculation("FUTURE",1,1,2,
            com.avoqado.pos.transactions.data.ExchangeResultLines(emptyList(),emptyList())))
        assertNull(vm.prepareExchange()); assertNotNull(vm.state.value.blockedReason)
    }

    @Test fun `101 taken lines block confirmation with the article limit reason`() {
        val product=Product("other","Blouse",priceValue=5.0)
        catalog.value=listOf(product)
        val vm=vm(item())
        vm.toggleReturned("line")
        vm.setReason("line",ExchangeReason.SIZE)
        // Inject an oversized restored state: the add button already caps new selections at 100.
        val field=ExchangeViewModel::class.java.getDeclaredField("_state").apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST")
        val state=field.get(vm) as MutableStateFlow<ExchangeUiState>
        state.value=state.value.copy(taken=List(100) { ExchangeTakenRow(product) })
        assertEquals(100,vm.prepareExchange()!!.taken.size)
        state.value=state.value.copy(taken=List(101) { ExchangeTakenRow(product) })
        assertNull(vm.prepareExchange())
        assertEquals(101,vm.state.value.taken.size)
        assertEquals(TextosCambio.LIMITE_ARTICULOS,vm.state.value.blockedReason)
        assertNull(vm.preparedExchange.value)
    }

}
