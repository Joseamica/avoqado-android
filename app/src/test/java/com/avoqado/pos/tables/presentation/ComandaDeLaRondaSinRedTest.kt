package com.avoqado.pos.tables.presentation

import androidx.lifecycle.viewModelScope
import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.FilaDeLaOrden
import com.avoqado.pos.core.data.sync.SyncAck
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.data.sync.TrabajoSinResolver
import com.avoqado.pos.core.domain.printing.ComandaDispatcher
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.pos.data.model.Product
import com.avoqado.pos.printing.data.ComandaPrinter
import com.avoqado.pos.printing.data.PrinterService
import com.avoqado.pos.printing.data.model.PrinterRole
import com.avoqado.pos.printing.data.model.SavedPrinter
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.printing.routing.TicketPlan
import com.avoqado.pos.tables.data.TableRoundDraftStore
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

/**
 * na13e014 (tarjeta de la nube, 17-nov): la comanda de una ronda de mesa SIN red.
 *
 * 1. «Volver a imprimir pedido» sólo mira la cuenta del servidor (`_check`): la ronda que sigue en la cola no está ahí, así que
 *    sin red no se puede reimprimir (en una mesa abierta sin red ni siquiera hay cuenta: «No hay artículos enviados»).
 * 2. Si la app muere después de guardar la ronda y antes de que salga su papel, nadie la vuelve a imprimir: al reabrir la mesa
 *    la ronda se recupera de disco (o ya está en la cola) pero su comanda no sale.
 *
 * VM, sesión y borrador reales, como `MesaSinRedTrasReinicioTest`; la cola es una lista.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ComandaDeLaRondaSinRedTest {
    @get:Rule val main = MainDispatcherRule()
    private val repository = mockk<TableServiceRepository>(relaxed = true)
    private val outbox = mockk<SyncOutbox>(relaxed = true)
    private val session = TableSession()
    private val acks = MutableSharedFlow<SyncAck>(extraBufferCapacity = 4)
    private val storage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns "v-1" }
    private val root = java.nio.file.Files.createTempDirectory("comanda-ronda").toFile()
    private val drafts = TableRoundDraftStore(root)
    private val cola = mutableListOf(FilaDeLaOrden("open", "OPEN_TABLE", "PENDING", null))
    private val productos = mockk<com.avoqado.pos.pos.data.ProductsRepository>(relaxed = true)
    private val despachador = mockk<ComandaDispatcher>(relaxed = true)
    private val impresora = mockk<ComandaPrinter>(relaxed = true)
    private val printerService = mockk<PrinterService>(relaxed = true)
    private val vivos = mutableListOf<TableOrderViewModel>()

    @After fun limpiar() {
        vivos.forEach { it.viewModelScope.cancel() }
        root.deleteRecursively()
    }

    private fun vm(keepSession: Boolean = false, enLinea: Boolean = false): TableOrderViewModel {
        every { repository.tables } returns MutableStateFlow(emptyList())
        every { repository.ownership } returns MutableStateFlow(TableServiceRepository.TableOwnership())
        every { outbox.acks } returns acks
        every { outbox.pendingCount } returns MutableStateFlow(0)
        every { outbox.observarEstado(any()) } answers { flowOf(cola.firstOrNull { it.id == firstArg<String>() }?.estado) }
        coEvery { outbox.enqueue(any(), any(), any(), any(), any(), any()) } answers {
            arg<String>(3).also { cola += FilaDeLaOrden(it, arg(1), "PENDING", null) }
        }
        coEvery { outbox.filasSinResolverDeLaOrden(any(), any()) } answers { cola.toList() }
        coEvery { outbox.trabajoSinResolverDeLaOrden(any(), any()) } answers {
            TrabajoSinResolver(cola.size, cola.count { it.estado == "REJECTED" }, cola.map { it.tipo }.toSet(), cola.sumOf { it.importeCents ?: 0 })
        }
        val config = mockk<PrintConfigRepository>(relaxed = true)
        every { config.getCurrentConfig() } returns PrintConfig()
        every { printerService.getDefaultPrinter(PrinterRole.KITCHEN) } returns SavedPrinter(
            id = "pr1", name = "Cocina", connectionType = "wifi", address = "192.168.1.50", port = 9100, roles = listOf(PrinterRole.KITCHEN.value),
        )
        coEvery { impresora.printComandas(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            ComandaPrinter.Result(attempted = 1, printed = 1, skippedNoPrinter = 0, lastError = null)
        val connectivity = mockk<ConnectivityMonitor>(relaxed = true)
        every { connectivity.isConnected } returns MutableStateFlow(enLinea)
        if (!keepSession) session.start(TableSession.Active("t1", "5", "Patio", "local-1", "LOCAL", 1, 0, TableSession.Mode.ORDERING,
            isProvisional = true, localOrderId = "local-1"))
        return TableOrderViewModel(repository, session, config, impresora, printerService, storage,
            outbox, productos, connectivity, mockk(relaxed = true), despachador, mockk(relaxed = true), draftStore = drafts)
            .also { vivos += it }
    }

    private fun TableOrderViewModel.agregarCafe() =
        addProductWithModifiers(Product(id = "p-cafe", name = "Café", priceValue = 50.0), 1, emptyList(), null)

    @Test fun `P1 sin red Volver a imprimir saca la ronda que sigue en la cola`() = runTest {
        val mesa = vm()
        mesa.agregarCafe(); mesa.sendRound(); advanceUntilIdle()

        mesa.reprintComandas(); advanceUntilIdle()

        assertNotEquals("No hay artículos enviados para reimprimir", mesa.actionMessage.value)
        val planes = mutableListOf<List<TicketPlan>>()
        io.mockk.coVerify { impresora.printComandas(capture(planes), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue("la reimpresión lleva el Café de la ronda en cola: $planes", planes.flatten().toString().contains("Café"))
    }

    /** Codex r1 (R3): una ronda que el SERVIDOR ya rechazó no se vuelve a mandar a cocina desde «Volver a imprimir». */
    @Test fun `una ronda rechazada por el servidor no se reimprime`() = runTest {
        val mesa = vm()
        mesa.agregarCafe(); mesa.sendRound(); advanceUntilIdle()
        val i = cola.indexOfFirst { it.tipo == "ADD_ITEMS" }
        cola[i] = cola[i].copy(estado = "REJECTED")

        mesa.reprintComandas(); advanceUntilIdle()

        io.mockk.coVerify(exactly = 0) { impresora.printComandas(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    /**
     * Codex r4 #1 de «Anular producto»: la caja A guardó una ronda (Café) cuya respuesta se perdió; otra caja ANULÓ el Café antes del
     * reenvío y el SERVIDOR confirmó la ronda sin volver a crearlo. Con la cuenta al día (Flautas, sin Café), «Volver a imprimir»
     * no manda el Café a cocina otra vez.
     */
    @Test fun `una ronda confirmada que la cuenta al dia ya no trae no se reimprime`() = runTest {
        session.start(TableSession.Active("t1", "5", "Patio", "o-1", "ORD", 3, 0, TableSession.Mode.ORDERING))
        val cafe = TableOrderViewModel.PendingLine(
            com.avoqado.pos.pos.data.model.CartItem("c1", com.avoqado.pos.pos.data.model.CartItemType.ProductItem("p-cafe"), "Café", unitPrice = 5000), null)
        drafts.save("v-1", "o-1", emptyList(), mapOf("r1" to listOf(cafe)))
        cola += FilaDeLaOrden("r1", "ADD_ITEMS", "ACKED", null)
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(com.avoqado.pos.tables.data.OrderDetail("o-1", total = 100.0,
            version = 4, items = listOf(com.avoqado.pos.tables.data.OrderDetailItem(id = "line", productName = "Flautas", total = 100.0,
                productId = "p-fl", sentToKitchenAt = "2026-11-20T18:00:00Z"))))
        val mesa = vm(keepSession = true, enLinea = true)
        mesa.loadCheck(); advanceUntilIdle()

        mesa.reprintComandas(); advanceUntilIdle()

        val planes = mutableListOf<List<TicketPlan>>()
        io.mockk.coVerify { impresora.printComandas(capture(planes), any(), any(), any(), any(), any(), any(), any()) }
        val impreso = planes.flatten().toString()
        assertTrue("reimprime lo de la cuenta: $impreso", impreso.contains("Flautas"))
        assertTrue("no reimprime el Café anulado: $impreso", !impreso.contains("Café"))
    }

    /** Regresión de r4 #1: sin la cuenta al día (sin red), una ronda confirmada que no se ve en la cuenta SÍ se reimprime. */
    @Test fun `sin la cuenta al dia una ronda confirmada sigue reimprimiendose`() = runTest {
        val mesa = vm()
        mesa.agregarCafe(); mesa.sendRound(); advanceUntilIdle()
        val i = cola.indexOfFirst { it.tipo == "ADD_ITEMS" }
        cola[i] = cola[i].copy(estado = "ACKED")

        mesa.reprintComandas(); advanceUntilIdle()

        val planes = mutableListOf<List<TicketPlan>>()
        io.mockk.coVerify { impresora.printComandas(capture(planes), any(), any(), any(), any(), any(), any(), any()) }
        assertTrue(planes.flatten().toString().contains("Café"))
    }

    /**
     * La app muere justo después de dejar la ronda en la cola y antes de su papel (la impresora tardaba, o Android la cerró). Se
     * simula con un `enqueue` que guarda la fila y nunca regresa: nada de ese proceso vuelve a correr. Se reabre la mesa en un
     * proceso nuevo: la comanda de la ronda tiene que salir.
     *
     * 🔴 ROJA en `develop` (corrida 44217774109, `despacharEnFondo(…) was not called`): el defecto es real. NO se arregla en este
     * trabajo: el diario que se intentó (dos rondas de Codex, `docs/audit/2026-11-17-nube-android-comandas/codex-r1.md` y
     * `codex-r2.md`) abría copias dobles y un hueco entre el diario y el borrador. Va como bloque propio, con el estado de la
     * comanda DENTRO del borrador (la misma escritura que guarda la ronda). Al arreglarlo, se quita el `@Ignore`.
     */
    @Ignore("na13e014 (2): la comanda de una ronda se pierde si la app muere antes de su papel — bloque propio, ver el KDoc")
    @Test fun `P1 la app muere tras guardar la ronda y antes de su papel - al volver la comanda sale`() = runTest {
        val antes = vm()
        val nuncaRegresa = CompletableDeferred<Unit>()
        coEvery { outbox.enqueue(any(), "ADD_ITEMS", any(), any(), any(), any()) } coAnswers {
            cola += FilaDeLaOrden(arg(3), "ADD_ITEMS", "PENDING", null)
            nuncaRegresa.await(); arg(3)
        }
        antes.agregarCafe(); antes.sendRound(); runCurrent()
        assertTrue("la ronda ya está en la cola", cola.any { it.tipo == "ADD_ITEMS" })

        // Proceso nuevo: la sesión vive en memoria, el borrador y la cola en disco. Se reabre la mesa desde el plano.
        session.clear()
        session.abrirDesdePiso("v-1", TableSession.Active("t1", "5", null, "local-1", "LOCAL", 1, 0, TableSession.Mode.ORDERING,
            isProvisional = true))
        val despues = vm(keepSession = true)
        despues.loadCheck(); advanceUntilIdle()

        verify(atLeast = 1) { despachador.despacharEnFondo(any(), any(), any(), any(), any(), any(), any()) }
    }
}
