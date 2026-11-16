package com.avoqado.pos.tables.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tables.data.TableRoundDraftStore
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * D14 (hz:87f4b879:D14): en la cuenta de la mesa, «Sin conexión… necesita internet» es un estado normal (regla «todo funciona sin
 * red»), no un fallo: se dice en gris con el wifi, nunca con la ✕ roja de [TableOrderViewModel.showError].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableOrderAvisoSinRedTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val repository = mockk<TableServiceRepository>(relaxed = true)
    private val syncOutbox = mockk<SyncOutbox>(relaxed = true)
    private val tableSession = TableSession()
    private val enLinea = MutableStateFlow(false)

    private fun vm(): TableOrderViewModel {
        every { repository.tables } returns MutableStateFlow(emptyList())
        every { repository.ownership } returns MutableStateFlow(TableServiceRepository.TableOwnership())
        every { syncOutbox.acks } returns MutableSharedFlow()
        every { syncOutbox.pendingCount } returns MutableStateFlow(0)
        val connectivity = mockk<ConnectivityMonitor>(relaxed = true)
        every { connectivity.isConnected } returns enLinea
        val printConfig = mockk<PrintConfigRepository>(relaxed = true)
        every { printConfig.getCurrentConfig() } returns PrintConfig()
        tableSession.start(
            TableSession.Active(
                tableId = "t1", tableNumber = "5", areaName = null, orderId = "o1", orderNumber = "ORD-1",
                version = 3, totalCents = 0, mode = TableSession.Mode.ORDERING,
            ),
        )
        return TableOrderViewModel(
            repository = repository, tableSession = tableSession, printConfigRepository = printConfig,
            comandaPrinter = mockk(relaxed = true), printerService = mockk(relaxed = true),
            secureStorage = mockk<SecureStorage>(relaxed = true).also { every { it.venueId } returns "venue-1" },
            syncOutbox = syncOutbox, productsRepository = mockk(relaxed = true), connectivityMonitor = connectivity,
            timeEntryRepository = mockk(relaxed = true), comandaDispatcher = mockk(relaxed = true),
            comandasPendientesStore = mockk(relaxed = true),
            draftStore = TableRoundDraftStore(java.nio.file.Files.createTempDirectory("aviso-sin-red").toFile()),
        )
    }

    /** Lo que pinta la pantalla: gris con el wifi sólo mientras el mensaje es el del aviso de sin red. */
    private fun sinRed(s: TableOrderViewModel) = s.actionMessage.value != null && s.actionMessage.value == s.mensajeSinRed.value

    @Test
    fun `descuento que pide aprobacion sin red - aviso gris con el wifi, no la cruz roja, y no se manda nada`() = runTest {
        val s = vm()
        advanceUntilIdle()
        s.applyDiscount("d-gerente", pideAprobacion = true)
        advanceUntilIdle()

        assertEquals(DESCUENTO_CON_APROBACION_SIN_RED_MESA, s.actionMessage.value)
        assertTrue(sinRed(s))
        assertFalse(s.actionIsError.value)
        coVerify(exactly = 0) { repository.applyOrderDiscount(any(), any(), any(), any()) }
    }

    @Test
    fun `lo que necesita internet en la mesa (requireOnline) - tambien en gris`() = runTest {
        val s = vm()
        advanceUntilIdle()
        s.removeDiscount("od-1")
        advanceUntilIdle()

        assertTrue(s.actionMessage.value!!.startsWith("Sin conexión"))
        assertTrue(sinRed(s))
        assertFalse(s.actionIsError.value)
    }

    @Test
    fun `un fallo de verdad despues del aviso sin red vuelve a ser rojo, y un exito no hereda el gris`() = runTest {
        val s = vm()
        advanceUntilIdle()
        s.removeDiscount("od-1")
        s.showError("No se pudo aplicar el descuento")
        assertTrue(s.actionIsError.value)
        assertFalse(sinRed(s))

        s.removeDiscount("od-1")
        s.showMessage("Descuento aplicado")
        assertFalse(s.actionIsError.value)
        assertFalse(sinRed(s))

        // Al cerrarse el aviso gris, el siguiente mensaje (que muchas acciones escriben directo) no hereda el wifi.
        s.removeDiscount("od-1")
        assertTrue(sinRed(s))
        s.consumeActionMessage()
        assertFalse(sinRed(s))
    }

    @Test
    fun `Codex r2 #2 - un exito que se escribe directo mientras esta el aviso gris no hereda el wifi`() = runTest {
        val s = vm()
        advanceUntilIdle()
        s.removeDiscount("od-1")
        assertTrue(sinRed(s))

        s.clearPending() // escribe «Artículos sin enviar borrados» directo, sin pasar por showMessage
        assertEquals("Artículos sin enviar borrados", s.actionMessage.value)
        assertFalse(sinRed(s))
        assertFalse(s.actionIsError.value)
    }
}
