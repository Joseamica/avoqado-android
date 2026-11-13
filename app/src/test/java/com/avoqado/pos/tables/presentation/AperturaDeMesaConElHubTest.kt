package com.avoqado.pos.tables.presentation

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.lan.Apertura
import com.avoqado.pos.core.data.lan.ContadorDeVidas
import com.avoqado.pos.core.data.lan.FakeDiscovery
import com.avoqado.pos.core.data.lan.LanHubCoordinator
import com.avoqado.pos.core.data.lan.LanHubService
import com.avoqado.pos.core.data.lan.LanPeer
import com.avoqado.pos.core.data.lan.LeaseOutcome
import com.avoqado.pos.core.data.lan.LeaseProtocol
import com.avoqado.pos.core.data.lan.LeaseRequest
import com.avoqado.pos.core.data.lan.LeaseServer
import com.avoqado.pos.core.data.lan.MesasDeEstaCaja
import com.avoqado.pos.core.data.lan.Red
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.tables.data.DiningTable
import com.avoqado.pos.tables.data.OpenCheckSummary
import com.avoqado.pos.tables.data.OpenedOrder
import com.avoqado.pos.tables.data.TableOrder
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Hub D3 (mesa declarada) · la pantalla de mesas es la ÚNICA dueña del ticket de apertura (spec §3.1): lo pide antes de esperar, lo
 * termina en TODA salida y, en el éxito, sólo después de que la sesión de la mesa ya la declara (sin hueco). La hoja que deja de
 * mostrar el intento lo abandona en el acto (Codex plan r1 #4 y r2 R2-3).
 *
 * Montaje: el `vm()` de `PermisoDeMesaAlNoAbrirTest` (rama vieja) con un [LanHubService] de mockk que lleva la cuenta de cada
 * ticket. p04 usa dos coordinadores reales (los de la Task 5) sobre un mismo árbitro real.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AperturaDeMesaConElHubTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())
    private val repository = mockk<TableServiceRepository>(relaxed = true)
    private val hub = mockk<LanHubService>(relaxed = true)
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val session = TableSession()
    private val outbox = mockk<com.avoqado.pos.core.data.sync.SyncOutbox>(relaxed = true)
    private val venue = "FULLTEST-apertura"
    private val mesaB = DiningTable("t-B", "B")
    private val mesaC = DiningTable("t-C", "C")
    private val plano = MutableStateFlow<List<DiningTable>>(emptyList())

    // La cuenta de cada ticket que entrega el Hub.
    private val tickets = mutableListOf<Apertura>()
    private val vivos = mutableSetOf<String>()
    private val llamadasTerminar = mutableMapOf<String, Int>()
    /** La mesa de la sesión en el momento en que el ticket se terminó DE VERDAD (la primera vez). */
    private val mesaAlTerminar = mutableMapOf<String, String?>()

    /** Lo que contesta `esperarApertura` (ticket, alEsperar, sigue). Por omisión, concedida. */
    private var esperar: suspend (Apertura, () -> Unit, () -> Boolean) -> LeaseOutcome = { _, _, _ -> LeaseOutcome.Granted }

    /** Como el Hub real: espera mientras el dueño siga y el ticket viva; si no, isla. */
    private val esperarHastaQueSuelte: suspend (Apertura, () -> Unit, () -> Boolean) -> LeaseOutcome = { a, _, sigue ->
        while (sigue() && a.id in vivos) delay(250)
        LeaseOutcome.NoHub
    }

    private fun vm(lanHub: LanHubService = hub, sesion: TableSession = session): TablesViewModel {
        every { repository.tables } returns plano
        every { repository.isLoading } returns MutableStateFlow(false)
        coEvery { repository.refresh(any()) } returns Result.success(emptyList())
        every { storage.venueId } returns venue
        sesion.sincronizarContexto("actor", venue)
        every { hub.abrirApertura(any(), any(), any()) } answers {
            Apertura("ap-${tickets.size + 1}", firstArg(), secondArg(), thirdArg(), venue, 0L).also { tickets += it; vivos += it.id }
        }
        coEvery { hub.esperarApertura(any(), any(), any()) } coAnswers { esperar(firstArg(), secondArg(), thirdArg()) }
        every { hub.terminarApertura(any()) } answers {
            val a = firstArg<Apertura>()
            llamadasTerminar[a.id] = (llamadasTerminar[a.id] ?: 0) + 1
            if (vivos.remove(a.id)) mesaAlTerminar[a.id] = session.current()?.tableId
        }
        return TablesViewModel(
            repository = repository, planManager = mockk(relaxed = true), tableSession = sesion,
            printerService = mockk(relaxed = true), secureStorage = storage, syncOutbox = outbox, lanHub = lanHub,
            floorPlanRepository = com.avoqado.pos.tables.data.FloorPlanRepository(mockk(relaxed = true), mockk(relaxed = true)),
            roleManager = mockk(relaxed = true), versionSource = mockk<com.avoqado.pos.tables.data.ApiFuenteDeVersiones>(relaxed = true),
            cashDrawerRepository = mockk(relaxed = true),
        )
    }

    /** Se pidió UN ticket y se terminó exactamente una vez. */
    private fun unTicketTerminadoUnaVez() {
        assertEquals(1, tickets.size)
        assertTrue("el ticket ya no vive", vivos.isEmpty())
        assertEquals(1, llamadasTerminar[tickets.single().id])
    }

    // MARK: - p21 (spec-21): el ticket se termina exactamente una vez en cada salida

    @Test fun `p21 openTable falla - el ticket se termina una vez`() = runTest {
        coEvery { repository.openTable(any(), any(), any()) } returns Result.failure(IllegalStateException("La mesa ya tiene una cuenta"))
        val subject = vm()
        try {
            subject.openTableAndStartOrdering(mesaB, 2) {}; advanceUntilIdle()
            assertTrue(subject.actionState.value is TableActionState.Error)
            unTicketTerminadoUnaVez()
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `p21 sin red no se pudo guardar la mesa en el aparato - el ticket se termina una vez`() = runTest {
        coEvery { repository.openTable(any(), any(), any()) } returns Result.failure(java.io.IOException("sin red"))
        coEvery { outbox.enqueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            java.io.IOException("disco lleno")
        val subject = vm()
        try {
            subject.openTableAndStartOrdering(mesaB, 2) {}; advanceUntilIdle()
            assertNull(session.current())
            assertEquals(
                TableActionState.Error("No se pudo guardar la mesa en este aparato. Libera espacio e inténtalo de nuevo."),
                subject.actionState.value,
            )
            unTicketTerminadoUnaVez()
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `p21 otra cuenta se activa mientras se abria - startIfCurrent no la pisa y el ticket se termina una vez`() = runTest {
        val respuesta = CompletableDeferred<Result<OpenedOrder>>()
        coEvery { repository.openTable(any(), any(), any()) } coAnswers { respuesta.await() }
        val subject = vm()
        var navego = 0
        try {
            subject.openTableAndStartOrdering(mesaB, 2) { navego++ }; runCurrent()
            val a = TableSession.Active("t-A", "A", null, "o-A", "A", 1, 8000, TableSession.Mode.PAYING)
            session.start(a, venue)
            respuesta.complete(Result.success(OpenedOrder("o-B", "B"))); advanceUntilIdle()
            assertEquals(a, session.current())
            assertEquals(0, navego)
            unTicketTerminadoUnaVez()
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `p21 Taken - no se abre y el ticket se termina una vez`() = runTest {
        esperar = { _, _, _ -> LeaseOutcome.Taken("Ana") }
        val subject = vm()
        try {
            subject.openTableAndStartOrdering(mesaB, 2) {}; advanceUntilIdle()
            coVerify(exactly = 0) { repository.openTable(any(), any(), any()) }
            unTicketTerminadoUnaVez()
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `p21 NoHub - abre como isla y el ticket se termina una vez`() = runTest {
        esperar = { _, _, _ -> LeaseOutcome.NoHub }
        coEvery { repository.openTable(any(), any(), any()) } returns Result.success(OpenedOrder("o-B", "B"))
        val subject = vm()
        try {
            subject.openTableAndStartOrdering(mesaB, 2) {}; advanceUntilIdle()
            assertEquals("t-B", session.current()?.tableId)
            unTicketTerminadoUnaVez()
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `p21 esperarApertura truena - ante la duda isla, sin tumbar la app, y el ticket se termina una vez`() = runTest {
        esperar = { _, _, _ -> throw IllegalStateException("el hub tronó") }
        coEvery { repository.openTable(any(), any(), any()) } returns Result.success(OpenedOrder("o-B", "B"))
        val subject = vm()
        try {
            subject.openTableAndStartOrdering(mesaB, 2) {}; advanceUntilIdle()
            assertEquals("el hub nunca frena la venta", "t-B", session.current()?.tableId)
            unTicketTerminadoUnaVez()
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `p21 se cancela el viewModelScope mientras espera al Hub - el ticket se termina una vez`() = runTest {
        esperar = esperarHastaQueSuelte
        val subject = vm()
        subject.openTableAndStartOrdering(mesaB, 2) {}; runCurrent()
        assertEquals(1, vivos.size)
        subject.viewModelScope.cancel(); runCurrent()
        unTicketTerminadoUnaVez()
    }

    @Test fun `p21 se cancela el viewModelScope con openTable en vuelo - el ticket se termina una vez`() = runTest {
        coEvery { repository.openTable(any(), any(), any()) } coAnswers { CompletableDeferred<Result<OpenedOrder>>().await() }
        val subject = vm()
        subject.openTableAndStartOrdering(mesaB, 2) {}; runCurrent()
        coVerify(exactly = 1) { repository.openTable(any(), any(), any()) }
        subject.viewModelScope.cancel(); runCurrent()
        unTicketTerminadoUnaVez()
    }

    @Test fun `p21 cambia el usuario mientras espera - no abre y el ticket se termina una vez`() = runTest {
        esperar = esperarHastaQueSuelte
        val subject = vm()
        try {
            subject.openTableAndStartOrdering(mesaB, 2) {}; runCurrent()
            session.sincronizarContexto("otro", venue)
            advanceUntilIdle()
            coVerify(exactly = 0) { repository.openTable(any(), any(), any()) }
            unTicketTerminadoUnaVez()
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `p21 cambia el usuario con openTable en vuelo - no activa sesion y el ticket se termina una vez`() = runTest {
        val respuesta = CompletableDeferred<Result<OpenedOrder>>()
        coEvery { repository.openTable(any(), any(), any()) } coAnswers { respuesta.await() }
        val subject = vm()
        try {
            subject.openTableAndStartOrdering(mesaB, 2) {}; runCurrent()
            session.sincronizarContexto("otro", venue)
            respuesta.complete(Result.success(OpenedOrder("o-B", "B"))); advanceUntilIdle()
            assertNull(session.current())
            unTicketTerminadoUnaVez()
        } finally { subject.viewModelScope.cancel() }
    }

    // MARK: - p21b: en el éxito, la sesión ya declara la mesa cuando el ticket se termina

    @Test fun `p21b en el exito la sesion de la mesa arranca ANTES de terminar el ticket`() = runTest {
        coEvery { repository.openTable(any(), any(), any()) } returns Result.success(OpenedOrder("o-B", "B"))
        val subject = vm()
        var navego = 0
        try {
            subject.openTableAndStartOrdering(mesaB, 2) { navego++ }; advanceUntilIdle()
            unTicketTerminadoUnaVez()
            assertEquals("al terminar el ticket la sesión ya declaraba t-B", "t-B", mesaAlTerminar[tickets.single().id])
            assertEquals(1, navego)
            assertEquals(TableActionState.Idle, subject.actionState.value)
        } finally { subject.viewModelScope.cancel() }
    }

    // MARK: - p20 (spec-20): cerrar la hoja mientras espera al Hub

    @Test fun `p20 cerrar la hoja mientras espera al Hub - el ticket se termina, no se abre y queda Idle`() = runTest {
        esperar = esperarHastaQueSuelte
        val subject = vm()
        try {
            subject.selectTable(mesaB.id)
            subject.openTableAndStartOrdering(mesaB, 2) {}; runCurrent()
            assertEquals(TableActionState.Working, subject.actionState.value)

            subject.selectTable(null)
            assertTrue("se terminó en el acto", vivos.isEmpty())
            assertEquals(TableActionState.Idle, subject.actionState.value)

            advanceUntilIdle()
            coVerify(exactly = 0) { repository.openTable(any(), any(), any()) }
            assertEquals(TableActionState.Idle, subject.actionState.value)
            assertEquals(1, tickets.size)
            assertNull(mesaAlTerminar[tickets.single().id])
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `p20 cerrar la hoja mientras dice Revisando - deja de decirlo en el acto`() = runTest {
        esperar = { a, alEsperar, sigue -> alEsperar(); while (sigue() && a.id in vivos) delay(250); LeaseOutcome.NoHub }
        val subject = vm()
        try {
            subject.selectTable(mesaB.id)
            subject.openTableAndStartOrdering(mesaB, 2) {}; runCurrent()
            assertTrue(subject.revisandoConCajas.value)

            subject.selectTable(null)
            assertEquals(false, subject.revisandoConCajas.value)
            assertTrue(vivos.isEmpty())
        } finally { subject.viewModelScope.cancel() }
    }

    // MARK: - Intento comprometido: ya activa su sesión (o escribe sin red): cerrar la hoja ya no lo abandona

    @Test fun `comprometida sin red - cerrar la hoja durante la escritura no la abandona - abre, navega y el ticket termina despues`() = runTest {
        coEvery { repository.openTable(any(), any(), any()) } returns Result.failure(java.io.IOException("sin red"))
        val escritura = CompletableDeferred<String>()
        coEvery { outbox.enqueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            escritura.await()
        }
        val subject = vm()
        var navego = 0
        try {
            subject.selectTable(mesaB.id)
            subject.openTableAndStartOrdering(mesaB, 2) { navego++ }; runCurrent()
            coVerify(exactly = 1) { outbox.enqueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }

            subject.selectTable(null)
            assertEquals("la mesa sigue declarada por su ticket mientras se escribe", 1, vivos.size)

            escritura.complete("intent-B"); advanceUntilIdle()
            assertEquals("t-B", session.current()?.tableId)
            assertTrue(session.current()?.isProvisional == true)
            assertEquals(1, navego)
            coVerify(exactly = 1) { repository.markTableOccupiedLocally("t-B", 2, venue) }
            unTicketTerminadoUnaVez()
            assertEquals("terminó con la sesión ya declarando t-B", "t-B", mesaAlTerminar[tickets.single().id])
            assertEquals(TableActionState.Idle, subject.actionState.value)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `comprometida con red - cerrar la hoja con el refresco en vuelo tras startIfCurrent no la abandona - navega y el ticket termina una vez`() = runTest {
        coEvery { repository.openTable(any(), any(), any()) } returns Result.success(OpenedOrder("o-B", "B"))
        val subject = vm()
        val refresco = CompletableDeferred<Result<List<DiningTable>>>()
        coEvery { repository.refresh(any()) } coAnswers { refresco.await() }
        var navego = 0
        try {
            subject.selectTable(mesaB.id)
            subject.openTableAndStartOrdering(mesaB, 2) { navego++ }; runCurrent()
            assertEquals("startIfCurrent ya corrió", "t-B", session.current()?.tableId)

            subject.selectTable(null)
            assertEquals(1, vivos.size)

            refresco.complete(Result.success(emptyList())); advanceUntilIdle()
            assertEquals("t-B", session.current()?.tableId)
            assertEquals(1, navego)
            unTicketTerminadoUnaVez()
            assertEquals("t-B", mesaAlTerminar[tickets.single().id])
            assertEquals(TableActionState.Idle, subject.actionState.value)
        } finally { subject.viewModelScope.cancel() }
    }

    // MARK: - p20b (Codex plan r1 #4): cerrar la hoja después de Granted, con openTable retenido

    @Test fun `p20b cerrar la hoja con openTable retenido termina el ticket en ese momento y el exito tardio no abre`() = runTest {
        val respuesta = CompletableDeferred<Result<OpenedOrder>>()
        coEvery { repository.openTable(any(), any(), any()) } coAnswers { respuesta.await() }
        val subject = vm()
        var navego = 0
        try {
            subject.selectTable(mesaB.id)
            subject.openTableAndStartOrdering(mesaB, 2) { navego++ }; runCurrent()
            coVerify(exactly = 1) { repository.openTable(any(), any(), any()) }

            subject.selectTable(null)
            assertTrue("terminado sin liberar la respuesta", vivos.isEmpty())
            assertEquals(TableActionState.Idle, subject.actionState.value)

            respuesta.complete(Result.success(OpenedOrder("o-B", "B"))); advanceUntilIdle()
            assertNull(session.current())
            assertEquals(0, navego)
            assertEquals(TableActionState.Idle, subject.actionState.value)
            assertEquals(1, tickets.size)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `p20b cerrar la hoja con openTable retenido y un fallo de red tardio - no se abre sin red`() = runTest {
        val respuesta = CompletableDeferred<Result<OpenedOrder>>()
        coEvery { repository.openTable(any(), any(), any()) } coAnswers { respuesta.await() }
        val subject = vm()
        try {
            subject.selectTable(mesaB.id)
            subject.openTableAndStartOrdering(mesaB, 2) {}; runCurrent()
            subject.selectTable(null)
            assertTrue(vivos.isEmpty())

            respuesta.complete(Result.failure(java.io.IOException("sin red"))); advanceUntilIdle()
            assertNull(session.current())
            coVerify(exactly = 0) { outbox.enqueue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { repository.markTableOccupiedLocally(any(), any(), any()) }
            assertEquals(TableActionState.Idle, subject.actionState.value)
        } finally { subject.viewModelScope.cancel() }
    }

    // MARK: - p20c (Codex plan r2 R2-3): la mesa sale del plano mientras espera

    @Test fun `p20c la mesa sale del plano mientras espera - se abandona, reaparece en Abrir y un intento nuevo no lo toca el viejo`() = runTest {
        plano.value = listOf(mesaB, mesaC)
        val vueltaVieja = CompletableDeferred<LeaseOutcome>()
        var llamadas = 0
        esperar = { _, _, _ -> if (++llamadas == 1) vueltaVieja.await() else LeaseOutcome.Granted }
        val respuestaNueva = CompletableDeferred<Result<OpenedOrder>>()
        coEvery { repository.openTable(any(), any(), any()) } coAnswers { respuestaNueva.await() }
        val subject = vm()
        try {
            subject.selectTable(mesaB.id)
            subject.openTableAndStartOrdering(mesaB, 2) {}; runCurrent()
            val viejo = tickets.single()

            plano.value = listOf(mesaC); runCurrent()
            assertTrue("se terminó en el acto, con la vuelta del Hub todavía retenida", viejo.id !in vivos && vueltaVieja.isActive)
            assertEquals(TableActionState.Idle, subject.actionState.value)

            plano.value = listOf(mesaB, mesaC); runCurrent()
            assertEquals(mesaB.id, subject.selectedTableId.value)
            assertEquals("Abrir mesa y ordenar", textoAbrirMesa(subject.actionState.value, subject.revisandoConCajas.value))

            subject.openTableAndStartOrdering(mesaB, 2) {}; runCurrent()
            assertEquals(2, tickets.size)
            val nuevo = tickets.last()
            assertTrue(nuevo.id in vivos)

            vueltaVieja.complete(LeaseOutcome.Granted); runCurrent()
            assertTrue("el nuevo sigue vivo", nuevo.id in vivos)
            assertTrue("el viejo se terminó (de verdad, una sola vez: en el abandono)", viejo.id in mesaAlTerminar && viejo.id !in vivos)
            coVerify(exactly = 1) { repository.openTable(any(), any(), any()) } // sólo el nuevo: el Granted tardío del viejo no abre
            assertEquals(TableActionState.Working, subject.actionState.value)

            respuestaNueva.complete(Result.success(OpenedOrder("o-B", "B"))); advanceUntilIdle()
            assertEquals("t-B", session.current()?.tableId)
            assertTrue(vivos.isEmpty())
            assertEquals("t-B", mesaAlTerminar[nuevo.id])
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `p20c una lista vacia es cargando - no abandona`() = runTest {
        plano.value = listOf(mesaB, mesaC)
        esperar = esperarHastaQueSuelte
        val subject = vm()
        try {
            subject.selectTable(mesaB.id)
            subject.openTableAndStartOrdering(mesaB, 2) {}; runCurrent()

            plano.value = emptyList(); runCurrent()
            assertEquals(1, vivos.size)
            assertEquals(TableActionState.Working, subject.actionState.value)
        } finally { subject.viewModelScope.cancel() }
    }

    // MARK: - Lo que ve el mesero

    @Test fun `p_taken - Taken muestra quien la atiende`() = runTest {
        esperar = { _, _, _ -> LeaseOutcome.Taken("Ana") }
        val subject = vm()
        try {
            subject.openTableAndStartOrdering(mesaB, 2) {}; advanceUntilIdle()
            assertEquals(TableActionState.Error("Ana está atendiendo esta mesa en otro dispositivo"), subject.actionState.value)
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `p_revisando - mientras el arbitro pide esperar el boton dice Revisando con las otras cajas`() = runTest {
        val vuelta = CompletableDeferred<LeaseOutcome>()
        esperar = { _, alEsperar, _ -> alEsperar(); vuelta.await() }
        val respuesta = CompletableDeferred<Result<OpenedOrder>>()
        coEvery { repository.openTable(any(), any(), any()) } coAnswers { respuesta.await() }
        val subject = vm()
        try {
            subject.openTableAndStartOrdering(mesaB, 2) {}; runCurrent()
            assertTrue(subject.revisandoConCajas.value)
            assertEquals("Revisando con las otras cajas…", textoAbrirMesa(subject.actionState.value, subject.revisandoConCajas.value))

            vuelta.complete(LeaseOutcome.Granted); runCurrent()
            assertEquals("ya contestó el Hub: ahora abre", "Abriendo...", textoAbrirMesa(subject.actionState.value, subject.revisandoConCajas.value))

            respuesta.complete(Result.success(OpenedOrder("o-B", "B"))); advanceUntilIdle()
            assertEquals(false, subject.revisandoConCajas.value)
            unTicketTerminadoUnaVez()
        } finally { subject.viewModelScope.cancel() }
    }

    // MARK: - p04 (spec-4, el límite): A vuelve a m13 (ocupada) mientras su «ninguna» viaja y C la pide como libre

    private class Caja(val sesion: TableSession, val mesas: MesasDeEstaCaja, val red: Red, val coordinador: LanHubCoordinator)

    private val arbitroX = LanPeer("X", "10.0.0.9", 9000, isWired = true, presencia = true)
    private val m13Ocupada = DiningTable("m13", "13", status = "OCCUPIED", currentOrder = TableOrder("o-13", "13"),
        openOrders = listOf(OpenCheckSummary("o-13")))
    private val m13Libre = DiningTable("m13", "13")

    /** Una caja de verdad (sesión, lo que declara y su coordinador) que le habla al árbitro [x] por su propia red. */
    private fun TestScope.caja(id: String, nombre: String, ip: String, x: LeaseServer): Caja {
        val sesion = TableSession().also { it.sincronizarContexto("actor", venue) }
        val mesas = MesasDeEstaCaja({ sesion.current()?.tableId }, sesion.active.map { it?.tableId }, ContadorDeVidas({ 0L }, { true })) {
            testScheduler.currentTime
        }
        val red = Red(mapOf("X" to x)) { testScheduler.currentTime }
        val coordinador = LanHubCoordinator(
            discovery = FakeDiscovery(listOf(LanPeer(id, ip, 9000, presencia = true), arbitroX)),
            mesas = mesas, deviceId = id, venueId = venue, nombre = { nombre }, client = red.client,
            relojMs = { testScheduler.currentTime }, scope = backgroundScope,
        )
        return Caja(sesion, mesas, red, coordinador)
    }

    /** El Hub de la app de [caja], sobre SU objeto del proceso (como `LanHubService` con su coordinador vigente). */
    private fun hubDe(caja: Caja) = mockk<LanHubService>(relaxed = true).also { h ->
        every { h.abrirApertura(any(), any(), any()) } answers { caja.mesas.abrirApertura(firstArg(), secondArg(), thirdArg(), venue) }
        coEvery { h.esperarApertura(any(), any(), any()) } coAnswers { caja.mesas.esperar(firstArg(), secondArg(), thirdArg()) }
        every { h.terminarApertura(any()) } answers { caja.mesas.terminarApertura(firstArg()) }
    }

    private fun LeaseServer.ocupadas() =
        respondTo(LeaseProtocol.encode(LeaseRequest(op = LeaseProtocol.OP_LIST))).leases.orEmpty().map { it.tableId }.toSet()

    private fun TestScope.arbitro() = LeaseServer(deviceId = "X", monoMillis = { testScheduler.currentTime }).also { it.asumirArbitraje(emptySet()) }

    @Test fun `p04 C llega antes que la ninguna de A - C recibe Taken y al salir nada queda huerfano`() = runTest {
        val x = arbitro()
        val a = caja("A", "Ana", "10.0.0.1", x)
        val c = caja("C", "Caro", "10.0.0.3", x)
        coEvery { repository.openTable(venue, "m13", any()) } returns Result.success(OpenedOrder("o-C13", "13"))
        val vmA = vm(sesion = a.sesion)
        val vmC = vm(hubDe(c), c.sesion)
        try {
            a.coordinador.start(); c.coordinador.start(); runCurrent()
            vmA.startOrdering(m13Ocupada) {}; runCurrent()
            assertEquals(setOf("m13"), x.ocupadas())

            a.red.retener = Red.Retener.IDA
            vmA.exitTableMode(); runCurrent() // sale con la flecha: su «ninguna» va en camino
            assertEquals(emptyList<String>(), a.red.retenidos.single().pedido.mesas)
            vmA.startOrdering(m13Ocupada) {}; runCurrent() // vuelve a entrar sin esperar al Hub
            assertEquals("m13", a.sesion.current()?.tableId)

            vmC.openTableAndStartOrdering(m13Libre, 2) {}; runCurrent()
            assertEquals(TableActionState.Error("Ana está atendiendo esta mesa en otro dispositivo"), vmC.actionState.value)
            assertNull(c.sesion.current())

            a.red.retener = Red.Retener.NADA; a.red.soltarTodo(); runCurrent()
            assertEquals("A sigue declarando m13", listOf("m13"), a.red.recibidos.last().mesas)
            assertEquals(setOf("m13"), x.ocupadas())

            vmA.exitTableMode(); runCurrent()
            assertEquals("nada huérfano", emptySet<String>(), x.ocupadas())
            assertTrue(c.mesas.declaracion(venue).aperturas.isEmpty())
        } finally {
            vmA.viewModelScope.cancel(); vmC.viewModelScope.cancel(); a.coordinador.stop(); c.coordinador.stop()
        }
    }

    @Test fun `p04 la ninguna de A llega antes que C - C abre, A declara m13 (dos cuentas, el limite) y al salir nada queda huerfano`() = runTest {
        val x = arbitro()
        val a = caja("A", "Ana", "10.0.0.1", x)
        val c = caja("C", "Caro", "10.0.0.3", x)
        coEvery { repository.openTable(venue, "m13", any()) } returns Result.success(OpenedOrder("o-C13", "13"))
        val vmA = vm(sesion = a.sesion)
        val vmC = vm(hubDe(c), c.sesion)
        try {
            a.coordinador.start(); c.coordinador.start(); runCurrent()
            vmA.startOrdering(m13Ocupada) {}; runCurrent()

            a.red.retener = Red.Retener.IDA
            vmA.exitTableMode(); runCurrent()
            vmA.startOrdering(m13Ocupada) {}; runCurrent()
            a.red.soltar(a.red.retenidos.single()); runCurrent() // llega su «ninguna»; su m13 sale detrás y queda en camino
            assertEquals(listOf("m13"), a.red.retenidos.single().pedido.mesas)
            assertEquals(emptySet<String>(), x.ocupadas())

            vmC.openTableAndStartOrdering(m13Libre, 2) {}; runCurrent()
            assertEquals("C la abrió", "m13", c.sesion.current()?.tableId)
            assertTrue(c.mesas.declaracion(venue).aperturas.isEmpty())

            a.red.retener = Red.Retener.NADA; a.red.soltarTodo(); runCurrent()
            assertEquals("A sigue dentro: dos cuentas en m13, el límite declarado", "m13", a.sesion.current()?.tableId)
            assertEquals(listOf("m13"), a.red.recibidos.last().mesas)
            assertEquals(setOf("m13"), x.ocupadas())

            vmA.exitTableMode(); vmC.exitTableMode(); runCurrent()
            assertEquals("nada huérfano", emptySet<String>(), x.ocupadas())
        } finally {
            vmA.viewModelScope.cancel(); vmC.viewModelScope.cancel(); a.coordinador.stop(); c.coordinador.stop()
        }
    }
}
