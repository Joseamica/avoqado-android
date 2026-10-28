package com.avoqado.pos.giftcards

import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*
import com.avoqado.pos.giftcards.presentation.VenderTarjetaViewModel
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class VenderTarjetaViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var fake: SolicitudesDeTarjetaTest.Preferencias
    private lateinit var almacen: SecureStorage
    private lateinit var store: SolicitudesDeTarjetaStore
    private val conectado = MutableStateFlow(true)
    private val alcanzable = MutableStateFlow(true)
    private val red = mockk<ConnectivityMonitor> {
        every { isConnected } returns conectado
        every { isServerReachable } returns alcanzable
    }
    private val ajustes = AjustesDeTarjetas(true, listOf(500.0), 100.0, 10000.0,
        topePorOperacion = 70000.0, sale = VentaPermitida(true), capacidades = setOf("ENTREGA"))
    private val form = FormularioDeVenta(listOf(SobreEnFormulario(50000, "PRINTED", "Ana", "", "", "")), "Luis")
    private fun vm(): VenderTarjetaViewModel {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer " + almacen.userId).build())
        }.build()
        return VenderTarjetaViewModel(GiftCardsRepository(client, almacen), store, almacen, red).also {
            it.alAbrir("a", ajustes); it.cambiar(form)
        }
    }
    @Before fun preparar() {
        server = MockWebServer().also { it.start() }
        System.setProperty("avoqado.test.baseUrl", server.url("/api/v1").toString())
        fake = SolicitudesDeTarjetaTest.Preferencias()
        fake.values["venueId"] = "a"; fake.values["userId"] = "c000000000000000000000000a"
        fake.values["userFirstName"] = "Ana"; fake.values["userLastName"] = "López"
        almacen = fake.almacen(); store = SolicitudesDeTarjetaStore(almacen)
        conectado.value = true; alcanzable.value = true
    }
    @After fun cerrar() { server.shutdown(); System.clearProperty("avoqado.test.baseUrl") }
    private fun exito(status: String = "PENDING") = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"success":true,"data":{"orderId":"o","orderNumber":"n","total":500,"sobres":[{"id":"g","ultimos4":"1234","monto":500,"deliveryChannel":"PRINTED","status":"$status","codigo":"secreto"}]}}""")
    private fun pendiente() = SolicitudDeTarjeta(tipo = "GIFT_CARD_SALE", llave = "k", venueId = "a",
        creadaEn = 1, orderId = "o", orderNumber = "n", cuerpo = "{ \"amount\":500.00 }")
    private fun s4(status: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(
        """{"success":true,"data":{"orderId":"o","orderNumber":"n","total":500,"paymentStatus":"PENDING","orderStatus":"CONFIRMED","sobres":[{"id":"g","ultimos4":"1234","monto":500,"saldo":500,"status":"$status","deliveryChannel":"PRINTED","usada":false,"puedeReenviar":false,"puedeCambiarDestino":false,"puedeRevelar":false}],"cancel":{"allowed":true}}}""")
    private fun alReclamar(terminarOtroActor: () -> Unit) {
        val reales = store.enVueloDeEsteProceso
        var primera = true
        val intercalado = object : MutableSet<String> by reales {
            override fun add(element: String): Boolean {
                if (primera) { primera = false; terminarOtroActor() }
                return reales.add(element)
            }
        }
        // Sólo adelanta al otro actor en la frontera del reclamo; diario y decisiones siguen siendo reales.
        SolicitudesDeTarjetaStore::class.java.getDeclaredField("enVueloDeEsteProceso").also {
            it.isAccessible = true; it.set(store, intercalado)
        }
    }
    @Test fun `P1 R8 folio anotado antes del reclamo consulta S4 sin repetir POST ni cuerpo`() = runBlocking {
        val original = pendiente().copy(orderId = null, orderNumber = null, cuerpo = "{ \"original\": \"á €\" }")
        assertTrue(store.guardar(original))
        val vm = vm(); assertTrue(vm.reanudar("k"))
        assertTrue(store.enVueloDeEsteProceso.add("k"))
        alReclamar {
            assertTrue(store.anotarOrden("k", "o", "n"))
            assertTrue(store.enVueloDeEsteProceso.remove("k"))
        }
        server.enqueue(s4("PENDING")); vm.cobrar()
        val request = server.takeRequest(1, TimeUnit.SECONDS)
        assertEquals("GET", request?.method)
        assertEquals("/api/v1/mobile/venues/a/gift-cards/orders/o", request?.path)
        assertEquals(0L, request?.bodySize)
        assertEquals(1, server.requestCount)
        assertEquals(listOf(original.copy(orderId = "o", orderNumber = "n")), store.delNegocio("a"))
        assertEquals("k", vm.llave)
        assertEquals(VenderTarjetaViewModel.Estado.Cobro("o"), vm.estado.value)
        assertTrue(store.enVueloDeEsteProceso.isEmpty())
    }
    @Test fun `P1 R8 solicitud retirada o reemplazada antes del reclamo no se recrea ni envia`() = runBlocking {
        for (reemplazar in listOf(false, true)) {
            val original = pendiente().copy(orderId = null, orderNumber = null)
            assertTrue(store.guardar(original))
            val vm = vm(); assertTrue(vm.reanudar("k"))
            alReclamar {
                assertTrue(store.quitar("k"))
                if (reemplazar) assertTrue(store.guardar(original.copy(tipo = "GIFT_CARD_REDEEM")))
            }
            server.enqueue(exito()); vm.cobrar()
            assertEquals("No debe enviar la copia vieja ni recrear la retirada", 0, server.requestCount)
            assertEquals(if (reemplazar) listOf(original.copy(tipo = "GIFT_CARD_REDEEM")) else emptyList(), store.delNegocio("a"))
            assertTrue(store.enVueloDeEsteProceso.isEmpty())
            if (reemplazar) assertTrue(store.quitar("k"))
        }
    }
    @Test fun `P1 R8 cambio de llave antes del reclamo no envia ni modifica ninguna solicitud`() = runBlocking {
        val original = pendiente().copy(orderId = null, orderNumber = null)
        val otra = original.copy(llave = "otra", cuerpo = "{\"otra\":true}")
        assertTrue(store.guardar(original)); assertTrue(store.guardar(otra))
        val vm = vm(); assertTrue(vm.reanudar("k"))
        alReclamar { assertTrue(vm.reanudar("otra")) }
        server.enqueue(exito()); vm.cobrar()
        assertEquals(0, server.requestCount)
        assertEquals(listOf(original, otra), store.delNegocio("a"))
        assertEquals("otra", vm.llave)
        assertTrue(store.enVueloDeEsteProceso.isEmpty())
    }
    @Test fun `P1 reabrir orden conocida consulta S4 PENDING sin POST y conserva bytes`() = runBlocking {
        assertTrue(store.guardar(pendiente()))
        server.enqueue(s4("PENDING"))
        val vm = vm(); vm.reanudar("k"); vm.cobrar()
        assertEquals(VenderTarjetaViewModel.Estado.Cobro("o"), vm.estado.value)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", req.method)
        assertTrue(req.path!!.endsWith("/gift-cards/orders/o"))
        assertEquals(listOf(pendiente()), store.delNegocio("a"))
    }
    @Test fun `P1 ACTIVE al reabrir conserva SALE y pasa a resultado pendiente nunca a cobro cero`() = runBlocking {
        assertTrue(store.guardar(pendiente()))
        server.enqueue(s4("ACTIVE"))
        val vm = vm(); vm.reanudar("k"); vm.cobrar()
        assertEquals(VenderTarjetaViewModel.Estado.ResultadoPendiente("o"), vm.estado.value)
        assertEquals("GET", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        assertEquals(listOf(pendiente()), store.delNegocio("a"))
    }
    @Test fun `DEACTIVATED al reabrir solo retira SALE si el disco confirma y estrena llave`() = runBlocking {
        assertTrue(store.guardar(pendiente()))
        val vm = vm(); vm.reanudar("k")
        fake.ok = false; server.enqueue(s4("DEACTIVATED")); vm.cobrar()
        assertEquals(VenderTarjetaViewModel.Estado.Error(TextosDeTarjetas.NO_SE_PUDO_GUARDAR), vm.estado.value)
        assertEquals(listOf(pendiente()), store.delNegocio("a"))
        fake.ok = true; server.enqueue(s4("DEACTIVATED")); vm.cobrar()
        assertEquals(VenderTarjetaViewModel.Estado.Editando, vm.estado.value)
        assertTrue(store.delNegocio("a").isEmpty())
        assertNotEquals("k", vm.llave)
        assertEquals(2, server.requestCount)
    }
    @Test fun `replay de venta ya desactivada no ofrece cobrar ni conserva llave cancelada`() = runBlocking {
        server.enqueue(exito("DEACTIVATED"))
        val vm = vm(); val original = vm.llave; vm.cobrar()
        assertEquals(VenderTarjetaViewModel.Estado.Editando, vm.estado.value)
        assertTrue(store.delNegocio("a").isEmpty())
        assertNotEquals(original, vm.llave)
    }
    @Test fun `P1 guarda cuerpo antes de POST y no conserva codigos de respuesta`() = runBlocking {
        var guardadaAntes = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                guardadaAntes = store.delNegocio("a").singleOrNull()?.cuerpo == request.body.readUtf8()
                return exito()
            }
        }
        val vm = vm(); vm.cobrar()
        assertTrue("El transporte debe ver la solicitud ya guardada", guardadaAntes)
        assertEquals(VenderTarjetaViewModel.Estado.Cobro("o"), vm.estado.value)
        assertEquals("o", store.delNegocio("a").single().orderId)
        assertFalse(almacen.giftCardRequestsJson!!.contains("secreto"))
    }
    @Test fun `P1 si almacenamiento falla no manda y no permite reescribir falso confirmado`() = runBlocking {
        val vm = vm(); fake.ok = false; vm.cobrar()
        assertEquals(VenderTarjetaViewModel.Estado.Error(TextosDeTarjetas.NO_SE_PUDO_GUARDAR), vm.estado.value)
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        assertTrue(store.delNegocio("a").isEmpty())
    }
    @Test fun `P1 sin respuesta reusa bytes y actor informativo con autenticacion actual`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Content-Type", "application/json").setBody("{}"))
        val vm = vm(); vm.cobrar()
        assertEquals(1, store.delNegocio("a").size)
        assertTrue(vm.bloqueado)
        val original = vm.formulario.value
        vm.cambiar(form.copy(deParteDe = "Cambio"))
        assertEquals(original, vm.formulario.value)
        val first = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(first)
        fake.values["userId"] = "c000000000000000000000000b"
        server.enqueue(exito()); vm.cobrar()
        val second = server.takeRequest(1, TimeUnit.SECONDS)
        assertNotNull(second)
        assertArrayEquals(first!!.body.readByteArray(), second!!.body.readByteArray())
        assertNotEquals(first.getHeader("Authorization"), second.getHeader("Authorization"))
        assertEquals("c000000000000000000000000a", store.delNegocio("a").single().iniciadoPor)
    }
    @Test fun `rechazo definitivo solo suelta con persistencia confirmada y conserva message`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setHeader("Content-Type", "application/json")
            .setBody("""{"code":"GIFT_CARDS_NOT_IN_PLAN","message":"Mensaje del negocio"}"""))
        val vm = vm(); vm.cobrar()
        assertEquals(VenderTarjetaViewModel.Estado.Error("Mensaje del negocio"), vm.estado.value)
        assertTrue(store.delNegocio("a").isEmpty())
        val llave = vm.llave; vm.cambiar(form.copy(deParteDe = "Otra"))
        assertNotEquals(llave, vm.llave)
    }
    @Test fun `P1 sin red o con negocio cambiado no guarda ni manda`() = runBlocking {
        for (which in 0..2) {
            val vm = vm()
            if (which == 0) conectado.value = false
            if (which == 1) alcanzable.value = false
            if (which == 2) fake.values["venueId"] = "b"
            vm.cobrar()
            assertTrue(vm.estado.value is VenderTarjetaViewModel.Estado.Error)
            assertTrue(store.delNegocio("a").isEmpty())
            conectado.value = true; alcanzable.value = true
        }
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
    }
}
