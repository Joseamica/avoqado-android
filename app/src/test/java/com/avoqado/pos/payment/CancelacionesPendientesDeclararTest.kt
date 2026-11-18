package com.avoqado.pos.payment

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.payment.data.CancelacionDeCobroCoordinator
import com.avoqado.pos.payment.data.ContextoDeCobro
import com.avoqado.pos.payment.data.IntencionDeCancelarCobro
import com.avoqado.pos.payment.data.ResultadoDeDeclaracion
import com.avoqado.pos.payment.data.TerminalPaymentService
import com.avoqado.pos.payment.domain.CancelacionDeCobro
import com.avoqado.pos.payment.presentation.CancelacionesPendientesViewModel
import com.avoqado.pos.payment.presentation.CobroPorConfirmar
import com.avoqado.pos.payment.presentation.PasoDelGerente
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 🔴 F1 mínimo, tarea 6 (founder, 17-nov: «el sistema solo; lo raro lo resuelve el gerente con su PIN»). La revisión del gerente en
 * «Operaciones por revisar»: la fila EXACTA → «Gerente: escribe tu PIN» → «Firma del gerente» → «Firmar». Reescribe las pruebas D19
 * del 4-nov, en las que el cajero declaraba sin PIN y el PIN sólo salía si el servidor contestaba 403.
 */
class CancelacionesPendientesDeclararTest {

    @get:Rule val main = MainDispatcherRule()

    private val cancelaciones = MutableStateFlow<List<IntencionDeCancelarCobro>>(emptyList())
    private val coordinador = mockk<CancelacionDeCobroCoordinator>(relaxed = true).also {
        every { it.pendientes } returns cancelaciones
    }
    private val servicio = mockk<TerminalPaymentService>(relaxed = true).also {
        every { it.intentoEnVuelo() } returns null
        every { it.pendientesDeOtrasVentas(null) } returns emptyList()
    }
    private val intencion = IntencionDeCancelarCobro(id = "req-1", requestId = "req-1", venueId = "venue-1", montoCents = 35_000, creadaEn = 1, terminalLibre = true)
    private val a = CobroPorConfirmar(requestId = "req-a", venueId = "venue-1", montoCents = 35_000, desdeMillis = 1)

    private fun vm() = CancelacionesPendientesViewModel(coordinador, servicio)

    private fun pendiente(id: String, centavos: Int?, cobrado: Boolean = false, propina: Int? = 0) = ContextoDeCobro(
        requestId = id, venueId = "venue-1", terminalId = "t1", orderId = "orden-$id", amountCents = centavos, tipCents = propina,
        desdeMillis = 1, cobrado = cobrado,
    )

    @Test
    fun `F1 el PIN del gerente va ANTES de la decision y no se manda nada hasta Firmar`() = runTest {
        val vm = vm()

        vm.revisar(a)
        assertTrue("lo primero es el PIN", vm.paso.value is PasoDelGerente.PedirPin)
        vm.firmar() // sin identificarse, «Firmar» no existe
        vm.identificar("4321")
        val firma = vm.paso.value as PasoDelGerente.Firmar
        assertEquals(a, firma.cobro)
        assertFalse("el PIN no sale en el texto del paso", firma.toString().contains("4321"))

        coVerify(exactly = 0) { servicio.declararNoCobrado(any(), any(), any()) }
    }

    @Test
    fun `F1 Firmar declara ESA fila con ESE PIN y nunca sin PIN`() = runTest {
        coEvery { servicio.declararNoCobrado("req-a", "venue-1", "4321") } returns ResultadoDeDeclaracion.Liberada
        val vm = vm()

        vm.revisar(a); vm.identificar("4321"); vm.firmar()

        assertEquals(PasoDelGerente.Cerrado, vm.paso.value)
        coVerify(exactly = 1) { servicio.declararNoCobrado("req-a", "venue-1", "4321") }
        coVerify(exactly = 0) { servicio.declararNoCobrado(any(), any(), null) }
    }

    @Test
    fun `F1 la cancelacion liberada solo vuelve a consultar - el coordinador cancela la orden, aqui no se borra nada`() = runTest {
        coEvery { servicio.declararNoCobrado("req-1", "venue-1", "4321") } returns ResultadoDeDeclaracion.Liberada
        val vm = vm()

        vm.revisar(vm.filaDe(intencion)!!); vm.identificar("4321"); vm.firmar()

        verify(exactly = 1) { coordinador.procesarTrasDeclarar("req-1") }
        assertTrue(vm.avisos.value.isEmpty())
    }

    @Test
    fun `F1 una intencion solo de la orden no tiene fila que revisar`() {
        assertEquals(null, vm().filaDe(intencion.copy(requestId = null)))
    }

    @Test
    fun `F1 un PIN que no puede firmar vuelve al PIN de la MISMA fila, sin aviso`() = runTest {
        coEvery { servicio.declararNoCobrado("req-a", any(), "0000") } returns
            ResultadoDeDeclaracion.Rechazada("Pide a un gerente que ingrese su PIN.", CancelacionDeCobro.CODIGO_PIDE_PIN_DE_GERENTE)
        val vm = vm()
        vm.revisar(a)
        val primerTeclado = (vm.paso.value as PasoDelGerente.PedirPin).id

        vm.identificar("0000"); vm.firmar()

        val otraVez = vm.paso.value as PasoDelGerente.PedirPin
        assertEquals(a, otraVez.cobro)
        assertTrue(otraVez.pinRechazado)
        assertTrue("teclado nuevo, vacío", otraVez.id != primerTeclado)
        assertTrue(vm.avisos.value.isEmpty())
        verify(exactly = 0) { coordinador.procesarTrasDeclarar(any()) }
    }

    @Test
    fun `F1 sin red la fila sigue pendiente y lo dice, nada se encola`() = runTest {
        coEvery { servicio.declararNoCobrado(any(), any(), any()) } returns ResultadoDeDeclaracion.SinConexion
        val vm = vm()

        vm.revisar(a); vm.identificar("4321"); vm.firmar()

        assertEquals(PasoDelGerente.Cerrado, vm.paso.value)
        assertEquals(CancelacionDeCobro.FIRMA_SIN_RED, vm.avisos.value["req-a"])
        coVerify(exactly = 1) { servicio.declararNoCobrado(any(), any(), any()) }
    }

    @Test
    fun `F1 un rechazo del servidor deja su motivo en la fila`() = runTest {
        coEvery { servicio.declararNoCobrado(any(), any(), any()) } returns
            ResultadoDeDeclaracion.Rechazada("Este cobro sí tiene señales de haber pasado.", "POSITIVE_EVIDENCE_EXISTS")
        val vm = vm()

        vm.revisar(a); vm.identificar("4321"); vm.firmar()

        assertEquals("Este cobro sí tiene señales de haber pasado.", vm.avisos.value["req-a"])
    }

    @Test
    fun `F1 doble toque en Firmar manda una sola firma, y con la firma viajando no se cierra ni se abre otra`() = runTest {
        val respuesta = CompletableDeferred<ResultadoDeDeclaracion>()
        coEvery { servicio.declararNoCobrado(any(), any(), any()) } coAnswers { respuesta.await() }
        val vm = vm()
        val b = a.copy(requestId = "req-b")

        vm.revisar(a); vm.identificar("4321")
        vm.firmar(); vm.firmar()
        vm.cerrar()
        vm.revisar(b)
        assertTrue("sigue firmando A", (vm.paso.value as PasoDelGerente.Firmar).let { it.enviando && it.cobro == a })
        respuesta.complete(ResultadoDeDeclaracion.Liberada)

        coVerify(exactly = 1) { servicio.declararNoCobrado(any(), any(), any()) }
        coVerify(exactly = 0) { servicio.declararNoCobrado("req-b", any(), any()) }
        assertEquals(PasoDelGerente.Cerrado, vm.paso.value)
    }

    @Test
    fun `F1 la lista cambia mientras el gerente teclea - se firma la fila que eligio`() = runTest {
        coEvery { servicio.declararNoCobrado(any(), any(), any()) } returns ResultadoDeDeclaracion.Liberada
        every { servicio.pendientesDeOtrasVentas(null) } returns listOf(pendiente("req-a", 35_000))
        val vm = vm()
        vm.refrescar()
        vm.revisar(vm.cobrosPorConfirmar.value.single())

        // Aparece B, del mismo importe, y queda primero.
        every { servicio.pendientesDeOtrasVentas(null) } returns listOf(pendiente("req-b", 35_000), pendiente("req-a", 35_000))
        vm.refrescar()
        vm.identificar("4321"); vm.firmar()

        coVerify(exactly = 1) { servicio.declararNoCobrado("req-a", "venue-1", "4321") }
        coVerify(exactly = 0) { servicio.declararNoCobrado("req-b", any(), any()) }
    }

    @Test
    fun `F1 Cobros por confirmar - dudas de este aparato, sin lo cobrado, lo que va en vuelo ni las cancelaciones`() = runTest {
        cancelaciones.value = listOf(intencion)
        every { servicio.intentoEnVuelo() } returns com.avoqado.pos.payment.data.IntentoDeCobroEnVuelo("req-en-vuelo", "t1", "venue-1")
        every { servicio.pendientesDeOtrasVentas(null) } returns listOf(
            pendiente("req-a", 30_000, propina = 5_000),
            pendiente("req-cobrado", 1_000, cobrado = true),
            pendiente("req-en-vuelo", 2_000),
            pendiente("req-1", 3_000),
        )
        val vm = vm()

        vm.refrescar()

        assertEquals(listOf("req-a"), vm.cobrosPorConfirmar.value.map { it.requestId })
        assertEquals("importe + propina del intento", 35_000, vm.cobrosPorConfirmar.value.single().montoCents)
    }

    @Test
    fun `F1 una fila antigua sin importe pregunta por su ID y mientras dice Importe por confirmar`() = runTest {
        val importe = CompletableDeferred<Int?>()
        coEvery { servicio.importeDelCobro("req-vieja", "venue-1") } coAnswers { importe.await() }
        every { servicio.pendientesDeOtrasVentas(null) } returns listOf(pendiente("req-vieja", null, propina = null))
        val vm = vm()

        vm.refrescar() // la banda: no pregunta
        coVerify(exactly = 0) { servicio.importeDelCobro(any(), any()) }
        vm.refrescar(recuperarImportes = true)
        vm.refrescar(recuperarImportes = true) // una pregunta a la vez
        assertEquals("mientras: sin importe, nunca uno inventado", null, vm.cobrosPorConfirmar.value.single().montoCents)

        importe.complete(12_000)
        assertEquals(12_000, vm.cobrosPorConfirmar.value.single().montoCents)
        vm.refrescar()
        assertEquals("se recuerda", 12_000, vm.cobrosPorConfirmar.value.single().montoCents)
        coVerify(exactly = 1) { servicio.importeDelCobro("req-vieja", "venue-1") }
    }

    @Test
    fun `F1 la banda dice cuantos cobros y cancelaciones esperan al gerente`() {
        assertEquals(null, com.avoqado.pos.sync.presentation.textoDeLaBandaDePorConfirmar(0, 0))
        assertEquals("Cancelaciones pendientes: 2", com.avoqado.pos.sync.presentation.textoDeLaBandaDePorConfirmar(0, 2))
        assertEquals("Cobros por confirmar: 1", com.avoqado.pos.sync.presentation.textoDeLaBandaDePorConfirmar(1, 0))
        assertEquals("Cobros por confirmar: 1 · Cancelaciones pendientes: 2", com.avoqado.pos.sync.presentation.textoDeLaBandaDePorConfirmar(1, 2))
    }
}
