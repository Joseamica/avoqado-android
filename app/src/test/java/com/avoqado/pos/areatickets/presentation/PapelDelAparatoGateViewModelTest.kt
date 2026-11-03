package com.avoqado.pos.areatickets.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.areatickets.data.AreaOpcion
import com.avoqado.pos.areatickets.data.AreaTicketArea
import com.avoqado.pos.areatickets.data.AreaTicketEnvelope
import com.avoqado.pos.areatickets.data.AreaTicketModuleSettings
import com.avoqado.pos.areatickets.data.AreaTicketRepository
import com.avoqado.pos.areatickets.data.AreaTicketSession
import com.avoqado.pos.areatickets.data.AreaTicketSettingsData
import com.avoqado.pos.areatickets.data.AreaTicketTerminalCapabilities
import com.avoqado.pos.areatickets.data.ScaleIntegrationSettings
import com.avoqado.pos.areatickets.domain.PapelDelAparato
import com.avoqado.pos.core.data.local.PayloadCache
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.ApiService
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.pos.data.ActiveCartState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/** D3: la compuerta de primera vez y la hoja «¿Qué hará este aparato?». Espejo: avoqado-ios `PapelDelAparatoGateTests`. */
class PapelDelAparatoGateViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val api = mockk<ApiService>()
    private var guardado: PayloadCache.Cached? = null
    private val cache = mockk<PayloadCache> {
        coEvery { save(any(), any(), any()) } answers { guardado = PayloadCache.Cached(thirdArg(), System.currentTimeMillis()) }
        coEvery { load(any(), any()) } answers { guardado }
    }
    private val storage = mockk<SecureStorage>(relaxed = true) {
        every { venueId } returns "v1"
        every { areaTicketCheckoutId } returns null
        every { areaTicketCheckoutVenueId } returns null
        every { pendingAreaTicketPrintCode } returns null
        every { valeSinConfirmar(any()) } returns null
    }
    private val repo = AreaTicketRepository(api = api, secureStorage = storage, session = AreaTicketSession(storage), payloadCache = cache)
    private val carrito = ActiveCartState()
    private var permiso = true
    private val roles = mockk<RoleManager> {
        every { hasVenuePermission("area-tickets:configure", any()) } answers { permiso }
    }
    private val red = MutableStateFlow(true)
    private val monitor = mockk<ConnectivityMonitor> {
        every { isConnected } returns red
        every { isServerReachable } returns MutableStateFlow(true)
        every { isFullyConnected } answers { red.value }
    }

    private fun vm() = PapelDelAparatoGateViewModel(repo, storage, carrito, roles, monitor)

    private fun ajustes(
        pendiente: Boolean = true,
        areas: List<AreaOpcion>? = listOf(AreaOpcion("a1", "Cremería"), AreaOpcion("a2", "Panadería")),
        terminal: AreaTicketTerminalCapabilities = AreaTicketTerminalCapabilities(id = "t1", name = "PC", roleChoicePending = pendiente),
    ) = AreaTicketSettingsData(
        venueId = "v1",
        areaTickets = AreaTicketModuleSettings(entitled = true, enabled = true),
        terminal = terminal,
        scaleIntegration = ScaleIntegrationSettings(entitled = false, enabled = false),
        areas = areas,
        areasTotal = areas?.size ?: 0,
    )

    private fun respondeAjustes(a: AreaTicketSettingsData) {
        coEvery { api.getAreaTicketSettings("v1") } returns AreaTicketEnvelope(true, a)
    }

    private fun httpError(status: Int, code: String, message: String) = HttpException(
        Response.error<Any>(
            status,
            """{"success":false,"error":{"code":"$code","message":"$message"}}""".toResponseBody("application/json".toMediaType()),
        ),
    )

    /** Arranque o login sube `sesion`; ON_RESUME, red recuperada y cambio de sucursal sólo `vez`. */
    private fun disparo(vez: Int, sesion: Int = 1) = ConsultaDelPapel(vez = vez, sesion = sesion)

    @Test
    fun `muestra la pregunta cuando roleChoicePending y hay red`() = runTest {
        respondeAjustes(ajustes())
        val vm = vm()

        vm.consultar(disparo(1))

        val hoja = vm.hoja.value!!
        assertTrue(hoja.primeraVez)
        assertEquals("t1", hoja.terminalId)
        assertEquals(listOf("Cremería", "Panadería"), hoja.areas.map { it.name })
        assertEquals(PapelDelAparato.SinElegir, hoja.seleccion)
    }

    @Test
    fun `sin red no muestra nada y la muestra al volver la red`() = runTest {
        respondeAjustes(ajustes())
        val vm = vm()
        red.value = false

        vm.consultar(disparo(1))
        assertNull(vm.hoja.value)

        red.value = true
        vm.consultar(disparo(2))
        assertNotNull(vm.hoja.value)
    }

    @Test
    fun `tras DEVICE_NOT_REGISTERED la muestra en el siguiente disparador`() = runTest {
        coEvery { api.getAreaTicketSettings("v1") } throws
            httpError(409, "DEVICE_NOT_REGISTERED", "Este dispositivo todavía no está registrado en el local.")
        val vm = vm()

        vm.consultar(disparo(1))
        assertNull(vm.hoja.value)
        assertFalse(vm.avisoSinPermiso.value)

        respondeAjustes(ajustes())
        vm.consultar(disparo(2))
        assertNotNull(vm.hoja.value)
    }

    @Test
    fun `Ahora no la calla en ON_RESUME pero vuelve tras un login`() = runTest {
        respondeAjustes(ajustes())
        val vm = vm()
        vm.consultar(disparo(1, sesion = 1))
        vm.cerrar()
        assertNull(vm.hoja.value)

        vm.consultar(disparo(2, sesion = 1)) // ON_RESUME / red recuperada
        assertNull(vm.hoja.value)

        vm.consultar(disparo(3, sesion = 2)) // login
        assertNotNull(vm.hoja.value)
    }

    @Test
    fun `P1 sin permiso muestra el aviso y no la hoja`() = runTest {
        respondeAjustes(ajustes())
        permiso = false
        val vm = vm()

        vm.consultar(disparo(1))
        assertTrue(vm.avisoSinPermiso.value)
        assertNull(vm.hoja.value)

        vm.entendido()
        vm.consultar(disparo(2))
        assertFalse("la misma regla de silencio que «Ahora no»", vm.avisoSinPermiso.value)
    }

    @Test
    fun `P1 servidor viejo (areas null) ni compuerta ni recuadro`() = runTest {
        respondeAjustes(ajustes(areas = null))
        val vm = vm()

        vm.consultar(disparo(1))

        assertNull(vm.hoja.value)
        assertNull(valorDelRecuadro(vm.ajustes.value!!))
    }

    @Test
    fun `el recuadro dice el papel actual cuando hay vales`() = runTest {
        val cremeria = AreaTicketArea("a1", "Cremería", "HOLD_UNTIL_PAID")
        respondeAjustes(
            ajustes(
                pendiente = false,
                terminal = AreaTicketTerminalCapabilities(id = "t1", name = "PC", fulfillmentArea = cremeria, canIssueAreaTickets = true),
            ),
        )
        val vm = vm()

        vm.consultar(disparo(1))

        assertNull(vm.hoja.value)
        assertEquals("Emite · Cremería", valorDelRecuadro(vm.ajustes.value!!))
    }

    @Test
    fun `después de guardar no vuelve a preguntar`() = runTest {
        respondeAjustes(ajustes())
        val cremeria = AreaTicketArea("a1", "Cremería", "HOLD_UNTIL_PAID")
        val yaElegido = ajustes(
            pendiente = false,
            terminal = AreaTicketTerminalCapabilities(id = "t1", name = "PC", fulfillmentArea = cremeria, canIssueAreaTickets = true),
        )
        coEvery { api.setAreaTicketRole("v1", "t1", any()) } returns AreaTicketEnvelope(true, yaElegido)
        val vm = vm()
        vm.consultar(disparo(1))

        vm.elegir(PapelDelAparato.EmiteVales("a1", "Cremería"))
        vm.guardar()

        assertNull(vm.hoja.value)
        assertEquals(PapelDelAparato.EmiteVales("a1", "Cremería"), vm.guardado.value)
        // El siguiente disparador no alcanza al servidor (503): la caché ya tiene lo que se guardó.
        coEvery { api.getAreaTicketSettings("v1") } throws httpError(503, "UNAVAILABLE", "Servidor ocupado")
        vm.consultar(disparo(2, sesion = 2))
        assertNull(vm.hoja.value)
    }

    @Test
    fun `un rechazo del servidor se queda en la hoja con su mensaje`() = runTest {
        respondeAjustes(ajustes())
        coEvery { api.setAreaTicketRole(any(), any(), any()) } throws httpError(400, "BAD_REQUEST", "El área no es válida.")
        val vm = vm()
        vm.consultar(disparo(1))

        vm.elegir(PapelDelAparato.EmiteVales("a1", "Cremería"))
        vm.guardar()

        val hoja = vm.hoja.value!!
        assertEquals("El área no es válida.", hoja.error)
        assertFalse(hoja.guardando)
        assertNull(vm.guardado.value)
    }

    @Test
    fun `P1 a media venta la compuerta no interrumpe y pregunta en el siguiente momento`() = runTest {
        respondeAjustes(ajustes())
        val vm = vm()
        carrito.update(itemCount = 2, totalDisplay = "$40.00")

        vm.consultar(disparo(1))
        assertNull(vm.hoja.value)

        carrito.clear()
        vm.consultar(disparo(2)) // no se calló: la venta no es un «Ahora no»
        assertNotNull(vm.hoja.value)
    }

    @Test
    fun `P1 con productos en el carrito no guarda y lo dice`() = runTest {
        respondeAjustes(ajustes())
        val vm = vm()
        vm.consultar(disparo(1))
        carrito.update(itemCount = 1, totalDisplay = "$10.00")

        vm.elegir(PapelDelAparato.Cobra)
        vm.guardar()

        coVerify(exactly = 0) { api.setAreaTicketRole(any(), any(), any()) }
        assertEquals("Termina la venta antes de cambiar", vm.hoja.value!!.motivo(hayRed = true))
    }

    @Test
    fun `desde Más se abre la misma hoja con el papel de hoy`() = runTest {
        respondeAjustes(ajustes(pendiente = false, terminal = AreaTicketTerminalCapabilities(id = "t1", name = "PC", canCheckoutAreaTickets = true)))
        val vm = vm()
        vm.consultar(disparo(1))

        vm.abrirDesdeMas()

        val hoja = vm.hoja.value!!
        assertFalse(hoja.primeraVez)
        assertEquals(PapelDelAparato.Cobra, hoja.seleccion)
    }

    @Test
    fun `cerrar la hoja de Más no calla la pregunta de primera vez`() = runTest {
        // Arrancó sin red (no preguntó), abrió Más con los ajustes guardados y cerró la hoja; al volver la red pregunta.
        respondeAjustes(ajustes())
        red.value = false
        val vm = vm()
        vm.consultar(disparo(1))
        vm.abrirDesdeMas()
        vm.cerrar()

        red.value = true
        vm.consultar(disparo(2))
        assertTrue(vm.hoja.value!!.primeraVez)
    }

    @Test
    fun `el aviso al guardar dice el texto exacto y en Windows esta computadora`() {
        assertEquals("¡Listo! Este aparato ahora cobra", textoAlGuardar(PapelDelAparato.Cobra, esEscritorio = false))
        assertEquals(
            "¡Listo! Esta computadora ahora emite vales de Cremería",
            textoAlGuardar(PapelDelAparato.EmiteVales("a1", "Cremería"), esEscritorio = true),
        )
        assertEquals("¿Qué hará este aparato?", tituloDeLaHoja(esEscritorio = false))
        assertEquals("¿Qué hará esta computadora?", tituloDeLaHoja(esEscritorio = true))
    }
}
