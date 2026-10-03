package com.avoqado.pos.customerdisplay

import com.avoqado.escritorio.ActividadDeEscritorio
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.escritorio.Inyector
import java.awt.Rectangle
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * La pantalla del cliente en un segundo monitor, sin pantalla de verdad: monitores, caja y letreros falsos. El manager sale del
 * inyector REAL (prueba de paso que Guice lo construye con sus dependencias de Android).
 * Sin `runTest`: el vigía de monitores es un bucle infinito y `advanceUntilIdle` giraría para siempre; el reloj se avanza a mano.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomerDisplayManagerDeEscritorioTest {

    private val principal = Monitor("\\\\.\\DISPLAY1", Rectangle(0, 0, 1920, 1080), principal = true)
    private val segundo = Monitor("\\\\.\\DISPLAY2", Rectangle(1920, 0, 1024, 768), principal = false)

    private val reloj = TestCoroutineScheduler()
    private lateinit var manager: CustomerDisplayManager
    private lateinit var estado: CustomerDisplayState
    private lateinit var preferenciasDelCliente: CustomerDisplayPrefs
    private lateinit var modo: DisplayModePrefs
    private lateinit var almacen: SecureStorage
    private var monitores = listOf(principal)
    private val caja = CajaFalsa(Rectangle(0, 0, 1920, 1040))
    private val fabrica = FabricaFalsa()

    @BeforeTest fun armar() {
        Dispatchers.setMain(UnconfinedTestDispatcher(reloj))
        val inyector = Inyector.crear(ActividadDeEscritorio(Files.createTempDirectory("Avoqado POS cliente")))
        manager = inyector.getInstance(CustomerDisplayManager::class.java)
        estado = inyector.getInstance(CustomerDisplayState::class.java)
        preferenciasDelCliente = inyector.getInstance(CustomerDisplayPrefs::class.java)
        modo = inyector.getInstance(DisplayModePrefs::class.java)
        almacen = inyector.getInstance(SecureStorage::class.java)
        manager.fuenteDeMonitores = { monitores }
    }

    @AfterTest fun apagar() {
        manager.detener()
        Dispatchers.resetMain()
    }

    private fun arrancar() = manager.arrancar(caja, fabrica)
    private fun pasan(ms: Long) = reloj.advanceTimeBy(ms).also { reloj.runCurrent() }

    @Test fun `con un solo monitor no hay letrero y se anuncia sin pantalla del cliente`() {
        arrancar()
        assertTrue(fabrica.abiertos.isEmpty())
        assertFalse(estado.isPresenting.value)
        assertFalse(manager.isActive)
        assertEquals(DisplayCapabilitySnapshot(present = false, invertible = false), estado.capabilities.value)
    }

    @Test fun `enchufar un segundo monitor monta el letrero ahi y el cliente puede tocar`() {
        preferenciasDelCliente.setCustomerCaptureEnabled(true)
        arrancar()
        monitores = listOf(principal, segundo)
        pasan(2_100)
        val letrero = fabrica.abiertos.single()
        assertEquals(segundo.limites, letrero.limites)
        assertTrue(estado.isPresenting.value)
        assertTrue(manager.isActive)
        assertTrue(estado.customerCapturesInput.value, "monitor físico ⇒ el cliente toca su pantalla (como Android)")
        assertEquals(DisplayCapabilitySnapshot(present = true, invertible = true), estado.capabilities.value)
        assertTrue(caja.movimientos.isEmpty(), "la caja ya estaba en el principal: no se mueve")
    }

    @Test fun `desenchufarlo a media venta quita el letrero y el cobro deja de esperar al cliente`() {
        preferenciasDelCliente.setCustomerCaptureEnabled(true)
        monitores = listOf(principal, segundo)
        arrancar()
        assertTrue(estado.customerCapturesInput.value)
        monitores = listOf(principal)
        pasan(2_100)
        assertTrue(fabrica.abiertos.single().cerrado)
        assertFalse(estado.isPresenting.value)
        assertFalse(estado.customerCapturesInput.value)
        assertFalse(manager.isActive)
    }

    @Test fun `invertir mueve la caja al segundo monitor y el letrero al principal, y de regreso`() {
        monitores = listOf(principal, segundo)
        arrancar()
        modo.setInverted(true)
        assertEquals(segundo.limites, caja.movimientos.last())
        assertEquals(principal.limites, fabrica.abiertos.last().limites)
        assertTrue(fabrica.abiertos.first().cerrado)
        assertTrue(estado.isPresenting.value)
        modo.setInverted(false)
        assertEquals(principal.limites, caja.movimientos.last())
        assertEquals(segundo.limites, fabrica.abiertos.last().limites)
        assertTrue(estado.isPresenting.value)
    }

    @Test fun `si la caja no se deja mover, el letrero nunca se monta encima de ella`() {
        monitores = listOf(principal, segundo)
        caja.r = Rectangle(2000, 0, 900, 700)   // el cajero la arrastró al monitor del cliente
        caja.inamovible = true
        arrancar()
        assertTrue(fabrica.abiertos.isEmpty())
        assertFalse(estado.isPresenting.value)
    }

    @Test fun `si la ventana del cliente no se puede crear, no truena y no dice que hay letrero`() {
        monitores = listOf(principal, segundo)
        fabrica.falla = true
        arrancar()
        assertFalse(estado.isPresenting.value)
        assertFalse(manager.isActive)
    }

    @Test fun `si el letrero truena se quita solo y la caja sigue`() {
        monitores = listOf(principal, segundo)
        arrancar()
        val letrero = fabrica.abiertos.single()
        letrero.alTronar()
        assertTrue(letrero.cerrado)
        assertFalse(estado.isPresenting.value)
        assertTrue(caja.movimientos.isEmpty())
    }

    @Test fun `sin cambios de monitores el vigia repone el letrero si Windows lo escondio`() {
        monitores = listOf(principal, segundo)
        arrancar()
        pasan(4_100)
        assertEquals(1, fabrica.abiertos.size, "no se reabre: sólo se asegura que siga visible")
        assertEquals(2, fabrica.abiertos.single().reposiciones)
    }

    @Test fun `si el cajero arrastra su ventana al monitor del cliente se quita el letrero, y vuelve al regresarla`() {
        preferenciasDelCliente.setCustomerCaptureEnabled(true)
        monitores = listOf(principal, segundo)
        arrancar()
        caja.r = Rectangle(2000, 0, 900, 700)
        pasan(2_100)
        assertTrue(fabrica.abiertos.single().cerrado)
        assertFalse(estado.isPresenting.value)
        assertFalse(estado.customerCapturesInput.value, "con la caja encima el cliente no ve nada: el cobro no lo espera")
        assertTrue(caja.movimientos.isEmpty(), "no se le regresa la ventana al cajero")
        caja.r = Rectangle(0, 0, 1920, 1040)
        pasan(2_100)
        assertEquals(segundo.limites, fabrica.abiertos.last().limites)
        assertFalse(fabrica.abiertos.last().cerrado)
        assertTrue(estado.isPresenting.value)
    }

    @Test fun `al iniciar sesion o cambiar de sucursal la marca del letrero se pone al dia sola`() {
        monitores = listOf(principal, segundo)
        arrancar()
        almacen.venueName = "Testarudo Cafe"
        pasan(2_100)
        assertEquals("Testarudo Cafe", estado.venueName.value)
    }

    @Test fun `al cerrar la app se libera la ventana reutilizable`() {
        monitores = listOf(principal, segundo)
        arrancar()
        manager.detener()
        assertTrue(fabrica.liberada)
        assertFalse(estado.isPresenting.value)
    }

    @Test fun `invertir desde el servidor con un solo monitor se rechaza como Android`() = runBlocking {
        arrancar()
        assertEquals(
            PhysicalDisplayModeResult.Rejected(DisplayModeAckResultCode.DISPLAY_NOT_INVERTIBLE, confirmedInverted = false),
            manager.applyAndConfirm(true),
        )
        assertEquals(PhysicalDisplayModeResult.Confirmed(false), manager.applyAndConfirm(false))
    }

    @Test fun `invertir desde el servidor con dos monitores se confirma cuando la caja ya esta en su lugar`() = runBlocking {
        monitores = listOf(principal, segundo)
        arrancar()
        assertEquals(false, manager.observeConfirmedMode())
        modo.setInverted(true)   // el coordinador guarda la preferencia ANTES de pedir aplicarla
        assertEquals(PhysicalDisplayModeResult.Confirmed(true), manager.applyAndConfirm(true))
        assertEquals(true, manager.observeConfirmedMode())
    }

    @Test fun `antes de arrancar no hay caja que observar`() = runBlocking {
        assertNull(manager.observeConfirmedMode())
        assertEquals(PhysicalDisplayModeResult.Pending, manager.applyAndConfirm(true))
    }

    private class CajaFalsa(var r: Rectangle?) : CajaDeEscritorio {
        val movimientos = mutableListOf<Rectangle>()
        var inamovible = false
        override fun limites(): Rectangle? = r?.let(::Rectangle)
        override fun moverA(monitor: Rectangle) {
            movimientos += Rectangle(monitor)
            if (!inamovible) r = Rectangle(monitor.x, monitor.y, monitor.width, monitor.height - 40)
        }
    }

    private class LetreroFalso(val limites: Rectangle, val alTronar: () -> Unit) : LetreroDelCliente {
        var cerrado = false
        var reposiciones = 0
        override fun cerrar() { cerrado = true }
        override fun asegurarVisible() { reposiciones++ }
    }

    private class FabricaFalsa : FabricaDeLetreros {
        val abiertos = mutableListOf<LetreroFalso>()
        var falla = false
        var liberada = false
        override fun liberar() { liberada = true }
        override fun abrir(limites: Rectangle, alTronar: () -> Unit): LetreroDelCliente {
            if (falla) error("sin aceleración gráfica")
            return LetreroFalso(Rectangle(limites), alTronar).also { abiertos += it }
        }
    }
}
