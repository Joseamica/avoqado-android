package com.avoqado.pos.giftcards

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.*
import com.avoqado.pos.giftcards.presentation.*
import com.avoqado.pos.pos.data.LectorHidBus
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConexionYCicloDePantallaR1Test {
    @get:Rule val main = MainDispatcherRule()
    private lateinit var prefs: SolicitudesDeTarjetaTest.Preferencias
    private lateinit var store: SolicitudesDeTarjetaStore
    private lateinit var repo: GiftCardsRepository
    private lateinit var red: ConnectivityMonitor
    private val conectado = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true)
    private val modelos = mutableListOf<androidx.lifecycle.ViewModel>()
    private val ajustes = AjustesDeTarjetas(true, listOf(500.0), 100.0, 10000.0, sale = VentaPermitida(true), capacidades = setOf("ENTREGA"))
    @Before fun preparar() {
        prefs = SolicitudesDeTarjetaTest.Preferencias()
        prefs.values["venueId"]="a"; prefs.values["userId"]="staff"
        store = SolicitudesDeTarjetaStore(prefs.almacen())
        repo = mockk {
            every { cambiosDeAjustes } returns MutableStateFlow<Map<String, EstadoDeAjustes>>(emptyMap())
            every { ajustesParaEntrada("a") } returns ajustes
        }
        red = mockk { every { isConnected } returns conectado; every { isServerReachable } returns servidor }
    }
    @After fun cerrar() { modelos.forEach { it.viewModelScope.cancel() } }
    private fun venta() = VenderTarjetaViewModel(repo,store,prefs.almacen(),red).also {
        modelos += it; it.alAbrir("a",ajustes)
        it.cambiar(FormularioDeVenta(listOf(SobreEnFormulario(50000,"PRINTED","Ana","","","")),""))
    }
    @Test fun `monto pagar sigue ruta y servidor sin salir de la hoja`() {
        for (control in listOf(conectado,servidor)) {
            assertTrue(controlDeMontoConSaldo(conectado.value,servidor.value,true))
            control.value=false
            assertFalse("Pagar debe apagarse al caer cualquiera de los monitores",controlDeMontoConSaldo(conectado.value,servidor.value,true))
            control.value=true
            assertTrue(controlDeMontoConSaldo(conectado.value,servidor.value,true))
        }
        assertFalse(controlDeMontoConSaldo(true,true,false))
    }
    @Test fun `monto reintentar sigue ruta y servidor sin cambiar desenlace`() {
        for (online in listOf(false to true,true to false)) {
            assertFalse(controlDeMontoConSaldo(online.first,online.second,true))
            assertTrue(controlDeMontoConSaldo(true,true,true))
        }
    }
    @Test fun `vender principal sigue ruta y servidor conservando validacion`() {
        for (online in listOf(false to true,true to false)) {
            assertFalse(controlDeVentaDeTarjeta(online.first,online.second,true))
            assertTrue(controlDeVentaDeTarjeta(true,true,true))
        }
        assertFalse(controlDeVentaDeTarjeta(true,true,false))
    }
    @Test fun `venta guardada reintentar sigue ruta y servidor`() {
        for (online in listOf(false to true,true to false)) {
            assertFalse(controlDeVentaDeTarjeta(online.first,online.second,true))
            assertTrue(controlDeVentaDeTarjeta(true,true,true))
        }
    }
    @Test fun `resultado reintentar sigue ruta y servidor y respeta ocupado`() {
        for (online in listOf(false to true,true to false)) {
            assertFalse(controlDeResultadoDeTarjeta(online.first,online.second,true))
            assertTrue(controlDeResultadoDeTarjeta(true,true,true))
        }
        assertFalse(controlDeResultadoDeTarjeta(true,true,false))
    }
    @Test fun `consultar cobrar con esta sigue ambos monitores sin perder la identificada`() = runTest {
        val roles=mockk<RoleManager> { every { canReadGiftCards } returns true }
        val vm=IdentificarTarjetaViewModel(repo,prefs.almacen(),red,LectorHidBus(),roles)
        modelos += vm
        val card=TarjetaConsultada("g","1234",500.0,"ACTIVE",usable=true)
        coEvery { repo.consultar("a",any()) } returns Respuesta.Ok(ConsultaDeSaldo(500.0,listOf(card)))
        vm.abrir("a",ajustes); vm.porCodigo("abcd")
        for (control in listOf(conectado,servidor)) {
            assertTrue(vm.puedeCobrar(true,true)); control.value=false
            assertFalse("La selección no autoriza cobrar sin conexión",vm.puedeCobrar(true,true))
            control.value=true; assertTrue(vm.puedeCobrar(true,true))
        }
        assertNotNull(vm.identificada.value)
        vm.cerrar()
    }
    @Test fun `girar durante crear conserva operacion del modelo y termina una sola vez`() = runTest {
        val respuesta=CompletableDeferred<Respuesta<VentaDeTarjetas>>()
        coEvery { repo.vender("a",any()) } coAnswers { respuesta.await() }
        val vm=venta(); val llave=vm.llave
        val pantalla=CoroutineScope(SupervisorJob()+mainDispatcher())
        pantalla.launch { vm.cobrarDesdePantalla() }
        runCurrent()
        assertEquals(VenderTarjetaViewModel.Estado.Enviando,vm.estado.value)
        assertEquals(llave,store.delNegocio("a").single().llave)
        pantalla.cancel(); runCurrent()
        respuesta.complete(Respuesta.Ok(VentaDeTarjetas("o","n",500.0,sobres=emptyList())))
        runCurrent()
        assertEquals("La recreación no puede dejar Enviando permanente",VenderTarjetaViewModel.Estado.Cobro("o"),vm.estado.value)
        vm.alAbrir("a",ajustes)
        assertTrue(vm.reanudar(llave))
        coEvery { repo.cuenta("a","o") } returns Respuesta.SinRespuesta
        vm.cobrarDesdePantalla()
        assertTrue(vm.estado.value is VenderTarjetaViewModel.Estado.Error)
        assertEquals("o",store.delNegocio("a").single().orderId)
        coVerify(exactly=1) { repo.vender("a",any()) }
        assertTrue(store.enVueloDeEsteProceso.isEmpty())
    }
    @Test fun `doble toque durante creacion conserva una solicitud y un envio`() = runTest {
        val respuesta=CompletableDeferred<Respuesta<VentaDeTarjetas>>()
        coEvery { repo.vender("a",any()) } coAnswers { respuesta.await() }
        val vm=venta()
        val primero=launch { vm.cobrarDesdePantalla() }; runCurrent()
        val segundo=launch { vm.cobrarDesdePantalla() }; runCurrent()
        assertEquals(1,store.delNegocio("a").size)
        respuesta.complete(Respuesta.Ok(VentaDeTarjetas("o","n",500.0,sobres=emptyList())))
        primero.join(); segundo.join()
        coVerify(exactly=1) { repo.vender("a",any()) }
        assertEquals(VenderTarjetaViewModel.Estado.Cobro("o"),vm.estado.value)
    }
    private fun mainDispatcher() = Dispatchers.Main.immediate
}
