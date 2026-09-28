package com.avoqado.pos.tables.presentation

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.ComandasPendientesStore
import com.avoqado.pos.printing.data.EstadoDeComanda
import com.avoqado.pos.printing.data.ReintentoDeComanda
import com.avoqado.pos.printing.data.TrabajoPendiente
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.StationInfo
import com.avoqado.pos.tables.data.AddOrderItemRequest
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.tables.data.UpdatedOrder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * La ronda de mesa con llave (spec 2026-09-27 §5): la ronda en línea y su red de seguridad llevan las MISMAS llaves
 * `sync:<roundKey>:<idx>`; el intent es UNO (id = `roundKey`) y nunca se encola una segunda copia. Así, si la respuesta
 * se pierde y la ronda se reproduce, el servidor la deduplica y arma UNA comanda `round:<roundKey>`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableOrderRondaConLlaveTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val repository = mockk<TableServiceRepository>(relaxed = true)
    private val syncOutbox = mockk<SyncOutbox>(relaxed = true)
    private val tableSession = TableSession()
    private val payloads = mutableListOf<JsonObject>()
    private val ids = mutableListOf<String>()
    private val enviados = mutableListOf<List<AddOrderItemRequest>>()
    private val dispatcher = mockk<ComandaDispatcher>(relaxed = true)

    private fun vm(
        store: ComandasPendientesStore = mockk(relaxed = true),
        despachador: ComandaDispatcher = dispatcher,
        secureStorage: SecureStorage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns "venue-1" },
    ): TableOrderViewModel {
        every { repository.tables } returns MutableStateFlow(emptyList())
        every { repository.ownership } returns MutableStateFlow(TableServiceRepository.TableOwnership())
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(OrderDetail(id = "o1", orderNumber = "ORD-1", items = emptyList()))
        every { syncOutbox.acks } returns MutableSharedFlow()
        every { syncOutbox.pendingCount } returns MutableStateFlow(0)
        coEvery { syncOutbox.enqueue(any(), any(), capture(payloads), capture(ids), any()) } answers { arg(3) }
        val connectivity = mockk<ConnectivityMonitor>(relaxed = true)
        every { connectivity.isConnected } returns MutableStateFlow(true)
        val printConfig = mockk<PrintConfigRepository>(relaxed = true)
        every { printConfig.getCurrentConfig() } returns PrintConfig()
        tableSession.start(
            TableSession.Active(
                tableId = "t1", tableNumber = "5", areaName = null, orderId = "o1", orderNumber = "ORD-1",
                version = 3, totalCents = 0, mode = TableSession.Mode.ORDERING,
            ),
        )
        return TableOrderViewModel(
            repository = repository, tableSession = tableSession, printConfigRepository = printConfig,
            comandaPrinter = mockk(relaxed = true), printerService = mockk(relaxed = true), secureStorage = secureStorage,
            syncOutbox = syncOutbox, productsRepository = mockk(relaxed = true), connectivityMonitor = connectivity,
            timeEntryRepository = mockk(relaxed = true),
            comandaDispatcher = despachador, comandasPendientesStore = store,
        ).apply {
            addCustomAmount("Pan", 3000)
            addCustomAmount("Café", 4500)
        }
    }

    private fun llavesDe(payload: JsonObject) = payload["items"]!!.jsonArray.map { it.jsonObject["externalId"]!!.jsonPrimitive.content }

    @Test
    fun `P1 la ronda en linea lleva las MISMAS llaves que su red de seguridad y con exito la descarta`() = runTest {
        coEvery { repository.addRound(any(), any(), capture(enviados), any()) } returns Result.success(UpdatedOrder(id = "o1", version = 4))
        var ok: Boolean? = null
        val vm = vm()

        vm.sendRound { exito, _ -> ok = exito }
        advanceUntilIdle()

        val llave = ids.single()
        assertEquals(listOf("sync:$llave:0", "sync:$llave:1"), llavesDe(payloads.single()))
        assertEquals(listOf("sync:$llave:0", "sync:$llave:1"), enviados.single().map { it.externalId })
        coVerifyOrder {
            syncOutbox.enqueue("venue-1", "ADD_ITEMS", any(), llave, true)
            repository.addRound("venue-1", "o1", any(), 3)
            syncOutbox.descartar("venue-1", llave)
        }
        coVerify(exactly = 0) { syncOutbox.soltar(any(), any()) }
        assertEquals(true, ok)
    }

    @Test
    fun `P1 sin red la ronda NO se encola dos veces - se suelta la misma y las lineas quedan con su llave`() = runTest {
        coEvery { repository.addRound(any(), any(), any(), any()) } returns Result.failure(IOException("sin red"))
        var ok: Boolean? = null
        var mensaje: String? = null
        val vm = vm()

        vm.sendRound { exito, msg -> ok = exito; mensaje = msg }
        advanceUntilIdle()

        val llave = ids.single()
        coVerify(exactly = 1) { syncOutbox.enqueue(any(), any(), any(), any(), any()) }
        coVerify { syncOutbox.soltar("venue-1", llave) }
        coVerify(exactly = 0) { syncOutbox.descartar(any(), any()) }
        assertEquals(listOf("sync:$llave:0", "sync:$llave:1"), vm.queued.value.map { it.externalId })
        assertEquals(true, ok)
        assertEquals(TableOrderViewModel.MENSAJE_RONDA_SIN_CONEXION, mensaje)
    }

    @Test
    fun `un rechazo DEFINITIVO (422) descarta la red de seguridad y deja las lineas para reintentar`() = runTest {
        coEvery { repository.addRound(any(), any(), any(), any()) } returns
            Result.failure(HttpException(Response.error<Any>(422, "{}".toResponseBody(null))))
        var ok: Boolean? = null
        val vm = vm()

        vm.sendRound { exito, _ -> ok = exito }
        advanceUntilIdle()

        coVerify { syncOutbox.descartar("venue-1", ids.single()) }
        coVerify(exactly = 0) { syncOutbox.soltar(any(), any()) }
        assertEquals(2, vm.pending.value.size)
        assertEquals(false, ok)
    }

    @Test
    fun `P1 un 409 NO descarta - la ronda pudo quedar escrita a medias, se suelta con la MISMA llave`() = runTest {
        coEvery { repository.addRound(any(), any(), any(), any()) } returns
            Result.failure(HttpException(Response.error<Any>(409, "{}".toResponseBody(null))))
        var ok: Boolean? = null
        var mensaje: String? = null
        val vm = vm()

        vm.sendRound { exito, msg -> ok = exito; mensaje = msg }
        advanceUntilIdle()

        val llave = ids.single()
        coVerify(exactly = 1) { syncOutbox.enqueue(any(), any(), any(), any(), any()) }
        coVerify { syncOutbox.soltar("venue-1", llave) }
        coVerify(exactly = 0) { syncOutbox.descartar(any(), any()) }
        assertEquals(listOf("sync:$llave:0", "sync:$llave:1"), vm.queued.value.map { it.externalId })
        assertEquals(true, ok)
        assertEquals(TableOrderViewModel.MENSAJE_RONDA_INCIERTA, mensaje)
    }

    @Test
    fun `P1 un 500 tampoco descarta y NO dice Sin conexion - se suelta con la MISMA llave`() = runTest {
        coEvery { repository.addRound(any(), any(), any(), any()) } returns
            Result.failure(HttpException(Response.error<Any>(500, "{}".toResponseBody(null))))
        var mensaje: String? = null
        val vm = vm()

        vm.sendRound { _, msg -> mensaje = msg }
        advanceUntilIdle()

        val llave = ids.single()
        coVerify { syncOutbox.soltar("venue-1", llave) }
        coVerify(exactly = 0) { syncOutbox.descartar(any(), any()) }
        assertEquals(TableOrderViewModel.MENSAJE_RONDA_INCIERTA, mensaje)
    }

    // MARK: - Etapa 3 del KDS (fase 3.4): presupuesto corto, un reintento tras 409 y el despachador del mostrador

    private val cafe = Product(id = "prod-cafe", name = "Café", priceValue = 30.0)
    private fun http(codigo: Int, cuerpo: String = "{}") = HttpException(Response.error<Any>(codigo, cuerpo.toResponseBody(null)))

    @Test
    fun `P1 una ronda colgada se rinde a los 15 s - la suelta con su llave y dice Sin conexion`() = runTest {
        coEvery { repository.addRound(any(), any(), any(), any()) } coAnswers {
            delay(60_000)
            Result.success(UpdatedOrder(id = "o1", version = 4))
        }
        var mensaje: String? = null
        val vm = vm()

        vm.sendRound { _, msg -> mensaje = msg }
        advanceUntilIdle()

        assertEquals(TableOrderViewModel.MENSAJE_RONDA_SIN_CONEXION, mensaje)
        coVerify { syncOutbox.soltar("venue-1", ids.single()) }
        // El reloj virtual es determinista: se rinde EXACTAMENTE en el presupuesto, no «antes del minuto».
        assertEquals(TableOrderViewModel.PRESUPUESTO_RONDA_MS, currentTime)
    }

    /** m-5 de la revisión final: tras el 409 hay UN solo reintento — un bucle retendría la cola del aparato. */
    @Test
    fun `P1 dos 409 seguidos - UN solo reintento y se suelta con la MISMA llave`() = runTest {
        coEvery { repository.addRound(any(), any(), capture(enviados), any()) } returnsMany listOf(
            Result.failure(http(409)),
            Result.failure(http(409)),
            Result.success(UpdatedOrder(id = "o1", version = 9)),
        )
        var mensaje: String? = null
        val vm = vm()
        coEvery { repository.getOrderDetail(any(), any()) } returns
            Result.success(OrderDetail(id = "o1", orderNumber = "ORD-1", items = emptyList(), version = 7))

        vm.sendRound { _, msg -> mensaje = msg }
        advanceUntilIdle()

        coVerify(exactly = 2) { repository.addRound(any(), any(), any(), any()) }
        val llave = ids.single()
        assertEquals(enviados[0].map { it.externalId }, enviados[1].map { it.externalId })
        coVerify { syncOutbox.soltar("venue-1", llave) }
        coVerify(exactly = 0) { syncOutbox.descartar(any(), any()) }
        assertEquals(listOf("sync:$llave:0", "sync:$llave:1"), vm.queued.value.map { it.externalId })
        assertEquals(TableOrderViewModel.MENSAJE_RONDA_INCIERTA, mensaje)
    }

    @Test
    fun `P1 un 409 reintenta UNA vez con la version fresca y la MISMA llave`() = runTest {
        val versiones = mutableListOf<Int>()
        coEvery { repository.addRound(any(), any(), capture(enviados), capture(versiones)) } returnsMany listOf(
            Result.failure(http(409)),
            Result.success(UpdatedOrder(id = "o1", version = 8)),
        )
        var ok: Boolean? = null
        val vm = vm()
        coEvery { repository.getOrderDetail(any(), any()) } returns
            Result.success(OrderDetail(id = "o1", orderNumber = "ORD-1", items = emptyList(), version = 7))

        vm.sendRound { exito, _ -> ok = exito }
        advanceUntilIdle()

        assertEquals(listOf(3, 7), versiones)
        assertEquals(enviados[0].map { it.externalId }, enviados[1].map { it.externalId })
        coVerify { syncOutbox.descartar("venue-1", ids.single()) }
        coVerify(exactly = 0) { syncOutbox.soltar(any(), any()) }
        assertEquals(true, ok)
    }

    @Test
    fun `P1 un 409 y luego cuenta pagada descarta y NO imprime`() = runTest {
        coEvery { repository.addRound(any(), any(), any(), any()) } returnsMany listOf(
            Result.failure(http(409)),
            Result.failure(http(400, """{"message":"Cannot add items to a paid order"}""")),
        )
        var ok: Boolean? = null
        val vm = vm()
        coEvery { repository.getOrderDetail(any(), any()) } returns
            Result.success(OrderDetail(id = "o1", orderNumber = "ORD-1", items = emptyList(), version = 9))
        vm.addProduct(cafe)

        vm.sendRound { exito, _ -> ok = exito }
        advanceUntilIdle()

        assertEquals(false, ok)
        coVerify { syncOutbox.descartar("venue-1", ids.single()) }
        verify(exactly = 0) { dispatcher.despacharEnFondo(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `P1 cuenta ya cobrada en otro aparato se avisa como ERROR en espanol y las lineas se quedan`() = runTest {
        coEvery { repository.addRound(any(), any(), any(), any()) } returnsMany listOf(
            Result.failure(http(409)),
            Result.failure(http(400, """{"message":"Cannot add items to a paid order"}""")),
        )
        var mensaje: String? = null
        val vm = vm()
        coEvery { repository.getOrderDetail(any(), any()) } returns
            Result.success(OrderDetail(id = "o1", orderNumber = "ORD-1", items = emptyList(), version = 9))

        vm.sendRound { _, texto -> mensaje = texto }
        advanceUntilIdle()

        val esperado = "Esta cuenta ya se cobró en otro aparato. La ronda no se envió."
        assertEquals(esperado, mensaje)
        assertEquals(esperado, vm.actionMessage.value)
        assertEquals(true, vm.actionIsError.value)
        assertEquals(2, vm.pending.value.size)
    }

    @Test
    fun `un rechazo definitivo cualquiera tampoco se pinta como exito`() = runTest {
        coEvery { repository.addRound(any(), any(), any(), any()) } returns Result.failure(http(422, """{"message":"Producto inactivo"}"""))
        val vm = vm()

        vm.sendRound { _, _ -> }
        advanceUntilIdle()

        assertEquals(true, vm.actionIsError.value)
        assertEquals(2, vm.pending.value.size)
    }

    @Test
    fun `P1 sin red la ronda imprime por el despachador como envio que el servidor NO tiene`() = runTest {
        coEvery { repository.addRound(any(), any(), any(), any()) } returns Result.failure(IOException("sin red"))
        val vm = vm()
        vm.addProduct(cafe)

        vm.sendRound { _, _ -> }
        advanceUntilIdle()

        val llave = ids.single()
        verify {
            dispatcher.despacharEnFondo(
                venueId = "venue-1", orderNumber = "ORD-1",
                pedidos = match { pedidos -> pedidos.map { it.orderType } == listOf("Mesa 5") },
                orderId = "o1", servidorLaTiene = false, origenDelFolio = "round:$llave", alCambiarEstado = any(),
            )
        }
    }

    @Test
    fun `P1 con la ronda en linea el despachador sabe que el servidor ya la tiene`() = runTest {
        coEvery { repository.addRound(any(), any(), any(), any()) } returns Result.success(UpdatedOrder(id = "o1", version = 4))
        val vm = vm()
        vm.addProduct(cafe)

        vm.sendRound { _, _ -> }
        advanceUntilIdle()

        val llave = ids.single()
        verify {
            dispatcher.despacharEnFondo(
                venueId = "venue-1", orderNumber = "ORD-1",
                pedidos = match { pedidos -> pedidos.map { it.orderType } == listOf("Mesa 5") },
                orderId = "o1", servidorLaTiene = true, origenDelFolio = "round:$llave", alCambiarEstado = any(),
            )
        }
    }

    @Test
    fun `P1 si la comanda de la ronda no sale se avisa en la mesa y se guarda para el reintento`() = runTest {
        coEvery { repository.addRound(any(), any(), any(), any()) } returns Result.success(UpdatedOrder(id = "o1", version = 4))
        val store = mockk<ComandasPendientesStore>(relaxed = true)
        val noSalio = EstadoDeComanda.NoSalio(
            estaciones = listOf("Barra"),
            causa = "offline",
            orderNumber = "ORD-1",
            trabajo = TrabajoPendiente(
                planes = emptyList(), config = PrintConfig(), orderNumber = "ORD-1", orderType = "Mesa 5",
                serverName = null, comboNames = emptyMap(), venueId = "venue-1", orderId = null,
            ),
        )
        every { dispatcher.despacharEnFondo(any(), any(), any(), any(), any(), any(), any()) } answers {
            arg<(EstadoDeComanda) -> Unit>(6).invoke(noSalio)
            Job()
        }
        val vm = vm(store)
        vm.addProduct(cafe)

        vm.sendRound { _, _ -> }
        advanceUntilIdle()

        verify { store.guardar(noSalio, any()) }
        assertEquals("No salió la comanda de: Barra", vm.actionMessage.value)
        assertEquals(true, vm.actionIsError.value)
    }

    /**
     * 🔴 El mesero manda la ronda y regresa al plano en ese momento (la pantalla de la mesa muere con su
     * `viewModelScope`). Si la comanda de la ronda corriera ahí, se cancelaría a media espera de la impresora: ni
     * reintento, ni aviso, ni pendiente guardado — una comanda perdida sin que nadie se entere. Con el despachador
     * REAL (su ámbito vive con la app) la comanda sigue, se rinde, se GUARDA y se avisa.
     *
     * La impresora espera una puerta que la prueba abre DESPUÉS de cerrar la pantalla: así el despacho está en vuelo
     * cuando se cancela el `viewModelScope` (sin la puerta todo terminaría antes y la prueba no mordería).
     */
    @Test
    fun `P1 cerrar la pantalla de la mesa NO cancela la comanda de la ronda - la que no sale se guarda y se avisa`() = runTest {
        coEvery { repository.addRound(any(), any(), any(), any()) } returns Result.success(UpdatedOrder(id = "o1", version = 4))
        val store = mockk<ComandasPendientesStore>(relaxed = true)
        val configDeBarra = mockk<PrintConfigRepository>(relaxed = true)
        every { configDeBarra.getCurrentConfig() } returns PrintConfig(
            stations = listOf(StationInfo(id = "st_barra", name = "Barra", printerId = "pr_1", active = true)),
            defaultStationId = "st_barra",
        )
        val puerta = CompletableDeferred<Unit>()
        val impresora = mockk<ComandaPrinter>(relaxed = true)
        coEvery { impresora.printComandas(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            puerta.await()
            ComandaPrinter.Result(
                attempted = 1, printed = 0, skippedNoPrinter = 0, lastError = "offline",
                failedStations = listOf("Barra"), failedPlans = firstArg(),
            )
        }
        val despachadorReal = ComandaDispatcher(
            configDeBarra,
            ReintentoDeComanda(impresora, esperar = {}, reporteDeComandas = mockk(relaxed = true)),
            mockk(relaxed = true),
        )
        val vm = vm(store = store, despachador = despachadorReal)
        vm.addProduct(cafe)

        vm.sendRound { _, _ -> }
        advanceUntilIdle()
        vm.viewModelScope.cancel() // el mesero regresó al plano: la pantalla de la mesa ya no existe
        puerta.complete(Unit)

        verify(timeout = 5_000) { store.guardar(match { it.estaciones == listOf("Barra") }, any()) }
    }

    /**
     * Task 9 review, ronda 1, m-2: `despacharEnFondo` vive en el ámbito de la app y puede seguir insistiendo
     * minutos después de que el mesero cambie de sucursal. Si el trabajo que no salió es de la sucursal VIEJA,
     * guardarlo en el almacén EN MEMORIA de la sucursal ACTUAL se saltaría el filtro por venue que `leer()` sí
     * aplica al releer de disco — con dos locales en el mismo 192.168.1.x el papel saldría en el equivocado.
     */
    @Test
    fun `P1 el venue cambio antes de que la comanda terminara - no se guarda para el venue viejo`() = runTest {
        coEvery { repository.addRound(any(), any(), any(), any()) } returns Result.success(UpdatedOrder(id = "o1", version = 4))
        val store = mockk<ComandasPendientesStore>(relaxed = true)
        var venueActual = "venue-1"
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        every { secureStorage.venueId } answers { venueActual }
        val noSalioDeVenue1 = EstadoDeComanda.NoSalio(
            estaciones = listOf("Barra"),
            causa = "offline",
            orderNumber = "ORD-1",
            trabajo = TrabajoPendiente(
                planes = emptyList(), config = PrintConfig(), orderNumber = "ORD-1", orderType = "Mesa 5",
                serverName = null, comboNames = emptyMap(), venueId = "venue-1", orderId = null,
            ),
        )
        every { dispatcher.despacharEnFondo(any(), any(), any(), any(), any(), any(), any()) } answers {
            // El mesero ya cambió de sucursal para cuando el despacho (en fondo) por fin reporta.
            venueActual = "venue-2"
            arg<(EstadoDeComanda) -> Unit>(6).invoke(noSalioDeVenue1)
            Job()
        }
        val vm = vm(store = store, secureStorage = secureStorage)
        vm.addProduct(cafe)

        vm.sendRound { _, _ -> }
        advanceUntilIdle()

        verify(exactly = 0) { store.guardar(any(), any()) }
        // El aviso en pantalla SÍ sale — la mesa que sigue abierta no tiene por qué mentir del todo.
        assertEquals("No salió la comanda de: Barra", vm.actionMessage.value)
    }
}
