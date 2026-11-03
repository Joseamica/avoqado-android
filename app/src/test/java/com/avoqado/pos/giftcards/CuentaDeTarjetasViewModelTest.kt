package com.avoqado.pos.giftcards

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.giftcards.presentation.CuentaDeTarjetasViewModel
import com.avoqado.pos.orders.data.OrdersRepository
import com.avoqado.pos.orders.data.model.*
import com.avoqado.pos.transactions.data.TransactionRepository
import com.avoqado.pos.transactions.data.model.Transaction
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*

class CuentaDeTarjetasViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private var venue = "c123456789012345678901234"
    private var usuario = "c223456789012345678901234"
    private val order = "c323456789012345678901234"
    private val online = MutableStateFlow(true)
    private val reach = MutableStateFlow(true)
    private val repo = mockk<GiftCardsRepository>()
    private val orders = mockk<OrdersRepository>()
    private val txs = mockk<TransactionRepository>()
    private val roles = mockk<RoleManager>(relaxed=true)
    private val storage = mockk<SecureStorage>(relaxed=true) {
        every { venueId } answers { venue }; every { userId } answers { usuario }
    }
    private val red = mockk<ConnectivityMonitor> {
        every { isConnected } returns online; every { isServerReachable } returns reach
    }
    private val vm = CuentaDeTarjetasViewModel(repo,orders,txs,storage,roles,red,mockk(relaxed=true),mockk(relaxed=true),mockk(relaxed=true))
    private var cancellation = Cancelacion(true)
    private var cuantos = 2
    private fun cuenta(id: String = order) = CuentaDeTarjetas(id,"ORD-7",1000.02,"PAID","COMPLETED",sobres=(1..cuantos).map {
        SobreDeLaCuenta("c42345678901234567890123$it","JKMN",500.01,500.01,"ACTIVE",deliveryChannel="WHATSAPP",usada=false,puedeReenviar=true,puedeCambiarDestino=true,puedeRevelar=true)
    },cancel=cancellation)
    @Before fun setup() {
        every { roles.canReadGiftCards } returns true
        every { roles.canManageGiftCards } returns true
        coEvery { repo.cuenta(any(),any()) } answers { Respuesta.Ok(cuenta(secondArg())) }
        coEvery { repo.cancelarVenta(any(),any(),any()) } returns Respuesta.Ok(listOf("tarjeta"))
        coEvery { repo.liberarCancelacion(any(),any(),any()) } returns Respuesta.Ok(Unit)
        coEvery { orders.fetchOrderDetail(any()) } answers { OrderDetail(firstArg(),payments=listOf(OrderPaymentInfo("p1",status="COMPLETED"),OrderPaymentInfo("p2",status="PENDING"),OrderPaymentInfo("p3",status="COMPLETED"))) }
        coEvery { txs.fetchTransactionDetail(any()) } answers { Transaction(firstArg(),method="CASH",remainingRefundable=500.01) }
    }
    @After fun close() { vm.cerrar();vm.viewModelScope.cancel() }
    @Test fun permisoYAllowedGobiernanCancelarYElMessageEsLiteral() = runBlocking {
        vm.abrir(order).join(); assertTrue(vm.puedeCancelar)
        every { roles.canManageGiftCards } returns false; assertFalse(vm.puedeCancelar)
        vm.abrirDialogo();vm.confirmarCancelacion()?.join();coVerify(exactly=0){repo.cancelarVenta(any(),any(),any())}
        every { roles.canManageGiftCards } returns true
        cancellation=Cancelacion(false,"ALREADY_USED","Texto literal de cancel.message")
        vm.refrescar().join();assertFalse(vm.puedeCancelar);assertEquals("Texto literal de cancel.message",vm.aviso.value)
    }
    @Test fun confirmacionSumaCentavosYUsaOrderNumberYSingular() = runBlocking {
        vm.abrir(order).join();assertEquals("Venta #ORD-7: se cancelan juntas sus 2 tarjetas ($1,000.02)",vm.confirmacion)
        cuantos=1;vm.refrescar().join();assertEquals("Venta #ORD-7: se cancela su tarjeta ($500.01)",vm.confirmacion)
    }
    @Test fun cancelarCargaSoloCompletedYDiceDevolverCompleto() = runBlocking {
        vm.abrir(order).join();vm.abrirDialogo();vm.confirmarCancelacion()?.join()
        assertEquals("DEVUELVE_COMPLETO",vm.paso.value)
        assertEquals("Ahora devuelve completo cada cobro de esta venta.",vm.aviso.value)
        assertEquals(listOf("p1","p3"),vm.pagosPorDevolver.value.map{it.id})
        coVerify(exactly=0){txs.fetchTransactionDetail("p2")}
    }
    @Test fun mismaLlaveEnReintentoYUnaNuevaPorDialogo() = runBlocking {
        val llaves=mutableListOf<String>()
        coEvery { repo.cancelarVenta(any(),any(),capture(llaves)) } returns Respuesta.SinRespuesta
        vm.abrir(order).join();vm.abrirDialogo();vm.confirmarCancelacion()?.join();vm.confirmarCancelacion()?.join()
        assertEquals(2,llaves.size);assertEquals(llaves[0],llaves[1])
        vm.cerrarDialogo();vm.abrirDialogo();vm.confirmarCancelacion()?.join()
        assertEquals(3,llaves.size);assertNotEquals(llaves[0],llaves[2])
        assertTrue(vm.pagosPorDevolver.value.isEmpty());assertNull(vm.exito.value)
    }
    @Test fun dobleToqueNoDuplicaCancelacion() = runBlocking {
        val entra=CompletableDeferred<Unit>();val fin=CompletableDeferred<Unit>()
        coEvery { repo.cancelarVenta(any(),any(),any()) } coAnswers { entra.complete(Unit);fin.await();Respuesta.Ok(listOf("t")) }
        vm.abrir(order).join();vm.abrirDialogo();val uno=vm.confirmarCancelacion()
        try { assertTrue("La operacion debe empezar antes de probar su suspension",entra.isCompleted); vm.confirmarCancelacion()?.join();coVerify(exactly=1){repo.cancelarVenta(any(),any(),any())} }
        finally {fin.complete(Unit);uno?.join()}
    }
    @Test fun rechazosDeCancelacionConservanElMessage() = runBlocking {
        for (code in listOf("GIFT_CARD_SALE_REFUND_MUST_BE_FULL","GIFT_CARD_ALREADY_USED","GIFT_CARD_CANCEL_WINDOW_CLOSED")) {
            coEvery { repo.cancelarVenta(any(),any(),any()) } returns Respuesta.Rechazo(FalloDeTarjeta(409,code,"Mensaje de $code",null,null))
            vm.abrir(order).join();vm.abrirDialogo();vm.confirmarCancelacion()?.join()
            assertEquals("Mensaje de $code",vm.aviso.value);assertTrue(vm.pagosPorDevolver.value.isEmpty());vm.cerrarDialogo()
        }
    }
    @Test fun liberarSoloEnCursoConPermisoYConfirmacionExplicita() = runBlocking {
        cancellation=Cancelacion(false,"IN_PROGRESS");vm.abrir(order).join();assertTrue(vm.puedeLiberar)
        vm.abrirDialogo(liberar=true);vm.liberar(false)?.join();coVerify(exactly=0){repo.liberarCancelacion(any(),any(),any())}
        vm.liberar(true)?.join();coVerify(exactly=1){repo.liberarCancelacion(venue,order,any())}
        cancellation=Cancelacion(false,"ALREADY_USED");vm.refrescar().join();assertFalse(vm.puedeLiberar)
        every { roles.canManageGiftCards } returns false;cancellation=Cancelacion(false,"IN_PROGRESS");vm.refrescar().join();assertFalse(vm.puedeLiberar)
    }
    @Test fun bloqueadaAlLiberarNoAnunciaExito() = runBlocking {
        cancellation=Cancelacion(false,"IN_PROGRESS");vm.abrir(order).join();vm.abrirDialogo(liberar=true)
        coEvery { repo.liberarCancelacion(any(),any(),any()) } returns Respuesta.Rechazo(FalloDeTarjeta(409,"GIFT_CARD_BLOCKED","Bloqueada por otro motivo",null,null))
        vm.liberar(true)?.join();assertEquals("Bloqueada por otro motivo",vm.aviso.value);assertNull(vm.exito.value)
    }
    @Test fun sinRedCeroPeticionesYMensajeCanonico() = runBlocking {
        vm.abrir(order).join();clearMocks(repo,answers=false);online.value=false
        vm.abrirDialogo();vm.confirmarCancelacion()?.join();vm.refrescar().join()
        assertEquals(TextosDeTarjetas.SIN_RED,vm.aviso.value);coVerify(exactly=0){repo.cancelarVenta(any(),any(),any())};coVerify(exactly=0){repo.cuenta(any(),any())}
        online.value=true;cancellation=Cancelacion(false,"IN_PROGRESS");vm.refrescar().join();clearMocks(repo,answers=false);reach.value=false
        vm.abrirDialogo(liberar=true);vm.liberar(true)?.join();assertEquals(TextosDeTarjetas.SIN_RED,vm.aviso.value);coVerify(exactly=0){repo.liberarCancelacion(any(),any(),any())}
    }
    @Test fun detalleFaltanteNoOmiteUnCobroNiOfreceDevolverUnaParte() = runBlocking {
        coEvery { txs.fetchTransactionDetail("p3") } returns null
        vm.abrir(order).join();vm.abrirDialogo();vm.confirmarCancelacion()?.join()
        assertTrue(vm.pagosPorDevolver.value.isEmpty());assertNotNull(vm.aviso.value);assertNull(vm.exito.value)
    }
    @Test fun respuestaTardiaDeAtrasAbrirBNoPublicaPagosNiMensajeEnB() = runBlocking {
        val entra=CompletableDeferred<Unit>();val fin=CompletableDeferred<Unit>()
        coEvery { repo.cancelarVenta(any(),order,any()) } coAnswers {entra.complete(Unit);withContext(NonCancellable){fin.await()};Respuesta.Ok(listOf("t"))}
        vm.abrir(order).join();vm.abrirDialogo();val uno=vm.confirmarCancelacion()
        try { assertTrue("La operacion debe empezar antes de probar su suspension",entra.isCompleted);
            vm.abrir("c523456789012345678901234").join();fin.complete(Unit);uno?.join()
            assertEquals("c523456789012345678901234",vm.cuenta.value?.orderId);assertTrue(vm.pagosPorDevolver.value.isEmpty());assertNull(vm.paso.value)
        } finally {fin.complete(Unit);uno?.join()}
    }
    @Test fun cambiarUsuarioDuranteLecturaNoPublicaCuentaAjena() = runBlocking {
        val entra=CompletableDeferred<Unit>();val fin=CompletableDeferred<Unit>()
        coEvery { repo.cuenta(any(),any()) } coAnswers {entra.complete(Unit);fin.await();Respuesta.Ok(cuenta())}
        val uno=vm.abrir(order);try { assertTrue("La operacion debe empezar antes de probar su suspension",entra.isCompleted);usuario="c623456789012345678901234";fin.complete(Unit);uno.join()
            assertNull(vm.cuenta.value);assertFalse(vm.puedeCancelar)
        } finally {fin.complete(Unit);uno.join()}
    }

    @Test fun clienteDeGerenteConservaPinYConfirmacionNoDevuelto() = runBlocking {
        every { storage.leerAjustesDeTarjetas(any()) } returns null
        val server=okhttp3.mockwebserver.MockWebServer();server.start()
        val previa=System.getProperty("avoqado.test.baseUrl")
        System.setProperty("avoqado.test.baseUrl",server.url("/api/v1").toString())
        try {
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"success":true,"data":{"giftCardIds":["t"]}}"""))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"success":true,"data":{"released":true}}"""))
            val real=GiftCardsRepository(okhttp3.OkHttpClient(),storage)
            assertTrue(real.cancelarVenta(venue,order,"cancela-an7") is Respuesta.Ok)
            assertTrue(real.liberarCancelacion(venue,order,"libera-an7") is Respuesta.Ok)
            val cancelar=server.takeRequest();val liberar=server.takeRequest()
            assertTrue(cancelar.path!!.endsWith("/cancel-sale"));assertTrue(liberar.path!!.endsWith("/release-cancel"))
            for(r in listOf(cancelar,liberar)) {
                assertNull(r.getHeader(com.avoqado.pos.core.data.network.ForbiddenInterceptor.LOCAL_ERROR_HEADER))
                assertNull(r.getHeader(com.avoqado.pos.core.data.network.ForbiddenInterceptor.FAIL_FAST_HEADER))
            }
            assertEquals(0,real.clienteDeGerente.callTimeoutMillis)
            assertEquals("""{"operationKey":"libera-an7","confirmacionNoDevuelto":true}""",liberar.body.readUtf8())
        } finally {
            if(previa==null) System.clearProperty("avoqado.test.baseUrl") else System.setProperty("avoqado.test.baseUrl",previa)
            server.shutdown()
        }
    }

    @Test fun liberarReintentaConLaMismaLlaveSinInventarExito() = runBlocking {
        val llaves=mutableListOf<String>();cancellation=Cancelacion(false,"IN_PROGRESS")
        coEvery { repo.liberarCancelacion(any(),any(),capture(llaves)) } returns Respuesta.SinRespuesta
        vm.abrir(order).join();vm.abrirDialogo(liberar=true);vm.liberar(true)?.join();vm.liberar(true)?.join()
        assertEquals(2,llaves.size);assertEquals(llaves[0],llaves[1]);assertNull(vm.exito.value)
    }
    @Test fun cancelacionYaEnCursoSeRetomaDesdeSusPagosSinOtroCancelSale() = runBlocking {
        cancellation=Cancelacion(false,"IN_PROGRESS");vm.abrir(order).join()
        assertEquals(listOf("p1","p3"),vm.pagosPorDevolver.value.map{it.id});assertEquals("DEVUELVE_COMPLETO",vm.paso.value)
        coVerify(exactly=0){repo.cancelarVenta(any(),any(),any())}
    }
    @Test fun sinPermisoDeLeerNoConsultaCuentaNiPermiteDinero() = runBlocking {
        every { roles.canReadGiftCards } returns false;vm.abrir(order).join()
        assertEquals(TextosDeTarjetas.SIN_PERMISO_CONSULTAR,vm.aviso.value)
        assertFalse(vm.puedeCancelar);assertFalse(vm.puedeLiberar);coVerify(exactly=0){repo.cuenta(any(),any())}
    }
    @Test fun permisoDenegadoPorRutaDeGerenteNoDuplicaElAvisoGlobal() = runBlocking {
        coEvery { repo.cancelarVenta(any(),any(),any()) } returns Respuesta.Rechazo(FalloDeTarjeta(403,null,"Permission giftCards:manage required",null,null))
        vm.abrir(order).join();vm.abrirDialogo();vm.confirmarCancelacion()?.join()
        assertNull(vm.aviso.value);assertNull(vm.paso.value);assertTrue(vm.pagosPorDevolver.value.isEmpty())
    }

    @Test fun cancelacionConfirmadaTrasCaerLaRedNoConsultaCobros() = runBlocking {
        val respuesta = CompletableDeferred<Respuesta<List<String>>>()
        coEvery { repo.cancelarVenta(any(),any(),any()) } coAnswers { respuesta.await() }
        vm.abrir(order).join();vm.abrirDialogo()
        val operacion=vm.confirmarCancelacion()
        try {
            assertNotNull(operacion)
            online.value=false
            respuesta.complete(Respuesta.Ok(listOf("tarjeta")));operacion?.join()
            coVerify(exactly=0){orders.fetchOrderDetail(any())}
            coVerify(exactly=0){txs.fetchTransactionDetail(any())}
            assertEquals("DEVUELVE_COMPLETO",vm.paso.value)
            assertEquals(TextosDeTarjetas.SIN_RED,vm.aviso.value)
            assertTrue(vm.pagosPorDevolver.value.isEmpty())
        } finally { respuesta.complete(Respuesta.SinRespuesta);operacion?.join() }
    }

    @Test fun lecturaEnCursoTrasCaerLaRedNoConsultaDetalle() = runBlocking {
        cancellation=Cancelacion(false,"IN_PROGRESS")
        val respuesta=CompletableDeferred<Respuesta<CuentaDeTarjetas>>()
        coEvery { repo.cuenta(any(),any()) } coAnswers { respuesta.await() }
        val lectura=vm.abrir(order)
        try {
            online.value=false
            respuesta.complete(Respuesta.Ok(cuenta()));lectura.join()
            coVerify(exactly=0){orders.fetchOrderDetail(any())}
            coVerify(exactly=0){txs.fetchTransactionDetail(any())}
            assertEquals("DEVUELVE_COMPLETO",vm.paso.value)
            assertEquals(TextosDeTarjetas.SIN_RED,vm.aviso.value)
            assertTrue(vm.pagosPorDevolver.value.isEmpty())
        } finally { respuesta.complete(Respuesta.SinRespuesta);lectura.join() }
    }

    @Test fun alVolverLaRedRefrescarPreparaLosCobrosUnaSolaVez() = runBlocking {
        cancellation=Cancelacion(false,"IN_PROGRESS")
        coEvery { repo.cuenta(any(),any()) } coAnswers { online.value=false;Respuesta.Ok(cuenta()) }
        vm.abrir(order).join()
        coVerify(exactly=0){orders.fetchOrderDetail(any())}
        coEvery { repo.cuenta(any(),any()) } answers { Respuesta.Ok(cuenta(secondArg())) }
        online.value=true;vm.refrescar().join()
        coVerify(exactly=1){orders.fetchOrderDetail(order)}
        coVerify(exactly=1){txs.fetchTransactionDetail("p1")}
        coVerify(exactly=1){txs.fetchTransactionDetail("p3")}
        coVerify(exactly=0){txs.fetchTransactionDetail("p2")}
        assertEquals(listOf("p1","p3"),vm.pagosPorDevolver.value.map{it.id})
        assertEquals(TextosDeTarjetas.DEVUELVE_COMPLETO,vm.aviso.value)
    }

    private fun liberacionConCaidaDeRed(sinServidor: Boolean) = runBlocking {
        cancellation=Cancelacion(false,"IN_PROGRESS");vm.abrir(order).join();vm.abrirDialogo(liberar=true)
        clearMocks(repo,orders,txs,answers=false)
        val respuesta=CompletableDeferred<Respuesta<Unit>>()
        coEvery { repo.liberarCancelacion(any(),any(),any()) } coAnswers { respuesta.await() }
        val operacion=vm.liberar(true)
        try {
            assertNotNull(operacion)
            coVerify(exactly=1){repo.liberarCancelacion(venue,order,any())}
            vm.liberar(true)?.join()
            coVerify(exactly=1){repo.liberarCancelacion(any(),any(),any())}
            if(sinServidor) reach.value=false else online.value=false
            respuesta.complete(Respuesta.Ok(Unit));operacion?.join()
            coVerify(exactly=0){repo.cuenta(any(),any())}
            coVerify(exactly=0){orders.fetchOrderDetail(any())}
            coVerify(exactly=0){txs.fetchTransactionDetail(any())}
            assertEquals(TextosDeTarjetas.SIN_RED,vm.aviso.value)
            assertTrue(vm.pagosPorDevolver.value.isEmpty());assertFalse(vm.ocupado.value)
            cancellation=Cancelacion(true);online.value=true;reach.value=true
            vm.refrescar().join()
            coVerify(exactly=1){repo.cuenta(venue,order)}
            assertNull(vm.aviso.value);assertTrue(vm.puedeCancelar)
        } finally {respuesta.complete(Respuesta.SinRespuesta);operacion?.join()}
    }
    @Test fun liberarConfirmadoSinRedNoIniciaOtraCuentaYRecuperaUnaVez() = liberacionConCaidaDeRed(false)
    @Test fun liberarConfirmadoSinServidorNoIniciaOtraCuentaYRecuperaUnaVez() = liberacionConCaidaDeRed(true)

    @Test fun caerLaRedDuranteDetalleNoIniciaTransacciones() = runBlocking {
        cancellation=Cancelacion(false,"IN_PROGRESS")
        val respuesta=CompletableDeferred<OrderDetail?>()
        coEvery { orders.fetchOrderDetail(any()) } coAnswers { respuesta.await() }
        val lectura=vm.abrir(order)
        try {
            coVerify(exactly=1){orders.fetchOrderDetail(order)}
            online.value=false
            respuesta.complete(OrderDetail(order,payments=listOf(OrderPaymentInfo("p1",status="COMPLETED"))));lectura.join()
            coVerify(exactly=0){txs.fetchTransactionDetail(any())}
            assertTrue(vm.pagosPorDevolver.value.isEmpty());assertEquals(TextosDeTarjetas.SIN_RED,vm.aviso.value)
        } finally {respuesta.complete(null);lectura.join()}
    }
    @Test fun caerLaRedDuranteUnCobroNoIniciaElSiguienteNiPublicaListaParcial() = runBlocking {
        cancellation=Cancelacion(false,"IN_PROGRESS")
        val respuesta=CompletableDeferred<Transaction?>()
        coEvery { txs.fetchTransactionDetail("p1") } coAnswers { respuesta.await() }
        val lectura=vm.abrir(order)
        try {
            coVerify(exactly=1){txs.fetchTransactionDetail("p1")}
            reach.value=false;respuesta.complete(Transaction("p1",method="CASH",remainingRefundable=500.01));lectura.join()
            coVerify(exactly=0){txs.fetchTransactionDetail("p3")}
            assertTrue(vm.pagosPorDevolver.value.isEmpty());assertEquals(TextosDeTarjetas.SIN_RED,vm.aviso.value)
            reach.value=true;clearMocks(repo,orders,txs,answers=false);vm.refrescar().join()
            coVerify(exactly=1){repo.cuenta(venue,order)}
            coVerify(exactly=1){orders.fetchOrderDetail(order)}
            coVerify(exactly=1){txs.fetchTransactionDetail("p1")}
            coVerify(exactly=1){txs.fetchTransactionDetail("p3")}
            assertEquals(listOf("p1","p3"),vm.pagosPorDevolver.value.map{it.id})
        } finally {respuesta.complete(null);lectura.join()}
    }
}
