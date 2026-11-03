package com.avoqado.pos.giftcards

import app.cash.turbine.test
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.domain.PlanManager
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.giftcards.data.*
import com.avoqado.pos.giftcards.domain.TextosDeTarjetas
import com.avoqado.pos.giftcards.presentation.TarjetasDeRegaloViewModel
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class TarjetasDeRegaloViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val cambios = MutableStateFlow<Map<String, EstadoDeAjustes>>(emptyMap())
    private val ajustes = MutableStateFlow<EstadoDeAjustes>(EstadoDeAjustes.Disponible(AjustesDeTarjetas(
        enabled = true, suggestedAmounts = listOf(500.0), minAmount = 100.0, maxAmount = 10000.0,
        sale = VentaPermitida(true), capacidades = setOf("ENTREGA"),
    )))
    private val conexion = MutableStateFlow(true)
    private val servidor = MutableStateFlow(true)
    private var venue: String? = "sucursal-a"
    private val cache = mutableMapOf<String, String?>("sucursal-a" to FijasDeTarjetas.ajustes)
    private var permisos = listOf("giftCards:read", "giftCards:sell")
    private val almacen = mockk<SecureStorage> {
        every { venueId } answers { venue }
        every { userRole } returns "CASHIER"
        every { venuePermissions } answers { permisos }
        every { leerAjustesDeTarjetas(any()) } answers { cache[firstArg()] }
    }
    private val repo = mockk<GiftCardsRepository> {
        every { estadoDeAjustes } returns ajustes
        every { cambiosDeAjustes } returns cambios
        coEvery { refrescarAjustes(any()) } answers { ajustes.value }
    }
    private val red = mockk<ConnectivityMonitor> {
        every { isConnected } returns conexion
        every { isServerReachable } returns servidor
    }
    private val planes = mockk<PlanManager> { every { requiredTierLabel("GIFT_CARDS") } returns "Pro" }
    private fun vm() = TarjetasDeRegaloViewModel(repo, RoleManager(almacen), planes, red, almacen)

    @Test fun `ajustes de venta pertenecen al venue capturado y no al flow global`() = runTest {
        val vm = vm()
        assertNotNull(vm.ajustesParaVender("sucursal-a"))
        ajustes.value = EstadoDeAjustes.SinPermiso
        assertNotNull("La foto global no identifica sucursal", vm.ajustesParaVender("sucursal-a"))
        assertNull(vm.ajustesParaVender("sucursal-b"))
        venue = "sucursal-b"
        assertNull(vm.ajustesParaVender("sucursal-a"))
        vm.alAbrir(true); runCurrent()
        assertNull(vm.ajustesParaVender("sucursal-b"))
    }

    @Test fun `ajustes de venta revalidan red permiso y carrito`() = runTest {
        val vm = vm()
        assertNotNull(vm.ajustesParaVender("sucursal-a"))
        conexion.value = false; assertNull(vm.ajustesParaVender("sucursal-a"))
        conexion.value = true; servidor.value = false; assertNull(vm.ajustesParaVender("sucursal-a"))
        servidor.value = true; permisos = listOf("giftCards:read"); assertNull(vm.ajustesParaVender("sucursal-a"))
        permisos = listOf("giftCards:read", "giftCards:sell")
        vm.alAbrir(false); runCurrent(); assertNull(vm.ajustesParaVender("sucursal-a"))
    }

    @Test fun `tarjeta identificada nunca revela prueba ni tarjeta al imprimir objeto`() {
        val identificada = TarjetaIdentificada(PruebaDeTarjeta.PageToken("secreto"),
            TarjetaConsultada("g", "1234", 500.0, "ACTIVE", usable = true))
        assertEquals("TarjetaIdentificada(****)", identificada.toString())
        assertFalse(identificada.toString().contains("secreto"))
    }

    @Test fun `al abrir con red refresca una vez para la sucursal actual`() = runTest {
        val vm = vm()
        venue = "sucursal-b"
        vm.alAbrir(ventaVacia = true)
        runCurrent()
        coVerify(exactly = 1) { repo.refrescarAjustes("sucursal-b") }
        coVerify(exactly = 0) { repo.refrescarAjustes("sucursal-a") }
    }

    @Test fun `no refresca sin conexion o sin servidor`() = runTest {
        val vm = vm()
        conexion.value = false
        vm.alAbrir(true)
        conexion.value = true
        servidor.value = false
        vm.alAbrir(true)
        runCurrent()
        coVerify(exactly = 0) { repo.refrescarAjustes(any()) }
        assertEquals(TextosDeTarjetas.SIN_RED, vm.hoja.value.consultar.motivo)
    }

    @Test fun `al volver conexion y servidor recalcula la hoja`() = runTest {
        val vm = vm()
        vm.hoja.test {
            assertTrue(awaitItem().vender.habilitada)
            conexion.value = false
            assertEquals(TextosDeTarjetas.SIN_RED, awaitItem().vender.motivo)
            conexion.value = true
            assertTrue(awaitItem().vender.habilitada)
            servidor.value = false
            assertEquals(TextosDeTarjetas.SIN_RED, awaitItem().consultar.motivo)
            servidor.value = true
            assertTrue(awaitItem().consultar.habilitada)
        }
    }

    @Test fun `abrir relee permisos y venta en lugar de congelar la primera foto`() = runTest {
        val vm = vm()
        vm.alAbrir(false)
        runCurrent()
        assertEquals(TextosDeTarjetas.VENTA_CON_ARTICULOS, vm.hoja.value.vender.motivo)
        permisos = listOf("giftCards:read")
        vm.alAbrir(true)
        runCurrent()
        assertEquals(TextosDeTarjetas.SIN_PERMISO_VENDER, vm.hoja.value.vender.motivo)
        assertTrue(vm.hoja.value.consultar.habilitada)
        permisos = listOf("giftCards:sell")
        vm.alAbrir(true)
        runCurrent()
        assertTrue(vm.hoja.value.vender.habilitada)
        assertEquals(TextosDeTarjetas.SIN_PERMISO_CONSULTAR, vm.hoja.value.consultar.motivo)
    }

    @Test fun `abrir revalida un 404 cacheado y refleja el resultado`() = runTest {
        val disponible = ajustes.value
        ajustes.value = EstadoDeAjustes.NoDisponible
        cache["sucursal-a"] = "null"
        val vm = vm()
        assertFalse(vm.hoja.value.mosaicoVisible)
        coEvery { repo.refrescarAjustes("sucursal-a") } answers { ajustes.value = disponible; disponible }
        vm.alAbrir(true)
        runCurrent()
        assertTrue(vm.hoja.value.mosaicoVisible)
        assertTrue(vm.hoja.value.vender.habilitada)
    }

    @Test fun `sin sucursal no hace peticion`() = runTest {
        venue = null
        vm().alAbrir(true)
        runCurrent()
        coVerify(exactly = 0) { repo.refrescarAjustes(any()) }
    }

    @Test fun `cambiar de A a B sin red no muestra capacidad de A`() = runTest {
        val vm = vm()
        assertTrue(vm.hoja.value.vender.visible)
        conexion.value = false
        venue = "sucursal-b"
        vm.alAbrir(true)
        runCurrent()
        assertFalse(vm.hoja.value.vender.visible)
        assertFalse(vm.hoja.value.mosaicoVisible)
        assertFalse(vm.hoja.value.consultar.visible)
        assertNull(vm.hoja.value.consultar.motivo)
        coVerify(exactly = 0) { repo.refrescarAjustes(any()) }
    }

    @Test fun `refresco retenido de A no se adopta en B sin red`() = runTest {
        val retenida = CompletableDeferred<EstadoDeAjustes>()
        val fotoA = ajustes.value
        coEvery { repo.refrescarAjustes("sucursal-a") } coAnswers {
            retenida.await().also { ajustes.value = it }
        }
        val vm = vm()
        vm.alAbrir(true)
        conexion.value = false
        venue = "sucursal-b"
        vm.alAbrir(true)
        retenida.complete(fotoA)
        runCurrent()
        assertFalse(vm.hoja.value.vender.visible)
        assertFalse(vm.hoja.value.mosaicoVisible)
        assertFalse(vm.hoja.value.consultar.visible)
        assertNull(vm.hoja.value.consultar.motivo)
        coVerify(exactly = 0) { repo.refrescarAjustes("sucursal-b") }
    }

    @Test fun `VM nueva en B no adopta ajustes globales de A`() = runTest {
        venue = "sucursal-b"
        conexion.value = false
        val vm = vm()
        assertFalse(vm.hoja.value.vender.visible)
        assertFalse(vm.hoja.value.mosaicoVisible)
        assertFalse(vm.hoja.value.consultar.visible)
        assertNull(vm.hoja.value.consultar.motivo)
    }

    @Test fun `VM nueva usa solo la cache propia de B sin entrega`() = runTest {
        venue = "sucursal-b"
        cache["sucursal-b"] = """{"enabled":true,"suggestedAmounts":[500],"minAmount":100,"maxAmount":10000,"sale":{"sellable":true},"capacidades":[]}"""
        val vm = vm()
        assertFalse(vm.hoja.value.vender.visible)
        assertTrue(vm.hoja.value.consultar.habilitada)
    }

    @Test fun `sin sucursal no conserva A y al volver usa su cache propia`() = runTest {
        val vm = vm()
        conexion.value = false
        venue = null
        vm.alAbrir(true)
        runCurrent()
        assertFalse(vm.hoja.value.vender.visible)
        venue = "sucursal-a"
        vm.alAbrir(true)
        runCurrent()
        assertTrue(vm.hoja.value.vender.visible)
        assertEquals(TextosDeTarjetas.SIN_RED, vm.hoja.value.vender.motivo)
        coVerify(exactly = 0) { repo.refrescarAjustes(any()) }
    }

    @Test fun `volver A no adopta su respuesta de una apertura anterior al cambio`() = runTest {
        val retenida = CompletableDeferred<EstadoDeAjustes>()
        val vieja = EstadoDeAjustes.Disponible((ajustes.value as EstadoDeAjustes.Disponible).ajustes.copy(capacidades = emptySet()))
        coEvery { repo.refrescarAjustes("sucursal-a") } coAnswers {
            retenida.await().also { ajustes.value = it }
        }
        val vm = vm()
        vm.alAbrir(true)
        conexion.value = false
        venue = "sucursal-b"
        vm.alAbrir(true)
        venue = "sucursal-a"
        vm.alAbrir(true)
        retenida.complete(vieja)
        runCurrent()
        assertTrue(vm.hoja.value.vender.visible)
        assertEquals(TextosDeTarjetas.SIN_RED, vm.hoja.value.vender.motivo)
    }

    @Test fun `apertura anterior del mismo negocio no reemplaza restriccion reciente`() = runTest {
        val retenida = CompletableDeferred<EstadoDeAjustes>()
        val vieja = ajustes.value
        val nueva = EstadoDeAjustes.Disponible((vieja as EstadoDeAjustes.Disponible).ajustes.copy(
            sale = VentaPermitida(false, message = "Venta apagada recientemente"),
        ))
        var llamadas = 0
        coEvery { repo.refrescarAjustes("sucursal-a") } coAnswers {
            llamadas++
            (if (llamadas == 1) retenida.await() else nueva).also { ajustes.value = it }
        }
        val vm = vm()
        vm.alAbrir(true)
        runCurrent()
        assertEquals(1, llamadas)
        vm.alAbrir(true)
        runCurrent()
        assertEquals(2, llamadas)
        assertEquals("Venta apagada recientemente", vm.hoja.value.vender.motivo)
        retenida.complete(vieja)
        runCurrent()
        assertEquals("Venta apagada recientemente", vm.hoja.value.vender.motivo)
        assertFalse(vm.hoja.value.vender.habilitada)
    }

    @Test fun `apertura offline del mismo negocio invalida su respuesta anterior`() = runTest {
        val retenida = CompletableDeferred<EstadoDeAjustes>()
        val vieja = EstadoDeAjustes.Disponible((ajustes.value as EstadoDeAjustes.Disponible).ajustes.copy(capacidades = emptySet()))
        var llamadas = 0
        coEvery { repo.refrescarAjustes("sucursal-a") } coAnswers {
            llamadas++
            retenida.await().also { ajustes.value = it }
        }
        val vm = vm()
        vm.alAbrir(true)
        runCurrent()
        assertEquals(1, llamadas)
        conexion.value = false
        vm.alAbrir(true)
        runCurrent()
        retenida.complete(vieja)
        runCurrent()
        assertEquals(1, llamadas)
        assertTrue(vm.hoja.value.vender.visible)
        assertEquals(TextosDeTarjetas.SIN_RED, vm.hoja.value.vender.motivo)
    }
    @Test fun `union E mosaico abierto observa cache retirada por otro refresco propio`() = runTest {
        val vm=vm();runCurrent();assertTrue(vm.hoja.value.mosaicoVisible)
        cache["sucursal-a"]="null";ajustes.value=EstadoDeAjustes.NoDisponible
        cambios.value=mapOf("sucursal-a" to EstadoDeAjustes.NoDisponible);runCurrent()
        assertFalse(vm.hoja.value.mosaicoVisible)
        assertNull(vm.ajustesParaConsultar("sucursal-a"));assertNull(vm.ajustesParaVender("sucursal-a"))
    }

    @Test fun `union E apertura adopta respuesta propia y no el retorno global de B`() = runTest {
        val propia=ajustes.value
        cambios.value=mapOf("sucursal-a" to propia,"sucursal-b" to EstadoDeAjustes.NoDisponible)
        coEvery { repo.refrescarAjustes("sucursal-a") } returns EstadoDeAjustes.NoDisponible
        val vm=vm();vm.alAbrir(true);runCurrent()
        assertTrue(vm.hoja.value.mosaicoVisible);assertTrue(vm.hoja.value.vender.habilitada)
        assertNotNull(vm.ajustesParaConsultar("sucursal-a"));assertNull(vm.ajustesParaConsultar("sucursal-b"))
    }

}
