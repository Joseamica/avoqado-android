package com.avoqado.pos.printing.data

import com.avoqado.pos.core.data.lan.KdsComanda
import com.avoqado.pos.core.data.lan.KdsLanProtocol
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.kds.data.local.EntregaKdsPendienteEntity
import com.avoqado.pos.kds.data.local.EntregasKdsPendientesDao
import com.avoqado.pos.printing.routing.ConsolidatedLine
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.TicketPlan
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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
    /** La libreta arranca vacía; las pruebas que la necesitan ocupada la re-stubbean. */
    private val pendientes = mockk<ComandasPendientesStore>(relaxed = true) {
        every { pendiente } returns MutableStateFlow<EstadoDeComanda.NoSalio?>(null)
    }
    /** La sucursal vigente (I1 de la revisión de la Task 7): las pruebas que la cambian a media pasada la re-stubbean. */
    private var sucursalVigente = "venue-1"
    private val secureStorage = mockk<SecureStorage>(relaxed = true) { every { venueId } answers { sucursalVigente } }
    private val replay = ReplayDeEntregasKds(dao, entregaPorWifi, despachador, cola, pendientes, secureStorage)

    private val trabajo = TrabajoPendiente(
        planes = emptyList(), config = PrintConfig(), orderNumber = "1234", orderType = "En tienda",
        serverName = null, comboNames = emptyMap(), venueId = "venue-1", orderId = null,
    )

    /** El fixture trae planes vacíos, así que su `entregaId` es `"<folio>|"`. */
    private fun fila(folio: String, creada: Long, soltada: Long? = null) = EntregaKdsPendienteEntity(
        entregaId = "$folio|", sourceKey = folio, venueId = "venue-1", stationId = "st_barra",
        mensajeJson = KdsLanProtocol.encode(
            KdsComanda(venueId = "venue-1", deviceId = "tablet-1", sourceKey = folio, stationId = "st_barra", orderNumber = "1234", orderType = "En tienda", createdAtMillis = creada, items = emptyList()),
        ),
        trabajoJson = Json.encodeToString(TrabajoPendiente.serializer(), trabajo),
        creadaEnMillis = creada,
        soltadaEnMillis = soltada,
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
        coVerify(exactly = 1) { dao.borrar("sale:reciente:st_barra|") }
        coVerify(exactly = 1) { dao.borrar("sale:vieja:st_barra|") }
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

    /**
     * Ronda 1 (I2/M4): sin papel no se marca y la fila SE QUEDA — la borra el papel cuando por fin sale
     * (`ComandaDispatcher.reintentar` → `cerrarPorPapel`). El trabajo va a la libreta, cuyo reloj insiste.
     */
    @Test
    fun `P1 si el papel NO salio no se marca - va a la libreta y la fila SE QUEDA hasta que el papel salga`() = runTest {
        coEvery { dao.delVenue("venue-1") } returns listOf(fila("sale:vieja:st_barra", 0))
        val noSalio = EstadoDeComanda.NoSalio(listOf("Barra"), "offline", "1234", trabajo)
        coEvery { despachador.reintentar(any(), any()) } returns noSalio

        replay.reproducirAlAbrir("venue-1", ahora = 20 * 60_000)

        coVerify(exactly = 0) { cola.enqueue(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { pendientes.guardar(noSalio, any()) }
        coVerify(exactly = 0) { dao.borrar(any()) }
    }

    /** M4: la libreta es de UNA ranura; si ya guarda otra comanda no se pisa (perdería su reintento). La fila espera. */
    @Test
    fun `con la libreta ocupada por otra comanda no se pisa - la fila se queda para la proxima apertura`() = runTest {
        every { pendientes.pendiente } returns MutableStateFlow(EstadoDeComanda.NoSalio(listOf("Cocina"), "offline", "7777", trabajo.copy(orderNumber = "7777")))
        coEvery { dao.delVenue("venue-1") } returns listOf(fila("sale:vieja:st_barra", 0))
        coEvery { despachador.reintentar(any(), any()) } returns EstadoDeComanda.NoSalio(listOf("Barra"), "offline", "1234", trabajo)

        replay.reproducirAlAbrir("venue-1", ahora = 20 * 60_000)

        coVerify(exactly = 0) { pendientes.guardar(any(), any()) }
        coVerify(exactly = 0) { dao.borrar(any()) }
    }

    /** Sin ninguna impresora no hay papel que reintentar: decidida, igual que en el despacho. */
    @Test
    fun `una estacion sin impresora se da por decidida - se borra sin marca`() = runTest {
        coEvery { dao.delVenue("venue-1") } returns listOf(fila("sale:vieja:st_barra", 0))
        coEvery { despachador.reintentar(any(), any()) } returns EstadoDeComanda.NoSalio(listOf("Barra"), "sin impresora", "1234", trabajo = null)

        replay.reproducirAlAbrir("venue-1", ahora = 20 * 60_000)

        coVerify(exactly = 0) { cola.enqueue(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { dao.borrar("sale:vieja:st_barra|") }
    }

    @Test
    fun `una fila ilegible se descarta sin tumbar el resto`() = runTest {
        coEvery { dao.delVenue("venue-1") } returns listOf(fila("sale:vieja:st_barra", 0).copy(trabajoJson = "{roto"))
        replay.reproducirAlAbrir("venue-1", ahora = 20 * 60_000)
        coVerify(exactly = 0) { despachador.reintentar(any(), any()) }
        coVerify(exactly = 1) { dao.borrar("sale:vieja:st_barra|") }
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
        coVerify(exactly = 0) { dao.borrar("sale:viva:st_barra|") }
        coVerify(exactly = 1) { dao.borrar("sale:muerta:st_barra|") }
    }

    /**
     * I1 + N2: una fila que truena (AIDL de la impresora, la base) no tumba la app ni frena a las demás; se queda en disco
     * y el SIGUIENTE tic la vuelve a intentar (la vigencia de 8 h le pone fin).
     */
    @Test
    fun `P1 una fila que truena no tumba la app ni a las demas, y el siguiente tic la vuelve a intentar`() = runTest {
        coEvery { dao.delVenue("venue-1") } returnsMany listOf(
            listOf(fila("sale:rota:st_barra", 0), fila("sale:buena:st_barra", 0)),
            listOf(fila("sale:rota:st_barra", 0)),
        )
        var llamadas = 0
        coEvery { despachador.reintentar(any(), any()) } coAnswers {
            llamadas++
            if (llamadas == 1) throw RuntimeException("AIDL no conectó") else EstadoDeComanda.Salio
        }

        replay.reproducirAlAbrir("venue-1", ahora = 20 * 60_000)
        coVerify(exactly = 0) { dao.borrar("sale:rota:st_barra|") }
        coVerify(exactly = 1) { dao.borrar("sale:buena:st_barra|") }

        replay.reproducirAlAbrir("venue-1", ahora = 21 * 60_000)
        coVerify(exactly = 3) { despachador.reintentar(any(), any()) }
        coVerify(exactly = 1) { dao.borrar("sale:rota:st_barra|") }
        coVerify(exactly = 2) { cola.enqueue("venue-1", "KDS_TICKET_MARK", any(), any(), false) }
    }

    /** N2: una fila de ESTE proceso que su despacho SOLTÓ (el papel no salió) la toma el replay; una viva, no. */
    @Test
    fun `P1 una fila soltada por un despacho de este proceso se retoma, y una viva no`() = runTest {
        coEvery { dao.delVenue("venue-1") } returns listOf(fila("sale:viva:st_barra", 5_000), fila("sale:suelta:st_barra", 5_000, soltada = 5_500))
        coEvery { entregaPorWifi.empujar(any(), any()) } returns false
        coEvery { despachador.reintentar(any(), any()) } returns EstadoDeComanda.Salio

        replay.reproducirAlAbrir("venue-1", ahora = 6_000, arranqueDelProceso = 4_000)

        coVerify(exactly = 1) { despachador.reintentar(any(), any()) }
        coVerify(exactly = 1) { dao.borrar("sale:suelta:st_barra|") }
        coVerify(exactly = 0) { dao.borrar("sale:viva:st_barra|") }
    }

    /**
     * N2: la impresora se cae y regresa SIN reabrir la app — el reloj (una pasada al arrancar y otra cada minuto) saca el
     * papel. La libreta está ocupada por otra comanda, así que el replay insiste por su cuenta. `iniciar` dos veces no
     * crea dos relojes. Sin `advanceUntilIdle`: el reloj es un lazo sin fin.
     */
    @Test
    fun `P1 impresora caida y de vuelta en el mismo proceso - el papel sale en el siguiente tic sin reabrir`() = runTest {
        val ahora = System.currentTimeMillis()
        every { pendientes.pendiente } returns MutableStateFlow(EstadoDeComanda.NoSalio(listOf("Cocina"), "offline", "7777", trabajo.copy(orderNumber = "7777")))
        coEvery { dao.delVenue("venue-1") } returns listOf(fila("sale:suelta:st_barra", ahora, soltada = ahora))
        coEvery { entregaPorWifi.empujar(any(), any()) } returns false
        coEvery { despachador.reintentar(any(), any()) } returnsMany listOf(
            EstadoDeComanda.NoSalio(listOf("Barra"), "offline", "1234", trabajo),
            EstadoDeComanda.Salio,
        )

        replay.iniciar(backgroundScope, "venue-1")
        replay.iniciar(backgroundScope, "venue-1")
        runCurrent()
        coVerify(exactly = 1) { despachador.reintentar(any(), any()) }
        coVerify(exactly = 0) { dao.borrar(any()) }

        advanceTimeBy(ReplayDeEntregasKds.INTERVALO_MS + 1)
        runCurrent()
        coVerify(exactly = 2) { despachador.reintentar(any(), any()) }
        coVerify(exactly = 1) { cola.enqueue("venue-1", "KDS_TICKET_MARK", any(), any(), false) }
        coVerify(exactly = 1) { dao.borrar("sale:suelta:st_barra|") }

        replay.detener()
        advanceTimeBy(ReplayDeEntregasKds.INTERVALO_MS + 1)
        runCurrent()
        coVerify(exactly = 2) { despachador.reintentar(any(), any()) }
    }

    @Test
    fun `si la base no se deja leer el replay no lanza`() = runTest {
        coEvery { dao.delVenue("venue-1") } throws IllegalStateException("disco lleno")
        replay.reproducirAlAbrir("venue-1", ahora = 20 * 60_000)
        coVerify(exactly = 0) { despachador.reintentar(any(), any()) }
    }

    /** I4: la comanda de ayer ya se resolvió; imprimirla sola en la cocina manda comida que nadie pidió (vigencia de la libreta). */
    @Test
    fun `P1 una entrega de mas de 8 h no se imprime ni se empuja - se borra`() = runTest {
        val ahora = 10L * 60 * 60 * 1_000
        coEvery { dao.delVenue("venue-1") } returns listOf(fila("sale:ayer:st_barra", ahora - 9L * 60 * 60 * 1_000))

        replay.reproducirAlAbrir("venue-1", ahora = ahora)

        coVerify(exactly = 0) { entregaPorWifi.empujar(any(), any()) }
        coVerify(exactly = 0) { despachador.reintentar(any(), any()) }
        coVerify(exactly = 0) { cola.enqueue(any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { dao.borrar("sale:ayer:st_barra|") }
    }

    /** I2: si la libreta ya tiene ESE papel (mismo venue, folio y plan), su reloj lo imprime: el replay no lo duplica. */
    @Test
    fun `P1 si la libreta ya tiene ese papel el replay no lo imprime otra vez - la fila espera`() = runTest {
        val plan = TicketPlan("st_barra", false, listOf(ConsolidatedLine("Café", 1, emptyList(), null, listOf("oi_2"))))
        val conPlan = trabajo.copy(planes = listOf(plan))
        every { pendientes.pendiente } returns MutableStateFlow(EstadoDeComanda.NoSalio(listOf("Barra"), "offline", "1234", conPlan))
        coEvery { dao.delVenue("venue-1") } returns listOf(
            fila("sale:vieja:st_barra", 0).copy(trabajoJson = Json.encodeToString(TrabajoPendiente.serializer(), conPlan)),
        )

        replay.reproducirAlAbrir("venue-1", ahora = 20 * 60_000)

        coVerify(exactly = 0) { despachador.reintentar(any(), any()) }
        coVerify(exactly = 0) { dao.borrar(any()) }
    }

    // MARK: - Ronda 3 (I1 de la revisión de la Task 7): la sucursal se revalida en CADA fila

    /**
     * El dueño abre en la sucursal A y cambia a B mientras la pasada de A sigue: la fila siguiente de A se SUSPENDE (ni se
     * imprime en la LAN de B, ni se refresca la config de impresión con la de A, ni se borra). La pasada termina; A la
     * retoma cuando vuelva a ser la vigente.
     */
    @Test
    fun `P1 si la sucursal cambia a media pasada la fila siguiente se suspende - ni se imprime ni se borra`() = runTest {
        coEvery { dao.delVenue("venue-1") } returns listOf(fila("sale:uno:st_barra", 0), fila("sale:dos:st_barra", 0))
        coEvery { despachador.reintentar(any(), any()) } coAnswers {
            sucursalVigente = "venue-2"
            EstadoDeComanda.Salio
        }

        replay.reproducirAlAbrir("venue-1", ahora = 20 * 60_000)

        coVerify(exactly = 1) { despachador.reintentar(any(), any()) }
        coVerify(exactly = 1) { dao.borrar("sale:uno:st_barra|") }
        coVerify(exactly = 0) { dao.borrar("sale:dos:st_barra|") }
        coVerify(exactly = 1) { cola.enqueue(any(), any(), any(), any(), any()) }
    }

    /** El cambio cae mientras se espera a la pantalla (hasta 3 s): tampoco se reimprime con la config de la otra sucursal. */
    @Test
    fun `P1 si la sucursal cambia mientras se espera a la pantalla no se reimprime`() = runTest {
        coEvery { dao.delVenue("venue-1") } returns listOf(fila("sale:reciente:st_barra", 1_000))
        coEvery { entregaPorWifi.empujar(any(), any()) } coAnswers {
            sucursalVigente = "venue-2"
            false
        }

        replay.reproducirAlAbrir("venue-1", ahora = 2_000)

        coVerify(exactly = 0) { despachador.reintentar(any(), any()) }
        coVerify(exactly = 0) { dao.borrar(any()) }
    }

    @Test
    fun `una pasada de una sucursal que ya no es la vigente no toca nada`() = runTest {
        sucursalVigente = "venue-2"
        coEvery { dao.delVenue("venue-1") } returns listOf(fila("sale:vieja:st_barra", 0))

        replay.reproducirAlAbrir("venue-1", ahora = 20 * 60_000)

        coVerify(exactly = 0) { despachador.reintentar(any(), any()) }
        coVerify(exactly = 0) { entregaPorWifi.empujar(any(), any()) }
        coVerify(exactly = 0) { dao.borrar(any()) }
    }
}
