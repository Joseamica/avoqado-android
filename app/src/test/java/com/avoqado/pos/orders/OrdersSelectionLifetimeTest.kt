package com.avoqado.pos.orders

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.refresh.RefreshGate
import com.avoqado.pos.core.domain.refresh.RefreshGateFactory
import com.avoqado.pos.orders.data.OrdersRepository
import com.avoqado.pos.orders.data.model.OrderDetail
import com.avoqado.pos.orders.presentation.OrdersViewModel
import com.avoqado.pos.orders.presentation.HostDelDetalle
import com.avoqado.pos.orders.presentation.EstadoDelDetalle
import com.avoqado.pos.orders.presentation.estadoDelDetalle
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import kotlin.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
class OrdersSelectionLifetimeTest {
    @get:Rule val main = MainDispatcherRule()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val repository = spyk(OrdersRepository(storage, OkHttpClient()))
    private val a = CompletableDeferred<OrderDetail?>()
    private val b = CompletableDeferred<OrderDetail?>()
    private lateinit var vm: OrdersViewModel

    @Before fun setup() {
        coEvery { repository.fetchOrderDetail("A") } coAnswers { a.await() }
        coEvery { repository.fetchOrderDetail("B") } coAnswers { b.await() }
        val factory = mockk<RefreshGateFactory>()
        every { factory.create(any(), any()) } returns RefreshGate(clock = { Duration.ZERO })
        vm = OrdersViewModel(repository, factory, mockk(relaxed = true), storage, mockk(relaxed = true))
    }
    @After fun close() { vm.viewModelScope.cancel() }

    @Test fun seleccionABConRespuestaBAConservaB() = runTest {
        vm.selectOrder("A"); vm.selectOrder("B"); runCurrent()
        b.complete(OrderDetail("B")); runCurrent()
        a.complete(OrderDetail("A")); runCurrent()
        assertEquals("B", vm.selectedOrderId.value)
        assertEquals("B", vm.selectedOrder.value?.id)
        assertNull(vm.errorMessage.value)
        assertFalse(vm.isLoadingDetail.value)
    }

    @Test fun nullTardioDeANoApagaCargaNiPintaErrorMientrasBEspera() = runTest {
        vm.selectOrder("A"); vm.selectOrder("B"); runCurrent()
        a.complete(null); runCurrent()
        assertTrue(vm.isLoadingDetail.value)
        assertNull(vm.errorMessage.value)
        assertNull(vm.selectedOrder.value)
        b.complete(OrderDetail("B")); runCurrent()
        assertEquals("B", vm.selectedOrder.value?.id)
    }

    @Test fun excepcionTardiaDeANoApagaCargaNiPintaErrorMientrasBEspera() = runTest {
        vm.selectOrder("A"); vm.selectOrder("B"); runCurrent()
        a.completeExceptionally(IllegalStateException("A tardia")); runCurrent()
        assertTrue(vm.isLoadingDetail.value)
        assertNull(vm.errorMessage.value)
        assertNull(vm.selectedOrder.value)
        b.complete(OrderDetail("B")); runCurrent()
        assertEquals("B", vm.selectedOrder.value?.id)
    }

    @Test fun nullTardioDeANoBorraBQueYaRespondio() = runTest {
        vm.selectOrder("A"); vm.selectOrder("B"); runCurrent()
        b.complete(OrderDetail("B")); runCurrent()
        a.complete(null); runCurrent()
        assertEquals("B", vm.selectedOrder.value?.id)
        assertNull(vm.errorMessage.value)
        assertFalse(vm.isLoadingDetail.value)
    }

    @Test fun excepcionTardiaDeANoPintaErrorSobreBQueYaRespondio() = runTest {
        vm.selectOrder("A"); vm.selectOrder("B"); runCurrent()
        b.complete(OrderDetail("B")); runCurrent()
        a.completeExceptionally(IllegalStateException("A tardia")); runCurrent()
        assertEquals("B", vm.selectedOrder.value?.id)
        assertNull(vm.errorMessage.value)
        assertFalse(vm.isLoadingDetail.value)
    }

    @Test fun limpiarSeleccionInvalidaLaRespuestaPendiente() = runTest {
        vm.selectOrder("A"); runCurrent()
        vm.clearSelection()
        a.complete(OrderDetail("A")); runCurrent()
        assertNull(vm.selectedOrderId.value)
        assertNull(vm.selectedOrder.value)
        assertNull(vm.errorMessage.value)
        assertFalse(vm.isLoadingDetail.value)
    }

    @Test fun dosSolicitudesDelMismoIdConservanLaMasReciente() = runTest {
        val segunda = CompletableDeferred<OrderDetail?>()
        var llamadas = 0
        coEvery { repository.fetchOrderDetail("A") } coAnswers {
            if (++llamadas == 1) a.await() else segunda.await()
        }
        vm.selectOrder("A"); runCurrent()
        vm.selectOrder("A"); runCurrent()
        segunda.complete(OrderDetail("A", orderNumber = "Nueva")); runCurrent()
        a.complete(OrderDetail("A", orderNumber = "Vieja")); runCurrent()
        assertEquals("Nueva", vm.selectedOrder.value?.orderNumber)
        assertFalse(vm.isLoadingDetail.value)
    }

    @Test fun elFalloDeLaSolicitudVigenteSigueVisible() = runTest {
        coEvery { repository.fetchOrderDetail("A") } returns null
        vm.selectOrder("A"); runCurrent()
        assertEquals("No se encontró la orden", vm.errorMessage.value)
        assertFalse(vm.isLoadingDetail.value)
        coEvery { repository.fetchOrderDetail("A") } throws IllegalStateException("vigente")
        vm.selectOrder("A"); runCurrent()
        assertEquals("Error al cargar detalle de orden", vm.errorMessage.value)
        assertFalse(vm.isLoadingDetail.value)
    }

    private suspend fun falloEnHost(host: HostDelDetalle, excepcion: Boolean, detalleViejo: Boolean) {
        if (detalleViejo) {
            vm.selectOrder("A"); a.complete(OrderDetail("A"))
        }
        vm.selectOrder("B")
        if (excepcion) b.completeExceptionally(IllegalStateException("B vigente")) else b.complete(null)
        val mensaje = if (excepcion) "Error al cargar detalle de orden" else "No se encontró la orden"
        assertEquals("B", vm.selectedOrderId.value)
        assertFalse(vm.isLoadingDetail.value)
        assertEquals(EstadoDelDetalle.Fallo(mensaje), estadoDelDetalle(host, vm.selectedOrderId.value,
            vm.isLoadingDetail.value, vm.selectedOrder.value, vm.errorMessage.value))
    }
    @Test fun nullVigenteEnTelefonoMuestraError() = runTest { falloEnHost(HostDelDetalle.TELEFONO, false, false) }
    @Test fun nullVigenteEnTabletaMuestraError() = runTest { falloEnHost(HostDelDetalle.TABLETA, false, false) }
    @Test fun excepcionVigenteEnTelefonoMuestraError() = runTest { falloEnHost(HostDelDetalle.TELEFONO, true, false) }
    @Test fun excepcionVigenteEnTabletaMuestraError() = runTest { falloEnHost(HostDelDetalle.TABLETA, true, false) }
    @Test fun detalleDeAYBFallidoEnTelefonoMuestraError() = runTest { falloEnHost(HostDelDetalle.TELEFONO, true, true) }
    @Test fun detalleDeAYBFallidoEnTabletaMuestraError() = runTest { falloEnHost(HostDelDetalle.TABLETA, true, true) }

    @Test fun reintentarSeleccionFallidaCargaBMismaCuentaYSalirLimpia() = runTest {
        vm.selectOrder("B"); b.complete(null); runCurrent()
        assertFalse(vm.isLoadingDetail.value)
        val reintento = CompletableDeferred<OrderDetail?>()
        coEvery { repository.fetchOrderDetail("B") } coAnswers { reintento.await() }
        vm.selectOrder("B"); runCurrent()
        for (host in HostDelDetalle.entries) assertEquals(EstadoDelDetalle.Cargando,
            estadoDelDetalle(host,vm.selectedOrderId.value,vm.isLoadingDetail.value,vm.selectedOrder.value,vm.errorMessage.value))
        reintento.complete(OrderDetail("B"));runCurrent()
        for (host in HostDelDetalle.entries) assertEquals(EstadoDelDetalle.Detalle(OrderDetail("B")),
            estadoDelDetalle(host,vm.selectedOrderId.value,vm.isLoadingDetail.value,vm.selectedOrder.value,vm.errorMessage.value))
        vm.clearSelection()
        assertEquals(EstadoDelDetalle.Lista,estadoDelDetalle(HostDelDetalle.TELEFONO,vm.selectedOrderId.value,
            vm.isLoadingDetail.value,vm.selectedOrder.value,vm.errorMessage.value))
    }
}

@org.junit.runner.RunWith(org.junit.runners.Parameterized::class)
class OrdersDetailStateTableTest(private val fila: Int, private val host: String,
    private val seleccion: String?, private val cargando: Boolean, private val detalleId: String?,
    private val error: String?, private val esperado: String, private val mensaje: String?) {
    @Test fun estadoDeLaFilaCompleta() {
        val detalle=detalleId?.let { OrderDetail(it) }
        val estado=estadoDelDetalle(HostDelDetalle.valueOf(host),seleccion,cargando,detalle,error)
        val esperadoEstado = when(esperado) {
            "LISTA" -> EstadoDelDetalle.Lista
            "VACIO" -> EstadoDelDetalle.Vacio
            "CARGANDO" -> EstadoDelDetalle.Cargando
            "DETALLE" -> EstadoDelDetalle.Detalle(detalle!!)
            else -> EstadoDelDetalle.Fallo(mensaje!!)
        }
        assertEquals("Fila $fila $host selección=$seleccion carga=$cargando detalle=$detalleId error=$error",esperadoEstado,estado)
    }
    companion object {
        @JvmStatic @org.junit.runners.Parameterized.Parameters(name = "fila{0}-{1}-sel{2}-carga{3}-detalle{4}-error{5}")
        fun filas(): List<Array<Any?>> = listOf(
            arrayOf<Any?>(1, "TELEFONO", null, false, null, null, "LISTA", null),
            arrayOf<Any?>(2, "TELEFONO", null, false, null, "Fallo literal", "LISTA", null),
            arrayOf<Any?>(3, "TELEFONO", null, false, "X", null, "LISTA", null),
            arrayOf<Any?>(4, "TELEFONO", null, false, "X", "Fallo literal", "LISTA", null),
            arrayOf<Any?>(5, "TELEFONO", null, false, "A", null, "LISTA", null),
            arrayOf<Any?>(6, "TELEFONO", null, false, "A", "Fallo literal", "LISTA", null),
            arrayOf<Any?>(7, "TELEFONO", null, true, null, null, "LISTA", null),
            arrayOf<Any?>(8, "TELEFONO", null, true, null, "Fallo literal", "LISTA", null),
            arrayOf<Any?>(9, "TELEFONO", null, true, "X", null, "LISTA", null),
            arrayOf<Any?>(10, "TELEFONO", null, true, "X", "Fallo literal", "LISTA", null),
            arrayOf<Any?>(11, "TELEFONO", null, true, "A", null, "LISTA", null),
            arrayOf<Any?>(12, "TELEFONO", null, true, "A", "Fallo literal", "LISTA", null),
            arrayOf<Any?>(13, "TELEFONO", "X", false, null, null, "ERROR", "No se pudo cargar el pedido"),
            arrayOf<Any?>(14, "TELEFONO", "X", false, null, "Fallo literal", "ERROR", "Fallo literal"),
            arrayOf<Any?>(15, "TELEFONO", "X", false, "X", null, "DETALLE", null),
            arrayOf<Any?>(16, "TELEFONO", "X", false, "X", "Fallo literal", "DETALLE", null),
            arrayOf<Any?>(17, "TELEFONO", "X", false, "A", null, "ERROR", "No se pudo cargar el pedido"),
            arrayOf<Any?>(18, "TELEFONO", "X", false, "A", "Fallo literal", "ERROR", "Fallo literal"),
            arrayOf<Any?>(19, "TELEFONO", "X", true, null, null, "CARGANDO", null),
            arrayOf<Any?>(20, "TELEFONO", "X", true, null, "Fallo literal", "CARGANDO", null),
            arrayOf<Any?>(21, "TELEFONO", "X", true, "X", null, "CARGANDO", null),
            arrayOf<Any?>(22, "TELEFONO", "X", true, "X", "Fallo literal", "CARGANDO", null),
            arrayOf<Any?>(23, "TELEFONO", "X", true, "A", null, "CARGANDO", null),
            arrayOf<Any?>(24, "TELEFONO", "X", true, "A", "Fallo literal", "CARGANDO", null),
            arrayOf<Any?>(25, "TABLETA", null, false, null, null, "VACIO", null),
            arrayOf<Any?>(26, "TABLETA", null, false, null, "Fallo literal", "VACIO", null),
            arrayOf<Any?>(27, "TABLETA", null, false, "X", null, "VACIO", null),
            arrayOf<Any?>(28, "TABLETA", null, false, "X", "Fallo literal", "VACIO", null),
            arrayOf<Any?>(29, "TABLETA", null, false, "A", null, "VACIO", null),
            arrayOf<Any?>(30, "TABLETA", null, false, "A", "Fallo literal", "VACIO", null),
            arrayOf<Any?>(31, "TABLETA", null, true, null, null, "VACIO", null),
            arrayOf<Any?>(32, "TABLETA", null, true, null, "Fallo literal", "VACIO", null),
            arrayOf<Any?>(33, "TABLETA", null, true, "X", null, "VACIO", null),
            arrayOf<Any?>(34, "TABLETA", null, true, "X", "Fallo literal", "VACIO", null),
            arrayOf<Any?>(35, "TABLETA", null, true, "A", null, "VACIO", null),
            arrayOf<Any?>(36, "TABLETA", null, true, "A", "Fallo literal", "VACIO", null),
            arrayOf<Any?>(37, "TABLETA", "X", false, null, null, "ERROR", "No se pudo cargar el pedido"),
            arrayOf<Any?>(38, "TABLETA", "X", false, null, "Fallo literal", "ERROR", "Fallo literal"),
            arrayOf<Any?>(39, "TABLETA", "X", false, "X", null, "DETALLE", null),
            arrayOf<Any?>(40, "TABLETA", "X", false, "X", "Fallo literal", "DETALLE", null),
            arrayOf<Any?>(41, "TABLETA", "X", false, "A", null, "ERROR", "No se pudo cargar el pedido"),
            arrayOf<Any?>(42, "TABLETA", "X", false, "A", "Fallo literal", "ERROR", "Fallo literal"),
            arrayOf<Any?>(43, "TABLETA", "X", true, null, null, "CARGANDO", null),
            arrayOf<Any?>(44, "TABLETA", "X", true, null, "Fallo literal", "CARGANDO", null),
            arrayOf<Any?>(45, "TABLETA", "X", true, "X", null, "CARGANDO", null),
            arrayOf<Any?>(46, "TABLETA", "X", true, "X", "Fallo literal", "CARGANDO", null),
            arrayOf<Any?>(47, "TABLETA", "X", true, "A", null, "CARGANDO", null),
            arrayOf<Any?>(48, "TABLETA", "X", true, "A", "Fallo literal", "CARGANDO", null),
        )
    }
}
