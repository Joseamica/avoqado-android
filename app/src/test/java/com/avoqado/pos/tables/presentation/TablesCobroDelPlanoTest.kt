package com.avoqado.pos.tables.presentation

import android.content.Context
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.local.database.IntentSinResolver
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.sync.SyncAck
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.ReceiptData
import com.avoqado.pos.printing.data.model.SavedPrinter
import com.avoqado.pos.tables.data.*
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

@OptIn(ExperimentalCoroutinesApi::class)
class TablesCobroDelPlanoTest {
    @get:Rule val main = MainDispatcherRule()
    private val repository = mockk<TableServiceRepository>(relaxed = true)
    private val outbox = mockk<SyncOutbox>(relaxed = true)
    private val session = TableSession()
    private val table = DiningTable("t1", "5", openOrders = listOf(OpenCheckSummary("o1", "ORD-1", total = 100.0)))
    private fun vm(printer: PrinterService = mockk(relaxed = true)): TablesViewModel {
        every { repository.tables } returns MutableStateFlow(listOf(table))
        every { repository.isLoading } returns MutableStateFlow(false)
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.venueId } returns "v-plano"
        return TablesViewModel(repository, mockk(relaxed = true), session, printer, storage, outbox, mockk(relaxed = true), com.avoqado.pos.tables.data.FloorPlanRepository(io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true)), io.mockk.mockk(relaxed = true), io.mockk.mockk<com.avoqado.pos.tables.data.ApiFuenteDeVersiones>(relaxed = true))
    }

    private fun impresorDePlano(order: OrderDetail): Pair<TablesViewModel, CapturingSlot<ReceiptData>> {
        val printer = mockk<PrinterService>(relaxed = true)
        every { printer.getDefaultPrinter(PrinterRole.RECEIPT) } returns SavedPrinter(
            id = "FULLTEST-recibos", name = "Recibos", connectionType = "wifi",
            address = "127.0.0.1", port = 9, roles = listOf(PrinterRole.RECEIPT.value),
        )
        val receipt = slot<ReceiptData>()
        coEvery { printer.printReceipt(capture(receipt), any()) } returns Unit
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(0, 0, emptySet(), 0)
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(order)
        return vm(printer) to receipt
    }

    private fun cuentaConCargoYExtra() = OrderDetail(
        id = "o1", orderNumber = "ORD-1", subtotal = 170.0, serviceChargeAmount = 17.0, total = 187.0,
        items = listOf(
            OrderDetailItem("cafe-1", productName = "Café Mesas C2", unitPrice = 80.0, total = 80.0),
            OrderDetailItem("cafe-2", productName = "Café Mesas C2", unitPrice = 80.0, total = 90.0,
                modifiers = listOf(OrderDetailModifier(name = "Extra Mesas C2", price = 10.0))),
        ),
    )

    @Test fun `precuenta del plano conserva subtotal y cargo del detalle fresco`() = runTest {
        val (subject, receipt) = impresorDePlano(cuentaConCargoYExtra())
        try {
            subject.printPreBill(table); advanceUntilIdle()
            assertEquals(17000, receipt.captured.subtotal)
            assertEquals(1700, receipt.captured.serviceChargeAmount)
            assertEquals(18700, receipt.captured.total)
            assertNull(receipt.captured.discountAmount)
        } finally { subject.viewModelScope.cancel() }
    }

    // D3, 19-oct: la pre-cuenta salía sin la nota del artículo (el plano y la cuenta la olvidaban por igual).
    @Test fun `precuenta del plano lleva la nota del articulo`() = runTest {
        val cuenta = cuentaConCargoYExtra()
        val (subject, receipt) = impresorDePlano(cuenta.copy(items = listOf(cuenta.items[0].copy(notes = "sin aceite"), cuenta.items[1])))
        try {
            subject.printPreBill(table); advanceUntilIdle()
            assertEquals(listOf("sin aceite", null), receipt.captured.items.map { it.note })
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `precuenta del plano conserva extra sin sumarlo dos veces`() = runTest {
        val (subject, receipt) = impresorDePlano(cuentaConCargoYExtra())
        try {
            subject.printPreBill(table); advanceUntilIdle()
            assertEquals(listOf("Extra Mesas C2"), receipt.captured.items[1].modifiers)
            assertEquals(9000, receipt.captured.items[1].totalPrice)
            assertEquals(18700, receipt.captured.total)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `precuenta del plano conserva la cortesia que informa el servidor`() = runTest {
        val order = OrderDetail("o1", subtotal = 80.0, total = 80.0, items = listOf(
            OrderDetailItem("pagado", productName = "Café", unitPrice = 80.0, total = 80.0),
            OrderDetailItem("cortesia", productName = "Café de cortesía", unitPrice = 80.0, total = 0.0, isCortesia = true),
        ))
        val (subject, receipt) = impresorDePlano(order)
        try {
            subject.printPreBill(table); advanceUntilIdle()
            assertTrue(receipt.captured.items[1].isCortesia)
            assertEquals(0, receipt.captured.items[1].totalPrice)
            assertEquals(8000, receipt.captured.total)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `precuenta del plano conserva descuento y total del detalle fresco`() = runTest {
        val (subject, receipt) = impresorDePlano(cuentaConCargoYExtra().copy(discountAmount = 10.0, total = 177.0))
        try {
            subject.printPreBill(table); advanceUntilIdle()
            assertEquals(1000, receipt.captured.discountAmount)
            assertEquals(17000, receipt.captured.subtotal)
            assertEquals(1700, receipt.captured.serviceChargeAmount)
            assertEquals(17700, receipt.captured.total)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `retenidos rechazos y lectura fallida no arrancan cobro ni reentrada parcial`() = runTest {
        val subject = vm()
        session.start(TableSession.Active("t1", "5", null, "o1", "ORD-1", 3, 6000, TableSession.Mode.PAYING))
        val before = session.current()
        var navigation = 0
        try {
            for (rejected in listOf(0, 1)) {
                coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(1, rejected, setOf("ADD_ITEMS"), 0)
                subject.startCobrar(table) { navigation++ }; advanceUntilIdle()
                assertEquals(0, navigation); assertEquals(before, session.current())
                assertEquals(TableActionState.Error("Esta mesa tiene cambios sin sincronizar: cóbrala cuando se sincronicen."), subject.actionState.value)
            }
            coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } throws IOException("disco")
            subject.startCobrar(table) { navigation++ }; advanceUntilIdle()
            assertEquals(0, navigation); assertEquals(before, session.current())
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `plano limpio conserva saldo parcial existente al navegar`() = runTest {
        val subject = vm()
        session.start(TableSession.Active("t1", "5", null, "o1", "ORD-1", 3, 6000, TableSession.Mode.PAYING))
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(0, 0, emptySet(), 0)
        var navigation = 0
        try {
            subject.startCobrar(table) { navigation++ }; advanceUntilIdle()
            assertEquals(1, navigation); assertEquals(6000, session.current()!!.totalCents)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `pre cuenta pide detalle fresco en vez del resumen y rechaza respuesta ajena`() = runTest {
        val subject = vm()
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(0, 0, emptySet(), 0)
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(OrderDetail("o1", total = 146.30))
        try {
            val fresh = subject.prepararPreCuenta(table)
            assertEquals(146.30, fresh!!.total, 0.0)
            coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(OrderDetail("otra", total = 146.30))
            assertNull(subject.prepararPreCuenta(table))
            coEvery { repository.getOrderDetail(any(), any()) } returns Result.failure(IOException("sin red"))
            assertNull(subject.prepararPreCuenta(table))
            assertEquals(TableActionState.Error("La cuenta no se pudo leer del servidor: la pre-cuenta sale cuando vuelva la conexión."), subject.actionState.value)
        } finally { subject.viewModelScope.cancel() }
    }

    /** Proyección DAO controlada; filtrado JSON, outbox, coordinador y sesión son los de producto. */
    private inner class PreCuentaFixture(private val printer: PrinterService = mockk(relaxed = true)) {
        val venue = "v-plano"
        var selectedVenue = venue
        val rows = mutableMapOf<String, List<IntentSinResolver>>()
        val dao = mockk<SyncIntentDao>(relaxed = true)
        val storage = mockk<SecureStorage>(relaxed = true)
        val filesDir = Files.createTempDirectory("precuenta-alias-").toFile()
        val context = mockk<Context>(relaxed = true)
        val drafts = TableRoundDraftStore(File(filesDir, "table-round-drafts"))
        private var ackType = "ADD_ITEMS"
        var actualOutbox: SyncOutbox
        var floor: TablesViewModel
        init {
            every { context.filesDir } returns filesDir
            every { storage.venueId } answers { selectedVenue }
            every { storage.userId } returns "staff-plano"
            every { repository.tables } returns MutableStateFlow(listOf(table))
            every { repository.isLoading } returns MutableStateFlow(false)
            coEvery { dao.sinResolver(any()) } answers { rows[firstArg<String>()].orEmpty() }
            coEvery { dao.preparationStatuses(any(), any()) } answers {
                listOf(SyncIntentEntity(secondArg<List<String>>().single(), firstArg(), seq = 1,
                    type = ackType, payloadJson = "{}", status = "ACKED"))
            }
            actualOutbox = newOutbox()
            floor = newFloor()
            session.sincronizarContexto("staff-plano", venue)
            coEvery { repository.getOrderDetail(venue, "o1") } returns Result.success(OrderDetail("o1", total = 100.0))
        }
        private fun newOutbox() = SyncOutbox(dao = dao, apiService = mockk(relaxed = true),
            connectivityMonitor = mockk(relaxed = true), secureStorage = storage,
            cajon = mockk(relaxed = true), context = context, cachedPayloads = mockk(relaxed = true), tableSession = session)
        private fun newFloor() = TablesViewModel(repository, mockk(relaxed = true), session, printer,
            storage, actualOutbox, mockk(relaxed = true), com.avoqado.pos.tables.data.FloorPlanRepository(io.mockk.mockk(relaxed = true), io.mockk.mockk(relaxed = true)), io.mockk.mockk(relaxed = true), io.mockk.mockk<com.avoqado.pos.tables.data.ApiFuenteDeVersiones>(relaxed = true))
        fun restartWithoutMemory() {
            floor.viewModelScope.cancel(); actualOutbox.stop()
            session.clear(); session.sincronizarContexto("staff-plano", venue)
            actualOutbox = newOutbox(); floor = newFloor()
        }
        fun writeDraft(order: String, body: String, owner: String = venue) {
            val digest = MessageDigest.getInstance("SHA-256").digest("$owner\u0000$order".toByteArray())
                .joinToString("") { "%02x".format(it) }
            val directory = File(filesDir, "table-round-drafts"); directory.mkdirs()
            File(directory, "$digest.json").writeText(body)
        }
        fun pending(order: String = "o1", owner: String = venue, local: Boolean = false) {
            val key = if (local) "localOrderId" else "orderId"
            rows[owner] = listOf(IntentSinResolver("ADD_ITEMS", "PENDING", """{"$key":"$order"}"""))
        }
        suspend fun ack(local: String? = null) {
            ackType = if (local == null) "ADD_ITEMS" else "OPEN_TABLE"
            val coordinator = TableSyncCoordinator(actualOutbox, session, repository, storage, dao)
            coordinator.handle(SyncAck("owned-ack", "ACKED", result = buildJsonObject {
                put("orderId", "o1"); put("version", 9)
                if (local != null) put("localOrderId", local)
            }, contextoDeSesion = session.contextoActual()))
        }
        private val panels = mutableListOf<TableOrderViewModel>()
        fun panel(): TableOrderViewModel {
            every { repository.ownership } returns MutableStateFlow(TableServiceRepository.TableOwnership())
            val connectivity = mockk<com.avoqado.pos.core.util.ConnectivityMonitor>(relaxed = true)
            every { connectivity.isConnected } returns MutableStateFlow(true)
            return TableOrderViewModel(repository, session, mockk(relaxed = true), mockk(relaxed = true),
                printer, storage, actualOutbox, mockk(relaxed = true), connectivity,
                mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), draftStore = drafts)
                .also { panels += it }
        }
        fun close() { panels.forEach { it.viewModelScope.cancel() }; floor.viewModelScope.cancel(); actualOutbox.stop(); filesDir.deleteRecursively() }
    }

    @Test fun `precuenta del piso lee e imprime tras abrir leer salir y repetir el recorrido`() = runTest {
        val printer = mockk<PrinterService>(relaxed = true)
        every { printer.getDefaultPrinter(PrinterRole.RECEIPT) } returns SavedPrinter(
            id = "FULLTEST-recibos", name = "Recibos", connectionType = "wifi", address = "127.0.0.1",
            port = 9, roles = listOf(PrinterRole.RECEIPT.value))
        val receipt = slot<ReceiptData>()
        val receiptProduced = CompletableDeferred<Unit>()
        coEvery { printer.printReceipt(capture(receipt), any()) } coAnswers { receiptProduced.complete(Unit); Unit }
        val f = PreCuentaFixture(printer)
        val selected = table.copy(number = "801", openOrders = listOf(OpenCheckSummary("o1", "ORD-1", total = 187.0)))
        every { repository.tables } returns MutableStateFlow(listOf(selected))
        coEvery { repository.getOrderDetail(f.venue, "o1") } returns Result.success(cuentaConCargoYExtra())
        try {
            repeat(2) {
                var entered = false
                f.floor.startOrdering(selected) { entered = true }
                assertTrue("El productor real debe abrir la sesión", entered)
                val panel = f.panel()
                panel.loadCheck(); advanceUntilIdle()
                assertTrue(panel.chequeAlDia.value)
                assertEquals(187.0, panel.check.value!!.total, 0.0)
                assertTrue(panel.exitToFloor())
                assertNull(session.current())
                assertTrue("Salir requiere otra lectura, no prohíbe pedirla", session.requiereRevisarSaldo(f.venue, setOf("o1")))
            }
            f.floor.printPreBill(selected); advanceUntilIdle()
            val printFinished = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeoutOrNull(3000) { receiptProduced.await(); true }
            } == true
            assertTrue("La impresión debe producir su recibo dentro del plazo real", printFinished)
            coVerify(exactly = 3) { repository.getOrderDetail(f.venue, "o1") }
            assertTrue("El VM del piso debe producir el recibo después de su GET fresco", receipt.isCaptured)
            if (!receipt.isCaptured) return@runTest
            assertEquals(17000, receipt.captured.subtotal)
            assertEquals(1700, receipt.captured.serviceChargeAmount)
            assertEquals(18700, receipt.captured.total)
            assertEquals(listOf("Extra Mesas C2"), receipt.captured.items[1].modifiers)
            assertTrue("Imprimir no limpia el guard de cobro compartido", session.requiereRevisarSaldo(f.venue, setOf("o1")))
        } finally { f.close() }
    }

    /** GET suspendido con boolean ya true: los cambios se producen por los métodos de producto. */
    private suspend fun TestScope.retainedReadWithExistingFlag(change: String) {
        val f = PreCuentaFixture()
        session.start(TableSession.Active("t1", "5", null, "o1", "ORD-1", 9, 10000, TableSession.Mode.ORDERING), f.venue)
        session.marcarSaldoDesconocido(f.venue, setOf("o1"), session.contextoActual())
        val before = session.current()
        val epoch = session.contextoActual()
        assertTrue(before!!.requiereLecturaServidor)
        val get = CompletableDeferred<Result<OrderDetail>>()
        val started = CompletableDeferred<Unit>()
        coEvery { repository.getOrderDetail(f.venue, "o1") } coAnswers { started.complete(Unit); get.await() }
        try {
            val reading = async { f.floor.prepararPreCuenta(table) }
            val getStarted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.withTimeoutOrNull(3000) { started.await(); true } } == true
            assertTrue("Una marca anterior permite iniciar GET fresco: $change", getStarted)
            if (!getStarted) { reading.await(); return }
            when (change) {
                "saldo" -> session.marcarSaldoDesconocido(f.venue, setOf("o1"), epoch)
                "ack" -> f.ack()
                "finalizar" -> session.finalizar(f.venue, setOf("o1"), epoch)
                "contexto" -> { f.selectedVenue = "otra-sucursal"; session.sincronizarContexto("staff-plano", f.selectedVenue) }
                "navegacion" -> {
                    var entered = false
                    f.floor.startOrdering(table) { entered = true }
                    assertTrue(entered)
                }
                "pendiente" -> f.pending()
                else -> error("Cambio desconocido")
            }
            if (change in setOf("saldo", "ack", "navegacion")) {
                assertEquals("Cambian la revisión, no el boolean ni importes", before, session.current())
                assertEquals("El epoch conserva su contrato de identidad", epoch, session.contextoActual())
            }
            get.complete(Result.success(OrderDetail("o1", subtotal = 100.0, total = 100.0)))
            assertNull("La respuesta anterior a $change no acredita precuenta", reading.await())
            coVerify(exactly = 1) { repository.getOrderDetail(f.venue, "o1") }
            if (change == "finalizar") {
                assertNull("Una cuenta finalizada sigue bloqueada", f.floor.prepararPreCuenta(table))
                coVerify(exactly = 1) { repository.getOrderDetail(f.venue, "o1") }
            } else if (change != "contexto") {
                if (change == "pendiente") f.rows.remove(f.venue)
                coEvery { repository.getOrderDetail(f.venue, "o1") } returns Result.success(OrderDetail("o1", subtotal = 100.0, total = 100.0))
                val fresh = f.floor.prepararPreCuenta(table)
                assertNotNull("Un NUEVO GET limpio sí acredita su propia lectura", fresh)
                assertEquals(100.0, fresh!!.total, 0.0)
                coVerify(exactly = 2) { repository.getOrderDetail(f.venue, "o1") }
                assertTrue("No limpiar el flag compartido para que pase", session.requiereRevisarSaldo(f.venue, setOf("o1")))
            }
        } finally { get.complete(Result.failure(IOException("cleanup"))); f.close() }
    }

    @Test fun `GET de precuenta descarta saldo desconocido repetido con flag e importes iguales`() = runTest { retainedReadWithExistingFlag("saldo") }
    @Test fun `GET de precuenta descarta ACK repetido con flag version e importes iguales`() = runTest { retainedReadWithExistingFlag("ack") }
    @Test fun `GET de precuenta descarta finalizacion mientras flag ya era true`() = runTest { retainedReadWithExistingFlag("finalizar") }
    @Test fun `GET de precuenta descarta cambio de contexto mientras flag ya era true`() = runTest { retainedReadWithExistingFlag("contexto") }
    @Test fun `GET de precuenta descarta reentrada real de navegacion al mismo cheque`() = runTest { retainedReadWithExistingFlag("navegacion") }
    @Test fun `GET de precuenta descarta pendiente nuevo y permite nuevo intento al resolverlo`() = runTest { retainedReadWithExistingFlag("pendiente") }

    @Test fun `pre cuenta de piso no pierde alias de cuenta estacionada promovida por ACK`() = runTest {
        val f = PreCuentaFixture()
        val local = "local-precuenta"
        session.start(TableSession.Active("t1", "5", null, local, "LOCAL", 1, 0,
            TableSession.Mode.ORDERING, isProvisional = true, localOrderId = local, valorRondasSinRedCents = 8000), f.venue)
        try {
            assertTrue(session.estacionar(f.venue, session.current()!!))
            f.pending(local, local = true)
            f.ack(local)
            assertNull(session.current())
            assertTrue(session.requiereRevisarSaldo(f.venue, setOf("o1")))
            assertEquals(1, f.actualOutbox.trabajoSinResolverDeLaOrden(f.venue, setOf(local)).intents)
            assertNull("La ronda local sigue pendiente aunque el piso ya muestre el id real", f.floor.prepararPreCuenta(table))
            assertNull(session.current())
            assertEquals(1, f.actualOutbox.trabajoSinResolverDeLaOrden(f.venue, setOf(local)).intents)
        } finally { f.close() }
    }

    @Test fun `pre cuenta descarta GET anterior al ACK que invalida la cuenta`() = runTest {
        val f = PreCuentaFixture()
        val get = CompletableDeferred<Result<OrderDetail>>()
        val started = CompletableDeferred<Unit>()
        session.start(TableSession.Active("t1", "5", null, "o1", "ORD-1", 3, 10000, TableSession.Mode.ORDERING), f.venue)
        coEvery { repository.getOrderDetail(f.venue, "o1") } coAnswers { started.complete(Unit); get.await() }
        try {
            val reading = async { f.floor.prepararPreCuenta(table) }
            started.await()
            coVerify(exactly = 1) { repository.getOrderDetail(f.venue, "o1") }
            f.ack()
            assertTrue(session.current()!!.requiereLecturaServidor)
            get.complete(Result.success(OrderDetail("o1", total = 100.0)))
            assertNull("El GET empezó antes del cambio confirmado", reading.await())
            assertEquals(9, session.current()!!.version)
        } finally { get.complete(Result.failure(IOException("cleanup"))); f.close() }
    }

    @Test fun `pre cuenta sin memoria rechaza pendiente inicial aunque ACK lo resuelva durante GET`() = runTest {
        val f = PreCuentaFixture()
        f.pending()
        var detailRequests = 0
        coEvery { repository.getOrderDetail(f.venue, "o1") } coAnswers {
            detailRequests++
            // El HTTP ya obtuvo su detalle viejo; el replay confirma la ronda antes de entregarlo.
            f.rows.clear()
            f.ack()
            Result.success(OrderDetail("o1", total = 100.0))
        }
        try {
            assertNull(session.current())
            assertFalse(session.requiereRevisarSaldo(f.venue, setOf("o1")))
            assertNull("La cuenta no estaba limpia al iniciar la lectura", f.floor.prepararPreCuenta(table))
            assertEquals("No debe pedir un detalle antes de resolver el trabajo inicial", 0, detailRequests)
            assertEquals(1, f.actualOutbox.trabajoSinResolverDeLaOrden(f.venue, setOf("o1")).intents)
        } finally { f.close() }
    }

    @Test fun `pre cuenta estable sin memoria conserva acceso directo del piso`() = runTest {
        val f = PreCuentaFixture()
        try {
            assertNull(session.current())
            assertEquals(100.0, f.floor.prepararPreCuenta(table)!!.total, 0.0)
            assertNull(session.current())
        } finally { f.close() }
    }

    @Test fun `pre cuenta no mezcla pendientes de otra sucursal con mismo id`() = runTest {
        val f = PreCuentaFixture()
        f.pending(owner = "otra-sucursal")
        try {
            assertEquals(1, f.actualOutbox.trabajoSinResolverDeLaOrden("otra-sucursal", setOf("o1")).intents)
            assertEquals(100.0, f.floor.prepararPreCuenta(table)!!.total, 0.0)
            coVerify { f.dao.sinResolver(f.venue) }
        } finally { f.close() }
    }

    @Test fun `pre cuenta no acepta GET retenido despues de cambiar sucursal`() = runTest {
        val f = PreCuentaFixture()
        val get = CompletableDeferred<Result<OrderDetail>>()
        val started = CompletableDeferred<Unit>()
        coEvery { repository.getOrderDetail(f.venue, "o1") } coAnswers { started.complete(Unit); get.await() }
        try {
            val reading = async { f.floor.prepararPreCuenta(table) }; started.await()
            coVerify(exactly = 1) { repository.getOrderDetail(f.venue, "o1") }
            f.selectedVenue = "otra-sucursal"
            session.sincronizarContexto("staff-plano", f.selectedVenue)
            get.complete(Result.success(OrderDetail("o1", total = 100.0)))
            assertNull(reading.await()); assertNull(session.current())
        } finally { get.complete(Result.failure(IOException("cleanup"))); f.close() }
    }

    @Test fun `alias durable sin OPEN ACK conservado bloquea pre cuenta tras perder memoria`() = runTest {
        val f = PreCuentaFixture()
        val local = "local-durable"
        try {
            f.pending(local, local = true)
            // Mismo store y directorio que usa el productor antes de confirmar OPEN_TABLE.
            f.drafts.promote(f.venue, local, "o1")
            f.ack(local)
            f.restartWithoutMemory()
            assertNull(session.current())
            assertFalse(session.requiereRevisarSaldo(f.venue, setOf("o1")))
            assertEquals("o1", TableRoundDraftStore(f.context).loadResolved(f.venue, local).first)
            // La proyección sólo conserva ADD: no hay OPEN_ACK disponible, igual que después de su TTL.
            assertEquals(listOf("ADD_ITEMS"), f.rows.getValue(f.venue).map { it.type })
            assertEquals(1, f.actualOutbox.trabajoSinResolverDeLaOrden(f.venue, setOf("o1")).intents)
            assertNull(f.floor.prepararPreCuenta(table))
            coVerify(exactly = 0) { repository.getOrderDetail(any(), any()) }
            f.rows.clear()
            assertEquals(100.0, f.floor.prepararPreCuenta(table)!!.total, 0.0)
        } finally { f.close() }
    }

    @Test fun `alias durable de otra sucursal no asocia pendiente local a cuenta propia`() = runTest {
        val f = PreCuentaFixture()
        val local = "local-compartido"
        val other = "otra-sucursal"
        try {
            f.drafts.promote(other, local, "o1")
            f.pending(local, local = true)
            f.pending(local, owner = other, local = true)
            f.restartWithoutMemory()
            assertEquals(0, f.actualOutbox.trabajoSinResolverDeLaOrden(f.venue, setOf("o1")).intents)
            assertEquals(1, f.actualOutbox.trabajoSinResolverDeLaOrden(f.venue, setOf(local)).intents)
            assertEquals(100.0, f.floor.prepararPreCuenta(table)!!.total, 0.0)
            assertEquals(1, f.rows.getValue(other).size)
        } finally { f.close() }
    }

    @Test fun `redirect ciclico no permite declarar limpia la pre cuenta`() = runTest {
        val f = PreCuentaFixture()
        try {
            f.pending("local-ciclo", local = true)
            f.writeDraft("local-ciclo", """{"redirectOrderId":"local-ciclo-2"}""")
            f.writeDraft("local-ciclo-2", """{"redirectOrderId":"local-ciclo"}""")
            f.restartWithoutMemory()
            assertTrue(runCatching { f.actualOutbox.trabajoSinResolverDeLaOrden(f.venue, setOf("o1")) }.isFailure)
            assertNull(f.floor.prepararPreCuenta(table))
            coVerify(exactly = 0) { repository.getOrderDetail(any(), any()) }
        } finally { f.close() }
    }

    @Test fun `redirect ilegible no permite declarar limpia la pre cuenta`() = runTest {
        val f = PreCuentaFixture()
        try {
            f.pending("local-roto", local = true)
            f.writeDraft("local-roto", "{json incompleto")
            f.restartWithoutMemory()
            assertTrue(runCatching { f.actualOutbox.trabajoSinResolverDeLaOrden(f.venue, setOf("o1")) }.isFailure)
            assertNull(f.floor.prepararPreCuenta(table))
            coVerify(exactly = 0) { repository.getOrderDetail(any(), any()) }
        } finally { f.close() }
    }


    @Test fun `T4 el piso no acredita cargo ni al reentrar a una sesion PAYING con saldo propio`() = runTest {
        val subject = vm()
        session.start(TableSession.Active("t1", "5", null, "o1", "ORD-1", 3, 6000, TableSession.Mode.PAYING,
            serviceChargeCents = 1500))
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(0, 0, emptySet(), 0)
        var navigation = 0
        try {
            subject.startCobrar(table) { navigation++ }; advanceUntilIdle()
            assertEquals(1, navigation); assertEquals(6000, session.current()!!.totalCents)
            assertEquals("El piso no sabe si hubo abonos", 0, session.current()!!.serviceChargeCents)
        } finally { subject.viewModelScope.cancel() }
    }

    /** D4 (mesas40): cobrar desde el plano también lleva al ticket quién atiende la cuenta. */
    @Test fun `D4 el piso lleva el mesero de la cuenta al cobro`() = runTest {
        val subject = vm()
        val conMesero = DiningTable("t1", "5", openOrders = listOf(OpenCheckSummary("o1", "ORD-1", total = 100.0, waiterName = "Ana")))
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(0, 0, emptySet(), 0)
        try {
            subject.startCobrar(conMesero) {}; advanceUntilIdle()
            assertEquals(TableSession.Mode.PAYING, session.current()!!.mode)
            assertEquals("Ana", session.current()!!.meseroNombre)
        } finally { subject.viewModelScope.cancel() }
    }

    /** D4, ronda 2 de Codex (P2): el resumen del plano ES la lectura de esa cuenta; si ya no trae mesero, no se conserva el viejo. */
    @Test fun `D4 asignacion quitada - reentrar al cobro desde el piso no conserva el mesero guardado`() = runTest {
        val subject = vm()
        session.start(TableSession.Active("t1", "5", null, "o1", "ORD-1", 3, 6000, TableSession.Mode.PAYING, meseroNombre = "Ana"))
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(0, 0, emptySet(), 0)
        try {
            subject.startCobrar(table) {}; advanceUntilIdle()
            assertEquals("montaje: reentró a la sesión PAYING", 6000, session.current()!!.totalCents)
            assertNull(session.current()!!.meseroNombre)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `T4 el piso limpio crea una sesion sin cargo que tampoco cambia el total del resumen`() = runTest {
        val subject = vm()
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(0, 0, emptySet(), 0)
        var navigation = 0
        try {
            subject.startCobrar(table) { navigation++ }; advanceUntilIdle()
            assertEquals(1, navigation); assertEquals(10000, session.current()!!.totalCents)
            assertEquals(0, session.current()!!.serviceChargeCents)
            assertEquals("o1", session.current()!!.orderId)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `P4 piso transporta IVA agregado16 sin recalcular116`() = runTest {
        val (subject, receipt) = impresorDePlano(PreCuentaProducto4Fixtures.order("taxAdded"))
        try {
            subject.printPreBill(table); advanceUntilIdle()
            assertTrue(receipt.isCaptured)
            assertEquals(1600, receipt.captured.taxAmount)
            assertEquals(11600, receipt.captured.total)
            val text = PreCuentaProducto4Fixtures.text(receipt.captured)
            PreCuentaProducto4Fixtures.amount(text, "IVA agregado:", "$16.00")
            assertFalse(text.contains("IVA incluido cuando aplica"))
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `P4 piso no agrega IVA con agregado0 declarado`() = runTest {
        val (subject, receipt) = impresorDePlano(PreCuentaProducto4Fixtures.order("taxIncluded"))
        try {
            subject.printPreBill(table); advanceUntilIdle()
            assertTrue(receipt.isCaptured)
            assertEquals(0, receipt.captured.taxAmount)
            assertEquals(10000, receipt.captured.total)
            val text = PreCuentaProducto4Fixtures.text(receipt.captured)
            assertFalse(text.contains("IVA agregado:"))
            assertTrue(text.contains("IVA incluido cuando aplica"))
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `P4 piso imprime snapshot197 pagado60 porpagar137 sin restar propina dos veces`() = runTest {
        val (subject, receipt) = impresorDePlano(PreCuentaProducto4Fixtures.order("partialTip"))
        try {
            subject.printPreBill(table); advanceUntilIdle()
            assertTrue("Abono con snapshot autoritativo permite precuenta", receipt.isCaptured)
            assertEquals(17000, receipt.captured.subtotal)
            assertEquals(1700, receipt.captured.serviceChargeAmount)
            assertEquals(19700, receipt.captured.total)
            val text = PreCuentaProducto4Fixtures.text(receipt.captured)
            PreCuentaProducto4Fixtures.amount(text, "Ya pagado", "$60.00")
            PreCuentaProducto4Fixtures.amount(text, "Por pagar", "$137.00")
            val withoutTotals = PreCuentaProducto4Fixtures.text(receipt.captured, listOf(com.avoqado.pos.printing.receiptlayout.ReceiptBlock.OrderInfo(showOrderType = false)))
            PreCuentaProducto4Fixtures.amount(withoutTotals, "Ya pagado", "$60.00")
            PreCuentaProducto4Fixtures.amount(withoutTotals, "Por pagar", "$137.00")
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `P4 piso imprime snapshot187 pagado50 porpagar137 sin propina`() = runTest {
        val (subject, receipt) = impresorDePlano(PreCuentaProducto4Fixtures.order("partialNoTip"))
        try {
            subject.printPreBill(table); advanceUntilIdle()
            assertTrue("Abono con snapshot autoritativo permite precuenta", receipt.isCaptured)
            assertEquals(17000, receipt.captured.subtotal)
            assertEquals(1700, receipt.captured.serviceChargeAmount)
            assertEquals(18700, receipt.captured.total)
            val text = PreCuentaProducto4Fixtures.text(receipt.captured)
            PreCuentaProducto4Fixtures.amount(text, "Ya pagado", "$50.00")
            PreCuentaProducto4Fixtures.amount(text, "Por pagar", "$137.00")
            val withoutTotals = PreCuentaProducto4Fixtures.text(receipt.captured, listOf(com.avoqado.pos.printing.receiptlayout.ReceiptBlock.OrderInfo(showOrderType = false)))
            PreCuentaProducto4Fixtures.amount(withoutTotals, "Ya pagado", "$50.00")
            PreCuentaProducto4Fixtures.amount(withoutTotals, "Por pagar", "$137.00")
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `P4 piso descuento legacy125 aplica100 y conserva cargo10 y total10`() = runTest {
        val (subject, receipt) = impresorDePlano(PreCuentaProducto4Fixtures.order("cappedDiscount"))
        try {
            subject.printPreBill(table); advanceUntilIdle()
            assertTrue(receipt.isCaptured)
            assertEquals(10000, receipt.captured.subtotal)
            assertEquals(10000, receipt.captured.discountAmount)
            assertEquals(1000, receipt.captured.serviceChargeAmount)
            assertEquals(1000, receipt.captured.total)
        } finally { subject.viewModelScope.cancel() }

    }

    @Test fun `P4 piso abono sin snapshot no imprime`() = runTest {
        val (subject, receipt) = impresorDePlano(PreCuentaProducto4Fixtures.order("noSnapshot"))
        try {
            subject.printPreBill(table); advanceUntilIdle()
            assertFalse(receipt.isCaptured)
            assertTrue(subject.actionState.value is TableActionState.Error)
        } finally { subject.viewModelScope.cancel() }

    }

    @Test fun `P4 piso con lectura previa fresca no imprime si GET siguiente falla`() = runTest {
        val (subject, receipt) = impresorDePlano(PreCuentaProducto4Fixtures.order("base"))
        try {
            assertEquals(187.0, subject.prepararPreCuenta(table)!!.total, 0.0)
            coEvery { repository.getOrderDetail(any(), any()) } returns Result.failure(IOException("sin red"))
            subject.printPreBill(table); advanceUntilIdle()
            assertFalse(receipt.isCaptured)
            assertTrue(subject.actionState.value is TableActionState.Error)
            assertTrue(subject.actionState.value.toString().contains("conex", true))
        } finally { subject.viewModelScope.cancel() }

    }

    @Test fun `P4 piso con lectura previa187 imprime207 desde siguiente GET`() = runTest {
        val (subject, receipt) = impresorDePlano(PreCuentaProducto4Fixtures.order("base"))
        try {
            assertEquals(187.0, subject.prepararPreCuenta(table)!!.total, 0.0)
            coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(PreCuentaProducto4Fixtures.order("updated"))
            subject.printPreBill(table); advanceUntilIdle()
            assertTrue(receipt.isCaptured)
            assertEquals(19000, receipt.captured.subtotal)
            assertEquals(20700, receipt.captured.total)
        } finally { subject.viewModelScope.cancel() }

    }

    @Test fun `P4 piso lectura PENDING rechaza detalle fresco CANCELLED y COMPLETED`() = runTest {
        for (kind in listOf("terminalDeleted", "terminalCancelled", "terminalCompleted")) {
            val (subject, receipt) = impresorDePlano(PreCuentaProducto4Fixtures.order("base"))
            try {
                assertEquals("PENDING", subject.prepararPreCuenta(table)!!.status)
                coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(PreCuentaProducto4Fixtures.order(kind))
                subject.printPreBill(table); advanceUntilIdle()
                assertFalse("Estado terminal del GET fresco no imprime: $kind", receipt.isCaptured)
                assertTrue(subject.actionState.value is TableActionState.Error)
            } finally { subject.viewModelScope.cancel() }
        }

    }

    @Test fun `P4 piso mantiene marca fija con showOrderTypefalse`() = runTest {
        val (subject, receipt) = impresorDePlano(PreCuentaProducto4Fixtures.order("base"))
        try {
            subject.printPreBill(table); advanceUntilIdle()
            assertTrue(receipt.isCaptured)
            val text = PreCuentaProducto4Fixtures.text(receipt.captured, listOf(com.avoqado.pos.printing.receiptlayout.ReceiptBlock.OrderInfo(showOrderType = false)))
            assertTrue(text.contains("PRE-CUENTA"))
            assertTrue(text.contains("NO ACREDITA PAGO"))
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `P4 piso mantiene marca fija con bloques vacios`() = runTest {
        val (subject, receipt) = impresorDePlano(PreCuentaProducto4Fixtures.order("base"))
        try {
            subject.printPreBill(table); advanceUntilIdle()
            assertTrue(receipt.isCaptured)
            val text = PreCuentaProducto4Fixtures.text(receipt.captured, emptyList())
            assertTrue(text.contains("PRE-CUENTA"))
            assertTrue(text.contains("NO ACREDITA PAGO"))
        } finally { subject.viewModelScope.cancel() }
    }

    private suspend fun TestScope.product4RetainedPanelRead(changeContext: Boolean) {
        val printer = mockk<PrinterService>(relaxed = true)
        every { printer.getDefaultPrinter(PrinterRole.RECEIPT) } returns SavedPrinter(id="FULLTEST-p4", name="P4", connectionType="wifi", address="127.0.0.1", port=9, roles=listOf(PrinterRole.RECEIPT.value))
        val captured = slot<ReceiptData>()
        coEvery { printer.printReceipt(capture(captured), any()) } returns Unit
        val f = PreCuentaFixture(printer)
        val get = CompletableDeferred<Result<OrderDetail>>()
        val started = CompletableDeferred<Unit>()
        try {
            f.floor.startOrdering(table) {}
            coEvery { repository.getOrderDetail(f.venue, "o1") } returns Result.success(PreCuentaProducto4Fixtures.order("base"))
            val panel = f.panel(); panel.loadCheck(); advanceUntilIdle()
            assertTrue(panel.chequeAlDia.value)
            coEvery { repository.getOrderDetail(f.venue, "o1") } coAnswers { started.complete(Unit); get.await() }
            panel.printPreBill()
            val getStarted = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeoutOrNull(3000) { started.await(); true }
            } == true
            assertTrue("El botón debe pedir su propio GET y quedar suspendido", getStarted)
            if (!getStarted) return
            if (changeContext) { f.selectedVenue="otra-sucursal"; session.sincronizarContexto("staff-plano", f.selectedVenue) }
            else f.ack()
            get.complete(Result.success(PreCuentaProducto4Fixtures.order("base"))); advanceUntilIdle()
            val printFinished = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                kotlinx.coroutines.withTimeoutOrNull(3000) {
                    kotlinx.coroutines.flow.combine(panel.blockedNotice, panel.actionMessage) { notice, message -> notice != null || message != null }.first { it }
                    true
                }
            } == true
            assertTrue("La impresión debe emitir su resultado dentro del plazo real", printFinished)
            assertFalse("El GET anterior al cambio no puede imprimir", captured.isCaptured)
            assertTrue(panel.blockedNotice.value != null || panel.actionMessage.value != null)
        } finally { get.complete(Result.failure(IOException("cleanup"))); f.close() }
    }

    @Test fun `P4 detalle descarta GET propio retenido al cambiar contexto`() = runTest {
        product4RetainedPanelRead(true)
    }

    @Test fun `P4 detalle descarta GET propio retenido ante ACK real`() = runTest {
        product4RetainedPanelRead(false)
    }

}
