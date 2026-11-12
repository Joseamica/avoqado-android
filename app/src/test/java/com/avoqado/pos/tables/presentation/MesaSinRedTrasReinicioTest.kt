package com.avoqado.pos.tables.presentation

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * D5 · B2a: una mesa abierta SIN internet se cobra con el número de disco (borrador ↔ cola), se reinicie o no la app (7b), y una
 * mesa ya cobrada sin internet no se vuelve a cobrar (9b, `hz:e5ee98bc:D13`). VM, sesión y borrador reales; la cola es una lista
 * que se llena con lo que la app encola, como la lee `SyncOutbox`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MesaSinRedTrasReinicioTest {
    @get:Rule val main = MainDispatcherRule()
    private val repository = mockk<TableServiceRepository>(relaxed = true)
    private val outbox = mockk<SyncOutbox>(relaxed = true)
    private val session = TableSession()
    private val acks = MutableSharedFlow<SyncAck>(extraBufferCapacity = 4)
    private val storage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns "v-b2a" }
    private val root = java.nio.file.Files.createTempDirectory("b2a").toFile()
    private val drafts = TableRoundDraftStore(root)
    /** La cola de esta tablet. La mesa se abrió sin internet: su `OPEN_TABLE` ya está. */
    private val cola = mutableListOf(FilaDeLaOrden("open", "OPEN_TABLE", "PENDING", null))

    @After fun limpiar() { root.deleteRecursively() }

    private val cuentaPagada = "Esta cuenta ya está pagada: falta subirla. Se sube sola cuando vuelva la conexión; no la cobres otra vez."
    private val seActualiza = "La cuenta se está actualizando. Intenta en un momento."
    private val noRecuperado = "No se pudo recuperar el saldo completo de esta cuenta. Conéctate y reabre la cuenta antes de cobrar; tus productos siguen guardados."

    private fun vm(keepSession: Boolean = false): TableOrderViewModel {
        every { repository.tables } returns MutableStateFlow(emptyList())
        every { repository.ownership } returns MutableStateFlow(TableServiceRepository.TableOwnership())
        every { outbox.acks } returns acks
        every { outbox.pendingCount } returns MutableStateFlow(0)
        every { outbox.observarEstado(any()) } answers { flowOf(cola.firstOrNull { it.id == firstArg<String>() }?.estado) }
        coEvery { outbox.enqueue(any(), any(), any(), any(), any(), any()) } answers {
            arg<String>(3).also { cola += FilaDeLaOrden(it, arg(1), "PENDING", null) }
        }
        coEvery { outbox.filasSinResolverDeLaOrden(any(), any()) } answers { cola.toList() }
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } answers {
            TrabajoSinResolver(cola.size, cola.count { it.estado == "REJECTED" }, cola.map { it.tipo }.toSet(), cola.sumOf { it.importeCents ?: 0 })
        }
        val config = mockk<PrintConfigRepository>(relaxed = true)
        every { config.getCurrentConfig() } returns PrintConfig()
        val connectivity = mockk<ConnectivityMonitor>(relaxed = true)
        every { connectivity.isConnected } returns MutableStateFlow(false)
        if (!keepSession) session.start(TableSession.Active("t1", "5", "Patio", "local-1", "LOCAL", 1, 0, TableSession.Mode.ORDERING,
            isProvisional = true, localOrderId = "local-1"))
        return TableOrderViewModel(repository, session, config, mockk(relaxed = true), mockk<PrinterService>(relaxed = true), storage,
            outbox, mockk(relaxed = true), connectivity, mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), draftStore = drafts)
    }

    /** El cobro en efectivo lo guarda el flujo de pago (B0): aquí sólo su fila en la cola. */
    private fun cobrado(cents: Int) { cola += FilaDeLaOrden("cobro-${cola.size}", "PAY_CASH", "PENDING", cents) }

    /** «Matar la app»: la sesión vive sólo en memoria; el borrador y la cola siguen en disco. Se reabre desde el plano. */
    private fun reiniciar(antes: TableOrderViewModel): TableOrderViewModel {
        antes.viewModelScope.cancel()
        session.clear()
        session.abrirDesdePiso("v-b2a", TableSession.Active("t1", "5", null, "local-1", "LOCAL", 1, 0, TableSession.Mode.ORDERING,
            isProvisional = true))
        return vm(keepSession = true)
    }

    private fun TestScope.ronda(vm: TableOrderViewModel, cents: Int) { vm.addCustomAmount("Ronda", cents); vm.sendRound(); advanceUntilIdle() }

    // MARK: - El camino de hoy (sin reiniciar) sigue igual

    @Test fun `camino de hoy abrir sin red mandar ronda y cobrar`() = runTest {
        val subject = vm()
        try {
            subject.addProductWithModifiers(Product(id = "p", name = "Café", priceValue = 50.0), 2,
                listOf(SelectedModifier("g", "Opciones", "m", "Extra", 500)), null)
            subject.sendRound(); advanceUntilIdle()
            var cobro: Int? = null; prepararPagoDeMesa(subject) { cobro = session.current()!!.totalCents }
            assertEquals(11000, cobro)
            assertEquals(TableSession.Mode.PAYING, session.current()!!.mode)
            assertTrue(session.current()!!.isProvisional)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `Pagar mientras la ronda se guarda dice que se actualiza y el segundo toque cobra`() = runTest {
        val subject = vm()
        try {
            val compuerta = CompletableDeferred<Unit>()
            coEvery { outbox.enqueue(any(), any(), any(), any(), any(), any()) } coAnswers {
                compuerta.await(); arg<String>(3).also { cola += FilaDeLaOrden(it, arg(1), "PENDING", null) }
            }
            subject.addCustomAmount("Ronda", 3000); subject.sendRound(); runCurrent()
            assertFalse(subject.preparePagar())
            assertEquals(seActualiza, subject.blockedNotice.value)
            compuerta.complete(Unit); advanceUntilIdle()
            assertTrue(subject.preparePagar()); assertEquals(3000, session.current()!!.totalCents)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `la memoria no decide el cobro de una mesa provisional`() = runTest {
        val subject = vm()
        try {
            ronda(subject, 3000)
            // Un número de memoria que no sale de disco (más o menos) no se cobra: manda el disco.
            for (memoria in listOf(9999, 0)) {
                session.start(session.current()!!.copy(mode = TableSession.Mode.ORDERING, valorRondasSinRedCents = memoria))
                assertTrue(subject.preparePagar()); assertEquals(3000, session.current()!!.totalCents)
                assertEquals(3000, session.current()!!.valorRondasSinRedCents)
            }
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `Codex r2 marcar resuelta en la cuarentena borra las filas y la mesa ya no se cobra con la memoria`() = runTest {
        val subject = vm()
        try {
            ronda(subject, 3000)
            assertTrue(session.current()!!.saldoSinRedConocido)
            // Al volver la red el SERVIDOR rechazó la apertura y la ronda; el gerente las marcó resueltas: sus filas se borran.
            cola.clear()
            assertFalse(subject.preparePagar())
            assertEquals(noRecuperado, subject.blockedNotice.value)
            assertEquals(TableSession.Mode.ORDERING, session.current()!!.mode)
        } finally { subject.viewModelScope.cancel() }
    }

    // MARK: - 9b y A5: una mesa cobrada aquí no se vuelve a cobrar ni recibe otra ronda

    @Test fun `9b mesa cobrada sin red no ofrece otro cobro en cero`() = runTest {
        val subject = vm()
        try {
            ronda(subject, 5500); cobrado(5500)
            subject.loadCheck(); advanceUntilIdle()
            assertTrue(subject.cuentaCobradaAqui)
            assertFalse(subject.preparePagar())
            assertEquals(TableSession.Mode.ORDERING, session.current()!!.mode)
            assertEquals(cuentaPagada, subject.blockedNotice.value)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `9b cierre de cero ya cobrado no se cobra otra vez`() = runTest {
        val subject = vm()
        try {
            subject.addProductWithModifiers(Product(id = "p", name = "Agua", priceValue = 20.0), 1, emptyList(), null,
                isCortesia = true, cortesiaReason = "Cortesía autorizada")
            subject.sendRound(); advanceUntilIdle()
            assertTrue(subject.preparePagar()); assertEquals(0, session.current()!!.totalCents) // el primer cierre de $0 sí
            session.start(session.current()!!.copy(mode = TableSession.Mode.ORDERING))
            cobrado(0)
            assertFalse(subject.preparePagar())
            assertEquals(cuentaPagada, subject.blockedNotice.value)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `abono parcial sin red deja cobrar el resto`() = runTest {
        val subject = vm()
        try {
            ronda(subject, 10000); cobrado(4000)
            assertTrue(subject.preparePagar()); assertEquals(6000, session.current()!!.totalCents)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `A5 mesa ya cobrada aqui no recibe otra ronda`() = runTest {
        val subject = vm()
        try {
            ronda(subject, 5500); cobrado(5500)
            subject.loadCheck(); advanceUntilIdle()
            val antes = cola.size
            var enviada: Boolean? = null
            subject.addCustomAmount("Postre", 2000); subject.sendRound { ok, _ -> enviada = ok }; advanceUntilIdle()
            assertEquals(false, enviada)
            assertEquals(antes, cola.size)
            assertEquals(cuentaPagada, subject.blockedNotice.value)
        } finally { subject.viewModelScope.cancel() }
    }

    // MARK: - 7b: la app se reinicia sin internet

    @Test fun `7b mesa abierta sin red con una ronda se cobra tras reiniciar la app`() = runTest {
        val a = vm()
        a.addProductWithModifiers(Product(id = "p", name = "Café", priceValue = 50.0), 2,
            listOf(SelectedModifier("g", "Opciones", "m", "Extra", 500)), null)
        a.sendRound(); advanceUntilIdle()
        val b = reiniciar(a)
        try {
            b.loadCheck(); advanceUntilIdle()
            assertTrue(session.current()!!.saldoSinRedConocido)
            assertEquals(11000, b.payableTotalCents)
            var cobro: Int? = null; prepararPagoDeMesa(b) { cobro = session.current()!!.totalCents }
            assertEquals(11000, cobro)
            assertEquals(TableSession.Mode.PAYING, session.current()!!.mode)
            assertTrue(session.current()!!.isProvisional)
        } finally { b.viewModelScope.cancel() }
    }

    @Test fun `7b abono y resto tras reiniciar y despues ya pagada`() = runTest {
        val a = vm()
        ronda(a, 10000)
        val b = reiniciar(a)
        cobrado(4000)
        assertTrue(b.preparePagar()); assertEquals(6000, session.current()!!.totalCents)
        val c = reiniciar(b)
        try {
            cobrado(6000)
            c.loadCheck(); advanceUntilIdle()
            assertTrue(c.cuentaCobradaAqui)
            assertFalse(c.preparePagar())
            assertEquals(cuentaPagada, c.blockedNotice.value)
            assertEquals(TableSession.Mode.ORDERING, session.current()!!.mode)
        } finally { c.viewModelScope.cancel() }
    }

    @Test fun `7b borrador sin fila espera y al volver a encolarse al abrir se cobra`() = runTest {
        val a = vm()
        ronda(a, 3000)
        cola.removeAll { it.tipo == "ADD_ITEMS" } // la app murió entre guardar el borrador y encolar la ronda
        val b = reiniciar(a)
        try {
            assertFalse(b.preparePagar())
            assertEquals(seActualiza, b.blockedNotice.value)
            b.loadCheck(); advanceUntilIdle() // `restoreDraft` la vuelve a encolar con la misma llave
            assertEquals(1, cola.count { it.tipo == "ADD_ITEMS" })
            assertTrue(b.preparePagar()); assertEquals(3000, session.current()!!.totalCents)
        } finally { b.viewModelScope.cancel() }
    }

    @Test fun `7b ronda ajena en la cola o cambio sin red no se cobra con lo de este aparato`() = runTest {
        val a = vm()
        ronda(a, 3000)
        val b = reiniciar(a)
        try {
            cola += FilaDeLaOrden("sin-borrador", "ADD_ITEMS", "PENDING", null)
            assertFalse(b.preparePagar())
            assertEquals(noRecuperado, b.blockedNotice.value)
            cola.removeAll { it.id == "sin-borrador" }
            cola += FilaDeLaOrden("d", "APPLY_DISCOUNT", "PENDING", null)
            assertFalse(b.preparePagar())
            assertEquals("Esta mesa tiene cambios sin sincronizar: cóbrala cuando se sincronicen.", b.blockedNotice.value)
            assertFalse(session.current()!!.saldoSinRedConocido)
        } finally { b.viewModelScope.cancel() }
    }

    @Test fun `7b mesa que el servidor ya conoce no se cobra con lo de este aparato`() = runTest {
        val a = vm()
        ronda(a, 3000)
        drafts.promote("v-b2a", "local-1", "real-1")
        val b = reiniciar(a)
        try {
            assertFalse(b.preparePagar())
            assertFalse(session.current()!!.saldoSinRedConocido)
        } finally { b.viewModelScope.cancel() }
    }

    @Test fun `7b una ronda que se esta mandando no se cuenta dos veces`() = runTest {
        val a = vm()
        ronda(a, 3000)
        val b = reiniciar(a)
        try {
            b.loadCheck(); advanceUntilIdle()
            val compuerta = CompletableDeferred<Unit>()
            coEvery { outbox.enqueue(any(), any(), any(), any(), any(), any()) } coAnswers {
                arg<String>(3).also { cola += FilaDeLaOrden(it, arg(1), "PENDING", null); compuerta.await() }
            }
            var enviada: Boolean? = null
            b.addCustomAmount("Segunda", 2000); b.sendRound { ok, _ -> enviada = ok }; runCurrent()
            // La fila de la segunda ya está en la cola, pero su suma en memoria todavía no.
            assertFalse(b.preparePagar())
            assertEquals(seActualiza, b.blockedNotice.value)
            compuerta.complete(Unit); advanceUntilIdle()
            assertEquals(true, enviada)
            assertTrue(b.preparePagar()); assertEquals(5000, session.current()!!.totalCents)
            assertEquals(5000, session.current()!!.valorRondasSinRedCents)
        } finally { b.viewModelScope.cancel() }
    }

    @Test fun `7b el saldo de disco al abrir se ve sin otro toque`() = runTest {
        val a = vm()
        ronda(a, 3000)
        val b = reiniciar(a)
        val observer = SnapshotStateObserver { it() }
        var visto = -1
        try {
            observer.start()
            observer.observeReads(Unit, { visto = b.payableTotalCents }) { visto = b.payableTotalCents }
            b.loadCheck(); advanceUntilIdle()
            Snapshot.sendApplyNotifications()
            assertEquals(3000, visto)
        } finally { observer.stop(); observer.clear(); b.viewModelScope.cancel() }
    }

    // MARK: - Codex b2a-r1

    @Test fun `7b la apertura que el servidor confirma mientras se lee la cola deja la mesa en gris`() = runTest {
        val a = vm()
        ronda(a, 3000)
        val b = reiniciar(a)
        try {
            // El replay guarda la promoción en disco y la apertura sigue PENDING en la cola mientras esta lectura espera.
            coEvery { outbox.filasSinResolverDeLaOrden(any(), any()) } coAnswers { drafts.promote("v-b2a", "local-1", "real-1"); cola.toList() }
            assertFalse(b.preparePagar())
            assertFalse(session.current()!!.saldoSinRedConocido)
            assertEquals(TableSession.Mode.ORDERING, session.current()!!.mode)
        } finally { b.viewModelScope.cancel() }
    }

    @Test fun `ronda que entra a la cola y falla despues no deja cobrar sin ella`() = runTest {
        val subject = vm()
        try {
            ronda(subject, 3000)
            // La fila entra a la cola y algo después del INSERT falla (p. ej. contar pendientes): el borrador la suelta.
            coEvery { outbox.enqueue(any(), any(), any(), any(), any(), any()) } answers {
                cola += FilaDeLaOrden(arg(3), arg(1), "PENDING", null); error("refreshCounts")
            }
            var enviada: Boolean? = null
            subject.addCustomAmount("Segunda", 2000); subject.sendRound { ok, _ -> enviada = ok }; advanceUntilIdle()
            assertEquals(false, enviada)
            subject.clearPending()
            assertFalse(subject.preparePagar())
            assertEquals(noRecuperado, subject.blockedNotice.value)
        } finally { subject.viewModelScope.cancel() }
    }

    /**
     * D16 (`hz:e5ee98bc:D16`): la ronda entra a la cola y algo DESPUÉS falla; el mesero vuelve a tocar «Enviar» sin cambiar nada.
     * Tiene que quedar UNA ronda con la llave del primer intento (la cola real conserva la fila del mismo id), no dos que el
     * SERVIDOR sumaría. Otra ronda distinta sí estrena su llave.
     */
    @Test fun `D16 reintentar Enviar tras fallar despues de guardar deja una sola ronda con la misma llave`() = runTest {
        val subject = vm()
        try {
            var fallarTrasGuardar = true
            coEvery { outbox.enqueue(any(), any(), any(), any(), any(), any()) } answers {
                val id = arg<String>(3)
                if (cola.none { it.id == id }) cola += FilaDeLaOrden(id, arg(1), "PENDING", null)
                if (fallarTrasGuardar) { fallarTrasGuardar = false; error("refreshCounts") }
                id
            }
            var enviada: Boolean? = null
            subject.addCustomAmount("Ronda", 3000); subject.sendRound { ok, _ -> enviada = ok }; advanceUntilIdle()
            assertEquals("el primer intento dice error", false, enviada)
            val primera = cola.single { it.tipo == "ADD_ITEMS" }.id

            // Codex b1-r1 #1: el segundo intento falla al guardar el borrador (disco) y no llega a la cola; el tercero sigue con la llave.
            root.setWritable(false)
            try { subject.sendRound { ok, _ -> enviada = ok }; advanceUntilIdle() } finally { root.setWritable(true) }
            assertEquals(false, enviada)
            subject.sendRound { ok, _ -> enviada = ok }; advanceUntilIdle()
            assertEquals(true, enviada)
            assertEquals("una sola ronda en la cola, con la llave del primer intento", listOf(primera), cola.filter { it.tipo == "ADD_ITEMS" }.map { it.id })
            assertTrue(subject.preparePagar()); assertEquals("la cuenta cobra la ronda una vez", 3000, session.current()!!.totalCents)

            session.start(session.current()!!.copy(mode = TableSession.Mode.ORDERING))
            ronda(subject, 2000)
            val rondas = cola.filter { it.tipo == "ADD_ITEMS" }.map { it.id }
            assertEquals(2, rondas.size); assertNotEquals(rondas[0], rondas[1])
        } finally { subject.viewModelScope.cancel() }
    }

    // MARK: - Codex b2a-r3

    @Test fun `r3 la apertura que el servidor confirma durante la segunda lectura no se cobra`() = runTest {
        val subject = vm()
        try {
            ronda(subject, 3000)
            // `saldoSinRedDeDisco` ya pasó; mientras `leerTrabajo` espera, el replay guarda la promoción en disco.
            coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } coAnswers {
                drafts.promote("v-b2a", "local-1", "real-1")
                TrabajoSinResolver(cola.size, 0, cola.map { it.tipo }.toSet(), 0)
            }
            assertFalse(subject.preparePagar())
            assertEquals(noRecuperado, subject.blockedNotice.value)
            assertEquals(TableSession.Mode.ORDERING, session.current()!!.mode)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `r3 A5 tras reiniciar una cuenta cobrada no recibe otra ronda antes de terminar la primera lectura`() = runTest {
        val a = vm()
        ronda(a, 5500); cobrado(5500)
        val b = reiniciar(a)
        try {
            val primera = CompletableDeferred<Unit>()
            var lecturas = 0
            coEvery { outbox.filasSinResolverDeLaOrden(any(), any()) } coAnswers { if (lecturas++ == 0) primera.await(); cola.toList() }
            b.loadCheck(); runCurrent()
            assertFalse("La memoria todavía no sabe que está pagada", b.cuentaCobradaAqui)
            val rondasAntes = cola.count { it.tipo == "ADD_ITEMS" }
            var enviada: Boolean? = null
            b.addCustomAmount("Postre", 2000); b.sendRound { ok, _ -> enviada = ok }; advanceUntilIdle()
            assertEquals(false, enviada)
            assertEquals(rondasAntes, cola.count { it.tipo == "ADD_ITEMS" })
            assertEquals(cuentaPagada, b.blockedNotice.value)
            primera.complete(Unit); advanceUntilIdle()
        } finally { b.viewModelScope.cancel() }
    }
}
