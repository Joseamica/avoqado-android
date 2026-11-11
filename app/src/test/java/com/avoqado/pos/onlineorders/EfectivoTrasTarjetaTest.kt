package com.avoqado.pos.onlineorders

import com.avoqado.pos.onlineorders.data.FolioCobradoConTarjeta
import com.avoqado.pos.onlineorders.data.FoliosCobradosConTarjeta
import com.avoqado.pos.onlineorders.data.OnlineOrdersRepository
import com.avoqado.pos.onlineorders.domain.ConteosPorPagar
import com.avoqado.pos.onlineorders.domain.CursoresPorPagar
import com.avoqado.pos.onlineorders.domain.Inbox
import com.avoqado.pos.onlineorders.domain.ListaPorPagar
import com.avoqado.pos.onlineorders.domain.OnlineOrder
import com.avoqado.pos.onlineorders.domain.OnlineOrderStatus
import com.avoqado.pos.onlineorders.domain.SaldoDelPedido
import com.avoqado.pos.onlineorders.presentation.OnlineOrdersViewModel
import com.avoqado.pos.onlineorders.presentation.TextosPedidosEnLinea
import com.avoqado.pos.payment.data.ContextoDeCobro
import com.avoqado.pos.payment.data.EfectivoDelPedido
import com.avoqado.pos.payment.domain.CardChargeDecision
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * P1 (Codex W-1 r6 #2): «Cobrar» revisa el dinero de ESTE aparato al empezar (R100) y después ESPERA (el saldo del servidor, la copia
 * guardada). Si mientras tanto la terminal confirma el cobro con tarjeta de esa orden, y la consulta del saldo falla por red, se abría el
 * efectivo de un folio ya pagado. Ahora el dinero se vuelve a revisar justo antes de abrir: un cobro confirmado frena; una tarjeta en
 * duda sigue sin frenar (founder, 2-nov «B»), sólo avisa.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EfectivoTrasTarjetaTest {
    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<OnlineOrdersRepository>()
    private val marcas = FoliosCobradosConTarjeta.enMemoria()

    /** Los cobros con tarjeta de este aparato que siguen en la lista durable (los de `TerminalPaymentService`). */
    private var cobros: List<ContextoDeCobro> = emptyList()
    private val enDuda = ContextoDeCobro("req-1", venueId = "v1", terminalId = "t1", orderId = "f1", amountCents = 13400, cobrado = false)

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.inbox(any(), any(), any()) } returns
            Result.success(Inbox(Instant.parse("2026-11-06T18:10:00Z"), 0, 0, 0, emptyList(), storeOff = false, truncated = false, nextCursor = null))
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun folio() = OnlineOrder(
        orderId = "f1", onlineOrderId = "oo-f1", orderNumber = "W7K3P", status = OnlineOrderStatus.PENDING_ACCEPTANCE,
        paymentMode = "PAY_AT_PICKUP", paymentStatus = "PENDING", customerName = "Ana", customerPhone = null, note = null,
        createdAt = Instant.parse("2026-11-06T18:00:00Z"), acceptDeadlineAt = Instant.parse("2099-11-06T18:30:00Z"), estimatedReadyAt = null,
        totalCents = 13400, remainingCents = 13400, needsPrint = false, items = emptyList(), requiresAcceptance = false, channel = "QR",
    )

    private val guardada = ListaPorPagar(true, true, 30, ConteosPorPagar(1, 0), listOf(folio()), emptyList(), CursoresPorPagar(null, null), numero = 1)

    private var respuesta: Result<ListaPorPagar> = Result.failure(IOException("sin red"))
    private var efectivo: suspend (String) -> EfectivoDelPedido = { EfectivoDelPedido.NINGUNO }

    private fun armar() = OnlineOrdersViewModel(
        repositorio = repo, permisoActualizar = { true }, permisoCancelar = { true }, tienePlan = { true },
        hayPedidosAbiertos = { true }, ventanaALaVista = MutableStateFlow(true), efectivoDelPedido = { efectivo(it) },
        leerPorPagar = { _, _, _ -> respuesta }, foliosGuardados = { guardada },
        ahoraMs = { Instant.parse("2026-11-06T18:10:00Z").toEpochMilli() },
        foliosConTarjeta = marcas, cobrosConTarjeta = { cobros }, venueActual = { "v1" },
    )

    @Test
    fun `P1 la tarjeta se confirma mientras se consulta el saldo y la red falla - no abre el efectivo`() = runTest(dispatcher) {
        cobros = listOf(enDuda) // al tocar «Cobrar» la tarjeta sigue en duda: no frena (founder «B»)
        coEvery { repo.saldoDelPedido("f1") } coAnswers {
            cobros = listOf(enDuda.copy(cobrado = true)) // la terminal confirma el cobro mientras tanto
            Result.failure(IOException("sin red"))
        }
        val vm = armar()

        vm.cobrarFolio(folio())
        advanceUntilIdle()

        assertNull("un folio ya pagado con tarjeta no abre efectivo", vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.FOLIO_COBRADO_CON_TARJETA, vm.aviso.value)
    }

    @Test
    fun `P1 igual con red si el saldo del servidor todavia no cuenta la tarjeta`() = runTest(dispatcher) {
        cobros = listOf(enDuda)
        coEvery { repo.saldoDelPedido("f1") } coAnswers {
            cobros = listOf(enDuda.copy(cobrado = true))
            Result.success(SaldoDelPedido(13400L, estadoDeLaCuenta = "PENDING")) // el servidor aún no registra el cobro de la terminal
        }
        val vm = armar()

        vm.cobrarFolio(folio())
        advanceUntilIdle()

        assertNull(vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.FOLIO_COBRADO_CON_TARJETA, vm.aviso.value)
    }

    @Test
    fun `P1 el positivo ya salio de la lista y dejo su marca mientras se consultaba - no abre`() = runTest(dispatcher) {
        coEvery { repo.saldoDelPedido("f1") } coAnswers {
            marcas.anotar(FolioCobradoConTarjeta("f1", 13400, 13400, enMs = 1, venueId = "v1", requestId = "req-1"))
            Result.failure(IOException("sin red"))
        }
        val vm = armar()

        vm.cobrarFolio(folio())
        advanceUntilIdle()

        assertNull(vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.FOLIO_COBRADO_CON_TARJETA, vm.aviso.value)
    }

    /** La marca «aquí ya se cobró» que deja un positivo al salir de la lista (la escribe `TerminalPaymentService.quitar`). */
    private val marcaF1 = FolioCobradoConTarjeta("f1", 13400, 13400, enMs = 1, venueId = "v1", requestId = "req-1")

    @Test
    fun `r2 con red - la tarjeta en duda se confirma, sale de la lista y su marca se suelta mientras se consulta - no abre`() = runTest(dispatcher) {
        // Codex r2 #1: saldo del servidor viejo ($134); mientras se consulta, la tarjeta se confirma, sale de la lista por su antigüedad,
        // deja su marca y una lectura que ya cuenta el pago la suelta: no queda rastro en la lista ni en las marcas.
        cobros = listOf(enDuda)
        coEvery { repo.saldoDelPedido("f1") } coAnswers {
            cobros = emptyList()
            marcas.anotar(marcaF1)
            marcas.olvidar(listOf(marcaF1))
            Result.success(SaldoDelPedido(13400L, estadoDeLaCuenta = "PENDING"))
        }
        val vm = armar()

        vm.cobrarFolio(folio())
        advanceUntilIdle()

        assertNull("nunca el efectivo con el saldo de antes de la tarjeta", vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.TARJETA_CAMBIO, vm.aviso.value)
    }

    @Test
    fun `r3 una solicitud que no estaba en duda al empezar vuelve cobrada y su marca se suelta en la espera - no abre`() = runTest(dispatcher) {
        // Codex r3 #1: una solicitud vieja que un negativo sacó de la lista vuelve con un positivo tardío (N2), sale por su antigüedad y
        // una lectura suelta su marca, todo durante la consulta: al empezar y al abrir no hay ninguna tarjeta de la orden.
        coEvery { repo.saldoDelPedido("f1") } coAnswers {
            marcas.anotar(marcaF1)
            marcas.olvidar(listOf(marcaF1))
            Result.success(SaldoDelPedido(13400L, estadoDeLaCuenta = "PENDING"))
        }
        val vm = armar()

        vm.cobrarFolio(folio())
        advanceUntilIdle()

        assertNull(vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.TARJETA_CAMBIO, vm.aviso.value)
    }

    @Test
    fun `la marca soltada de OTRA orden no frena`() = runTest(dispatcher) {
        coEvery { repo.saldoDelPedido("f1") } coAnswers {
            val otra = marcaF1.copy(orderId = "f2", requestId = "req-2")
            marcas.anotar(otra)
            marcas.olvidar(listOf(otra))
            Result.success(SaldoDelPedido(13400L, estadoDeLaCuenta = "PENDING"))
        }
        val vm = armar()

        vm.cobrarFolio(folio())
        advanceUntilIdle()

        assertEquals(13400, vm.cobroAbierto.value?.saldoCents)
    }

    @Test
    fun `una tarjeta que sigue en duda no frena el efectivo, solo avisa - founder B`() = runTest(dispatcher) {
        cobros = listOf(enDuda)
        var consultasALaCola = 0
        efectivo = { consultasALaCola++; EfectivoDelPedido.NINGUNO }
        coEvery { repo.saldoDelPedido("f1") } returns Result.failure(IOException("sin red"))
        val vm = armar()

        vm.cobrarFolio(folio())
        advanceUntilIdle()

        assertNotNull(vm.cobroAbierto.value)
        assertEquals(13400, vm.cobroAbierto.value!!.saldoCents)
        assertEquals(CardChargeDecision.UNDETERMINED_MESSAGE, vm.aviso.value)
        // Codex r3 #2: entre elegir el saldo y abrir no hay otra espera (una consulta a la cola, la del principio).
        assertEquals(1, consultasALaCola)
    }
}
