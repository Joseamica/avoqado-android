package com.avoqado.pos.tables.presentation

import com.avoqado.pos.MainDispatcherRule
import com.avoqado.pos.core.data.local.SecureStorage
import com.avoqado.pos.core.data.sync.SyncOutbox
import com.avoqado.pos.core.util.ConnectivityMonitor
import com.avoqado.pos.printing.routing.PrintConfig
import com.avoqado.pos.printing.routing.PrintConfigRepository
import com.avoqado.pos.tables.data.AddOrderItemRequest
import com.avoqado.pos.tables.data.OrderDetail
import com.avoqado.pos.tables.data.TableServiceRepository
import com.avoqado.pos.tables.data.TableSession
import com.avoqado.pos.tables.data.UpdatedOrder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

/**
 * La ronda de mesa con llave (spec 2026-09-27 §5): la ronda en línea y su red de seguridad llevan las MISMAS llaves
 * `sync:<roundKey>:<idx>`; el intent es UNO (id = `roundKey`) y nunca se encola una segunda copia. Así, si la respuesta
 * se pierde y la ronda se reproduce, el servidor la deduplica y arma UNA comanda `round:<roundKey>`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TableOrderRondaConLlaveTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val repository = mockk<TableServiceRepository>(relaxed = true)
    private val syncOutbox = mockk<SyncOutbox>(relaxed = true)
    private val tableSession = TableSession()
    private val payloads = mutableListOf<JsonObject>()
    private val ids = mutableListOf<String>()
    private val enviados = mutableListOf<List<AddOrderItemRequest>>()

    private fun vm(): TableOrderViewModel {
        every { repository.tables } returns MutableStateFlow(emptyList())
        every { repository.ownership } returns MutableStateFlow(TableServiceRepository.TableOwnership())
        coEvery { repository.getOrderDetail(any(), any()) } returns Result.success(OrderDetail(id = "o1", orderNumber = "ORD-1", items = emptyList()))
        every { syncOutbox.acks } returns MutableSharedFlow()
        every { syncOutbox.pendingCount } returns MutableStateFlow(0)
        coEvery { syncOutbox.enqueue(any(), any(), capture(payloads), capture(ids), any()) } answers { arg(3) }
        val secureStorage = mockk<SecureStorage>(relaxed = true)
        every { secureStorage.venueId } returns "venue-1"
        val connectivity = mockk<ConnectivityMonitor>(relaxed = true)
        every { connectivity.isConnected } returns MutableStateFlow(true)
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
            comandaPrinter = mockk(relaxed = true), printerService = mockk(relaxed = true), secureStorage = secureStorage,
            syncOutbox = syncOutbox, productsRepository = mockk(relaxed = true), connectivityMonitor = connectivity,
            timeEntryRepository = mockk(relaxed = true),
        ).apply {
            addCustomAmount("Pan", 3000)
            addCustomAmount("Café", 4500)
        }
    }

    private fun llavesDe(payload: JsonObject) = payload["items"]!!.jsonArray.map { it.jsonObject["externalId"]!!.jsonPrimitive.content }

    @Test
    fun `P1 la ronda en linea lleva las MISMAS llaves que su red de seguridad y con exito la descarta`() = runTest {
        coEvery { repository.addRound(any(), any(), capture(enviados), any()) } returns Result.success(UpdatedOrder(id = "o1", version = 4))
        var ok: Boolean? = null
        val vm = vm()

        vm.sendRound { exito, _ -> ok = exito }
        advanceUntilIdle()

        val llave = ids.single()
        assertEquals(listOf("sync:$llave:0", "sync:$llave:1"), llavesDe(payloads.single()))
        assertEquals(listOf("sync:$llave:0", "sync:$llave:1"), enviados.single().map { it.externalId })
        coVerifyOrder {
            syncOutbox.enqueue("venue-1", "ADD_ITEMS", any(), llave, true)
            repository.addRound("venue-1", "o1", any(), 3)
            syncOutbox.descartar("venue-1", llave)
        }
        coVerify(exactly = 0) { syncOutbox.soltar(any(), any()) }
        assertEquals(true, ok)
    }

    @Test
    fun `P1 sin red la ronda NO se encola dos veces - se suelta la misma y las lineas quedan con su llave`() = runTest {
        coEvery { repository.addRound(any(), any(), any(), any()) } returns Result.failure(IOException("sin red"))
        var ok: Boolean? = null
        val vm = vm()

        vm.sendRound { exito, _ -> ok = exito }
        advanceUntilIdle()

        val llave = ids.single()
        coVerify(exactly = 1) { syncOutbox.enqueue(any(), any(), any(), any(), any()) }
        coVerify { syncOutbox.soltar("venue-1", llave) }
        coVerify(exactly = 0) { syncOutbox.descartar(any(), any()) }
        assertEquals(listOf("sync:$llave:0", "sync:$llave:1"), vm.queued.value.map { it.externalId })
        assertEquals(true, ok)
    }

    @Test
    fun `un rechazo del servidor descarta la red de seguridad y deja las lineas para reintentar`() = runTest {
        coEvery { repository.addRound(any(), any(), any(), any()) } returns
            Result.failure(HttpException(Response.error<Any>(409, "{}".toResponseBody(null))))
        var ok: Boolean? = null
        val vm = vm()

        vm.sendRound { exito, _ -> ok = exito }
        advanceUntilIdle()

        coVerify { syncOutbox.descartar("venue-1", ids.single()) }
        coVerify(exactly = 0) { syncOutbox.soltar(any(), any()) }
        assertEquals(2, vm.pending.value.size)
        assertEquals(false, ok)
    }
}
