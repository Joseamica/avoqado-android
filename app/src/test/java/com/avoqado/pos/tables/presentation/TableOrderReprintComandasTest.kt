package com.avoqado.pos.tables.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.SavedPrinter
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.PrinterInfo
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.OrderDetailItem
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    ): TableOrderViewModel {
        val repository = mockk<TableServiceRepository>(relaxed = true)
        every { repository.tables } returns MutableStateFlow(emptyList())
        every { repository.ownership } returns MutableStateFlow(TableServiceRepository.TableOwnership())
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(
            OrderDetail(id = "o1", orderNumber = "ORD-1", items = items),
        )

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
                totalCents = 12000,
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
        ).also { it.loadCheck() }
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
}
