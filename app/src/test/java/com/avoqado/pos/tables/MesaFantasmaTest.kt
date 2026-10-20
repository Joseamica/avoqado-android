package com.avoqado.pos.tables

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.database.CachedPayloadDao
import com.avoqado.pos.core.data.local.database.CachedPayloadEntity
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import com.avoqado.pos.pos.data.model.CartItem
import com.avoqado.pos.pos.data.model.CartItemType
import com.avoqado.pos.tables.data.*
import com.avoqado.pos.tables.presentation.TableOrderViewModel.PendingLine
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Mesa abierta y cobrada sin red: cuando la cola ya confirmó todo de esa cuenta, el plano dice lo que dice el servidor. */
class MesaFantasmaTest {
    @get:Rule val main = MainDispatcherRule()
    private val venue = "venue-fantasma"
    private val mesa = DiningTable(id = "t11", number = "M11", shape = "ROUND", positionX = 0.5f, positionY = 0.5f, rotation = 0)
    private fun linea(id: String) = PendingLine(CartItem(id = id, type = CartItemType.ProductItem("cafe"), name = "Café", unitPrice = 3000), null)
    private fun trabajo(intents: Int) = TrabajoSinResolver(intents, 0, emptySet(), 0)
    private fun cuenta(status: String) = OrderDetailResponse(order = OrderDetail(id = "real", status = status))
    private val api = mockk<ApiService>()

    /** Plano con la M11 abierta sin red como «local», su ronda guardada y el OPEN ya confirmado (local → real). */
    private val session = TableSession()
    /** Por default el mesero ya volvió al plano (la sesión de la mesa se estacionó); `enPantalla` la deja abierta. */
    private suspend fun escenario(drafts: TableRoundDraftStore, outbox: SyncOutbox, pendiente: List<PendingLine> = emptyList(),
        enPantalla: Boolean = false): TableServiceRepository {
        val rows = mutableMapOf<String, CachedPayloadEntity>()
        val dao = mockk<CachedPayloadDao>()
        coEvery { dao.get(any()) } answers { rows[firstArg()] }
        coEvery { dao.upsert(any()) } answers { val row = firstArg<CachedPayloadEntity>(); rows[row.cacheKey] = row }
        coEvery { api.getTables(venue) } returns TablesResponse(data = listOf(mesa))
        // El servidor ya la cobró y cerró; las pruebas de una cuenta viva lo cambian.
        coEvery { api.getOrderDetail(venue, "real") } returns cuenta("COMPLETED")
        val repository = TableServiceRepository(api, PayloadCache(dao), outbox, session, mockk(relaxed = true), drafts)
        repository.refresh(venue).getOrThrow()
        session.start(TableSession.Active(tableId = "t11", tableNumber = "M11", areaName = null, orderId = "local", orderNumber = "LOCAL",
            version = 1, totalCents = 0, mode = TableSession.Mode.ORDERING, isProvisional = true))
        repository.markTableOccupiedLocally("t11")
        drafts.save(venue, "local", pendiente, mapOf("ronda-1" to listOf(linea("l1"))))
        drafts.promote(venue, "local", "real")
        if (!enPantalla) session.clear()
        return repository
    }

    private fun conDrafts(block: suspend TestScope.(TableRoundDraftStore) -> Unit) = runTest {
        val root = java.nio.file.Files.createTempDirectory("mesa-fantasma").toFile()
        try { block(this, TableRoundDraftStore(root)) } finally { root.deleteRecursively() }
    }

    private fun outbox(ronda: String?, sinResolver: () -> Int) = mockk<SyncOutbox>().also { o ->
        every { o.observarEstado("ronda-1") } returns flowOf(ronda)
        coEvery { o.trabajoSinResolverDeLaOrden(venue, any()) } answers { trabajo(sinResolver()) }
    }

    @Test fun `todo confirmado y orden cerrada en el servidor - la mesa queda como dice el servidor`() = conDrafts { drafts ->
        val outbox = outbox("ACKED") { 0 }
        val repository = escenario(drafts, outbox)
        repository.refresh(venue).getOrThrow()
        val m11 = repository.tables.value.single()
        assertTrue("La cuenta ya se pagó y liberó: no queda una cuenta provisional", m11.openOrders.isEmpty())
        assertFalse(m11.isOccupied)
        coVerify { outbox.trabajoSinResolverDeLaOrden(venue, match { "local" in it && "real" in it }) }
    }

    @Test fun `con trabajo sin resolver de la cuenta en la cola sigue Por sincronizar`() = conDrafts { drafts ->
        val repository = escenario(drafts, outbox("ACKED") { 1 })
        repository.refresh(venue).getOrThrow()
        val m11 = repository.tables.value.single()
        assertTrue(m11.isOccupied)
        assertTrue(m11.primaryCheck!!.isProvisional)
    }

    @Test fun `una ronda guardada que nunca se encolo retiene la mesa aunque la cola este limpia`() = conDrafts { drafts ->
        // La app murió entre guardar la ronda y encolar su ADD_ITEMS: sólo al abrir la cuenta se vuelve a encolar.
        val repository = escenario(drafts, outbox(null) { 0 })
        repository.refresh(venue).getOrThrow()
        assertTrue(repository.tables.value.single().primaryCheck!!.isProvisional)
    }

    @Test fun `una respuesta del servidor pedida antes de confirmar no suelta la mesa - la siguiente si`() = conDrafts { drafts ->
        var pendientes = 1
        val repository = escenario(drafts, outbox("ACKED") { pendientes })
        // El GET sale con la cola aún pendiente; mientras viaja, la cola confirma todo.
        coEvery { api.getTables(venue) } coAnswers { pendientes = 0; TablesResponse(data = listOf(mesa)) }
        repository.refresh(venue).getOrThrow()
        assertTrue("Una respuesta vieja no puede pintar libre la mesa", repository.tables.value.single().primaryCheck!!.isProvisional)
        repository.refresh(venue).getOrThrow()
        assertTrue(repository.tables.value.single().openOrders.isEmpty())
    }

    @Test fun `con productos sin mandar en el borrador se conserva aunque la cola este limpia`() = conDrafts { drafts ->
        val repository = escenario(drafts, outbox("ACKED") { 0 }, pendiente = listOf(linea("sin-mandar")))
        repository.refresh(venue).getOrThrow()
        assertTrue(repository.tables.value.single().primaryCheck!!.isProvisional)
    }

    @Test fun `si la cola no se puede leer se conserva`() = conDrafts { drafts ->
        val outbox = mockk<SyncOutbox>()
        every { outbox.observarEstado(any()) } returns flowOf("ACKED")
        coEvery { outbox.trabajoSinResolverDeLaOrden(venue, any()) } throws IllegalStateException("base ocupada")
        val repository = escenario(drafts, outbox)
        repository.refresh(venue).getOrThrow()
        assertTrue(repository.tables.value.single().primaryCheck!!.isProvisional)
    }

    @Test fun `con la cuenta abierta en pantalla no se suelta - ahi se estan agregando productos`() = conDrafts { drafts ->
        val repository = escenario(drafts, outbox("ACKED") { 0 }, enPantalla = true)
        repository.refresh(venue).getOrThrow()
        assertTrue(repository.tables.value.single().primaryCheck!!.isProvisional)
    }

    @Test fun `el mesero abre la cuenta mientras el plano decide - no se suelta`() = conDrafts { drafts ->
        var llamadas = 0
        val outbox = outbox("ACKED") { 0 }
        val repository = escenario(drafts, outbox)
        // 1.ª lectura: antes del GET; 2.ª: tras el GET. En medio de la 2.ª, el mesero abre la mesa desde el plano.
        coEvery { outbox.trabajoSinResolverDeLaOrden(venue, any()) } answers {
            if (++llamadas == 2) session.start(TableSession.Active(tableId = "t11", tableNumber = "M11", areaName = null, orderId = "local",
                orderNumber = "LOCAL", version = 1, totalCents = 0, mode = TableSession.Mode.ORDERING, isProvisional = true))
            trabajo(0)
        }
        repository.refresh(venue).getOrThrow()
        assertEquals(2, llamadas)
        assertTrue("La cuenta que el mesero acaba de abrir sigue en el plano", repository.tables.value.single().primaryCheck!!.isProvisional)
    }

    @Test fun `dos refrescos van en fila - el segundo GET sale cuando el primero ya se pinto`() = conDrafts { drafts ->
        val repository = escenario(drafts, outbox("ACKED") { 0 })
        val pedida = CompletableDeferred<Unit>()
        val vieja = CompletableDeferred<TablesResponse>()
        var gets = 0
        coEvery { api.getTables(venue) } coAnswers {
            if (++gets == 1) { pedida.complete(Unit); vieja.await() }
            else TablesResponse(data = listOf(mesa.copy(openOrders = listOf(OpenCheckSummary("real", "ORD-real")))))
        }
        val a = async { repository.refresh(venue) }
        pedida.await()
        val b = async { repository.refresh(venue) }
        testScheduler.advanceUntilIdle()
        assertEquals("Con un GET en el aire el segundo no sale", 1, gets)
        vieja.complete(TablesResponse(data = listOf(mesa)))
        a.await().getOrThrow(); b.await().getOrThrow()
        assertEquals(2, gets)
        assertEquals("Gana la foto pedida después", listOf("real"), repository.tables.value.single().openOrders.map { it.id })
    }

    @Test fun `un refresco que esperaba turno no regresa el plano a la sucursal anterior`() = conDrafts { drafts ->
        // Codex r4 #2: un refresco de A espera en la fila, el usuario cambia a B; al entrar no puede volver a activar A.
        val repository = escenario(drafts, outbox("ACKED") { 0 })
        val pedida = CompletableDeferred<Unit>()
        val sigue = CompletableDeferred<TablesResponse>()
        coEvery { api.getTables(venue) } coAnswers { pedida.complete(Unit); sigue.await() }
        val a = async { repository.refresh(venue) }
        pedida.await()
        val b = async { repository.refresh(venue) }
        testScheduler.advanceUntilIdle()
        repository.hydrate("otra-sucursal")
        sigue.complete(TablesResponse(data = listOf(mesa)))
        a.await(); b.await()
        assertTrue("En la otra sucursal no aparecen las mesas de la anterior", repository.tables.value.isEmpty())
    }

    @Test fun `un refresco pedido antes de cambiar de sucursal no la regresa aunque espere el candado del plano`() = conDrafts { drafts ->
        // Codex r5 #1: la sucursal se anota al PEDIR el refresco, no después de esperar el candado (ahí ya puede ser la nueva).
        val outbox = outbox("ACKED") { 0 }
        val repository = escenario(drafts, outbox)
        val dentro = CompletableDeferred<Unit>()
        val puerta = CompletableDeferred<Unit>()
        coEvery { outbox.trabajoSinResolverDeLaOrden(venue, any()) } coAnswers { dentro.complete(Unit); puerta.await(); trabajo(0) }
        val primero = async { repository.refresh(venue) } // tiene el candado del plano mientras lee la cola
        dentro.await()
        val cambio = async { repository.hydrate("otra-sucursal") } // el usuario cambia de sucursal: espera el candado
        val tardio = async { repository.refresh(venue) } // el coordinador ya había comprobado la sucursal vieja
        testScheduler.advanceUntilIdle()
        puerta.complete(Unit)
        primero.await(); cambio.await(); tardio.await()
        assertTrue("En la otra sucursal no aparecen las mesas de la anterior", repository.tables.value.isEmpty())
    }

    @Test fun `si la mesa no viene en la respuesta (archivada) su cuenta no se suelta`() = conDrafts { drafts ->
        // Codex r5 #2: /tables omite las mesas archivadas; que falte la mesa no dice que su cuenta se cerró.
        val repository = escenario(drafts, outbox("ACKED") { 0 })
        coEvery { api.getTables(venue) } returns TablesResponse(data = emptyList())
        coEvery { api.getOrderDetail(venue, "real") } returns cuenta("PENDING")
        repository.refresh(venue).getOrThrow()
        assertTrue("La cuenta viva sigue en el plano", repository.tables.value.single().primaryCheck!!.isProvisional)
    }

    @Test fun `una cuenta viva movida a una mesa archivada no se suelta aunque su mesa de origen venga libre`() = conDrafts { drafts ->
        // Codex r6: sin red se movió de M11 a una mesa que después archivaron; /tables trae M11 libre y omite la otra.
        // Sólo se suelta lo que el servidor dice CERRADO, no lo que falta en /tables.
        val repository = escenario(drafts, outbox("ACKED") { 0 })
        coEvery { api.getOrderDetail(venue, "real") } returns cuenta("PENDING")
        repository.refresh(venue).getOrThrow()
        assertTrue(repository.tables.value.single().primaryCheck!!.isProvisional)
    }

    @Test fun `si no se puede preguntar al servidor por la cuenta se conserva`() = conDrafts { drafts ->
        val repository = escenario(drafts, outbox("ACKED") { 0 })
        coEvery { api.getOrderDetail(venue, "real") } throws java.io.IOException("sin red")
        repository.refresh(venue).getOrThrow()
        assertTrue(repository.tables.value.single().primaryCheck!!.isProvisional)
    }
}
