package com.avoqado.pos.tables

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.database.CachedPayloadDao
import com.avoqado.pos.core.data.local.database.CachedPayloadEntity
import com.avoqado.pos.core.data.local.database.SyncIntentEntity
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import com.avoqado.pos.tables.data.*
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * D8 (hz:033d44ca:D8): sin red, la mesa cobrada por completo se suelta en el plano del aparato que la cobró (su `CLEAR_TABLE` va en la
 * cola de mesas) y se le puede abrir otra cuenta, que sale por la misma cola como visita nueva. Si el SERVIDOR rechaza su cobro o su
 * «liberar», la cuenta vuelve al plano, también sin red. Con un servidor que no entiende la visita nueva, todo como hoy.
 */
class MesaCobradaSinRedLibreTest {
    @get:Rule val main = MainDispatcherRule()
    private val venue = "venue-d8"
    private val mesa = DiningTable(id = "t7", number = "M7", shape = "ROUND", positionX = 0.5f, positionY = 0.5f)
    private val otraMesa = DiningTable(id = "t8", number = "M8", shape = "ROUND", positionX = 0.2f, positionY = 0.2f)
    private val cuentaA = OpenCheckSummary(id = "orden-a", orderNumber = "A-1", total = 45.0, version = 3)
    private val ocupada = mesa.copy(status = "OCCUPIED", openOrders = listOf(cuentaA))
    private val conCapacidad = TablesCapabilities(openTableNewVisit = true)

    private class Mundo(val repo: TableServiceRepository, val api: ApiService, val outbox: SyncOutbox, val filas: MutableMap<String, CachedPayloadEntity>,
        val estados: MutableMap<String, MutableStateFlow<String?>>, val trabajo: MutableMap<String, TrabajoSinResolver>,
        val encolados: MutableList<Pair<String, JsonObject>>, val promovidas: MutableMap<String, String>)

    private fun mundo(
        respuesta: TablesResponse,
        filas: MutableMap<String, CachedPayloadEntity> = mutableMapOf(),
        sesion: TableSession = TableSession(),
    ): Mundo {
        val dao = mockk<CachedPayloadDao>()
        coEvery { dao.get(any()) } answers { filas[firstArg()] }
        coEvery { dao.upsert(any()) } answers { val row = firstArg<CachedPayloadEntity>(); filas[row.cacheKey] = row }
        val api = mockk<ApiService>()
        coEvery { api.getTables(venue) } returns respuesta
        coEvery { api.clearTable(venue, any()) } throws java.net.ConnectException("sin red")
        val estados = mutableMapOf<String, MutableStateFlow<String?>>()
        val trabajo = mutableMapOf<String, TrabajoSinResolver>()
        val encolados = mutableListOf<Pair<String, JsonObject>>()
        val outbox = mockk<SyncOutbox>()
        coEvery { outbox.enqueue(venue, any(), any()) } answers {
            val id = "intent-${encolados.size + 1}"
            encolados += secondArg<String>() to thirdArg<JsonObject>()
            estados[id] = MutableStateFlow(SyncIntentEntity.STATUS_PENDING)
            id
        }
        every { outbox.observarEstado(any()) } answers { estados.getOrPut(firstArg()) { MutableStateFlow(null) } }
        coEvery { outbox.trabajoSinResolverDeLaOrden(venue, any()) } answers {
            val ids = secondArg<Set<String>>()
            ids.firstNotNullOfOrNull { trabajo[it] } ?: TrabajoSinResolver(0, 0, emptySet(), 0)
        }
        // El borrador lleva del id local al del servidor una vez que su OPEN_TABLE subió.
        val promovidas = mutableMapOf<String, String>()
        val drafts = mockk<TableRoundDraftStore>()
        every { drafts.loadResolved(venue, any()) } answers {
            (promovidas[secondArg<String>()] ?: secondArg<String>()) to TableRoundDraftStore.Snapshot()
        }
        val repo = TableServiceRepository(api, PayloadCache(dao), outbox, sesion, mockk(relaxed = true), drafts)
        return Mundo(repo, api, outbox, filas, estados, trabajo, encolados, promovidas)
    }

    private fun sesionDe(cuenta: OpenCheckSummary, provisional: Boolean = false) = TableSession().also {
        it.start(TableSession.Active(tableId = "t7", tableNumber = "M7", areaName = null, orderId = cuenta.id, orderNumber = cuenta.orderNumber,
            version = cuenta.version, totalCents = 4500, mode = TableSession.Mode.PAYING, isProvisional = provisional,
            localOrderId = if (provisional) cuenta.id else null))
    }

    private fun prueba(block: suspend TestScope.() -> Unit) = runTest { block() }

    /** El cobro de A ya va en la cola de mesas (lo que pasa al confirmar el efectivo sin red). */
    private fun Mundo.cobroEnCamino(vararg ids: String) { ids.forEach { trabajo[it] = TrabajoSinResolver(1, 0, setOf("PAY_CASH"), 4500) } }

    @Test fun `sin red, la mesa cobrada se suelta en el plano y el liberar va a la cola`() = prueba {
        val m = mundo(TablesResponse(data = listOf(ocupada, otraMesa), capabilities = conCapacidad), sesion = sesionDe(cuentaA))
        m.repo.refresh(venue).getOrThrow()
        m.cobroEnCamino("orden-a")

        val soltada = m.repo.liberarTrasCobro(venue, "t7", "orden-a", setOf("orden-a")) { true }.getOrThrow()

        assertTrue(soltada)
        assertEquals(listOf("CLEAR_TABLE"), m.encolados.map { it.first })
        assertEquals(JsonPrimitive("t7"), m.encolados.single().second["tableId"])
        val m7 = m.repo.tables.value.first { it.id == "t7" }
        assertTrue("La mesa cobrada queda libre en este aparato", m7.isAvailable)
        assertTrue(m.repo.abrirComoVisitaNueva(venue, "t7"))
        assertFalse(m.repo.abrirComoVisitaNueva(venue, "t8"))
        assertNull("Sin versión: el sondeo vuelve a pedir /tables", m.repo.tablesVersion)
    }

    @Test fun `con un servidor que no anuncia la visita nueva, la mesa se queda como hoy`() = prueba {
        val m = mundo(TablesResponse(data = listOf(ocupada)), sesion = sesionDe(cuentaA))
        m.repo.refresh(venue).getOrThrow()

        val soltada = m.repo.liberarTrasCobro(venue, "t7", "orden-a", setOf("orden-a")) { true }.getOrThrow()

        assertFalse(soltada)
        assertEquals("El liberar igual va a la cola (D75)", listOf("CLEAR_TABLE"), m.encolados.map { it.first })
        assertTrue(m.repo.tables.value.single().isOccupied)
        assertFalse(m.repo.abrirComoVisitaNueva(venue, "t7"))
    }

    @Test fun `si quien llama dice que hay dinero en la cola de cobros, no se suelta`() = prueba {
        val m = mundo(TablesResponse(data = listOf(ocupada), capabilities = conCapacidad), sesion = sesionDe(cuentaA))
        m.repo.refresh(venue).getOrThrow()

        assertFalse(m.repo.liberarTrasCobro(venue, "t7", "orden-a", setOf("orden-a")) { false }.getOrThrow())
        assertTrue(m.repo.tables.value.single().isOccupied)
    }

    @Test fun `en linea el liberar va directo y no se escribe marca`() = prueba {
        val m = mundo(TablesResponse(data = listOf(ocupada), capabilities = conCapacidad), sesion = sesionDe(cuentaA))
        coEvery { m.api.clearTable(venue, "t7") } returns SimpleSuccessResponse()
        m.repo.refresh(venue).getOrThrow()

        assertFalse(m.repo.liberarTrasCobro(venue, "t7", "orden-a", setOf("orden-a")) { error("no se pregunta") }.getOrThrow())
        assertTrue(m.encolados.isEmpty())
        assertFalse(m.repo.abrirComoVisitaNueva(venue, "t7"))
    }

    @Test fun `un rechazo de negocio del liberar sube tal cual y no encola`() = prueba {
        val m = mundo(TablesResponse(data = listOf(ocupada), capabilities = conCapacidad), sesion = sesionDe(cuentaA))
        coEvery { m.api.clearTable(venue, "t7") } throws retrofit2.HttpException(
            retrofit2.Response.error<Any>(409, "{}".toResponseBody(null)))
        m.repo.refresh(venue).getOrThrow()

        assertTrue(m.repo.liberarTrasCobro(venue, "t7", "orden-a", setOf("orden-a")) { true }.isFailure)
        assertTrue(m.encolados.isEmpty())
        assertTrue(m.repo.tables.value.single().isOccupied)
    }

    @Test fun `otra cuenta de la misma mesa sigue - se quita sólo la cobrada`() = prueba {
        val otra = OpenCheckSummary(id = "orden-c", orderNumber = "C-1", total = 30.0)
        val m = mundo(TablesResponse(data = listOf(ocupada.copy(openOrders = listOf(cuentaA, otra))), capabilities = conCapacidad),
            sesion = sesionDe(cuentaA))
        m.repo.refresh(venue).getOrThrow()

        assertTrue(m.repo.liberarTrasCobro(venue, "t7", "orden-a", setOf("orden-a")) { true }.getOrThrow())
        assertEquals(listOf("orden-c"), m.repo.tables.value.single().openOrders.map { it.id })
        assertTrue(m.repo.tables.value.single().isOccupied)
    }

    @Test fun `con red de vuelta y la cola sin subir, una respuesta que aún trae A no la vuelve a pintar`() = prueba {
        val m = mundo(TablesResponse(data = listOf(ocupada), capabilities = conCapacidad, tablesVersion = "v1"), sesion = sesionDe(cuentaA))
        m.repo.refresh(venue).getOrThrow()
        m.cobroEnCamino("orden-a")
        m.repo.liberarTrasCobro(venue, "t7", "orden-a", setOf("orden-a")) { true }.getOrThrow()

        m.repo.refresh(venue).getOrThrow() // el servidor todavía trae A

        assertTrue(m.repo.tables.value.single().isAvailable)
        assertNull("Mientras la marca viva, sin versión guardada (Codex r1 #3)", m.repo.tablesVersion)
        assertTrue(m.repo.abrirComoVisitaNueva(venue, "t7"))
    }

    @Test fun `la cola subió todo - la marca se borra y manda el servidor`() = prueba {
        val m = mundo(TablesResponse(data = listOf(ocupada), capabilities = conCapacidad, tablesVersion = "v1"), sesion = sesionDe(cuentaA))
        m.repo.refresh(venue).getOrThrow()
        m.repo.liberarTrasCobro(venue, "t7", "orden-a", setOf("orden-a")) { true }.getOrThrow()
        m.estados["intent-1"]!!.value = SyncIntentEntity.STATUS_ACKED // el liberar subió; el cobro también (sin trabajo)
        coEvery { m.api.getTables(venue) } returns TablesResponse(data = listOf(mesa), capabilities = conCapacidad, tablesVersion = "v2")

        m.repo.refresh(venue).getOrThrow()

        assertTrue(m.repo.tables.value.single().isAvailable)
        assertFalse(m.repo.abrirComoVisitaNueva(venue, "t7"))
        assertEquals("v2", m.repo.tablesVersion)
    }

    @Test fun `el servidor rechazó el cobro de A - con red, A vuelve al plano`() = prueba {
        val m = mundo(TablesResponse(data = listOf(ocupada), capabilities = conCapacidad), sesion = sesionDe(cuentaA))
        m.repo.refresh(venue).getOrThrow()
        m.cobroEnCamino("orden-a")
        m.repo.liberarTrasCobro(venue, "t7", "orden-a", setOf("orden-a")) { true }.getOrThrow()
        m.trabajo["orden-a"] = TrabajoSinResolver(1, 1, setOf("PAY_CASH"), 4500) // rechazado

        m.repo.refresh(venue).getOrThrow()

        assertEquals(listOf("orden-a"), m.repo.tables.value.single().openOrders.map { it.id })
        assertFalse(m.repo.abrirComoVisitaNueva(venue, "t7"))
    }

    @Test fun `el servidor rechazó el cobro y el siguiente GET falla - A vuelve al plano desde la copia (Codex r1 #4)`() = prueba {
        val m = mundo(TablesResponse(data = listOf(ocupada), capabilities = conCapacidad), sesion = sesionDe(cuentaA))
        m.repo.refresh(venue).getOrThrow()
        m.cobroEnCamino("orden-a")
        m.repo.liberarTrasCobro(venue, "t7", "orden-a", setOf("orden-a")) { true }.getOrThrow()
        m.trabajo["orden-a"] = TrabajoSinResolver(1, 1, setOf("PAY_CASH"), 4500)
        coEvery { m.api.getTables(venue) } throws java.net.ConnectException("se volvió a caer")

        assertTrue(m.repo.refresh(venue).isFailure)

        val m7 = m.repo.tables.value.single()
        assertEquals(listOf("orden-a"), m7.openOrders.map { it.id })
        assertTrue(m7.isOccupied)
        assertFalse(m.repo.abrirComoVisitaNueva(venue, "t7"))
    }

    @Test fun `Codex r2 #1 - el rechazo vuelve a pintar la mesa sin ningún refresco (lo dispara la cola o el sondeo)`() = prueba {
        val m = mundo(TablesResponse(data = listOf(ocupada), capabilities = conCapacidad), sesion = sesionDe(cuentaA))
        m.repo.refresh(venue).getOrThrow()
        m.cobroEnCamino("orden-a")
        m.repo.liberarTrasCobro(venue, "t7", "orden-a", setOf("orden-a")) { true }.getOrThrow()
        m.trabajo["orden-a"] = TrabajoSinResolver(1, 1, setOf("PAY_CASH"), 4500)

        m.repo.reconciliarMarcasSinRed(venue)

        assertEquals(listOf("orden-a"), m.repo.tables.value.single().openOrders.map { it.id })
        coVerify(exactly = 1) { m.api.getTables(venue) } // sólo el del arranque
    }

    @Test fun `Codex r2 #3 - sólo se rechazó el liberar (el cobro entró) - no se vuelve a pintar una cuenta pagada`() = prueba {
        val m = mundo(TablesResponse(data = listOf(ocupada), capabilities = conCapacidad), sesion = sesionDe(cuentaA))
        m.repo.refresh(venue).getOrThrow()
        m.repo.liberarTrasCobro(venue, "t7", "orden-a", setOf("orden-a")) { true }.getOrThrow()
        m.estados["intent-1"]!!.value = SyncIntentEntity.STATUS_REJECTED // el cobro subió bien (sin trabajo de la cuenta)

        m.repo.reconciliarMarcasSinRed(venue)
        assertTrue("Sin red: la cuenta ya pagada no regresa", m.repo.tables.value.single().isAvailable)

        coEvery { m.api.getTables(venue) } returns TablesResponse(data = listOf(mesa), capabilities = conCapacidad, tablesVersion = "v3")
        m.repo.refresh(venue).getOrThrow()
        assertTrue("Con red manda el servidor: libre", m.repo.tables.value.single().isAvailable)
        assertFalse(m.repo.abrirComoVisitaNueva(venue, "t7"))
    }

    @Test fun `Codex r2 #2 - otra caja movió A a otra mesa - allá se ve ocupada y esta mesa queda libre`() = prueba {
        val m = mundo(TablesResponse(data = listOf(ocupada, otraMesa), capabilities = conCapacidad), sesion = sesionDe(cuentaA))
        m.repo.refresh(venue).getOrThrow()
        m.cobroEnCamino("orden-a")
        m.repo.liberarTrasCobro(venue, "t7", "orden-a", setOf("orden-a")) { true }.getOrThrow()
        coEvery { m.api.getTables(venue) } returns TablesResponse(capabilities = conCapacidad,
            data = listOf(mesa, otraMesa.copy(status = "OCCUPIED", openOrders = listOf(cuentaA))))

        m.repo.refresh(venue).getOrThrow()

        assertTrue(m.repo.tables.value.first { it.id == "t7" }.isAvailable)
        assertEquals(listOf("orden-a"), m.repo.tables.value.first { it.id == "t8" }.openOrders.map { it.id })
        assertFalse(m.repo.abrirComoVisitaNueva(venue, "t8"))
    }

    @Test fun `Codex r2 #4 - el servidor dejó de anunciar la visita nueva - las marcas se anulan y la mesa se ve como hoy`() = prueba {
        val m = mundo(TablesResponse(data = listOf(ocupada), capabilities = conCapacidad), sesion = sesionDe(cuentaA))
        m.repo.refresh(venue).getOrThrow()
        m.cobroEnCamino("orden-a")
        m.repo.liberarTrasCobro(venue, "t7", "orden-a", setOf("orden-a")) { true }.getOrThrow()
        coEvery { m.api.getTables(venue) } returns TablesResponse(data = listOf(ocupada))

        m.repo.refresh(venue).getOrThrow()

        assertTrue(m.repo.tables.value.single().isOccupied)
        assertFalse(m.repo.abrirComoVisitaNueva(venue, "t7"))
    }

    @Test fun `la provisional rechazada no se duplica cuando el servidor ya la trae con su id real`() = prueba {
        val local = OpenCheckSummary(id = "local-a", orderNumber = "L-1", total = 45.0, isProvisional = true)
        val m = mundo(TablesResponse(data = listOf(mesa), capabilities = conCapacidad), sesion = sesionDe(local, provisional = true))
        m.repo.refresh(venue).getOrThrow()
        m.repo.markTableOccupiedLocally("t7")
        assertTrue(m.repo.liberarTrasCobro(venue, "t7", "local-a", setOf("local-a")) { true }.getOrThrow())
        assertTrue(m.repo.tables.value.single().isAvailable)
        // El OPEN subió (local-a → real-a) y el cobro se rechazó; el servidor trae la cuenta con su id REAL.
        m.promovidas["local-a"] = "real-a"
        m.trabajo["local-a"] = TrabajoSinResolver(1, 1, setOf("PAY_CASH"), 4500)
        coEvery { m.api.getTables(venue) } returns TablesResponse(capabilities = conCapacidad, data = listOf(mesa.copy(status = "OCCUPIED",
            openOrders = listOf(OpenCheckSummary(id = "real-a", orderNumber = "A-9", total = 45.0)))))

        m.repo.refresh(venue).getOrThrow()

        assertEquals(listOf("real-a"), m.repo.tables.value.single().openOrders.map { it.id })
    }

    @Test fun `la provisional rechazada que el servidor no conoce vuelve desde la copia`() = prueba {
        val local = OpenCheckSummary(id = "local-b", orderNumber = "L-2", total = 45.0, isProvisional = true)
        val m = mundo(TablesResponse(data = listOf(mesa), capabilities = conCapacidad), sesion = sesionDe(local, provisional = true))
        m.repo.refresh(venue).getOrThrow()
        m.repo.markTableOccupiedLocally("t7")
        m.repo.liberarTrasCobro(venue, "t7", "local-b", setOf("local-b")) { true }.getOrThrow()
        m.trabajo["local-b"] = TrabajoSinResolver(1, 1, setOf("OPEN_TABLE"), 0) // su apertura se rechazó

        m.repo.refresh(venue).getOrThrow()

        assertEquals(listOf("local-b"), m.repo.tables.value.single().openOrders.map { it.id })
    }

    @Test fun `tras reiniciar la app sin red, el plano guardado ya no trae A y recuerda la marca`() = prueba {
        val filas = mutableMapOf<String, CachedPayloadEntity>()
        val m = mundo(TablesResponse(data = listOf(ocupada), capabilities = conCapacidad), filas, sesionDe(cuentaA))
        m.repo.refresh(venue).getOrThrow()
        m.cobroEnCamino("orden-a")
        m.repo.liberarTrasCobro(venue, "t7", "orden-a", setOf("orden-a")) { true }.getOrThrow()

        val otra = mundo(TablesResponse(), filas)
        otra.cobroEnCamino("orden-a")
        otra.estados["intent-1"] = MutableStateFlow(SyncIntentEntity.STATUS_PENDING)
        coEvery { otra.api.getTables(venue) } throws java.net.ConnectException("sin red")
        otra.repo.refresh(venue)

        assertTrue(otra.repo.tables.value.single().isAvailable)
        assertTrue(otra.repo.abrirComoVisitaNueva(venue, "t7"))
    }
}
