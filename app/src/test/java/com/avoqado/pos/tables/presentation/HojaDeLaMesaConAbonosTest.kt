package com.avoqado.pos.tables.presentation

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import com.avoqado.pos.payment.data.CashPaymentRepository
import com.avoqado.pos.payment.data.EfectivoDelPedido
import com.avoqado.pos.tables.data.*
import io.mockk.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * D25 (Flautas, 20-nov): la hoja de una mesa con un abono de $10 decía «Cobrar cuenta • $60.00» y la cuenta «Por pagar $50.00».
 * La hoja dice lo mismo que la cuenta: con su saldo al día, lo que FALTA (o el total sin abonos); sin él (sin red, leyendo, sin
 * confirmar), la cuenta dice «Saldo por confirmar» y el botón va sin importe (QA en la SAMSUNG, 21-nov).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HojaDeLaMesaConAbonosTest {
    @get:Rule val main = MainDispatcherRule()
    private val venue = "v-hoja"
    private val cuenta = OpenCheckSummary("o1", "ORD-1", total = 60.0)
    private val mesa = DiningTable("t1", "4", openOrders = listOf(cuenta))
    private val repository = mockk<TableServiceRepository>(relaxed = true)
    private val outbox = mockk<SyncOutbox>(relaxed = true)
    private val cola = mockk<CashPaymentRepository>()
    private val drafts = TableRoundDraftStore(Files.createTempDirectory("hoja-mesa").toFile())
    private val session = TableSession()

    private fun detalle(pagado: Int, falta: Int) = OrderDetail(id = "o1", orderNumber = "ORD-1", total = 60.0,
        preBillBalance = PreBillBalance(orderTotalCents = 6000, totalPaidCents = pagado, remainingBalanceCents = falta))

    private fun vm(): TablesViewModel {
        every { repository.tables } returns MutableStateFlow(listOf(mesa))
        every { repository.isLoading } returns MutableStateFlow(false)
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(0, 0, emptySet(), 0)
        coEvery { cola.efectivoDelPedido(any()) } returns EfectivoDelPedido.NINGUNO
        val storage = mockk<SecureStorage>(relaxed = true)
        every { storage.venueId } returns venue
        return TablesViewModel(repository, mockk(relaxed = true), session, mockk(relaxed = true), storage, outbox,
            mockk(relaxed = true), FloorPlanRepository(mockk(relaxed = true), mockk(relaxed = true)), mockk(relaxed = true),
            mockk<ApiFuenteDeVersiones>(relaxed = true), mockk(relaxed = true), efectivoEnColaDeCobros = cola, draftStore = drafts)
            .also { it.discoParaFusionar = kotlin.coroutines.EmptyCoroutineContext }
    }

    private fun TestScope.textoDeLaHoja(subject: TablesViewModel, check: OpenCheckSummary = cuenta): String {
        subject.leerSaldoDeLaHoja(mesa.copy(openOrders = listOf(check))); advanceUntilIdle()
        return textoCobrarCuenta(check, subject.saldoDeLaHoja.value)
    }

    @Test fun `D25 con red y un abono el boton dice lo que falta, no el total`() = runTest {
        coEvery { repository.getOrderDetail(venue, "o1") } returns Result.success(detalle(pagado = 1000, falta = 5000))
        val subject = vm()
        try { assertEquals("Cobrar lo que falta • $50.00", textoDeLaHoja(subject)) } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `D25 con red y sin abonos el boton no cambia`() = runTest {
        coEvery { repository.getOrderDetail(venue, "o1") } returns Result.success(detalle(pagado = 0, falta = 6000))
        val subject = vm()
        try { assertEquals("Cobrar cuenta • $60.00", textoDeLaHoja(subject)) } finally { subject.viewModelScope.cancel() }
    }

    // QA SAMSUNG 21-nov (M4): la cuenta ya abierta con red, guardada en el aparato; sin red la cuenta dice «Saldo por confirmar».
    @Test fun `D25 sin red la hoja no promete el importe guardado que la cuenta no confirma`() = runTest {
        drafts.save(venue, "o1", emptyList(), emptyMap(), cachedCheck = detalle(pagado = 1000, falta = 5000))
        coEvery { repository.getOrderDetail(venue, "o1") } returns Result.failure(IOException("sin red"))
        val subject = vm()
        try { assertEquals("Cobrar cuenta", textoDeLaHoja(subject)) } finally { subject.viewModelScope.cancel() }
    }

    // Mesa que este aparato nunca abrió: sin red decía «Cobrar cuenta • $25.00» (el total del plano) y la cuenta «Saldo por confirmar».
    @Test fun `D25 sin red y sin nada guardado tampoco dice el total del plano`() = runTest {
        coEvery { repository.getOrderDetail(venue, "o1") } returns Result.failure(IOException("sin red"))
        val subject = vm()
        try { assertEquals("Cobrar cuenta", textoDeLaHoja(subject)) } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `D25 mientras se lee la cuenta no hay importe, y al llegar si`() = runTest {
        val respuesta = CompletableDeferred<Result<OrderDetail>>()
        coEvery { repository.getOrderDetail(venue, "o1") } coAnswers { respuesta.await() }
        val subject = vm()
        try {
            assertEquals("Cobrar cuenta", textoDeLaHoja(subject))
            respuesta.complete(Result.success(detalle(pagado = 1000, falta = 5000))); advanceUntilIdle()
            assertEquals("Cobrar lo que falta • $50.00", textoCobrarCuenta(cuenta, subject.saldoDeLaHoja.value))
        } finally { subject.viewModelScope.cancel() }
    }

    // Servidor viejo sin `preBillBalance` y con un pago: la cuenta dice «Saldo por confirmar» (la suma a mano se equivoca).
    @Test fun `D25 con un saldo sin confirmar la hoja tampoco da importe`() = runTest {
        coEvery { repository.getOrderDetail(venue, "o1") } returns Result.success(OrderDetail(id = "o1", total = 60.0,
            payments = listOf(OrderDetailPayment(id = "p1", amount = 10.0, status = "COMPLETED"))))
        val subject = vm()
        try { assertEquals("Cobrar cuenta", textoDeLaHoja(subject)) } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `D25 el saldo de otra cuenta o de un total que ya cambio no se usa`() {
        val saldo = SaldoDeLaHoja("o1", totalDelPlanoCents = 6000, pagadoCents = 1000, porPagarCents = 5000)
        assertEquals("Cobrar lo que falta • $50.00", textoCobrarCuenta(cuenta, saldo))
        // Otra caja agregó una ronda: el plano ya dice $80 y lo leído ($50) quedó viejo hasta releer.
        assertEquals("Cobrar cuenta", textoCobrarCuenta(cuenta.copy(total = 80.0), saldo))
        assertEquals("Cobrar cuenta", textoCobrarCuenta(cuenta.copy(id = "o2"), saldo))
        assertEquals("Cobrar cuenta • Por sincronizar", textoCobrarCuenta(cuenta.copy(isProvisional = true), null))
    }

    @Test fun `D25 la hoja y la cuenta sacan el saldo de la misma funcion`() {
        fun fuente(nombre: String): String {
            val path = "src/main/java/com/avoqado/pos/tables/presentation/$nombre"
            return listOf(File(path), File("app/$path")).first { it.exists() }.readText()
        }
        assertTrue(fuente("TableOrderViewModel.kt").contains("?.let(::saldoDelCheque) ?: SaldoDeMesa.soloSaldo(0)"))
        assertTrue(fuente("TablesViewModel.kt").contains("?.let(::saldoDelCheque)"))
    }

    @Test fun `D25 el boton abre la cuenta (su Pagar cobra lo que falta), nunca un cobro por el total`() = runTest {
        val subject = vm()
        try {
            var abrio = 0
            subject.startOrdering(mesa) { abrio++ }
            assertEquals(1, abrio)
            assertEquals(TableSession.Mode.ORDERING, session.current()?.mode)
        } finally { subject.viewModelScope.cancel() }
        val path = "src/main/java/com/avoqado/pos/tables/presentation/TablesScreen.kt"
        val source = listOf(File(path), File("app/$path")).first { it.exists() }.readText()
        val boton = source.substringAfter("text = textoCobrarCuenta(").substringBefore("fullWidth")
        assertTrue(boton.contains("viewModel.startOrdering(table, onReady = onOpenTableOrder)"))
        assertFalse(source.contains("startCobrar("))
    }
}
