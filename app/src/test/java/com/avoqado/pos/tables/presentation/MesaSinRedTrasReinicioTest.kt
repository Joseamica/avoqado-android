package com.avoqado.pos.tables.presentation

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.avoqado.pos.core.data.sync.FilaDeLaOrden
import com.avoqado.pos.core.data.sync.SyncAck
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.pos.data.model.SelectedModifier
import com.avoqado.pos.printing.data.PrinterService
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * D5 · B2a: la mesa abierta SIN internet se cobra en efectivo aunque la app se reinicie (escenario 7b del ensayo), y una mesa
 * ya cobrada sin internet no se vuelve a cobrar (9b, `hz:e5ee98bc:D13`). VM, sesión y borrador reales; la cola, controlada.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MesaSinRedTrasReinicioTest {
    @get:Rule val main = MainDispatcherRule()
    private val repository = mockk<TableServiceRepository>(relaxed = true)
    private val outbox = mockk<SyncOutbox>(relaxed = true)
    private val session = TableSession()
    private val acks = MutableSharedFlow<SyncAck>(extraBufferCapacity = 4)
    private val storage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns "v-b2a" }

    private fun vm(keepSession: Boolean = false, drafts: TableRoundDraftStore? = null): TableOrderViewModel {
        every { repository.tables } returns MutableStateFlow(emptyList())
        every { repository.ownership } returns MutableStateFlow(TableServiceRepository.TableOwnership())
        every { outbox.acks } returns acks
        every { outbox.pendingCount } returns MutableStateFlow(0)
        every { outbox.observarEstado(any()) } returns flowOf("PENDING")
        coEvery { outbox.enqueue(any(), any(), any(), any(), any(), any()) } answers { arg<String>(3) }
        val config = mockk<PrintConfigRepository>(relaxed = true)
        every { config.getCurrentConfig() } returns PrintConfig()
        val connectivity = mockk<ConnectivityMonitor>(relaxed = true)
        every { connectivity.isConnected } returns MutableStateFlow(false)
        if (!keepSession) session.start(TableSession.Active("t1", "5", "Patio", "local-1", "LOCAL", 1, 0, TableSession.Mode.ORDERING,
            isProvisional = true, localOrderId = "local-1"))
        return TableOrderViewModel(repository, session, config, mockk(relaxed = true), mockk<PrinterService>(relaxed = true), storage,
            outbox, mockk(relaxed = true), connectivity, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), draftStore = drafts)
    }

    private fun trabajo(cobradoCents: Int) = TrabajoSinResolver(3, 0, setOf("OPEN_TABLE", "ADD_ITEMS", "PAY_CASH"), cobradoCents)

    @Test fun `9b mesa cobrada sin red no ofrece otro cobro en cero`() = runTest {
        val subject = vm()
        try {
            subject.addCustomAmount("Coca y café", 5500); subject.sendRound(); advanceUntilIdle()
            coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns trabajo(5500)
            assertFalse(subject.preparePagar())
            assertEquals(TableSession.Mode.ORDERING, session.current()!!.mode)
            assertEquals("Esta cuenta ya está pagada: falta subirla. Se sube sola cuando vuelva la conexión; no la cobres otra vez.",
                subject.blockedNotice.value)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `9b cierre de cero ya cobrado no se cobra otra vez`() = runTest {
        val subject = vm()
        try {
            subject.addProductWithModifiers(Product(id = "p", name = "Agua", priceValue = 20.0), 1, emptyList(), null,
                isCortesia = true, cortesiaReason = "Cortesía autorizada")
            subject.sendRound(); advanceUntilIdle()
            coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns trabajo(0)
            assertFalse(subject.preparePagar())
            assertEquals(TableSession.Mode.ORDERING, session.current()!!.mode)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `abono parcial sin red deja cobrar el resto`() = runTest {
        val subject = vm()
        try {
            subject.addCustomAmount("Ronda", 10000); subject.sendRound(); advanceUntilIdle()
            coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns trabajo(4000)
            assertTrue(subject.preparePagar()); assertEquals(6000, session.current()!!.totalCents)
        } finally { subject.viewModelScope.cancel() }
    }

    // MARK: - 7b: la app se reinicia sin internet

    private val texto9b = "Esta cuenta ya está pagada: falta subirla. Se sube sola cuando vuelva la conexión; no la cobres otra vez."

    /** «Matar la app»: la sesión vive sólo en memoria; el borrador y la cola siguen en disco. Se reabre desde el plano. */
    private fun reiniciar(antes: TableOrderViewModel, drafts: TableRoundDraftStore): TableOrderViewModel {
        antes.viewModelScope.cancel()
        session.clear()
        session.abrirDesdePiso("v-b2a", TableSession.Active("t1", "5", null, "local-1", "LOCAL", 1, 0, TableSession.Mode.ORDERING,
            isProvisional = true))
        return vm(keepSession = true, drafts = drafts)
    }

    /** La cola de esta tablet: la apertura, una fila por ronda del borrador (salvo las que se quiten) y el efectivo. */
    private fun cola(drafts: TableRoundDraftStore, vararg efectivo: Int, sinRondas: Set<String> = emptySet(), extra: List<FilaDeLaOrden> = emptyList()) {
        val rondas = drafts.load("v-b2a", "local-1").rounds.keys - sinRondas
        val filas = listOf(FilaDeLaOrden("open", "OPEN_TABLE", "PENDING", null)) +
            rondas.map { FilaDeLaOrden(it, "ADD_ITEMS", "PENDING", null) } +
            efectivo.mapIndexed { i, cents -> FilaDeLaOrden("cobro-$i", "PAY_CASH", "PENDING", cents) } + extra
        coEvery { outbox.filasSinResolverDeLaOrden(any(), any()) } returns filas
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns
            TrabajoSinResolver(filas.size, 0, filas.map { it.tipo }.toSet(), efectivo.sum())
    }

    private fun conDisco(prueba: suspend TestScope.(TableRoundDraftStore) -> Unit) = runTest {
        val root = java.nio.file.Files.createTempDirectory("b2a").toFile()
        try { prueba(TableRoundDraftStore(root)) } finally { root.deleteRecursively() }
    }

    @Test fun `7b mesa abierta sin red con una ronda se cobra tras reiniciar la app`() = conDisco { drafts ->
        val a = vm(drafts = drafts)
        a.addProductWithModifiers(Product(id = "p", name = "Café", priceValue = 50.0), 2,
            listOf(SelectedModifier("g", "Opciones", "m", "Extra", 500)), null)
        a.sendRound(); advanceUntilIdle()
        val b = reiniciar(a, drafts)
        try {
            cola(drafts)
            b.loadCheck(); advanceUntilIdle()
            assertTrue(session.current()!!.saldoSinRedConocido)
            assertEquals(11000, b.payableTotalCents)
            var cobro: Int? = null; prepararPagoDeMesa(b) { cobro = session.current()!!.totalCents }
            assertEquals(11000, cobro)
            assertEquals(TableSession.Mode.PAYING, session.current()!!.mode)
            assertTrue(session.current()!!.isProvisional)
        } finally { b.viewModelScope.cancel() }
    }

    @Test fun `7b abono y resto tras reiniciar y despues ya pagada`() = conDisco { drafts ->
        val a = vm(drafts = drafts)
        a.addCustomAmount("Ronda", 10000); a.sendRound(); advanceUntilIdle()
        val b = reiniciar(a, drafts)
        cola(drafts, 4000)
        assertTrue(b.preparePagar()); assertEquals(6000, session.current()!!.totalCents)
        val c = reiniciar(b, drafts)
        try {
            cola(drafts, 4000, 6000)
            c.loadCheck(); advanceUntilIdle()
            assertTrue(c.cuentaCobradaAqui)
            assertFalse(c.preparePagar())
            assertEquals(texto9b, c.blockedNotice.value)
            assertEquals(TableSession.Mode.ORDERING, session.current()!!.mode)
        } finally { c.viewModelScope.cancel() }
    }

    @Test fun `7b borrador sin fila espera y al volver a encolarse se cobra`() = conDisco { drafts ->
        val a = vm(drafts = drafts)
        a.addCustomAmount("Ronda", 3000); a.sendRound(); advanceUntilIdle()
        val b = reiniciar(a, drafts)
        try {
            val ronda = drafts.load("v-b2a", "local-1").rounds.keys.single()
            cola(drafts, sinRondas = setOf(ronda))
            assertFalse(b.preparePagar())
            assertEquals("La cuenta se está actualizando. Intenta en un momento.", b.blockedNotice.value)
            cola(drafts)
            assertTrue(b.preparePagar()); assertEquals(3000, session.current()!!.totalCents)
        } finally { b.viewModelScope.cancel() }
    }

    @Test fun `7b ronda ajena en la cola o cambio sin red no se cobra con lo de este aparato`() = conDisco { drafts ->
        val a = vm(drafts = drafts)
        a.addCustomAmount("Ronda", 3000); a.sendRound(); advanceUntilIdle()
        val b = reiniciar(a, drafts)
        try {
            cola(drafts, extra = listOf(FilaDeLaOrden("sin-borrador", "ADD_ITEMS", "PENDING", null)))
            assertFalse(b.preparePagar())
            assertEquals("No se pudo recuperar el saldo completo de esta cuenta. Conéctate y reabre la cuenta antes de cobrar; tus productos siguen guardados.",
                b.blockedNotice.value)
            cola(drafts, extra = listOf(FilaDeLaOrden("d", "APPLY_DISCOUNT", "PENDING", null)))
            assertFalse(b.preparePagar())
            assertEquals("Esta mesa tiene cambios sin sincronizar: cóbrala cuando se sincronicen.", b.blockedNotice.value)
            assertFalse(session.current()!!.saldoSinRedConocido)
        } finally { b.viewModelScope.cancel() }
    }

    @Test fun `7b mesa que el servidor ya conoce no se cobra con lo de este aparato`() = conDisco { drafts ->
        val a = vm(drafts = drafts)
        a.addCustomAmount("Ronda", 3000); a.sendRound(); advanceUntilIdle()
        drafts.promote("v-b2a", "local-1", "real-1")
        val b = reiniciar(a, drafts)
        try {
            cola(drafts)
            assertFalse(b.preparePagar())
            assertFalse(session.current()!!.saldoSinRedConocido)
        } finally { b.viewModelScope.cancel() }
    }

    @Test fun `7b una ronda que se esta mandando no se cuenta dos veces`() = conDisco { drafts ->
        val a = vm(drafts = drafts)
        a.addCustomAmount("Primera", 3000); a.sendRound(); advanceUntilIdle()
        val b = reiniciar(a, drafts)
        try {
            // Al abrir, la primera ronda todavía no se ve en la cola: el saldo sigue sin conocerse mientras se manda la segunda.
            cola(drafts, sinRondas = drafts.load("v-b2a", "local-1").rounds.keys)
            b.loadCheck(); advanceUntilIdle()
            assertFalse(session.current()!!.saldoSinRedConocido)
            val compuerta = CompletableDeferred<Unit>()
            coEvery { outbox.enqueue(any(), any(), any(), any(), any(), any()) } coAnswers { compuerta.await(); arg<String>(3) }
            var enviada: Boolean? = null
            b.addCustomAmount("Segunda", 2000); b.sendRound { ok, _ -> enviada = ok }; runCurrent()
            // La fila de la segunda ya está en la cola, pero su suma en memoria todavía no: rearmar aquí la contaría dos veces.
            cola(drafts)
            assertFalse(b.preparePagar())
            assertEquals("La cuenta se está actualizando. Intenta en un momento.", b.blockedNotice.value)
            compuerta.complete(Unit); advanceUntilIdle()
            assertEquals(true, enviada)
            assertTrue(b.preparePagar()); assertEquals(5000, session.current()!!.totalCents)
        } finally { b.viewModelScope.cancel() }
    }

    @Test fun `7b el saldo rearmado al abrir se ve sin otro toque`() = conDisco { drafts ->
        val a = vm(drafts = drafts)
        a.addCustomAmount("Ronda", 3000); a.sendRound(); advanceUntilIdle()
        val b = reiniciar(a, drafts)
        val observer = SnapshotStateObserver { it() }
        var visto = -1
        try {
            cola(drafts)
            observer.start()
            observer.observeReads(Unit, { visto = b.payableTotalCents }) { visto = b.payableTotalCents }
            b.loadCheck(); advanceUntilIdle()
            Snapshot.sendApplyNotifications()
            assertEquals(3000, visto)
        } finally { observer.stop(); observer.clear(); b.viewModelScope.cancel() }
    }

    // MARK: - Codex b2a-r1

    @Test fun `7b la apertura que el servidor confirma mientras se lee la cola deja la mesa en gris`() = conDisco { drafts ->
        val a = vm(drafts = drafts)
        a.addCustomAmount("Ronda", 3000); a.sendRound(); advanceUntilIdle()
        val b = reiniciar(a, drafts)
        try {
            cola(drafts)
            val filas = coEvery { outbox.filasSinResolverDeLaOrden(any(), any()) }
            val leidas = drafts.load("v-b2a", "local-1").rounds.keys.map { FilaDeLaOrden(it, "ADD_ITEMS", "PENDING", null) } +
                FilaDeLaOrden("open", "OPEN_TABLE", "PENDING", null)
            // El replay guarda la promoción en disco y la apertura sigue PENDING en la cola mientras esta lectura espera.
            filas coAnswers { drafts.promote("v-b2a", "local-1", "real-1"); leidas }
            assertFalse(b.preparePagar())
            assertFalse(session.current()!!.saldoSinRedConocido)
            assertEquals(TableSession.Mode.ORDERING, session.current()!!.mode)
        } finally { b.viewModelScope.cancel() }
    }

    @Test fun `7b ronda que entra a la cola y falla despues no deja cobrar sin ella`() = conDisco { drafts ->
        val a = vm(drafts = drafts)
        a.addCustomAmount("Primera", 3000); a.sendRound(); advanceUntilIdle()
        val b = reiniciar(a, drafts)
        try {
            cola(drafts)
            b.loadCheck(); advanceUntilIdle()
            assertTrue(session.current()!!.saldoSinRedConocido)
            // La fila entra a la cola y algo después del INSERT falla (p. ej. contar pendientes): el borrador la suelta.
            var llave: String? = null
            coEvery { outbox.enqueue(any(), any(), any(), any(), any(), any()) } answers { llave = arg(3); error("refreshCounts") }
            var enviada: Boolean? = null
            b.addCustomAmount("Segunda", 2000); b.sendRound { ok, _ -> enviada = ok }; advanceUntilIdle()
            assertEquals(false, enviada)
            cola(drafts, extra = listOf(FilaDeLaOrden(llave!!, "ADD_ITEMS", "PENDING", null)))
            b.clearPending()
            assertFalse(b.preparePagar())
            assertEquals("No se pudo recuperar el saldo completo de esta cuenta. Conéctate y reabre la cuenta antes de cobrar; tus productos siguen guardados.",
                b.blockedNotice.value)
        } finally { b.viewModelScope.cancel() }
    }
}
