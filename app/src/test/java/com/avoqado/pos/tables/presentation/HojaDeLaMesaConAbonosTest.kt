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
 * Con abonos, el botón dice lo que FALTA, del mismo saldo del servidor (`preBillBalance`) que la cuenta pinta en «Por pagar».
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

    @Test fun `D25 con un abono el boton dice lo que falta, no el total`() = runTest {
        coEvery { repository.getOrderDetail(venue, "o1") } returns Result.success(detalle(pagado = 1000, falta = 5000))
        val subject = vm()
        try { assertEquals("Cobrar lo que falta • $50.00", textoDeLaHoja(subject)) } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `D25 sin abonos el boton no cambia`() = runTest {
        coEvery { repository.getOrderDetail(venue, "o1") } returns Result.success(detalle(pagado = 0, falta = 6000))
        val subject = vm()
        try { assertEquals("Cobrar cuenta • $60.00", textoDeLaHoja(subject)) } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `D25 sin red usa la ultima lectura guardada de la cuenta, la misma que la cuenta pinta`() = runTest {
        drafts.save(venue, "o1", emptyList(), emptyMap(), cachedCheck = detalle(pagado = 1000, falta = 5000))
        coEvery { repository.getOrderDetail(venue, "o1") } returns Result.failure(IOException("sin red"))
        val subject = vm()
        try { assertEquals("Cobrar lo que falta • $50.00", textoDeLaHoja(subject)) } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `D25 sin red y con un abono de este aparato sin subir no se afirma un importe`() = runTest {
        drafts.save(venue, "o1", emptyList(), emptyMap(), cachedCheck = detalle(pagado = 0, falta = 6000))
        coEvery { repository.getOrderDetail(venue, "o1") } returns Result.failure(IOException("sin red"))
        val subject = vm()
        try {
            coEvery { cola.efectivoDelPedido("o1") } returns EfectivoDelPedido.EN_COLA
            assertEquals("Cobrar cuenta", textoDeLaHoja(subject))
            coEvery { cola.efectivoDelPedido("o1") } returns EfectivoDelPedido.NINGUNO
            coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } returns TrabajoSinResolver(1, 0, setOf("PAY_CASH"), 0)
            assertEquals("Cobrar cuenta", textoDeLaHoja(subject))
        } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `D25 sin red y sin nada guardado ni en cola sigue el total del plano`() = runTest {
        coEvery { repository.getOrderDetail(venue, "o1") } returns Result.failure(IOException("sin red"))
        val subject = vm()
        try { assertEquals("Cobrar cuenta • $60.00", textoDeLaHoja(subject)) } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `D25 con red la lectura del servidor manda aunque haya algo en cola, como en la cuenta`() = runTest {
        coEvery { repository.getOrderDetail(venue, "o1") } returns Result.success(detalle(pagado = 1000, falta = 5000))
        coEvery { cola.efectivoDelPedido("o1") } returns EfectivoDelPedido.EN_COLA
        val subject = vm()
        try { assertEquals("Cobrar lo que falta • $50.00", textoDeLaHoja(subject)) } finally { subject.viewModelScope.cancel() }
    }

    @Test fun `D25 el saldo de otra cuenta o de un total que ya cambio no se usa`() {
        val saldo = SaldoDeLaHoja("o1", totalDelPlanoCents = 6000, pagadoCents = 1000, porPagarCents = 5000)
        assertEquals("Cobrar lo que falta • $50.00", textoCobrarCuenta(cuenta, saldo))
        // Otra tablet agregó una ronda: el plano ya dice $80 y lo leído ($50) quedó viejo hasta releer.
        assertEquals("Cobrar cuenta • $80.00", textoCobrarCuenta(cuenta.copy(total = 80.0), saldo))
        assertEquals("Cobrar cuenta • $60.00", textoCobrarCuenta(cuenta.copy(id = "o2"), saldo))
        assertEquals("Cobrar cuenta • Por sincronizar", textoCobrarCuenta(cuenta.copy(isProvisional = true), null))
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
