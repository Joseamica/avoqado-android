package com.avoqado.pos.cashdrawer.presentation

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.cashdrawer.VENUE_ID
import com.avoqado.pos.cashdrawer.data.CashDrawerRepository
import com.avoqado.pos.cashdrawer.data.ComprobanteDeRetiro
import com.avoqado.pos.cashdrawer.data.ComprobantesDeRetiroPorImprimir
import com.avoqado.pos.cashdrawer.data.ResultadoDelRetiro
import com.avoqado.pos.cashdrawer.esperarSuTrabajo
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.network.OverrideResult
import com.avoqado.pos.core.data.network.PermissionOverrideRepository
import com.avoqado.pos.core.domain.RoleManager
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.SavedPrinter
import com.avoqado.pos.tpvsettings.data.TerminalNavigationSettings
import com.avoqado.pos.tpvsettings.data.TpvSettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Collections

/**
 * Task 11 del retiro con gerente: lo que ven el cajero y el gerente, y el papel. El repositorio, el teclado, la impresora y el
 * almacén de comprobantes son falsos (salvo en la recuperación, que usa el almacén real); el ViewModel es el de verdad.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CashDrawerRetiroFlujoTest {

    @get:Rule val main = MainDispatcherRule(UnconfinedTestDispatcher())
    @get:Rule val temporal = TemporaryFolder()

    // MARK: - Andamio

    private var nombre = "Ana"
    private var rol = "CASHIER"
    private var conPermiso = false
    private var pinActivo = true
    private var hayRed = true

    private val storage = mockk<SecureStorage>(relaxed = true).also {
        every { it.venueId } returns VENUE_ID
        every { it.userFirstName } answers { nombre }
        every { it.userLastName } returns "Ruiz"
        every { it.userRole } answers { rol }
        every { it.listaDePermisosVigente } returns true
    }
    private val roles = mockk<RoleManager>(relaxed = true).also {
        every { it.hasVenuePermission(any(), any()) } returns true
        every { it.hasVenuePermission(PERMISO_RETIRAR) } answers { conPermiso }
        every { it.role } answers { rol }
    }
    private val ajustes = mockk<TpvSettingsRepository>(relaxed = true).also {
        every { it.managerPinOverrideEnabled } answers { MutableStateFlow(pinActivo) }
        every { it.terminalNavigation } returns MutableStateFlow(TerminalNavigationSettings(terminalName = "Caja 1"))
    }
    private val red = mockk<ConnectivityMonitor>(relaxed = true).also { every { it.isOnline() } answers { hayRed } }
    private val override = mockk<PermissionOverrideRepository>()
    private val store = mockk<ComprobantesDeRetiroPorImprimir>(relaxed = true).also {
        coEvery { it.pendientes(any()) } returns emptyList()
    }

    private val papeles: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var impresora: SavedPrinter? = SavedPrinter(name = "Recibos", connectionType = "wifi", address = "10.0.0.9", port = 9100)
    private var impresoraFalla = false
    private val printer = mockk<PrinterService>(relaxed = true).also {
        every { it.getDefaultPrinter(PrinterRole.RECEIPT) } answers { impresora }
        coEvery { it.sendPrintData(any(), any()) } coAnswers {
            if (impresoraFalla) throw IOException("sin papel")
            papeles += String(firstArg<ByteArray>(), Charsets.ISO_8859_1)
        }
    }

    private var resultado: ResultadoDelRetiro = ResultadoDelRetiro.Hecho(mockk(relaxed = true), "Daniel", confirmado = true)
    private val comprobanteEnviado = slot<ComprobanteDeRetiro>()
    private val repo = mockk<CashDrawerRepository>(relaxed = true).also {
        every { it.venueName } returns "Testarudo Cafe"
        coEvery { it.addPayOut(any(), any(), any(), any(), capture(comprobanteEnviado)) } coAnswers { resultado }
    }

    private val pantallas = mutableListOf<CashDrawerViewModel>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @After fun fin() {
        pantallas.forEach { it.esperarSuTrabajo() }
        scope.cancel()
    }

    private fun vm(comprobantes: ComprobantesDeRetiroPorImprimir = store) =
        CashDrawerViewModel(repo, printer, roles, ajustes, red, override, comprobantes, storage).also { pantallas += it }

    private fun gerente() { conPermiso = true; rol = "MANAGER"; nombre = "Daniel" }

    private fun hoja(vm: CashDrawerViewModel): RetiroUi.Hoja =
        vm.retiro.value as? RetiroUi.Hoja ?: throw AssertionError("no se abrió la hoja: ${vm.retiro.value}")

    private fun esperar(que: String, cond: () -> Boolean) {
        val limite = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < limite) {
            if (cond()) return
            Thread.sleep(10)
        }
        fail(que)
    }

    private fun comprobante(venueId: String, localId: String, hizo: String, concepto: String) = ComprobanteDeRetiro(
        venueId = venueId, localId = localId, venueName = "Testarudo Cafe", nombreDelAparato = "Caja 2",
        fechaTexto = "09/11/2026", horaTexto = "18:40", concepto = concepto, amountCents = 120_000,
        hizo = hizo, rolDeQuienHizo = "CASHIER", autorizadoPor = "Daniel",
    )

    // MARK: - El camino directo (quien tiene el permiso)

    @Test
    fun `el gerente retira sin PIN, con o sin red, y el papel dice que lo autorizó él mismo`() {
        gerente()
        for (conRed in listOf(true, false)) {
            hayRed = conRed
            papeles.clear()
            resultado = ResultadoDelRetiro.Hecho(mockk(relaxed = true), null, confirmado = conRed)
            val vm = vm()

            vm.tocarRetiro()
            val h = hoja(vm)
            assertFalse("el gerente no necesita PIN", h.conPin)
            vm.confirmarRetiro(500_000, "Bajada")

            coVerify { repo.addPayOut(500_000, "Bajada", h.localId, null, any()) }
            val c = comprobanteEnviado.captured
            assertEquals("Daniel Ruiz", c.hizo)
            assertEquals("lo autorizó él mismo", "Daniel Ruiz", c.autorizadoPor)
            assertEquals(h.localId, c.localId)
            assertEquals(VENUE_ID, c.venueId)
            assertEquals("Caja 1", c.nombreDelAparato)
            assertEquals(RetiroUi.Cerrado, vm.retiro.value)
            assertEquals(1, papeles.size)
            assertTrue(papeles[0].contains("Daniel Ruiz"))
            assertEquals(
                if (conRed) null else "Retiro registrado. Se subirá al servidor cuando vuelva la conexión.",
                vm.avisoDelRetiro.value,
            )
        }
        coVerify(exactly = 0) { override.requestToken(any(), any(), any(), any()) }
    }

    @Test
    fun `el cajero sin red ve el aviso y no se abre nada`() {
        hayRed = false
        val vm = vm()

        vm.tocarRetiro()

        assertEquals(
            RetiroUi.Aviso("Sin conexión. Para retirar necesitas el PIN de un gerente, y eso requiere internet. El efectivo se queda en la caja."),
            vm.retiro.value,
        )
        vm.cerrarRetiro()
        assertEquals(RetiroUi.Cerrado, vm.retiro.value)
        coVerify(exactly = 0) { repo.addPayOut(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `con el PIN apagado ve que sólo un gerente puede`() {
        pinActivo = false
        val vm = vm()

        vm.tocarRetiro()

        assertEquals(
            RetiroUi.Aviso("Sólo un gerente puede retirar efectivo. Pídele que lo haga con su usuario, o que el dueño active el PIN de gerente en el panel."),
            vm.retiro.value,
        )
    }

    // MARK: - El camino con PIN (el cajero)

    @Test
    fun `el cajero confirma, el gerente teclea su PIN viendo el importe, se guarda con la autorización atada e imprime con su nombre`() {
        coEvery { override.requestToken(any(), any(), any(), any()) } returns OverrideResult.Granted("tok-1", "Daniel")
        val vm = vm()

        vm.tocarRetiro()
        val h = hoja(vm)
        assertTrue(h.conPin)
        vm.confirmarRetiro(500_000, "Bajada")

        val pin = vm.retiro.value as RetiroUi.PidiendoPin
        assertEquals("Retiro de $5,000.00 · Bajada", pin.etiqueta)
        coVerify(exactly = 0) { repo.addPayOut(any(), any(), any(), any(), any()) }

        val r = runBlocking { vm.teclearPinDelRetiro("1234") }

        assertTrue(r is OverrideResult.Granted)
        coVerify { override.requestToken(VENUE_ID, "1234", "cash-drawer:pay-out", "pay-out:${h.localId}:500000") }
        coVerify { repo.addPayOut(500_000, "Bajada", h.localId, "tok-1", any()) }
        assertEquals("Ana Ruiz", comprobanteEnviado.captured.hizo)
        assertEquals("Daniel", comprobanteEnviado.captured.autorizadoPor)
        assertEquals(RetiroUi.Cerrado, vm.retiro.value)
        assertEquals(1, papeles.size)
        assertTrue(papeles[0].contains("Daniel"))
        assertNull(vm.avisoDelRetiro.value)
    }

    @Test
    fun `PIN malo, el teclado lo dice y no se manda nada`() {
        coEvery { override.requestToken(any(), any(), any(), any()) } returns OverrideResult.WrongPin
        val vm = vm()
        vm.tocarRetiro()
        vm.confirmarRetiro(500_000, "Bajada")
        val pin = vm.retiro.value

        val r = runBlocking { vm.teclearPinDelRetiro("0000") }

        assertEquals(OverrideResult.WrongPin, r)
        assertEquals("el teclado sigue abierto", pin, vm.retiro.value)
        coVerify(exactly = 0) { repo.addPayOut(any(), any(), any(), any(), any()) }
        assertTrue(papeles.isEmpty())
    }

    @Test
    fun `el gerente cancela, la hoja sigue con lo escrito`() {
        val vm = vm()
        vm.tocarRetiro()
        val h = hoja(vm)
        vm.confirmarRetiro(500_000, "Bajada")

        vm.cancelarPin()

        assertEquals("vuelve la MISMA hoja (mismo retiro)", h, vm.retiro.value)
        coVerify(exactly = 0) { repo.addPayOut(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { override.requestToken(any(), any(), any(), any()) }
    }

    @Test
    fun `si cancelan mientras viaja el PIN, no se manda nada`() {
        val vm = vm()
        coEvery { override.requestToken(any(), any(), any(), any()) } coAnswers {
            vm.cancelarPin() // el gerente se arrepintió con el PIN en camino
            OverrideResult.Granted("tok-1", "Daniel")
        }
        vm.tocarRetiro()
        vm.confirmarRetiro(500_000, "Bajada")

        runBlocking { vm.teclearPinDelRetiro("1234") }

        coVerify(exactly = 0) { repo.addPayOut(any(), any(), any(), any(), any()) }
        assertTrue(vm.retiro.value is RetiroUi.Hoja)
    }

    @Test
    fun `cambiar el importe pide PIN otra vez con la llave nueva`() {
        coEvery { override.requestToken(any(), any(), any(), any()) } returns OverrideResult.Granted("tok-2", "Daniel")
        val vm = vm()
        vm.tocarRetiro()
        val h = hoja(vm)
        vm.confirmarRetiro(500_000, "Bajada")
        vm.cancelarPin()

        vm.confirmarRetiro(600_000, "Bajada")

        assertEquals("Retiro de $6,000.00 · Bajada", (vm.retiro.value as RetiroUi.PidiendoPin).etiqueta)
        runBlocking { vm.teclearPinDelRetiro("1234") }
        coVerify(exactly = 1) { override.requestToken(any(), any(), any(), any()) }
        coVerify { override.requestToken(VENUE_ID, "1234", PERMISO_RETIRAR, "pay-out:${h.localId}:600000") }
        coVerify { repo.addPayOut(600_000, "Bajada", h.localId, "tok-2", any()) }
    }

    @Test
    fun `rechazado tras el PIN, volver a confirmar pide PIN otra vez`() {
        coEvery { override.requestToken(any(), any(), any(), any()) } returns OverrideResult.Granted("tok-1", "Daniel")
        resultado = ResultadoDelRetiro.Rechazado("La autorización ya no es válida.")
        val vm = vm()
        vm.tocarRetiro()
        val h = hoja(vm)
        vm.confirmarRetiro(500_000, "Bajada")
        runBlocking { vm.teclearPinDelRetiro("1234") }

        vm.confirmarRetiro(500_000, "Bajada")

        assertTrue("con PIN siempre se vuelve a pedir", vm.retiro.value is RetiroUi.PidiendoPin)
        assertEquals(h.localId, (vm.retiro.value as RetiroUi.PidiendoPin).hoja.localId)
    }

    @Test
    fun `doble toque en Confirmar = un teclado, un envío`() {
        coEvery { override.requestToken(any(), any(), any(), any()) } returns OverrideResult.Granted("tok-1", "Daniel")
        val vm = vm()
        vm.tocarRetiro()

        vm.confirmarRetiro(500_000, "Bajada")
        val primero = vm.retiro.value
        vm.confirmarRetiro(500_000, "Bajada")

        assertEquals("un solo teclado", primero, vm.retiro.value)
        runBlocking { vm.teclearPinDelRetiro("1234") }
        runBlocking { vm.teclearPinDelRetiro("1234") }
        coVerify(exactly = 1) { override.requestToken(any(), any(), any(), any()) }
        coVerify(exactly = 1) { repo.addPayOut(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `doble toque en Confirmar del gerente con el envío en camino = un envío`() {
        gerente()
        val enCamino = CompletableDeferred<Unit>()
        coEvery { repo.addPayOut(any(), any(), any(), any(), any()) } coAnswers { enCamino.await(); resultado }
        val vm = vm()
        vm.tocarRetiro()

        vm.confirmarRetiro(500_000, "Bajada")
        assertEquals("ocupada mientras se registra", true, (vm.retiro.value as RetiroUi.Hoja).ocupado)
        vm.confirmarRetiro(500_000, "Bajada")
        vm.cerrarRetiro()
        assertTrue("ocupada no se cierra", vm.retiro.value is RetiroUi.Hoja)
        enCamino.complete(Unit)

        esperar("no terminó el retiro") { vm.retiro.value == RetiroUi.Cerrado }
        coVerify(exactly = 1) { repo.addPayOut(any(), any(), any(), any(), any()) }
        assertEquals(1, papeles.size)
    }

    @Test
    fun `se cae la red después del PIN, queda registrado, se avisa en tono normal e imprime`() {
        coEvery { override.requestToken(any(), any(), any(), any()) } returns OverrideResult.Granted("tok-1", "Daniel")
        resultado = ResultadoDelRetiro.Hecho(mockk(relaxed = true), null, confirmado = false)
        val vm = vm()
        vm.tocarRetiro()
        val h = hoja(vm)
        vm.confirmarRetiro(500_000, "Bajada")

        runBlocking { vm.teclearPinDelRetiro("1234") }

        assertEquals(RetiroUi.Cerrado, vm.retiro.value)
        assertEquals("Retiro registrado. Se subirá al servidor cuando vuelva la conexión.", vm.avisoDelRetiro.value)
        assertEquals(1, papeles.size)
        assertTrue("el nombre del teclado, aunque el servidor no contestó", papeles[0].contains("Daniel"))
        coVerify { store.quitar(VENUE_ID, h.localId) }
        vm.descartarAvisoDelRetiro()
        assertNull(vm.avisoDelRetiro.value)
    }

    @Test
    fun `el servidor lo rechaza al momento, no se imprime y se dice`() {
        gerente()
        resultado = ResultadoDelRetiro.Rechazado("Permission 'cash-drawer:pay-out' required")
        val vm = vm()
        vm.tocarRetiro()
        val h = hoja(vm)

        vm.confirmarRetiro(500_000, "Bajada")

        val despues = vm.retiro.value as RetiroUi.Hoja
        assertEquals(h.localId, despues.localId)
        assertFalse(despues.ocupado)
        val error = despues.error ?: throw AssertionError("sin error en la hoja")
        assertTrue(error, error.startsWith("No se registró el retiro: "))
        assertFalse("el texto técnico del servidor no llega a la pantalla: $error", error.contains("Permission '"))
        assertTrue(papeles.isEmpty())
        coVerify(exactly = 0) { store.quitar(any(), any()) }
    }

    @Test
    fun `el VM pasa el comprobante con los datos de antes de enviar`() {
        coEvery { repo.addPayOut(any(), any(), any(), any(), capture(comprobanteEnviado)) } coAnswers {
            nombre = "Beatriz" // otra persona entra mientras viaja el retiro
            resultado
        }
        gerente()
        nombre = "Ana"
        val vm = vm()
        vm.tocarRetiro()

        vm.confirmarRetiro(500_000, "Bajada")

        assertEquals("Ana Ruiz", comprobanteEnviado.captured.hizo)
        assertEquals(1, papeles.size)
        assertTrue(papeles[0].contains("Ana Ruiz"))
        assertFalse(papeles[0].contains("Beatriz"))
    }

    @Test
    fun `se quita el comprobante después de imprimir, también si la impresora falla`() {
        gerente()
        val vm = vm()
        vm.tocarRetiro()
        val bien = hoja(vm).localId
        vm.confirmarRetiro(500_000, "Bajada")
        coVerifyOrder {
            printer.sendPrintData(any(), any())
            store.quitar(VENUE_ID, bien)
        }
        assertNull(vm.avisoDelRetiro.value)

        impresoraFalla = true
        vm.tocarRetiro()
        val falla = hoja(vm).localId
        vm.confirmarRetiro(500_000, "Bajada")
        coVerify { store.quitar(VENUE_ID, falla) }
        assertEquals("El retiro quedó registrado, pero no se imprimió el comprobante.", vm.avisoDelRetiro.value)
        vm.descartarAvisoDelRetiro()

        impresora = null
        vm.tocarRetiro()
        val sinImpresora = hoja(vm).localId
        vm.confirmarRetiro(500_000, "Bajada")
        coVerify { store.quitar(VENUE_ID, sinImpresora) }
        assertEquals("El retiro quedó registrado, pero no hay impresora de recibos en esta caja.", vm.avisoDelRetiro.value)
    }

    @Test
    fun `al abrir Caja imprime lo que quedó pendiente con SUS datos y la leyenda REIMPRESIÓN, y nunca el de otro venue`() {
        val real = ComprobantesDeRetiroPorImprimir(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { temporal.root.resolve("retiro_comprobantes.preferences_pb") }),
        )
        runBlocking {
            real.anotar(comprobante(VENUE_ID, "loc-viejo", hizo = "Beatriz Luna", concepto = "Bajada vieja"))
            real.anotar(comprobante("venue-otro", "loc-ajeno", hizo = "Otro Negocio", concepto = "Ajeno"))
        }

        val vm = vm(real)
        vm.alEntrar() // la pantalla también entra al abrirse: el papel no puede salir dos veces

        esperar("no salió el papel pendiente") { papeles.isNotEmpty() }
        vm.esperarSuTrabajo()
        assertEquals("un papel, una vez", 1, papeles.size)
        val papel = papeles[0]
        assertTrue(papel.contains("REIMPRESI"))
        assertTrue("con SUS datos, no los de la sesión", papel.contains("Beatriz Luna") && papel.contains("Bajada vieja"))
        assertFalse(papel.contains("Ana Ruiz"))
        runBlocking {
            assertTrue(real.pendientes(VENUE_ID).isEmpty())
            assertEquals("el de otro negocio se queda", 1, real.pendientes("venue-otro").size)
        }
    }
}
