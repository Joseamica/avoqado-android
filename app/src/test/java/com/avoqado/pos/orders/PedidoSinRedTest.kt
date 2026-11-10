package com.avoqado.pos.orders

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.orders.data.OrdersRepository
import io.mockk.every
import io.mockk.mockk
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.UnknownHostException

/**
 * D22 (9-nov-2026, SAMSUNG del Alien): sin red, abrir otra venta en «Pedidos» decía «No se encontró la orden». La venta
 * existe; lo que falta es red. Sólo un 404 dice que no existe.
 */
class PedidoSinRedTest {
    private fun repo(respuesta: (Interceptor.Chain) -> Response): OrdersRepository {
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.venueId } returns "v1"
        return OrdersRepository(storage, OkHttpClient.Builder().addInterceptor(Interceptor { respuesta(it) }).build())
    }

    private fun http(chain: Interceptor.Chain, codigo: Int, cuerpo: String) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(codigo).message("x")
        .body(cuerpo.toResponseBody()).build()

    @Test fun `sin red no dice que el pedido no existe`() = runTest {
        val r = repo { throw UnknownHostException("sin red") }
        r.loadOrderDetail("B")
        assertEquals("Sin conexión: para ver este pedido necesitas internet. Se abre solo al volver la conexión.", r.errorMessage.value)
        assertNull(r.selectedOrder.value)
    }

    @Test fun `un 404 sigue diciendo que no se encontro`() = runTest {
        val r = repo { http(it, 404, """{"success":false,"message":"Order not found"}""") }
        r.loadOrderDetail("B")
        assertEquals("No se encontró la orden", r.errorMessage.value)
    }

    @Test fun `un 500 no dice que el pedido no existe`() = runTest {
        val r = repo { http(it, 500, """{"message":"boom"}""") }
        r.loadOrderDetail("B")
        assertEquals("Error al cargar detalle de orden", r.errorMessage.value)
    }

    @Test fun `un 200 sin pedido no dice que no existe`() = runTest {
        val r = repo { http(it, 200, """{"success":true}""") }
        r.loadOrderDetail("B")
        assertEquals("Error al cargar detalle de orden", r.errorMessage.value)
    }

    /** Codex r1 #1: la conexión se corta al leer el cuerpo. */
    private val cuerpoQueSeCorta = object : okhttp3.ResponseBody() {
        override fun contentType(): okhttp3.MediaType? = null
        override fun contentLength() = -1L
        override fun source(): okio.BufferedSource = object : okio.Source {
            override fun read(sink: okio.Buffer, byteCount: Long): Long = throw java.io.IOException("se cortó al leer")
            override fun timeout() = okio.Timeout.NONE
            override fun close() {}
        }.buffer()
    }

    @Test fun `un 500 que se corta al leer no dice Sin conexion`() = runTest {
        val r = repo { http(it, 500, "").newBuilder().body(cuerpoQueSeCorta).build() }
        r.loadOrderDetail("B")
        assertEquals("Error al cargar detalle de orden", r.errorMessage.value)
    }

    @Test fun `un 200 que se corta al leer es sin red`() = runTest {
        val r = repo { http(it, 200, "").newBuilder().body(cuerpoQueSeCorta).build() }
        r.loadOrderDetail("B")
        assertEquals("B", r.detalleSinRed.value)
        assertEquals("Sin conexión: para ver este pedido necesitas internet. Se abre solo al volver la conexión.", r.errorMessage.value)
    }

    /** Codex r2 #3: cerrar «Pedidos» corta la consulta, y con ella la llamada (su 403 no sale encima de otra pantalla). */
    @Test fun `cancelar la consulta cancela la llamada`() = runTest {
        val entro = java.util.concurrent.CountDownLatch(1)
        val sigue = java.util.concurrent.CountDownLatch(1)
        val vista = java.util.concurrent.CountDownLatch(1)
        val cancelada = java.util.concurrent.atomic.AtomicBoolean(false)
        val r = repo { chain ->
            entro.countDown()
            sigue.await(5, java.util.concurrent.TimeUnit.SECONDS)
            cancelada.set(chain.call().isCanceled())
            vista.countDown()
            http(chain, 200, """{"success":true,"order":{"id":"B"}}""")
        }
        val consulta = launch { r.loadOrderDetail("B") }
        runCurrent()
        assertEquals(true, entro.await(5, java.util.concurrent.TimeUnit.SECONDS))
        consulta.cancel()
        sigue.countDown()
        assertEquals(true, vista.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals("la llamada se canceló", true, cancelada.get())
    }

    @Test fun `con red abre el pedido`() = runTest {
        val r = repo { http(it, 200, """{"success":true,"order":{"id":"B"}}""") }
        r.loadOrderDetail("B")
        assertEquals("B", r.selectedOrder.value?.id)
        assertNull(r.errorMessage.value)
    }
}

/** D22: mientras «Pedidos» se ve, el pedido que no abrió por falta de red se vuelve a pedir solo cuando el aparato tiene red. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PedidoSeAbreAlVolverLaRedTest {
    @get:org.junit.Rule val main = com.avoqado.pos.MainDispatcherRule()
    private val storage = mockk<SecureStorage>(relaxed = true)
    private val repository = io.mockk.spyk(OrdersRepository(storage, OkHttpClient()))
    private val conectado = kotlinx.coroutines.flow.MutableStateFlow(true)
    private val red = mockk<com.avoqado.pos.core.util.ConnectivityMonitor> { every { isConnected } returns conectado }
    private lateinit var vm: com.avoqado.pos.orders.presentation.OrdersViewModel
    private val sinRed = "Sin conexión: para ver este pedido necesitas internet. Se abre solo al volver la conexión."
    private var consultas = 0

    @org.junit.Before fun armar() {
        val factory = mockk<com.avoqado.pos.core.domain.refresh.RefreshGateFactory>()
        every { factory.create(any(), any()) } returns com.avoqado.pos.core.domain.refresh.RefreshGate(clock = { kotlin.time.Duration.ZERO })
        vm = com.avoqado.pos.orders.presentation.OrdersViewModel(repository, factory, mockk(relaxed = true), storage, mockk(relaxed = true), red)
    }

    @org.junit.After fun cerrar() { vm.viewModelScope.cancel() }

    private fun responde(id: String, que: () -> com.avoqado.pos.orders.data.model.OrderDetail?) {
        io.mockk.coEvery { repository.fetchOrderDetail(id) } answers { consultas++; que() }
    }

    @Test fun `sin red no abre y al volver la red se abre solo`() = runTest {
        backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        conectado.value = false
        responde("B") { throw UnknownHostException("sin red") }
        vm.selectOrder("B"); runCurrent()
        assertEquals("sin red", sinRed, vm.errorMessage.value)
        advanceTimeBy(120_000); runCurrent()
        assertEquals("sin red del aparato no insiste", 1, consultas)

        responde("B") { com.avoqado.pos.orders.data.model.OrderDetail("B") }
        conectado.value = true; runCurrent()
        assertEquals("al volver la red se abre solo", "B", vm.selectedOrder.value?.id)
        assertNull(vm.errorMessage.value)
    }

    @Test fun `con el pedido a la vista volver la red no lo vuelve a pedir`() = runTest {
        backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        responde("A") { com.avoqado.pos.orders.data.model.OrderDetail("A") }
        vm.selectOrder("A"); runCurrent()
        conectado.value = false; runCurrent(); conectado.value = true; runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, consultas)
    }

    /** Codex r1 #1 y #2: el fallo de red llega con el aparato «con red» (se cortó al leer, o el «volvió» llegó a media carga). */
    @Test fun `con red del aparato reintenta en escalera sin bucle y abre cuando contesta`() = runTest {
        backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        responde("B") { throw java.io.IOException("se cortó al leer") }
        vm.selectOrder("B"); runCurrent()
        assertEquals("el toque y el reintento inmediato", 2, consultas)
        advanceTimeBy(4_999); runCurrent()
        assertEquals("nada antes de 5 s", 2, consultas)
        advanceTimeBy(1); runCurrent()
        assertEquals(3, consultas)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(4, consultas)
        assertEquals(sinRed, vm.errorMessage.value)

        responde("B") { com.avoqado.pos.orders.data.model.OrderDetail("B") }
        advanceTimeBy(20_000); runCurrent()
        assertEquals("B", vm.selectedOrder.value?.id)
        val alAbrir = consultas
        advanceTimeBy(300_000); runCurrent()
        assertEquals("abierto ya no se pide", alAbrir, consultas)
    }

    @Test fun `un 404 o un servidor que contesto mal no se repiten solos`() = runTest {
        backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        responde("X") { null }
        vm.selectOrder("X"); runCurrent()
        assertEquals("No se encontró la orden", vm.errorMessage.value)
        responde("Y") { throw com.avoqado.pos.core.data.network.LecturaFallida(com.avoqado.pos.core.data.network.FalloDeLectura.SERVIDOR) }
        vm.selectOrder("Y"); runCurrent()
        assertEquals("Error al cargar detalle de orden", vm.errorMessage.value)
        advanceTimeBy(300_000); runCurrent()
        assertEquals(2, consultas)
    }

    @Test fun `con Pedidos cerrado no pide nada`() = runTest {
        val viendo = backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        conectado.value = false
        responde("B") { throw UnknownHostException("sin red") }
        vm.selectOrder("B"); runCurrent()
        viendo.cancel(); runCurrent()
        conectado.value = true; runCurrent()
        advanceTimeBy(120_000); runCurrent()
        assertEquals(1, consultas)
        assertEquals(sinRed, vm.errorMessage.value)
    }

    /** Codex r2 #3: la consulta automática que ya salió se corta al cerrar «Pedidos». */
    @Test fun `cerrar Pedidos corta la consulta automatica en vuelo`() = runTest {
        val viendo = backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        conectado.value = false
        responde("B") { throw UnknownHostException("sin red") }
        vm.selectOrder("B"); runCurrent()
        var cortada = false
        io.mockk.coEvery { repository.fetchOrderDetail("B") } coAnswers {
            try { kotlinx.coroutines.awaitCancellation() } finally { cortada = true }
        }
        conectado.value = true; runCurrent()
        assertEquals(false, cortada)
        assertEquals("mientras reintenta se queda el aviso, sin círculo", sinRed, vm.errorMessage.value)
        assertEquals(false, vm.isLoadingDetail.value)
        viendo.cancel(); runCurrent()
        assertEquals("se cortó con la pantalla", true, cortada)
        assertEquals("Codex r3 #3: cortada, se queda el aviso", sinRed, vm.errorMessage.value)
        assertEquals(false, vm.isLoadingDetail.value)
    }

    /** Codex r3 #2: la red sube y baja a media reapertura; nunca hay dos consultas a la vez y el aviso no se pierde. */
    @Test fun `la red que va y viene no encima consultas`() = runTest {
        backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        conectado.value = false
        responde("B") { throw UnknownHostException("sin red") }
        vm.selectOrder("B"); runCurrent()
        var enVuelo = 0
        var maximo = 0
        var cortadas = 0
        io.mockk.coEvery { repository.fetchOrderDetail("B") } coAnswers {
            enVuelo++; maximo = maxOf(maximo, enVuelo)
            try { kotlinx.coroutines.awaitCancellation() } finally { enVuelo--; cortadas++ }
        }
        repeat(3) {
            conectado.value = true; runCurrent()
            conectado.value = false; runCurrent()
        }
        assertEquals("una a la vez", 1, maximo)
        assertEquals("cada bajada corta la suya", 3, cortadas)
        assertEquals(sinRed, vm.errorMessage.value)
    }

    /** Codex r4 #1: «Reintentar» (o tocar el mismo pedido) a media reapertura la corta: nunca dos consultas a la vez. */
    @Test fun `Reintentar a media reapertura la corta y manda el toque`() = runTest {
        backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        conectado.value = false
        responde("B") { throw UnknownHostException("sin red") }
        vm.selectOrder("B"); runCurrent()
        var cortada = false
        io.mockk.coEvery { repository.fetchOrderDetail("B") } coAnswers {
            try { kotlinx.coroutines.awaitCancellation() } finally { cortada = true }
        }
        conectado.value = true; runCurrent()
        val respuesta = kotlinx.coroutines.CompletableDeferred<com.avoqado.pos.orders.data.model.OrderDetail?>()
        io.mockk.coEvery { repository.fetchOrderDetail("B") } coAnswers { respuesta.await() }
        vm.selectOrder("B"); runCurrent()
        assertEquals("la automática se cortó", true, cortada)
        advanceTimeBy(60_000); runCurrent()
        respuesta.complete(com.avoqado.pos.orders.data.model.OrderDetail("B")); runCurrent()
        assertEquals("el toque abrió", "B", vm.selectedOrder.value?.id)
    }

    /** Codex r4 #3: refrescar la lista (volver a la pantalla tras 30 s) no borra el «Sin conexión…» del pedido. */
    @Test fun `refrescar la lista no borra el aviso del pedido`() = runTest {
        conectado.value = false
        responde("B") { throw UnknownHostException("sin red") }
        vm.selectOrder("B"); runCurrent()
        io.mockk.coEvery { repository.fetchOrders(any(), any(), any(), any()) } returns Result.failure(UnknownHostException("sin red"))
        vm.refreshNow(); runCurrent()
        assertEquals(sinRed, vm.errorMessage.value)
        io.mockk.coEvery { repository.fetchOrders(any(), any(), any(), any()) } returns
            Result.success(com.avoqado.pos.orders.data.model.OrderPage(orders = emptyList(), total = 0, page = 1, pageCount = 1))
        vm.refreshNow(); runCurrent()
        assertEquals(sinRed, vm.errorMessage.value)
    }

    /** Codex r5: volver a pedir el pedido que ya se ve y recibir un 5xx enseña el fallo, no la foto anterior. */
    @Test fun `volver a pedir el pedido abierto y fallar ensena el fallo`() = runTest {
        responde("A") { com.avoqado.pos.orders.data.model.OrderDetail("A") }
        vm.selectOrder("A"); runCurrent()
        responde("A") { throw com.avoqado.pos.core.data.network.LecturaFallida(com.avoqado.pos.core.data.network.FalloDeLectura.SERVIDOR) }
        vm.selectOrder("A"); runCurrent()
        assertEquals(com.avoqado.pos.orders.presentation.EstadoDelDetalle.Fallo("Error al cargar detalle de orden"),
            com.avoqado.pos.orders.presentation.estadoDelDetalle(com.avoqado.pos.orders.presentation.HostDelDetalle.TABLETA,
                vm.selectedOrderId.value, vm.isLoadingDetail.value, vm.selectedOrder.value, vm.errorMessage.value))
    }

    @Test fun `tocar otro pedido corta la reapertura del anterior`() = runTest {
        backgroundScope.launch { vm.reabrirAlVolverLaRed() }
        conectado.value = false
        responde("A") { throw UnknownHostException("sin red") }
        vm.selectOrder("A"); runCurrent()
        var cortada = false
        io.mockk.coEvery { repository.fetchOrderDetail("A") } coAnswers {
            try { kotlinx.coroutines.awaitCancellation() } finally { cortada = true }
        }
        conectado.value = true; runCurrent()
        responde("B") { com.avoqado.pos.orders.data.model.OrderDetail("B") }
        vm.selectOrder("B"); runCurrent()
        assertEquals(true, cortada)
        assertEquals("B", vm.selectedOrder.value?.id)
    }
}
