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

    // MARK: - Hub D6 bandas (hz:033d44ca:D6, ensayo del 16-nov): la línea sigue a quien de verdad coordina

    private val samsung = LanPeer("samsung-d118", "192.168.100.159", 37147)

    @Test fun `D6 bandas - la D3 ve a la N86 pero no a la SAMSUNG que recuerda - dice que no ve a todas`() {
        // Antes: la N86 a la vista apagaba la línea, y la D3 no avisaba que con la SAMSUNG trabajaba sola.
        assertEquals(
            AvisoDeIsla.TEXTO_PARCIAL,
            AvisoDeIsla.texto(true, yo.deviceId, listOf(yo, n86), setOf(n86.deviceId, samsung.deviceId)),
        )
        assertFalse("no es la línea de «no ve a ninguna»", AvisoDeIsla.mostrar(true, yo.deviceId, listOf(yo, n86), setOf(samsung.deviceId)))
    }

    @Test fun `D6 bandas - la SAMSUNG no ve a la N86 pero la N86 le declara - no dice que no ve a ninguna`() {
        // La SAMSUNG es árbitro de la N86: coordina con ella aunque NSD no la traiga. Le falta la D3 (5 GHz aislado): parcial.
        val d3 = "d3-4f9f"
        assertEquals(
            AvisoDeIsla.TEXTO_PARCIAL,
            AvisoDeIsla.texto(true, samsung.deviceId, listOf(samsung), setOf(n86.deviceId, d3), meDeclaran = setOf(n86.deviceId)),
        )
        // Sin la D3 en la memoria: la N86 le declara y no falta nadie ⇒ sin línea.
        assertEquals(null, AvisoDeIsla.texto(true, samsung.deviceId, listOf(samsung), setOf(n86.deviceId), meDeclaran = setOf(n86.deviceId)))
    }

    @Test fun `D6 bandas - con todas a la vista o sin Hub no hay linea y sin nadie sigue la de siempre`() {
        assertEquals(null, AvisoDeIsla.texto(true, yo.deviceId, listOf(yo, n86, samsung), setOf(n86.deviceId, samsung.deviceId)))
        assertEquals(null, AvisoDeIsla.texto(false, yo.deviceId, listOf(yo, n86), setOf(samsung.deviceId)))
        assertEquals(AvisoDeIsla.TEXTO, AvisoDeIsla.texto(true, yo.deviceId, listOf(yo), setOf(n86.deviceId, samsung.deviceId)))
        // Sólo este aparato «se declara» a sí mismo: no cuenta como compañía.
        assertEquals(AvisoDeIsla.TEXTO, AvisoDeIsla.texto(true, yo.deviceId, listOf(yo), setOf(n86.deviceId), meDeclaran = setOf(yo.deviceId)))
    }

    @Test fun `D6 bandas - el flujo cambia de texto cuando una caja que coordinaba se pierde`() = runTest {
        val peers = MutableStateFlow(listOf(yo, n86))
        val visto = mutableListOf<String?>()
        backgroundScope.launch {
            AvisoDeIsla.flujo(peers, { yo.deviceId }, { true }, { setOf(n86.deviceId, samsung.deviceId) }).collect { visto += it }
        }
        runCurrent()
        assertTrue("Codex r2 #2: durante la gracia no emite nada (el null inicial es del ViewModel)", visto.isEmpty())
        advanceTimeBy(AvisoDeIsla.GRACIA_MS + 1); runCurrent()
        assertEquals("falta la SAMSUNG: parcial a los 10 s", AvisoDeIsla.TEXTO_PARCIAL, visto.last())
        peers.value = listOf(yo) // también se pierde la N86
        runCurrent()
        assertEquals("Codex r1 4: ya se mostraba: cambia de texto sin otra gracia", AvisoDeIsla.TEXTO, visto.last())
        peers.value = listOf(yo, n86, samsung) // vuelven las dos
        runCurrent()
        assertEquals("se quita al instante", null, visto.last())
    }

    @Test fun `Codex r1 4 - la caja que empieza a declararle cambia el texto al instante, sin esperar la revision del minuto`() = runTest {
        val meDeclaran = MutableStateFlow(emptySet<String>())
        val visto = mutableListOf<String?>()
        // La SAMSUNG del ensayo: no ve a nadie por NSD, recuerda a la N86 y a la D3.
        backgroundScope.launch {
            AvisoDeIsla.flujo(MutableStateFlow(listOf(yo)), { yo.deviceId }, { true }, { setOf(n86.deviceId, "d3-4f9f") }, meDeclaran)
                .collect { visto += it }
        }
        advanceTimeBy(AvisoDeIsla.GRACIA_MS + 1); runCurrent()
        assertEquals(AvisoDeIsla.TEXTO, visto.last())
        meDeclaran.value = setOf(n86.deviceId) // la N86 le empieza a declarar
        runCurrent()
        assertEquals(AvisoDeIsla.TEXTO_PARCIAL, visto.last())
        meDeclaran.value = emptySet() // deja de declararle (vence a los 30 s): vuelve el de siempre, también al instante
        runCurrent()
        assertEquals(AvisoDeIsla.TEXTO, visto.last())
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
        backgroundScope.launch { AvisoDeIsla.flujo(peers, { yo.deviceId }, { true }, { setOf(n86.deviceId) }).collect { visto += (it != null) } }
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
        backgroundScope.launch { AvisoDeIsla.flujo(peers, { yo.deviceId }, { true }, { setOf(n86.deviceId) }).collect { visto += (it != null) } }
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
        backgroundScope.launch { AvisoDeIsla.flujo(peers, { yo.deviceId }, { hub }, { setOf(n86.deviceId) }).collect { visto += (it != null) } }
        advanceTimeBy(AvisoDeIsla.GRACIA_MS + 1); runCurrent()
        assertTrue(visto.last())
        hub = false
        advanceTimeBy(AvisoDeIsla.REVISION_MS); runCurrent()
        assertFalse(visto.last())
    }

    @Test fun `Codex r1 4 - la revision del minuto no reinicia los 10 s de una ausencia que ya corria`() = runTest {
        val peers = MutableStateFlow(listOf(yo, n86))
        val visto = mutableListOf<Boolean>()
        backgroundScope.launch { AvisoDeIsla.flujo(peers, { yo.deviceId }, { true }, { setOf(n86.deviceId) }).collect { visto += (it != null) } }
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
        every { hub.cajasQueLeDeclaran } returns MutableStateFlow(emptySet())
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
        assertEquals("la línea está puesta", AvisoDeIsla.TEXTO, vm.avisoDeIsla.value)
        bloque()
        runCurrent()
        assertEquals("y sigue puesta", AvisoDeIsla.TEXTO, vm.avisoDeIsla.value)
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
            assertEquals(AvisoDeIsla.TEXTO, vm.avisoDeIsla.value)
            mira.cancel() // sale de Mesas
            advanceTimeBy(5_001); runCurrent()
            peers.value = listOf(yo, n86) // mientras tanto vuelve la N86
            assertEquals("al regresar no se entrega la línea de la visita anterior", null, vm.avisoDeIsla.value)
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
