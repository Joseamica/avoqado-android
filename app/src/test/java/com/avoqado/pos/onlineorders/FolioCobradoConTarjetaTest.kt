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
import com.avoqado.pos.onlineorders.presentation.conLoCobradoEnElAparato
import com.avoqado.pos.onlineorders.presentation.tarjetasConfirmadas
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tienda híbrida (A5, Codex fase 3-4 r1 #1): un folio cobrado con la terminal ligada. La tarjeta pasó, se cayó la red antes de releer
 * la lista, y la lista vieja (en memoria o la guardada tras reiniciar) lo sigue diciendo «Por pagar»: sin la marca del aparato, la
 * cajera podía cobrarle EFECTIVO encima. La marca la escribe el cobro (`PaymentFlowViewModel`, ver `PaymentFlowViewModelTest`) antes
 * de soltar su pendiente; aquí, lo que la bandeja hace con ella: bloquea, la pinta en «Por entregar» y la suelta cuando el SERVIDOR
 * ya cuenta el pago.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FolioCobradoConTarjetaTest {

    private val dispatcher = StandardTestDispatcher()
    private val repo = mockk<OnlineOrdersRepository>()

    /** El «disco» del aparato: lo comparten la bandeja de antes y la de después de reiniciar. */
    private val marcas = FoliosCobradosConTarjeta.enMemoria()

    private var respuesta: Result<ListaPorPagar> = Result.failure(IOException("sin red"))
    private var guardada: ListaPorPagar? = null

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { repo.inbox(any(), any(), any()) } returns
            Result.success(Inbox(Instant.parse("2026-11-06T18:10:00Z"), 0, 0, 0, emptyList(), storeOff = false, truncated = false, nextCursor = null))
        coEvery { repo.saldoDelPedido(any()) } returns Result.failure(IOException("sin red"))
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private fun folio(id: String = "f1", total: Long = 13400, falta: Long = 13400, status: OnlineOrderStatus = OnlineOrderStatus.PENDING_ACCEPTANCE) = OnlineOrder(
        orderId = id, onlineOrderId = "oo-$id", orderNumber = "W7K3P", status = status, paymentMode = "PAY_AT_PICKUP",
        paymentStatus = if (falta == 0L) "PAID" else "PENDING", customerName = "Ana", customerPhone = null, note = null,
        createdAt = Instant.parse("2026-11-06T18:00:00Z"), acceptDeadlineAt = Instant.parse("2026-11-06T18:30:00Z"), estimatedReadyAt = null,
        totalCents = total, remainingCents = falta, needsPrint = false, items = emptyList(), requiresAcceptance = false, channel = "QR",
    )

    private fun lista(porPagar: List<OnlineOrder> = emptyList(), porEntregar: List<OnlineOrder> = emptyList(), cursorPagar: String? = null) =
        ListaPorPagar(true, true, 30, ConteosPorPagar(porPagar.size, porEntregar.size), porPagar, porEntregar, CursoresPorPagar(cursorPagar, null))

    private fun armar() = OnlineOrdersViewModel(
        repositorio = repo,
        permisoActualizar = { true },
        permisoCancelar = { true },
        tienePlan = { true },
        hayPedidosAbiertos = { true },
        ventanaALaVista = MutableStateFlow(true),
        leerPorPagar = { _, _, _ -> respuesta },
        foliosGuardados = { guardada },
        foliosConTarjeta = marcas,
    )

    @Test
    fun `P1 card success then offline with the stale list - Cobrar is blocked and the folio moves to Por entregar`() = runTest(dispatcher) {
        respuesta = Result.success(lista(porPagar = listOf(folio())))
        val vm = armar()
        vm.refrescar()

        // La terminal cobró $134 (lo anota el cobro, antes de soltar su pendiente) y la red se cae antes de releer.
        marcas.marcar("f1", montoCents = 13400, saldoAntesCents = 13400)
        respuesta = Result.failure(IOException("sin red"))
        vm.refrescar()
        assertEquals("la lista vieja lo sigue diciendo por pagar", listOf("f1"), vm.porPagar.value!!.porPagar.map { it.orderId })

        vm.cobrarFolio(folio())
        advanceUntilIdle()

        assertNull("no se abre un cobro en efectivo encima de la tarjeta", vm.cobroAbierto.value)
        assertEquals("Este folio ya se cobró con tarjeta en esta caja.", vm.aviso.value)
        assertEquals(TextosPedidosEnLinea.FOLIO_COBRADO_CON_TARJETA, vm.aviso.value)
        coVerify(exactly = 0) { repo.saldoDelPedido(any()) }
        // Igual que el efectivo en la cola: se pinta en «Por entregar» con lo cobrado.
        assertEquals(mapOf("f1" to 13400L), vm.cobradosConTarjeta.value)
        val pantalla = conLoCobradoEnElAparato(vm.porPagar.value!!, vm.cobradosConTarjeta.value.keys)
        assertTrue(pantalla.porPagar.isEmpty())
        assertEquals(listOf("f1"), pantalla.porEntregar.map { it.orderId })
    }

    @Test
    fun `P1 an app restart with the stale saved list - still blocked`() = runTest(dispatcher) {
        marcas.marcar("f1", montoCents = 13400, saldoAntesCents = 13400)
        // Reinicia sin red: la lista guardada es de antes del cobro.
        guardada = lista(porPagar = listOf(folio())).copy(guardadoHaceMin = 2)
        respuesta = Result.failure(IOException("sin red"))
        val vm = armar()
        vm.descubrirPorPagar()

        assertEquals(mapOf("f1" to 13400L), vm.cobradosConTarjeta.value)
        vm.cobrarFolio(folio())
        advanceUntilIdle()
        assertNull(vm.cobroAbierto.value)
        assertEquals(TextosPedidosEnLinea.FOLIO_COBRADO_CON_TARJETA, vm.aviso.value)
    }

    @Test
    fun `the server confirming the payment clears the marker`() = runTest(dispatcher) {
        marcas.marcar("f1", montoCents = 13400, saldoAntesCents = 13400)
        val vm = armar()

        // Una lectura con red que todavía no lo cuenta (salió antes del cobro): la marca se queda.
        respuesta = Result.success(lista(porPagar = listOf(folio())))
        vm.refrescar()
        assertEquals(1, marcas.marcas().size)

        // El SERVIDOR ya lo cuenta: salió de «Por pagar» (está en «Por entregar»).
        respuesta = Result.success(lista(porEntregar = listOf(folio(falta = 0, status = OnlineOrderStatus.ACCEPTED))))
        vm.refrescar()
        assertTrue(marcas.marcas().isEmpty())
        assertTrue(vm.cobradosConTarjeta.value.isEmpty())

        // Ya sin marca, «Cobrar» vuelve a preguntar al servidor (la marca no se queda para siempre).
        coEvery { repo.saldoDelPedido("f1") } returns Result.success(SaldoDelPedido(13400L))
        vm.cobrarFolio(folio())
        advanceUntilIdle()
        coVerify(exactly = 1) { repo.saldoDelPedido("f1") }
    }

    @Test
    fun `an abono by card clears when what is missing dropped by at least the charge`() {
        val marca = FolioCobradoConTarjeta("f1", montoCents = 10000, saldoAntesCents = 18500, enMs = 0)
        val vieja = lista(porPagar = listOf(folio(total = 18500, falta = 18500)))
        val nueva = lista(porPagar = listOf(folio(total = 18500, falta = 8500)))
        assertTrue("la lista de antes del cobro no confirma nada", tarjetasConfirmadas(listOf(marca), vieja).isEmpty())
        assertEquals(listOf(marca), tarjetasConfirmadas(listOf(marca), nueva))
        // Fuera de «Por pagar» pero con más páginas por traer: pudo quedar en otra; no se suelta.
        assertTrue(tarjetasConfirmadas(listOf(marca), lista(cursorPagar = "c1")).isEmpty())
        assertEquals(listOf(marca), tarjetasConfirmadas(listOf(marca), lista()))
    }

    @Test
    fun `two card parts of the same folio add up and keep the first balance`() {
        marcas.marcar("f1", montoCents = 5000, saldoAntesCents = 18500, enMs = 1)
        marcas.marcar("f1", montoCents = 3000, saldoAntesCents = 13500, enMs = 2)
        assertEquals(listOf(FolioCobradoConTarjeta("f1", montoCents = 8000, saldoAntesCents = 18500, enMs = 1)), marcas.marcas())
        // Un olvido con la marca de antes (ya cambió) no borra la suma nueva.
        marcas.olvidar(listOf(FolioCobradoConTarjeta("f1", montoCents = 5000, saldoAntesCents = 18500, enMs = 1)))
        assertEquals(1, marcas.marcas().size)
    }

    @Test
    fun `a re-read whose second page fails keeps the card folio and its marker`() = runTest(dispatcher) {
        var segunda: Result<ListaPorPagar> = Result.success(lista(porPagar = listOf(folio("f1"))))
        val vm = OnlineOrdersViewModel(
            repositorio = repo, permisoActualizar = { true }, permisoCancelar = { true }, tienePlan = { true }, hayPedidosAbiertos = { true },
            ventanaALaVista = MutableStateFlow(true),
            leerPorPagar = { _, _, cursor -> if (cursor == null) respuesta else segunda },
            foliosConTarjeta = marcas,
        )
        // El folio f1 está en la segunda página, traída con «Cargar más».
        respuesta = Result.success(lista(porPagar = listOf(folio("f0")), cursorPagar = "c1"))
        vm.refrescar()
        vm.cargarMasFolios(OnlineOrdersViewModel.SECCION_POR_PAGAR)
        advanceUntilIdle()
        assertEquals(listOf("f0", "f1"), vm.porPagar.value!!.porPagar.map { it.orderId })

        marcas.marcar("f1", montoCents = 13400, saldoAntesCents = 13400)
        segunda = Result.failure(IllegalStateException("500"))
        vm.refrescar()

        assertEquals("la lectura incompleta no lo borra", listOf("f0", "f1"), vm.porPagar.value!!.porPagar.map { it.orderId })
        assertEquals("ni suelta su marca", 1, marcas.marcas().size)
        assertEquals(mapOf("f1" to 13400L), vm.cobradosConTarjeta.value)
    }
}
