package com.avoqado.pos.inventory

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.inventory.data.BorradorDeConteo
import com.avoqado.pos.inventory.data.BorradorDeConteoStore
import com.avoqado.pos.inventory.data.CancelacionPendienteDeConteo
import com.avoqado.pos.inventory.data.ConteoEnCurso
import com.avoqado.pos.inventory.data.InventoryCountSyncCoordinator
import com.avoqado.pos.inventory.data.InventoryCountTransport
import com.avoqado.pos.inventory.data.RespuestaHttp
import com.avoqado.pos.inventory.data.model.StockCountItem
import com.avoqado.pos.inventory.data.model.StockCountType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryCountSyncCoordinatorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `P1 cold start drena todos los venues sin construir InventoryViewModel`() = runTest {
        val store = FakeStore(
            borrador("venue-b", "count-b", revision = 7, lineaId = "line-b"),
            borrador("venue-a", "count-a", revision = 2, lineaId = "line-a"),
        )
        val transport = FakeTransport()
        val connectivity = mockk<ConnectivityMonitor>(relaxed = true) {
            every { isConnected } returns MutableStateFlow(true)
            every { isServerReachable } returns MutableStateFlow(true)
        }
        val coordinator = InventoryCountSyncCoordinator(store, transport, connectivity)

        coordinator.start(backgroundScope)
        runCurrent()

        assertEquals(
            listOf(
                AdvanceCall("venue-a", "count-a", expectedRevision = 2),
                AdvanceCall("venue-b", "count-b", expectedRevision = 7),
            ),
            transport.advances,
        )
    }

    @Test
    fun `P1 ack de PUT avanza revision y no borra una edicion hecha durante el viaje`() = runTest {
        val original = borrador("venue-a", "count-a", revision = 4, lineaId = "line-a")
        val store = FakeStore(original)
        val empezo = CompletableDeferred<Unit>()
        val responder = CompletableDeferred<Unit>()
        val transport = FakeTransport(advance = { _, _, _, expectedRevision ->
            empezo.complete(Unit)
            responder.await()
            RespuestaHttp(200, """{"success":true,"revision":${expectedRevision + 1}}""")
        })
        val connectivity = mockk<ConnectivityMonitor>(relaxed = true) {
            every { isConnected } returns MutableStateFlow(true)
            every { isServerReachable } returns MutableStateFlow(true)
        }
        val coordinator = InventoryCountSyncCoordinator(store, transport, connectivity)

        coordinator.start(backgroundScope)
        empezo.await()
        val lineaB = original.lineas.single().copy(
            id = "line-b",
            productId = "product-line-b",
            counted = 9.0,
            countedAt = "2026-09-08T10:00:01Z",
        )
        store.guardar(
            "venue-a",
            original.copy(
                lineas = original.lineas + lineaB,
                pendientesDeEnviar = setOf("line-a", "line-b"),
            ),
        )
        responder.complete(Unit)
        runCurrent()

        val vigente = store.leer("venue-a")!!
        assertEquals(5, vigente.revision)
        assertEquals(setOf("line-b"), vigente.pendientesDeEnviar)
        assertEquals(9.0, vigente.lineas.single { it.id == "line-b" }.counted, 0.0)
    }

    @Test
    fun `P1 no toca red offline y drena al reconectar`() = runTest {
        val store = FakeStore(borrador("venue-a", "count-a", 3, "line-a"))
        val transport = FakeTransport()
        val red = MutableStateFlow(false)
        val servidor = MutableStateFlow(true)
        val coordinator = InventoryCountSyncCoordinator(store, transport, connectivity(red, servidor))

        coordinator.start(backgroundScope)
        runCurrent()
        assertTrue(transport.advances.isEmpty())

        red.value = true
        runCurrent()
        assertEquals(listOf(AdvanceCall("venue-a", "count-a", 3)), transport.advances)
    }

    @Test
    fun `P1 revision legacy desconocida se pausa durable y no inventa cero`() = runTest {
        val store = FakeStore(borrador("venue-a", "count-a", null, "line-a"))
        val transport = FakeTransport()
        val coordinator = InventoryCountSyncCoordinator(store, transport, connectivity())

        coordinator.start(backgroundScope)
        runCurrent()

        assertTrue(transport.advances.isEmpty())
        assertEquals(ConteoEnCurso.CODIGO_REVISION_DESCONOCIDA, store.leer("venue-a")?.conflictoRevision?.code)
        assertNull(store.leer("venue-a")?.revision)
    }

    @Test
    fun `P1 409 de revision conserva cola y details sin decir cerrado`() = runTest {
        val store = FakeStore(borrador("venue-a", "count-a", 4, "line-a"))
        val transport = FakeTransport(
            advance = { _, _, _, _ -> conflicto(expected = 4, current = 5) },
        )
        val coordinator = InventoryCountSyncCoordinator(store, transport, connectivity())

        coordinator.start(backgroundScope)
        runCurrent()

        val draft = store.leer("venue-a")!!
        assertEquals(setOf("line-a"), draft.pendientesDeEnviar)
        assertEquals(4, draft.revision)
        assertEquals(5, draft.conflictoRevision?.currentRevision)
        assertEquals("IN_PROGRESS", draft.conflictoRevision?.status)
    }

    @Test
    fun `P1 403 y APPLYING conservan sin crear conflicto de revision`() = runTest {
        val store403 = FakeStore(borrador("venue-a", "count-a", 4, "line-a"))
        InventoryCountSyncCoordinator(
            store403,
            FakeTransport(advance = { _, _, _, _ -> RespuestaHttp(403, """{"message":"Sin permiso"}""") }),
            connectivity(),
        ).start(backgroundScope)
        runCurrent()
        assertEquals(setOf("line-a"), store403.leer("venue-a")?.pendientesDeEnviar)
        assertNull(store403.leer("venue-a")?.conflictoRevision)

        val storeApplying = FakeStore(borrador("venue-b", "count-b", 8, "line-b"))
        InventoryCountSyncCoordinator(
            storeApplying,
            FakeTransport(advance = { _, _, _, _ -> applying(current = 8) }),
            connectivity(),
        ).start(backgroundScope)
        runCurrent()
        assertEquals(setOf("line-b"), storeApplying.leer("venue-b")?.pendientesDeEnviar)
        assertNull(storeApplying.leer("venue-b")?.conflictoRevision)
    }

    @Test
    fun `P1 cancelacion 409 revision no se consume y legacy no toca red`() = runTest {
        val store = FakeStore()
        store.agregarCancelacionPendiente(
            CancelacionPendienteDeConteo("venue-a", "cancel-a", expectedRevision = 2),
        )
        store.agregarCancelacionPendiente(
            CancelacionPendienteDeConteo(
                "venue-b",
                "cancel-legacy",
                conflictoRevision = com.avoqado.pos.inventory.data.ConflictoRevision(
                    ConteoEnCurso.CODIGO_REVISION_DESCONOCIDA,
                    ConteoEnCurso.REVISION_DESCONOCIDA,
                    "venue-b",
                    "cancel-legacy",
                ),
            ),
        )
        val transport = FakeTransport(cancel = { _, _, _ -> conflicto(2, 3, status = "CANCELLED") })
        val coordinator = InventoryCountSyncCoordinator(store, transport, connectivity())

        coordinator.start(backgroundScope)
        runCurrent()

        assertEquals(listOf(CancelCall("venue-a", "cancel-a", 2)), transport.cancels)
        assertEquals(3, store.cancelacionesPendientes("venue-a").single().conflictoRevision?.currentRevision)
        assertEquals("cancel-legacy", store.cancelacionesPendientes("venue-b").single().countId)
    }

    @Test
    fun `P1 stop durante request no hace ack ni inicia otro venue`() = runTest {
        val store = FakeStore(
            borrador("venue-a", "count-a", 1, "line-a"),
            borrador("venue-b", "count-b", 7, "line-b"),
        )
        val empezo = CompletableDeferred<Unit>()
        val liberar = CompletableDeferred<Unit>()
        val transport = FakeTransport(advance = { _, _, _, revision ->
            empezo.complete(Unit)
            withContext(NonCancellable) { liberar.await() }
            RespuestaHttp(200, """{"revision":${revision + 1}}""")
        })
        val coordinator = InventoryCountSyncCoordinator(store, transport, connectivity())

        coordinator.start(backgroundScope)
        empezo.await()
        coordinator.stop()
        liberar.complete(Unit)
        runCurrent()

        assertEquals(listOf(AdvanceCall("venue-a", "count-a", 1)), transport.advances)
        assertEquals(1, store.leer("venue-a")?.revision)
        assertEquals(7, store.leer("venue-b")?.revision)
    }

    @Test
    fun `P1 descarte espera PUT y encola cancel con revision ACK sin reenviar lineas`() = runTest {
        val store = FakeStore(borrador("venue-a", "count-a", 4, "line-a"))
        val empezo = CompletableDeferred<Unit>()
        val liberar = CompletableDeferred<Unit>()
        val transport = FakeTransport(
            advance = { _, _, _, _ ->
                empezo.complete(Unit)
                liberar.await()
                RespuestaHttp(200, """{"revision":5}""")
            },
        )
        val coordinator = InventoryCountSyncCoordinator(store, transport, connectivity())
        coordinator.start(backgroundScope)
        empezo.await()

        val descarte = launch { assertTrue(coordinator.descartarManualmente("venue-a", "count-a")) }
        runCurrent()
        assertTrue(transport.cancels.isEmpty())
        liberar.complete(Unit)
        descarte.join()
        runCurrent()

        assertEquals(listOf(AdvanceCall("venue-a", "count-a", 4)), transport.advances)
        assertEquals(listOf(CancelCall("venue-a", "count-a", 5)), transport.cancels)
        assertNull(store.leer("venue-a"))
    }

    @Test
    fun `P1 barrera cancel existente evita replay de lineas del mismo conteo`() = runTest {
        val store = FakeStore(borrador("venue-a", "count-a", 4, "line-a"))
        store.agregarCancelacionPendiente(CancelacionPendienteDeConteo("venue-a", "count-a", 4))
        val transport = FakeTransport()
        val coordinator = InventoryCountSyncCoordinator(store, transport, connectivity())

        coordinator.start(backgroundScope)
        runCurrent()

        assertTrue(transport.advances.isEmpty())
        assertEquals(listOf(CancelCall("venue-a", "count-a", 4)), transport.cancels)
    }

    @Test
    fun `P1 barrera cancel bloquea tambien avance directo y cierre manual`() = runTest {
        val store = FakeStore(borrador("venue-a", "count-a", 4, "line-a"))
        store.agregarCancelacionPendiente(CancelacionPendienteDeConteo("venue-a", "count-a", 4))
        val transport = FakeTransport()
        val coordinator = InventoryCountSyncCoordinator(store, transport, connectivity())
        coordinator.start(backgroundScope)

        val avance = coordinator.sincronizarAvanceAhora("venue-a", "count-a")
        val cierre = coordinator.cerrarManualmente("venue-a", "count-a")

        assertNull(avance)
        assertFalse(cierre.completado)
        assertTrue(transport.advances.isEmpty())
        assertTrue(transport.finals.isEmpty())
        assertTrue(transport.confirms.isEmpty())
        assertEquals(1, store.cancelacionesPendientes("venue-a").size)
    }

    @Test
    fun `P1 final ACK durable permite retry manual tras recrear coordinador sin repetir PUT`() = runTest {
        val store = FakeStore(
            borrador("venue-a", "count-a", 4, "line-a").copy(pendientesDeEnviar = emptySet()),
        )
        val primeraRed = FakeTransport(
            final = { _, _, _, _, _ -> RespuestaHttp(200, """{"revision":5}""") },
            confirm = { _, _, _ -> applying(current = 5) },
        )
        val primero = InventoryCountSyncCoordinator(store, primeraRed, connectivity())
        primero.start(backgroundScope)
        runCurrent()

        val primerResultado = primero.cerrarManualmente("venue-a", "count-a")
        assertFalse(primerResultado.completado)
        assertEquals(5, store.leer("venue-a")?.revisionConPutFinalConfirmado)
        primero.stop()

        val segundaRed = FakeTransport(
            confirm = { _, _, expected -> RespuestaHttp(200, """{"revision":${expected + 1}}""") },
        )
        val recreado = InventoryCountSyncCoordinator(store, segundaRed, connectivity())
        recreado.start(backgroundScope)
        runCurrent()
        val segundoResultado = recreado.cerrarManualmente("venue-a", "count-a")

        assertTrue(segundoResultado.completado)
        assertTrue(segundaRed.finals.isEmpty())
        assertEquals(listOf(ConfirmCall("venue-a", "count-a", 5)), segundaRed.confirms)
    }

    @Test
    fun `P1 borrar nota viaja como string vacio y deja stage antes de confirm`() = runTest {
        val store = FakeStore(
            borrador("venue-a", "count-a", 4, "line-a").copy(
                nota = "",
                notaPendienteDeEnviar = true,
                pendientesDeEnviar = emptySet(),
            ),
        )
        var stageAlConfirmar: Int? = null
        val transport = FakeTransport(
            final = { _, _, _, _, _ -> RespuestaHttp(200, """{"revision":5}""") },
            confirm = { _, _, _ ->
                stageAlConfirmar = store.leer("venue-a")?.revisionConPutFinalConfirmado
                RespuestaHttp(200, """{"revision":6}""")
            },
        )
        val coordinator = InventoryCountSyncCoordinator(store, transport, connectivity())
        coordinator.start(backgroundScope)
        runCurrent()

        val resultado = coordinator.cerrarManualmente("venue-a", "count-a")

        assertTrue(resultado.completado)
        assertEquals("", transport.finals.single().note)
        assertEquals(5, stageAlConfirmar)
        assertEquals(false, store.leer("venue-a")?.notaPendienteDeEnviar)
    }

    @Test
    fun `P1 COMPLETED mas dos no se auto reconoce como confirm perdido`() = runTest {
        val store = FakeStore(
            borrador("venue-a", "count-a", 5, "line-a").copy(
                pendientesDeEnviar = emptySet(),
                revisionConPutFinalConfirmado = 5,
            ),
        )
        val transport = FakeTransport(confirm = { _, _, _ -> conflicto(5, 7, status = "COMPLETED") })
        val coordinator = InventoryCountSyncCoordinator(store, transport, connectivity())
        coordinator.start(backgroundScope)
        runCurrent()

        val resultado = coordinator.cerrarManualmente("venue-a", "count-a")

        assertFalse(resultado.completado)
        assertEquals(7, store.leer("venue-a")?.conflictoRevision?.currentRevision)
        assertEquals(5, store.leer("venue-a")?.revisionConPutFinalConfirmado)
    }

    private fun borrador(
        venueId: String,
        countId: String,
        revision: Int?,
        lineaId: String,
    ) = BorradorDeConteo(
        venueId = venueId,
        countId = countId,
        type = StockCountType.FULL,
        lineas = listOf(
            StockCountItem(
                id = lineaId,
                productId = "product-$lineaId",
                productName = "Producto $lineaId",
                counted = 3.0,
                countedAt = "2026-09-08T10:00:00Z",
            ),
        ),
        pendientesDeEnviar = setOf(lineaId),
        revision = revision,
        actualizadoEn = 1_700_000_000_000,
    )

    private data class AdvanceCall(
        val venueId: String,
        val countId: String,
        val expectedRevision: Int,
    )

    private data class FinalCall(val venueId: String, val countId: String, val note: String?, val expectedRevision: Int)
    private data class ConfirmCall(val venueId: String, val countId: String, val expectedRevision: Int)
    private data class CancelCall(val venueId: String, val countId: String, val expectedRevision: Int)

    private fun connectivity(
        red: MutableStateFlow<Boolean> = MutableStateFlow(true),
        servidor: MutableStateFlow<Boolean> = MutableStateFlow(true),
    ) = mockk<ConnectivityMonitor>(relaxed = true) {
        every { isConnected } returns red
        every { isServerReachable } returns servidor
    }

    private fun conflicto(expected: Int, current: Int, status: String = "IN_PROGRESS") = RespuestaHttp(
        409,
        """{"message":"cambió","code":"INVENTORY_COUNT_REVISION_CONFLICT","details":{"venueId":"venue-a","countId":"count-a","expectedRevision":$expected,"currentRevision":$current,"status":"$status"}}""",
    )

    private fun applying(current: Int) = RespuestaHttp(
        409,
        """{"message":"aplicando","code":"STOCK_COUNT_APPLYING","details":{"venueId":"venue-a","countId":"count-a","currentRevision":$current,"status":"APPLYING"}}""",
    )

    private class FakeTransport(
        private val advance: suspend (String, String, List<StockCountItem>, Int) -> RespuestaHttp =
            { _, _, _, revision -> RespuestaHttp(200, """{"success":true,"revision":${revision + 1}}""") },
        private val final: suspend (String, String, List<StockCountItem>, String?, Int) -> RespuestaHttp =
            { _, _, _, _, revision -> RespuestaHttp(200, """{"success":true,"revision":${revision + 1}}""") },
        private val confirm: suspend (String, String, Int) -> RespuestaHttp =
            { _, _, revision -> RespuestaHttp(200, """{"success":true,"revision":${revision + 1}}""") },
        private val cancel: suspend (String, String, Int) -> RespuestaHttp =
            { _, _, revision -> RespuestaHttp(200, """{"success":true,"count":{"revision":${revision + 1}}}""") },
    ) : InventoryCountTransport {
        val advances = mutableListOf<AdvanceCall>()
        val finals = mutableListOf<FinalCall>()
        val confirms = mutableListOf<ConfirmCall>()
        val cancels = mutableListOf<CancelCall>()

        override suspend fun enviarAvance(
            venueId: String,
            countId: String,
            items: List<StockCountItem>,
            expectedRevision: Int,
        ): RespuestaHttp {
            advances += AdvanceCall(venueId, countId, expectedRevision)
            return advance(venueId, countId, items, expectedRevision)
        }

        override suspend fun enviarFinal(
            venueId: String,
            countId: String,
            items: List<StockCountItem>,
            note: String?,
            expectedRevision: Int,
        ): RespuestaHttp {
            finals += FinalCall(venueId, countId, note, expectedRevision)
            return final(venueId, countId, items, note, expectedRevision)
        }

        override suspend fun confirmarConteo(
            venueId: String,
            countId: String,
            expectedRevision: Int,
        ): RespuestaHttp {
            confirms += ConfirmCall(venueId, countId, expectedRevision)
            return confirm(venueId, countId, expectedRevision)
        }

        override suspend fun cancelStockCount(
            venueId: String,
            countId: String,
            expectedRevision: Int,
        ): RespuestaHttp {
            cancels += CancelCall(venueId, countId, expectedRevision)
            return cancel(venueId, countId, expectedRevision)
        }
    }

    /**
     * Doble de disco: el transporte es el único borde externo que se reemplaza. La prueba observa
     * las peticiones reales que construye el coordinador a partir de dos borradores persistidos.
     */
    private class FakeStore(vararg borradores: BorradorDeConteo) : BorradorDeConteoStore {
        private val porVenue = borradores.associateBy { it.venueId }.toMutableMap()
        private val cancelaciones = mutableMapOf<String, MutableList<CancelacionPendienteDeConteo>>()

        override fun leer(): BorradorDeConteo? = null
        override fun leer(venueId: String): BorradorDeConteo? = porVenue[venueId]
        override fun venuesConTrabajo(): List<String> = (porVenue.keys + cancelaciones.keys).distinct().sorted()
        override fun guardar(borrador: BorradorDeConteo): Boolean {
            porVenue[borrador.venueId] = borrador
            return true
        }
        override fun guardar(venueId: String, borrador: BorradorDeConteo): Boolean =
            guardar(borrador.copy(venueId = venueId))
        override fun borrar(): Boolean = true
        override fun borrar(venueId: String): Boolean = porVenue.remove(venueId) != null
        override fun cancelacionesPendientes() = emptyList<String>()
        override fun agregarCancelacionPendiente(countId: String) = true
        override fun quitarCancelacionPendiente(countId: String) = true
        override fun descartarYEncolarCancelacion(countId: String) = true
        override fun descartarYEncolarCancelacion(
            venueId: String,
            countId: String,
            expectedRevision: Int?,
        ): Boolean {
            porVenue.remove(venueId)
            if (countId.isNotBlank()) {
                agregarCancelacionPendiente(
                    CancelacionPendienteDeConteo(
                        venueId = venueId,
                        countId = countId,
                        expectedRevision = expectedRevision,
                    ),
                )
            }
            return true
        }
        override fun cancelacionesPendientes(venueId: String): List<CancelacionPendienteDeConteo> =
            cancelaciones[venueId].orEmpty()
        override fun agregarCancelacionPendiente(cancelacion: CancelacionPendienteDeConteo): Boolean {
            val lista = cancelaciones.getOrPut(cancelacion.venueId) { mutableListOf() }
            lista.removeAll { it.countId == cancelacion.countId }
            lista += cancelacion
            return true
        }
        override fun quitarCancelacionPendiente(venueId: String, countId: String): Boolean {
            cancelaciones[venueId]?.removeAll { it.countId == countId }
            return true
        }
        override fun reconocerPut(
            venueId: String,
            countId: String,
            expectedRevision: Int,
            nuevaRevision: Int,
            sellos: Map<String, Pair<Double, String?>>,
            notaEnviada: String?,
            esFinal: Boolean,
        ): Boolean {
            val actual = porVenue[venueId] ?: return false
            if (actual.countId != countId || actual.revision != expectedRevision) return false
            val vivas = actual.lineas.associate { it.id to (it.counted to it.countedAt) }
            val pendientes = actual.pendientesDeEnviar - sellos.filter { (id, sello) -> vivas[id] == sello }.keys
            val notaReconocida = notaEnviada != null && actual.nota == notaEnviada &&
                ConteoEnCurso.notaPendienteDeEnviar(actual)
            val notaPendiente = if (notaReconocida) false else ConteoEnCurso.notaPendienteDeEnviar(actual)
            porVenue[venueId] = actual.copy(
                revision = nuevaRevision,
                pendientesDeEnviar = pendientes,
                notaPendienteDeEnviar = notaPendiente,
                revisionConPutFinalConfirmado = nuevaRevision.takeIf {
                    esFinal && pendientes.isEmpty() && !notaPendiente
                },
            )
            return true
        }
    }
}
