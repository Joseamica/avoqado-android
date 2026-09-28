package com.avoqado.pos.printing.data

import com.avoqado.pos.core.data.lan.KdsComanda
import com.avoqado.pos.core.data.lan.KdsLanProtocol
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.local.EntregaKdsPendienteEntity
import com.avoqado.pos.kds.data.local.EntregasKdsPendientesDao
import com.avoqado.pos.printing.routing.PrintConfig
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Al abrir la app: las entregas de menos de 10 min se reintentan UNA vez por WiFi; el resto sale en papel y se marca (D7).
 * Las filas de estas pruebas traen `creadaEnMillis` chicos (< al reloj real con que nace `replay`): pasan la guarda del
 * despacho vivo sin decir nada; la prueba de la guarda pasa `arranqueDelProceso` explícito.
 */
class ReplayDeEntregasKdsTest {

    private val dao = mockk<EntregasKdsPendientesDao>(relaxed = true)
    private val entregaPorWifi = mockk<EntregaPorWifi>()
    private val despachador = mockk<ComandaDispatcher>()
    private val cola = mockk<SyncOutbox>(relaxed = true)
    private val pendientes = mockk<ComandasPendientesStore>(relaxed = true)
    private val replay = ReplayDeEntregasKds(dao, entregaPorWifi, despachador, cola, pendientes)

    private val trabajo = TrabajoPendiente(
        planes = emptyList(), config = PrintConfig(), orderNumber = "1234", orderType = "En tienda",
        serverName = null, comboNames = emptyMap(), venueId = "venue-1", orderId = null,
    )

    private fun fila(folio: String, creada: Long) = EntregaKdsPendienteEntity(
        sourceKey = folio, venueId = "venue-1", stationId = "st_barra",
        mensajeJson = KdsLanProtocol.encode(
            KdsComanda(venueId = "venue-1", deviceId = "tablet-1", sourceKey = folio, stationId = "st_barra", orderNumber = "1234", orderType = "En tienda", createdAtMillis = creada, items = emptyList()),
        ),
        trabajoJson = Json.encodeToString(TrabajoPendiente.serializer(), trabajo),
        creadaEnMillis = creada,
    )

    @Test
    fun `P1 al abrir una entrega reciente se reintenta por WiFi y una vieja sale en papel y se marca`() = runTest {
        val ahora = 1_000_000L
        coEvery { dao.delVenue("venue-1") } returns listOf(fila("sale:reciente:st_barra", ahora - 5 * 60_000), fila("sale:vieja:st_barra", ahora - 20 * 60_000))
        coEvery { entregaPorWifi.empujar(match { it.sourceKey == "sale:reciente:st_barra" }, any()) } returns true
        coEvery { despachador.reintentar(any(), any()) } returns EstadoDeComanda.Salio
        val marca = slot<JsonObject>()
        coEvery { cola.enqueue("venue-1", "KDS_TICKET_MARK", capture(marca), any(), false) } returns "m-1"

        replay.reproducirAlAbrir("venue-1", ahora)

        coVerify(exactly = 1) { entregaPorWifi.empujar(any(), ReplayDeEntregasKds.ESPERA_PANTALLAS_MS) }
        coVerify(exactly = 1) { despachador.reintentar(any(), any()) }
        assertEquals("sale:vieja:st_barra", marca.captured["sourceKey"]!!.jsonPrimitive.content)
        assertEquals("FALLBACK_PRINTED", marca.captured["action"]!!.jsonPrimitive.content)
        assertEquals("1234", marca.captured["label"]!!.jsonPrimitive.content)
        coVerify(exactly = 1) { dao.borrar("sale:reciente:st_barra") }
        coVerify(exactly = 1) { dao.borrar("sale:vieja:st_barra") }
    }

    @Test
    fun `una reciente que NO acusa tambien sale en papel`() = runTest {
        coEvery { dao.delVenue("venue-1") } returns listOf(fila("sale:reciente:st_barra", 1_000))
        coEvery { entregaPorWifi.empujar(any(), any()) } returns false
        coEvery { despachador.reintentar(any(), any()) } returns EstadoDeComanda.Salio

        replay.reproducirAlAbrir("venue-1", ahora = 2_000)

        coVerify(exactly = 1) { despachador.reintentar(any(), any()) }
        coVerify(exactly = 1) { cola.enqueue("venue-1", "KDS_TICKET_MARK", any(), any(), false) }
    }

    @Test
    fun `P1 si el papel NO salio no se marca - se guarda en el almacen de comandas pendientes y su reloj insiste`() = runTest {
        coEvery { dao.delVenue("venue-1") } returns listOf(fila("sale:vieja:st_barra", 0))
        val noSalio = EstadoDeComanda.NoSalio(listOf("Barra"), "offline", "1234", trabajo)
        coEvery { despachador.reintentar(any(), any()) } returns noSalio

        replay.reproducirAlAbrir("venue-1", ahora = 20 * 60_000)

        coVerify(exactly = 0) { cola.enqueue(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { pendientes.guardar(noSalio, any()) }
        coVerify(exactly = 1) { dao.borrar("sale:vieja:st_barra") }
    }

    @Test
    fun `una fila ilegible se descarta sin tumbar el resto`() = runTest {
        coEvery { dao.delVenue("venue-1") } returns listOf(fila("sale:vieja:st_barra", 0).copy(trabajoJson = "{roto"))
        replay.reproducirAlAbrir("venue-1", ahora = 20 * 60_000)
        coVerify(exactly = 0) { despachador.reintentar(any(), any()) }
        coVerify(exactly = 1) { dao.borrar("sale:vieja:st_barra") }
    }

    /** La fila de un despacho EN CURSO de este proceso (creada después de nacer el replay) no se toca: la cierra su `finally`. */
    @Test
    fun `P1 una fila de un despacho vivo de este proceso no se toca - ni se empuja ni se reimprime ni se borra`() = runTest {
        coEvery { dao.delVenue("venue-1") } returns listOf(fila("sale:viva:st_barra", 5_000), fila("sale:muerta:st_barra", 3_000))
        coEvery { entregaPorWifi.empujar(any(), any()) } returns true

        replay.reproducirAlAbrir("venue-1", ahora = 6_000, arranqueDelProceso = 4_000)

        coVerify(exactly = 1) { entregaPorWifi.empujar(match { it.sourceKey == "sale:muerta:st_barra" }, any()) }
        coVerify(exactly = 0) { entregaPorWifi.empujar(match { it.sourceKey == "sale:viva:st_barra" }, any()) }
        coVerify(exactly = 0) { despachador.reintentar(any(), any()) }
        coVerify(exactly = 0) { dao.borrar("sale:viva:st_barra") }
        coVerify(exactly = 1) { dao.borrar("sale:muerta:st_barra") }
    }
}
