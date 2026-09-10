package com.avoqado.pos.inventory

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.inventory.data.BorradorDeConteo
import com.avoqado.pos.inventory.data.BorradorDeConteoStore
import com.avoqado.pos.inventory.data.ConteoEnCurso
import com.avoqado.pos.inventory.data.ConflictoRevision
import com.avoqado.pos.inventory.data.InventoryCountSyncCoordinator
import com.avoqado.pos.inventory.data.InventoryRepository
import com.avoqado.pos.inventory.data.RespuestaHttp
import com.avoqado.pos.inventory.data.ResultadoDeCierreCoordinado
import com.avoqado.pos.inventory.data.model.StockCount
import com.avoqado.pos.inventory.data.model.StockCountItem
import com.avoqado.pos.inventory.data.model.StockCountType
import com.avoqado.pos.inventory.data.model.StockItem
import com.avoqado.pos.inventory.domain.StockRefresher
import com.avoqado.pos.inventory.presentation.InventoryViewModel
import com.avoqado.pos.scale.ScaleSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration

/** La sucursal del aparato en todas las pruebas, salvo donde se cambia a propósito. */
private const val VENUE = "v"

@OptIn(ExperimentalCoroutinesApi::class)
class InventoryViewModelConteoTest {

    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    /**
     * Almacén falso que además apunta el ORDEN de lo que pasa: persistir tiene que ir antes que la
     * red. Espeja del almacén real las DOS reglas que el ViewModel da por sentadas: sólo se lee el
     * borrador de la sucursal ACTUAL, y no se adopta uno de otra sucursal.
     */
    class StoreFalso(val eventos: MutableList<String>) : BorradorDeConteoStore {
        var borrador: BorradorDeConteo? = null
        val cancelaciones = mutableListOf<String>()

        /** La sucursal del APARATO: es la que llavea el borrador en el almacén real. */
        var venueDelAparato: String? = VENUE

        /** `commit()` en false: disco lleno o almacenamiento en modo lectura. */
        var discoRoto = false

        /** Los tests de composición habilitan el ACK real; el caso de disco roto lo deja en false. */
        var reconocePut = false

        override fun leer() = borrador?.takeIf { it.venueId == venueDelAparato }

        override fun guardar(borrador: BorradorDeConteo): Boolean {
            eventos += "guardar"
            if (discoRoto) return false
            // Guarda M4 del almacén real: estampar la sucursal actual sobre un borrador que dice
            // ser de otra sería adoptar trabajo ajeno.
            if (borrador.venueId.isNotBlank() && borrador.venueId != venueDelAparato) return false
            this.borrador = borrador
            return true
        }

        override fun borrar(): Boolean {
            eventos += "borrar"
            if (discoRoto) return false
            borrador = null
            return true
        }

        override fun cancelacionesPendientes() = cancelaciones.toList()

        override fun agregarCancelacionPendiente(countId: String): Boolean {
            eventos += "cancelacion+$countId"
            if (discoRoto) return false
            if (countId !in cancelaciones) cancelaciones += countId
            return true
        }

        override fun quitarCancelacionPendiente(countId: String): Boolean {
            eventos += "cancelacion-$countId"
            if (discoRoto) return false
            cancelaciones -= countId
            return true
        }

        override fun descartarYEncolarCancelacion(countId: String): Boolean {
            // UN solo evento: es UNA sola escritura. Que aquí aparezcan dos sería el defecto.
            eventos += "descartar+$countId"
            if (discoRoto) return false
            borrador = null
            if (countId.isNotBlank() && countId !in cancelaciones) cancelaciones += countId
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
            eventos += "ack"
            if (!reconocePut || discoRoto) return false
            val actual = leer(venueId) ?: return false
            if (actual.countId != countId || actual.revision != expectedRevision) return false
            val vigentes = actual.lineas.associate { it.id to (it.counted to it.countedAt) }
            val confirmadas = sellos.filter { (id, sello) -> vigentes[id] == sello }.keys
            val pendientes = actual.pendientesDeEnviar - confirmadas
            val notaReconocida = notaEnviada != null &&
                ConteoEnCurso.notaPendienteDeEnviar(actual) && actual.nota == notaEnviada
            val notaPendiente = if (notaReconocida) false else ConteoEnCurso.notaPendienteDeEnviar(actual)
            borrador = actual.copy(
                revision = nuevaRevision,
                pendientesDeEnviar = pendientes,
                notaPendienteDeEnviar = notaPendiente,
                revisionConPutFinalConfirmado = nuevaRevision.takeIf {
                    esFinal && pendientes.isEmpty() && !notaPendiente
                },
            )
            return true
        }

        override fun guardarSnapshotReconocido(
            venueId: String,
            countId: String,
            revisionReconocida: Int,
            borrador: BorradorDeConteo,
            durableAlSeleccionar: BorradorDeConteo?,
        ): Boolean {
            eventos += "ack-ram"
            if (discoRoto) return false
            this.borrador = borrador.copy(
                venueId = venueId,
                countId = countId,
                revision = revisionReconocida,
            )
            return true
        }

        override fun reconocerPutDesdeMemoria(
            venueId: String,
            countId: String,
            expectedRevision: Int,
            nuevaRevision: Int,
            snapshot: BorradorDeConteo,
            durableAlSeleccionar: BorradorDeConteo?,
            sellos: Map<String, Pair<Double, String?>>,
        ): Boolean {
            eventos += "ack-ram"
            if (discoRoto) return false
            val vigentes = snapshot.lineas.associate { it.id to (it.counted to it.countedAt) }
            borrador = snapshot.copy(
                venueId = venueId,
                countId = countId,
                revision = nuevaRevision,
                pendientesDeEnviar = snapshot.pendientesDeEnviar -
                    sellos.filter { (id, sello) -> vigentes[id] == sello }.keys,
                revisionConPutFinalConfirmado = null,
            )
            return true
        }
    }

    private val eventos = mutableListOf<String>()
    private val store = StoreFalso(eventos)
    private val repository: InventoryRepository = mockk(relaxed = true)
    private val conectado = MutableStateFlow(true)

    /** El WiFi puede estar en pie con la API muerta: son dos señales, no una. */
    private val servidorAlcanzable = MutableStateFlow(true)

    /** La sucursal del aparato. Cambiarla a media captura es lo que prueba el guard de sucursal. */
    private var venueActual: String? = VENUE
    private val factory: RefreshGateFactory = mockk()

    private fun linea(id: String, expected: Double = 10.0, counted: Double = 0.0, countedAt: String? = null) =
        StockCountItem(id = id, productId = "p-$id", productName = "Prod $id", expected = expected, counted = counted, difference = counted - expected, countedAt = countedAt)

    private fun conteoFull(vararg ids: String) =
        StockCount(
            id = "full-1",
            type = StockCountType.FULL,
            status = "IN_PROGRESS",
            itemCount = ids.size,
            revision = 4,
            items = ids.map { linea(it) },
        )

    private fun buildViewModel(
        catalogo: List<StockItem> = emptyList(),
        insumos: List<StockItem> = emptyList(),
        conteos: List<StockCount> = emptyList(),
        respuestaAvance: RespuestaHttp = RespuestaHttp(200, "{}"),
        coordinator: InventoryCountSyncCoordinator? = null,
        draftStore: BorradorDeConteoStore = store,
        stockRefresher: StockRefresher = mockk(relaxed = true),
    ): InventoryViewModel {
        every { factory.create(any(), any()) } returns RefreshGate(clock = { Duration.ZERO }, random = { 0.5 })
        val monitor = mockk<ConnectivityMonitor>()
        every { monitor.isConnected } returns conectado
        every { monitor.isServerReachable } returns servidorAlcanzable
        // EXPLÍCITO: el conteo se ancla a su sucursal, y un mock relajado contestaría aquí lo que
        // le diera la gana — el guard pasaría o fallaría por accidente.
        every { repository.venueIdActual() } answers { venueActual }
        every { repository.venueNameActual() } returns "Testarudo Centro"
        coEvery { repository.enviarAvance(any(), any()) } coAnswers { eventos += "enviar"; respuestaAvance }
        coEvery { repository.enviarFinal(any(), any(), any()) } coAnswers { eventos += "final"; RespuestaHttp(200, "{}") }
        coEvery { repository.confirmarConteo(any()) } coAnswers { eventos += "confirm"; RespuestaHttp(200, "{}") }
        coEvery { repository.fetchStockCounts() } returns Result.success(Unit)
        coEvery { repository.fetchStockOverview() } returns Result.success(Unit)
        coEvery { repository.fetchRawMaterials() } returns Result.success(Unit)
        every { repository.stockCounts } returns MutableStateFlow(conteos)
        // El catálogo, EXPLÍCITO: `asegurarCatalogo` decide por `stockItems.value.isEmpty()`, y un
        // mock relajado contestaría eso con lo que le dé la gana.
        every { repository.stockItems } returns MutableStateFlow(catalogo)
        // Los insumos son OTRA lista con OTRA petición: pueden faltar aunque los productos estén.
        every { repository.countableRawMaterials } returns MutableStateFlow(insumos)
        every { repository.isRawMaterial(any()) } returns false
        return InventoryViewModel(
            repository = repository,
            planManager = mockk<PlanManager>(relaxed = true),
            scaleSettingsRepository = mockk<ScaleSettingsRepository>(relaxed = true),
            stockRefresher = stockRefresher,
            refreshGateFactory = factory,
            borradores = draftStore,
            connectivityMonitor = monitor,
            inventoryCountSyncCoordinator = coordinator,
        )
    }

    private fun InventoryViewModel.contar(index: Int, valor: String) { selectCountItem(index); updateCountedText(valor); moveToNextItem() }

    /** Azúcar para que la aserción hable de lo que pasó DESPUÉS de preparar el escenario. */
    private fun InventoryViewModel.contarEventosDesdeCero() { eventos.clear() }

    @Test
    fun `P1 contar una linea persiste el borrador ANTES de tocar la red`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.resumeCount(conteoFull("a", "b"))
        eventos.clear()
        vm.contar(0, "3")
        assertEquals(listOf("guardar", "enviar"), eventos.take(2))
        assertEquals(3.0, store.borrador!!.lineas[0].counted, 0.0)
        assertTrue(store.borrador!!.lineas[0].yaSeConto)
    }

    @Test
    fun `P1 en un ciclico cada linea tiene su propio id y contar una NO marca las demas`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.startCycleCount()
        vm.addItemsToCycleCount(listOf(StockItem(id = "p1", name = "Uno", onHand = 1.0), StockItem(id = "p2", name = "Dos", onHand = 2.0)))
        val ids = vm.countItems.value.map { it.id }
        assertTrue(ids.all { it.isNotBlank() }); assertEquals(2, ids.toSet().size)
        vm.contar(0, "5")
        assertEquals(setOf(ids[0]), vm.touchedItemIds.value)
        assertFalse(vm.countItems.value[1].yaSeConto)
        coVerify(exactly = 0) { repository.enviarAvance(any(), any()) } // un cíclico no existe en el servidor: no hay PUT
    }

    @Test
    fun `P1 confirmar un ciclico que YA existe en el servidor no vuelve a crearlo`() = runTest(scheduler) {
        val vm = buildViewModel()
        val enServidor = StockCount(id = "cyc-1", type = StockCountType.CYCLE, status = "IN_PROGRESS", itemCount = 1, items = listOf(linea("s1")))
        vm.resumeCount(enServidor)
        vm.contar(0, "4")
        vm.finishCounting(); vm.confirmCount()
        coVerify(exactly = 0) { repository.createStockCount(any(), any(), any()) }
        coVerify(exactly = 1) { repository.confirmarConteo("cyc-1") }
    }

    @Test
    fun `P1 confirmar un FULL manda solo las lineas contadas`() = runTest(scheduler) {
        val vm = buildViewModel()
        val enviadas = slot<List<StockCountItem>>()
        coEvery { repository.enviarFinal(any(), capture(enviadas), any()) } returns RespuestaHttp(200, "{}")
        vm.resumeCount(conteoFull("a", "b", "c"))
        vm.contar(0, "1"); vm.contar(2, "0")   // "0" tecleado cuenta; "b" nunca se tocó
        vm.finishCounting(); vm.confirmCount()
        assertEquals(setOf("a", "c"), enviadas.captured.map { it.id }.toSet())
        assertNull("al confirmar, el borrador se borra", store.borrador)
    }

    @Test
    fun `retomar fusiona - lo local gana, lo del servidor se conserva, y se para en la primera sin contar`() = runTest(scheduler) {
        val vm = buildViewModel()
        store.borrador = BorradorDeConteo(
            venueId = "v", countId = "full-1", type = StockCountType.FULL,
            lineas = listOf(linea("a", counted = 3.0, countedAt = "t-local")), pendientesDeEnviar = setOf("a"), actualizadoEn = 1L,
        )
        val servidor = StockCount(id = "full-1", type = StockCountType.FULL, status = "IN_PROGRESS", itemCount = 3,
            items = listOf(linea("a"), linea("b", counted = 7.0, countedAt = "t-otro-aparato"), linea("c")))
        vm.resumeCount(servidor)
        assertEquals(3.0, vm.countItems.value[0].counted, 0.0)
        assertEquals(7.0, vm.countItems.value[1].counted, 0.0)
        assertEquals(2, vm.selectedItemIndex.value)
        assertEquals(setOf("a", "b"), vm.touchedItemIds.value)
        coVerify(exactly = 1) { repository.enviarAvance("full-1", match { it.map { l -> l.id } == listOf("a") }) }
    }

    @Test
    fun `sin red el avance se queda pendiente y se manda solo al volver la red`() = runTest(scheduler) {
        val vm = buildViewModel()
        coEvery { repository.enviarAvance(any(), any()) } returns RespuestaHttp(0, "")
        vm.resumeCount(conteoFull("a", "b"))
        vm.contar(0, "2")
        assertEquals(setOf("a"), vm.pendientesDeEnviar.value)
        assertTrue(vm.sinRedAlEnviar.value)
        assertEquals(setOf("a"), store.borrador!!.pendientesDeEnviar)
        coEvery { repository.enviarAvance(any(), any()) } returns RespuestaHttp(200, "{}")
        conectado.value = false; conectado.value = true
        assertTrue(vm.pendientesDeEnviar.value.isEmpty())
        assertFalse(vm.sinRedAlEnviar.value)
    }

    @Test
    fun `P1 un 404 al enviar es conflicto - se avisa y lo local NO se borra`() = runTest(scheduler) {
        val vm = buildViewModel()
        coEvery { repository.enviarAvance(any(), any()) } returns RespuestaHttp(404, """{"message":"Conteo no encontrado o ya completado"}""")
        vm.resumeCount(conteoFull("a"))
        vm.contar(0, "2")
        assertEquals(ConteoEnCurso.CONFLICTO, vm.conflictoDelServidor.value)
        assertNotNull(store.borrador)
        assertEquals(setOf("a"), vm.pendientesDeEnviar.value)
    }

    @Test
    fun `guardar y salir cierra la pantalla y conserva el borrador`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.resumeCount(conteoFull("a", "b"))
        vm.contar(0, "2")
        vm.pedirSalida()
        assertTrue(vm.salidaPendiente.value)
        vm.guardarYSalir()
        assertFalse(vm.salidaPendiente.value); assertFalse(vm.showCounting.value)
        assertNotNull(store.borrador); assertEquals("full-1", vm.borradorLocal.value?.countId)
    }

    @Test
    fun `P1 descartar un FULL borra el borrador y cancela en el servidor - sin red la cancelacion queda pendiente y sale al reconectar`() = runTest(scheduler) {
        val vm = buildViewModel()
        coEvery { repository.cancelStockCount("full-1") } returns RespuestaHttp(0, "")
        vm.resumeCount(conteoFull("a"))
        vm.contar(0, "2")
        vm.pedirSalida()
        eventos.clear() // desde aquí, sólo lo que hace DESCARTAR
        vm.descartarConteo()
        assertNull(store.borrador); assertFalse(vm.showCounting.value)
        assertEquals(listOf("full-1"), store.cancelaciones)
        // 🔴 UNA sola escritura: borrar el borrador y encolar la cancelación en el mismo `commit()`.
        // Dos eventos aquí serían la ventana en la que el proceso muere con el borrador ya borrado
        // y la cancelación sin encolar.
        assertEquals(listOf("descartar+full-1"), eventos)
        coEvery { repository.cancelStockCount("full-1") } returns RespuestaHttp(200, "{}")
        conectado.value = false; conectado.value = true
        assertTrue(store.cancelaciones.isEmpty())
        coVerify(exactly = 2) { repository.cancelStockCount("full-1") }
    }

    @Test
    fun `descartar un ciclico sin crear no toca el servidor`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.startCycleCount()
        vm.addItemsToCycleCount(listOf(StockItem(id = "p1", name = "Uno", onHand = 1.0)))
        vm.contar(0, "1")
        vm.pedirSalida(); vm.descartarConteo()
        assertNull(store.borrador); assertTrue(store.cancelaciones.isEmpty())
        coVerify(exactly = 0) { repository.cancelStockCount(any()) }
    }

    @Test
    fun `continuarBorrador retoma un ciclico local sin servidor y un FULL desde su conteo en la lista`() = runTest(scheduler) {
        val vm = buildViewModel()
        store.borrador = BorradorDeConteo(venueId = "v", countId = null, type = StockCountType.CYCLE,
            lineas = listOf(linea("u1", counted = 2.0, countedAt = "t")), actualizadoEn = 1L)
        vm.refrescarBorradorLocal(); vm.continuarBorrador()
        assertTrue(vm.showCounting.value); assertEquals(StockCountType.CYCLE, vm.activeCountType.value); assertNull(vm.activeCount.value)
        assertEquals(2.0, vm.countItems.value[0].counted, 0.0)

        vm.guardarYSalir()
        store.borrador = BorradorDeConteo(venueId = "v", countId = "full-1", type = StockCountType.FULL,
            lineas = listOf(linea("a", counted = 1.0, countedAt = "t"), linea("b")), pendientesDeEnviar = setOf("a"), actualizadoEn = 1L)
        every { repository.stockCounts } returns MutableStateFlow(listOf(conteoFull("a", "b")))
        vm.refrescarBorradorLocal(); vm.continuarBorrador()
        assertEquals("full-1", vm.activeCount.value?.id); assertEquals(1, vm.selectedItemIndex.value)
    }

    @Test
    fun `contar una linea no la guarda ni la manda dos veces`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.resumeCount(conteoFull("a", "b"))
        eventos.clear()
        vm.contar(0, "3")

        // `moveToNextItem` llama a `saveCurrentCount` y enseguida a `selectCountItem`, que lo
        // vuelve a llamar con el MISMO texto: eran dos fsync del conteo ENTERO y dos PUT por cada
        // línea contada — en un conteo de 138 artículos, ~276 PUT.
        // El tercer «guardar» NO es el defecto: es el borrador reescrito sin el pendiente después
        // del 200, y hace falta para que un reinicio no lo vuelva a mandar. Lo que sobraba era la
        // PAREJA repetida.
        assertEquals(listOf("guardar", "enviar", "guardar"), eventos)
        coVerify(exactly = 1) { repository.enviarAvance(any(), any()) }
    }

    @Test
    fun `P1 un rechazo del servidor no se reintenta en bucle y lo pendiente se conserva`() = runTest(scheduler) {
        val vm = buildViewModel()
        // El encadenado sólo corre tras un envío que SALIÓ. Encadenar también tras un rechazo
        // (400/403/409/422) o un fallo de red sería una recursión sin tope de PUTs: la condición
        // sigue siendo cierta porque lo pendiente no se limpia. Lo pendiente sale con la
        // siguiente tecla o al volver la red, no aquí.
        coEvery { repository.enviarAvance(any(), any()) } returns
            RespuestaHttp(400, """{"message":"Una de las líneas no pertenece a este conteo"}""")
        vm.resumeCount(conteoFull("a"))
        vm.contar(0, "2")

        coVerify(exactly = 1) { repository.enviarAvance(any(), any()) }
        assertNotNull("el rechazo se DICE, no se traga", vm.errorMessage.value)
        assertEquals(setOf("a"), vm.pendientesDeEnviar.value)
        assertFalse("un rechazo no es falta de red", vm.sinRedAlEnviar.value)
    }

    @Test
    fun `con el conteo cerrado en el servidor no se intenta confirmar`() = runTest(scheduler) {
        val vm = buildViewModel()
        // «No se pudo guardar el conteo. Intenta de nuevo.» sería falso: reintentar contra un
        // conteo que ya no está IN_PROGRESS no puede funcionar nunca.
        coEvery { repository.enviarAvance(any(), any()) } returns RespuestaHttp(404, "{}")
        vm.resumeCount(conteoFull("a"))
        vm.contar(0, "2")
        assertEquals(ConteoEnCurso.CONFLICTO, vm.conflictoDelServidor.value)

        vm.finishCounting(); vm.confirmCount()

        coVerify(exactly = 0) { repository.enviarFinal(any(), any(), any()) }
        coVerify(exactly = 0) { repository.confirmarConteo(any()) }
        assertEquals(ConteoEnCurso.CONFLICTO, vm.errorMessage.value)
    }

    @Test
    fun `retomar olvida un pendiente cuya linea el servidor ya no tiene`() = runTest(scheduler) {
        val vm = buildViewModel()
        // `fusionar` descarta las líneas locales que el servidor ya no tiene, así que ese
        // pendiente no se puede mandar NUNCA: arrastrarlo dejaría el aviso «1 línea guardada en
        // este aparato» encendido para siempre.
        store.borrador = BorradorDeConteo(
            venueId = "v", countId = "full-1", type = StockCountType.FULL,
            lineas = listOf(linea("zz", counted = 1.0, countedAt = "t")), pendientesDeEnviar = setOf("zz"), actualizadoEn = 1L,
        )
        vm.resumeCount(conteoFull("a"))

        assertTrue(vm.pendientesDeEnviar.value.isEmpty())
        coVerify(exactly = 0) { repository.enviarAvance(any(), any()) }
    }

    @Test
    fun `la nota no reescribe el conteo entero por cada tecla, pero se guarda al salir`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.resumeCount(conteoFull("a", "b"))
        vm.contar(0, "1")
        eventos.clear()

        // Cada `guardar` serializa el conteo ENTERO y hace un commit() con fsync, en el hilo de
        // la UI: una nota de 40 caracteres serían 40. El `commit()` es correcto para lo que sí es
        // dinero —una cantidad, una vez por línea—; para la nota es caro y no lo exige nada.
        "estante 3".forEach { vm.updateCountNote(vm.countNote.value + it) }
        assertTrue("ni un fsync por tecla: $eventos", eventos.isEmpty())

        vm.pedirSalida()
        assertEquals("estante 3", store.borrador!!.nota)
        assertEquals(true, store.borrador!!.notaPendienteDeEnviar)
    }

    @Test
    fun `P1 descartar dos conteos sin red conserva las DOS cancelaciones`() = runTest(scheduler) {
        val vm = buildViewModel()
        // Con UNA sola ranura, la segunda cancelación pisaba a la primera y ese conteo se quedaba
        // IN_PROGRESS para siempre — el defecto D4 que este proyecto está arreglando, y sin un
        // solo aviso.
        coEvery { repository.cancelStockCount(any()) } returns RespuestaHttp(0, "")
        vm.resumeCount(conteoFull("a"))
        vm.contar(0, "1"); vm.pedirSalida(); vm.descartarConteo()

        val segundo = StockCount(id = "full-2", type = StockCountType.FULL, status = "IN_PROGRESS", itemCount = 1, items = listOf(linea("z")))
        vm.resumeCount(segundo)
        vm.contar(0, "2"); vm.pedirSalida(); vm.descartarConteo()

        assertEquals(listOf("full-1", "full-2"), store.cancelaciones)

        coEvery { repository.cancelStockCount(any()) } returns RespuestaHttp(200, "{}")
        conectado.value = false; conectado.value = true

        assertTrue("las dos salen al reconectar", store.cancelaciones.isEmpty())
        coVerify(atLeast = 1) { repository.cancelStockCount("full-1") }
        coVerify(atLeast = 1) { repository.cancelStockCount("full-2") }
    }

    @Test
    fun `P1 al volver la red lo pendiente sale desde el DISCO aunque la pantalla este cerrada`() = runTest(scheduler) {
        val vm = buildViewModel()
        // «Guardar el avance» cierra la pantalla y deja `_activeCount` en null. La cola vive en
        // el DISCO, no en la pantalla: si el envío dependiera de tenerla abierta, lo contado se
        // quedaría en el aparato hasta que alguien volviera a entrar — y si otro aparato confirma
        // el conteo mientras tanto, esas líneas no se aplican nunca.
        coEvery { repository.enviarAvance(any(), any()) } returns RespuestaHttp(0, "")
        vm.resumeCount(conteoFull("a", "b"))
        vm.contar(0, "2")
        vm.guardarYSalir()
        assertNull(vm.activeCount.value)
        assertEquals(setOf("a"), store.borrador!!.pendientesDeEnviar)

        val enviadas = slot<List<StockCountItem>>()
        coEvery { repository.enviarAvance("full-1", capture(enviadas)) } returns RespuestaHttp(200, "{}")
        conectado.value = false; conectado.value = true

        assertEquals(listOf("a"), enviadas.captured.map { it.id })
        assertEquals(2.0, enviadas.captured[0].counted, 0.0)
        assertTrue("el borrador queda sin pendientes", store.borrador!!.pendientesDeEnviar.isEmpty())
    }

    @Test
    fun `P1 corregir una cantidad mientras viaja el PUT la deja pendiente y se remanda`() = runTest(scheduler) {
        val vm = buildViewModel()
        // Con el WiFi del ICP un PUT tarda segundos: corregir el número recién tecleado es lo
        // normal, no lo raro. Si el 200 del PUT VIEJO cierra la línea, el servidor se queda con
        // el valor viejo mientras el aparato enseña el nuevo — y nadie se entera hasta que otro
        // aparato confirma el conteo.
        val puerta = CompletableDeferred<Unit>()
        val enviados = mutableListOf<List<Double>>()
        coEvery { repository.enviarAvance(any(), any()) } coAnswers {
            enviados += secondArg<List<StockCountItem>>().map { it.counted }
            if (enviados.size == 1) puerta.await()
            RespuestaHttp(200, "{}")
        }
        vm.resumeCount(conteoFull("a", "b"))
        vm.contar(0, "5")                                                    // el PUT sale con 5 y se queda en vuelo
        vm.selectCountItem(0); vm.updateCountedText("7"); vm.moveToNextItem() // el cajero corrige
        puerta.complete(Unit)                                                // llega el 200 del PUT VIEJO
        scheduler.advanceUntilIdle()

        assertEquals(listOf(listOf(5.0), listOf(7.0)), enviados)
        assertEquals(7.0, vm.countItems.value[0].counted, 0.0)
        assertTrue("el segundo PUT sí la cierra", vm.pendientesDeEnviar.value.isEmpty())
    }

    @Test
    fun `P1 contar OTRA linea mientras viaja el PUT no la saca de la cola`() = runTest(scheduler) {
        val vm = buildViewModel()
        // Seguir contando mientras el PUT anterior viaja es el flujo NORMAL del mostrador, no un
        // caso raro. Si el 200 del envío VIEJO reescribe la cola con la foto de antes del viaje,
        // la línea nueva se cae de la cola Y del borrador: el servidor nunca recibe su cantidad y
        // la banda dice cero pendientes. Sólo la salvaría confirmar DESDE ESTE aparato.
        val puertaA = CompletableDeferred<Unit>()
        val puertaB = CompletableDeferred<Unit>()
        val enviados = mutableListOf<List<String>>()
        coEvery { repository.enviarAvance(any(), any()) } coAnswers {
            enviados += secondArg<List<StockCountItem>>().map { it.id }
            when (enviados.size) {
                1 -> puertaA.await()
                2 -> puertaB.await()
            }
            RespuestaHttp(200, "{}")
        }
        vm.resumeCount(conteoFull("a", "b"))
        vm.contar(0, "5") // el PUT sale con [a] y se queda en vuelo
        vm.contar(1, "2") // se cuenta b mientras a viaja
        assertEquals("b espera su turno", listOf(listOf("a")), enviados)

        puertaA.complete(Unit) // llega el 200 del PUT de a
        scheduler.advanceUntilIdle()

        assertEquals("b sigue en la cola", setOf("b"), vm.pendientesDeEnviar.value)
        assertEquals("y también en el disco", setOf("b"), store.borrador!!.pendientesDeEnviar)
        assertEquals("el encadenado la manda", listOf(listOf("a"), listOf("b")), enviados)

        puertaB.complete(Unit)
        scheduler.advanceUntilIdle()
        assertTrue("el segundo PUT sí la cierra", vm.pendientesDeEnviar.value.isEmpty())
    }

    @Test
    fun `P1 guardar el avance con el PUT en vuelo conserva el borrador y su nota`() = runTest(scheduler) {
        val vm = buildViewModel()
        // «Guardar el avance» cierra la pantalla con el PUT todavía viajando. Al llegar el 200,
        // persistir desde `_countItems` —ya vacío— borra el borrador entero: se pierde la nota
        // (que el PUT del avance no manda) y desaparece la tarjeta «sin terminar en este aparato».
        val puerta = CompletableDeferred<Unit>()
        var envios = 0
        coEvery { repository.enviarAvance(any(), any()) } coAnswers {
            envios++
            if (envios == 1) puerta.await()
            RespuestaHttp(200, "{}")
        }
        vm.resumeCount(conteoFull("a", "b"))
        vm.contar(0, "5") // el PUT sale con [a] y se queda en vuelo
        vm.updateCountNote("estante 3")
        vm.guardarYSalir()
        assertNull("la pantalla ya está cerrada", vm.activeCount.value)

        puerta.complete(Unit) // el 200 llega con la pantalla ya cerrada
        scheduler.advanceUntilIdle()

        assertNotNull("el borrador sobrevive", store.borrador)
        assertEquals("con su nota", "estante 3", store.borrador!!.nota)
        assertEquals("y sus cantidades", 5.0, store.borrador!!.lineas[0].counted, 0.0)
        assertTrue("sin la línea que ya salió", store.borrador!!.pendientesDeEnviar.isEmpty())
    }

    @Test
    fun `P1 continuarBorrador con el conteo ya cerrado en el servidor conserva lo local y avisa`() = runTest(scheduler) {
        val vm = buildViewModel()
        // El mensaje promete «lo que contaste aquí se conserva sólo para consulta» y el código lo
        // borraba en la misma rama: la UI mintiendo. El spec (§5) es explícito — lo local nunca
        // se descarta en silencio; sólo «Descartar el conteo» borra.
        store.borrador = BorradorDeConteo(
            venueId = "v", countId = "full-1", type = StockCountType.FULL,
            lineas = listOf(linea("a", counted = 4.0, countedAt = "t"), linea("b")),
            pendientesDeEnviar = setOf("a"), revision = 4, actualizadoEn = 1L,
        )
        every { repository.stockCounts } returns MutableStateFlow(
            listOf(StockCount(id = "full-1", type = StockCountType.FULL, status = "COMPLETED", itemCount = 2, revision = 5, items = listOf(linea("a"), linea("b")))),
        )
        vm.refrescarBorradorLocal()
        vm.contarEventosDesdeCero()
        vm.continuarBorrador()

        assertEquals(ConteoEnCurso.CONFLICTO, vm.conflictoDelServidor.value)
        assertNotNull("sólo «Descartar» borra", store.borrador)
        assertFalse("nada de borrar", eventos.contains("borrar"))
        assertEquals(4.0, vm.countItems.value[0].counted, 0.0)
        assertEquals("la selección inicial intacta sigue vacía", "", vm.countedText.value)
        assertTrue(vm.showCounting.value)
        // Con conflicto puesto no se manda nada: el conteo ya no está IN_PROGRESS allá.
        coVerify(exactly = 0) { repository.enviarAvance(any(), any()) }
    }

    @Test
    fun `P1 continuar tras 409 abre la copia local exacta sin fusionar el valor remoto`() = runTest(scheduler) {
        store.borrador = BorradorDeConteo(
            venueId = VENUE,
            countId = "full-1",
            type = StockCountType.FULL,
            lineas = listOf(linea("a", counted = 5.0, countedAt = "t-local")),
            nota = "nota local",
            notaPendienteDeEnviar = false,
            pendientesDeEnviar = emptySet(),
            revision = 4,
            conflictoRevision = ConflictoRevision(
                code = ConteoEnCurso.CODIGO_CONFLICTO_REVISION,
                message = ConteoEnCurso.CONFLICTO_REVISION,
                venueId = VENUE,
                countId = "full-1",
                expectedRevision = 4,
                currentRevision = 5,
                status = "IN_PROGRESS",
            ),
            actualizadoEn = 1L,
        )
        val remoto = conteoFull("a").copy(
            revision = 5,
            note = "nota remota",
            items = listOf(linea("a", counted = 8.0, countedAt = "t-remoto")),
        )
        val vm = buildViewModel(conteos = listOf(remoto))
        vm.refrescarBorradorLocal()

        vm.continuarBorrador()

        assertEquals(5.0, vm.countItems.value.single().counted, 0.0)
        assertEquals("5", vm.countedText.value)
        assertEquals("nota local", vm.countNote.value)
        assertEquals(4, store.borrador?.revision)
        assertNotNull(vm.conflictoDeRevision.value)
        assertNull(vm.conflictoDelServidor.value)
        coVerify(exactly = 0) { repository.enviarAvance(any(), any()) }
    }

    @Test
    fun `P1 continuar legacy sin revision conserva local y marca UNKNOWN antes de fusionar`() = runTest(scheduler) {
        store.borrador = BorradorDeConteo(
            venueId = VENUE,
            countId = "full-1",
            type = StockCountType.FULL,
            lineas = listOf(linea("a", counted = 5.0, countedAt = "t-local")),
            nota = "nota legacy",
            notaPendienteDeEnviar = false,
            pendientesDeEnviar = emptySet(),
            revision = null,
            actualizadoEn = 1L,
        )
        val remoto = conteoFull("a").copy(
            revision = 8,
            note = "nota remota",
            items = listOf(linea("a", counted = 9.0, countedAt = "t-remoto")),
        )
        val vm = buildViewModel(conteos = listOf(remoto))
        vm.refrescarBorradorLocal()

        vm.continuarBorrador()

        assertEquals(5.0, vm.countItems.value.single().counted, 0.0)
        assertEquals("5", vm.countedText.value)
        assertEquals("nota legacy", vm.countNote.value)
        assertNull("un GET posterior no inventa la base del borrador", store.borrador?.revision)
        assertEquals(ConteoEnCurso.CODIGO_REVISION_DESCONOCIDA, store.borrador?.conflictoRevision?.code)
        assertEquals(ConteoEnCurso.REVISION_DESCONOCIDA, vm.bandaDeAviso.value)
        coVerify(exactly = 0) { repository.enviarAvance(any(), any()) }
    }

    @Test
    fun `P1 consulta cerrada muestra la cantidad del item seleccionado`() = runTest(scheduler) {
        store.borrador = BorradorDeConteo(
            venueId = VENUE,
            countId = "full-1",
            type = StockCountType.FULL,
            lineas = listOf(linea("a", counted = 4.0, countedAt = "t-local")),
            pendientesDeEnviar = setOf("a"),
            revision = 4,
            actualizadoEn = 1L,
        )
        val cerrado = conteoFull("a").copy(status = "COMPLETED", revision = 5)
        val vm = buildViewModel(conteos = listOf(cerrado))
        vm.refrescarBorradorLocal()

        vm.continuarBorrador()

        assertEquals(ConteoEnCurso.CONFLICTO, vm.conflictoDelServidor.value)
        assertEquals(0, vm.selectedItemIndex.value)
        assertEquals("4", vm.countedText.value)
    }

    @Test
    fun `P1 consulta muestra cero cuando el item seleccionado fue contado en cero`() = runTest(scheduler) {
        val conflicto = ConflictoRevision(
            code = ConteoEnCurso.CODIGO_CONFLICTO_REVISION,
            message = ConteoEnCurso.CONFLICTO_REVISION,
            venueId = VENUE,
            countId = "full-1",
            expectedRevision = 4,
            currentRevision = 5,
            status = "IN_PROGRESS",
        )
        store.borrador = BorradorDeConteo(
            venueId = VENUE,
            countId = "full-1",
            type = StockCountType.FULL,
            lineas = listOf(linea("cero", counted = 0.0, countedAt = "t-cero")),
            pendientesDeEnviar = setOf("cero"),
            revision = 4,
            conflictoRevision = conflicto,
            actualizadoEn = 1L,
        )
        val vm = buildViewModel()
        vm.refrescarBorradorLocal()

        vm.continuarBorrador()

        assertEquals("el cero explícito se puede leer", "0", vm.countedText.value)
    }

    @Test
    fun `P1 consulta mantiene vacío un item seleccionado que nunca se contó`() = runTest(scheduler) {
        store.borrador = BorradorDeConteo(
            venueId = VENUE,
            countId = "full-1",
            type = StockCountType.FULL,
            lineas = listOf(linea("intacta")),
            pendientesDeEnviar = emptySet(),
            revision = 4,
            conflictoRevision = ConflictoRevision(
                code = ConteoEnCurso.CODIGO_CONFLICTO_REVISION,
                message = ConteoEnCurso.CONFLICTO_REVISION,
                venueId = VENUE,
                countId = "full-1",
                expectedRevision = 4,
                currentRevision = 5,
                status = "IN_PROGRESS",
            ),
            actualizadoEn = 1L,
        )
        val vm = buildViewModel()
        vm.refrescarBorradorLocal()

        vm.continuarBorrador()

        assertEquals(0, vm.selectedItemIndex.value)
        assertEquals("", vm.countedText.value)
    }

    @Test
    fun `continuar un borrador editable mantiene vacío el campo seleccionado`() = runTest(scheduler) {
        conectado.value = false
        store.borrador = BorradorDeConteo(
            venueId = VENUE,
            countId = "full-1",
            type = StockCountType.FULL,
            lineas = listOf(linea("a", counted = 33.0, countedAt = "t-local")),
            pendientesDeEnviar = setOf("a"),
            revision = 4,
            actualizadoEn = 1L,
        )
        val vm = buildViewModel(conteos = listOf(conteoFull("a")))
        vm.refrescarBorradorLocal()

        vm.continuarBorrador()

        assertNull(vm.conflictoDeRevision.value)
        assertNull(vm.conflictoDelServidor.value)
        assertEquals("", vm.countedText.value)
        assertEquals(33.0, vm.countItems.value.single().counted, 0.0)
    }

    @Test
    fun `P1 con un borrador de OTRO conteo sin enviar no se abre ninguno nuevo`() = runTest(scheduler) {
        val vm = buildViewModel()
        coEvery { repository.createStockCount(StockCountType.FULL, any(), any()) } returns Result.success(conteoFull("z"))
        // Hay UNA ranura de borrador por sucursal: abrir otro conteo pisaba las líneas de éste
        // —30 sin red en el caso real— con un solo toque en «Continuar» y sin un aviso.
        store.borrador = BorradorDeConteo(
            venueId = "v", countId = "full-7", type = StockCountType.FULL,
            lineas = listOf(linea("a", counted = 3.0, countedAt = "t")), pendientesDeEnviar = setOf("a"), actualizadoEn = 1L,
        )

        vm.resumeCount(conteoFull("a", "b")) // "full-1": otro conteo
        assertEquals(ConteoEnCurso.HAY_OTRO_BORRADOR, vm.errorMessage.value)
        assertFalse(vm.showCounting.value)

        vm.clearErrorMessage()
        vm.startCycleCount()
        assertEquals(ConteoEnCurso.HAY_OTRO_BORRADOR, vm.errorMessage.value)
        assertFalse(vm.showCounting.value)

        vm.clearErrorMessage()
        vm.startFullCount()
        assertEquals(ConteoEnCurso.HAY_OTRO_BORRADOR, vm.errorMessage.value)
        // Se comprueba ANTES de crear: no se dejan conteos huérfanos en el servidor para
        // enseguida negarse a abrirlos.
        coVerify(exactly = 0) { repository.createStockCount(any(), any(), any()) }

        // Y lo de verdad importante: el borrador del otro conteo sigue entero.
        assertEquals("full-7", store.borrador?.countId)
        assertEquals(3.0, store.borrador!!.lineas[0].counted, 0.0)
        assertFalse("no se tocó el disco", eventos.contains("borrar"))
    }

    @Test
    fun `P1 el aviso de que ya hay un conteo sin terminar CIERRA la hoja de tipo de conteo`() = runTest(scheduler) {
        val vm = buildViewModel()
        coEvery { repository.createStockCount(StockCountType.FULL, any(), any()) } returns Result.success(conteoFull("z"))
        // Medido en la tablet (QA pasada 2, DP2-1): el aviso SE EMITÍA y no se veía. El
        // `SnackbarHost` de `InventoryScreen` vive DEBAJO de la ventana del `ModalBottomSheet`,
        // así que con la hoja abierta el cajero tocaba dos o tres veces creyendo que el botón
        // estaba muerto — y nunca se enteraba de que tenía un conteo suyo sin terminar.
        store.borrador = BorradorDeConteo(
            venueId = "v", countId = "full-7", type = StockCountType.FULL,
            lineas = listOf(linea("a", counted = 3.0, countedAt = "t")), pendientesDeEnviar = setOf("a"), actualizadoEn = 1L,
        )

        vm.openCountTypeSheet()
        assertTrue(vm.showCountTypeSheet.value)

        vm.startFullCount()
        assertFalse("la hoja abierta tapa el aviso", vm.showCountTypeSheet.value)
        assertEquals(ConteoEnCurso.HAY_OTRO_BORRADOR, vm.errorMessage.value)
        assertFalse(vm.showCounting.value)
        coVerify(exactly = 0) { repository.createStockCount(any(), any(), any()) }

        // El otro botón de la MISMA hoja, que entra por el mismo guard.
        vm.clearErrorMessage()
        vm.openCountTypeSheet()
        vm.startCycleCount()
        assertFalse("la hoja abierta tapa el aviso", vm.showCountTypeSheet.value)
        assertEquals(ConteoEnCurso.HAY_OTRO_BORRADOR, vm.errorMessage.value)
        assertFalse(vm.showCounting.value)
    }

    @Test
    fun `un borrador ya sincronizado no bloquea empezar otro conteo`() = runTest(scheduler) {
        val vm = buildViewModel()
        // Sin pendientes, el servidor YA tiene todo lo de ese conteo: reemplazar el borrador no
        // pierde nada, así que no se le pide permiso a nadie.
        store.borrador = BorradorDeConteo(
            venueId = "v", countId = "full-7", type = StockCountType.FULL,
            lineas = listOf(linea("a", counted = 3.0, countedAt = "t")),
            nota = "ya está en el servidor",
            notaPendienteDeEnviar = false,
            pendientesDeEnviar = emptySet(),
            actualizadoEn = 1L,
        )
        vm.resumeCount(conteoFull("a", "b"))
        assertNull(vm.errorMessage.value)
        assertTrue(vm.showCounting.value)
    }

    @Test
    fun `P1 confirmar un ciclico NO manda lineas que el servidor no creo`() = runTest(scheduler) {
        val vm = buildViewModel()
        // 🔴 `createStockCount(CYCLE, …)` DESCARTA en silencio lo que no puede contar (filtra por
        // venue/active/deletedAt e `inventoryMethod != RECIPE`), y el PUT del servidor es
        // TODO-O-NADA: un id que no reconoce tumba el conteo entero con un 400, y reintentar no
        // puede funcionar nunca porque el cuerpo sale igual. El trabajo del cajero queda atrapado.
        val creado = StockCount(
            id = "cyc-9", type = StockCountType.CYCLE, status = "IN_PROGRESS", itemCount = 1,
            items = listOf(StockCountItem(id = "srv-1", productId = "p1", productName = "Uno", expected = 1.0)),
        )
        coEvery { repository.createStockCount(StockCountType.CYCLE, any(), any()) } returns Result.success(creado)
        val enviadas = slot<List<StockCountItem>>()
        coEvery { repository.enviarFinal(any(), capture(enviadas), any()) } returns RespuestaHttp(200, "{}")

        vm.startCycleCount()
        vm.addItemsToCycleCount(listOf(StockItem(id = "p1", name = "Uno", onHand = 1.0), StockItem(id = "p2", name = "Dos", onHand = 2.0)))
        vm.contar(0, "5"); vm.contar(1, "7")
        vm.finishCounting(); vm.confirmCount()

        assertEquals(listOf("srv-1"), enviadas.captured.map { it.id })
        assertEquals(5.0, enviadas.captured[0].counted, 0.0)
        // Se DICE: la línea contada que no entró no se aplica a inventario.
        assertEquals(ConteoEnCurso.lineasNoIncluidas(1), vm.errorMessage.value)
        // …y aun así el conteo se confirma: nunca se bloquea por esto.
        coVerify(exactly = 1) { repository.confirmarConteo("cyc-9") }
    }

    @Test
    fun `P1 en un ciclico sin red la banda lo DICE`() = runTest(scheduler) {
        // 🔴 En un CÍCLICO la banda no podía salir NUNCA: `_pendientesDeEnviar` sólo crece con un
        // conteo que YA existe en el servidor, y `_sinRedAlEnviar` sólo se enciende DENTRO de
        // `enviarPendientes`, que en un cíclico sale en su primera línea (`_activeCount` es null).
        // O sea: el cajero contaba con el WiFi apagado y la pantalla no decía absolutamente nada.
        // `todo-funciona-sin-red.md` no admite eso: o funciona igual y lo DICE, o miente.
        val vm = buildViewModel()
        vm.startCycleCount()
        vm.addItemsToCycleCount(listOf(StockItem(id = "p1", name = "Uno", onHand = 1.0), StockItem(id = "p2", name = "Dos", onHand = 2.0)))
        vm.contar(0, "5")
        assertNull("con red no hay nada que avisar", vm.bandaDeAviso.value)

        conectado.value = false

        // Un cíclico no tiene «pendientes de enviar»: lo que vive sólo aquí es TODO lo contado.
        assertEquals(ConteoEnCurso.avisoSinRed(1), vm.bandaDeAviso.value)
    }

    @Test
    fun `P1 en un FULL la banda cuenta las lineas que faltan por subir`() = runTest(scheduler) {
        val vm = buildViewModel()
        // Aquí la fuente es la de siempre: el PUT que no salió (code 0), con el monitor en verde.
        coEvery { repository.enviarAvance(any(), any()) } returns RespuestaHttp(0, "")
        vm.resumeCount(conteoFull("a", "b", "c"))
        vm.contar(0, "1"); vm.contar(1, "2")

        assertTrue(vm.sinRedAlEnviar.value)
        assertEquals(ConteoEnCurso.avisoSinRed(2), vm.bandaDeAviso.value)
    }

    @Test
    fun `P1 el conflicto del servidor gana sobre el aviso de sin red`() = runTest(scheduler) {
        val vm = buildViewModel()
        coEvery { repository.enviarAvance(any(), any()) } returns RespuestaHttp(404, "{}")
        vm.resumeCount(conteoFull("a"))
        vm.contar(0, "2")
        conectado.value = false

        // «Sin conexión» sería mentira: la red está mal, pero lo que de verdad pasó es que ese
        // conteo ya no existe allá — y eso no lo arregla esperar a que vuelva el WiFi.
        assertEquals(ConteoEnCurso.CONFLICTO, vm.bandaDeAviso.value)
    }

    @Test
    fun `con todo subido la banda no dice nada, ni siquiera sin red`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.resumeCount(conteoFull("a", "b"))
        vm.contar(0, "3") // el PUT salió (200)
        assertNull(vm.bandaDeAviso.value)

        conectado.value = false

        // Nada vive sólo en el aparato: una banda aquí sería ruido que enseña a ignorarlas.
        assertNull("sin red pero con todo en el servidor no hay nada que avisar", vm.bandaDeAviso.value)
    }

    @Test
    fun `P1 al volver la red se recupera el catalogo si se quedo vacio`() = runTest(scheduler) {
        // D1 del QA en la OrderPAD 3 (7-sep): abrir la app sin red deja `stock-overview` y
        // `raw-materials` disparados y SIN respuesta, y nadie los vuelve a pedir — ni siquiera
        // cuando la red vuelve. Con `/health` ya en 200, «Descripción general» decía «Sin
        // artículos con inventario» en un venue con 28 productos y el conteo CÍCLICO quedaba
        // inservible: «Agregar artículos» abría vacío incluso sin filtro. Es
        // `lista-vacia-no-es-fallo-de-red` aplicado al catálogo, y sólo se curaba reiniciando.
        conectado.value = false
        buildViewModel()

        conectado.value = true

        coVerify(exactly = 1) { repository.fetchStockOverview() }
        coVerify(exactly = 1) { repository.fetchRawMaterials() }
    }

    @Test
    fun `con el catalogo cargado la reconexion no lo vuelve a pedir`() = runTest(scheduler) {
        // Es RECUPERACIÓN, no refresco: con datos buenos en pantalla no se pide nada. Si no,
        // cada bache de WiFi de la tienda sería una descarga del catálogo entero.
        conectado.value = false
        buildViewModel(
            catalogo = listOf(StockItem(id = "p1", name = "Uno", onHand = 1.0)),
            insumos = listOf(StockItem(id = "r1", name = "Harina", onHand = 3.0)),
        )

        conectado.value = true

        coVerify(exactly = 0) { repository.fetchStockOverview() }
        coVerify(exactly = 0) { repository.fetchRawMaterials() }
    }

    @Test
    fun `P1 empezar un ciclico con el catalogo vacio lo vuelve a pedir`() = runTest(scheduler) {
        // Sin catálogo, un cíclico no se puede ni empezar: el selector abre vacío. Va FUERA del
        // gate de refresco a propósito (el gate se apaga con un conteo en curso, §4.5), y es
        // seguro justo porque sólo corre con la lista VACÍA: no hay nada que pisar.
        val vm = buildViewModel()

        vm.startCycleCount()

        coVerify(exactly = 1) { repository.fetchStockOverview() }
        coVerify(exactly = 1) { repository.fetchRawMaterials() }

        // El MISMO camino que usa la vista al abrir «Agregar artículos»: el selector es el que de
        // verdad se queda vacío, y ahí la lista sigue sin llegar.
        vm.asegurarCatalogo()
        coVerify(exactly = 2) { repository.fetchStockOverview() }

        // Sin red no se intenta: el catálogo no está en el aparato y pedirlo sólo suma un error.
        conectado.value = false
        vm.asegurarCatalogo()
        coVerify(exactly = 2) { repository.fetchStockOverview() }
    }

    @Test
    fun `P1 confirmar sin red no toca el servidor, lo DICE y conserva el conteo`() = runTest(scheduler) {
        // D2 del QA (OrderPAD 3, 7-sep): con el WiFi apagado, tocar «Confirmar» dejaba la
        // pantalla EXACTAMENTE igual — sin diálogo, sin toast, sin spinner (4 volcados de UI a
        // t+1, +2, +3 y +4 s: ni un texto nuevo). Lo único visible era el banner global «Sin
        // conexión — las ventas se guardan en el dispositivo», que habla de VENTAS: el cajero se
        // iba creyendo que había cerrado el conteo. Confirmar es online-only a propósito (el
        // ajuste de existencias lo aplica el servidor) y eso hay que DECIRLO.
        val vm = buildViewModel()
        vm.resumeCount(conteoFull("a", "b"))
        vm.contar(0, "3")
        vm.finishCounting()
        conectado.value = false

        vm.confirmCount()

        coVerify(exactly = 0) { repository.createStockCount(any(), any(), any()) }
        coVerify(exactly = 0) { repository.enviarFinal(any(), any(), any()) }
        coVerify(exactly = 0) { repository.confirmarConteo(any()) }
        assertEquals(ConteoEnCurso.CONFIRMAR_SIN_RED, vm.errorMessage.value)
        assertNotNull("el conteo sigue en el aparato", store.borrador)
        assertEquals(3.0, store.borrador!!.lineas[0].counted, 0.0)
        assertTrue("la revisión sigue abierta: no se finge que se cerró", vm.showReview.value)
        assertFalse(vm.isSaving.value)
    }

    @Test
    fun `continuarBorrador sin la lista del servidor retoma un FULL desde lo local`() = runTest(scheduler) {
        val vm = buildViewModel()
        store.borrador = BorradorDeConteo(venueId = "v", countId = "full-9", type = StockCountType.FULL,
            lineas = listOf(linea("a", counted = 1.0, countedAt = "t"), linea("b")), pendientesDeEnviar = setOf("a"), actualizadoEn = 1L)
        vm.refrescarBorradorLocal(); vm.continuarBorrador()
        assertEquals("full-9", vm.activeCount.value?.id); assertEquals(2, vm.countItems.value.size); assertTrue(vm.showCounting.value)
    }

    // ------------------------------------------------------------------------------------------
    // Ronda 5 (auditoría de Codex): lo que todavía podía perder o sellar una cantidad equivocada.
    // ------------------------------------------------------------------------------------------

    @Test
    fun `P1 si descartar NO queda en disco la pantalla NO se limpia`() = runTest(scheduler) {
        // Enseñar el conteo cerrado sobre un borrador que sigue en disco es peor que no cerrarlo:
        // al siguiente arranque reaparece la tarjeta «sin terminar» de un conteo que el cajero da
        // por descartado, y bloquea empezar otro.
        val vm = buildViewModel()
        vm.resumeCount(conteoFull("a"))
        vm.contar(0, "2")
        store.discoRoto = true

        vm.pedirSalida(); vm.descartarConteo()

        assertTrue("la pantalla del conteo sigue abierta", vm.showCounting.value)
        assertNotNull("el borrador sigue en disco", store.borrador)
        assertTrue("y no se encoló una cancelación fantasma", store.cancelaciones.isEmpty())
        assertNotNull("se DICE", vm.errorMessage.value)
        coVerify(exactly = 0) { repository.cancelStockCount(any()) }
    }

    @Test
    fun `P1 un commit fallido no impide intentar el PUT - la red puede salvar lo que el disco no`() = runTest(scheduler) {
        // Disco y red son dos seguros independientes. Que falle uno no es motivo para renunciar al
        // otro: el servidor guardando la línea es exactamente la mitad que sí puede funcionar.
        val vm = buildViewModel()
        vm.resumeCount(conteoFull("a"))
        store.discoRoto = true
        eventos.clear()

        vm.contar(0, "3")

        assertEquals(listOf("guardar", "enviar"), eventos.take(2))
        coVerify(exactly = 1) { repository.enviarAvance("full-1", match { it.map { l -> l.id } == listOf("a") }) }
    }

    @Test
    fun `P1 coordinator real envia snapshot RAM conocido y conserva ACK si el store sigue roto`() = runTest(scheduler) {
        val monitor = mockk<ConnectivityMonitor> {
            every { isConnected } returns conectado
            every { isServerReachable } returns servidorAlcanzable
        }
        val coordinator = InventoryCountSyncCoordinator(store, repository, monitor)
        coordinator.start(backgroundScope)
        coEvery {
            repository.enviarAvance(VENUE, "full-1", any(), 4)
        } returns RespuestaHttp(200, """{"success":true,"revision":5}""")
        val vm = buildViewModel(coordinator = coordinator)
        vm.resumeCount(conteoFull("a"))
        store.discoRoto = true
        eventos.clear()

        vm.contar(0, "3")
        scheduler.advanceUntilIdle()

        coVerify(exactly = 1) {
            repository.enviarAvance(
                VENUE,
                "full-1",
                match { it.single().id == "a" && it.single().counted == 3.0 },
                4,
            )
        }
        assertNull("el store no finge durabilidad", store.borrador)
        assertEquals(5, vm.activeCount.value?.revision)
        assertEquals(5, vm.borradorLocal.value?.revision)
        assertEquals(emptySet<String>(), vm.borradorLocal.value?.pendientesDeEnviar)
        assertNotNull("el fallo durable sigue visible", vm.errorMessage.value)
        coVerify(exactly = 0) { repository.enviarFinal(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { repository.confirmarConteo(any(), any(), any()) }
    }

    @Test
    fun `P1 el PUT viejo no puede aterrizar DESPUES del cierre`() = runTest(scheduler) {
        // 🔴 Con el WiFi del ICP: sale el PUT de 5, tarda, el cajero corrige a 7 y toca
        // «Confirmar». Sin candado compartido el PUT final de 7 salía primero, el de 5 aterrizaba
        // después y el `/confirm` sellaba 5 — con el borrador borrado porque «todo salió bien».
        val vm = buildViewModel()
        val puerta = CompletableDeferred<Unit>()
        val orden = mutableListOf<String>()
        coEvery { repository.enviarAvance(any(), any()) } coAnswers {
            orden += "avance:" + secondArg<List<StockCountItem>>().joinToString { it.counted.toString() }
            puerta.await()
            orden += "avance-listo"
            RespuestaHttp(200, "{}")
        }
        val finales = mutableListOf<Double>()
        coEvery { repository.enviarFinal(any(), any(), any()) } coAnswers {
            finales += secondArg<List<StockCountItem>>().first().counted
            orden += "final"
            RespuestaHttp(200, "{}")
        }
        coEvery { repository.confirmarConteo(any()) } coAnswers { orden += "confirm"; RespuestaHttp(200, "{}") }

        vm.resumeCount(conteoFull("a", "b"))
        vm.contar(0, "5")                                                     // el PUT de 5 se queda en vuelo
        vm.selectCountItem(0); vm.updateCountedText("7"); vm.moveToNextItem()  // el cajero corrige
        vm.finishCounting()
        vm.confirmCount()                                                      // …y confirma con el PUT viejo vivo

        // El cierre no puede haber pasado por delante del avance en vuelo.
        assertEquals("el cierre espera su turno", emptyList<String>(), orden.filter { it == "final" })

        puerta.complete(Unit)
        scheduler.advanceUntilIdle()

        // Y ningún incremental ARRANCA durante el cierre: aterrizaría después del `/confirm`,
        // contra un conteo ya COMPLETED. El 7 no se pierde — lo lleva el PUT final, que manda
        // todas las líneas contadas.
        assertEquals(listOf("avance:5.0", "avance-listo", "final", "confirm"), orden)
        assertEquals("el servidor cierra con lo ÚLTIMO que se contó", listOf(7.0), finales)
    }

    @Test
    fun `P1 con el cierre en vuelo la captura se congela`() = runTest(scheduler) {
        // La otra mitad del mismo defecto: si se puede seguir tecleando durante «Confirmando…»,
        // el confirm sella el valor viejo y al salir bien borra el borrador con la corrección
        // dentro. La pantalla también bloquea BACK y «Atrás» mientras `isSaving`.
        val vm = buildViewModel()
        val puerta = CompletableDeferred<Unit>()
        coEvery { repository.enviarFinal(any(), any(), any()) } coAnswers { puerta.await(); RespuestaHttp(200, "{}") }

        vm.resumeCount(conteoFull("a", "b"))
        vm.contar(0, "5")
        vm.finishCounting()
        vm.confirmCount()
        assertTrue(vm.isSaving.value)

        vm.selectCountItem(1); vm.updateCountedText("99"); vm.moveToNextItem()
        vm.updateCountNote("no debe entrar")

        assertEquals("nada se teclea mientras se cierra", "", vm.countedText.value)
        assertFalse("y la línea no se sella", vm.countItems.value[1].yaSeConto)
        assertEquals("la nota también queda congelada", "", vm.countNote.value)

        puerta.complete(Unit)
        scheduler.advanceUntilIdle()
    }

    @Test
    fun `P1 un 404 en el PUT final es CONFLICTO, no un error generico`() = runTest(scheduler) {
        // Otro aparato cerró el conteo y este confirma sin un PUT incremental previo que lo
        // descubra. El 404 salía envuelto en un `Result.failure` sin código: «No se pudo guardar
        // el conteo. Intenta de nuevo.» — reintentar no puede funcionar NUNCA, y la banda de
        // conflicto no aparecía.
        val vm = buildViewModel()
        coEvery { repository.enviarFinal(any(), any(), any()) } returns
            RespuestaHttp(404, """{"message":"Conteo no encontrado o ya completado"}""")

        vm.resumeCount(conteoFull("a"))
        vm.contar(0, "2")
        vm.finishCounting(); vm.confirmCount()

        assertEquals(ConteoEnCurso.CONFLICTO, vm.conflictoDelServidor.value)
        assertEquals(ConteoEnCurso.CONFLICTO, vm.errorMessage.value)
        assertNotNull("lo contado se conserva", store.borrador)
        assertEquals(2.0, store.borrador!!.lineas[0].counted, 0.0)
        coVerify(exactly = 0) { repository.confirmarConteo(any()) }
        assertFalse(vm.isSaving.value)
    }

    @Test
    fun `P1 un 404 en el CONFIRM tambien es conflicto y no borra el borrador`() = runTest(scheduler) {
        val vm = buildViewModel()
        coEvery { repository.confirmarConteo(any()) } returns RespuestaHttp(404, """{"message":"Conteo no encontrado"}""")

        vm.resumeCount(conteoFull("a"))
        vm.contar(0, "2")
        vm.finishCounting(); vm.confirmCount()

        assertEquals(ConteoEnCurso.CONFLICTO, vm.conflictoDelServidor.value)
        assertNotNull("lo contado se conserva", store.borrador)
        assertTrue("la revisión sigue abierta", vm.showReview.value)
    }

    @Test
    fun `P1 retomar NO resucita una linea que el servidor ya reconocio`() = runTest(scheduler) {
        // A contó 5 y recibió 200 (sin pendientes). B corrigió a 8. Al retomar, lo local ganaba
        // por tener `countedAt` y al confirmar sellaba el 5: la corrección de B desaparecía.
        val vm = buildViewModel()
        store.borrador = BorradorDeConteo(
            venueId = VENUE, countId = "full-1", type = StockCountType.FULL,
            lineas = listOf(linea("a", counted = 5.0, countedAt = "t-local")),
            pendientesDeEnviar = emptySet(), actualizadoEn = 1L,
        )
        val servidor = StockCount(
            id = "full-1", type = StockCountType.FULL, status = "IN_PROGRESS", itemCount = 1,
            items = listOf(linea("a", counted = 8.0, countedAt = "t-otro-aparato")),
        )

        vm.resumeCount(servidor)

        assertEquals(8.0, vm.countItems.value[0].counted, 0.0)
    }

    @Test
    fun `P1 un 404 de RUTA conserva la cancelacion en la cola`() = runTest(scheduler) {
        // App nueva contra un servidor sin la fase 1: `POST …/cancel` no existe y Express contesta
        // su HTML. Tratarlo como «hecha» dejaba ese conteo IN_PROGRESS para siempre.
        val vm = buildViewModel()
        coEvery { repository.cancelStockCount("full-1") } returns
            RespuestaHttp(404, "<html><body><pre>Cannot POST /api/v1/mobile/venues/v/inventory/stock-counts/full-1/cancel</pre></body></html>")
        vm.resumeCount(conteoFull("a"))
        vm.contar(0, "1")
        vm.pedirSalida(); vm.descartarConteo()

        assertEquals("la cola espera al despliegue", listOf("full-1"), store.cancelaciones)

        // Y cuando el servidor SÍ tiene la ruta, la misma cola se vacía.
        coEvery { repository.cancelStockCount("full-1") } returns RespuestaHttp(404, """{"message":"Conteo no encontrado"}""")
        conectado.value = false; conectado.value = true

        assertTrue(store.cancelaciones.isEmpty())
    }

    @Test
    fun `P1 el servidor caido con el WiFi en pie cuenta como sin red`() = runTest(scheduler) {
        // El WiFi de la tienda sigue en pie y el túnel o la API están muertos. Mirando sólo
        // `isConnected`, la banda callaba y «Confirmar» tocaba la red para nada.
        val vm = buildViewModel()
        coEvery { repository.enviarAvance(any(), any()) } returns RespuestaHttp(0, "")
        vm.resumeCount(conteoFull("a", "b"))
        vm.contar(0, "3")

        servidorAlcanzable.value = false

        assertEquals(ConteoEnCurso.avisoSinRed(1), vm.bandaDeAviso.value)

        vm.finishCounting(); vm.confirmCount()
        coVerify(exactly = 0) { repository.enviarFinal(any(), any(), any()) }
        assertEquals(ConteoEnCurso.CONFIRMAR_SIN_RED, vm.errorMessage.value)

        // Y al volver el SERVIDOR —sin que el WiFi se haya movido— sale lo pendiente. Antes no se
        // reproducía nada: `isConnected` nunca volvía a emitir.
        val enviadas = slot<List<StockCountItem>>()
        coEvery { repository.enviarAvance("full-1", capture(enviadas)) } returns RespuestaHttp(200, "{}")
        servidorAlcanzable.value = true

        assertEquals(listOf("a"), enviadas.captured.map { it.id })
        assertTrue(vm.pendientesDeEnviar.value.isEmpty())
    }

    @Test
    fun `P1 cambiar de sucursal a media captura no manda el conteo a la sucursal equivocada`() = runTest(scheduler) {
        // 🔴 El id del conteo sólo existe en `/venues/<la de origen>/…`. Sin ancla, el avance se
        // mandaba a `/venues/<la otra>/…`, el servidor contestaba 404 y la pantalla lo traducía a
        // «este conteo ya se cerró desde otro aparato»: un CONFLICTO falso que además apaga el
        // envío para el resto de la sesión, sobre trabajo que estaba perfectamente bien.
        val vm = buildViewModel()
        vm.resumeCount(conteoFull("a", "b"))
        vm.contar(0, "3")
        val borradorAntes = store.borrador
        assertNotNull(borradorAntes)

        // El cajero cambia de sucursal sin cerrar sesión.
        venueActual = "otra-sucursal"
        store.venueDelAparato = "otra-sucursal"
        vm.clearErrorMessage()
        eventos.clear()

        vm.contar(1, "4")

        coVerify(exactly = 1) { repository.enviarAvance(any(), any()) } // sólo el de ANTES del cambio
        assertEquals(ConteoEnCurso.cambiasteDeSucursal("Testarudo Centro"), vm.errorMessage.value)
        assertNull("no hay conflicto que inventar", vm.conflictoDelServidor.value)
        assertEquals("el borrador de la sucursal original queda intacto", borradorAntes, store.borrador)

        // Confirmar tampoco toca la red…
        vm.clearErrorMessage()
        vm.finishCounting(); vm.confirmCount()
        coVerify(exactly = 0) { repository.enviarFinal(any(), any(), any()) }
        assertEquals(ConteoEnCurso.cambiasteDeSucursal("Testarudo Centro"), vm.errorMessage.value)

        // …ni descartar: el borrador es de la OTRA sucursal y allá no se puede ni borrar ni cancelar.
        vm.clearErrorMessage()
        vm.descartarConteo()
        coVerify(exactly = 0) { repository.cancelStockCount(any()) }
        assertEquals(borradorAntes, store.borrador)
        assertEquals(ConteoEnCurso.cambiasteDeSucursal("Testarudo Centro"), vm.errorMessage.value)
    }

    @Test
    fun `P2 si sólo fallaron los INSUMOS se piden sólo ellos`() = runTest(scheduler) {
        // `stock-overview` volvió y `raw-materials` no. Cortando por la primera lista, los insumos
        // no reaparecían NUNCA y el cíclico se quedaba sin la mitad de lo que se puede contar
        // (P2 #2 de Codex). Y no se re-descarga el catálogo entero por eso.
        conectado.value = false
        buildViewModel(catalogo = listOf(StockItem(id = "p1", name = "Uno", onHand = 1.0)), insumos = emptyList())

        conectado.value = true

        coVerify(exactly = 0) { repository.fetchStockOverview() }
        coVerify(exactly = 1) { repository.fetchRawMaterials() }
    }

    @Test
    fun `P2 lo contado con el PUT en vuelo sale al cerrar la pantalla, sin esperar a la siguiente tecla`() = runTest(scheduler) {
        // PUT de A en vuelo → se cuenta B → «Guardar el avance» limpia la memoria → llega el 200 de
        // A. La cola en memoria ya está vacía, así que el encadenado no veía a B: se quedaba en el
        // aparato hasta otra tecla, la reconexión o reabrir el conteo (P2 #1 de Codex).
        val vm = buildViewModel()
        val puerta = CompletableDeferred<Unit>()
        val enviados = mutableListOf<List<String>>()
        coEvery { repository.enviarAvance(any(), any()) } coAnswers {
            enviados += secondArg<List<StockCountItem>>().map { it.id }
            if (enviados.size == 1) puerta.await()
            RespuestaHttp(200, "{}")
        }
        vm.resumeCount(conteoFull("a", "b"))
        vm.contar(0, "5")   // el PUT de [a] se queda en vuelo
        vm.contar(1, "2")   // b entra a la cola mientras a viaja
        vm.guardarYSalir()  // la pantalla se cierra con el PUT todavía vivo
        assertNull(vm.activeCount.value)

        puerta.complete(Unit)
        scheduler.advanceUntilIdle()

        assertEquals(listOf(listOf("a"), listOf("b")), enviados)
        assertTrue("el borrador queda sin pendientes", store.borrador!!.pendientesDeEnviar.isEmpty())
    }

    @Test
    fun `P2 confirmar limpia el detalle desde el que se retomó`() = runTest(scheduler) {
        // El detalle guarda una FOTO en `IN_PROGRESS`: sin limpiarlo, al confirmar reaparece
        // diciendo «En progreso · Continuar conteo» sobre un conteo que acaba de cerrarse
        // (P2 #3 de Codex).
        val vm = buildViewModel()
        val enElServidor = conteoFull("a")
        vm.selectCountDetail(enElServidor)
        vm.resumeCount(enElServidor)
        vm.contar(0, "3")
        vm.finishCounting(); vm.confirmCount()

        assertNull(vm.selectedDetail.value)
    }

    @Test
    fun `M1 un rechazo 400 conserva una banda neutral sin reintentar en bucle`() = runTest(scheduler) {
        val vm = buildViewModel()
        coEvery { repository.enviarAvance(any(), any()) } returns
            RespuestaHttp(400, """{"message":"Línea inválida"}""")

        vm.resumeCount(conteoFull("a"))
        vm.contar(0, "2")

        coVerify(exactly = 1) { repository.enviarAvance(any(), any()) }
        assertEquals("1 línea guardada en este aparato sin subir", vm.bandaDeAviso.value)
        assertEquals(setOf("a"), vm.pendientesDeEnviar.value)
    }

    @Test
    fun `M1 un rechazo 403 conserva una banda neutral sin reintentar en bucle`() = runTest(scheduler) {
        val vm = buildViewModel()
        coEvery { repository.enviarAvance(any(), any()) } returns
            RespuestaHttp(403, """{"message":"Sin permiso"}""")

        vm.resumeCount(conteoFull("a"))
        vm.contar(0, "2")

        coVerify(exactly = 1) { repository.enviarAvance(any(), any()) }
        assertEquals("1 línea guardada en este aparato sin subir", vm.bandaDeAviso.value)
        assertEquals(setOf("a"), vm.pendientesDeEnviar.value)
    }

    @Test
    fun `M1 un 5xx conserva la banda sin bucle y un ACK posterior la apaga`() = runTest(scheduler) {
        val vm = buildViewModel()
        coEvery { repository.enviarAvance(any(), any()) } returnsMany listOf(
            RespuestaHttp(503, """{"message":"Servicio no disponible"}"""),
            RespuestaHttp(200, "{}"),
        )

        vm.resumeCount(conteoFull("a"))
        vm.contar(0, "2")

        coVerify(exactly = 1) { repository.enviarAvance(any(), any()) }
        assertEquals("1 línea guardada en este aparato sin subir", vm.bandaDeAviso.value)

        vm.selectCountItem(0)
        vm.updateCountedText("2")
        vm.moveToNextItem()

        coVerify(exactly = 2) { repository.enviarAvance(any(), any()) }
        assertTrue(vm.pendientesDeEnviar.value.isEmpty())
        assertNull("el ACK deja cero trabajo sólo local", vm.bandaDeAviso.value)
    }

    @Test
    fun `M2 una nota de borrador viejo bloquea reemplazar un conteo sincronizado`() = runTest(scheduler) {
        val vm = buildViewModel()
        store.borrador = BorradorDeConteo(
            venueId = VENUE,
            countId = "full-7",
            type = StockCountType.FULL,
            lineas = listOf(linea("a", counted = 3.0, countedAt = "t")),
            nota = "nota que el servidor no conoce",
            pendientesDeEnviar = emptySet(),
            actualizadoEn = 1L,
        )

        vm.startFullCount()

        assertEquals(ConteoEnCurso.HAY_OTRO_BORRADOR, vm.errorMessage.value)
        coVerify(exactly = 0) { repository.createStockCount(any(), any(), any()) }
        assertEquals("nota que el servidor no conoce", store.borrador?.nota)
    }

    @Test
    fun `M2 un ciclico con sólo nota bloquea reemplazar su borrador`() = runTest(scheduler) {
        val vm = buildViewModel()
        store.borrador = BorradorDeConteo(
            venueId = VENUE,
            countId = null,
            type = StockCountType.CYCLE,
            lineas = emptyList(),
            nota = "revisar cámara fría",
            actualizadoEn = 1L,
        )

        vm.startCycleCount()

        assertEquals(ConteoEnCurso.HAY_OTRO_BORRADOR, vm.errorMessage.value)
        assertFalse(vm.showCounting.value)
        assertEquals("revisar cámara fría", store.borrador?.nota)
    }

    @Test
    fun `M2 vaciar una nota local se conserva como intención pendiente`() = runTest(scheduler) {
        val vm = buildViewModel()
        val remoto = conteoFull("a").copy(note = "nota del servidor")
        vm.resumeCount(remoto)
        vm.updateCountNote("")

        vm.pedirSalida()

        assertNotNull("vaciar la nota también es trabajo local", store.borrador)
        assertEquals("", store.borrador?.nota)
        assertEquals(true, store.borrador?.notaPendienteDeEnviar)

        vm.guardarYSalir()
        vm.startCycleCount()
        assertEquals(ConteoEnCurso.HAY_OTRO_BORRADOR, vm.errorMessage.value)
    }

    @Test
    fun `M2 una nota sincronizada cede ante una nota más nueva del servidor`() = runTest(scheduler) {
        store.borrador = BorradorDeConteo(
            venueId = VENUE,
            countId = "full-1",
            type = StockCountType.FULL,
            lineas = listOf(linea("a", counted = 3.0, countedAt = "t-local")),
            nota = "nota sincronizada vieja",
            notaPendienteDeEnviar = false,
            pendientesDeEnviar = emptySet(),
            actualizadoEn = 1L,
        )
        val vm = buildViewModel()
        val remoto = conteoFull("a").copy(
            note = "nota corregida en otra tablet",
            items = listOf(linea("a", counted = 8.0, countedAt = "t-otra-tablet")),
        )

        vm.resumeCount(remoto)

        assertEquals("nota corregida en otra tablet", vm.countNote.value)
        assertEquals(false, store.borrador?.notaPendienteDeEnviar)
    }

    @Test
    fun `M2 sin lista remota una nota marcada sincronizada conserva su baseline`() = runTest(scheduler) {
        store.borrador = BorradorDeConteo(
            venueId = VENUE,
            countId = "full-1",
            type = StockCountType.FULL,
            lineas = listOf(linea("a", counted = 3.0, countedAt = "t-local")),
            nota = "nota sincronizada",
            notaPendienteDeEnviar = false,
            pendientesDeEnviar = emptySet(),
            revision = 4,
            actualizadoEn = 1L,
        )
        val vm = buildViewModel()
        vm.refrescarBorradorLocal()
        vm.continuarBorrador()

        vm.updateCountNote("cambio temporal")
        vm.updateCountNote("nota sincronizada")
        vm.guardarYSalir()
        vm.startCycleCount()

        assertNull(vm.errorMessage.value)
        assertTrue(vm.showCounting.value)
    }

    @Test
    fun `M2 un PUT 200 reconoce el borrado enviado como nota vacía`() = runTest(scheduler) {
        val vm = buildViewModel()
        coEvery { repository.confirmarConteo(any()) } returns RespuestaHttp(503, "")
        vm.resumeCount(conteoFull("a").copy(note = "nota del servidor"))
        vm.updateCountNote("")
        vm.finishCounting()

        vm.confirmCount()

        assertNotNull(store.borrador)
        assertEquals("", store.borrador?.nota)
        assertEquals(false, store.borrador?.notaPendienteDeEnviar)
        coVerify(exactly = 1) { repository.enviarFinal("full-1", any(), "") }
    }

    @Test
    fun `M3 el ACK del PUT final se persiste antes de un confirm fallido`() = runTest(scheduler) {
        val vm = buildViewModel()
        coEvery { repository.enviarAvance(any(), any()) } returns RespuestaHttp(503, "")
        coEvery { repository.confirmarConteo("full-1") } coAnswers {
            assertEquals("el ACK de líneas llega al disco antes del confirm", emptySet<String>(), store.borrador?.pendientesDeEnviar)
            assertEquals("el ACK de nota llega al disco antes del confirm", false, store.borrador?.notaPendienteDeEnviar)
            RespuestaHttp(503, "")
        }
        vm.resumeCount(conteoFull("a").copy(note = "nota anterior"))
        vm.contar(0, "5")
        vm.updateCountNote("nota local")
        vm.finishCounting()

        vm.confirmCount()

        assertEquals(emptySet<String>(), store.borrador?.pendientesDeEnviar)
        assertEquals(false, store.borrador?.notaPendienteDeEnviar)

        val corregido = StockCount(
            id = "full-1",
            type = StockCountType.FULL,
            status = "IN_PROGRESS",
            itemCount = 1,
            note = "nota más nueva del servidor",
            items = listOf(linea("a", counted = 8.0, countedAt = "t-otra-tablet")),
        )
        val nuevoVm = buildViewModel(conteos = listOf(corregido))
        nuevoVm.refrescarBorradorLocal()
        nuevoVm.continuarBorrador()

        assertEquals(8.0, nuevoVm.countItems.value.single().counted, 0.0)
        assertEquals("nota más nueva del servidor", nuevoVm.countNote.value)
    }

    @Test
    fun `M3 create OK y PUT fallido sobreviven reinicio y reutilizan el countId`() = runTest(scheduler) {
        val creado = StockCount(
            id = "cyc-9",
            type = StockCountType.CYCLE,
            status = "IN_PROGRESS",
            itemCount = 1,
            revision = 0,
            items = listOf(linea("srv-1")),
        )
        val primero = buildViewModel()
        coEvery { repository.createStockCount(StockCountType.CYCLE, any(), any()) } returns Result.success(creado)
        var intentosFinales = 0
        coEvery { repository.enviarFinal(any(), any(), any()) } coAnswers {
            intentosFinales++
            if (intentosFinales == 1) {
                assertEquals("el countId se guarda antes del PUT", "cyc-9", store.borrador?.countId)
                assertEquals("los ids ya son los del servidor", listOf("srv-1"), store.borrador?.lineas?.map { it.id })
                assertEquals("lo remapeado se marca pendiente", setOf("srv-1"), store.borrador?.pendientesDeEnviar)
                RespuestaHttp(503, """{"message":"Temporal"}""")
            } else {
                RespuestaHttp(200, "{}")
            }
        }
        coEvery { repository.enviarAvance(any(), any()) } returns RespuestaHttp(503, "")

        primero.startCycleCount()
        primero.addItemsToCycleCount(listOf(StockItem(id = "p-srv-1", name = "Prod", onHand = 10.0)))
        primero.contar(0, "5")
        val countedAtLocal = primero.countItems.value.single().countedAt
        primero.finishCounting()
        primero.confirmCount()

        assertEquals("cyc-9", store.borrador?.countId)
        assertEquals(listOf("srv-1"), store.borrador?.lineas?.map { it.id })
        assertEquals(setOf("srv-1"), store.borrador?.pendientesDeEnviar)
        assertEquals(5.0, store.borrador!!.lineas.single().counted, 0.0)
        assertEquals(countedAtLocal, store.borrador!!.lineas.single().countedAt)

        val segundo = buildViewModel(
            conteos = listOf(creado),
            respuestaAvance = RespuestaHttp(503, ""),
        )
        coEvery { repository.enviarFinal(any(), any(), any()) } returns RespuestaHttp(200, "{}")
        coEvery { repository.confirmarConteo("cyc-9") } coAnswers {
            assertEquals("el ACK final se guarda antes del confirm", emptySet<String>(), store.borrador?.pendientesDeEnviar)
            RespuestaHttp(200, "{}")
        }
        segundo.refrescarBorradorLocal()
        segundo.continuarBorrador()

        assertEquals("cyc-9", segundo.activeCount.value?.id)
        assertEquals(5.0, segundo.countItems.value.single().counted, 0.0)
        assertEquals(countedAtLocal, segundo.countItems.value.single().countedAt)

        segundo.finishCounting()
        segundo.confirmCount()

        coVerify(exactly = 1) { repository.createStockCount(StockCountType.CYCLE, any(), any()) }
        coVerify(exactly = 1) { repository.confirmarConteo("cyc-9") }
    }

    @Test
    fun `M5 un estado FULL sin conteo activo falla visible y sin tocar la red`() = runTest(scheduler) {
        val vm = buildViewModel()
        val field = InventoryViewModel::class.java.getDeclaredField("_activeCountType")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val type = field.get(vm) as MutableStateFlow<StockCountType>
        type.value = StockCountType.FULL

        vm.confirmCount()

        assertEquals("No hay un conteo activo para confirmar. Vuelve a abrirlo e intenta de nuevo.", vm.errorMessage.value)
        assertFalse(vm.isSaving.value)
        coVerify(exactly = 0) { repository.createStockCount(any(), any(), any()) }
        coVerify(exactly = 0) { repository.enviarFinal(any(), any(), any()) }
        coVerify(exactly = 0) { repository.confirmarConteo(any()) }
    }

    @Test
    fun `P1 un PUT final 200 cuyo ACK durable falla no cierra ni borra el borrador`() = runTest(scheduler) {
        val monitor = mockk<ConnectivityMonitor> {
            every { isConnected } returns conectado
            every { isServerReachable } returns servidorAlcanzable
        }
        val coordinator = InventoryCountSyncCoordinator(store, repository, monitor)
        coordinator.start(backgroundScope)
        coEvery {
            repository.enviarFinal(VENUE, "full-1", any(), null, 4)
        } returns RespuestaHttp(200, """{"success":true,"revision":5}""")

        val contado = conteoFull("a").copy(
            items = listOf(linea("a", counted = 5.0, countedAt = "t-local")),
        )
        val vm = buildViewModel(coordinator = coordinator)
        vm.resumeCount(contado)
        vm.finishCounting()
        eventos.clear()

        vm.confirmCount()

        assertTrue("el review sigue abierto", vm.showReview.value)
        assertNotNull("el borrador sin stage sigue disponible", store.borrador)
        assertFalse("un PUT 200 solo nunca autoriza borrar", eventos.contains("borrar"))
        coVerify(exactly = 0) { repository.confirmarConteo(VENUE, "full-1", any()) }
    }

    @Test
    fun `P1 fallback no confirma si falla persistir el ACK del PUT final`() = runTest(scheduler) {
        val vm = buildViewModel()
        vm.resumeCount(conteoFull("a"))
        vm.contar(0, "5")
        vm.finishCounting()
        coEvery { repository.enviarFinal("full-1", any(), null) } coAnswers {
            // La escritura previa al HTTP sí quedó; falla exactamente el guardado del ACK.
            store.discoRoto = true
            RespuestaHttp(200, "{}")
        }

        vm.confirmCount()

        assertTrue(vm.showReview.value)
        assertNotNull("la copia RAM reconocida sigue disponible", vm.borradorLocal.value)
        assertEquals(
            "No pudimos guardar este avance en el aparato. Mantén este conteo abierto y vuelve a intentarlo.",
            vm.errorMessage.value,
        )
        coVerify(exactly = 1) { repository.enviarFinal("full-1", any(), null) }
        coVerify(exactly = 0) { repository.confirmarConteo("full-1") }
    }

    @Test
    fun `P1 cierre feliz con coordinator real borra el draft y refresca existencias`() = runTest(scheduler) {
        store.reconocePut = true
        val monitor = mockk<ConnectivityMonitor> {
            every { isConnected } returns conectado
            every { isServerReachable } returns servidorAlcanzable
        }
        val stockRefresher = mockk<StockRefresher>(relaxed = true)
        val coordinator = InventoryCountSyncCoordinator(store, repository, monitor)
        coordinator.start(backgroundScope)
        coEvery {
            repository.enviarFinal(VENUE, "full-1", any(), null, 4)
        } returns RespuestaHttp(200, """{"success":true,"revision":5}""")
        coEvery {
            repository.confirmarConteo(VENUE, "full-1", 5)
        } returns RespuestaHttp(200, """{"success":true,"revision":6}""")
        val vm = buildViewModel(coordinator = coordinator, stockRefresher = stockRefresher)
        vm.resumeCount(
            conteoFull("a").copy(
                items = listOf(linea("a", counted = 5.0, countedAt = "t-local")),
            ),
        )
        vm.finishCounting()

        vm.confirmCount()

        assertNull(store.borrador)
        assertFalse(vm.showReview.value)
        assertNull(vm.activeCount.value)
        coVerify(exactly = 1) { repository.enviarFinal(VENUE, "full-1", any(), null, 4) }
        coVerify(exactly = 1) { repository.confirmarConteo(VENUE, "full-1", 5) }
        coVerify(exactly = 1) { stockRefresher.refreshAfterStockChange() }
    }

    @Test
    fun `P1 VM recreado con GET completed stage mas uno permite retry confirm only`() = runTest(scheduler) {
        store.reconocePut = true
        val monitor = mockk<ConnectivityMonitor> {
            every { isConnected } returns conectado
            every { isServerReachable } returns servidorAlcanzable
        }
        var intentosConfirm = 0
        coEvery {
            repository.enviarFinal(VENUE, "full-1", any(), null, 4)
        } returns RespuestaHttp(200, """{"success":true,"revision":5}""")
        coEvery { repository.confirmarConteo(VENUE, "full-1", 5) } coAnswers {
            intentosConfirm += 1
            if (intentosConfirm == 1) {
                RespuestaHttp(
                    409,
                    """{"message":"aplicando","code":"STOCK_COUNT_APPLYING","details":{"venueId":"$VENUE","countId":"full-1","currentRevision":5,"status":"APPLYING"}}""",
                )
            } else {
                RespuestaHttp(200, """{"success":true,"revision":6}""")
            }
        }

        val primero = InventoryCountSyncCoordinator(store, repository, monitor)
        primero.start(backgroundScope)
        val vmAntesDeMorir = buildViewModel(coordinator = primero)
        vmAntesDeMorir.resumeCount(
            conteoFull("a").copy(
                items = listOf(linea("a", counted = 5.0, countedAt = "t-local")),
            ),
        )
        vmAntesDeMorir.finishCounting()
        vmAntesDeMorir.confirmCount()

        assertEquals(5, store.borrador?.revisionConPutFinalConfirmado)
        assertTrue(vmAntesDeMorir.showReview.value)
        primero.stop()

        val completadoEnServidor = conteoFull("a").copy(
            status = "COMPLETED",
            revision = 6,
            items = listOf(linea("a", counted = 9.0, countedAt = "t-remoto")),
        )
        val recreado = InventoryCountSyncCoordinator(store, repository, monitor)
        recreado.start(backgroundScope)
        val vmRecreado = buildViewModel(
            conteos = listOf(completadoEnServidor),
            coordinator = recreado,
        )
        vmRecreado.refrescarBorradorLocal()

        vmRecreado.continuarBorrador()

        assertTrue("el COMPLETED conocido no bloquea la recuperación manual", vmRecreado.showCounting.value)
        assertNull(vmRecreado.conflictoDelServidor.value)
        assertNull(vmRecreado.conflictoDeRevision.value)
        assertEquals(5.0, vmRecreado.countItems.value.single().counted, 0.0)
        vmRecreado.finishCounting()
        vmRecreado.confirmCount()

        assertNull(store.borrador)
        assertFalse(vmRecreado.showReview.value)
        coVerify(exactly = 1) { repository.enviarFinal(VENUE, "full-1", any(), null, 4) }
        coVerify(exactly = 2) { repository.confirmarConteo(VENUE, "full-1", 5) }
    }

    @Test
    fun `P1 cambiar de venue durante confirm no cierra la UI ni borra el borrador de B`() = runTest(scheduler) {
        val empezo = CompletableDeferred<Unit>()
        val responder = CompletableDeferred<Unit>()
        val cambios = MutableSharedFlow<com.avoqado.pos.inventory.data.CambioDeSyncDeInventario>()
        val coordinator = mockk<InventoryCountSyncCoordinator>(relaxed = true)
        every { coordinator.cambios } returns cambios
        coEvery { coordinator.cerrarManualmente(VENUE, "full-1") } coAnswers {
            empezo.complete(Unit)
            responder.await()
            ResultadoDeCierreCoordinado(
                confirm = RespuestaHttp(200, """{"success":true,"revision":6}"""),
                completado = true,
            )
        }
        val contado = conteoFull("a").copy(
            items = listOf(linea("a", counted = 5.0, countedAt = "t-local")),
        )
        val vm = buildViewModel(coordinator = coordinator)
        vm.resumeCount(contado)
        vm.finishCounting()
        vm.confirmCount()
        empezo.await()

        venueActual = "b"
        store.venueDelAparato = "b"
        store.borrador = BorradorDeConteo(
            venueId = "b",
            countId = "full-b",
            type = StockCountType.FULL,
            lineas = listOf(linea("b", counted = 2.0, countedAt = "t-b")),
            revision = 9,
            actualizadoEn = 2L,
        )
        vm.refrescarBorradorLocal()
        assertEquals("full-b", vm.borradorLocal.value?.countId)
        responder.complete(Unit)

        assertTrue("el resultado de A no cierra una pantalla ya fuera de A", vm.showReview.value)
        assertEquals("full-b", store.borrador?.countId)
        assertEquals("un resultado tardío de A no inyecta su snapshot en B", "full-b", vm.borradorLocal.value?.countId)
        assertFalse(eventos.contains("borrar"))
    }

    @Test
    fun `P1 un fallo 500 del coordinator queda visible y conserva el review`() = runTest(scheduler) {
        val cambios = MutableSharedFlow<com.avoqado.pos.inventory.data.CambioDeSyncDeInventario>()
        val coordinator = mockk<InventoryCountSyncCoordinator>(relaxed = true)
        every { coordinator.cambios } returns cambios
        coEvery { coordinator.cerrarManualmente(VENUE, "full-1") } returns ResultadoDeCierreCoordinado(
            put = RespuestaHttp(500, """{"message":"Temporal"}"""),
            completado = false,
        )
        val vm = buildViewModel(coordinator = coordinator)
        vm.resumeCount(
            conteoFull("a").copy(
                items = listOf(linea("a", counted = 5.0, countedAt = "t-local")),
            ),
        )
        vm.finishCounting()

        vm.confirmCount()

        assertTrue(vm.showReview.value)
        assertNotNull(vm.errorMessage.value)
        assertNotNull(store.borrador)
    }

    @Test
    fun `P1 create ciclico tardio persiste el remap en A sin tocar la UI ni borrador de B`() = runTest(scheduler) {
        val porVenue = mutableMapOf<String, BorradorDeConteo>()
        val multiStore = mockk<BorradorDeConteoStore>(relaxed = true)
        every { multiStore.leer() } answers { venueActual?.let(porVenue::get) }
        every { multiStore.leer(any()) } answers { porVenue[firstArg()] }
        every { multiStore.guardar(any<String>(), any()) } answers {
            porVenue[firstArg()] = secondArg<BorradorDeConteo>()
            true
        }
        every { multiStore.guardarEdicion(any(), any()) } answers {
            porVenue[firstArg()] = secondArg<BorradorDeConteo>()
            true
        }
        every { multiStore.borrar() } answers {
            venueActual?.let(porVenue::remove)
            true
        }
        every { multiStore.borrar(any()) } answers {
            porVenue.remove(firstArg())
            true
        }
        every { multiStore.cancelacionesPendientes() } returns emptyList()
        every { multiStore.cancelacionesPendientes(any()) } returns emptyList()

        val empezo = CompletableDeferred<Unit>()
        val responder = CompletableDeferred<Unit>()
        val creado = StockCount(
            id = "cycle-a",
            type = StockCountType.CYCLE,
            status = "IN_PROGRESS",
            itemCount = 1,
            revision = 0,
            items = listOf(
                StockCountItem(
                    id = "server-line-a",
                    productId = "product-a",
                    productName = "Producto A",
                    expected = 10.0,
                ),
            ),
        )
        coEvery { repository.createStockCount(StockCountType.CYCLE, any(), any()) } coAnswers {
            empezo.complete(Unit)
            responder.await()
            Result.success(creado)
        }
        val cambios = MutableSharedFlow<com.avoqado.pos.inventory.data.CambioDeSyncDeInventario>()
        val coordinator = mockk<InventoryCountSyncCoordinator>(relaxed = true)
        every { coordinator.cambios } returns cambios
        val vm = buildViewModel(draftStore = multiStore, coordinator = coordinator)
        vm.startCycleCount()
        vm.addItemsToCycleCount(listOf(StockItem(id = "product-a", name = "Producto A", onHand = 10.0)))
        vm.contar(0, "5")
        vm.finishCounting()
        vm.confirmCount()
        empezo.await()

        venueActual = "b"
        porVenue["b"] = BorradorDeConteo(
            venueId = "b",
            countId = "full-b",
            type = StockCountType.FULL,
            lineas = listOf(linea("b", counted = 2.0, countedAt = "t-b")),
            revision = 9,
            actualizadoEn = 2L,
        )
        vm.refrescarBorradorLocal()
        responder.complete(Unit)

        val durableA = porVenue[VENUE]!!
        assertEquals("cycle-a", durableA.countId)
        assertEquals(0, durableA.revision)
        assertEquals(listOf("server-line-a"), durableA.lineas.map { it.id })
        assertEquals(5.0, durableA.lineas.single().counted, 0.0)
        assertEquals(setOf("server-line-a"), durableA.pendientesDeEnviar)
        assertEquals("full-b", porVenue["b"]?.countId)
        assertEquals("full-b", vm.borradorLocal.value?.countId)
        verify { coordinator.solicitarSync() }
        coVerify(exactly = 0) { coordinator.cerrarManualmente(any(), any()) }
        coVerify(exactly = 0) { repository.enviarFinal(any(), any(), any()) }
    }
}
