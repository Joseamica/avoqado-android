package com.avoqado.pos.inventory

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.inventory.data.BorradorDeConteo
import com.avoqado.pos.inventory.data.BorradorDeConteoStore
import com.avoqado.pos.inventory.data.InventoryCountSyncCoordinator
import com.avoqado.pos.inventory.data.InventoryCountTransport
import com.avoqado.pos.inventory.data.RespuestaHttp
import com.avoqado.pos.inventory.data.model.StockCountItem
import com.avoqado.pos.inventory.data.model.StockCountType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

        coordinator.start(this)
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
        val transport = FakeTransport { _, _, _, expectedRevision ->
            empezo.complete(Unit)
            responder.await()
            RespuestaHttp(200, """{"success":true,"revision":${expectedRevision + 1}}""")
        }
        val connectivity = mockk<ConnectivityMonitor>(relaxed = true) {
            every { isConnected } returns MutableStateFlow(true)
            every { isServerReachable } returns MutableStateFlow(true)
        }
        val coordinator = InventoryCountSyncCoordinator(store, transport, connectivity)

        coordinator.start(this)
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

    private fun borrador(
        venueId: String,
        countId: String,
        revision: Int,
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

    private class FakeTransport(
        private val advance: suspend (String, String, List<StockCountItem>, Int) -> RespuestaHttp =
            { _, _, _, revision -> RespuestaHttp(200, """{"success":true,"revision":${revision + 1}}""") },
    ) : InventoryCountTransport {
        val advances = mutableListOf<AdvanceCall>()

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
        ) = error("No se usa en esta prueba")

        override suspend fun confirmarConteo(
            venueId: String,
            countId: String,
            expectedRevision: Int,
        ) = error("No se usa en esta prueba")

        override suspend fun cancelStockCount(
            venueId: String,
            countId: String,
            expectedRevision: Int,
        ) = error("No se usa en esta prueba")
    }

    /**
     * Doble de disco: el transporte es el único borde externo que se reemplaza. La prueba observa
     * las peticiones reales que construye el coordinador a partir de dos borradores persistidos.
     */
    private class FakeStore(vararg borradores: BorradorDeConteo) : BorradorDeConteoStore {
        private val porVenue = borradores.associateBy { it.venueId }.toMutableMap()

        override fun leer(): BorradorDeConteo? = null
        override fun leer(venueId: String): BorradorDeConteo? = porVenue[venueId]
        override fun venuesConTrabajo(): List<String> = porVenue.keys.sorted()
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
        override fun reconocerPut(
            venueId: String,
            countId: String,
            expectedRevision: Int,
            nuevaRevision: Int,
            sellos: Map<String, Pair<Double, String?>>,
        ): Boolean {
            val actual = porVenue[venueId] ?: return false
            if (actual.countId != countId || actual.revision != expectedRevision) return false
            val vivas = actual.lineas.associate { it.id to (it.counted to it.countedAt) }
            porVenue[venueId] = actual.copy(
                revision = nuevaRevision,
                pendientesDeEnviar = actual.pendientesDeEnviar - sellos.filter { (id, sello) -> vivas[id] == sello }.keys,
            )
            return true
        }
    }
}
