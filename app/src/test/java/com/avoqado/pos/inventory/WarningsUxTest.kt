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
import com.avoqado.pos.inventory.data.InventoryRepository
import com.avoqado.pos.inventory.data.RespuestaHttp
import com.avoqado.pos.inventory.data.model.StockCount
import com.avoqado.pos.inventory.data.model.StockCountItem
import com.avoqado.pos.inventory.data.model.StockCountType
import com.avoqado.pos.inventory.domain.StockRefresher
import com.avoqado.pos.inventory.presentation.EstadoDelDetalleDeConteo
import com.avoqado.pos.inventory.presentation.InventoryViewModel
import com.avoqado.pos.scale.ScaleSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class WarningsUxTest {
    private val scheduler = TestCoroutineScheduler()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(UnconfinedTestDispatcher(scheduler))

    private val repository = mockk<InventoryRepository>(relaxed = true)
    private val counts = MutableStateFlow<List<StockCount>>(emptyList())
    private var venue = "venue-a"

    private class RecordingStore : BorradorDeConteoStore {
        var draft: BorradorDeConteo? = null
        val cancellations = mutableListOf<String>()
        var saves = 0
        var discards = 0

        override fun leer() = draft
        override fun guardar(borrador: BorradorDeConteo): Boolean {
            saves += 1
            draft = borrador
            return true
        }
        override fun borrar(): Boolean {
            draft = null
            return true
        }
        override fun cancelacionesPendientes() = cancellations.toList()
        override fun agregarCancelacionPendiente(countId: String): Boolean {
            if (countId.isNotBlank() && countId !in cancellations) cancellations += countId
            return true
        }
        override fun quitarCancelacionPendiente(countId: String): Boolean {
            cancellations -= countId
            return true
        }
        override fun descartarYEncolarCancelacion(countId: String): Boolean {
            discards += 1
            draft = null
            if (countId.isNotBlank() && countId !in cancellations) cancellations += countId
            return true
        }
    }

    private fun count(id: String, status: String = "IN_PROGRESS", countedAt: String? = null) = StockCount(
        id = id,
        type = StockCountType.FULL,
        status = status,
        itemCount = 1,
        items = listOf(
            StockCountItem(
                id = "line-$id",
                productId = "product-$id",
                productName = "Producto $id",
                expected = 5.0,
                counted = 0.0,
                difference = -5.0,
                countedAt = countedAt,
            ),
        ),
    )

    private fun emptyStore(): BorradorDeConteoStore = mockk(relaxed = true) {
        every { leer() } returns null
        every { cancelacionesPendientes() } returns emptyList()
    }

    private fun viewModel(store: BorradorDeConteoStore = emptyStore()): InventoryViewModel {
        val factory = mockk<RefreshGateFactory>()
        every { factory.create(any(), any()) } returns RefreshGate(clock = { Duration.ZERO }, random = { 0.5 })
        every { repository.stockCounts } returns counts
        every { repository.stockItems } returns MutableStateFlow(emptyList())
        every { repository.countableRawMaterials } returns MutableStateFlow(emptyList())
        every { repository.venueIdActual() } answers { venue }
        every { repository.venueNameActual() } returns "Sucursal A"
        coEvery { repository.fetchStockCounts() } returns Result.success(Unit)
        coEvery { repository.enviarAvance(any(), any()) } returns RespuestaHttp(200, "{}")
        coEvery { repository.cancelStockCount(any()) } returns RespuestaHttp(0, "sin red")
        return InventoryViewModel(
            repository = repository,
            planManager = mockk<PlanManager>(relaxed = true),
            scaleSettingsRepository = mockk<ScaleSettingsRepository>(relaxed = true),
            stockRefresher = mockk<StockRefresher>(relaxed = true),
            refreshGateFactory = factory,
            borradores = store,
            connectivityMonitor = mockk<ConnectivityMonitor>().also {
                every { it.isConnected } returns MutableStateFlow(true)
                every { it.isServerReachable } returns MutableStateFlow(true)
            },
        )
    }

    @Test
    fun `W3 un refresh exitoso reemplaza la copia seleccionada y uno fallido conserva lo conocido`() = runTest(scheduler) {
        val vm = viewModel()
        val stale = count("a")
        val completed = count("a", status = "COMPLETED")
        vm.selectCountDetail(stale)
        coEvery { repository.fetchStockCounts() } coAnswers {
            counts.value = listOf(completed)
            Result.success(Unit)
        }

        vm.refreshSelectedCountDetail()

        assertEquals(completed, vm.selectedDetail.value)
        assertEquals(EstadoDelDetalleDeConteo.DISPONIBLE, vm.estadoDelDetalle.value)
        assertFalse(vm.puedeContinuarDetalle.value)

        coEvery { repository.fetchStockCounts() } returns Result.failure(IllegalStateException("sin red"))
        vm.refreshSelectedCountDetail()

        assertEquals(completed, vm.selectedDetail.value)
        assertEquals(EstadoDelDetalleDeConteo.DISPONIBLE, vm.estadoDelDetalle.value)
        assertFalse(vm.puedeContinuarDetalle.value)
    }

    @Test
    fun `W3 ausencia exitosa marca no disponible pero una falla inicial conserva consulta y continuar`() = runTest(scheduler) {
        val vm = viewModel()
        val cached = count("a")
        vm.selectCountDetail(cached)
        counts.value = emptyList()

        coEvery { repository.fetchStockCounts() } returns Result.failure(IllegalStateException("sin red"))
        vm.refreshSelectedCountDetail()
        assertEquals(EstadoDelDetalleDeConteo.SIN_VERIFICAR, vm.estadoDelDetalle.value)
        assertEquals(cached, vm.selectedDetail.value)
        assertTrue(vm.puedeContinuarDetalle.value)

        coEvery { repository.fetchStockCounts() } returns Result.success(Unit)
        vm.refreshSelectedCountDetail()
        assertEquals(EstadoDelDetalleDeConteo.NO_DISPONIBLE, vm.estadoDelDetalle.value)
        assertEquals(cached, vm.selectedDetail.value)
        assertFalse(vm.puedeContinuarDetalle.value)
        coVerify(exactly = 2) { repository.fetchStockCounts() }
    }

    @Test
    fun `W3 una respuesta vieja no pisa una seleccion A B A mas nueva`() = runTest(scheduler) {
        val vm = viewModel()
        val entro = CompletableDeferred<Unit>()
        val soltar = CompletableDeferred<Unit>()
        var llamada = 0
        coEvery { repository.fetchStockCounts() } coAnswers {
            llamada += 1
            if (llamada == 1) {
                entro.complete(Unit)
                soltar.await()
                counts.value = listOf(count("a", "COMPLETED"))
            } else {
                counts.value = listOf(count("a", "IN_PROGRESS"))
            }
            Result.success(Unit)
        }

        vm.selectCountDetail(count("a"))
        val anterior = launch { vm.refreshSelectedCountDetail() }
        entro.await()
        vm.selectCountDetail(count("b"))
        vm.selectCountDetail(count("a"))
        vm.refreshSelectedCountDetail()
        assertTrue(vm.puedeContinuarDetalle.value)

        soltar.complete(Unit)
        anterior.join()

        assertEquals("a", vm.selectedDetail.value?.id)
        assertEquals("IN_PROGRESS", vm.selectedDetail.value?.status)
        assertTrue(vm.puedeContinuarDetalle.value)
    }

    @Test
    fun `W3 cambiar de sucursal A B A invalida el vuelo anterior aunque vuelva al mismo venue`() = runTest(scheduler) {
        val vm = viewModel()
        val entro = CompletableDeferred<Unit>()
        val soltar = CompletableDeferred<Unit>()
        var llamada = 0
        coEvery { repository.fetchStockCounts() } coAnswers {
            llamada += 1
            if (llamada == 1) {
                entro.complete(Unit)
                soltar.await()
                counts.value = listOf(count("a", "COMPLETED"))
            } else {
                counts.value = listOf(count("a", "IN_PROGRESS"))
            }
            Result.success(Unit)
        }

        vm.selectCountDetail(count("a"))
        val anterior = launch { vm.refreshSelectedCountDetail() }
        entro.await()
        venue = "venue-b"
        vm.refreshSelectedCountDetail()
        assertFalse(vm.puedeContinuarDetalle.value)
        venue = "venue-a"
        vm.refreshSelectedCountDetail()
        assertTrue(vm.puedeContinuarDetalle.value)

        soltar.complete(Unit)
        anterior.join()

        assertEquals("IN_PROGRESS", vm.selectedDetail.value?.status)
        assertTrue(vm.puedeContinuarDetalle.value)
        coVerify(exactly = 2) { repository.fetchStockCounts() }
    }

    @Test
    fun `W3 dos triggers iguales comparten un solo refresh en vuelo`() = runTest(scheduler) {
        val vm = viewModel()
        val entro = CompletableDeferred<Unit>()
        val soltar = CompletableDeferred<Unit>()
        coEvery { repository.fetchStockCounts() } coAnswers {
            entro.complete(Unit)
            soltar.await()
            counts.value = listOf(count("a"))
            Result.success(Unit)
        }
        vm.selectCountDetail(count("a"))

        val primero = launch { vm.refreshSelectedCountDetail() }
        entro.await()
        val segundo = launch { vm.refreshSelectedCountDetail() }
        segundo.join()
        coVerify(exactly = 1) { repository.fetchStockCounts() }

        soltar.complete(Unit)
        primero.join()
    }

    @Test
    fun `W3 un fallo de consulta no toca el borrador local`() = runTest(scheduler) {
        val store = RecordingStore()
        val local = count("a", countedAt = "2026-09-08T10:00:00Z")
        store.draft = BorradorDeConteo(
            venueId = "venue-a",
            countId = local.id,
            type = local.type,
            lineas = local.items,
            actualizadoEn = 1L,
        )
        val vm = viewModel(store)
        vm.selectCountDetail(local)
        val before = store.draft
        val savesBefore = store.saves
        coEvery { repository.fetchStockCounts() } returns Result.failure(IllegalStateException("sin red"))

        vm.refreshSelectedCountDetail()

        assertEquals(before, store.draft)
        assertEquals(savesBefore, store.saves)
        assertEquals(0, store.discards)
    }

    @Test
    fun `W5 pedir volver y confirmar descarte son seguros y el doble toque ejecuta una vez`() = runTest(scheduler) {
        val store = RecordingStore()
        val vm = viewModel(store)
        vm.resumeCount(count("a", countedAt = "2026-09-08T10:00:00Z"))
        val lineas = vm.countItems.value

        vm.pedirSalida()
        vm.pedirDescarte()
        assertNotNull(vm.confirmacionDeDescarte.value)
        assertEquals(0, store.discards)
        assertEquals(lineas, vm.countItems.value)

        vm.cancelarDescarte()
        assertNull(vm.confirmacionDeDescarte.value)
        assertTrue(vm.salidaPendiente.value)
        assertEquals(0, store.discards)
        assertEquals(lineas, vm.countItems.value)

        vm.pedirDescarte()
        vm.confirmarDescarte()
        vm.confirmarDescarte()
        assertEquals(1, store.discards)
        assertNull(vm.confirmacionDeDescarte.value)
        assertTrue("la cancelación durable queda en cola sin red", "a" in store.cancellations)
    }

    @Test
    fun `W5 cero lineas contadas descarta en un paso`() = runTest(scheduler) {
        val store = RecordingStore()
        val vm = viewModel(store)
        vm.resumeCount(count("a"))

        vm.pedirSalida()
        vm.pedirDescarte()

        assertNull(vm.confirmacionDeDescarte.value)
        assertEquals(1, store.discards)
    }

    @Test
    fun `W5 cambiar de sucursal invalida la segunda confirmacion sin mutar`() = runTest(scheduler) {
        val store = RecordingStore()
        val vm = viewModel(store)
        vm.resumeCount(count("a", countedAt = "2026-09-08T10:00:00Z"))
        vm.pedirSalida()
        vm.pedirDescarte()

        venue = "venue-b"
        vm.confirmarDescarte()

        assertNotNull(vm.confirmacionDeDescarte.value)
        assertEquals(0, store.discards)
        assertNotNull(store.draft)
    }

    @Test
    fun `W5 isSaving bloquea confirmar descarte`() = runTest(scheduler) {
        val store = RecordingStore()
        val vm = viewModel(store)
        val entro = CompletableDeferred<Unit>()
        val soltar = CompletableDeferred<Unit>()
        coEvery { repository.enviarFinal(any(), any(), any()) } coAnswers {
            entro.complete(Unit)
            soltar.await()
            RespuestaHttp(0, "sin red")
        }
        vm.resumeCount(count("a", countedAt = "2026-09-08T10:00:00Z"))
        vm.pedirSalida()
        vm.pedirDescarte()
        vm.finishCounting()
        vm.confirmCount()
        entro.await()
        assertTrue(vm.isSaving.value)

        vm.confirmarDescarte()

        assertEquals(0, store.discards)
        assertNotNull(vm.confirmacionDeDescarte.value)
        soltar.complete(Unit)
        advanceUntilIdle()
    }

    @Test
    fun `W4 guardar y salir con conflicto conserva borrador y no reintenta avance`() = runTest(scheduler) {
        val store = RecordingStore()
        val vm = viewModel(store)
        coEvery { repository.enviarAvance(any(), any()) } returns RespuestaHttp(404, "cerrado")
        vm.resumeCount(count("a"))
        vm.selectCountItem(0)
        vm.updateCountedText("3")
        vm.moveToNextItem()
        assertNotNull(vm.conflictoDelServidor.value)
        coVerify(exactly = 1) { repository.enviarAvance(any(), any()) }

        vm.pedirSalida()
        vm.guardarYSalir()

        assertNotNull(store.draft)
        assertEquals(3.0, store.draft!!.lineas.single().counted, 0.0)
        coVerify(exactly = 1) { repository.enviarAvance(any(), any()) }
    }

    @Test
    fun `W5 textos y umbral cuentan una linea explicitamente contada en cero`() {
        val ceroExplicito = count("a", countedAt = "2026-09-08T10:00:00Z").items
        assertEquals(1, ConteoEnCurso.contadas(ceroExplicito))
        assertTrue(ConteoEnCurso.necesitaConfirmarDescarte(ceroExplicito))
        assertFalse(ConteoEnCurso.necesitaConfirmarDescarte(emptyList()))
        assertEquals("¿Descartar 1 línea contada?", ConteoEnCurso.tituloDescartar(1))
        assertEquals("¿Descartar 2 líneas contadas?", ConteoEnCurso.tituloDescartar(2))
        assertEquals("Esto no se puede deshacer.", ConteoEnCurso.DESCRIPCION_DESCARTAR)
        assertEquals("Volver", ConteoEnCurso.VOLVER)
    }

    @Test
    fun `W4 y W6 usan el mismo copy canonico para conflicto y busqueda`() {
        assertEquals("Salir y conservar para consulta", ConteoEnCurso.SALIR_Y_CONSERVAR)
        assertEquals("Seguir viendo", ConteoEnCurso.SEGUIR_VIENDO)
        assertEquals(
            "El conteo ya se cerró. Lo que contaste aquí se conservará en este aparato para consulta.",
            ConteoEnCurso.descripcionSalir(contadas = 3, total = 5, hayConflicto = true),
        )
        assertEquals("Sin resultados para \"Coctel\"", ConteoEnCurso.sinResultados("Coctel"))
    }

    @Test
    fun `P1 conflicto real y revision desconocida usan explicaciones distintas`() {
        val real = ConflictoRevision(
            code = ConteoEnCurso.CODIGO_CONFLICTO_REVISION,
            message = "detalle técnico",
            venueId = "venue-a",
            countId = "count-a",
        )
        val desconocida = real.copy(code = ConteoEnCurso.CODIGO_REVISION_DESCONOCIDA)

        assertEquals(
            "El conteo cambió en el servidor. Tu avance se conserva en este aparato.",
            ConteoEnCurso.descripcionConflictoRevision(real),
        )
        assertEquals(
            "No pudimos comprobar si este conteo cambió. Tu avance se conserva en este aparato.",
            ConteoEnCurso.descripcionConflictoRevision(desconocida),
        )
    }

    @Test
    fun `P1 una revision desconocida conserva modo consulta sin fingir conteo cerrado`() = runTest(scheduler) {
        val store = RecordingStore()
        store.draft = BorradorDeConteo(
            venueId = "venue-a",
            countId = "a",
            type = StockCountType.FULL,
            lineas = count("a", countedAt = "2026-09-08T10:00:00Z").items.map { it.copy(counted = 3.0) },
            revision = null,
            conflictoRevision = ConflictoRevision(
                code = ConteoEnCurso.CODIGO_REVISION_DESCONOCIDA,
                message = ConteoEnCurso.REVISION_DESCONOCIDA,
                venueId = "venue-a",
                countId = "a",
            ),
            actualizadoEn = 1L,
        )
        val vm = viewModel(store)
        vm.refrescarBorradorLocal()
        vm.continuarBorrador()

        assertTrue(vm.soloConsulta.value)
        assertNull("el flag de cerrado conserva su semántica anterior", vm.conflictoDelServidor.value)
        assertEquals(ConteoEnCurso.REVISION_DESCONOCIDA, vm.bandaDeAviso.value)
        vm.selectCountItem(0)
        vm.updateCountedText("9")
        vm.moveToNextItem()
        assertEquals(3.0, vm.countItems.value.single().counted, 0.0)
    }
}
