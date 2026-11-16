package com.avoqado.pos.tables.presentation

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.lan.CajasRecordadas
import com.avoqado.pos.core.data.lan.LanHubService
import com.avoqado.pos.core.data.lan.LanPeer
import com.avoqado.pos.core.data.lan.LeaseOutcome
import com.avoqado.pos.core.data.lan.TransporteLan
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.tables.data.ApiFuenteDeVersiones
import com.avoqado.pos.tables.data.DiningTable
import com.avoqado.pos.tables.data.FloorPlanRepository
import com.avoqado.pos.tables.data.OpenCheckSummary
import com.avoqado.pos.tables.data.OpenedOrder
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * D6 (hz:e5ee98bc:D6): en el ensayo del 11-nov la D3 perdió a la N86 por el WiFi y entró a una mesa con el mesero adentro sin ningún
 * aviso. Con el Hub, si este aparato ya conoció a otra caja y ahora no ve a ninguna, el plano lo dice en una línea gris; nunca bloquea.
 *
 * 🔴 Sin `advanceUntilIdle()` con el aviso vivo: su tic de 60 s es un `while { delay }` (memoria `runtest-con-timer-infinito`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AvisoDeIslaTest {
    @get:Rule val main = MainDispatcherRule()

    private val yo = LanPeer("yo-d3", "127.0.0.1", 4000)
    private val n86 = LanPeer("n86-aaaa", "192.168.100.152", 4321)

    // MARK: - La regla

    @Test fun `con Hub, una caja recordada y nadie a la vista sale la linea`() {
        assertTrue(AvisoDeIsla.mostrar(hubDelNegocio = true, yo = yo.deviceId, peers = listOf(yo), recordadas = setOf(n86.deviceId)))
        // Con la red local apagada el transporte publica la lista vacía: tampoco ve a nadie.
        assertTrue(AvisoDeIsla.mostrar(true, yo.deviceId, emptyList(), setOf(n86.deviceId)))
    }

    @Test fun `otra caja a la vista quita la linea, la vea NSD o siga por TCP`() {
        assertFalse(AvisoDeIsla.mostrar(true, yo.deviceId, listOf(yo, n86), setOf(n86.deviceId)))
        // Otra caja que no estaba recordada todavía (recién encendida) también cuenta.
        assertFalse(AvisoDeIsla.mostrar(true, yo.deviceId, listOf(yo, LanPeer("nueva", "192.168.100.9", 1)), setOf(n86.deviceId)))
    }

    @Test fun `sin Hub en el negocio no sale`() {
        assertFalse(AvisoDeIsla.mostrar(hubDelNegocio = false, yo = yo.deviceId, peers = listOf(yo), recordadas = setOf(n86.deviceId)))
    }

    @Test fun `sin otra caja recordada no sale - una sola caja en el negocio`() {
        assertFalse(AvisoDeIsla.mostrar(true, yo.deviceId, listOf(yo), emptySet()))
        assertFalse(AvisoDeIsla.mostrar(true, yo.deviceId, listOf(yo), setOf(yo.deviceId)))
    }

    @Test fun `un aparato a la vista que no coordina mesas no cuenta - hub=0`() {
        val cocinaSinHub = LanPeer("kds-1", "192.168.100.30", 5000, sirveLeases = false)
        // La N86 se perdió; la pantalla de cocina sigue a la vista pero no coordina mesas: sale la línea.
        assertTrue(AvisoDeIsla.mostrar(true, yo.deviceId, listOf(yo, cocinaSinHub), setOf(n86.deviceId, cocinaSinHub.deviceId)))
    }

    @Test fun `Codex r1 1 - una pantalla de cocina a la vista no es una caja que falte`() {
        // Caja única + pantalla de cocina con app vieja (hub=0): el D12 la recuerda, pero está a la vista. No falta nadie.
        val cocinaSinHub = LanPeer("kds-1", "192.168.100.30", 5000, sirveLeases = false)
        assertFalse(AvisoDeIsla.mostrar(true, yo.deviceId, listOf(yo, cocinaSinHub), setOf(cocinaSinHub.deviceId)))
    }

    // MARK: - Lo recordado (el disco del D12, sólo lectura)

    private val ahora = 1_800_000_000_000L
    private fun disco(vararg cajas: Pair<String, CajasRecordadas.Recordada>) =
        PayloadCache.Cached(Json.encodeToString(CajasRecordadas.Disco.serializer(), CajasRecordadas.Disco(cajas.toMap())), 0)

    @Test fun `lee las recordadas de esta sucursal sin este aparato ni las de 24 h o mas`() = runTest {
        val cache = mockk<PayloadCache> {
            coEvery { loadDurable(PayloadCache.TYPE_CAJAS_RECORDADAS, "v1") } returns disco(
                "n86-aaaa" to CajasRecordadas.Recordada("192.168.100.152", 4321, "v1", ahora - 60_000),
                "yo-d3" to CajasRecordadas.Recordada("127.0.0.1", 4000, "v1", ahora),
                "de-otro-negocio" to CajasRecordadas.Recordada("192.168.100.7", 1, "v2", ahora),
                "vieja" to CajasRecordadas.Recordada("192.168.100.8", 1, "v1", ahora - CajasRecordadas.OLVIDAR_MS),
            )
        }
        assertEquals(setOf("n86-aaaa"), AvisoDeIsla.recordadas(cache, "v1", "yo-d3", ahora))
    }

    @Test fun `si el disco no se puede leer o no hay nada no sale la linea`() = runTest {
        val falla = mockk<PayloadCache> { coEvery { loadDurable(any(), any()) } throws java.io.IOException("Room no contestó") }
        assertEquals(emptySet<String>(), AvisoDeIsla.recordadas(falla, "v1", "yo-d3", ahora))
        val vacio = mockk<PayloadCache> { coEvery { loadDurable(any(), any()) } returns null }
        assertEquals(emptySet<String>(), AvisoDeIsla.recordadas(vacio, "v1", "yo-d3", ahora))
        val roto = mockk<PayloadCache> { coEvery { loadDurable(any(), any()) } returns PayloadCache.Cached("{no es json", 0) }
        assertEquals(emptySet<String>(), AvisoDeIsla.recordadas(roto, "v1", "yo-d3", ahora))
    }

    // MARK: - En el tiempo

    @Test fun `la linea espera 10 s seguidos para salir y se quita al instante`() = runTest {
        val peers = MutableStateFlow(listOf(yo, n86))
        val visto = mutableListOf<Boolean>()
        backgroundScope.launch { AvisoDeIsla.flujo(peers, { yo.deviceId }, { true }, { setOf(n86.deviceId) }).collect { visto += it } }
        runCurrent()
        assertEquals(listOf(false), visto)

        peers.value = listOf(yo) // la D3 pierde a la N86
        runCurrent()
        advanceTimeBy(AvisoDeIsla.GRACIA_MS - 1); runCurrent()
        assertFalse("antes de 10 s no sale", visto.last())
        advanceTimeBy(2); runCurrent()
        assertTrue("a los 10 s sale", visto.last())

        peers.value = listOf(yo, n86) // vuelve a verla
        runCurrent()
        assertFalse("se quita sin esperar", visto.last())
    }

    @Test fun `si la caja vuelve antes de 10 s la linea nunca sale - arranque o un bache`() = runTest {
        val peers = MutableStateFlow(listOf(yo))
        val visto = mutableListOf<Boolean>()
        backgroundScope.launch { AvisoDeIsla.flujo(peers, { yo.deviceId }, { true }, { setOf(n86.deviceId) }).collect { visto += it } }
        runCurrent()
        advanceTimeBy(5_000); runCurrent()
        peers.value = listOf(yo, n86)
        runCurrent()
        advanceTimeBy(AvisoDeIsla.GRACIA_MS * 2); runCurrent()
        assertFalse(visto.any { it })
    }

    @Test fun `cada minuto vuelve a revisar el plan y el disco - el Hub se apaga o la caja se olvida`() = runTest {
        val peers = MutableStateFlow(listOf(yo))
        var hub = true
        val visto = mutableListOf<Boolean>()
        backgroundScope.launch { AvisoDeIsla.flujo(peers, { yo.deviceId }, { hub }, { setOf(n86.deviceId) }).collect { visto += it } }
        advanceTimeBy(AvisoDeIsla.GRACIA_MS + 1); runCurrent()
        assertTrue(visto.last())
        hub = false
        advanceTimeBy(AvisoDeIsla.REVISION_MS); runCurrent()
        assertFalse(visto.last())
    }

    @Test fun `Codex r1 4 - la revision del minuto no reinicia los 10 s de una ausencia que ya corria`() = runTest {
        val peers = MutableStateFlow(listOf(yo, n86))
        val visto = mutableListOf<Boolean>()
        backgroundScope.launch { AvisoDeIsla.flujo(peers, { yo.deviceId }, { true }, { setOf(n86.deviceId) }).collect { visto += it } }
        runCurrent()
        advanceTimeBy(51_000); runCurrent()
        peers.value = listOf(yo) // se pierde en el segundo 51; la revisión llega en el 60
        runCurrent()
        advanceTimeBy(AvisoDeIsla.GRACIA_MS - 1); runCurrent()
        assertFalse(visto.last())
        advanceTimeBy(2); runCurrent()
        assertTrue("sale a los 10 s de perderla (61 s), no a los 70", visto.last())
        // Un cambio de la lista que no cambia la condición (la red local se apaga: lista vacía) tampoco la quita ni la reinicia.
        peers.value = emptyList()
        runCurrent()
        assertTrue(visto.last())
    }

    // MARK: - Nunca bloquea

    private fun vmConLaN86Perdida(
        mesas: List<DiningTable>,
        session: TableSession,
        peers: MutableStateFlow<List<LanPeer>>,
        repository: TableServiceRepository = mockk(relaxed = true),
        hub: LanHubService = mockk(relaxed = true),
    ): TablesViewModel {
        every { repository.tables } returns MutableStateFlow(mesas)
        every { repository.isLoading } returns MutableStateFlow(false)
        val storage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns "v1" }
        val outbox = mockk<SyncOutbox>(relaxed = true)
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(0, 0, emptySet(), 0)
        val plan = mockk<PlanManager>(relaxed = true).also { every { it.hasFeature(any()) } returns true }
        val transporte = mockk<TransporteLan>().also {
            every { it.peers } returns peers
            every { it.deviceId } returns yo.deviceId
        }
        val cache = mockk<PayloadCache> {
            coEvery { loadDurable(PayloadCache.TYPE_CAJAS_RECORDADAS, "v1") } returns
                disco("n86-aaaa" to CajasRecordadas.Recordada("192.168.100.152", 4321, "v1", System.currentTimeMillis()))
        }
        return TablesViewModel(
            repository, plan, session, mockk(relaxed = true), storage, outbox, hub,
            FloorPlanRepository(mockk(relaxed = true), mockk(relaxed = true)), mockk(relaxed = true), mockk<ApiFuenteDeVersiones>(relaxed = true),
            mockk(relaxed = true), null, transporte, cache,
        )
    }

    /** Deja la línea puesta (la N86 perdida, 10 s) mientras corre [bloque]. */
    private suspend fun kotlinx.coroutines.test.TestScope.conLaLineaPuesta(vm: TablesViewModel, bloque: () -> Unit) {
        val mira = backgroundScope.launch { vm.avisoDeIsla.collect {} }
        runCurrent()
        advanceTimeBy(AvisoDeIsla.GRACIA_MS + 1); runCurrent()
        assertTrue("la línea está puesta", vm.avisoDeIsla.value)
        bloque()
        runCurrent()
        assertTrue("y sigue puesta", vm.avisoDeIsla.value)
        mira.cancel()
    }

    @Test fun `Codex r1 5 - al volver al plano no reaparece una linea vieja`() = runTest {
        val table = DiningTable("t1", "5")
        val peers = MutableStateFlow(listOf(yo))
        val vm = vmConLaN86Perdida(listOf(table), TableSession(), peers)
        try {
            val mira = backgroundScope.launch { vm.avisoDeIsla.collect {} }
            runCurrent()
            advanceTimeBy(AvisoDeIsla.GRACIA_MS + 1); runCurrent()
            assertTrue(vm.avisoDeIsla.value)
            mira.cancel() // sale de Mesas
            advanceTimeBy(5_001); runCurrent()
            peers.value = listOf(yo, n86) // mientras tanto vuelve la N86
            assertFalse("al regresar no se entrega el true de la visita anterior", vm.avisoDeIsla.value)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `con la linea puesta se abre una mesa libre igual - el Hub en isla no frena`() = runTest {
        val libre = DiningTable("t2", "6")
        val repository = mockk<TableServiceRepository>(relaxed = true)
        coEvery { repository.openTable("v1", "t2", 2) } returns Result.success(OpenedOrder("o2", "ORD-2"))
        val hub = mockk<LanHubService>(relaxed = true)
        coEvery { hub.esperarApertura(any(), any(), any()) } returns LeaseOutcome.NoHub // no ve a nadie: isla (Hub D3: el ticket de la apertura)
        val session = TableSession()
        val vm = vmConLaN86Perdida(listOf(libre), session, MutableStateFlow(listOf(yo)), repository, hub)
        try {
            var listas = 0
            conLaLineaPuesta(vm) { vm.openTableAndStartOrdering(libre, 2) { listas++ } }
            assertEquals("entra a la mesa recién abierta", 1, listas)
            assertEquals("o2", session.current()?.orderId)
            assertEquals(TableActionState.Idle, vm.actionState.value)
        } finally { vm.viewModelScope.cancel() }
    }

    @Test fun `con la linea puesta tocar una mesa ocupada entra a su cuenta igual - ahi se cobra`() = runTest {
        val ocupada = DiningTable("t1", "5", openOrders = listOf(OpenCheckSummary("o1", "ORD-1", total = 100.0)))
        val session = TableSession()
        val vm = vmConLaN86Perdida(listOf(ocupada), session, MutableStateFlow(listOf(yo)))
        try {
            var listas = 0
            conLaLineaPuesta(vm) { vm.startOrdering(ocupada) { listas++ } }
            assertEquals(1, listas)
            assertEquals("o1", session.current()?.orderId)
        } finally { vm.viewModelScope.cancel() }
    }
}
