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
import com.avoqado.pos.core.data.network.OverrideResult
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 🔴 F1 mínimo, tarea 6 (founder, 17-nov: «el sistema solo; lo raro lo resuelve el gerente con su PIN»). La revisión del gerente en
 * «Operaciones por revisar», en UN paso: la fila EXACTA → el teclado que ya dice qué se firma → [Firmar] con el PIN. Reescribe las pruebas D19
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
        // La fila A está en la lista durable (la sucursal ya la filtra el servicio, `TerminalPaymentServiceHttpTest`).
        every { it.pendientesDeOtrasVentas(null) } returns listOf(pendiente("req-a", 35_000))
    }
    private val intencion = IntencionDeCancelarCobro(id = "req-1", requestId = "req-1", venueId = "venue-1", montoCents = 35_000, creadaEn = 1, terminalLibre = true)
    private val a = CobroPorConfirmar(requestId = "req-a", venueId = "venue-1", montoCents = 35_000, desdeMillis = 1)

    private var sucursal: String? = "venue-1"
    private val storage = mockk<com.avoqado.pos.core.data.local.SecureStorage>(relaxed = true).also {
        every { it.venueId } answers { sucursal }
    }

    private fun vm() = CancelacionesPendientesViewModel(coordinador, servicio, storage)

    private fun pendiente(id: String, centavos: Int?, cobrado: Boolean = false, propina: Int? = 0) = ContextoDeCobro(
        requestId = id, venueId = "venue-1", terminalId = "t1", orderId = "orden-$id", amountCents = centavos, tipCents = propina,
        desdeMillis = 1, cobrado = cobrado,
    )

    private val firmado = OverrideResult.Granted(token = "", authorizedByName = "")

    @Test
    fun `F1 un solo paso - Revisar abre el teclado de ESA fila y Firmar manda ESA fila con ESE PIN`() = runTest {
        coEvery { servicio.declararNoCobrado("req-a", "venue-1", "4321") } returns ResultadoDeDeclaracion.Liberada
        val vm = vm()

        vm.revisar(a)
        assertEquals("el teclado es de la fila elegida", a, (vm.paso.value as PasoDelGerente.Firmar).cobro)
        coVerify(exactly = 0) { servicio.declararNoCobrado(any(), any(), any()) }

        assertEquals(firmado, vm.firmar("4321"))

        assertEquals(PasoDelGerente.Cerrado, vm.paso.value)
        coVerify(exactly = 1) { servicio.declararNoCobrado("req-a", "venue-1", "4321") }
        coVerify(exactly = 0) { servicio.declararNoCobrado(any(), any(), null) }
    }

    @Test
    fun `F1 sin teclado abierto Firmar no manda nada`() = runTest {
        vm().firmar("4321")
        coVerify(exactly = 0) { servicio.declararNoCobrado(any(), any(), any()) }
    }

    @Test
    fun `F1 la cancelacion liberada solo vuelve a consultar - el coordinador cancela la orden, aqui no se borra nada`() = runTest {
        coEvery { servicio.declararNoCobrado("req-1", "venue-1", "4321") } returns ResultadoDeDeclaracion.Liberada
        cancelaciones.value = listOf(intencion)
        val vm = vm()

        vm.revisar(vm.filaDe(intencion)!!)
        vm.firmar("4321")

        verify(exactly = 1) { coordinador.procesarTrasDeclarar("req-1") }
        assertTrue(vm.avisos.value.isEmpty())
    }

    @Test
    fun `F1 una intencion solo de la orden no tiene fila que revisar`() {
        assertEquals(null, vm().filaDe(intencion.copy(requestId = null)))
    }

    @Test
    fun `F1 un 403 deja el MISMO teclado sobre la MISMA fila con Ese PIN no es de un gerente con permiso`() = runTest {
        coEvery { servicio.declararNoCobrado("req-a", any(), "0000") } returns
            ResultadoDeDeclaracion.Rechazada("Pide a un gerente que ingrese su PIN.", CancelacionDeCobro.CODIGO_PIDE_PIN_DE_GERENTE)
        coEvery { servicio.declararNoCobrado("req-a", any(), "4321") } returns ResultadoDeDeclaracion.Liberada
        val vm = vm()
        vm.revisar(a)
        val teclado = vm.paso.value

        assertEquals(OverrideResult.Failed(CancelacionDeCobro.PIN_NO_ES_DE_GERENTE), vm.firmar("0000"))
        assertEquals("el mismo teclado, la misma fila", teclado, vm.paso.value)
        assertTrue(vm.avisos.value.isEmpty())
        verify(exactly = 0) { coordinador.procesarTrasDeclarar(any()) }

        assertEquals(firmado, vm.firmar("4321"))
        assertEquals(PasoDelGerente.Cerrado, vm.paso.value)
    }

    @Test
    fun `F1 sin red el teclado dice que la firma necesita internet y la fila sigue, nada se encola`() = runTest {
        coEvery { servicio.declararNoCobrado(any(), any(), any()) } returns ResultadoDeDeclaracion.SinConexion
        val vm = vm()
        vm.revisar(a)

        assertEquals(OverrideResult.Failed(CancelacionDeCobro.FIRMA_SIN_RED), vm.firmar("4321"))
        assertTrue("el teclado sigue sobre la fila", vm.paso.value is PasoDelGerente.Firmar)
        coVerify(exactly = 1) { servicio.declararNoCobrado(any(), any(), any()) }
    }

    @Test
    fun `F1 un rechazo del servidor cierra el teclado y deja su motivo en la fila`() = runTest {
        coEvery { servicio.declararNoCobrado(any(), any(), any()) } returns
            ResultadoDeDeclaracion.Rechazada("Este cobro sí tiene señales de haber pasado.", "POSITIVE_EVIDENCE_EXISTS")
        val vm = vm()
        vm.revisar(a)

        vm.firmar("4321")

        assertEquals(PasoDelGerente.Cerrado, vm.paso.value)
        assertEquals("Este cobro sí tiene señales de haber pasado.", vm.avisos.value["req-a"])
    }

    @Test
    fun `F1 doble toque en Firmar manda una sola firma, y con la firma viajando no se cierra ni se abre otra`() = runTest {
        val respuesta = CompletableDeferred<ResultadoDeDeclaracion>()
        coEvery { servicio.declararNoCobrado(any(), any(), any()) } coAnswers { respuesta.await() }
        val vm = vm()
        val b = a.copy(requestId = "req-b")
        vm.revisar(a)

        val teclado = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler) + kotlinx.coroutines.Job())
        val primero = teclado.async { vm.firmar("4321") }
        val segundo = teclado.async { vm.firmar("4321") }
        vm.cerrar()
        vm.revisar(b)
        assertEquals("sigue firmando A", a, (vm.paso.value as PasoDelGerente.Firmar).cobro)
        respuesta.complete(ResultadoDeDeclaracion.Liberada)

        assertEquals(firmado, primero.await())
        assertEquals(firmado, segundo.await())
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
        vm.firmar("4321")

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
    fun `Codex r1 7 - la cancelacion se resuelve mientras el gerente teclea - el teclado se cierra y un PIN tardio no manda nada`() = runTest {
        cancelaciones.value = listOf(intencion)
        val vm = vm()
        vm.revisar(vm.filaDe(intencion)!!)

        every { coordinador.desenlaceConocido("req-1") } returns com.avoqado.pos.payment.domain.DesenlaceDeCancelacion.NoSeCobro
        cancelaciones.value = emptyList() // el coordinador la cerró (la terminal confirmó sola)

        assertEquals("el teclado se va", PasoDelGerente.Cerrado, vm.paso.value)
        assertEquals("y se dice cómo quedó", CancelacionDeCobro.YA_SE_RESOLVIO, vm.resultado.value)
        vm.firmar("4321")
        coVerify(exactly = 0) { servicio.declararNoCobrado(any(), any(), any()) }
    }

    @Test
    fun `Codex r1 7 - el cobro por confirmar se resuelve mientras el gerente teclea - un PIN tardio no manda nada`() = runTest {
        every { servicio.pendientesDeOtrasVentas(null) } returns listOf(pendiente("req-a", 35_000))
        val vm = vm()
        vm.refrescar()
        vm.revisar(vm.cobrosPorConfirmar.value.single())

        every { servicio.pendientesDeOtrasVentas(null) } returns emptyList() // A ya consta: SÍ pasó
        every { servicio.yaCobrado("req-a") } returns true
        vm.firmar("4321")

        coVerify(exactly = 0) { servicio.declararNoCobrado(any(), any(), any()) }
        assertEquals(PasoDelGerente.Cerrado, vm.paso.value)
        assertEquals(CancelacionDeCobro.SE_COBRO_AL_FINAL, vm.resultado.value)
    }

    @Test
    fun `Codex r1 8 - Cancelaciones pendientes solo de la sucursal activa, las viejas sin sucursal se ven`() = runTest {
        cancelaciones.value = listOf(
            intencion,
            intencion.copy(id = "req-b", requestId = "req-b", venueId = "venue-2"),
            intencion.copy(id = "req-vieja", requestId = "req-vieja", venueId = ""),
        )
        val vm = vm()
        vm.refrescar()

        assertEquals(listOf("req-1", "req-vieja"), vm.pendientes.value.map { it.id })
    }

    @Test
    fun `Codex r1 11 - la fila de una cancelacion sin importe toma el de su solicitud`() = runTest {
        every { servicio.contextoDe("req-1") } returns pendiente("req-1", 30_000, propina = 5_000)

        assertEquals(35_000, vm().filaDe(intencion.copy(montoCents = null))?.montoCents)
    }

    @Test
    fun `F1 la banda dice cuantos cobros y cancelaciones esperan al gerente`() {
        assertEquals(null, com.avoqado.pos.sync.presentation.textoDeLaBandaDePorConfirmar(0, 0))
        assertEquals("Cancelaciones pendientes: 2", com.avoqado.pos.sync.presentation.textoDeLaBandaDePorConfirmar(0, 2))
        assertEquals("Cobros por confirmar: 1", com.avoqado.pos.sync.presentation.textoDeLaBandaDePorConfirmar(1, 0))
        assertEquals("Cobros por confirmar: 1 · Cancelaciones pendientes: 2", com.avoqado.pos.sync.presentation.textoDeLaBandaDePorConfirmar(1, 2))
    }
}
