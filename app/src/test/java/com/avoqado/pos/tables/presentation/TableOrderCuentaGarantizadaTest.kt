package com.avoqado.pos.tables.presentation

import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.database.SyncIntentDao
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncAck
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tables.data.*
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException

/** VMs/estado reales; IO controlado. La durabilidad de la consulta se prueba en TrabajoDeLaOrdenTest. */
@OptIn(ExperimentalCoroutinesApi::class)
class TableOrderCuentaGarantizadaTest {
    @get:Rule val main = MainDispatcherRule()
    private val repository = mockk<TableServiceRepository>(relaxed = true)
    private val outbox = mockk<SyncOutbox>(relaxed = true)
    private val session = TableSession()
    private val acks = MutableSharedFlow<SyncAck>(extraBufferCapacity = 4)
    private val printer = mockk<PrinterService>(relaxed = true)
    private val storage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns "v-cuenta-garantizada" }
    private val empty = TrabajoSinResolver(0, 0, emptySet(), 0)
    private fun detail(total: Double = 100.0, id: String = "o1") = OrderDetail(id = id, orderNumber = "ORD-1", total = total, items = emptyList(), version = 3)

    private fun vm(provisional: Boolean = false, keepSession: Boolean = false, drafts: TableRoundDraftStore? = null): TableOrderViewModel {
        every { repository.tables } returns MutableStateFlow(emptyList())
        every { repository.ownership } returns MutableStateFlow(TableServiceRepository.TableOwnership())
        every { outbox.acks } returns acks
        every { outbox.pendingCount } returns MutableStateFlow(0)
        coEvery { outbox.enqueue(any(), any(), any(), any(), any(), any()) } answers { arg<String>(3) }
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns empty
        every { printer.getDefaultPrinter(PrinterRole.RECEIPT) } returns mockk(relaxed = true)
        val config = mockk<PrintConfigRepository>(relaxed = true)
        every { config.getCurrentConfig() } returns PrintConfig()
        val connectivity = mockk<ConnectivityMonitor>(relaxed = true)
        every { connectivity.isConnected } returns MutableStateFlow(true)
        if (!keepSession) session.start(TableSession.Active("t1", "5", "Patio", "o1", "ORD-1", 3, 10000, TableSession.Mode.ORDERING,
            isProvisional = provisional, localOrderId = "local-origin"))
        return TableOrderViewModel(repository, session, config, mockk(relaxed = true), printer, storage, outbox,
            mockk(relaxed = true), connectivity, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), draftStore = drafts)
    }

    @Test fun `mutacion invalida al empezar y el GET durante ella no garantiza la cuenta`() = runTest {
        val subject = vm()
        val write = CompletableDeferred<Result<Unit>>()
        val during = CompletableDeferred<Result<OrderDetail>>()
        val after = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail())
        try {
            subject.loadCheck(); advanceUntilIdle(); assertTrue(subject.chequeAlDia.value)
            coEvery { repository.applyServiceCharge(any(), any(), any()) } coAnswers { write.await() }
            coEvery { repository.getOrderDetail(any(), any()) } coAnswers { during.await() }
            subject.applyServiceCharge("s1"); runCurrent()
            assertTrue(subject.hayMutacionEnVuelo)
            assertFalse(subject.chequeAlDia.value)
            assertFalse(subject.preparePagar())
            subject.loadCheck(); runCurrent(); during.complete(Result.success(detail(105.0))); runCurrent()
            assertFalse(subject.chequeAlDia.value)
            coEvery { repository.getOrderDetail(any(), any()) } coAnswers { after.await() }
            write.complete(Result.success(Unit)); runCurrent()
            assertFalse(subject.chequeAlDia.value)
            assertFalse(subject.hayMutacionEnVuelo)
            after.complete(Result.success(detail(110.0))); advanceUntilIdle()
            assertTrue(subject.chequeAlDia.value)
            assertTrue(subject.preparePagar()); assertEquals(11000, session.current()!!.totalCents)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `GET emitido antes de mutacion y recibido despues no sustituye cuenta vigente`() = runTest {
        val subject = vm()
        val old = CompletableDeferred<Result<OrderDetail>>()
        val fresh = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.getOrderDetail(any(), any()) } coAnswers { old.await() }
        coEvery { repository.applyServiceCharge(any(), any(), any()) } returns Result.success(Unit)
        try {
            subject.loadCheck(); runCurrent()
            coEvery { repository.getOrderDetail(any(), any()) } coAnswers { fresh.await() }
            subject.applyServiceCharge("s1"); runCurrent()
            fresh.complete(Result.success(detail(110.0))); runCurrent()
            old.complete(Result.success(detail(100.0))); advanceUntilIdle()
            assertEquals(110.0, subject.check.value!!.total, 0.0)
            assertTrue(subject.chequeAlDia.value)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `respuesta de otra orden no habilita cobro ni pre cuenta`() = runTest {
        val subject = vm()
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail(id = "otra"))
        try {
            subject.loadCheck(); advanceUntilIdle()
            assertNull(subject.check.value); assertFalse(subject.chequeAlDia.value)
            assertEquals("La cuenta todavía no se puede leer del servidor. Sin conexión no se imprime la pre-cuenta.", subject.motivoDePreCuentaAhora())
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `GET actual que no puede persistir conserva cuenta y draft anteriores pero no su frescura`() = runTest {
        val root = java.nio.file.Files.createTempDirectory("check-persist-failure-").toFile()
        val directory = File(root, "drafts")
        val backup = File(root, "preserved-drafts")
        val drafts = TableRoundDraftStore(directory)
        val subject = vm(drafts = drafts)
        val response = CompletableDeferred<Result<OrderDetail>>()
        val started = CompletableDeferred<Unit>()
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail())
        try {
            subject.loadCheck(); advanceUntilIdle()
            assertTrue(subject.chequeAlDia.value)
            val previous = drafts.load("v-cuenta-garantizada", "o1")
            assertEquals(100.0, previous.cachedCheck!!.total, 0.0)
            coEvery { repository.getOrderDetail(any(), any()) } coAnswers { started.complete(Unit); response.await() }
            subject.loadCheck(); started.await()
            // Restore ya terminó. El directorio previo se conserva; sólo la siguiente escritura falla.
            java.nio.file.Files.move(directory.toPath(), backup.toPath())
            directory.writeText("owned fixture blocks draft writes")
            response.complete(Result.success(detail(120.0))); advanceUntilIdle()
            assertTrue(directory.delete())
            java.nio.file.Files.move(backup.toPath(), directory.toPath())
            assertEquals(previous, drafts.load("v-cuenta-garantizada", "o1"))
            assertEquals(100.0, subject.check.value!!.total, 0.0)
            assertEquals("No se pudo guardar la cuenta en este aparato", subject.actionMessage.value)
            assertFalse(subject.chequeAlDia.value)
            assertNotNull(subject.motivoDePreCuentaAhora())
        } finally {
            response.complete(Result.failure(IOException("cleanup")))
            subject.viewModelScope.cancel()
            if (backup.exists()) { if (directory.isFile) directory.delete(); java.nio.file.Files.move(backup.toPath(), directory.toPath()) }
            root.deleteRecursively()
        }
    }

    @Test fun `GET actual de otra orden conserva cuenta anterior pero invalida pre cuenta`() = runTest {
        val root = java.nio.file.Files.createTempDirectory("check-wrong-id-").toFile()
        val drafts = TableRoundDraftStore(root)
        val subject = vm(drafts = drafts)
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail())
        try {
            subject.loadCheck(); advanceUntilIdle(); assertTrue(subject.chequeAlDia.value)
            val previous = drafts.load("v-cuenta-garantizada", "o1")
            coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail(120.0, "other"))
            subject.loadCheck(); advanceUntilIdle()
            assertEquals(previous, drafts.load("v-cuenta-garantizada", "o1"))
            assertEquals("o1", subject.check.value!!.id); assertEquals(100.0, subject.check.value!!.total, 0.0)
            assertFalse(subject.chequeAlDia.value)
            assertNotNull(subject.motivoDePreCuentaAhora())
        } finally { subject.viewModelScope.cancel(); root.deleteRecursively() }
    }

    @Test fun `segundo GET pendiente bloquea pre cuenta y exito persistido vuelve a habilitarla`() = runTest {
        val root = java.nio.file.Files.createTempDirectory("check-refreshed-").toFile()
        val drafts = TableRoundDraftStore(root)
        val subject = vm(drafts = drafts)
        val response = CompletableDeferred<Result<OrderDetail>>()
        val started = CompletableDeferred<Unit>()
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail())
        try {
            subject.loadCheck(); advanceUntilIdle(); assertTrue(subject.chequeAlDia.value)
            coEvery { repository.getOrderDetail(any(), any()) } coAnswers { started.complete(Unit); response.await() }
            subject.loadCheck(); started.await()
            assertEquals(100.0, subject.check.value!!.total, 0.0)
            assertFalse(subject.chequeAlDia.value); assertNotNull(subject.motivoDePreCuentaAhora())
            response.complete(Result.success(detail(120.0))); advanceUntilIdle()
            assertEquals(120.0, subject.check.value!!.total, 0.0)
            assertEquals(120.0, drafts.load("v-cuenta-garantizada", "o1").cachedCheck!!.total, 0.0)
            assertTrue(subject.chequeAlDia.value); assertNull(subject.motivoDePreCuentaAhora())
        } finally { response.complete(Result.failure(IOException("cleanup"))); subject.viewModelScope.cancel(); root.deleteRecursively() }
    }

    @Test fun `GET de generacion vieja con id ajeno no invalida segundo GET valido`() = runTest {
        val subject = vm()
        val old = CompletableDeferred<Result<OrderDetail>>()
        val started = CompletableDeferred<Unit>()
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail())
        try {
            subject.loadCheck(); advanceUntilIdle(); assertTrue(subject.chequeAlDia.value)
            coEvery { repository.getOrderDetail(any(), any()) } coAnswers { started.complete(Unit); old.await() }
            subject.loadCheck(); started.await()
            coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail(120.0))
            subject.loadCheck(); advanceUntilIdle()
            assertEquals(120.0, subject.check.value!!.total, 0.0); assertTrue(subject.chequeAlDia.value)
            old.complete(Result.success(detail(80.0, "other"))); advanceUntilIdle()
            assertEquals("o1", subject.check.value!!.id); assertEquals(120.0, subject.check.value!!.total, 0.0)
            assertTrue(subject.chequeAlDia.value); assertNull(subject.motivoDePreCuentaAhora())
        } finally { old.complete(Result.failure(IOException("cleanup"))); subject.viewModelScope.cancel() }
    }

    @Test fun `ACK por alias local invalida y refresco fallido no recupera garantia`() = runTest {
        val subject = vm()
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail())
        try {
            subject.loadCheck(); advanceUntilIdle()
            coEvery { repository.getOrderDetail(any(), any()) } returns Result.failure(IOException("sin red"))
            acks.emit(SyncAck("round", "ACKED", result = buildJsonObject { put("localOrderId", "local-origin") }))
            advanceUntilIdle()
            assertFalse(subject.chequeAlDia.value); assertTrue(subject.vioCambios)
            assertFalse(subject.preparePagar())
            assertEquals("La cuenta cambió y no se pudo actualizar: cóbrala cuando vuelva la conexión.", subject.blockedNotice.value)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `ACK de otra orden no invalida esta cuenta`() = runTest {
        val subject = vm()
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail())
        try {
            subject.loadCheck(); advanceUntilIdle()
            acks.emit(SyncAck("foreign", "ACKED", result = buildJsonObject { put("orderId", "otra") }))
            advanceUntilIdle(); assertTrue(subject.chequeAlDia.value)
            coVerify(exactly = 1) { repository.getOrderDetail(any(), any()) }
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `HELD y REJECTED de la orden bloquean pagar y pre cuenta aunque pendingCount sea cero`() = runTest {
        for (rejected in listOf(0, 1)) {
            val subject = vm()
            coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail())
            try {
                subject.loadCheck(); advanceUntilIdle()
                coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(1, rejected, setOf("ADD_ITEMS"), 0)
                assertFalse(subject.preparePagar())
                assertEquals("Esta mesa tiene cambios sin sincronizar: cóbrala cuando se sincronicen.", subject.blockedNotice.value)
                assertEquals("Esta mesa tiene cambios sin sincronizar: la pre-cuenta sale cuando se sincronicen.", subject.motivoDePreCuentaAhora())
            } finally { subject.viewModelScope.cancel() }
        }
    }

    @Test fun `lectura que falla significa no se y no autoriza dinero`() = runTest {
        val subject = vm()
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail())
        try {
            subject.loadCheck(); advanceUntilIdle()
            coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } throws IOException("disco ilegible")
            assertFalse(subject.preparePagar())
            assertEquals("No se pudo revisar si esta mesa tiene cambios sin sincronizar. Intenta de nuevo.", subject.blockedNotice.value)
            assertEquals(subject.blockedNotice.value, subject.motivoDePreCuentaAhora())
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `cambiar orden mientras se lee disco no prepara pago con resultado ajeno`() = runTest {
        val subject = vm()
        val read = CompletableDeferred<TrabajoSinResolver>()
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail())
        try {
            subject.loadCheck(); advanceUntilIdle()
            coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } coAnswers { read.await() }
            val pagar = async { subject.preparePagar() }; runCurrent()
            session.start(session.current()!!.copy(orderId = "otra", totalCents = 7500))
            read.complete(empty); runCurrent()
            assertFalse(pagar.await()); assertEquals("otra", session.current()!!.orderId)
            assertEquals(TableSession.Mode.ORDERING, session.current()!!.mode)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `servidor con rondas pendientes espera y luego cobra saldo sin volver a sumar UI`() = runTest {
        val subject = vm()
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail(110.0))
        coEvery { repository.addRound(any(), any(), any(), any()) } returns Result.failure(IOException("sin red"))
        try {
            subject.loadCheck(); advanceUntilIdle()
            subject.addCustomAmount("Artículo", 3300); subject.sendRound(); advanceUntilIdle()
            coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(1, 0, setOf("ADD_ITEMS"), 0)
            assertFalse(subject.preparePagar())
            coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns empty
            coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail(146.30))
            subject.loadCheck(); advanceUntilIdle()
            assertEquals(1, subject.queued.value.size)
            assertTrue(subject.preparePagar()); assertEquals(14630, session.current()!!.totalCents)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `provisional suma total real con modificadores y conserva valor al recrear VM`() = runTest {
        val subject = vm(provisional = true)
        try {
            subject.addProductWithModifiers(Product(id = "p", name = "Artículo", priceValue = 50.0), 2,
                listOf(SelectedModifier("g", "Opciones", "m", "Extra", 500)), null)
            subject.sendRound(); advanceUntilIdle()
            assertEquals(11000, session.current()!!.valorRondasSinRedCents)
            coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(2, 0, setOf("OPEN_TABLE", "ADD_ITEMS"), 0)
            assertTrue(subject.preparePagar()); assertEquals(11000, session.current()!!.totalCents)
            val recreated = vm(keepSession = true)
            try {
                coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(3, 0, setOf("OPEN_TABLE", "ADD_ITEMS", "PAY_CASH"), 3300)
                assertTrue(recreated.preparePagar()); assertEquals(7700, session.current()!!.totalCents)
                assertTrue(session.current()!!.isProvisional)
            } finally { recreated.viewModelScope.cancel() }
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `provisional con ajuste o rechazo no autoriza cobrar`() = runTest {
        val subject = vm(provisional = true)
        session.start(session.current()!!.copy(valorRondasSinRedCents = 5000))
        try {
            for (work in listOf(TrabajoSinResolver(1, 0, setOf("APPLY_DISCOUNT"), 0), TrabajoSinResolver(1, 1, setOf("ADD_ITEMS"), 0))) {
                coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns work
                assertFalse(subject.preparePagar())
                assertEquals(TableSession.Mode.ORDERING, session.current()!!.mode)
            }
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `otra sucursal con el mismo orderId no hereda prueba de frescura`() = runTest {
        val subject = vm()
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail())
        try {
            subject.loadCheck(); advanceUntilIdle(); assertTrue(subject.chequeAlDia.value)
            every { storage.venueId } returns "otra-sucursal"
            assertFalse(subject.preparePagar())
            assertEquals("La cuenta cambió y no se pudo actualizar: cóbrala cuando vuelva la conexión.", subject.blockedNotice.value)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `limite de Int32 conserva borrador y no persiste la ronda provisional`() = runTest {
        val subject = vm(provisional = true)
        try {
            session.start(session.current()!!.copy(valorRondasSinRedCents = Int.MAX_VALUE))
            subject.addCustomAmount("Artículo", 1)
            var ok: Boolean? = null
            subject.sendRound { success, _ -> ok = success }; advanceUntilIdle()
            assertEquals(false, ok); assertEquals(1, subject.pending.value.size)
            assertEquals(Int.MAX_VALUE, session.current()!!.valorRondasSinRedCents)
            coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any(), any()) }
            assertEquals("El importe de esta ronda excede el límite de la cuenta. Ajusta la ronda antes de enviarla.", subject.actionMessage.value)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `precio por cantidad desbordado no envuelve a un importe pequeño`() = runTest {
        val subject = vm(provisional = true)
        try {
            subject.addProductWithModifiers(Product(id = "p", name = "Artículo", priceValue = 10000000.0), 5, emptyList(), null)
            var ok: Boolean? = null
            subject.sendRound { success, _ -> ok = success }; advanceUntilIdle()
            assertEquals(false, ok); assertEquals(1, subject.pending.value.size)
            assertEquals(0, session.current()!!.valorRondasSinRedCents)
            coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any(), any()) }
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `cortesia enviada vale cero y permite registrar cierre de cero`() = runTest {
        val subject = vm(provisional = true)
        try {
            subject.addProductWithModifiers(Product(id = "p", name = "Artículo", priceValue = 50.0), 2, emptyList(), null,
                isCortesia = true, cortesiaReason = "Cortesía autorizada")
            subject.sendRound(); advanceUntilIdle()
            assertEquals(0, session.current()!!.valorRondasSinRedCents)
            assertTrue(subject.preparePagar()); assertEquals(0, session.current()!!.totalCents)
            assertTrue(session.current()!!.isProvisional)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `consulta suspendida tras sumar ronda no borra el borrador de una sesion nueva`() = runTest {
        val subject = vm(provisional = true)
        val read = CompletableDeferred<TrabajoSinResolver>()
        var readStarted = false
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } coAnswers { readStarted = true; read.await() }
        try {
            subject.addCustomAmount("Ronda A", 100)
            var completed: Boolean? = null
            subject.sendRound { ok, _ -> completed = ok }; runCurrent()
            assertTrue("La consulta debe estar suspendida después de sumar", readStarted)
            assertEquals(100, session.current()!!.valorRondasSinRedCents)
            val b = TableSession.Active("t2", "8", "Patio", "b", "B", 1, 4500, TableSession.Mode.ORDERING,
                isProvisional = true, localOrderId = "local-b", valorRondasSinRedCents = 77)
            session.start(b); subject.clearPending(); subject.addCustomAmount("Borrador B", 4500)
            val draftB = subject.pending.value
            val queuedB = subject.queued.value
            read.complete(empty); advanceUntilIdle()
            assertEquals(false, completed)
            assertEquals(b, session.current())
            assertEquals(draftB, subject.pending.value)
            assertEquals(queuedB, subject.queued.value)
        } finally { read.complete(empty); subject.viewModelScope.cancel() }
    }

    @Test fun `cambiar cuenta no hereda alias contador ni apertura de split y ACK viejo no afecta B`() = runTest {
        val subject = vm()
        session.start(session.current()!!.copy(localOrderId = "local-a", valorRondasSinRedCents = 7000, openSplitOnArrival = true))
        coEvery { repository.getOrderDetail(any(), "b") } returns Result.success(detail(75.0, "b"))
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } answers {
            if ("local-a" in arg<Set<String>>(1)) TrabajoSinResolver(1, 0, setOf("ADD_ITEMS"), 0) else empty
        }
        try {
            val next = subject.switchToCheck(OpenCheckSummary(id = "b", orderNumber = "B", total = 75.0, version = 4))!!
            advanceUntilIdle()
            assertNull(next.localOrderId); assertEquals(0, next.valorRondasSinRedCents)
            assertFalse(next.openSplitOnArrival); assertTrue(subject.chequeAlDia.value)
            acks.emit(SyncAck("old-a", "ACKED", result = buildJsonObject { put("localOrderId", "local-a") }))
            advanceUntilIdle()
            assertTrue(subject.chequeAlDia.value)
            coVerify(exactly = 1) { repository.getOrderDetail(any(), "b") }
            assertTrue(subject.preparePagar()); assertEquals(7500, session.current()!!.totalCents)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `volver a la misma cuenta conserva alias y valor provisional`() = runTest {
        val subject = vm(provisional = true)
        session.start(session.current()!!.copy(valorRondasSinRedCents = 7000, openSplitOnArrival = true))
        try {
            val next = subject.switchToCheck(OpenCheckSummary(id = "o1", total = 70.0, isProvisional = true))!!
            assertEquals("local-origin", next.localOrderId)
            assertEquals(7000, next.valorRondasSinRedCents); assertTrue(next.openSplitOnArrival)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `caller real de Pagar usa saldo con modificadores y deja borrador separado`() = runTest {
        val subject = vm(provisional = true)
        try {
            subject.addProductWithModifiers(Product(id = "p", name = "Artículo", priceValue = 50.0), 2,
                listOf(SelectedModifier("g", "Opciones", "m", "Extra", 500)), null)
            subject.sendRound(); advanceUntilIdle()
            subject.addCustomAmount("Borrador aparte", 2300)
            val shown = subject.payableTotalCents
            var charged: Int? = null
            prepararPagoDeMesa(subject) { charged = session.current()!!.totalCents }
            assertEquals(11000, shown); assertEquals(shown, charged)
            assertEquals(2300, subject.pendingTotalCents)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `caller real de Pagar llega al cierre cero por cortesia enviada`() = runTest {
        val subject = vm(provisional = true)
        try {
            subject.addProductWithModifiers(Product(id = "p", name = "Artículo", priceValue = 50.0), 2, emptyList(), null,
                isCortesia = true, cortesiaReason = "Cortesía autorizada")
            subject.sendRound(); advanceUntilIdle()
            assertTrue(subject.queued.value.isNotEmpty()); assertTrue(subject.pending.value.isEmpty())
            val shown = subject.payableTotalCents
            var charged: Int? = null
            prepararPagoDeMesa(subject) { charged = session.current()!!.totalCents }
            assertEquals(0, shown); assertEquals(shown, charged)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `caller real de Pagar muestra y prepara saldo despues de abono`() = runTest {
        val subject = vm()
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail().copy(
            items = listOf(OrderDetailItem(id = "i1", quantity = 1, unitPrice = 100.0, total = 100.0)),
            payments = listOf(OrderDetailPayment(id = "paid", amount = 50.0, status = "COMPLETED"))))
        try {
            subject.loadCheck(); advanceUntilIdle()
            val shown = subject.payableTotalCents
            var charged: Int? = null
            prepararPagoDeMesa(subject) { charged = session.current()!!.totalCents }
            assertEquals(5000, shown); assertEquals(shown, charged)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `ambos paneles reales muestran el saldo de VM y llaman al handler probado`() {
        val path = "src/main/java/com/avoqado/pos/tables/presentation/TableOrderScreen.kt"
        val source = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
        assertEquals(2, source.componentsCount("payableTotalCents = viewModel.payableTotalCents"))
        assertTrue(source.contains("prepararPagoDeMesa(viewModel, onReady)"))
        assertTrue(source.contains("val subtotalCents = payableTotalCents"))
        assertFalse(source.contains("checkTotalCents(check) + pendingTotalCents + queuedCents"))
    }

    private fun String.componentsCount(value: String): Int = split(value).size - 1

    @Test fun `las diecisiete llamadas de escritura reales llevan la barrera de generacion`() {
        val path = "src/main/java/com/avoqado/pos/tables/presentation/TableOrderViewModel.kt"
        val source = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
        val names = listOf("addRound", "applyOrderDiscount", "applyServiceCharge", "assignOrder", "cancelOrder", "clearTable", "compItem", "compWholeOrder", "mergeOrders", "moveOrder", "redeemLoyaltyPoints", "removeOrderDiscount", "removeServiceCharge", "splitOrder", "splitOrderBySeat", "updateOrderDetails")
        for (name in names) {
            val real = Regex("repository\\.$name\\(").findAll(source).count()
            val wrapped = Regex("mutar\\s*\\{\\s*repository\\.$name\\(").findAll(source).count()
            assertEquals(name, real, wrapped); assertTrue(name, real > 0)
        }
    }

    private val ownedVenue = "v-cuenta-garantizada"
    private fun floor(): TablesViewModel {
        every { repository.isLoading } returns MutableStateFlow(false)
        return TablesViewModel(repository, mockk(relaxed = true), session, mockk(relaxed = true), storage, outbox, mockk(relaxed = true))
    }
    private fun table(id: String = "o1", tableId: String = "t1", provisional: Boolean = true) =
        DiningTable(tableId, "5", openOrders = listOf(OpenCheckSummary(id, "LOCAL", total = 0.0, isProvisional = provisional)))
    private fun coordinator(type: String, read: (suspend () -> Unit)? = null): TableSyncCoordinator {
        val dao = mockk<SyncIntentDao>()
        coEvery { dao.preparationStatuses(any(), any()) } coAnswers {
            read?.invoke()
            listOf(SyncIntentEntity(secondArg<List<String>>().firstOrNull() ?: "ack", ownedVenue, seq = 1, type = type, payloadJson = "{}"))
        }
        // Consulta controlada de IO, coordinador/Session/VM reales.
        return TableSyncCoordinator(outbox, session, repository, storage, dao)
    }

    @Test fun `Guardar piso reabrir restaura ronda real con modificadores y alias`() = runTest {
        val root = java.nio.file.Files.createTempDirectory("parking-round").toFile()
        val drafts = TableRoundDraftStore(root)
        val a = vm(provisional = true, drafts = drafts)
        val f = floor(); var b: TableOrderViewModel? = null
        try {
            a.addProductWithModifiers(Product(id = "p", name = "Artículo", priceValue = 50.0), 2,
                listOf(SelectedModifier("g", "Opciones", "m", "Extra", 500)), null)
            a.sendRound(); advanceUntilIdle()
            assertTrue(a.exitToFloor()); assertNull(session.current())
            var ready = false; f.startOrdering(table()) { ready = true }; assertTrue(ready)
            assertEquals(11000, session.current()!!.valorRondasSinRedCents)
            assertEquals("local-origin", session.current()!!.localOrderId)
            b = vm(keepSession = true, drafts = drafts); b.loadCheck(); advanceUntilIdle()
            assertEquals(1, b.queued.value.size)
            assertEquals(11000, b.payableTotalCents)
            var charge: Int? = null; prepararPagoDeMesa(b) { charge = session.current()!!.totalCents }
            assertEquals(11000, charge)
        } finally { a.viewModelScope.cancel(); b?.viewModelScope?.cancel(); f.viewModelScope.cancel(); root.deleteRecursively() }
    }

    @Test fun `el saldo leido de disco al reabrir notifica a la pantalla sin otro toque`() = runTest {
        val subject = vm(provisional = true)
        session.start(session.current()!!.copy(totalCents = 0, valorRondasSinRedCents = 8000))
        val disk = CompletableDeferred<TrabajoSinResolver>()
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } coAnswers { disk.await() }
        val observer = SnapshotStateObserver { it() }
        var displayed = -1
        try {
            observer.start()
            observer.observeReads(Unit, { displayed = subject.payableTotalCents }) {
                displayed = subject.payableTotalCents
            }
            assertEquals(0, displayed)
            subject.loadCheck(); runCurrent()
            disk.complete(empty); advanceUntilIdle()
            Snapshot.sendApplyNotifications()
            assertEquals(8000, subject.payableTotalCents)
            assertEquals("La UI debe enterarse sin un toque que fuerce recomponer", 8000, displayed)
        } finally { observer.stop(); observer.clear(); subject.viewModelScope.cancel() }
    }

    @Test fun `cortesia real conserva cero conocido al guardar y reabrir`() = runTest {
        val root = java.nio.file.Files.createTempDirectory("parking-free").toFile(); val drafts = TableRoundDraftStore(root)
        val a = vm(provisional = true, drafts = drafts); val f = floor(); var b: TableOrderViewModel? = null
        try {
            a.addProductWithModifiers(Product(id = "p", name = "Artículo", priceValue = 50.0), 2, emptyList(), null,
                isCortesia = true, cortesiaReason = "Cortesía autorizada")
            a.sendRound(); advanceUntilIdle(); assertTrue(a.exitToFloor()); f.startOrdering(table()) {}
            b = vm(keepSession = true, drafts = drafts); b.loadCheck(); advanceUntilIdle()
            assertTrue(session.current()!!.saldoSinRedConocido); assertTrue(b.preparePagar())
            assertEquals(0, session.current()!!.totalCents)
        } finally { a.viewModelScope.cancel(); b?.viewModelScope?.cancel(); f.viewModelScope.cancel(); root.deleteRecursively() }
    }

    @Test fun `alternar cuentas conserva A y B sin heredar dinero entre ellas`() = runTest {
        val a = vm(provisional = true); val f = floor(); val opened = mutableListOf<TableOrderViewModel>()
        try {
            a.addCustomAmount("Ronda A", 11000); a.sendRound(); advanceUntilIdle(); assertTrue(a.exitToFloor())
            f.startOrdering(table("b", "t2")) {}
            // Abrir desde un resumen sin snapshot no inventa dinero conocido.
            assertFalse(session.current()!!.saldoSinRedConocido)
            session.start(session.current()!!.copy(saldoSinRedConocido = true, localOrderId = "local-b"))
            val b = vm(keepSession = true).also { opened += it }
            b.addCustomAmount("Ronda B", 7000); b.sendRound(); advanceUntilIdle(); assertTrue(b.exitToFloor())
            f.startOrdering(table()) {}; assertEquals(11000, session.current()!!.valorRondasSinRedCents)
            assertEquals("local-origin", session.current()!!.localOrderId)
            assertTrue(vm(keepSession = true).also { opened += it }.exitToFloor()); f.startOrdering(table("b", "t2")) {}
            assertEquals(7000, session.current()!!.valorRondasSinRedCents); assertEquals("local-b", session.current()!!.localOrderId)
        } finally { a.viewModelScope.cancel(); opened.forEach { it.viewModelScope.cancel() }; f.viewModelScope.cancel() }
    }

    @Test fun `abono de outbox se resta una vez despues de reabrir contador bruto`() = runTest {
        val a = vm(provisional = true); val f = floor()
        try {
            a.addCustomAmount("Ronda", 11000); a.sendRound(); advanceUntilIdle()
            assertTrue(a.exitToFloor()); f.startOrdering(table()) {}
            coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(1, 0, setOf("PAY_CASH"), 5000)
            assertTrue(a.preparePagar()); assertEquals(6000, session.current()!!.totalCents)
            assertEquals(11000, session.current()!!.valorRondasSinRedCents)
            assertTrue(a.exitToFloor()); f.startOrdering(table()) {}
            assertTrue(a.preparePagar()); assertEquals(6000, session.current()!!.totalCents)
        } finally { a.viewModelScope.cancel(); f.viewModelScope.cancel() }
    }

    @Test fun `ACK OPEN desde piso promueve sin activar y GET fallido no permite fallback cero`() = runTest {
        val a = vm(provisional = true); val f = floor()
        try {
            a.addCustomAmount("Ronda", 11000); a.sendRound(); advanceUntilIdle(); assertTrue(a.exitToFloor())
            val c = coordinator("OPEN_TABLE")
            val ack = SyncAck("open", "ACKED", result = buildJsonObject { put("localOrderId", "o1"); put("orderId", "real"); put("version", 2) })
            c.handle(ack); c.handle(ack); assertNull(session.current())
            f.startOrdering(table("real", provisional = false)) {}
            assertEquals("local-origin", session.current()!!.localOrderId); assertTrue(session.current()!!.requiereLecturaServidor)
            coEvery { repository.getOrderDetail(any(), any()) } returns Result.failure(IOException("sin red"))
            a.loadCheck(); advanceUntilIdle(); assertFalse(a.preparePagar())
            coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail(110.0, "real"))
            a.loadCheck(); advanceUntilIdle(); assertTrue(a.preparePagar()); assertEquals(11000, session.current()!!.totalCents)
        } finally { a.viewModelScope.cancel(); f.viewModelScope.cancel() }
    }

    @Test fun `salir o cambiar durante enqueue suspendido conserva la ronda y sesion propia`() = runTest {
        val a = vm(provisional = true); val enqueue = CompletableDeferred<String>()
        coEvery { outbox.enqueue(any(), any(), any(), any(), any(), any()) } coAnswers { enqueue.await() }
        try {
            a.addCustomAmount("Ronda", 11000); a.sendRound(); runCurrent()
            assertTrue(a.hayMutacionEnVuelo); assertFalse(a.exitToFloor())
            assertNull(a.switchToCheck(OpenCheckSummary("b", isProvisional = true)))
            assertEquals("o1", session.current()!!.orderId)
            enqueue.complete("round"); advanceUntilIdle(); assertTrue(a.exitToFloor())
            floor().also { f -> try { f.startOrdering(table()) {}; assertEquals(11000, session.current()!!.valorRondasSinRedCents) } finally { f.viewModelScope.cancel() } }
        } finally { enqueue.complete("round"); a.viewModelScope.cancel() }
    }

    @Test fun `finalizacion tardia A no borra B ni restaura A pagada desde piso viejo`() = runTest {
        val a = vm(provisional = true); val f = floor(); val done = CompletableDeferred<Result<Unit>>()
        try {
            session.start(session.current()!!.copy(mode = TableSession.Mode.PAYING))
            coEvery { repository.clearTable(any(), any()) } coAnswers { done.await() }
            f.finishTableAfterPayment(); runCurrent()
            assertTrue(a.exitToFloor()); f.startOrdering(table("b", "t2")) {}
            val b = session.current(); done.complete(Result.success(Unit)); advanceUntilIdle()
            assertEquals(b, session.current())
            assertTrue(a.exitToFloor()); f.startOrdering(table()) {}
            assertFalse(session.current()!!.saldoSinRedConocido); assertFalse(a.preparePagar())
        } finally { done.complete(Result.success(Unit)); a.viewModelScope.cancel(); f.viewModelScope.cancel() }
    }

    @Test fun `ACK cash completo desde piso invalida solo cuenta pagada y parcial la conserva`() = runTest {
        val a = vm(provisional = true); val f = floor()
        try {
            a.addCustomAmount("Ronda", 11000); a.sendRound(); advanceUntilIdle(); assertTrue(a.exitToFloor())
            val c = coordinator("PAY_CASH")
            c.handle(SyncAck("partial", "ACKED", result = buildJsonObject { put("orderId", "o1"); put("remainingBalanceCents", 6000) }))
            f.startOrdering(table()) {}; assertEquals(11000, session.current()!!.valorRondasSinRedCents)
            assertFalse(session.current()!!.saldoSinRedConocido); assertFalse(a.preparePagar())
            assertTrue(a.exitToFloor())
            c.handle(SyncAck("paid", "ACKED", result = buildJsonObject { put("orderId", "o1"); put("remainingBalanceCents", 0) }))
            f.startOrdering(table()) {}; assertFalse(session.current()!!.saldoSinRedConocido); assertFalse(a.preparePagar())
        } finally { a.viewModelScope.cancel(); f.viewModelScope.cancel() }
    }

    @Test fun `ACK suspendido del contexto anterior no finaliza la sesion nueva con mismo id`() = runTest {
        val a = vm(provisional = true); val read = CompletableDeferred<Unit>()
        session.sincronizarContexto("staff", ownedVenue)
        session.start(TableSession.Active("t1", "5", null, "o1", "LOCAL", 1, 0, TableSession.Mode.ORDERING, isProvisional = true), ownedVenue)
        val c = coordinator("PAY_CASH") { read.await() }
        try {
            val task = async { c.handle(SyncAck("old", "ACKED", result = buildJsonObject { put("orderId", "o1"); put("remainingBalanceCents", 0) })) }
            runCurrent(); session.clear(); session.sincronizarContexto("staff", ownedVenue)
            val fresh = TableSession.Active("t1", "5", null, "o1", "NEW", 1, 0, TableSession.Mode.ORDERING, isProvisional = true)
            session.start(fresh, ownedVenue); read.complete(Unit); task.await()
            assertEquals(fresh, session.current())
        } finally { read.complete(Unit); a.viewModelScope.cancel() }
    }

    @Test fun `provisional sin snapshot no interpreta cero por default como cortesia`() = runTest {
        val root = java.nio.file.Files.createTempDirectory("parking-unknown").toFile(); val drafts = TableRoundDraftStore(root)
        val a = vm(provisional = true, drafts = drafts); val f = floor(); var reopened: TableOrderViewModel? = null
        try {
            a.addCustomAmount("Ronda", 11000); a.sendRound(); advanceUntilIdle()
            session.clear(); f.startOrdering(table()) {}
            reopened = vm(keepSession = true, drafts = drafts); reopened.loadCheck(); advanceUntilIdle()
            assertFalse(session.current()!!.saldoSinRedConocido)
            var enteredPayment = false; prepararPagoDeMesa(reopened) { enteredPayment = true }
            assertFalse(reopened.saldoDeMesaConocido); assertFalse(enteredPayment)
            assertEquals("No se pudo recuperar el saldo completo de esta cuenta. Conéctate y reabre la cuenta antes de cobrar; tus productos siguen guardados.", reopened.blockedNotice.value)
        } finally { a.viewModelScope.cancel(); reopened?.viewModelScope?.cancel(); f.viewModelScope.cancel(); root.deleteRecursively() }
    }


    @Test fun `enqueue viejo no marca desconocida una apertura nueva del mismo id despues de logout`() = runTest {
        val a = vm(provisional = true); val enqueue = CompletableDeferred<String>()
        coEvery { outbox.enqueue(any(), any(), any(), any(), any(), any()) } coAnswers { enqueue.await() }
        try {
            a.addCustomAmount("Ronda", 11000); a.sendRound(); runCurrent()
            session.clear()
            val fresh = TableSession.Active("t1", "5", null, "o1", "NEW", 1, 0, TableSession.Mode.ORDERING, isProvisional = true)
            session.start(fresh, ownedVenue); enqueue.complete("round"); advanceUntilIdle()
            assertEquals(fresh, session.current())
        } finally { enqueue.complete("round"); a.viewModelScope.cancel() }
    }


    @Test fun `anulacion masiva desde piso invalida A estacionada sin borrar B`() = runTest {
        val a = vm(provisional = true); val f = floor()
        try {
            a.addCustomAmount("Ronda A", 11000); a.sendRound(); advanceUntilIdle(); assertTrue(a.exitToFloor())
            f.startOrdering(table("b", "t2")) {}; val b = session.current()
            coEvery { repository.cancelOrder(any(), any(), any()) } returns Result.success(Unit)
            coEvery { repository.clearTable(any(), any()) } returns Result.success(Unit)
            var completed = false
            f.bulkAnular(listOf(table()), "Cancelación autorizada") { ok, fail, _ -> assertEquals(1, ok); assertEquals(0, fail); completed = true }
            advanceUntilIdle(); assertTrue(completed); assertEquals(b, session.current())
            assertTrue(a.exitToFloor()); f.startOrdering(table()) {}; assertFalse(session.current()!!.saldoSinRedConocido)
        } finally { a.viewModelScope.cancel(); f.viewModelScope.cancel() }
    }


    @Test fun `GET anterior al logout no actualiza version de nueva sesion con misma identidad`() = runTest {
        val old = vm(); val pending = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.getOrderDetail(any(), any()) } coAnswers { pending.await() }
        try {
            old.loadCheck(); runCurrent(); session.clear()
            val fresh = TableSession.Active("t1", "5", null, "o1", "NEW", 77, 7000, TableSession.Mode.ORDERING)
            session.start(fresh, ownedVenue); pending.complete(Result.success(detail(100.0))); advanceUntilIdle()
            assertEquals(fresh, session.current()); assertNull(old.check.value); assertFalse(old.chequeAlDia.value)
        } finally { pending.complete(Result.failure(IOException("cleanup"))); old.viewModelScope.cancel() }
    }

    @Test fun `merge desde piso invalida origen estacionado y saldo del destino sin borrar otra cuenta`() = runTest {
        val a = vm(provisional = true); val f = floor()
        try {
            a.addCustomAmount("Ronda A", 11000); a.sendRound(); advanceUntilIdle(); assertTrue(a.exitToFloor())
            f.startOrdering(table("target", "t2")) {}
            val target = session.current()!!.copy(saldoSinRedConocido = true, valorRondasSinRedCents = 7000)
            session.start(target, ownedVenue); assertTrue(a.exitToFloor())
            f.startOrdering(table("other", "t3")) {}; val other = session.current()
            coEvery { repository.mergeOrders(any(), any(), any()) } returns Result.success(Unit)
            var finished = false
            f.bulkUnir(table("target", "t2"), listOf(table())) { ok, fail, _ -> assertEquals(1, ok); assertEquals(0, fail); finished = true }
            advanceUntilIdle(); assertTrue(finished); assertEquals(other, session.current())
            assertTrue(a.exitToFloor()); f.startOrdering(table()) {}; assertFalse(session.current()!!.saldoSinRedConocido)
            assertTrue(a.exitToFloor()); f.startOrdering(table("target", "t2")) {}; assertFalse(session.current()!!.saldoSinRedConocido)
        } finally { a.viewModelScope.cancel(); f.viewModelScope.cancel() }
    }

    @Test fun `move desde piso invalida contador de la mesa anterior y exige reconciliacion`() = runTest {
        val a = vm(provisional = true); val f = floor()
        try {
            a.addCustomAmount("Ronda A", 11000); a.sendRound(); advanceUntilIdle(); assertTrue(a.exitToFloor())
            coEvery { repository.moveOrder(any(), any(), any()) } returns Result.success(Unit)
            var finished = false; f.bulkMover(table(), "t2") { ok, _ -> assertTrue(ok); finished = true }
            advanceUntilIdle(); assertTrue(finished); assertNull(session.current())
            f.startOrdering(table()) {}; assertFalse(session.current()!!.saldoSinRedConocido)
            assertFalse(a.preparePagar())
        } finally { a.viewModelScope.cancel(); f.viewModelScope.cancel() }
    }

    @Test fun `Cobrar directo desde piso no salta GET despues de ACK estacionado`() = runTest {
        val a=vm(provisional=true); val f=floor()
        try {
            a.addCustomAmount("Ronda",11000); a.sendRound(); advanceUntilIdle(); assertTrue(a.exitToFloor())
            coordinator("OPEN_TABLE").handle(SyncAck("open","ACKED",result=buildJsonObject { put("localOrderId","o1"); put("orderId","real"); put("version",2) }))
            val summary=table("real",provisional=false)
            every { repository.tables } returns MutableStateFlow(listOf(summary))
            var ready=false; f.startCobrar(summary) { ready=true }; advanceUntilIdle()
            assertFalse(ready); assertNull(session.current())
            assertEquals("Abre esta cuenta para revisar el saldo antes de cobrar.",(f.actionState.value as TableActionState.Error).message)
        } finally { a.viewModelScope.cancel(); f.viewModelScope.cancel() }
    }

    @Test fun `Pagar con Room suspendido no cruza logout aunque Active nueva sea identica`() = runTest {
        val old=vm(provisional=true); val query=CompletableDeferred<TrabajoSinResolver>()
        val original=session.current()!!
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(),any()) } coAnswers { query.await() }
        try {
            val payment=async { old.preparePagar() }; runCurrent()
            session.clear(); session.start(original,ownedVenue); query.complete(empty)
            assertFalse(payment.await()); assertEquals(original,session.current())
        } finally { query.complete(empty); old.viewModelScope.cancel() }
    }

    @Test fun `Cobrar de piso con Room suspendido no cruza logout cuando ambas Active son nil`() = runTest {
        val old=vm(); val f=floor(); val query=CompletableDeferred<TrabajoSinResolver>()
        val summary=table(provisional=false)
        every { repository.tables } returns MutableStateFlow(listOf(summary)); session.clear()
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(),any()) } coAnswers { query.await() }
        try {
            var ready=false; f.startCobrar(summary) { ready=true }; runCurrent()
            session.clear(); query.complete(empty); advanceUntilIdle()
            assertFalse(ready); assertNull(session.current())
        } finally { query.complete(empty); old.viewModelScope.cancel(); f.viewModelScope.cancel() }
    }

    @Test fun `Pre cuenta de piso con GET suspendido no cruza logout de misma sucursal`() = runTest {
        val old=vm(); val f=floor(); val get=CompletableDeferred<Result<OrderDetail>>()
        val summary=table(provisional=false)
        every { repository.tables } returns MutableStateFlow(listOf(summary))
        coEvery { repository.getOrderDetail(any(),any()) } coAnswers { get.await() }
        try {
            val print=async { f.prepararPreCuenta(summary) }; runCurrent()
            session.clear(); get.complete(Result.success(detail())); assertNull(print.await())
        } finally { get.complete(Result.failure(IOException("cleanup"))); old.viewModelScope.cancel(); f.viewModelScope.cancel() }
    }

    @Test fun `ACK retenido de antes del logout no invalida VM nueva de la misma orden`() = runTest {
        session.sincronizarContexto("same-user", ownedVenue)
        val oldEpoch = session.contextoActual()
        val previous = vm()
        session.clear(); session.sincronizarContexto("same-user", ownedVenue)
        val subject = vm()
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail())
        try {
            subject.loadCheck(); advanceUntilIdle(); assertTrue(subject.chequeAlDia.value)
            val fresh = session.current()
            coEvery { repository.getOrderDetail(any(), any()) } returns Result.failure(IOException("sin red"))
            acks.emit(SyncAck("old-round", "ACKED", result = buildJsonObject { put("orderId", "o1"); put("version", 9) }, contextoDeSesion = oldEpoch))
            advanceUntilIdle()
            assertTrue(subject.chequeAlDia.value); assertFalse(subject.vioCambios)
            assertEquals(fresh, session.current())
            coVerify(exactly = 1) { repository.getOrderDetail(any(), any()) }
        } finally { previous.viewModelScope.cancel(); subject.viewModelScope.cancel() }
    }

    @Test fun `VM anterior al logout no promueve nueva Active al recibir ACK de sesion vigente`() = runTest {
        session.sincronizarContexto("same-user", ownedVenue)
        val old = vm(provisional = true)
        runCurrent() // The real collector is subscribed before this session is replaced.
        session.clear(); session.sincronizarContexto("same-user", ownedVenue)
        val fresh = TableSession.Active("t1", "5", null, "o1", "NEW", 77, 7000, TableSession.Mode.ORDERING,
            isProvisional = true, localOrderId = "o1", valorRondasSinRedCents = 7000)
        session.start(fresh, ownedVenue)
        try {
            acks.emit(SyncAck("new-open", "ACKED", result = buildJsonObject {
                put("localOrderId", "o1"); put("orderId", "real-order"); put("version", 9)
            }, contextoDeSesion = session.contextoActual()))
            advanceUntilIdle()
            assertEquals(fresh, session.current()); assertFalse(old.vioCambios)
        } finally { old.viewModelScope.cancel() }
    }





    @Test fun `T4 Pagar lleva cargo del cheque garantizado sin abonos y conserva total exacto`() = runTest {
        val subject = vm()
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail(115.0).copy(serviceChargeAmount = 15.0))
        try {
            subject.loadCheck(); advanceUntilIdle(); assertTrue(subject.chequeAlDia.value)
            assertTrue(subject.preparePagar())
            assertEquals(11500, session.current()!!.totalCents)
            assertEquals(1500, session.current()!!.serviceChargeCents)
            assertEquals(TableSession.Mode.PAYING, session.current()!!.mode)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `T4 Pagar saldo tras abono no vuelve a separar cargo de la cuenta original`() = runTest {
        val subject = vm()
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail(115.0).copy(
            serviceChargeAmount = 15.0, payments = listOf(OrderDetailPayment("p1", 50.0, 0.0, "COMPLETED")),
        ))
        try {
            subject.loadCheck(); advanceUntilIdle(); assertTrue(subject.chequeAlDia.value)
            assertTrue(subject.preparePagar())
            assertEquals(6500, session.current()!!.totalCents)
            assertEquals(0, session.current()!!.serviceChargeCents)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `T4 el fallback del plano sin cheque no acredita un cargo viejo`() = runTest {
        val subject = vm()
        session.start(session.current()!!.copy(totalCents = 11500, serviceChargeCents = 1500))
        try {
            assertNull(subject.check.value); assertFalse(subject.chequeAlDia.value)
            assertTrue(subject.preparePagar())
            assertEquals(11500, session.current()!!.totalCents)
            assertEquals(0, session.current()!!.serviceChargeCents)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `T4 la cuenta provisional conserva saldo sin inventar cargo de un cheque inexistente`() = runTest {
        val subject = vm(provisional = true)
        session.start(session.current()!!.copy(totalCents = 11500, valorRondasSinRedCents = 11500, serviceChargeCents = 1500))
        try {
            assertNull(subject.check.value); assertFalse(subject.chequeAlDia.value)
            assertTrue(subject.preparePagar())
            assertTrue(session.current()!!.isProvisional)
            assertEquals(11500, session.current()!!.totalCents)
            assertEquals(0, session.current()!!.serviceChargeCents)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `T4 el GET retenido descarta cargo anterior antes de leer y el error no lo resucita`() = runTest {
        val subject = vm()
        val response = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.getOrderDetail(any(), any()) } coAnswers { response.await() }
        session.start(session.current()!!.copy(serviceChargeCents = 1500))
        try {
            subject.loadCheck(); runCurrent()
            assertFalse(subject.chequeAlDia.value)
            assertEquals("El nuevo GET aún no acredita dinero", 0, session.current()!!.serviceChargeCents)
            response.complete(Result.failure(IOException("sin red"))); advanceUntilIdle()
            assertEquals(0, session.current()!!.serviceChargeCents)
            assertEquals(10000, session.current()!!.totalCents)
        } finally { response.complete(Result.failure(IOException("cleanup"))); subject.viewModelScope.cancel() }
    }

    @Test fun `T4 un cargo no representable no se transforma en centavos al habilitar pago`() = runTest {
        val subject = vm()
        try {
            for (cargo in listOf(Double.NaN, Double.POSITIVE_INFINITY, -15.0, 200.0)) {
                session.start(session.current()!!.copy(mode = TableSession.Mode.ORDERING))
                coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(detail(115.0).copy(serviceChargeAmount = cargo))
                subject.loadCheck(); advanceUntilIdle()
                assertTrue(subject.preparePagar()); assertEquals(11500, session.current()!!.totalCents)
                assertEquals(0, session.current()!!.serviceChargeCents)
            }
        } finally { subject.viewModelScope.cancel() }
    }

}
