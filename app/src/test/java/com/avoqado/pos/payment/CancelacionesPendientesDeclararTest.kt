package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.payment.data.CancelacionDeCobroCoordinator
import com.avoqado.pos.payment.data.IntencionDeCancelarCobro
import com.avoqado.pos.payment.data.ResultadoDeDeclaracion
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.domain.CancelacionDeCobro
import com.avoqado.pos.payment.presentation.CancelacionesPendientesViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** D19 (4-nov): «No se cobró» desde «Por revisar», con la terminal ya suelta. */
class CancelacionesPendientesDeclararTest {

    @get:Rule val main = MainDispatcherRule()

    private val coordinador = mockk<CancelacionDeCobroCoordinator>(relaxed = true)
    private val servicio = mockk<TerminalPaymentService>()
    private val intencion = IntencionDeCancelarCobro(id = "req-1", requestId = "req-1", venueId = "venue-1", creadaEn = 1, terminalLibre = true)

    private fun vm() = CancelacionesPendientesViewModel(coordinador, servicio)

    @Test
    fun `D19 liberada solo vuelve a consultar - el coordinador cancela la orden, aqui no se borra nada`() = runTest {
        coEvery { servicio.declararNoCobrado("req-1", "venue-1", null) } returns ResultadoDeDeclaracion.Liberada
        val vm = vm()

        vm.declarar(intencion)

        verify(exactly = 1) { coordinador.procesarTrasDeclarar("req-1") }
        assertTrue(vm.avisos.value.isEmpty())
        assertTrue(vm.declarando.value.isEmpty())
    }

    @Test
    fun `D19 un rechazo deja el mensaje del servidor y no consulta`() = runTest {
        coEvery { servicio.declararNoCobrado(any(), any(), any()) } returns
            ResultadoDeDeclaracion.Rechazada("Este cobro sí tiene señales de haber pasado.", "POSITIVE_EVIDENCE_EXISTS")
        val vm = vm()

        vm.declarar(intencion)

        assertEquals("Este cobro sí tiene señales de haber pasado.", vm.avisos.value["req-1"])
        verify(exactly = 0) { coordinador.procesarTrasDeclarar(any()) }
    }

    @Test
    fun `D19 sin red lo dice con el texto de siempre`() = runTest {
        coEvery { servicio.declararNoCobrado(any(), any(), any()) } returns ResultadoDeDeclaracion.SinConexion
        val vm = vm()

        vm.declarar(intencion)

        assertEquals(CancelacionDeCobro.DECLARACION_SIN_RED, vm.avisos.value["req-1"])
    }

    @Test
    fun `D19 un doble toque no manda dos declaraciones`() = runTest {
        val respuesta = CompletableDeferred<ResultadoDeDeclaracion>()
        coEvery { servicio.declararNoCobrado(any(), any(), any()) } coAnswers { respuesta.await() }
        val vm = vm()

        vm.declarar(intencion)
        vm.declarar(intencion)
        respuesta.complete(ResultadoDeDeclaracion.Liberada)

        coVerify(exactly = 1) { servicio.declararNoCobrado(any(), any(), any()) }
    }

    @Test
    fun `D19 sin solicitud no hay nada que declarar`() = runTest {
        vm().declarar(intencion.copy(requestId = null))

        coVerify(exactly = 0) { servicio.declararNoCobrado(any(), any(), any()) }
    }
    @Test
    fun `D19 el mesero sin permiso recibe el teclado del gerente y con el PIN se declara`() = runTest {
        coEvery { servicio.declararNoCobrado("req-1", "venue-1", null) } returns
            ResultadoDeDeclaracion.Rechazada("Pide a un gerente que ingrese su PIN.", CancelacionDeCobro.CODIGO_PIDE_PIN_DE_GERENTE)
        coEvery { servicio.declararNoCobrado("req-1", "venue-1", "4321") } returns ResultadoDeDeclaracion.Liberada
        val vm = vm()

        vm.declarar(intencion)
        assertTrue(vm.pidiendoPin.value != null)
        assertTrue(vm.avisos.value.isEmpty())

        vm.enviarPin("4321")

        assertEquals(null, vm.pidiendoPin.value)
        verify(exactly = 1) { coordinador.procesarTrasDeclarar("req-1") }
    }

    @Test
    fun `D19 un PIN equivocado deja el teclado abierto y cerrarlo muestra el motivo`() = runTest {
        coEvery { servicio.declararNoCobrado(any(), any(), any()) } returns
            ResultadoDeDeclaracion.Rechazada("Pide a un gerente que ingrese su PIN.", CancelacionDeCobro.CODIGO_PIDE_PIN_DE_GERENTE)
        val vm = vm()
        vm.declarar(intencion)

        assertEquals(com.avoqado.pos.core.data.network.OverrideResult.Insufficient, vm.enviarPin("0000"))
        assertTrue(vm.pidiendoPin.value != null)

        vm.cerrarPin()
        assertEquals(null, vm.pidiendoPin.value)
        assertEquals("Pide a un gerente que ingrese su PIN.", vm.avisos.value["req-1"])
        verify(exactly = 0) { coordinador.procesarTrasDeclarar(any()) }
    }
    @Test
    fun `D19 cerrar el teclado con el PIN viajando no pierde la respuesta ni deja mandar otro`() = runTest {
        val respuesta = CompletableDeferred<ResultadoDeDeclaracion>()
        coEvery { servicio.declararNoCobrado("req-1", "venue-1", null) } returns
            ResultadoDeDeclaracion.Rechazada("Pide a un gerente que ingrese su PIN.", CancelacionDeCobro.CODIGO_PIDE_PIN_DE_GERENTE)
        coEvery { servicio.declararNoCobrado("req-1", "venue-1", "4321") } coAnswers { respuesta.await() }
        val vm = vm()
        vm.declarar(intencion)

        val teclado = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler) + kotlinx.coroutines.Job())
        teclado.launch { vm.enviarPin("4321") }
        vm.cerrarPin()                                         // con el PIN viajando, no hace nada
        teclado.launch { vm.enviarPin("4321") }                // un segundo «Autorizar» espera al primero
        teclado.cancel()                                       // el teclado se va (su scope muere)
        respuesta.complete(ResultadoDeDeclaracion.Liberada)

        verify(exactly = 1) { coordinador.procesarTrasDeclarar("req-1") }
        coVerify(exactly = 1) { servicio.declararNoCobrado("req-1", "venue-1", "4321") }
        assertEquals(null, vm.pidiendoPin.value)
    }
}
