package com.avoqado.pos.tables.presentation

import androidx.lifecycle.viewModelScope
import kotlinx.serialization.decodeFromString
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.SavedPrinter
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.PrinterInfo
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.OrderDetailItem
import com.avoqado.pos.tables.data.OrderDetailModifier
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * "Volver a imprimir pedido" (Acciones) — la ÚNICA pantalla que mira el
 * [ComandaPrinter.Result], y hasta ahora la única sin una sola prueba.
 *
 * 🔴 El bug que justifica este archivo (medido en una T3 el 2026-08-09):
 * `printComandas` devolvía Unit y se tragaba cada excepción en un log, así que
 * la reimpresión cantaba "Comandas reimpresas" **aunque no saliera ni un
 * papel** — 10 s de timeout contra una impresora inalcanzable y aun así
 * palomita verde. El mesero se iba a cocina a buscar una comanda que no
 * existía, y de paso le echaba la culpa a la cocina.
 *
 * La invariante que fijan estos tests: **el aviso tiene que corresponder al
 * papel que salió.** Nada impreso = error; impreso a medias = error explícito
 * con la cuenta exacta; todo impreso = éxito. Y el motivo se dice en palabras
 * de mesero (qué revisar), nunca con el error crudo del socket.
 *
 * A diferencia del disparo automático post-envío —donde una impresora caída
 * JAMÁS puede frenar una venta y por eso el Result se ignora a propósito—,
 * aquí la acción es MANUAL: el mesero la pidió y está esperando el papel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableOrderReprintComandasTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val subjects = mutableListOf<TableOrderViewModel>()

    @After
    fun closeSubjects() {
        subjects.forEach { it.viewModelScope.cancel() }
        subjects.clear()
    }

    // MARK: - Fixtures

    private val impresoraCocina = SavedPrinter(
        id = "pr1",
        name = "Cocina",
        connectionType = "wifi",
        address = "192.168.1.50",
        port = 9100,
        roles = listOf(PrinterRole.KITCHEN.value),
    )

    /** Venue configurado: una estación activa con su impresora. */
    private val configConEstacion = PrintConfig(
        printers = listOf(
            PrinterInfo(id = "pr1", name = "Cocina", connectionType = "NETWORK", address = "192.168.1.50:9100"),
        ),
        stations = listOf(StationInfo(id = "st1", name = "Cocina", printerId = "pr1", active = true)),
        defaultStationId = "st1",
    )

    private fun linea(id: String, nombre: String, course: String? = null) = OrderDetailItem(
        id = id,
        productId = "prod-$id",
        productName = nombre,
        quantity = 1,
        course = course,
    )

    private fun ok(attempted: Int, printed: Int) =
        ComandaPrinter.Result(attempted = attempted, printed = printed, skippedNoPrinter = 0, lastError = null)

    /** El error REAL que devolvió la T3: crudo, en inglés y con puertos. */
    private val errorRealDeLaT3 =
        "failed to connect to /10.0.2.2 (port 9100) from /192.168.100.217 (port 50942) after 10000ms"

    private val comandaPrinter = mockk<ComandaPrinter>(relaxed = true)
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val printConfigRepository = mockk<PrintConfigRepository>(relaxed = true)
    private val tableSession = TableSession()

    /**
     * Arma el ViewModel con una mesa abierta y su cheque ya cargado — que es el
     * único estado desde el que "Volver a imprimir pedido" es alcanzable.
     */
    private fun buildVm(
        items: List<OrderDetailItem> = listOf(linea("i1", "Taco")),
        config: PrintConfig = configConEstacion,
        defaultKitchenPrinter: SavedPrinter? = impresoraCocina,
        initialTotalCents: Int = 12000,
        order: OrderDetail? = null,
        readOrder: (suspend () -> Result<OrderDetail>)? = null,
    ): TableOrderViewModel {
        val repository = mockk<TableServiceRepository>(relaxed = true)
        every { repository.tables } returns MutableStateFlow(emptyList())
        every { repository.ownership } returns MutableStateFlow(TableServiceRepository.TableOwnership())
        coEvery { repository.getOrderDetail(any(), any()) } coAnswers {
            readOrder?.invoke() ?: Result.success(order ?: OrderDetail(id = "o1", orderNumber = "ORD-1", items = items))
        }

        val secureStorage = mockk<SecureStorage>(relaxed = true)
        every { secureStorage.venueId } returns "venue-1"

        val syncOutbox = mockk<SyncOutbox>(relaxed = true)
        every { syncOutbox.acks } returns MutableSharedFlow()
        every { syncOutbox.pendingCount } returns MutableStateFlow(0)

        val connectivityMonitor = mockk<ConnectivityMonitor>(relaxed = true)
        every { connectivityMonitor.isConnected } returns MutableStateFlow(true)

        coEvery { printConfigRepository.refresh(any()) } returns Unit
        every { printConfigRepository.getCurrentConfig() } returns config
        every { printerService.getDefaultPrinter(PrinterRole.KITCHEN) } returns defaultKitchenPrinter

        tableSession.start(
            TableSession.Active(
                tableId = "t1",
                tableNumber = "5",
                areaName = null,
                orderId = "o1",
                orderNumber = "ORD-1",
                version = 1,
                totalCents = initialTotalCents,
                mode = TableSession.Mode.ORDERING,
            ),
        )

        return TableOrderViewModel(
            repository = repository,
            tableSession = tableSession,
            printConfigRepository = printConfigRepository,
            comandaPrinter = comandaPrinter,
            printerService = printerService,
            secureStorage = secureStorage,
            syncOutbox = syncOutbox,
            productsRepository = mockk(relaxed = true),
            connectivityMonitor = connectivityMonitor,
            timeEntryRepository = mockk(relaxed = true),
            comandaDispatcher = mockk(relaxed = true),
            comandasPendientesStore = mockk(relaxed = true),
        ).also { subjects += it; it.loadCheck() }
    }

    // MARK: - 🔴 El bug: nada impreso NUNCA es éxito

    @Test
    fun `sin papel no canta exito`() = runTest {
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 2, printed = 0, skippedNoPrinter = 0, lastError = errorRealDeLaT3)

        val vm = buildVm()
        vm.reprintComandas()

        assertTrue("nada salió: tiene que verse como fallo", vm.actionIsError.value)
        assertEquals("No se pudo reimprimir la comanda", vm.actionMessage.value)
        assertFalse(
            "el aviso no puede insinuar que la comanda salió",
            vm.actionMessage.value.orEmpty().contains("reimpres", ignoreCase = true),
        )
    }

    @Test
    fun `el motivo se dice en palabras de mesero, no con el error del socket`() = runTest {
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 1, printed = 0, skippedNoPrinter = 0, lastError = errorRealDeLaT3)

        val vm = buildVm()
        vm.reprintComandas()

        val hint = vm.actionHint.value
        assertNotNull("un fallo sin siguiente paso deja al mesero atorado", hint)
        assertTrue(
            "el timeout debe traducirse a qué revisar: $hint",
            hint!!.contains("no responde") && hint.contains("misma red"),
        )
        // El error crudo (inglés, IPs y puertos) jamás llega a pantalla.
        assertFalse(hint.contains("failed to connect"))
        assertFalse(vm.actionMessage.value.orEmpty().contains("failed to connect"))
    }

    @Test
    fun `cero comandas intentadas tampoco es exito`() = runTest {
        // Ruteo que no produjo ni un plan: no hay papel, luego no hay éxito.
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns ok(attempted = 0, printed = 0)

        val vm = buildVm()
        vm.reprintComandas()

        assertTrue(vm.actionIsError.value)
        assertEquals("No se pudo reimprimir la comanda", vm.actionMessage.value)
    }

    // MARK: - Parcial ≠ completo

    @Test
    fun `parcial no se puede confundir con exito completo`() = runTest {
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 3, printed = 1, skippedNoPrinter = 0, lastError = errorRealDeLaT3)

        val vm = buildVm()
        vm.reprintComandas()

        assertTrue("faltaron comandas: es un fallo, no un éxito", vm.actionIsError.value)
        // La cuenta EXACTA importa: el mesero tiene que saber cuántas faltan.
        assertEquals("Sólo se reimprimieron 1 de 3 comandas", vm.actionMessage.value)
        assertTrue(
            "el hint debe decir qué revisar",
            vm.actionHint.value.orEmpty().contains("no responde"),
        )
    }

    @Test
    fun `parcial sin error de impresora manda a revisar las estaciones que faltaron`() = runTest {
        // printed < attempted por estaciones sin impresora, no por un fallo de red.
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 2, printed = 1, skippedNoPrinter = 1, lastError = null)

        val vm = buildVm()
        vm.reprintComandas()

        assertTrue(vm.actionIsError.value)
        assertEquals("Sólo se reimprimieron 1 de 2 comandas", vm.actionMessage.value)
        assertEquals("Revisa las impresoras de las estaciones que faltaron.", vm.actionHint.value)
    }

    @Test
    fun `los cursos se suman entre si — uno bueno y uno malo es parcial`() = runTest {
        // Dos tiempos ⇒ dos llamadas a printComandas. El acumulado manda: 1 de 2.
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returnsMany listOf(
            ok(attempted = 1, printed = 1),
            ComandaPrinter.Result(attempted = 1, printed = 0, skippedNoPrinter = 0, lastError = errorRealDeLaT3),
        )

        val vm = buildVm(
            items = listOf(
                linea("i1", "Guacamole", course = "Aperitivos"),
                linea("i2", "Arrachera", course = "Principales"),
            ),
        )
        vm.reprintComandas()

        coVerify(exactly = 2) { comandaPrinter.printComandas(any(), any(), any(), any(), any()) }
        assertTrue("un curso sin imprimir no puede quedar tapado por el otro", vm.actionIsError.value)
        assertEquals("Sólo se reimprimieron 1 de 2 comandas", vm.actionMessage.value)
    }

    // MARK: - Éxito de verdad

    @Test
    fun `todo impreso canta exito`() = runTest {
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns ok(attempted = 2, printed = 2)

        val vm = buildVm()
        vm.reprintComandas()

        assertFalse("salió el papel: no es un error", vm.actionIsError.value)
        assertEquals("2 comandas reimpresas", vm.actionMessage.value)
    }

    @Test
    fun `una sola comanda se anuncia en singular`() = runTest {
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns ok(attempted = 1, printed = 1)

        val vm = buildVm()
        vm.reprintComandas()

        assertFalse(vm.actionIsError.value)
        assertEquals("Comanda reimpresa", vm.actionMessage.value)
    }

    // MARK: - Sin impresora resuelta: accionable, nunca silencioso

    @Test
    fun `estaciones sin impresora dicen exactamente que hacer`() = runTest {
        // Hay estación activa (el guard de arriba no aplica), pero ninguna
        // comanda llegó a intentarse: sin impresora resuelta NI default.
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 1, printed = 0, skippedNoPrinter = 1, lastError = null)

        val vm = buildVm()
        vm.reprintComandas()

        assertTrue(vm.actionIsError.value)
        assertEquals("No se reimprimió: no hay impresora para esas estaciones", vm.actionMessage.value)
        assertEquals(
            "Configúralas en Más → Impresoras, o asígnale una impresora de cocina al local.",
            vm.actionHint.value,
        )
    }

    /**
     * Saltadas PERO con un error de impresora encima: manda el error, porque
     * "configura la estación" sería un consejo equivocado cuando lo que pasó es
     * que la impresora no contestó.
     */
    @Test
    fun `saltadas con error gana el motivo de la impresora`() = runTest {
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 2, printed = 0, skippedNoPrinter = 1, lastError = errorRealDeLaT3)

        val vm = buildVm()
        vm.reprintComandas()

        assertTrue(vm.actionIsError.value)
        assertEquals("No se pudo reimprimir la comanda", vm.actionMessage.value)
        assertTrue(vm.actionHint.value.orEmpty().contains("no responde"))
    }

    // MARK: - El Result mismo

    @Test
    fun `los helpers del Result no confunden nada con parcial`() {
        val nada = ComandaPrinter.Result(attempted = 2, printed = 0, skippedNoPrinter = 0, lastError = "x")
        assertTrue(nada.nothingPrinted)
        assertFalse("cero impresas no es 'parcial', es nada", nada.partial)

        val parcial = ComandaPrinter.Result(attempted = 3, printed = 1, skippedNoPrinter = 0, lastError = "x")
        assertFalse(parcial.nothingPrinted)
        assertTrue(parcial.partial)

        val completo = ComandaPrinter.Result(attempted = 2, printed = 2, skippedNoPrinter = 0, lastError = null)
        assertFalse(completo.nothingPrinted)
        assertFalse("todo impreso no es parcial", completo.partial)
    }

    /**
     * El único guard que SÍ corta antes de intentar. Vale porque no existe
     * ninguna impresora de cocina en todo el dispositivo — no es el guard de
     * configuración que la regla offline-first prohíbe (ese exigía estaciones
     * y dejaba sin comandas a un local que sí tenía su impresora).
     */
    @Test
    fun `sin ninguna impresora de cocina avisa antes de intentar`() = runTest {
        val vm = buildVm(config = PrintConfig(), defaultKitchenPrinter = null)
        vm.reprintComandas()

        assertTrue(vm.actionIsError.value)
        assertEquals("No hay ninguna impresora de cocina configurada", vm.actionMessage.value)
        assertEquals("Agrega una en Más → Impresoras y asígnale el rol de Cocina.", vm.actionHint.value)
        coVerify(exactly = 0) { comandaPrinter.printComandas(any(), any(), any(), any(), any()) }
        assertFalse("el spinner no puede quedarse prendido", vm.isReprinting.value)
    }

    /**
     * Sin estaciones PERO con impresora de cocina se reimprime igual (fail-open,
     * regla offline-first §4.1a): el ruteo cae a "SIN ESTACIÓN" y sale por la
     * impresora local. Abortar aquí dejaba a un local sin estaciones sin poder
     * reimprimir NADA.
     */
    @Test
    fun `sin estaciones pero con impresora de cocina se reimprime igual`() = runTest {
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns ok(attempted = 1, printed = 1)

        val vm = buildVm(config = PrintConfig(), defaultKitchenPrinter = impresoraCocina)
        vm.reprintComandas()

        coVerify(exactly = 1) { comandaPrinter.printComandas(any(), any(), any(), any(), any()) }
        assertFalse(vm.actionIsError.value)
        assertEquals("Comanda reimpresa", vm.actionMessage.value)
    }

    // MARK: - Nada que reimprimir

    @Test
    fun `sin articulos enviados no se llama a la impresora`() = runTest {
        // Líneas de importe libre (productId null) no se pueden rutear.
        val vm = buildVm(items = listOf(OrderDetailItem(id = "i1", productId = null, productName = "Varios")))
        vm.reprintComandas()

        assertEquals("No hay artículos enviados para reimprimir", vm.actionMessage.value)
        coVerify(exactly = 0) { comandaPrinter.printComandas(any(), any(), any(), any(), any()) }
    }

    // MARK: - El spinner no se queda prendido

    @Test
    fun `isReprinting vuelve a false aunque no se imprima nada`() = runTest {
        coEvery { comandaPrinter.printComandas(any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 1, printed = 0, skippedNoPrinter = 0, lastError = errorRealDeLaT3)

        val vm = buildVm()
        vm.reprintComandas()

        assertFalse("un fallo no puede dejar el botón bloqueado", vm.isReprinting.value)
    }

    // MARK: - I4 (revisión de conjunto, Fase 3 del diseñador de tickets): la pre-cuenta y la cortesía

    private val impresoraRecibos = SavedPrinter(
        id = "pr-recibo",
        name = "Recibos",
        connectionType = "wifi",
        address = "192.168.1.60",
        port = 9100,
        roles = listOf(PrinterRole.RECEIPT.value),
    )

    private fun cuentaConCargoYExtra() = OrderDetail(
        id = "o1",
        orderNumber = "ORD-1",
        subtotal = 170.0,
        serviceChargeAmount = 17.0,
        total = 187.0,
        items = listOf(
            linea("cafe-1", "Café Mesas C2").copy(unitPrice = 80.0, total = 80.0),
            linea("cafe-2", "Café Mesas C2").copy(
                unitPrice = 80.0,
                total = 90.0,
                modifiers = listOf(OrderDetailModifier(name = "Extra Mesas C2", price = 10.0)),
            ),
        ),
    )

    @Test
    fun `precuenta conserva subtotal de productos sin sumar el cargo`() = runTest {
        every { printerService.getDefaultPrinter(PrinterRole.RECEIPT) } returns impresoraRecibos
        val impreso = slot<ReceiptData>()
        coEvery { printerService.printReceipt(capture(impreso), any()) } returns Unit

        buildVm(order = cuentaConCargoYExtra()).printPreBill()

        assertEquals(17000, impreso.captured.subtotal)
        assertEquals(18700, impreso.captured.total)
        assertNull(impreso.captured.discountAmount)
    }

    // D3, 19-oct: la pre-cuenta salía sin «Nota: sin aceite» aunque la cuenta la mostraba y el ticket del cobro la imprime.
    @Test
    fun `precuenta lleva la nota del articulo como el ticket del cobro`() = runTest {
        every { printerService.getDefaultPrinter(PrinterRole.RECEIPT) } returns impresoraRecibos
        val impreso = slot<ReceiptData>()
        coEvery { printerService.printReceipt(capture(impreso), any()) } returns Unit
        val cuenta = cuentaConCargoYExtra()
        val conNota = cuenta.copy(items = listOf(cuenta.items[0].copy(notes = "sin aceite"), cuenta.items[1].copy(notes = "  ")))

        buildVm(order = conNota).printPreBill()

        assertEquals(listOf("sin aceite", null), impreso.captured.items.map { it.note })
    }

    @Test
    fun `precuenta entrega el cargo separado al impresor`() = runTest {
        every { printerService.getDefaultPrinter(PrinterRole.RECEIPT) } returns impresoraRecibos
        val impreso = slot<ReceiptData>()
        coEvery { printerService.printReceipt(capture(impreso), any()) } returns Unit

        buildVm(order = cuentaConCargoYExtra()).printPreBill()

        assertEquals(1700, impreso.captured.serviceChargeAmount)
        assertEquals(18700, impreso.captured.total)
    }

    @Test
    fun `precuenta conserva el nombre del extra sin volver a sumarlo`() = runTest {
        every { printerService.getDefaultPrinter(PrinterRole.RECEIPT) } returns impresoraRecibos
        val impreso = slot<ReceiptData>()
        coEvery { printerService.printReceipt(capture(impreso), any()) } returns Unit

        buildVm(order = cuentaConCargoYExtra()).printPreBill()

        assertEquals(listOf("Extra Mesas C2"), impreso.captured.items[1].modifiers)
        assertEquals(9000, impreso.captured.items[1].totalPrice)
        assertEquals(18700, impreso.captured.total)
    }

    @Test
    fun `precuenta conserva descuento del servidor sin recalcular el total`() = runTest {
        every { printerService.getDefaultPrinter(PrinterRole.RECEIPT) } returns impresoraRecibos
        val impreso = slot<ReceiptData>()
        coEvery { printerService.printReceipt(capture(impreso), any()) } returns Unit

        buildVm(order = cuentaConCargoYExtra().copy(discountAmount = 10.0, total = 177.0)).printPreBill()

        assertEquals(1000, impreso.captured.discountAmount)
        assertEquals(17000, impreso.captured.subtotal)
        assertEquals(1700, impreso.captured.serviceChargeAmount)
        assertEquals(17700, impreso.captured.total)
    }

    /**
     * iOS ya manda `isCortesia` en la pre-cuenta (`TableOrderViewModel.swift:1253-1275`); Android
     * no lo mandaba, así que un artículo de cortesía imprimía su PRECIO en la pre-cuenta de la
     * mesa mientras el iPad, para la MISMA cuenta, imprimía «CORTESÍA» (revisión de conjunto, I4).
     */
    @Test
    fun `P1 la pre-cuenta marca el articulo de cortesia — antes imprimia su precio, distinto del iPad`() = runTest {
        every { printerService.getDefaultPrinter(PrinterRole.RECEIPT) } returns impresoraRecibos
        val impreso = slot<ReceiptData>()
        coEvery { printerService.printReceipt(capture(impreso), any()) } returns Unit

        val cortesia = linea("i-cortesia", "Café de cortesía").copy(isCortesia = true)
        val vm = buildVm(items = listOf(cortesia))
        vm.printPreBill()

        assertTrue(
            "la pre-cuenta debe marcar el artículo como cortesía, igual que la del iPad para la misma cuenta",
            impreso.captured.items.single().isCortesia,
        )
    }
    private fun product4Panel(kind: String, readOrder: (suspend () -> Result<OrderDetail>)? = null): Pair<TableOrderViewModel, io.mockk.CapturingSlot<ReceiptData>> {
        every { printerService.getDefaultPrinter(PrinterRole.RECEIPT) } returns impresoraRecibos
        val captured = slot<ReceiptData>()
        coEvery { printerService.printReceipt(capture(captured), any()) } returns Unit
        val order = PreCuentaProducto4Fixtures.order(kind)
        return buildVm(order = order, initialTotalCents = kotlin.math.round(order.total * 100).toInt(), readOrder = readOrder) to captured
    }

    @Test fun `P4 detalle transporta IVA agregado16 sin recalcular total116`() = runTest {
        val (panel, receipt) = product4Panel("taxAdded")
        panel.printPreBill()
        assertTrue("Debe imprimirse el recibo real del VM", receipt.isCaptured)
        assertEquals(1600, receipt.captured.taxAmount)
        assertEquals(11600, receipt.captured.total)
        val text = PreCuentaProducto4Fixtures.text(receipt.captured)
        PreCuentaProducto4Fixtures.amount(text, "IVA agregado:", "$16.00")
        assertFalse(text.contains("IVA incluido cuando aplica"))
    }

    @Test fun `P4 detalle no agrega IVA cuando el servidor declara agregado0`() = runTest {
        val (panel, receipt) = product4Panel("taxIncluded")
        panel.printPreBill()
        assertTrue("Debe imprimirse el recibo real del VM", receipt.isCaptured)
        assertEquals(0, receipt.captured.taxAmount)
        assertEquals(10000, receipt.captured.total)
        val text = PreCuentaProducto4Fixtures.text(receipt.captured)
        assertFalse(text.contains("IVA agregado:"))
        assertTrue(text.contains("IVA incluido cuando aplica"))
    }

    @Test fun `P4 detalle imprime abono50 propina10 con snapshot197 pagado60 porpagar137`() = runTest {
        val (panel, receipt) = product4Panel("partialTip")
        panel.printPreBill()
        assertTrue("Cuenta abierta con snapshot de saldo sí imprime", receipt.isCaptured)
        assertEquals(17000, receipt.captured.subtotal)
        assertEquals(1700, receipt.captured.serviceChargeAmount)
        assertEquals(19700, receipt.captured.total)
        assertEquals(1000, receipt.captured.tipAmount)
        assertEquals(13700, panel.payableTotalCents)
        val text = PreCuentaProducto4Fixtures.text(receipt.captured)
        PreCuentaProducto4Fixtures.amount(text, "Ya pagado", "$60.00")
        PreCuentaProducto4Fixtures.amount(text, "Por pagar", "$137.00")
        val withoutTotals = PreCuentaProducto4Fixtures.text(receipt.captured, listOf(com.avoqado.pos.printing.receiptlayout.ReceiptBlock.OrderInfo(showOrderType = false)))
        PreCuentaProducto4Fixtures.amount(withoutTotals, "Ya pagado", "$60.00")
        PreCuentaProducto4Fixtures.amount(withoutTotals, "Por pagar", "$137.00")
    }

    @Test fun `P4 detalle imprime abono50 sin propina con snapshot187 pagado50 porpagar137`() = runTest {
        val (panel, receipt) = product4Panel("partialNoTip")
        panel.printPreBill()
        assertTrue("Cuenta abierta con snapshot de saldo sí imprime", receipt.isCaptured)
        assertEquals(17000, receipt.captured.subtotal)
        assertEquals(1700, receipt.captured.serviceChargeAmount)
        assertEquals(18700, receipt.captured.total)
        assertNull(receipt.captured.tipAmount)
        assertEquals(13700, panel.payableTotalCents)
        val text = PreCuentaProducto4Fixtures.text(receipt.captured)
        PreCuentaProducto4Fixtures.amount(text, "Ya pagado", "$50.00")
        PreCuentaProducto4Fixtures.amount(text, "Por pagar", "$137.00")
        val withoutTotals = PreCuentaProducto4Fixtures.text(receipt.captured, listOf(com.avoqado.pos.printing.receiptlayout.ReceiptBlock.OrderInfo(showOrderType = false)))
        PreCuentaProducto4Fixtures.amount(withoutTotals, "Ya pagado", "$50.00")
        PreCuentaProducto4Fixtures.amount(withoutTotals, "Por pagar", "$137.00")
    }

    @Test fun `P4 detalle descuento legacy125 aplica100 y conserva cargo10 y total10`() = runTest {
        val (panel, receipt) = product4Panel("cappedDiscount")
        panel.printPreBill()
        assertTrue(receipt.isCaptured)
        assertEquals(10000, receipt.captured.subtotal)
        assertEquals(10000, receipt.captured.discountAmount)
        assertEquals(1000, receipt.captured.serviceChargeAmount)
        assertEquals(1000, receipt.captured.total)

    }

    @Test fun `P4 detalle getter usa restante137 del snapshot legacy y no127`() = runTest {
        val (panel, _) = product4Panel("partialTip")
        assertEquals(13700, panel.payableTotalCents)

    }

    @Test fun `P4 detalle REFUND completado no reabre deuda con snapshot restante0`() = runTest {
        val (panel, _) = product4Panel("refundZero")
        assertEquals(0, panel.payableTotalCents)

    }

    @Test fun `P4 detalle con abono completado sin snapshot sigue sin imprimir`() = runTest {
        val (panel, receipt) = product4Panel("noSnapshot")
        panel.printPreBill()
        assertFalse(receipt.isCaptured)
        assertTrue(panel.blockedNotice.value != null || panel.actionMessage.value != null)

    }

    @Test fun `P4 detalle cache fresco no imprime cuando el GET nuevo falla sin red`() = runTest {
        var response = Result.success(PreCuentaProducto4Fixtures.order("base"))
        var reads = 0
        val (panel, receipt) = product4Panel("base") { reads++; response }
        assertTrue(panel.chequeAlDia.value)
        response = Result.failure(java.io.IOException("sin red"))
        panel.printPreBill()
        assertEquals("La impresión debe intentar otro GET", 2, reads)
        assertFalse(receipt.isCaptured)
        val message = panel.blockedNotice.value ?: panel.actionMessage.value.orEmpty()
        assertTrue("Debe explicar el bloqueo de lectura: $message", message.contains("conex", true) || message.contains("servidor", true) || message.contains("actualiz", true))

    }

    @Test fun `P4 detalle cache187 imprime207 del GET posterior sin pendientes`() = runTest {
        var response = Result.success(PreCuentaProducto4Fixtures.order("base"))
        var reads = 0
        val (panel, receipt) = product4Panel("base") { reads++; response }
        assertEquals(187.0, panel.check.value!!.total, 0.0)
        response = Result.success(PreCuentaProducto4Fixtures.order("updated"))
        panel.printPreBill()
        assertEquals(2, reads)
        assertTrue(receipt.isCaptured)
        assertEquals(19000, receipt.captured.subtotal)
        assertEquals(20700, receipt.captured.total)

    }

    @Test fun `P4 detalle cache PENDING rechaza GET fresco CANCELLED y COMPLETED`() = runTest {
        for (kind in listOf("terminalDeleted", "terminalCancelled", "terminalCompleted")) {
            var response = Result.success(PreCuentaProducto4Fixtures.order("base"))
            val (panel, receipt) = product4Panel("base") { response }
            assertEquals("PENDING", panel.check.value!!.status)
            response = Result.success(PreCuentaProducto4Fixtures.order(kind))
            panel.printPreBill()
            assertFalse("Estado terminal leído del servidor no imprime: $kind", receipt.isCaptured)
            assertTrue(panel.blockedNotice.value != null || panel.actionMessage.value != null)
        }

    }

    @Test fun `P4 detalle mantiene marca fija cuando showOrderType es false`() = runTest {
        val (panel, receipt) = product4Panel("base")
        panel.printPreBill()
        assertTrue(receipt.isCaptured)
        val text = PreCuentaProducto4Fixtures.text(receipt.captured, listOf(com.avoqado.pos.printing.receiptlayout.ReceiptBlock.OrderInfo(showOrderType = false)))
        assertTrue(text.contains("PRE-CUENTA"))
        assertTrue(text.contains("NO ACREDITA PAGO"))
    }

    @Test fun `P4 detalle mantiene marca fija aunque Interpreter reciba bloques vacios`() = runTest {
        val (panel, receipt) = product4Panel("base")
        panel.printPreBill()
        assertTrue(receipt.isCaptured)
        val text = PreCuentaProducto4Fixtures.text(receipt.captured, emptyList())
        assertTrue(text.contains("PRE-CUENTA"))
        assertTrue(text.contains("NO ACREDITA PAGO"))
    }

}

/** Literal server inputs; the production DTO decoder intentionally ignores currently unknown fields. */
internal object PreCuentaProducto4Fixtures {
    private val wire = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    fun order(kind: String): OrderDetail = wire.decodeFromString<com.avoqado.pos.tables.data.OrderDetailResponse>(body(kind)).order!!
    fun body(kind: String): String = when (kind) {
        "base" -> """{"success":true,"order":{"id":"o1","orderNumber":"ORD-P4","version":9,"status":"PENDING","paymentStatus":"PENDING","subtotal":170,"discountAmount":0,"serviceChargeAmount":17,"taxAmount":0,"addedTaxAmount":0,"tipAmount":0,"total":187,"payments":[],"items":[{"id":"coffee","productId":"coffee","productName":"Cafe base","quantity":1,"unitPrice":170,"total":170,"modifiers":[],"isCortesia":false}],"preBillBalance":{"orderTotalCents":18700,"totalPaidCents":0,"remainingBalanceCents":18700,"tipAmountCents":0}}}"""
        "taxAdded" -> """{"success":true,"order":{"id":"o1","orderNumber":"ORD-P4","version":9,"status":"PENDING","paymentStatus":"PENDING","subtotal":100,"discountAmount":0,"serviceChargeAmount":0,"taxAmount":16,"addedTaxAmount":16,"tipAmount":0,"total":116,"payments":[],"items":[{"id":"coffee","productId":"coffee","productName":"Cafe base","quantity":1,"unitPrice":100,"total":100,"modifiers":[],"isCortesia":false}],"preBillBalance":{"orderTotalCents":11600,"totalPaidCents":0,"remainingBalanceCents":11600,"tipAmountCents":0}}}"""
        "taxIncluded" -> """{"success":true,"order":{"id":"o1","orderNumber":"ORD-P4","version":9,"status":"PENDING","paymentStatus":"PENDING","subtotal":100,"discountAmount":0,"serviceChargeAmount":0,"taxAmount":16,"addedTaxAmount":0,"tipAmount":0,"total":100,"payments":[],"items":[{"id":"coffee","productId":"coffee","productName":"Cafe base","quantity":1,"unitPrice":100,"total":100,"modifiers":[],"isCortesia":false}],"preBillBalance":{"orderTotalCents":10000,"totalPaidCents":0,"remainingBalanceCents":10000,"tipAmountCents":0}}}"""
        "partialTip" -> """{"success":true,"order":{"id":"o1","orderNumber":"ORD-P4","version":9,"status":"PENDING","paymentStatus":"PARTIAL","subtotal":170,"discountAmount":0,"serviceChargeAmount":17,"taxAmount":0,"addedTaxAmount":0,"tipAmount":0,"total":187,"payments":[{"id":"pay50","status":"COMPLETED","amount":50,"tipAmount":10}],"items":[{"id":"coffee","productId":"coffee","productName":"Cafe base","quantity":1,"unitPrice":170,"total":170,"modifiers":[],"isCortesia":false}],"preBillBalance":{"orderTotalCents":19700,"totalPaidCents":6000,"remainingBalanceCents":13700,"tipAmountCents":1000}}}"""
        "partialNoTip" -> """{"success":true,"order":{"id":"o1","orderNumber":"ORD-P4","version":9,"status":"PENDING","paymentStatus":"PARTIAL","subtotal":170,"discountAmount":0,"serviceChargeAmount":17,"taxAmount":0,"addedTaxAmount":0,"tipAmount":0,"total":187,"payments":[{"id":"pay50","status":"COMPLETED","amount":50,"tipAmount":0}],"items":[{"id":"coffee","productId":"coffee","productName":"Cafe base","quantity":1,"unitPrice":170,"total":170,"modifiers":[],"isCortesia":false}],"preBillBalance":{"orderTotalCents":18700,"totalPaidCents":5000,"remainingBalanceCents":13700,"tipAmountCents":0}}}"""
        "noSnapshot" -> """{"success":true,"order":{"id":"o1","orderNumber":"ORD-P4","version":9,"status":"PENDING","paymentStatus":"PARTIAL","subtotal":170,"discountAmount":0,"serviceChargeAmount":17,"taxAmount":0,"addedTaxAmount":0,"tipAmount":0,"total":187,"payments":[{"id":"pay50","status":"COMPLETED","amount":50,"tipAmount":10}],"items":[{"id":"coffee","productId":"coffee","productName":"Cafe base","quantity":1,"unitPrice":170,"total":170,"modifiers":[],"isCortesia":false}]}}"""
        "refundZero" -> """{"success":true,"order":{"id":"o1","orderNumber":"ORD-P4","version":9,"status":"COMPLETED","paymentStatus":"PAID","subtotal":170,"discountAmount":0,"serviceChargeAmount":17,"taxAmount":0,"addedTaxAmount":0,"tipAmount":0,"total":187,"payments":[{"id":"paid187","status":"COMPLETED","amount":187,"tipAmount":0},{"id":"refund187","status":"COMPLETED","amount":-187,"tipAmount":0}],"items":[{"id":"coffee","productId":"coffee","productName":"Cafe base","quantity":1,"unitPrice":170,"total":170,"modifiers":[],"isCortesia":false}],"preBillBalance":{"orderTotalCents":18700,"totalPaidCents":18700,"remainingBalanceCents":0,"tipAmountCents":0},"refundState":"FULL"}}"""
        "updated" -> """{"success":true,"order":{"id":"o1","orderNumber":"ORD-P4","version":10,"status":"PENDING","paymentStatus":"PENDING","subtotal":190,"discountAmount":0,"serviceChargeAmount":17,"taxAmount":0,"addedTaxAmount":0,"tipAmount":0,"total":207,"payments":[],"items":[{"id":"coffee","productId":"coffee","productName":"Cafe base","quantity":1,"unitPrice":190,"total":190,"modifiers":[],"isCortesia":false}],"preBillBalance":{"orderTotalCents":20700,"totalPaidCents":0,"remainingBalanceCents":20700,"tipAmountCents":0}}}"""
        "cappedDiscount" -> """{"success":true,"order":{"id":"o1","orderNumber":"ORD-P4","version":9,"status":"PENDING","paymentStatus":"PENDING","subtotal":100,"discountAmount":125,"serviceChargeAmount":10,"taxAmount":0,"addedTaxAmount":0,"tipAmount":0,"total":10,"payments":[],"items":[{"id":"coffee","productId":"coffee","productName":"Cafe base","quantity":1,"unitPrice":100,"total":100,"modifiers":[],"isCortesia":false}],"preBillBalance":{"orderTotalCents":1000,"totalPaidCents":0,"remainingBalanceCents":1000,"tipAmountCents":0}}}"""
        "terminalDeleted" -> """{"success":true,"order":{"id":"o1","orderNumber":"ORD-P4","version":9,"status":"DELETED","paymentStatus":"PENDING","subtotal":170,"discountAmount":0,"serviceChargeAmount":17,"taxAmount":0,"addedTaxAmount":0,"tipAmount":0,"total":187,"payments":[],"items":[{"id":"coffee","productId":"coffee","productName":"Cafe base","quantity":1,"unitPrice":170,"total":170,"modifiers":[],"isCortesia":false}],"preBillBalance":{"orderTotalCents":18700,"totalPaidCents":0,"remainingBalanceCents":18700,"tipAmountCents":0}}}"""
        "terminalCancelled" -> """{"success":true,"order":{"id":"o1","orderNumber":"ORD-P4","version":9,"status":"CANCELLED","paymentStatus":"PENDING","subtotal":170,"discountAmount":0,"serviceChargeAmount":17,"taxAmount":0,"addedTaxAmount":0,"tipAmount":0,"total":187,"payments":[],"items":[{"id":"coffee","productId":"coffee","productName":"Cafe base","quantity":1,"unitPrice":170,"total":170,"modifiers":[],"isCortesia":false}]}}"""
        "terminalCompleted" -> """{"success":true,"order":{"id":"o1","orderNumber":"ORD-P4","version":9,"status":"COMPLETED","paymentStatus":"PAID","subtotal":170,"discountAmount":0,"serviceChargeAmount":17,"taxAmount":0,"addedTaxAmount":0,"tipAmount":0,"total":187,"payments":[],"items":[{"id":"coffee","productId":"coffee","productName":"Cafe base","quantity":1,"unitPrice":170,"total":170,"modifiers":[],"isCortesia":false}]}}"""
        else -> error("Fixture desconocida: $kind")
    }
    fun text(receipt: ReceiptData, blocks: List<com.avoqado.pos.printing.receiptlayout.ReceiptBlock> = listOf(
        com.avoqado.pos.printing.receiptlayout.ReceiptBlock.OrderInfo(),
        com.avoqado.pos.printing.receiptlayout.ReceiptBlock.Totals(),
    )): String {
        val input = com.avoqado.pos.printing.receiptlayout.ReceiptInputMapper.map(receipt, null, false, "America/Mexico_City", null)
        return com.avoqado.pos.printing.receiptlayout.ReceiptLayoutInterpreter.interpret(blocks, input, 48)
            .filterIsInstance<com.avoqado.pos.printing.receiptlayout.LogicalLine.Text>().joinToString("\n") { it.text }
    }
    fun amount(text: String, label: String, value: String) {
        org.junit.Assert.assertTrue("Falta $label $value: $text", Regex(Regex.escape(label) + "[^\r\n]*" + Regex.escape(value), RegexOption.IGNORE_CASE).containsMatchIn(text))
    }
}
